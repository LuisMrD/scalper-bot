# Verify frozen LONG-context OOS schedule without invoking HTTP.
$ErrorActionPreference='Stop'
$runner=Join-Path (Split-Path $PSScriptRoot -Parent) 'validate-long-context-oos.ps1'
$tokens=$null;$errors=$null
[System.Management.Automation.Language.Parser]::ParseFile(
    (Resolve-Path $runner).Path,[ref]$tokens,[ref]$errors) | Out-Null
if($errors.Count -gt 0){throw ("Invalid PowerShell: {0}" -f ($errors | Out-String))}
$plan=@(& $runner -PrintOnly)
foreach($assertion in @(
    'LONG_CONTEXT_OOS_START_WINDOW=40',
    'LONG_CONTEXT_OOS_END_WINDOW=45',
    'LONG_CONTEXT_WINDOWS=6',
    'LONG_CONTEXT_FROZEN=true',
    'LONG_CONTEXT_UNTOUCHED_WINDOWS=true',
    'LONG_CONTEXT_CANDLES_PER_WINDOW=3600',
    'LONG_CONTEXT_WAIT_MINUTES=60',
    'LONG_CONTEXT_5M_BREAKOUT=UP_FOLLOW_THROUGH',
    'LONG_CONTEXT_15M_TRENDS=RANGE,UP',
    'LONG_CONTEXT_5M_VOLUME=NORMAL',
    'LONG_CONTEXT_MARKET_SYMBOLS=SOLUSDT',
    'LONG_CONTEXT_SCENARIOS=CONTROL,BLOCK_LONG_RANGE_FOLLOW_THROUGH,BLOCK_LONG_UP_NORMAL_VOLUME',
    'LONG_CONTEXT_SOURCE=TESTNET',
    'LONG_CONTEXT_NO_LUNA=true',
    'LONG_CONTEXT_NO_ORDERS=true',
    'LONG_CONTEXT_EXPECTED_CAPITAL=1500'
)){
    if($plan -notcontains $assertion){throw "Missing frozen invariant: $assertion"}
}
$periods=@($plan | Where-Object {$_ -like 'LONG_CONTEXT_WINDOW=*'})
if($periods.Count -ne 6){throw 'Expected six disjoint OOS windows'}
[long]$span=300L*3600000L
for($i=0;$i -lt 6;$i++){
    if($periods[$i] -notmatch '^LONG_CONTEXT_WINDOW=(\d+);FROM=(\d+);ASOF=(\d+)$'){
        throw "Malformed window $($periods[$i])"
    }
    [long]$number=[long]$Matches[1]
    [long]$from=[long]$Matches[2]
    [long]$asOf=[long]$Matches[3]
    if($number -ne 40+$i -or
       $asOf -ne (1791845999999-([long]($number-1)*$span)) -or
       $asOf-$from+1 -ne $span){throw "Invalid OOS window $number"}
}
$source=Get-Content -LiteralPath $runner -Raw
foreach($required in @(
    '/compare-long-context','longContextBlockedSignals',
    'longNetClosedUsdt','shortNetClosedUsdt',
    'BLOCK_LONG_UP_NORMAL_VOLUME','BLOCK_LONG_RANGE_FOLLOW_THROUGH',
    '-SyncHistory'
)){
    if(-not $source.Contains($required)){throw "Missing LONG-context API/contract field: $required"}
}
if($source -match '(?im)^\s*\$windows\s*\+?=\s*@\('){
    throw 'A result array must not shadow typed [int]$Windows'
}
Write-Host 'PASS: LONG context frozen w40-45, 3 policies, TESTNET-only, no orders/Luna'
