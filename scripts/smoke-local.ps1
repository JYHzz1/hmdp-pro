param([string]$Phone = '13900009001')
$ErrorActionPreference = 'Stop'
if (!$env:DB_PASSWORD) { throw 'Set DB_PASSWORD for physical shard checks.' }
$base = 'http://127.0.0.1:5175/api'
function Call-Api([string]$Path, $Body = $null, $Headers = @{}, [string]$Method = 'Post') {
    $params = @{Uri="$base$Path"; Method=$Method; Headers=$Headers}
    if ($null -ne $Body) { $params.ContentType='application/json'; $params.Body=($Body|ConvertTo-Json -Depth 8) }
    $result = Invoke-RestMethod @params
    if (!$result.success) { throw "API failed $Path : $($result.errorMsg)" }
    return $result
}
function Check([bool]$Ok,[string]$Name) {
    if (!$Ok) { throw "FAILED $Name" }
    Write-Host "PASS $Name"
}
Check ((Invoke-WebRequest 'http://127.0.0.1:5175/').StatusCode -eq 200) 'Vue frontend'
$types = Call-Api '/shop-type/list' -Method Get
Check (@($types.data).Count -gt 0) 'types through Vite proxy and database'
$shops = Call-Api '/shop/of/type?typeId=1&current=1' -Method Get
Check (@($shops.data).Count -gt 0) 'shop list'
$shopId = $shops.data[0].id
$shop = Call-Api "/shop/$shopId" -Method Get
Check ($null -ne $shop.data) 'Bloom filter and cached shop lookup'
$code = Call-Api "/user/code?phone=$Phone"
$login = Call-Api '/user/login' @{phone=$Phone;code=$code.data}
$auth = @{authorization=$login.data}
$me = Call-Api '/user/me' -Headers $auth -Method Get
Check ($null -ne $me.data.id) 'login and authenticated profile'
$voucher = @{shopId=$shopId; title='hmdp-plus local smoke'; subTitle='isolated demo fixture'; rules='local only'; payValue=100; actualValue=200; type=1;status=1; stock=2;beginTime=(Get-Date).AddMinutes(-1).ToString('yyyy-MM-dd HH:mm:ss');endTime=(Get-Date).AddHours(1).ToString('yyyy-MM-dd HH:mm:ss')}
$created = Call-Api '/voucher/seckill' $voucher -Headers $auth
$voucherId = [string]$created.data
Check (![string]::IsNullOrWhiteSpace($voucherId)) 'create sharded voucher'
$detail = Call-Api '/voucher/get' @{voucherId=$voucherId} -Headers $auth
Check ($null -ne $detail.data.shopId) 'voucher details and local cache'
$access = Call-Api "/voucher-order/seckill/token/$voucherId" -Headers $auth -Method Get
$order = Call-Api "/voucher-order/seckill/$voucherId`?accessToken=$($access.data)" -Headers $auth
$orderId = [string]$order.data
Check (![string]::IsNullOrWhiteSpace($orderId)) 'access token and Kafka order acceptance'
$persisted = $null
for($i=0;$i -lt 30;$i++) {
    $query = Call-Api '/voucher-order/get/seckill/voucher/order-id' @{orderId=$orderId} -Headers $auth
    if ($query.data) { $persisted=[string]$query.data; break }
    Start-Sleep -Milliseconds 300
}
Check ($persisted -eq $orderId) 'asynchronous persisted order lookup'
$env:MYSQL_PWD = $env:DB_PASSWORD
try {
    $orderCount = 0
    for($attempt=0;$attempt -lt 20;$attempt++) {
        $orderCount = 0
        foreach($db in 'hmdpplus_local_0','hmdpplus_local_1') {
            foreach($n in 0,1) {
                $row = & mysql -u root -N -e "SELECT COUNT(*) FROM $db.tb_voucher_order_$n WHERE id=$orderId"
                if($LASTEXITCODE -ne 0) { throw 'Cannot inspect physical order table.' }
                $orderCount += [int]$row
            }
        }
        if($orderCount -eq 1) { break }
        Start-Sleep -Milliseconds 200
    }
    Check ($orderCount -eq 1) 'exactly one order in four physical order tables'
    $stocks = @()
    foreach($db in 'hmdpplus_local_0','hmdpplus_local_1') {
        foreach($n in 0,1) {
            $rows = & mysql -u root -N -e "SELECT stock FROM $db.tb_seckill_voucher_$n WHERE voucher_id=$voucherId"
            if($LASTEXITCODE -ne 0) { throw 'Cannot inspect physical stock table.' }
            $stocks += @($rows | Where-Object { ![string]::IsNullOrWhiteSpace($_) })
        }
    }
    Check ($stocks.Count -eq 1 -and [int]$stocks[0] -eq 1) 'database stock 2 -> 1'
    $redisCli = (Resolve-Path "$PSScriptRoot\..\..\redis\redis-cli.exe").Path
    $redisStock = & $redisCli -p 6381 --raw GET "hmdpplus-local-seckill:stock:{$voucherId}"
    Check ([int]$redisStock -eq 1) 'Redis stock 2 -> 1'
} finally { Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue }
$replay = Invoke-RestMethod -Method Post "$base/voucher-order/seckill/$voucherId`?accessToken=$($access.data)" -Headers $auth
Check (!$replay.success) 'consumed access token rejected'
$metrics = Invoke-WebRequest 'http://127.0.0.1:8085/actuator/prometheus' -Headers $auth
Check ($metrics.StatusCode -eq 200) 'Prometheus metrics endpoint'
$summary = @{date=(Get-Date).ToString('yyyy-MM-dd HH:mm:ss');phone=$Phone;userId=[string]$me.data.id;voucherId=$voucherId;orderId=$orderId;accepted=$true;persisted=$true}
$root=(Resolve-Path "$PSScriptRoot\..").Path
$summary|ConvertTo-Json|Set-Content -LiteralPath "$root\.local\smoke-result.json" -Encoding utf8
Write-Host "SMOKE SUCCESS voucherId=$voucherId orderId=$orderId (demo data retained only in isolated databases)"
