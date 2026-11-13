param([string]$JavaHome = $env:HMDP_JAVA_HOME, [switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
if (!$JavaHome) { $JavaHome = 'C:\Program Files\JetBrains\PyCharm Community Edition 2023.2\jbr' }
$root = (Resolve-Path "$PSScriptRoot\..").Path
$java = Join-Path $JavaHome 'bin\java.exe'
if (!(Test-Path -LiteralPath $java)) { throw 'Set HMDP_JAVA_HOME to a JDK 17 installation.' }
function Test-Port([int]$port) {
    $tcp = New-Object Net.Sockets.TcpClient
    try { $tcp.Connect('127.0.0.1',$port); return $true }
    catch { return $false }
    finally { $tcp.Dispose() }
}
Push-Location $root
try {
    & "$PSScriptRoot\init-local.ps1"
    & "$PSScriptRoot\start-infra.ps1" -JavaHome $JavaHome
    if (!(Test-Port 8085)) {
        if (!$SkipBuild) {
            $env:JAVA_HOME = $JavaHome
            $env:Path = "$JavaHome\bin;$env:Path"
            & mvn -B -DskipTests package
            if ($LASTEXITCODE -ne 0) { throw 'Maven build failed.' }
        }
        $jar = Join-Path $root 'hmdp-core-service\target\hmdp-core-service-0.0.1-SNAPSHOT.jar'
        $sharding = (Join-Path $root '.local\shardingsphere-local.yaml').Replace('\','/')
        $upload = Join-Path $root 'hmdp-vue3\public\imgs'
        $backendArgs = "-Xms256m -Xmx1024m `"-Dhmdp.upload.dir=$upload`" -jar `"$jar`" --server.address=127.0.0.1 --server.port=8085 --spring.data.redis.port=6381 --spring.kafka.bootstrap-servers=127.0.0.1:19092 --spring.kafka.consumer.group-id=hmdpplus-local-orders --spring.kafka.consumer.auto-offset-reset=earliest --prefix.distinction.name=hmdpplus-local `"--spring.datasource.url=jdbc:shardingsphere:absolutepath:$sharding`""
        $proc = Start-Process $java -ArgumentList $backendArgs -WorkingDirectory $root -WindowStyle Hidden -PassThru -RedirectStandardOutput '.local\backend.out.log' -RedirectStandardError '.local\backend.err.log'
        $proc.Id | Set-Content '.local\backend.pid'
        for($i=0;$i -lt 120 -and !(Test-Port 8085);$i++){ Start-Sleep -Seconds 1; $proc.Refresh(); if($proc.HasExited){throw 'Backend exited; inspect .local/backend.out.log and backend.err.log.'} }
        if (!(Test-Port 8085)) { throw 'Backend startup timed out.' }
    }
    if (!(Test-Port 5175)) {
        $node = (Get-Command node).Source
        $vite = Join-Path $root 'hmdp-vue3\node_modules\vite\bin\vite.js'
        if (!(Test-Path -LiteralPath $vite)) { throw 'Run npm ci in hmdp-vue3 first.' }
        $proc = Start-Process $node -ArgumentList "`"$vite`"" -WorkingDirectory "$root\hmdp-vue3" -WindowStyle Hidden -PassThru -RedirectStandardOutput "$root\.local\frontend.out.log" -RedirectStandardError "$root\.local\frontend.err.log"
        $proc.Id | Set-Content '.local\frontend.pid'
    }
    $response = Invoke-RestMethod 'http://127.0.0.1:8085/shop-type/list'
    if (!$response.success) { throw 'Backend API check did not pass.' }
    Write-Host 'Ready: http://127.0.0.1:5175 (backend :8085, Redis :6381, Kafka :19092).'
} finally { Pop-Location }
