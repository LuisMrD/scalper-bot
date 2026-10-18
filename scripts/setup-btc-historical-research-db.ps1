<#
.SYNOPSIS
  Create an ISOLATED MySQL schema for public historical USD-M research.
  Existing 'trading_bot' TESTNET data are NEVER deleted, copied or modified.
.EXAMPLE
  .\scripts\setup-btc-historical-research-db.ps1
#>
[CmdletBinding()]
param([switch]$PrintOnly)
$ErrorActionPreference='Stop'
if($PrintOnly) {
    Write-Output 'DATABASE=trading_bot_research'
    Write-Output 'DATA_SOURCE=PRODUCTION_PUBLIC_READ_ONLY'
    Write-Output 'EXISTING_DATABASE=trading_bot_UNCHANGED'
    return
}
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw 'Docker CLI not available'
}
$root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location $root
try {
    & docker compose up -d mysql
    if($LASTEXITCODE -ne 0) { throw 'MySQL Docker start failed' }
    # Local development credentials match ONLY compose.yaml, not production.
    $sql=@'
CREATE DATABASE IF NOT EXISTS trading_bot_research CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
GRANT ALL PRIVILEGES ON trading_bot_research.* TO 'trading'@'%';
FLUSH PRIVILEGES;
'@
    $sql | & docker exec -i trading-bot-mysql-local mysql --user=root --password=root_local_only
    if($LASTEXITCODE -ne 0) { throw 'Research schema creation/GRANT failed' }
    Write-Host 'OK: dedicated trading_bot_research schema is ready. Original trading_bot unchanged.'
} finally {
    Pop-Location
}
