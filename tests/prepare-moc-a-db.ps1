param([string]$TestDatabase = 'ScheduleManager_MocA_Test_20261031', [string]$SqlServer = '.\SQLEXPRESS')
$ErrorActionPreference = 'Stop'
if ($TestDatabase -notmatch '^ScheduleManager_MocA_Test_[A-Za-z0-9_]+$') { throw 'Only a dedicated MocA test database is allowed' }
New-Item -ItemType Directory -Force .local-test | Out-Null
$settings = @{}
Get-Content -LiteralPath '.env' | ForEach-Object {
    if ($_ -match '^\s*([^#=]+?)\s*=\s*(.*)$') { $settings[$matches[1].Trim()] = $matches[2].Trim() }
}
$loginName = $settings['spring.datasource.username']
$loginName = if($loginName) { $loginName } else { $settings['DB_USERNAME'] }
if (-not $loginName) { throw 'Missing datasource username in .env' }
$escapedLogin = $loginName.Replace(']', ']]')
$sql = "IF DB_ID(N'$testDatabase') IS NULL CREATE DATABASE [$testDatabase];"
& sqlcmd -S $SqlServer -E -C -b -Q $sql
if ($LASTEXITCODE -ne 0) { throw 'Cannot create isolated test database' }
$sql = "IF IS_SRVROLEMEMBER('sysadmin',N'"+$loginName.Replace("'", "''")+"')<>1 BEGIN IF USER_ID(N'" + $loginName.Replace("'", "''") + "') IS NULL CREATE USER [$escapedLogin] FOR LOGIN [$escapedLogin]; ALTER ROLE db_owner ADD MEMBER [$escapedLogin]; END"
& sqlcmd -S $SqlServer -E -C -b -d $testDatabase -Q $sql
if ($LASTEXITCODE -ne 0) { throw 'Cannot grant access to isolated test database' }
$testUrl = $settings['spring.datasource.url']
if (-not $testUrl) {
    $dbHost = if($settings['DB_HOST']) { $settings['DB_HOST'] } else { 'localhost' }
    $dbPort = if($settings['DB_PORT']) { $settings['DB_PORT'] } else { '1433' }
    $testUrl="jdbc:sqlserver://${dbHost}:${dbPort};encrypt=true;trustServerCertificate=true"
}
if ($testUrl -match '(?i)databaseName=') { $testUrl = $testUrl -replace '(?i)databaseName=[^;]*', "databaseName=$testDatabase" }
else { $testUrl += ";databaseName=$testDatabase" }
$dbPassword = if($settings['spring.datasource.password']) { $settings['spring.datasource.password'] } else { $settings['DB_PASSWORD'] }
$config = @(
    "spring.datasource.url=$testUrl",
    "spring.datasource.username=$loginName",
    "spring.datasource.password=$dbPassword",
    'server.port=8082',
    'app.scheduling.enabled=false',
    'logging.level.root=WARN'
)
[System.IO.File]::WriteAllLines((Join-Path (Get-Location) '.local-test/application-testdb.properties'), $config, [System.Text.UTF8Encoding]::new($false))
Write-Output "Isolated database ready: $testDatabase"
