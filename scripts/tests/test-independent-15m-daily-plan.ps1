$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$script=Join-Path $root 'scripts/run-independent-15m-daily.ps1'
$baseline=Get-Content -LiteralPath (Join-Path $root 'scripts/config/independent-daily-weekly-research.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$new=Get-Content -LiteralPath (Join-Path $root 'scripts/config/independent-15m-daily-research.json') -Raw -Encoding UTF8 | ConvertFrom-Json
# Original 1d rules and risk remain frozen; only interval/window definition
# and a text notice are allowed to differ.
foreach($property in $baseline.PSObject.Properties) {
    if($property.Name -in @('windows','steps','_notice')){continue}
    $name=$property.Name
    if($name -eq 'parameters') {
        if(($baseline.parameters | ConvertTo-Json -Compress -Depth 30) -cne
           ($new.parameters | ConvertTo-Json -Compress -Depth 30)) {
            throw 'The new 15m experiment must not retune ANY pullback threshold'
        }
    } elseif((ConvertTo-Json -InputObject $property.Value -Depth 30 -Compress) -cne
             (ConvertTo-Json -InputObject $new.$name -Depth 30 -Compress)) {
        throw "Unexpected change from 1d control to 15m study: $name"
    }
}
if($new.entryInterval -ne '15m' -or $new.trendInterval -ne '1d' -or
    $new.windows -ne 6 -or $new.steps -ne 2400 -or
    $new.maxHoldingCandles -ne 30) {
    throw '15m/1d plan must be exactly 6 x 2400 candles with frozen 30-bar holding limit'
}
$plan=@(& $script -PrintOnly -AsOfDateUtc 2026-10-15)
foreach($expected in @(
    'RESEARCH=INDEPENDENT_15M_WITH_CLOSED_DAILY_TREND',
    'PRIMARY=15m','HIGHER_TREND=1d',
    'ACTIVE_SETUP=PULLBACK_ONLY','DAILY_FILTER=CLOSED_DAILY_EMA21_SLOPE',
    'ORDERS_ENABLED=false','WINDOW_HOURS=600','TOTAL_HOURS=3600',
    'MAX_HOLDING_HOURS=7.5','CAPITAL_RESET_EACH_600H_WINDOW=true',
    'SYMBOLS=BTCUSDT,SOLUSDT,DOGEUSDT','WINDOWS=6','STEPS=2400',
    'PORTFOLIOS=72','CONTEXT_HIGHER_BARS=60','AS_OF=1792108799999',
    'WINDOW=1 FROM=1789949699999 THROUGH=1792108799999',
    'WINDOW=6 FROM=1779149699999 THROUGH=1781308799999'
)) {
    if($plan -notcontains $expected){throw "Invalid intraday research plan: $expected"}
}
$windows=@($plan|Where-Object {$_ -match '^WINDOW=\d+ FROM='})
if($windows.Count -ne 6){throw 'Expected six nonoverlapping 600-hour windows'}
$parsed=@()
foreach($line in $windows) {
    if($line -notmatch '^WINDOW=(\d+) FROM=(\d+) THROUGH=(\d+)$') {
        throw "Malformed window: $line"
    }
    $id=[int]$Matches[1]
    [long]$from=[long]$Matches[2]
    [long]$end=[long]$Matches[3]
    if(($end-$from)/900000+1 -ne 2400 -or ($end+1)%900000 -ne 0) {
        throw "Non-contiguous or non-closed 2400x15m period: $line"
    }
    $parsed+= [pscustomobject]@{Id=$id;From=$from;End=$end}
}
$chron=@($parsed|Sort-Object From)
for($i=1;$i -lt $chron.Count;$i++) {
    # Window N+1 starts exactly one 15m candle close after the
    # final closed candle of the preceding window.
    if($chron[$i].From -ne $chron[$i-1].End+900000) {
        throw "Adjacent 600-hour windows must have no gap or overlap: $($chron[$i-1].Id) -> $($chron[$i].Id)"
    }
}
Write-Host 'PASS: six disjoint 600-hour 15m/1d paper windows, unchanged price-action/VPA/risk inputs'
