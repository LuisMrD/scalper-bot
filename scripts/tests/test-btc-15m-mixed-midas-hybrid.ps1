$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$script=Join-Path $root 'scripts/run-btc-15m-mixed-midas-hybrid.ps1'
$existing=Get-Content -Raw -Encoding UTF8 (Join-Path $root 'scripts/config/btc-15m-mixed-midas-12x900h.json')|ConvertFrom-Json
$cfg=Get-Content -Raw -Encoding UTF8 (Join-Path $root 'scripts/config/btc-15m-mixed-midas-hybrid-12x900h.json')|ConvertFrom-Json
if(@($cfg.symbols).Count -ne 1 -or $cfg.symbols[0] -cne 'BTCUSDT' -or
   [int]$cfg.windows -ne 12 -or [int]$cfg.steps -ne 3600 -or
   [decimal]$cfg.capitalUsdt -ne 600 -or $cfg.exitPolicy -cne 'BASELINE' -or
   [int]$cfg.midasWarmupBars -ne 1000 -or
   $cfg.selectionCutoffUtc -cne '2026-10-15T23:59:59.999Z' -or
   (($cfg.hybridPolicies -join ',') -cne
      'BASELINE,HYBRID_LONG_BASELINE_SHORT_MIDAS_RETEST')) {
    throw 'Hybrid must freeze $600, BTC, original stop, MIDAS 1000 bars and selected directional gate'
}
foreach($p in $existing.PSObject.Properties){
    if($p.Name -in @('_notice','entryPolicies')){continue}
    $name=$p.Name
    $a=ConvertTo-Json -InputObject $p.Value -Depth 30 -Compress
    $b=ConvertTo-Json -InputObject $cfg.$name -Depth 30 -Compress
    if($a -cne $b){throw "Hybrid modified existing price-action/volume/finance input: $name"}
}
$plan=@(& $script -PrintOnly -AsOfDateUtc 2026-10-15)
foreach($need in @(
    'RESEARCH=BTC_MIDAS_HYBRID_INSAMPLE_DIAGNOSTIC',
    'PRIMARY=15m','HIGHER_TREND=1d',
    'DAILY_FILTER=CLOSED_DAILY_EMA21_SLOPE','ORDERS_ENABLED=false',
    'SYMBOLS=BTCUSDT','WINDOWS=12','STEPS=3600','PORTFOLIOS=48',
    'CAPITAL_USDT=600','EXIT_POLICY=BASELINE',
    'HYBRID_POLICIES=BASELINE,HYBRID_LONG_BASELINE_SHORT_MIDAS_RETEST',
    'SELECTION_CUTOFF_UTC=2026-10-15T23:59:59.999Z',
    'MIDAS_WARMUP_BARS=1000','OOS_MODE=false',
    'ENTRY_ARMS=TREND_PULLBACK_MIXED,TREND_PULLBACK_MIXED_SHORT',
    'TOTAL_HOURS=10800',
    'WINDOW=1 FROM=1788869699999 THROUGH=1792108799999',
    'WINDOW=12 FROM=1753229699999 THROUGH=1756468799999'
)) {
    if($plan -notcontains $need){throw "Missing frozen in-sample hybrid plan: $need"}
}
if(@($plan|Where-Object{$_ -match '^WINDOW=\d+ FROM=\d+ THROUGH=\d+$'}).Count -ne 12)
    {throw 'Expected 12 frozen 900h in-sample windows'}
$rejected=$false
try {
    & $script -PrintOnly -ProspectiveOos -AsOfDateUtc 2026-10-15 *> $null
} catch {
    $rejected=$true
}
if(-not $rejected){throw 'Historical selection data was incorrectly advertised as out-of-sample'}
$oos=@(& $script -PrintOnly -ProspectiveOos -AsOfDateUtc 2026-11-22)
foreach($need in @(
    'RESEARCH=BTC_MIDAS_HYBRID_PROSPECTIVE_OOS',
    'OOS_MODE=true','WINDOWS=1','PORTFOLIOS=4',
    'CAPITAL_USDT=600','EXIT_POLICY=BASELINE'
)) {
    if($oos -notcontains $need){throw "Missing prospective OOS protection: $need"}
}
if(@($oos|Where-Object{$_ -match '^WINDOW=\d+ FROM='}).Count -ne 1)
    {throw 'OOS must use one forward 900h period only'}
$source=Get-Content -Raw -Encoding UTF8 $script
foreach($phrase in @(
    'backtest-midas-hybrid',
    "'-oos'",
    'mixed-hybrid/status',
    'MidasAnchorOpenTime=$midTrend.anchorOpenTime',
    "ExitPolicy='BASELINE'",
    'HybridPolicy=($arm -split ''__'',2)[1]',
    'AddHours(-900*$windows).AddDays(-75)'
)){
    if(-not $source.Contains($phrase)){throw "Missing hybrid runner protection: $phrase"}
}
Write-Host 'PASS: 48 BTC hybrid historical accounts and 4 strictly future OOS paper accounts, original stop, no mixed time windows'
