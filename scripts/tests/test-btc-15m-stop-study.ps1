$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$script=Join-Path $root 'scripts/run-btc-15m-stop-study.ps1'
$control=Get-Content -LiteralPath (Join-Path $root 'scripts/config/independent-15m-daily-research.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$experiment=Get-Content -LiteralPath (Join-Path $root 'scripts/config/btc-15m-stop-study.json') -Raw -Encoding UTF8 | ConvertFrom-Json
if(@($experiment.symbols).Count -ne 1 -or $experiment.symbols[0] -cne 'BTCUSDT') {
    throw 'Stop study MUST limit research to BTCUSDT'
}
foreach($property in $control.PSObject.Properties) {
    if($property.Name -in @('_notice','symbols')){continue}
    $name=$property.Name
    $a=ConvertTo-Json -InputObject $property.Value -Compress -Depth 30
    $b=ConvertTo-Json -InputObject $experiment.$name -Compress -Depth 30
    if($a -cne $b) {
        throw "Stop study changed existing signal, risk or fee input: $name"
    }
}
$policies=@('BASELINE','ATR_025','ATR_050','BREAK_EVEN_1_5R','ATR_TRAIL_2_5')
if(($experiment.stopPolicies -join ',') -cne ($policies -join ',')) {
    throw 'Stop policies must be explicitly frozen and include unchanged baseline'
}
$plan=@(& $script -AsOfDateUtc 2026-10-15 -PrintOnly)
foreach($expected in @(
    'RESEARCH=BTC_15M_STOP_POLICY_COMPARISON',
    'PRIMARY=15m','HIGHER_TREND=1d','ACTIVE_SETUP=PULLBACK_ONLY',
    'DAILY_FILTER=CLOSED_DAILY_EMA21_SLOPE','WINDOW_HOURS=600',
    'TOTAL_HOURS=3600','MAX_HOLDING_HOURS=7.5',
    'ORDERS_ENABLED=false','SYMBOLS=BTCUSDT',
    'WINDOWS=6','STEPS=2400','PORTFOLIOS=120',
    'POLICIES=BASELINE,ATR_025,ATR_050,BREAK_EVEN_1_5R,ATR_TRAIL_2_5',
    'DIAGNOSTICS_SHARED_ACROSS_POLICIES=true',
    'AS_OF=1792108799999',
    'WINDOW=1 FROM=1789949699999 THROUGH=1792108799999',
    'WINDOW=6 FROM=1779149699999 THROUGH=1781308799999'
)) {
    if($plan -notcontains $expected){throw "Stop experiment plan mismatch: $expected"}
}
if(@($plan|Where-Object {$_ -match '^WINDOW=\d+ FROM='}).Count -ne 6) {
    throw 'Stop experiment must retain exactly six historical 600h windows'
}
Write-Host 'PASS: BTC-only 120 independent paper portfolios, same entries/risk/costs, 5 frozen stops, 6 x 600h'
