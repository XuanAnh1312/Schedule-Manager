param([switch]$Offline)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
if (Get-NetTCPConnection -LocalPort 8082 -State Listen -ErrorAction SilentlyContinue) {
    throw 'Port 8082 is busy. Stop the previous QA backend before starting a new isolated run.'
}
New-Item -ItemType Directory -Force .local-test | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd_HHmmss_fff'
$backendProcess = $null
$previousSqlFlag=$env:SM_RUN_SQL_TESTS
$previousItUrl=$env:SM_IT_DB_URL
$previousItUser=$env:SM_IT_DB_USER
$previousItPassword=$env:SM_IT_DB_PASSWORD
try {
    # Never drop databases or reuse a production database. Keep old evidence for diagnosis.
    & "$PSScriptRoot/prepare-moc-a-db.ps1" -TestDatabase "ScheduleManager_MocA_Test_Issue5_$stamp"
    Copy-Item .local-test/application-testdb.properties .local-test/issue5-db.properties
    $settings=@{}
    Get-Content .local-test/issue5-db.properties | ForEach-Object {
        if ($_ -match '^([^#=]+)=(.*)$') { $settings[$matches[1]]=$matches[2] }
    }
    $env:SM_IT_DB_URL=$settings['spring.datasource.url']
    $env:SM_IT_DB_USER=$settings['spring.datasource.username']
    $env:SM_IT_DB_PASSWORD=$settings['spring.datasource.password']
    & "$PSScriptRoot/prepare-moc-a-db.ps1" -TestDatabase "ScheduleManager_MocA_Test_$stamp"
    $env:SM_RUN_SQL_TESTS='true'
    $mavenCommand='mvn -B package -Dmoc.build.directory=.local-test/build -Dtest=*Test,IntegratedMocAIT,IssueFiveFlowIT'
    if($Offline){$mavenCommand='mvn -o -B package -Dmoc.build.directory=.local-test/build -Dtest=*Test,IntegratedMocAIT,IssueFiveFlowIT'}
    & cmd.exe /d /c "$mavenCommand > .local-test/moc-a-current-tests.log 2>&1"
    if($LASTEXITCODE -ne 0){throw 'Maven failed: see .local-test/moc-a-current-tests.log'}
    $backendProcess=Start-Process java -ArgumentList @('-jar','.local-test/build/ManageSchedule-1.0-SNAPSHOT.jar','--spring.config.additional-location=file:.local-test/application-testdb.properties','--app.scheduling.enabled=true','--app.events.poll-ms=500') -WindowStyle Hidden -PassThru -RedirectStandardOutput .local-test/moc-a-server.log -RedirectStandardError .local-test/moc-a-server-error.log
    & cmd.exe /d /c 'node tests/moc-a-http.mjs > .local-test/moc-a-http-current.log 2>&1'
    if($LASTEXITCODE -ne 0){throw 'HTTP tests failed: see .local-test/moc-a-http-current.log'}
    & cmd.exe /d /c 'node tests/moc-a-browser.mjs > .local-test/moc-a-browser-current.log 2>&1'
    if($LASTEXITCODE -ne 0){throw 'Browser tests failed: see .local-test/moc-a-browser-current.log'}
    & cmd.exe /d /c 'node tests/frontend-host.mjs > .local-test/frontend-host.log 2>&1'
    if($LASTEXITCODE -ne 0){throw 'Frontend host tests failed: see .local-test/frontend-host.log'}
    $manifest=@()
    foreach($path in (& rg --files src js css tests HUONG_DAN | Sort-Object)) {
        if($path.EndsWith('.md')){continue}
        $manifest+=@{path=$path;sha256=(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash}
    }
    foreach($path in @('pom.xml','workspace.html','attendance.html','index.html','package-lock.json')) {
        $manifest+=@{path=$path;sha256=(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash}
    }
    @{at=(Get-Date).ToUniversalTime().ToString('o');baseCommit=(& git rev-parse HEAD);jarSha256=(Get-FileHash .local-test/build/ManageSchedule-1.0-SNAPSHOT.jar -Algorithm SHA256).Hash;sourceFiles=$manifest;automatedResult='PASS';windowsDisplayScale='Manual acceptance still required unless separately recorded'} |
        ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 .local-test/moc-a-run-manifest.json
    Write-Output 'PASS automated SQL Server, HTTP, browser suites. See .local-test/moc-a-run-manifest.json. Windows display scaling is a separate manual acceptance step.'
} finally {
    if($backendProcess -and -not $backendProcess.HasExited){Stop-Process -Id $backendProcess.Id}
    $env:SM_RUN_SQL_TESTS=$previousSqlFlag
    $env:SM_IT_DB_URL=$previousItUrl
    $env:SM_IT_DB_USER=$previousItUser
    $env:SM_IT_DB_PASSWORD=$previousItPassword
}
