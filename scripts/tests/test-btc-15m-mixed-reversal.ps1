$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$script=Join-Path $root 'scripts/run-btc-15m-mixed-reversal.ps1'
$old=Get-Content -LiteralPath (Join-Path $root 'scripts/config/independent-15m-daily-research.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$study=Get-Content -LiteralPath (Join-Path $root 'scripts/config/btc-15m-mixed-reversal-study.json') -Raw -Encoding UTF8 | ConvertFrom-Json
if(@($study.symbols).Count -ne 1 -or $study.symbols[0] -cne 'BTCUSDT')
    {throw 'Only BTCUSDT is permitted'}
foreach($prop in $old.PSObject.Properties) {
    if($prop.Name -in @('symbols','_notice')) {continue}
    $name=$prop.Name
    $expected=ConvertTo-Json -InputObject $prop.Value -Depth 30 -Compress
    $actual=ConvertTo-Json -InputObject $study.$name -Depth 30 -Compress
    if($actual -cne $expected) {throw "Study changed existing entry or risk parameter $name"}
}
if(($study.exitPolicies -join ',') -cne 'BASELINE,PRICE_ACTION_VOLUME_REVERSAL')
    {throw 'Only frozen baseline and reversal exit policies are allowed'}
$plan=@(& $script -AsOfDateUtc 2026-10-15 -PrintOnly)
foreach($expected in @(
    'RESEARCH=BTC_15M_MIXED_REVERSAL_EXIT_COMPARISON',
    'PRIMARY=15m','HIGHER_TREND=1d','ACTIVE_SETUP=PULLBACK_ONLY',
    'DAILY_FILTER=CLOSED_DAILY_EMA21_SLOPE',
    'WINDOW_HOURS=600','TOTAL_HOURS=3600','MAX_HOLDING_HOURS=7.5',
    'ORDERS_ENABLED=false','SYMBOLS=BTCUSDT','WINDOWS=6','STEPS=2400',
    'PORTFOLIOS=24',
    'ENTRY_ARMS=TREND_PULLBACK_MIXED,TREND_PULLBACK_MIXED_SHORT',
    'EXIT_POLICIES=BASELINE,PRICE_ACTION_VOLUME_REVERSAL',
    'DIAGNOSTICS_SHARED_ACROSS_EXIT_POLICIES=true',
    'WINDOW=1 FROM=1789949699999 THROUGH=1792108799999',
    'WINDOW=6 FROM=1779149699999 THROUGH=1781308799999'
)){
    if($plan -notcontains $expected){throw "Missing offline reversal plan: $expected"}
}
if(@($plan|Where-Object{$_ -match '^WINDOW=\d+ FROM='}).Count -ne 6)
    {throw 'Six historical 600h windows required'}
$source=Get-Content -LiteralPath $script -Raw -Encoding UTF8
if($source -notmatch "BaseSetup=\(\$arm -split '__',2\)\[0\]" -or
   $source -notmatch "ExitPolicy=\(\$arm -split '__',2\)\[1\]") {
    throw 'Composite arm must preserve all underscores in BaseSetup and ExitPolicy'
}
Write-Host 'PASS: BTC 2 mixed setups x 2 exit policies x 6 frozen 600h paper windows'
