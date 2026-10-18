<#
.SYNOPSIS
  Create dedicated, non-destructive MySQL schema for OTC FX CSV paper research.
#>
[CmdletBinding()]
param([switch]$PrintOnly)
$ErrorActionPreference='Stop'
if($PrintOnly) {
    Write-Output 'CREATE_DATABASE=trading_bot_forex'
    Write-Output 'EXISTING_DATABASES=UNCHANGED'
    return
}
$containerName='trading-bot-mysql-local'
$found=@(& docker ps -a --filter "name=^/$containerName$" --format '{{.ID}}')
if($LASTEXITCODE -ne 0 -or $found.Count -ne 1) {
    throw 'Start the EXISTING local trading-bot-mysql-local MySQL container first; no container deletion/recreation is performed.'
}
$imageName=(& docker inspect --format '{{.Config.Image}}' $containerName)
if($LASTEXITCODE -ne 0 -or $imageName -notmatch '(^|/)mysql:8\.4($|[.-])') {
    throw 'Existing container is not the expected MySQL 8.4. Inspect Docker before proceeding.'
}
$running=(& docker inspect --format '{{.State.Running}}' $containerName)
if($running.Trim() -ne 'true') {
    & docker start $containerName | Out-Null
    if($LASTEXITCODE -ne 0) { throw 'MySQL container failed to start' }
}
$sql=@'
CREATE DATABASE IF NOT EXISTS trading_bot_forex CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
GRANT ALL PRIVILEGES ON trading_bot_forex.* TO 'trading'@'%';
FLUSH PRIVILEGES;
'@
$sql | & docker exec -i $containerName mysql --user=root --password=root_local_only
if($LASTEXITCODE -ne 0) { throw 'Could not create the isolated FX research schema' }
Write-Host 'OK: trading_bot_forex ready. Existing Binance/MySQL schemas unchanged.'
