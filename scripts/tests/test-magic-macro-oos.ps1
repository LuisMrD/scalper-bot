# Offline: never hits MySQL, Binance, Luna, or order endpoints.
$ErrorActionPreference='Stop'
$runner=Join-Path (Split-Path $PSScriptRoot -Parent) 'validate-magic-macro-oos.ps1'
$tokens=$null;$errors=$null
[System.Management.Automation.Language.Parser]::ParseFile(
    (Resolve-Path $runner).Path,[ref]$tokens,[ref]$errors) | Out-Null
if($errors.Count -gt 0){
    throw ("Invalid PowerShell code: {0}" -f ($errors | ForEach-Object Message | Out-String))
}
$lines=@(& $runner -PrintOnly -StartWindow 16 -Windows 6 -AsOf 1791845999999)
foreach($line in @(
    'MAGIC_MACRO_OOS_START_WINDOW=16',
    'MAGIC_MACRO_OOS_END_WINDOW=21',
    'MAGIC_MACRO_WINDOWS=6',
    'MAGIC_MACRO_FROZEN=true',
    'MAGIC_MACRO_UNTOUCHED_WINDOWS=true',
    'MAGIC_MACRO_CANDLES_PER_WINDOW=3600',
    'MAGIC_MACRO_HOURS=3,6,9',
    'MAGIC_MACRO_WAIT_MINUTES=60',
    'MAGIC_MACRO_MARKET_SYMBOLS=SOLUSDT,BTCUSDT,ETHUSDT',
    'MAGIC_MACRO_SCENARIOS=NONE,BOTH_UP_3H,BOTH_UP_6H,BOTH_UP_9H,BOTH_UP_6H_DIP_3H,BOTH_UP_9H_DIP_3H',
    'MAGIC_MACRO_SOURCE=TESTNET',
    'MAGIC_MACRO_NO_LUNA=true',
    'MAGIC_MACRO_NO_ORDERS=true',
    'MAGIC_MACRO_EXPECTED_CAPITAL=1500'
)){
    if($lines -notcontains $line){throw ("Missing research invariant: {0}" -f $line)}
}
$periods=@($lines | Where-Object {$_ -like 'MAGIC_MACRO_WINDOW=*'})
if($periods.Count -ne 6){throw 'Expected exactly 6 untouched OOS windows'}
[long]$duration=300L*3600000L
for($i=0;$i -lt 6;$i++){
    if($periods[$i] -notmatch '^MAGIC_MACRO_WINDOW=(\d+);FROM=(\d+);ASOF=(\d+)$'){
        throw ("Invalid date record: {0}" -f $periods[$i])
    }
    [long]$window=[long]$Matches[1]
    [long]$from=[long]$Matches[2]
    [long]$asOf=[long]$Matches[3]
    [long]$expected=1791845999999-([long]($i+15)*$duration)
    if($window -ne $i+16 -or $asOf -ne $expected -or
       $asOf-$from+1 -ne $duration){
        throw ("Window {0} overlaps, is out of order, or has wrong duration" -f $window)
    }
}
$short=@(& $runner -PrintOnly -StartWindow 22 -Windows 3)
if(@($short | Where-Object {$_ -like 'MAGIC_MACRO_WINDOW=*'}).Count -ne 3){
    throw '3-window magic research plan failed'
}
Write-Host 'PASS: 3/6/9h magic macro, 6 untouched 300h windows, testnet and no orders'
