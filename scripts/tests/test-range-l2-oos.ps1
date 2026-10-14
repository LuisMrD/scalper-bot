# Offline schedule and contract guard: no HTTP, DB or exchange.
$ErrorActionPreference='Stop'
$runner=Join-Path (Split-Path $PSScriptRoot -Parent) 'validate-range-l2-oos.ps1'
$tokens=$null;$errors=$null
[System.Management.Automation.Language.Parser]::ParseFile(
    (Resolve-Path $runner).Path,[ref]$tokens,[ref]$errors) | Out-Null
if($errors.Count -gt 0){
    throw ("PowerShell syntax: {0}" -f ($errors | ForEach-Object Message | Out-String))
}
$plan=@(& $runner -PrintOnly)
foreach($row in @(
    'RANGE_L2_OOS_START_WINDOW=34',
    'RANGE_L2_OOS_END_WINDOW=39',
    'RANGE_L2_WINDOWS=6',
    'RANGE_L2_FROZEN=true',
    'RANGE_L2_UNTOUCHED_WINDOWS=true',
    'RANGE_L2_CANDLES_PER_WINDOW=3600',
    'RANGE_L2_RANGE_BARS_15M=30',
    'RANGE_L2_WAIT_MINUTES=60',
    'RANGE_L2_FAILURE_MAX_5M_BARS=9',
    'RANGE_L2_FIRST_ATTEMPT_MAX_5M_BARS=3',
    'RANGE_L2_REQUIRES_CONFIRMED_PIVOT_STOP=true',
    'RANGE_L2_MARKET_SYMBOLS=SOLUSDT',
    'RANGE_L2_SCENARIOS=CONTROL,BLOCK_ALL_15M_RANGE_SHORTS,REPLACE_RANGE_SHORT_RETEST_WITH_L2',
    'RANGE_L2_SOURCE=TESTNET',
    'RANGE_L2_NO_LUNA=true',
    'RANGE_L2_NO_ORDERS=true',
    'RANGE_L2_EXPECTED_CAPITAL=1500'
)){
    if($plan -notcontains $row){throw ("Missing L2 study invariant {0}" -f $row)}
}
$windows=@($plan | Where-Object {$_ -like 'RANGE_L2_WINDOW=*'})
if($windows.Count -ne 6){throw 'Expected six new 300-hour windows'}
[long]$duration=300L*3600000L
for($i=0;$i -lt 6;$i++){
    if($windows[$i] -notmatch '^RANGE_L2_WINDOW=(\d+);FROM=(\d+);ASOF=(\d+)$'){
        throw "Malformed L2 research window: $($windows[$i])"
    }
    [long]$id=[long]$Matches[1]
    [long]$from=[long]$Matches[2]
    [long]$asOf=[long]$Matches[3]
    if($id -ne $i+34 -or $asOf -ne (1791845999999-([long]($id-1)*$duration)) -or
       $asOf-$from+1 -ne $duration){
        throw "Overlapping or incorrect L2 research window $id"
    }
}
$source=Get-Content -LiteralPath $runner -Raw
foreach($marker in @(
    '/compare-range-l2',
    'REPLACE_RANGE_SHORT_RETEST_WITH_L2',
    'entrySetup',
    'longNetClosedUsdt',
    'shortNetClosedUsdt',
    'l2ClosedTrades',
    'l2Signals',
    'TESTNET',
    '-SyncHistory'
)){
    if(-not $source.Contains($marker)){throw "Missing L2 API contract $marker"}
}
if($source -match '(?im)^\s*\$windows\s*\+?=\s*@\('){
    throw 'Typed [int]$Windows was shadowed by an array (case insensitive)!'
}
Write-Host 'PASS: frozen w34..39, control/range/L2, 3/9/30/60 values, no orders'
