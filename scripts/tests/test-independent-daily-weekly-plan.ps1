$ErrorActionPreference='Stop'
$repo=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$start=Join-Path $repo 'scripts/start-backtest.ps1'
$sync=Join-Path $repo 'scripts/sync-independent-daily-weekly.ps1'

$old=@(& $start -PrintOnly)
if($old -notcontains 'MARKET_DATA_INTERVALS=1m,5m,15m') {
    throw 'Existing backtest mode changed its default intervals'
}
$new=@(& $start -IncludeDailyWeekly -PrintOnly)
if($new -notcontains 'MARKET_DATA_INTERVALS=1m,5m,15m,1d,1w') {
    throw 'Daily + closed weekly research intervals were not enabled'
}
foreach($required in @('TRADING_ORDERS_ENABLED=false','LUNA_ENABLED=false')) {
    if($new -notcontains $required) {throw "Unsafe startup setting: $required"}
}

# Crosses Sunday; weekly history must include the previous Monday open.
$plan=@(& $sync -Symbols BTCUSDT,SOLUSDT -FromUtc 2026-01-04 -UntilUtc 2026-02-03 -PrintOnly)
if($plan.Count -ne 8) {
    throw "Expected 2 daily and 2 weekly <=30d slices per symbol, got $($plan.Count)"
}
if($plan -notcontains 'PLAN BTCUSDT 1d 2026-01-04..2026-02-02' -or
    $plan -notcontains 'PLAN BTCUSDT 1w 2025-12-29..2026-01-27') {
    throw 'UTC daily/weekly partitions are not stable'
}
if(@($plan|Where-Object {$_ -match '^PLAN SOLUSDT '}).Count -ne 4) {
    throw 'Symbols must remain independent in sync'
}
Write-Host 'PASS: daily/weekly opt-in, orders off, weekly aligned UTC chunking'
