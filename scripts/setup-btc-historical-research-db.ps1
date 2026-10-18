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
    $containerName = 'trading-bot-mysql-local'
    $existing = @(& docker ps -a --filter "name=^/$containerName$" --format '{{.ID}}')
    if ($LASTEXITCODE -ne 0) { throw 'Unable to inspect local Docker containers' }
    if ($existing.Count -gt 0) {
        # A container with this name may belong to a different Compose project.
        # Reuse a compatible local MySQL 8.4 container instead of recreating it.
        # Never remove it, prune its volumes, or alter the original trading_bot DB.
        $imageName = (& docker inspect --format '{{.Config.Image}}' $containerName)
        if ($LASTEXITCODE -ne 0 -or $imageName -notmatch '(^|/)mysql:8\.4($|[.-])') {
            throw "Existing $containerName is not the expected MySQL 8.4 container. Inspect it manually; no data were removed."
        }
        $ports = @(& docker port $containerName '3306/tcp')
        if ($LASTEXITCODE -ne 0 -or $ports -notcontains '127.0.0.1:3307') {
            throw "Existing $containerName does not expose expected localhost port 3307. Inspect Docker before proceeding."
        }
        $running = (& docker inspect --format '{{.State.Running}}' $containerName)
        if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect existing MySQL state' }
        if ($running.Trim() -ne 'true') {
            & docker start $containerName | Out-Null
            if ($LASTEXITCODE -ne 0) { throw 'Could not start existing MySQL container' }
        }
        Write-Host "Reusing existing container $containerName. Original trading_bot database unchanged."
    } else {
        & docker compose up -d mysql
        if ($LASTEXITCODE -ne 0) { throw 'MySQL Docker start failed' }
    }
    # Allow MySQL 8.4 initialization to finish before DDL.
    # Local development credentials match ONLY compose.yaml, not production.
    $ready=$false
    for ($attempt=0; $attempt -lt 30; $attempt++) {
        & docker exec $containerName mysqladmin ping -h 127.0.0.1 --silent 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) { $ready=$true; break }
        Start-Sleep -Seconds 2
    }
    if (-not $ready) {
        throw 'Local MySQL did not become ready in 60 seconds'
    }
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
