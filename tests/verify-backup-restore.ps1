param(
    [Parameter(Mandatory=$true)][string]$SourceDatabase,
    [string]$SqlServer='.\SQLEXPRESS'
)
$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
if($SourceDatabase -notmatch '^ScheduleManager_MocA_Test_[A-Za-z0-9_]+$') { throw 'Only a dedicated MocA QA database is allowed.' }
$stamp=Get-Date -Format 'yyyyMMdd_HHmmss_fff'
$restoreDatabase="ScheduleManager_MocA_Test_Restore_$stamp"
$backupDirectory=Join-Path (Get-Location) '.local-test/backups'
New-Item -ItemType Directory -Force $backupDirectory | Out-Null
$backupPath=Join-Path $backupDirectory "$SourceDatabase-$stamp.bak"
$connection=[System.Data.SqlClient.SqlConnection]::new("Server=$SqlServer;Database=master;Integrated Security=True;Encrypt=True;TrustServerCertificate=True")
$connection.Open()
function Query([string]$sql) {
    $command=$connection.CreateCommand();$command.CommandText=$sql;$command.CommandTimeout=120
    $table=[System.Data.DataTable]::new();$reader=$command.ExecuteReader()
    try{$table.Load($reader)}finally{$reader.Dispose();$command.Dispose()}
    return ,$table
}
try {
    $files=Query "SELECT name,type_desc FROM [$SourceDatabase].sys.database_files"
    $escapedBackup=$backupPath.Replace("'","''")
    $null=Query "BACKUP DATABASE [$SourceDatabase] TO DISK=N'$escapedBackup' WITH COPY_ONLY,CHECKSUM"
    $null=Query "RESTORE VERIFYONLY FROM DISK=N'$escapedBackup' WITH CHECKSUM"
    $moves=@()
    foreach($file in $files.Rows) {
        $extension=if($file.type_desc -eq 'LOG'){'ldf'}else{'mdf'}
        $target=Join-Path $backupDirectory "$restoreDatabase-$($moves.Count).$extension"
        $moves+="MOVE N'"+$file.name.Replace("'","''")+"' TO N'"+$target.Replace("'","''")+"'"
    }
    $null=Query ("RESTORE DATABASE [$restoreDatabase] FROM DISK=N'$escapedBackup' WITH CHECKSUM,"+($moves -join ','))
    $checks=Query "DBCC CHECKDB ([$restoreDatabase]) WITH NO_INFOMSGS,ALL_ERRORMSGS"
    if($checks.Rows.Count -ne 0){throw 'DBCC CHECKDB returned integrity errors.'}
    $counts=@{}
    foreach($table in @('flyway_schema_history','users','periods','shifts','assignments','attendance_records','history','domain_events','notifications')) {
        $source=Query "SELECT COUNT(*) AS total FROM [$SourceDatabase].dbo.[$table]"
        $restored=Query "SELECT COUNT(*) AS total FROM [$restoreDatabase].dbo.[$table]"
        if($source.Rows[0].total -ne $restored.Rows[0].total){throw "Restored row count differs: $table"}
        $counts[$table]=[int]$restored.Rows[0].total
    }
    @{at=(Get-Date).ToUniversalTime().ToString('o');source=$SourceDatabase;restored=$restoreDatabase;backup=$backupPath;verifyOnly='PASS';checkDb='PASS';tableCounts=$counts} |
        ConvertTo-Json -Depth 4 | Set-Content -Encoding UTF8 .local-test/backup-restore-evidence.json
    Write-Output "PASS backup checksum, RESTORE VERIFYONLY, restore to $restoreDatabase, DBCC CHECKDB and row counts."
} finally { $connection.Dispose() }
