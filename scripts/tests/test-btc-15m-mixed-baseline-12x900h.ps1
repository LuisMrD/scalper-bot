$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$runner=Join-Path $root 'scripts/run-btc-15m-mixed-baseline-12x900h.ps1'
$ref=Get-Content -Raw -Encoding UTF8 (Join-Path $root 'scripts/config/btc-15m-mixed-reversal-study.json') | ConvertFrom-Json
$cfg=Get-Content -Raw -Encoding UTF8 (Join-Path $root 'scripts/config/btc-15m-mixed-baseline-12x900h.json') | ConvertFrom-Json
if(@($cfg.symbols).Count -ne 1 -or $cfg.symbols[0] -cne 'BTCUSDT' -or
   $cfg.windows -ne 12 -or $cfg.steps -ne 3600 -or
   [decimal]$cfg.capitalUsdt -ne 600 -or $cfg.contextCandles -ne 60 -or
   $cfg.entryInterval -ne '15m' -or $cfg.trendInterval -ne '1d' -or
   $cfg.exitPolicy -cne 'BASELINE' -or $null -ne $cfg.exitPolicies) {
    throw 'Requires BTC-only 12x900h 15m/1d two mixed baseline accounts with $600 initial capital'
}
foreach($p in $ref.PSObject.Properties) {
    if($p.Name -in @('windows','steps','capitalUsdt','_notice','exitPolicies')){continue}
    $name=$p.Name
    $expected=ConvertTo-Json -InputObject $p.Value -Depth 30 -Compress
    $actual=ConvertTo-Json -InputObject $cfg.$name -Depth 30 -Compress
    if($actual -cne $expected) {
        throw "Entry, volume, risk, cost, stop or higher-timeframe setting changed: $name"
    }
}
$plan=@(& $runner -PrintOnly -AsOfDateUtc 2026-10-15)
foreach($item in @(
    'RESEARCH=BTC_15M_MIXED_BASELINE_12X900H',
    'PRIMARY=15m','HIGHER_TREND=1d','ACTIVE_SETUP=PULLBACK_ONLY',
    'DAILY_FILTER=CLOSED_DAILY_EMA21_SLOPE',
    'ORDERS_ENABLED=false','SYMBOLS=BTCUSDT',
    'WINDOWS=12','STEPS=3600','PORTFOLIOS=24',
    'CAPITAL_USDT=600','EXIT_POLICY=BASELINE',
    'ENTRY_ARMS=TREND_PULLBACK_MIXED,TREND_PULLBACK_MIXED_SHORT',
    'MAX_HOLDING_HOURS=7.5','WINDOW_HOURS=900',
    'TOTAL_HOURS=10800','CAPITAL_RESET_EACH_900H_WINDOW=true',
    'DIAGNOSTICS=ONLY_TWO_MIXED_SETUPS',
    'WINDOW=1 FROM=1788869699999 THROUGH=1792108799999',
    'WINDOW=12 FROM=1753229699999 THROUGH=1756468799999'
)) {
    if($plan -notcontains $item){throw "Historical study plan mismatch: $item"}
}
$windows=@($plan|Where-Object{$_ -match '^WINDOW=\d+ FROM=\d+ THROUGH=\d+$'})
if($windows.Count -ne 12){throw 'Expected exactly twelve disjoint 900h windows'}
$interval=900000L
$parsed=@(foreach($line in $windows){
    $line -match '^WINDOW=(\d+) FROM=(\d+) THROUGH=(\d+)$' | Out-Null
    [pscustomobject]@{Id=[int]$Matches[1];From=[long]$Matches[2];End=[long]$Matches[3]}
})
foreach($w in $parsed) {
    if(($w.End-$w.From) -ne (3600-1)*$interval -or
       ($w.End+1)%$interval -ne 0) { throw "Invalid 900h window $($w.Id)"}
}
$chron=@($parsed|Sort-Object From)
for($i=1;$i -lt $chron.Count;$i++) {
    if($chron[$i].From -ne $chron[$i-1].End+$interval)
        {throw 'Study windows have an overlap or a missing 15m candle'}
}
$source=Get-Content -Raw -Encoding UTF8 $runner
if(-not $source.Contains('interval=1d&limit=60&asOf=$firstStudyClose') -or
   -not $source.Contains('backtest-mixed-baseline-900h') -or
   -not $source.Contains("ExitPolicy='BASELINE'") -or
   -not $source.Contains('AddHours(-10800).AddDays(-75)')) {
    throw 'Historical preflight, dedicated baseline endpoint, or frozen exit policy missing'
}
Write-Host 'PASS: BTC only, 12 contiguous 900h windows, 24 independent $600 mixed baseline paper portfolios'
