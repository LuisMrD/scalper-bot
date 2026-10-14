# Offline guard for the SOL/BTC/ETH macro association experiment.
$ErrorActionPreference='Stop'
$runner=Join-Path (Split-Path $PSScriptRoot -Parent) 'analyze-cross-asset-macro.ps1'
$tokens=$null
$errors=$null
[System.Management.Automation.Language.Parser]::ParseFile(
    (Resolve-Path $runner).Path,[ref]$tokens,[ref]$errors) | Out-Null
if($errors.Count -gt 0){
    throw ("Invalid PowerShell syntax: {0}" -f ($errors | ForEach-Object Message | Out-String))
}
$printed=@(& $runner -Symbol SOLUSDT -StartWindow 1 -Windows 15 -PrintOnly)
foreach($invariant in @(
    'MACRO_START_WINDOW=1',
    'MACRO_END_WINDOW=15',
    'MACRO_WINDOWS=15',
    'MACRO_SYMBOLS=SOLUSDT,BTCUSDT,ETHUSDT',
    'MACRO_HORIZONS=1h,4h,24h',
    'MACRO_SOURCE=TESTNET_CLOSED_15M',
    'MACRO_POLICIES=NONE,WAIT_WHILE_15M_RANGE,WAIT_12_BARS',
    'MACRO_NO_ORDERS=true',
    'MACRO_NO_LUNA=true',
    'MACRO_OBSERVATIONAL_ONLY=true'
)){
    if($printed -notcontains $invariant){throw ("Macro invariant missing: {0}" -f $invariant)}
}
$plan=@($printed | Where-Object {$_ -like 'MACRO_WINDOW=*'})
if($plan.Count -ne 15){throw 'Expected 15 macro research windows'}
[long]$periodMs=300L*3600000L
for($i=0;$i -lt 15;$i++){
    if($plan[$i] -notmatch '^MACRO_WINDOW=(\d+);FROM=(\d+);ASOF=(\d+)$'){
        throw ("Malformed macro window {0}" -f $plan[$i])
    }
    $idx=[int]$Matches[1]
    [long]$from=[long]$Matches[2]
    [long]$until=[long]$Matches[3]
    if($idx -ne ($i+1) -or $until -ne (1791845999999-([long]$i*$periodMs)) -or
       $until-$from+1 -ne $periodMs){
        throw ("Macro window {0} is not a disjoint 300-hour period" -f $idx)
    }
}
$printedOos=@(& $runner -StartWindow 10 -Windows 6 -PrintOnly)
if(@($printedOos | Where-Object {$_ -like 'MACRO_WINDOW=*'}).Count -ne 6 -or
   $printedOos -notcontains 'MACRO_START_WINDOW=10' -or
   $printedOos -notcontains 'MACRO_END_WINDOW=15'){
    throw 'Macro OOS subgroup plan incorrect'
}
Write-Host 'PASS: 15x300h macro lookback study, no orders/Luna, 3 frozen policies'
