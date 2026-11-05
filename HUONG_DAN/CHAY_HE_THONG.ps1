param([switch]$Offline,[switch]$NoBrowser,[switch]$Rebuild,[switch]$Stop,[int]$Port=8080)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $projectRoot
$runtime=Join-Path $projectRoot '.local-run'
$javaProcess=$null
$instanceLock=$null
$ownsInstanceLock=$false
function Ready([string]$origin) {
    try {
        $health=Invoke-RestMethod "$origin/api/health" -TimeoutSec 2
        $database=Invoke-RestMethod "$origin/api/health/database" -TimeoutSec 2
        return $health.service -eq 'schedule-manager-api' -and $health.status -eq 'UP' -and $database.status -eq 'UP'
    } catch { return $false }
}
function Open-Application([string]$url) {
    if(-not $NoBrowser){Start-Process $url | Out-Null}
}
function Port-InUse([int]$number) {
    $client=[System.Net.Sockets.TcpClient]::new()
    try {
        $pending=$client.ConnectAsync('127.0.0.1',$number)
        if(-not $pending.Wait(5000)){throw "Khong xac minh duoc cong $number trong thoi gian cho phep."}
        return $true
    } catch {
        $reason=$_.Exception.GetBaseException()
        if($reason -is [System.Net.Sockets.SocketException] -and $reason.SocketErrorCode -eq [System.Net.Sockets.SocketError]::ConnectionRefused){return $false}
        throw
    } finally { $client.Dispose() }
}
try {
    $statePath=Join-Path $runtime 'runtime.json'
    if($Stop){
        if(-not (Test-Path -LiteralPath $statePath)){Write-Host 'Khong co phien he thong do launcher quan ly.';exit 0}
        $owned=Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
        $ownedProcess=Get-Process -Id $owned.processId -ErrorAction SilentlyContinue
        if($owned.projectRoot -ne $projectRoot){throw 'Runtime state khong thuoc du an nay.'}
        if($ownedProcess -and $ownedProcess.StartTime.ToUniversalTime().ToString('o') -ne $owned.processStartedAt){throw 'PID da duoc tai su dung; khong tat tien trinh khac.'}
        $owned | Add-Member -NotePropertyName stopping -NotePropertyValue $true -Force
        $owned | ConvertTo-Json | Set-Content -LiteralPath $statePath -Encoding UTF8
        if($ownedProcess){Stop-Process -Id $ownedProcess.Id -ErrorAction Stop}
        if(Test-Path -LiteralPath $statePath){$remaining=Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json;if($remaining.processId -eq $owned.processId){Remove-Item -LiteralPath $statePath}}
        Write-Host 'Da dung he thong. Du lieu SQL Server duoc giu nguyen.'
        exit 0
    }
    if($env:APP_PORT){$Port=[int]$env:APP_PORT}
    if($Port -lt 1 -or $Port -gt 65535){throw 'APP_PORT must be 1..65535.'}
    if(-not (Test-Path -LiteralPath (Join-Path $projectRoot '.env'))){throw 'Chua co .env. Xem HUONG_DAN_CHAY_HE_THONG.md; khong ghi de .env da co.'}
    $configurationFingerprint=(Get-FileHash -LiteralPath (Join-Path $projectRoot '.env') -Algorithm SHA256).Hash
    $java=Get-Command java -ErrorAction SilentlyContinue
    if(-not $java){throw 'Can Java 21 tro len trong PATH.'}
    New-Item -ItemType Directory -Force -Path $runtime | Out-Null
    $base="http://127.0.0.1:$Port"
    $browserUrl="http://localhost:$Port"
    $manifestPath=Join-Path $runtime 'build.json'
    $jarPath=Join-Path $projectRoot 'target/ManageSchedule-1.0-SNAPSHOT.jar'
    $files=@(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'src/main') -File -Recurse)
    foreach($name in @('pom.xml','index.html','workspace.html','attendance.html','js/workspace.js','js/attendance-portal.js','css/workspace.css','css/attendance.css')){$files+=Get-Item -LiteralPath (Join-Path $projectRoot $name)}
    $source=[System.Text.StringBuilder]::new()
    foreach($file in ($files | Sort-Object FullName)){
        $null=$source.Append($file.FullName.Substring($projectRoot.Length).Replace('\','/')).Append(':').Append((Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash).Append("`n")
    }
    $sha=[System.Security.Cryptography.SHA256]::Create()
    try{
        $fingerprint=[BitConverter]::ToString($sha.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($source.ToString()))).Replace('-','')
        $lockName='Local\ManageSchedule_'+[BitConverter]::ToString($sha.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($projectRoot.ToLowerInvariant()))).Replace('-','')
    }finally{$sha.Dispose()}
    $instanceLock=[System.Threading.Mutex]::new($false,$lockName)
    try{$ownsInstanceLock=$instanceLock.WaitOne(0)}catch{
        if($_.Exception.GetBaseException() -is [System.Threading.AbandonedMutexException]){$ownsInstanceLock=$true}else{throw}
    }
    if(-not $ownsInstanceLock -or (Port-InUse $Port)){
        $state=$null
        if(Test-Path -LiteralPath $statePath){try{$state=Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json}catch{}}
        $existing=if($state){Get-Process -Id $state.processId -ErrorAction SilentlyContinue}else{$null}
        if($state -and $existing -and $state.port -eq $Port -and $state.projectRoot -eq $projectRoot -and $existing.StartTime.ToUniversalTime().ToString('o') -eq $state.processStartedAt -and (Ready $base)){
            if($Rebuild -or $state.sourceFingerprint -ne $fingerprint -or $state.configurationFingerprint -ne $configurationFingerprint){throw 'Can build lai hoac ma nguon/.env da thay doi. Dung phien cu bang Ctrl+C hoac --stop, sau do chay lai.'}
            Write-Host "He thong da chay: $browserUrl"
            Open-Application $browserUrl
            exit 0
        }
        if(-not $ownsInstanceLock){throw 'He thong dang khoi dong o cua so khac. Cho cua so do bao SAN SANG roi mo lai.'}
        throw "Cong $Port dang duoc tien trinh khac su dung. Dung phien FE/BE cu hoac dat APP_PORT khac; khong tu dong tat tien trinh khac."
    }
    $build=$null
    if(Test-Path -LiteralPath $manifestPath){try{$build=Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json}catch{}}
    $buildNeeded=$true
    if(-not $Rebuild -and $build -and (Test-Path -LiteralPath $jarPath)){
        $buildNeeded=$build.sourceFingerprint -ne $fingerprint -or $build.jarSha256 -ne (Get-FileHash -LiteralPath $jarPath -Algorithm SHA256).Hash
    }
    if($buildNeeded){
        $maven=Get-Command mvn.cmd -ErrorAction SilentlyContinue
        if(-not $maven){throw 'Can Maven trong PATH de build lan dau hoac sau khi ma nguon thay doi.'}
        $buildLog=Join-Path $runtime 'build.log'
        Write-Host 'Dang dong goi giao dien va API thanh mot ung dung...'
        Write-Host "Log build: $buildLog"
        $mavenArgs=@('-o','-B','package','-DskipTests')
        $previousPreference=$ErrorActionPreference
        try{
            $ErrorActionPreference='Continue'
            & $maven.Source @mavenArgs *> $buildLog
            $buildExit=$LASTEXITCODE
            if($buildExit -ne 0 -and -not $Offline -and (Select-String -LiteralPath $buildLog -Pattern 'offline mode|has not been downloaded|Cannot access.*offline' -Quiet)){
                Write-Host 'Chua du thu vien trong cache. Dang tai dependencies bang Maven...'
                & $maven.Source '-B' 'package' '-DskipTests' *> $buildLog
                $buildExit=$LASTEXITCODE
            }
        }finally{$ErrorActionPreference=$previousPreference}
        if($buildExit -ne 0){Get-Content -LiteralPath $buildLog -Tail 15 | ForEach-Object {Write-Host $_};throw "Build that bai. Xem $buildLog"}
        @{sourceFingerprint=$fingerprint;jarSha256=(Get-FileHash -LiteralPath $jarPath -Algorithm SHA256).Hash} | ConvertTo-Json | Set-Content -LiteralPath $manifestPath -Encoding UTF8
    }else{Write-Host 'Ma nguon khong thay doi: dung lai ban build da co.'}
    $outputLog=Join-Path $runtime 'application.log'
    $errorLog=Join-Path $runtime 'application-error.log'
    Write-Host 'Dang khoi dong ung dung va kiem tra ket noi SQL Server...'
    $javaProcess=Start-Process -FilePath $java.Source -ArgumentList @('-jar',('"'+$jarPath+'"'),'--spring.profiles.active=sqlserver',"--server.port=$Port",'--server.address=127.0.0.1') -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput $outputLog -RedirectStandardError $errorLog
    @{processId=$javaProcess.Id;processStartedAt=$javaProcess.StartTime.ToUniversalTime().ToString('o');projectRoot=$projectRoot;port=$Port;sourceFingerprint=$fingerprint;configurationFingerprint=$configurationFingerprint} | ConvertTo-Json | Set-Content -LiteralPath $statePath -Encoding UTF8
    $deadline=(Get-Date).AddSeconds(90)
    while($true){
        $javaProcess.Refresh()
        if($javaProcess.HasExited){Get-Content -LiteralPath $outputLog -Tail 12 | ForEach-Object {Write-Host $_};throw "Ung dung da dung. Xem $outputLog va $errorLog de biet loi SQL/migration/cau hinh."}
        if(Ready $base){break}
        if((Get-Date) -gt $deadline){throw "Qua 90 giay chua san sang. Xem $outputLog va $errorLog."}
        Start-Sleep -Seconds 1
    }
    Write-Host "SAN SANG: $browserUrl"
    Write-Host 'Giao dien va API cung mot cong. Du lieu duoc luu vao SQL Server theo .env.'
    Write-Host 'Giu cua so nay. Nhan Ctrl+C tai day de dung he thong.'
    Write-Host "Log: $outputLog"
    Open-Application $browserUrl
    while(-not $javaProcess.HasExited){Start-Sleep -Seconds 1;$javaProcess.Refresh()}
    $stopping=$false
    if(Test-Path -LiteralPath $statePath){try{$finished=Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json;$stopping=[bool]$finished.stopping}catch{}}
    else{$stopping=$true}
    $javaProcess.WaitForExit()
    if($javaProcess.ExitCode -ne 0 -and -not $stopping){throw 'Ung dung da dung dot ngot. Xem log de biet nguyen nhan.'}
} catch {
    Write-Host ('[LOI] '+$_.Exception.Message) -ForegroundColor Red
    exit 1
} finally {
    if($javaProcess){
        $javaProcess.Refresh()
        if(-not $javaProcess.HasExited){Stop-Process -Id $javaProcess.Id -ErrorAction SilentlyContinue}
        if(Test-Path -LiteralPath $statePath){
            try{$owned=Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json;if($owned.processId -eq $javaProcess.Id){Remove-Item -LiteralPath $statePath}}catch{}
        }
    }
    if($instanceLock){if($ownsInstanceLock){$instanceLock.ReleaseMutex()};$instanceLock.Dispose()}
}
