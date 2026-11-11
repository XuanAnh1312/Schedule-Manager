# Backup / restore verification for the isolated Docker SQL Server used by tests/qa/run-qa.ps1.
# BACKUP (COPY_ONLY, CHECKSUM) -> RESTORE VERIFYONLY -> RESTORE to a new database -> DBCC CHECKDB ->
# compare row counts of every table between source and restored copy.
#
#   powershell -ExecutionPolicy Bypass -File tests\qa\db-backup-restore.ps1 -SourceDatabase ScheduleManager_MocA_Test_QA_<stamp>
#
# Only databases whose name starts with ScheduleManager_MocA_Test_ are accepted. Nothing is dropped.
param(
    [Parameter(Mandatory = $true)][string]$SourceDatabase,
    [string]$Container = 'sm-qa-mssql',
    [int]$Port = 14333
)
$ErrorActionPreference = 'Stop'
if ($SourceDatabase -notmatch '^ScheduleManager_MocA_Test_[A-Za-z0-9_]+$') { throw 'Only an isolated ScheduleManager_MocA_Test_* database is allowed' }
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$cfg = @{}
Get-Content (Join-Path $root '.local-test\qa.env') | ForEach-Object { if ($_ -match '^([^=]+)=(.*)$') { $cfg[$matches[1]] = $matches[2] } }
$server = "tcp:localhost,$Port"
$stamp = Get-Date -Format 'yyyyMMdd_HHmmss'
$target = "ScheduleManager_MocA_Test_Restore_$stamp"
$bak = "/var/opt/mssql/data/$SourceDatabase.$stamp.bak"

function Sql([string]$query, [string]$database = 'master') {
    $out = & sqlcmd -S $server -U sa -P $cfg.SM_QA_SA_PASSWORD -C -b -d $database -h -1 -W -s '|' -Q "SET NOCOUNT ON; $query" 2>&1
    if ($LASTEXITCODE -ne 0) { throw "sqlcmd failed: $($out | Out-String)" }
    return @($out | Where-Object { $_ -and $_.Trim() })
}
function Counts([string]$database) {
    $map = [ordered]@{}
    foreach ($line in Sql "SELECT t.name, SUM(p.rows) FROM sys.tables t JOIN sys.partitions p ON p.object_id=t.object_id AND p.index_id IN (0,1) GROUP BY t.name ORDER BY t.name" $database) {
        $parts = $line.Split('|'); $map[$parts[0]] = [int64]$parts[1]
    }
    return $map
}

$evidence = [ordered]@{ startedAt = (Get-Date).ToString('o'); source = $SourceDatabase; restored = $target; backupFile = $bak; steps = @() }
function Step($name, [scriptblock]$block) {
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $result = & $block
    $evidence.steps += [ordered]@{ step = $name; seconds = [math]::Round($sw.Elapsed.TotalSeconds, 2); result = ($result | Out-String).Trim() }
    Write-Host ("{0,-22} {1,6:N2}s  {2}" -f $name, $sw.Elapsed.TotalSeconds, (($result | Out-String).Trim() -split "`n" | Select-Object -First 1))
}

Step 'BACKUP' { Sql "BACKUP DATABASE [$SourceDatabase] TO DISK=N'$bak' WITH COPY_ONLY, CHECKSUM, INIT, FORMAT, STATS=100" }
Step 'RESTORE VERIFYONLY' { Sql "RESTORE VERIFYONLY FROM DISK=N'$bak' WITH CHECKSUM" }
$files = Sql "SET NOCOUNT ON; DECLARE @t TABLE(LogicalName nvarchar(128),PhysicalName nvarchar(260),[Type] char(1),FileGroupName nvarchar(128),Size numeric(20,0),MaxSize numeric(20,0),FileId bigint,CreateLSN numeric(25,0),DropLSN numeric(25,0),UniqueId uniqueidentifier,ReadOnlyLSN numeric(25,0),ReadWriteLSN numeric(25,0),BackupSizeInBytes bigint,SourceBlockSize int,FileGroupId int,LogGroupGUID uniqueidentifier,DifferentialBaseLSN numeric(25,0),DifferentialBaseGUID uniqueidentifier,IsReadOnly bit,IsPresent bit,TDEThumbprint varbinary(32),SnapshotUrl nvarchar(360)); INSERT @t EXEC('RESTORE FILELISTONLY FROM DISK=N''$bak'''); SELECT LogicalName, [Type] FROM @t"
$moves = foreach ($f in $files) {
    $p = $f.Split('|'); $ext = if ($p[1] -eq 'L') { '_log.ldf' } else { '.mdf' }
    "MOVE N'$($p[0])' TO N'/var/opt/mssql/data/$target$ext'"
}
Step 'RESTORE DATABASE' { Sql "RESTORE DATABASE [$target] FROM DISK=N'$bak' WITH $($moves -join ', '), CHECKSUM, STATS=100" }
Step 'DBCC CHECKDB' { $r = Sql "DBCC CHECKDB([$target]) WITH NO_INFOMSGS, ALL_ERRORMSGS"; if ($r.Count) { throw "CHECKDB reported: $($r -join ' ')" }; 'CHECKDB: 0 errors' }

$src = Counts $SourceDatabase
$dst = Counts $target
$diff = @($src.Keys | Where-Object { $src[$_] -ne $dst[$_] })
$evidence.tables = $src.Count
$evidence.rowsSource = ($src.Values | Measure-Object -Sum).Sum
$evidence.rowsRestored = ($dst.Values | Measure-Object -Sum).Sum
$evidence.mismatchedTables = $diff
$evidence.counts = $src
$size = Sql "SELECT TOP 1 backup_size FROM msdb.dbo.backupset WHERE database_name=N'$SourceDatabase' ORDER BY backup_finish_date DESC"
$evidence.backupSizeBytes = [int64]($size | Select-Object -First 1)
$evidence.passed = ($diff.Count -eq 0)
$evidence.finishedAt = (Get-Date).ToString('o')
$out = Join-Path $root '.local-test\qa\backup-restore-evidence.json'
[IO.File]::WriteAllText($out, ($evidence | ConvertTo-Json -Depth 5), [Text.UTF8Encoding]::new($false))
Write-Host "Tables: $($src.Count); rows source=$($evidence.rowsSource) restored=$($evidence.rowsRestored); mismatches=$($diff.Count)"
if ($diff.Count) { Write-Host "Mismatched: $($diff -join ', ')"; exit 1 }
Write-Host "PASS backup/restore evidence: $out"
