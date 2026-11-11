# QA orchestrator for Schedule-Manager (isolated Docker SQL Server, never touches local SQL Server).
#
# Stages (run in order with -Stage All, or one at a time):
#   Env     : start Docker container sm-qa-mssql, create login + isolated QA databases, write
#             .local-test/application-testdb.properties and .local-test/qa/run-context.json
#   Java    : mvn unit/controller/IT tests (+ new qa tests) with JaCoCo coverage
#   Live    : package jar, start backend on :8082 with JaCoCo agent, run Node/PowerShell suites
#   Backup  : backup/restore/CHECKDB evidence inside the container
#
# Usage (repository root, PowerShell 5.1+):
#   powershell -ExecutionPolicy Bypass -File tests\qa\run-qa.ps1                # everything
#   powershell -ExecutionPolicy Bypass -File tests\qa\run-qa.ps1 -Stage Live    # only live suites
#   ... -Offline   : Maven offline mode
#   ... -NewDatabases : force new QA databases (new timestamp)
param(
    [ValidateSet('All', 'Env', 'Java', 'Live', 'Backup')][string]$Stage = 'All',
    [switch]$Offline,
    [switch]$NewDatabases,
    [string]$JacocoVersion = '0.8.14'
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Set-Location $root
if (-not (Test-Path (Join-Path $root 'pom.xml'))) { throw 'Run from the Schedule-Manager repository.' }

$qaDir = Join-Path $root '.local-test\qa'
$logDir = Join-Path $qaDir 'logs'
New-Item -ItemType Directory -Force $qaDir, $logDir | Out-Null
$utf8 = [Text.UTF8Encoding]::new($false)

function Write-Step($text) { Write-Host ''; Write-Host "=== $text ===" -ForegroundColor Cyan }

# ---------- secrets (.local-test is git-ignored) ----------
$secretFile = Join-Path $root '.local-test\qa.env'
function New-Secret([int]$n) {
    $chars = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789'.ToCharArray()
    -join (1..$n | ForEach-Object { $chars[(Get-Random -Maximum $chars.Length)] })
}
if (-not (Test-Path $secretFile)) {
    $lines = @(
        "SM_QA_SA_PASSWORD=Qa#$(New-Secret 20)9a",
        "SM_QA_APP_PASSWORD=App#$(New-Secret 20)7b",
        "SM_QA_JWT_SECRET=$(New-Secret 64)",
        "SM_QA_STAMP=$(Get-Date -Format 'yyyyMMdd_HHmmss')",
        'SM_QA_SQL_PORT=14333',
        'SM_QA_CONTAINER=sm-qa-mssql')
    [IO.File]::WriteAllLines($secretFile, $lines, $utf8)
}
$cfg = @{}
Get-Content $secretFile | ForEach-Object { if ($_ -match '^([^=]+)=(.*)$') { $cfg[$matches[1]] = $matches[2] } }
if ($NewDatabases) {
    $cfg['SM_QA_STAMP'] = Get-Date -Format 'yyyyMMdd_HHmmss'
    [IO.File]::WriteAllLines($secretFile, ($cfg.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" }), $utf8)
}
$sqlServer = "tcp:localhost,$($cfg.SM_QA_SQL_PORT)"
$mainDb = "ScheduleManager_MocA_Test_QA_$($cfg.SM_QA_STAMP)"
$issue5Db = "ScheduleManager_MocA_Test_Issue5_$($cfg.SM_QA_STAMP)"
$appLogin = 'schedulemanagerproject'

function Invoke-Sql([string]$query, [string]$database = 'master') {
    $out = & sqlcmd -S $sqlServer -U sa -P $cfg.SM_QA_SA_PASSWORD -C -b -d $database -h -1 -W -Q "SET NOCOUNT ON; $query" 2>&1
    if ($LASTEXITCODE -ne 0) { throw "sqlcmd failed: $($out | Out-String)" }
    return $out
}

function Start-QaDatabase {
    Write-Step 'Docker SQL Server'
    docker info --format '{{.ServerVersion}}' 2>$null | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Docker daemon is not running. Start Docker Desktop first.' }
    $state = docker ps -a --filter "name=^/$($cfg.SM_QA_CONTAINER)$" --format '{{.State}}'
    if (-not $state) {
        docker run -d --name $cfg.SM_QA_CONTAINER -e 'ACCEPT_EULA=Y' -e "MSSQL_SA_PASSWORD=$($cfg.SM_QA_SA_PASSWORD)" `
            -e 'MSSQL_PID=Developer' -p "$($cfg.SM_QA_SQL_PORT):1433" mcr.microsoft.com/mssql/server:2022-latest | Out-Null
    } elseif ($state -ne 'running') { docker start $cfg.SM_QA_CONTAINER | Out-Null }
    $deadline = (Get-Date).AddMinutes(3)
    while ($true) {
        & sqlcmd -S $sqlServer -U sa -P $cfg.SM_QA_SA_PASSWORD -C -l 5 -Q 'SELECT 1' 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) { break }
        if ((Get-Date) -gt $deadline) { throw 'SQL Server container did not accept logins within 3 minutes' }
        Start-Sleep 3
    }
    $pw = $cfg.SM_QA_APP_PASSWORD.Replace("'", "''")
    Invoke-Sql "IF SUSER_ID(N'$appLogin') IS NULL CREATE LOGIN [$appLogin] WITH PASSWORD=N'$pw', CHECK_POLICY=OFF; ELSE ALTER LOGIN [$appLogin] WITH PASSWORD=N'$pw';" | Out-Null
    foreach ($db in @($mainDb, $issue5Db)) {
        Invoke-Sql "IF DB_ID(N'$db') IS NULL CREATE DATABASE [$db];" | Out-Null
        Invoke-Sql "IF USER_ID(N'$appLogin') IS NULL CREATE USER [$appLogin] FOR LOGIN [$appLogin]; ALTER ROLE db_owner ADD MEMBER [$appLogin];" $db | Out-Null
        Write-Host "Database ready: $db"
    }
    $url = "jdbc:sqlserver://localhost:$($cfg.SM_QA_SQL_PORT);databaseName=$mainDb;encrypt=true;trustServerCertificate=true"
    $props = @(
        "spring.datasource.url=$url",
        "spring.datasource.username=$appLogin",
        "spring.datasource.password=$($cfg.SM_QA_APP_PASSWORD)",
        "app.jwt.secret=$($cfg.SM_QA_JWT_SECRET)",
        'server.port=8082',
        'app.scheduling.enabled=false',
        'logging.level.root=WARN')
    [IO.File]::WriteAllLines((Join-Path $root '.local-test\application-testdb.properties'), $props, $utf8)
    $context = [ordered]@{ generatedAt = (Get-Date).ToString('o'); sqlServer = $sqlServer; image = 'mcr.microsoft.com/mssql/server:2022-latest'
        mainDatabase = $mainDb; issue5Database = $issue5Db; appLogin = $appLogin; backendUrl = 'http://localhost:8082' }
    [IO.File]::WriteAllText((Join-Path $qaDir 'run-context.json'), ($context | ConvertTo-Json), $utf8)
}

function Get-MavenArgs {
    $a = @('-B', '-ntp')
    if ($Offline) { $a += '-o' }
    return , $a
}

function Get-JacocoTools {
    $tools = Join-Path $root '.local-test\tools'
    New-Item -ItemType Directory -Force $tools | Out-Null
    $agent = Join-Path $tools 'jacocoagent.jar'; $cli = Join-Path $tools 'jacococli.jar'
    $mvn = Get-MavenArgs
    if (-not (Test-Path $agent)) {
        & mvn @mvn dependency:copy "-Dartifact=org.jacoco:org.jacoco.agent:$($JacocoVersion):jar:runtime" "-DoutputDirectory=$tools" '-Dmdep.stripVersion=true' | Out-Null
        Move-Item (Join-Path $tools 'org.jacoco.agent-runtime.jar') $agent -Force
    }
    if (-not (Test-Path $cli)) {
        & mvn @mvn dependency:copy "-Dartifact=org.jacoco:org.jacoco.cli:$($JacocoVersion):jar:nodeps" "-DoutputDirectory=$tools" '-Dmdep.stripVersion=true' | Out-Null
        Move-Item (Join-Path $tools 'org.jacoco.cli-nodeps.jar') $cli -Force
    }
    return @{ Agent = $agent; Cli = $cli }
}

function Set-ItEnvironment {
    $env:SM_RUN_SQL_TESTS = 'true'
    $env:SM_IT_DB_URL = "jdbc:sqlserver://localhost:$($cfg.SM_QA_SQL_PORT);databaseName=$issue5Db;encrypt=true;trustServerCertificate=true"
    $env:SM_IT_DB_USER = $appLogin
    $env:SM_IT_DB_PASSWORD = $cfg.SM_QA_APP_PASSWORD
}

$results = [ordered]@{}
$resultsFile = Join-Path $qaDir 'results.json'
if (Test-Path $resultsFile) {
    (Get-Content $resultsFile -Raw | ConvertFrom-Json).PSObject.Properties | ForEach-Object { $results[$_.Name] = $_.Value }
}
function Save-Result($name, $exitCode, $seconds, $log) {
    $results[$name] = [ordered]@{ exitCode = $exitCode; passed = ($exitCode -eq 0); seconds = [math]::Round($seconds, 1); log = $log; at = (Get-Date).ToString('o') }
    [IO.File]::WriteAllText($resultsFile, ($results | ConvertTo-Json -Depth 5), $utf8)
}
function Invoke-Logged($name, [scriptblock]$block) {
    $log = Join-Path $logDir "$name.log"
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $global:LASTEXITCODE = 0
    # PowerShell 5.1 turns native stderr into ErrorRecords; keep them as plain text instead of aborting the step.
    $previous = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try { & $block 2>&1 | ForEach-Object { "$_" } | Out-File -Encoding utf8 $log; $code = $LASTEXITCODE } catch { $_ | Out-File -Append -Encoding utf8 $log; $code = 1 } finally { $ErrorActionPreference = $previous }
    Save-Result $name $code $sw.Elapsed.TotalSeconds $log
    $status = if ($code -eq 0) { 'PASS' } else { "FAIL (exit $code)" }
    Write-Host ("{0,-28} {1,-14} {2,7:N1}s  {3}" -f $name, $status, $sw.Elapsed.TotalSeconds, $log)
}

function Invoke-JavaStage {
    Write-Step 'Java tests + JaCoCo'
    Set-ItEnvironment
    $mvn = Get-MavenArgs
    Invoke-Logged 'maven-tests' {
        & mvn @mvn '-Dmoc.build.directory=.local-test/build' '-Dmaven.test.failure.ignore=true' `
            '-Dtest=*Test,IntegratedMocAIT,IssueFiveFlowIT,DatabaseSchemaIT' '-Dsurefire.failIfNoSpecifiedTests=false' `
            "org.jacoco:jacoco-maven-plugin:$($JacocoVersion):prepare-agent" 'test' `
            "org.jacoco:jacoco-maven-plugin:$($JacocoVersion):report"
    }
}

$backend = $null
function Start-Backend($tools, [string]$name = 'backend') {
    $jar = Join-Path $root '.local-test\build\ManageSchedule-1.0-SNAPSHOT.jar'
    $outLog = Join-Path $logDir "$name.out.log"; $errLog = Join-Path $logDir "$name.err.log"
    $javaArgs = @('-jar', $jar, '--spring.profiles.active=sqlserver',
        '--spring.config.additional-location=file:.local-test/application-testdb.properties',
        '--app.scheduling.enabled=true', '--app.events.poll-ms=500', '--logging.level.root=INFO')
    if ($tools) { $javaArgs = @("-javaagent:$($tools.Agent)=output=tcpserver,address=127.0.0.1,port=6300,includes=org.dacnpm.*") + $javaArgs }
    $p = Start-Process java -ArgumentList $javaArgs -WorkingDirectory $root -RedirectStandardOutput $outLog -RedirectStandardError $errLog -PassThru -WindowStyle Hidden
    $deadline = (Get-Date).AddMinutes(3)
    while ($true) {
        try { if ((Invoke-WebRequest 'http://localhost:8082/api/health' -UseBasicParsing -TimeoutSec 3).StatusCode -eq 200) { break } } catch {}
        if ($p.HasExited) { throw "Backend exited early, see $errLog / $outLog" }
        if ((Get-Date) -gt $deadline) { throw 'Backend did not become healthy in 3 minutes' }
        Start-Sleep 2
    }
    Write-Host "Backend PID $($p.Id) healthy on http://localhost:8082"
    return $p
}

function Stop-Backend($p, $tools, [string]$execName) {
    if (-not $p) { return }
    if ($tools) {
        & java -jar $tools.Cli dump --address 127.0.0.1 --port 6300 --destfile (Join-Path $root ".local-test\qa\$execName") --quiet 2>&1 | Out-Null
    }
    # Start-Process may return the Oracle javapath shim; stop the real JVM that owns port 8082 as well.
    foreach ($l in @(Get-NetTCPConnection -State Listen -LocalPort 8082 -ErrorAction SilentlyContinue)) { Stop-Process -Id $l.OwningProcess -Force -ErrorAction SilentlyContinue }
    if (-not $p.HasExited) { Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue }
    $deadline = (Get-Date).AddSeconds(30)
    while ((Get-NetTCPConnection -State Listen -LocalPort 8082 -ErrorAction SilentlyContinue) -and (Get-Date) -lt $deadline) { Start-Sleep 1 }
}

function Invoke-LiveStage {
    Write-Step 'Package backend'
    $mvn = Get-MavenArgs
    Invoke-Logged 'maven-package' { & mvn @mvn '-Dmoc.build.directory=.local-test/build' -DskipTests package }
    if ($results['maven-package'].exitCode -ne 0) { throw 'Packaging failed' }
    foreach ($port in 8082, 8083) {
        if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) { throw "Port $port is already in use" }
    }
    $tools = Get-JacocoTools
    Write-Step 'Live suites on http://localhost:8082'
    $p = Start-Backend $tools
    $env:SM_TEST_URL = 'http://localhost:8082'
    $env:SM_TEST_ADMIN = 'admin'
    $env:SM_TEST_PASSWORD = 'Admin@123'
    $env:SM_QA_JWT_SECRET = $cfg.SM_QA_JWT_SECRET
    try {
        Invoke-Logged 'node-moc-a-http' { & node tests/moc-a-http.mjs }
        Invoke-Logged 'node-moc-a-browser' { & node tests/moc-a-browser.mjs }
        Invoke-Logged 'node-frontend-host' { & node tests/frontend-host.mjs }
        $periodId = 'qa_attendance_period'
        $fixtureFile = Join-Path $root '.local-test\browser-fixture.json'
        if (Test-Path $fixtureFile) { $periodId = (Get-Content $fixtureFile -Raw | ConvertFrom-Json).livePeriodId }
        Invoke-Logged 'ps-attendance-api' { & powershell -NoProfile -ExecutionPolicy Bypass -File tests/attendance-api.ps1 -Base 'http://localhost:8082' -PeriodId $periodId }
        Invoke-Logged 'node-qa-api-full' { & node tests/qa/api-full.mjs }
        Invoke-Logged 'node-qa-ui-full' { & node tests/qa/ui-full.mjs }
    } finally {
        Stop-Backend $p $tools 'jacoco-e2e.exec'
    }
    Write-Step 'Restart on the same database (Flyway must report schema up to date)'
    $p2 = Start-Backend $null 'backend-restart'
    Stop-Backend $p2 $null ''
    $restartLog = Get-Content (Join-Path $logDir 'backend-restart.out.log') -Raw
    $upToDate = $restartLog -match 'Schema .* is up to date'
    Save-Result 'flyway-restart-idempotent' ([int](-not $upToDate)) 0 (Join-Path $logDir 'backend-restart.out.log')
    Write-Host "Flyway up-to-date on restart: $upToDate"
    $e2eExec = Join-Path $qaDir 'jacoco-e2e.exec'
    if (Test-Path $e2eExec) {
        Invoke-Logged 'jacoco-e2e-report' {
            & java -jar $tools.Cli report $e2eExec --classfiles .local-test/build/classes --sourcefiles src/main/java `
                --csv .local-test/qa/coverage-e2e.csv --html .local-test/qa/coverage-e2e
        }
        $unitExec = Join-Path $root '.local-test\build\jacoco.exec'
        if (Test-Path $unitExec) {
            Invoke-Logged 'jacoco-merged-report' {
                & java -jar $tools.Cli merge $unitExec $e2eExec --destfile .local-test/qa/jacoco-merged.exec
                & java -jar $tools.Cli report .local-test/qa/jacoco-merged.exec --classfiles .local-test/build/classes --sourcefiles src/main/java `
                    --csv .local-test/qa/coverage-merged.csv --html .local-test/qa/coverage-merged
            }
        }
    }
}

function Invoke-BackupStage {
    Write-Step 'Backup / restore'
    Invoke-Logged 'db-backup-restore' { & powershell -NoProfile -ExecutionPolicy Bypass -File tests/qa/db-backup-restore.ps1 -SourceDatabase $mainDb }
}

Start-QaDatabase
switch ($Stage) {
    'Env' { }
    'Java' { Invoke-JavaStage }
    'Live' { Invoke-LiveStage }
    'Backup' { Invoke-BackupStage }
    'All' { Invoke-JavaStage; Invoke-LiveStage; Invoke-BackupStage }
}
Write-Step 'Summary'
$results.GetEnumerator() | ForEach-Object { '{0,-28} {1}' -f $_.Key, $(if ($_.Value.passed) { 'PASS' } else { 'FAIL' }) }
Write-Host "Results: $resultsFile"
