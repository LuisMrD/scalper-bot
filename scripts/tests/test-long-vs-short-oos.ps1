# Static/offline validation, no API calls, no exchange, no orders.
$ErrorActionPreference='Stop'
$runner=Join-Path (Split-Path $PSScriptRoot -Parent) 'validate-long-vs-short-oos.ps1'
$tokens=$null;$errors=$null
[System.Management.Automation.Language.Parser]::ParseFile(
    (Resolve-Path $runner).Path,[ref]$tokens,[ref]$errors) | Out-Null
if($errors.Count -gt 0){
    throw ("PowerShell parse failure: {0}" -f ($errors | ForEach-Object Message | Out-String))
}
$rows=@(& $runner -PrintOnly)
foreach($expected in @(
    'LONG_SHORT_OOS_START_WINDOW=22',
    'LONG_SHORT_OOS_END_WINDOW=27',
    'LONG_SHORT_WINDOWS=6',
    'LONG_SHORT_FROZEN=true',
    'LONG_SHORT_UNTOUCHED_WINDOWS=true',
    'LONG_SHORT_CANDLES_PER_WINDOW=3600',
    'LONG_SHORT_HOURS=3,6,9',
    'LONG_SHORT_WAIT_MINUTES=60',
    'LONG_SHORT_MARKET_SYMBOLS=SOLUSDT,BTCUSDT,ETHUSDT',
    'LONG_SHORT_SCENARIOS=CONTROL,SHORT_ONLY,BOTH_UP_6H_DIP_3H,BOTH_UP_9H_DIP_3H',
    'LONG_SHORT_SOURCE=TESTNET',
    'LONG_SHORT_NO_LUNA=true',
    'LONG_SHORT_NO_ORDERS=true',
    'LONG_SHORT_EXPECTED_CAPITAL=1500'
)){
    if($rows -notcontains $expected){throw ("Missing LONG vs SHORT OOS assertion: {0}" -f $expected)}
}
$periods=@($rows | Where-Object {$_ -like 'LONG_SHORT_WINDOW=*'})
if($periods.Count -ne 6){throw "Expected 6 new windows; got $($periods.Count)"}
[long]$periodMs=300L*3600000L
for($i=0;$i -lt 6;$i++){
    if($periods[$i] -notmatch '^LONG_SHORT_WINDOW=(\d+);FROM=(\d+);ASOF=(\d+)$'){
        throw ("Invalid OOS line {0}" -f $periods[$i])
    }
    [long]$w=[long]$Matches[1]
    [long]$start=[long]$Matches[2]
    [long]$asOf=[long]$Matches[3]
    [long]$expectedAsOf=1791845999999-([long]($w-1)*$periodMs)
    if($w -ne ($i+22) -or $asOf -ne $expectedAsOf -or
       $asOf-$start+1 -ne $periodMs){
        throw ("Window {0} does not match 300-hour disjoint OOS plan" -f $w)
    }
}
# The last day of the previous, inspected w21 is more recent than w22.
[long]$previousW21End=1791845999999-(20L*$periodMs)
[long]$newW22End=1791845999999-(21L*$periodMs)
if($newW22End -ge $previousW21End){throw 'Previous window overlaps new OOS'}
$small=@(& $runner -PrintOnly -StartWindow 28 -Windows 3)
if(@($small | Where-Object {$_ -like 'LONG_SHORT_WINDOW=*'}).Count -ne 3){
    throw 'Magic 3-window format is broken'
}
Write-Host 'PASS: 4 LONG vs SHORT portfolios, windows 22..27, 6x300h, no HTTP/Luna/orders'
