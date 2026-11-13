param([string]$JavaHome = $env:HMDP_JAVA_HOME,
      [string]$RedisHome = "$PSScriptRoot\..\..\redis")
$ErrorActionPreference = 'Stop'
if (!$JavaHome) { $JavaHome = 'C:\Program Files\JetBrains\PyCharm Community Edition 2023.2\jbr' }
$java = Join-Path $JavaHome 'bin\java.exe'
if (!(Test-Path -LiteralPath $java)) { throw 'Set HMDP_JAVA_HOME to JDK 17.' }
$root = (Resolve-Path "$PSScriptRoot\..").Path
$localDir = Join-Path $root '.local'
New-Item -ItemType Directory -Force $localDir | Out-Null
function Test-Port([int]$port) {
    $tcp = New-Object Net.Sockets.TcpClient
    try { $tcp.Connect('127.0.0.1',$port); return $true }
    catch { return $false }
    finally { $tcp.Dispose() }
}
if (!(Test-Port 6381)) {
    $redis = (Resolve-Path "$RedisHome\redis-server.exe").Path
    $proc = Start-Process $redis -ArgumentList "--bind 127.0.0.1 --port 6381 --dir `"$localDir`" --appendonly yes" -WorkingDirectory $root -WindowStyle Hidden -PassThru -RedirectStandardOutput "$localDir\redis.out.log" -RedirectStandardError "$localDir\redis.err.log"
    $proc.Id | Set-Content "$localDir\redis.pid"
}
$kafka = Join-Path $localDir 'kafka_2.13-3.9.1'
if (!(Test-Path -LiteralPath "$kafka\libs")) { throw 'Prepare official Kafka 3.9.1 binary under .local/kafka_2.13-3.9.1 first. See docs/local-setup.md.' }
$data = (Join-Path $localDir 'kafka-data').Replace('\','/')
$conf = @"
process.roles=broker,controller
node.id=1
controller.quorum.voters=1@127.0.0.1:19093
listeners=PLAINTEXT://127.0.0.1:19092,CONTROLLER://127.0.0.1:19093
advertised.listeners=PLAINTEXT://127.0.0.1:19092
controller.listener.names=CONTROLLER
listener.security.protocol.map=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT
inter.broker.listener.name=PLAINTEXT
log.dirs=$data
num.partitions=3
offsets.topic.replication.factor=1
transaction.state.log.replication.factor=1
transaction.state.log.min.isr=1
group.initial.rebalance.delay.ms=0
auto.create.topics.enable=true
"@
[IO.File]::WriteAllText("$localDir\kafka.properties",$conf,(New-Object Text.UTF8Encoding($false)))
if (!(Test-Port 19092)) {
    if (!(Test-Path -LiteralPath "$localDir\kafka-data\meta.properties")) {
        $clusterId = & $java -cp "$kafka\libs\*" kafka.tools.StorageTool random-uuid
        if ($LASTEXITCODE -ne 0) { throw 'Cannot generate Kafka cluster ID.' }
        & $java -cp "$kafka\libs\*" kafka.tools.StorageTool format -t $clusterId.Trim() -c "$localDir\kafka.properties"
        if ($LASTEXITCODE -ne 0) { throw 'Kafka storage initialization failed.' }
    }
    $proc = Start-Process $java -ArgumentList "-Xms256m -Xmx512m -cp `"$kafka\libs\*`" kafka.Kafka `"$localDir\kafka.properties`"" -WorkingDirectory $root -WindowStyle Hidden -PassThru -RedirectStandardOutput "$localDir\kafka.out.log" -RedirectStandardError "$localDir\kafka.err.log"
    $proc.Id | Set-Content "$localDir\kafka.pid"
    for($i=0;$i -lt 60 -and !(Test-Port 19092);$i++){ Start-Sleep -Seconds 1; $proc.Refresh(); if($proc.HasExited){throw 'Kafka exited; inspect .local/kafka.err.log.'} }
}
if (!(Test-Port 19092)) { throw 'Kafka did not become ready.' }
Write-Host 'Redis :6381 and Kafka :19092 ready (loopback only).'
