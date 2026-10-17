$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$runner=Join-Path $root 'scripts/run-btc-15m-mixed-entry-selection.ps1'
$before=Get-Content -Raw -Encoding UTF8 (Join-Path $root 'scripts/config/btc-15m-mixed-dynamic-holding-12x900h.json')|ConvertFrom-Json
$study=Get-Content -Raw -Encoding UTF8 (Join-Path $root 'scripts/config/btc-15m-mixed-entry-selection-12x900h.json')|ConvertFrom-Json
if(@($study.symbols).Count -ne 1 -or $study.symbols[0] -cne 'BTCUSDT' -or
   $study.windows -ne 12 -or $study.steps -ne 3600 -or
   $study.contextCandles -ne 60 -or
   [decimal]$study.capitalUsdt -ne 600 -or
   $study.maxHoldingCandles -ne 30 -or $study.exitPolicy -cne 'BASELINE' -or
   $study.midasWarmupBars -ne 1000 -or
   $study.selectedHoldingPolicy -cne 'DYNAMIC_TREND_60' -or
   $study.weekendBasis -cne 'NEXT_OPEN_UTC' -or
   $study.stopDistanceDefinition -cne 'SIGNAL_REFERENCE_TO_ORIGINAL_STRUCTURAL_STOP' -or
   (($study.entrySelectionPolicies -join ',') -cne
      'BASELINE,WEEKDAYS_ONLY,STOP_045_ONLY,COMBINED_045,STOP_050_ONLY,COMBINED_050')) {
    throw 'BTC entry-filter study changed frozen model/thresholds/config'
}
foreach($p in $before.PSObject.Properties) {
    if($p.Name -eq '_notice'){continue}
    $name=$p.Name
    $a=ConvertTo-Json -InputObject $p.Value -Depth 30 -Compress
    $b=ConvertTo-Json -InputObject $study.$name -Depth 30 -Compress
    if($a -cne $b) {
        throw "Entry-filter study modified previous price-action/volume/cost/stop/holding config: $name"
    }
}
$plan=@(& $runner -AsOfDateUtc 2026-10-15 -PrintOnly)
foreach($required in @(
    'RESEARCH=BTC_MIXED_ENTRY_SELECTION_INSAMPLE',
    'PRIMARY=15m','HIGHER_TREND=1d','ORDERS_ENABLED=false',
    'WINDOWS=12','WINDOW_HOURS=900','TOTAL_HOURS=10800',
    'STEPS=3600','SYMBOLS=BTCUSDT','PORTFOLIOS=144',
    'CAPITAL_USDT=600','MIDAS_WARMUP_BARS=1000',
    'HOLDING_POLICY=DYNAMIC_TREND_60',
    'ENTRY_SELECTION_POLICIES=BASELINE,WEEKDAYS_ONLY,STOP_045_ONLY,COMBINED_045,STOP_050_ONLY,COMBINED_050',
    'WEEKEND_BASIS=NEXT_OPEN_UTC',
    'STOP_DISTANCE_DEFINITION=SIGNAL_REFERENCE_TO_ORIGINAL_STRUCTURAL_STOP',
    'ENTRY_ARMS=TREND_PULLBACK_MIXED,TREND_PULLBACK_MIXED_SHORT',
    'WINDOW=1 FROM=1788869699999 THROUGH=1792108799999',
    'WINDOW=12 FROM=1753229699999 THROUGH=1756468799999'
)) {
    if($plan -notcontains $required) {
        throw "Frozen entry screen plan differs: $required"
    }
}
if(@($plan|Where-Object{$_ -match '^WINDOW=\d+ FROM=\d+ THROUGH=\d+$'}).Count -ne 12) {
    throw 'Expected 12 separate historical BTC windows'
}
$source=Get-Content -Raw -Encoding UTF8 $runner
foreach($needle in @(
    'backtest-entry-selection',
    'entry-selection/status',
    'BTC_MIXED_ENTRY_SELECTION_900H',
    'MidasAnchorOpenTime=$midTrend.anchorOpenTime',
    'SelectionNextOpenUtc=$baselineSelection.nextOpenUtc',
    'SelectionStopDistancePct=$baselineSelection.stopDistancePct',
    'SelectionCombined045Allowed=$selection.COMBINED_045.allowed',
    'SelectionCombined050Allowed=$selection.COMBINED_050.allowed',
    'EntrySelectionPolicy=($arm -split ''__'',2)[1]',
    "ExitPolicy='DYNAMIC_TREND_60'"
)) {
    if(-not $source.Contains($needle)) {
        throw "Missing required entry selection export/contract: $needle"
    }
}
Write-Host 'PASS: 144 paper books across 12 BTC windows, 0.45/0.50 weekday/stop screening, unchanged MIDAS hybrid dynamic-60'
