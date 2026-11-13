$ErrorActionPreference = 'Stop'
$root=(Resolve-Path "$PSScriptRoot\..").Path
foreach($name in 'frontend','backend','kafka'){
    $file=Join-Path $root ".local\$name.pid"
    if(!(Test-Path -LiteralPath $file)){continue}
    $taskPid=[int](Get-Content -LiteralPath $file)
    $proc=Get-CimInstance Win32_Process -Filter "ProcessId=$taskPid"
    if(!$proc){continue}
    if(!$proc.CommandLine.Contains($root)){throw "PID identity mismatch for $name; refusing to stop."}
    Stop-Process -Id $taskPid
}
$redisCli=(Resolve-Path "$PSScriptRoot\..\..\redis\redis-cli.exe").Path
$info=& $redisCli -p 6381 --raw INFO server 2>$null
$line=$info|Where-Object {$_ -match '^process_id:(\d+)'}|Select-Object -First 1
if($line -match '^process_id:(\d+)'){
    $taskPid=[int]$Matches[1]
    $proc=Get-CimInstance Win32_Process -Filter "ProcessId=$taskPid"
    if($proc -and $proc.Name -eq 'redis-server.exe' -and $proc.CommandLine.Contains('6381')){
        & $redisCli -p 6381 shutdown
    }
}
Write-Host 'Stopped this project services. MySQL and original hmdp data remain available.'
