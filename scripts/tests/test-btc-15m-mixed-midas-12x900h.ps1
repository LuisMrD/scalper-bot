$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$runner=Join-Path $root 'scripts/run-btc-15m-mixed-midas-12x900h.ps1'
$baseline=Get-Content -Raw -Encoding UTF8 (Join-Path $root 'scripts/config/btc-15m-mixed-baseline-12x900h.json') | ConvertFrom-Json
$cfg=Get-Content -Raw -Encoding UTF8 (Join-Path $root 'scripts/config/btc-15m-mixed-midas-12x900h.json') | ConvertFrom-Json
if(@($cfg.symbols).Count -ne 1 -or $cfg.symbols[0] -cne 'BTCUSDT' -or
   $cfg.windows -ne 12 -or $cfg.steps -ne 3600 -or $cfg.contextCandles -ne 60 -or
   [decimal]$cfg.capitalUsdt -ne 600 -or $cfg.exitPolicy -cne 'BASELINE' -or
   $null -ne $cfg.exitPolicies -or [int]$cfg.midasWarmupBars -ne 1000 -or
   (($cfg.entryPolicies -join ',') -cne
       'BASELINE,MIDAS_TREND,MIDAS_PULLBACK_REJECTION')) {
    throw 'BTC MIDAS must freeze 3 entry policies, original stop, 1000 warmup and $600 bankroll'
}
foreach($p in $baseline.PSObject.Properties) {
    if($p.Name -eq '_notice'){continue}
    $name=$p.Name
    $a=ConvertTo-Json -InputObject $p.Value -Depth 30 -Compress
    $b=ConvertTo-Json -InputObject $cfg.$name -Depth 30 -Compress
    if($a -cne $b) {throw "MIDAS modified an existing stop, risk, entry or cost parameter: $name"}
}
$plan=@(& $runner -PrintOnly -AsOfDateUtc 2026-10-15)
foreach($need in @(
    'RESEARCH=BTC_15M_MIXED_MIDAS_12X900H',
    'PRIMARY=15m','HIGHER_TREND=1d','ACTIVE_SETUP=PULLBACK_ONLY',
    'DAILY_FILTER=CLOSED_DAILY_EMA21_SLOPE',
    'ORDERS_ENABLED=false','SYMBOLS=BTCUSDT',
    'WINDOWS=12','STEPS=3600','PORTFOLIOS=72',
    'CAPITAL_USDT=600','EXIT_POLICY=BASELINE',
    'ENTRY_POLICIES=BASELINE,MIDAS_TREND,MIDAS_PULLBACK_REJECTION',
    'MIDAS_WARMUP_BARS=1000',
    'ENTRY_ARMS=TREND_PULLBACK_MIXED,TREND_PULLBACK_MIXED_SHORT',
    'MAX_HOLDING_HOURS=7.5','WINDOW_HOURS=900',
    'TOTAL_HOURS=10800','CAPITAL_RESET_EACH_900H_WINDOW=true',
    'DIAGNOSTICS=TWO_SHARED_SETUP_EVALUATIONS_PLUS_MIDAS_POLICIES',
    'WINDOW=1 FROM=1788869699999 THROUGH=1792108799999',
    'WINDOW=12 FROM=1753229699999 THROUGH=1756468799999'
)) {
    if($plan -notcontains $need){throw "MIDAS offline plan differs: $need"}
}
$windowLines=@($plan|Where-Object{$_ -match '^WINDOW=\d+ FROM=\d+ THROUGH=\d+$'})
if($windowLines.Count -ne 12){throw 'Exactly twelve MIDAS windows required'}
$durationMs=3600*900000L
$parsed=@(foreach($line in $windowLines){
    $line -match '^WINDOW=(\d+) FROM=(\d+) THROUGH=(\d+)$' | Out-Null
    [pscustomobject]@{Id=[int]$Matches[1];From=[long]$Matches[2];End=[long]$Matches[3]}
})
$chron=@($parsed | Sort-Object From)
for($i=0;$i -lt $chron.Count;$i++){
    if($chron[$i].End-$chron[$i].From -ne $durationMs-900000L)
        {throw 'MIDAS window duration incorrect'}
    if($i -gt 0 -and $chron[$i].From -ne $chron[$i-1].End+900000L)
        {throw 'MIDAS windows overlap or contain a gap'}
}
$source=Get-Content -Raw -Encoding UTF8 $runner
foreach($part in @(
    'backtest-midas',
    'midas-study/status',
    'MidasValue=$midTrend.value',
    'MidasTrendAllowed=$midTrend.allowed',
    'MidasRetestAllowed=$midRetest.allowed',
    'MidasAnchorOpenTime=$midTrend.anchorOpenTime',
    "ExitPolicy='BASELINE'",
    'EntryPolicy=($arm -split ''__'',2)[1]',
    'AddHours(-10800).AddDays(-75)',
    'interval=1d&limit=60&asOf=$firstStudyClose'
)) {
    if(-not $source.Contains($part)) {throw "MIDAS runner contract not satisfied: $part"}
}
Write-Host 'PASS: BTC $600, 12x900h, 72 paper books, 3 causal MIDAS entries, unchanged protective stop'
