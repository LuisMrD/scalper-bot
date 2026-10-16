$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$runner=Join-Path $root 'scripts/run-btc-15m-mixed-dynamic-holding.ps1'
$reference=Get-Content -Raw -Encoding UTF8 (Join-Path $root 'scripts/config/btc-15m-mixed-midas-hybrid-12x900h.json')|ConvertFrom-Json
$study=Get-Content -Raw -Encoding UTF8 (Join-Path $root 'scripts/config/btc-15m-mixed-dynamic-holding-12x900h.json')|ConvertFrom-Json
if(@($study.symbols).Count -ne 1 -or $study.symbols[0] -cne 'BTCUSDT' -or
   $study.windows -ne 12 -or $study.steps -ne 3600 -or
   [decimal]$study.capitalUsdt -ne 600 -or $study.contextCandles -ne 60 -or
   $study.exitPolicy -cne 'BASELINE' -or $study.maxHoldingCandles -ne 30 -or
   $study.midasWarmupBars -ne 1000 -or
   (($study.hybridPolicies -join ',') -cne 'BASELINE,HYBRID_LONG_BASELINE_SHORT_MIDAS_RETEST') -or
   (($study.holdingPolicies -join ',') -cne
        'BASELINE_30,HOLD_45,HOLD_60,DYNAMIC_TREND_60')) {
    throw 'BTC holding study must preserve the original hybrid/stop and compare exactly 4 duration policies'
}
foreach($p in $reference.PSObject.Properties) {
    if($p.Name -eq '_notice'){continue}
    $name=$p.Name
    $before=ConvertTo-Json -InputObject $p.Value -Depth 30 -Compress
    $after=ConvertTo-Json -InputObject $study.$name -Depth 30 -Compress
    if($before -cne $after){throw "Financial, MIDAS, stop, price-action or risk input changed: $name"}
}
$plan=@(& $runner -AsOfDateUtc 2026-10-15 -PrintOnly)
foreach($required in @(
    'RESEARCH=BTC_MIXED_DYNAMIC_HOLDING_INSAMPLE',
    'PRIMARY=15m','HIGHER_TREND=1d','WINDOW_HOURS=900',
    'TOTAL_HOURS=10800','ORDERS_ENABLED=false',
    'SYMBOLS=BTCUSDT','WINDOWS=12','STEPS=3600','PORTFOLIOS=96',
    'CAPITAL_USDT=600','MIDAS_WARMUP_BARS=1000',
    'HYBRID_ENTRY=LONG_BASELINE_SHORT_MIDAS_RETEST',
    'HOLDING_POLICIES=BASELINE_30,HOLD_45,HOLD_60,DYNAMIC_TREND_60',
    'ENTRY_ARMS=TREND_PULLBACK_MIXED,TREND_PULLBACK_MIXED_SHORT',
    'WINDOW=1 FROM=1788869699999 THROUGH=1792108799999',
    'WINDOW=12 FROM=1753229699999 THROUGH=1756468799999'
)) {
    if($plan -notcontains $required){throw "Frozen duration-study plan mismatch: $required"}
}
if(@($plan|Where-Object{$_ -match '^WINDOW=\d+ FROM='}).Count -ne 12) {
    throw 'Expected exactly 12 disjoint study windows'
}
$source=Get-Content -Raw -Encoding UTF8 $runner
foreach($text in @('dynamic-holding/status','backtest-dynamic-holding',
       'HISTORICAL_INSAMPLE_ONLY','HoldingPolicy=($arm -split ''__'',2)[1]',
       'MidasAnchorOpenTime=$midTrend.anchorOpenTime',
       'ExitPolicy=($arm -split ''__'',2)[1]',
       'AddHours(-900*$windows).AddDays(-75)')) {
    if(-not $source.Contains($text)){throw "Dynamic study export/safety not found: $text"}
}
Write-Host 'PASS: BTC 12x900h, 96 independent $600 hybrid-entry paper books, four original-stop holding rules'
