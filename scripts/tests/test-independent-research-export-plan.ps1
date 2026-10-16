$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$script=Join-Path $root 'scripts/run-independent-daily-weekly.ps1'
$config=Join-Path $root 'scripts/config/independent-daily-weekly-research.json'
$study=Get-Content -LiteralPath $config -Raw | ConvertFrom-Json
if([int]$study.windows -ne 6 -or [int]$study.steps -ne 30 -or
    @($study.symbols).Count -ne 3 -or [int]$study.contextCandles -ne 60) {
    throw 'Example study must state its experimental 6x30d/3-coin configuration'
}
$plan=@(& $script -AsOfDateUtc 2026-10-15 -PrintOnly)
foreach($expected in @(
    'RESEARCH=INDEPENDENT_DAILY_WITH_CLOSED_WEEKLY_TREND',
    'PRIMARY=1d','HIGHER_TREND=1w','ORDERS_ENABLED=false',
    'FINANCIAL_INPUTS=EXPLICIT_RESEARCH_CONFIG_NOT_VIDEO_DEFAULTS',
    'SYMBOLS=BTCUSDT,SOLUSDT,DOGEUSDT','WINDOWS=6','STEPS=30',
    'CONTEXT_WEEKS=60','PORTFOLIOS=54','AS_OF=1792108799999',
    'WINDOW=1 FROM=1789603199999 THROUGH=1792108799999',
    'WINDOW=6 FROM=1776643199999 THROUGH=1779148799999'
)) {
    if($plan -notcontains $expected){throw "Independent research plan mismatch: $expected"}
}
if(@($plan|Where-Object {$_ -match '^WINDOW='}).Count -ne 6) {
    throw 'Expected exactly six research windows'
}
Write-Host 'PASS: independent 1d/1w 6x30 paper replay plan with no backend/network'
