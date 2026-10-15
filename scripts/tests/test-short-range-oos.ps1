# Offline invariant check: no HTTP calls, database, LLM, or orders.
$ErrorActionPreference='Stop'
$runner=Join-Path (Split-Path $PSScriptRoot -Parent) 'validate-short-range-oos.ps1'
$tokens=$null;$errors=$null
[System.Management.Automation.Language.Parser]::ParseFile(
    (Resolve-Path $runner).Path,[ref]$tokens,[ref]$errors) | Out-Null
if($errors.Count -gt 0){
    throw ("Invalid PowerShell syntax: {0}" -f ($errors | ForEach-Object Message | Out-String))
}
$plan=@(& $runner -PrintOnly)
foreach($line in @(
    'SHORT_RANGE_OOS_START_WINDOW=28',
    'SHORT_RANGE_OOS_END_WINDOW=33',
    'SHORT_RANGE_WINDOWS=6',
    'SHORT_RANGE_FROZEN=true',
    'SHORT_RANGE_UNTOUCHED_WINDOWS=true',
    'SHORT_RANGE_CANDLES_PER_WINDOW=3600',
    'SHORT_RANGE_RANGE_BARS_15M=30',
    'SHORT_RANGE_WAIT_MINUTES=60',
    'SHORT_RANGE_THIRDS=3',
    'SHORT_RANGE_MARKET_SYMBOLS=SOLUSDT',
    'SHORT_RANGE_SCENARIOS=CONTROL,BLOCK_ALL_15M_RANGE_SHORTS,UPPER_THIRD_OF_30_CLOSED_15M_BARS',
    'SHORT_RANGE_SOURCE=TESTNET',
    'SHORT_RANGE_NO_LUNA=true',
    'SHORT_RANGE_NO_ORDERS=true',
    'SHORT_RANGE_EXPECTED_CAPITAL=1500'
)){
    if($plan -notcontains $line){throw ("Missing frozen SHORT range invariant: {0}" -f $line)}
}
$rows=@($plan | Where-Object {$_ -like 'SHORT_RANGE_WINDOW=*'})
if($rows.Count -ne 6){throw 'Expected six new SHORT RANGE windows'}
[long]$period=300L*3600000L
for($i=0;$i -lt 6;$i++){
    if($rows[$i] -notmatch '^SHORT_RANGE_WINDOW=(\d+);FROM=(\d+);ASOF=(\d+)$'){
        throw "Window line invalid: $($rows[$i])"
    }
    [long]$number=[long]$Matches[1]
    [long]$start=[long]$Matches[2]
    [long]$end=[long]$Matches[3]
    [long]$expectedEnd=1791845999999-([long]($number-1)*$period)
    if($number -ne ($i+28) -or $end-$start+1 -ne $period -or
       $end -ne $expectedEnd){throw "OOS period mismatch $number"}
}
$three=@(& $runner -PrintOnly -StartWindow 34 -Windows 3)
if(@($three | Where-Object {$_ -like 'SHORT_RANGE_WINDOW=*'}).Count -ne 3){
    throw '3-window extension invalid'
}
$source=Get-Content -LiteralPath $runner -Raw
foreach($marker in @(
    '/compare-short-range','rangeShortBlockedSignals',
    'BLOCK_ALL_15M_RANGE_SHORTS','UPPER_THIRD_OF_30_CLOSED_15M_BARS',
    'Near $closedSum','TESTNET','-SyncHistory'
)){
    if(-not $source.Contains($marker)){throw "Missing code marker: $marker"}
}
if($source -match '(?im)^\s*\$windows\s*(\+?=)\s*@\('){
    throw 'PowerShell case-insensitive collision with typed $Windows parameter'
}
Write-Host 'PASS: frozen 3-scenario causal 30x15m RANGE short OOS, 6x300h, no orders'
