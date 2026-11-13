param([string]$MySqlExe = 'mysql')
$ErrorActionPreference = 'Stop'
if (!$env:DB_PASSWORD) { throw 'Set DB_PASSWORD to your local MySQL password.' }
$root = (Resolve-Path "$PSScriptRoot\..").Path
$runtime = Join-Path $root '.local'
New-Item -ItemType Directory -Force $runtime | Out-Null
$env:MYSQL_PWD = $env:DB_PASSWORD
try {
    foreach ($n in 0,1) {
        $db = "hmdpplus_local_$n"
        $exists = & $MySqlExe -u root -N -e "SELECT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME='$db'"
        if ($LASTEXITCODE -ne 0) { throw 'MySQL connection failed.' }
        if ($exists) { Write-Host "Keeping existing $db"; continue }
        & $MySqlExe -u root -e "CREATE DATABASE $db CHARACTER SET utf8mb4"
        if ($LASTEXITCODE -ne 0) { throw "Cannot create $db" }
        $sql = Get-Content -Raw -LiteralPath "$root\sql\hmdp_$n.sql"
        $sql = "SET @saved_cs_client=@@character_set_client;`n" + $sql.Replace("USE hmdp_$n;", "USE $db;")
        $path = "$runtime\import_$n.sql"
        [IO.File]::WriteAllText($path, $sql, (New-Object Text.UTF8Encoding($false)))
        $source = $path.Replace('\','/')
        & $MySqlExe --default-character-set=utf8mb4 -u root -e "source $source"
        if ($LASTEXITCODE -ne 0) { throw "Import failed for $db; inspect it before retrying." }
    }
    $sharding = Get-Content -Raw -LiteralPath "$root\hmdp-core-service\src\main\resources\shardingsphere.yaml"
    foreach ($n in 0,1) { $sharding = $sharding.Replace("/hmdp_$n`?", "/hmdpplus_local_$n`?") }
    $quotedPassword = "'" + $env:DB_PASSWORD.Replace("'", "''") + "'"
    $sharding = $sharding.Replace('password: root', "password: $quotedPassword")
    [IO.File]::WriteAllText("$runtime\shardingsphere-local.yaml", $sharding, (New-Object Text.UTF8Encoding($false)))
    Write-Host 'Local databases and isolated ShardingSphere config prepared.'
} finally { Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue }
