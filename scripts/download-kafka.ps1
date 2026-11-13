$ErrorActionPreference = 'Stop'
$root = (Resolve-Path "$PSScriptRoot\..").Path
$localDir = Join-Path $root '.local'
New-Item -ItemType Directory -Force $localDir | Out-Null
$name='kafka_2.13-3.9.1.tgz'
$url="https://archive.apache.org/dist/kafka/3.9.1/$name"
$archive=Join-Path $localDir 'kafka.tgz'
if (!(Test-Path -LiteralPath $archive)) { Invoke-WebRequest $url -OutFile $archive }
$sum=(Invoke-WebRequest "$url.sha512").Content -replace '\s',''
$digest=(Get-FileHash -LiteralPath $archive -Algorithm SHA512).Hash
if($sum -notmatch $digest){throw 'Kafka archive checksum mismatch; no extraction performed.'}
& tar -xzf $archive -C $localDir
if($LASTEXITCODE -ne 0){throw 'Kafka extraction failed.'}
Write-Host 'Kafka 3.9.1 archive verified and extracted.'
