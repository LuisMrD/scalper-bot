# Offline contract test; do not touch HTTP, DB or exchange.
$ErrorActionPreference='Stop'
$runner=Join-Path (Split-Path $PSScriptRoot -Parent) 'validate-reentry-oos.ps1'
$tokens=$null;$errors=$null
[System.Management.Automation.Language.Parser]::ParseFile(
    (Resolve-Path $runner).Path,[ref]$tokens,[ref]$errors) | Out-Null
if($errors.Count -gt 0){
    throw ("Invalid PowerShell: {0}" -f ($errors | ForEach-Object Message | Out-String))
}
$rows=@(& $runner -PrintOnly -Symbol SOLUSDT -StartWindow 10 -Windows 6 -AsOf 1791845999999)
foreach($field in @(
    'REENTRY_OOS_START_WINDOW=10',
    'REENTRY_OOS_END_WINDOW=15',
    'REENTRY_WINDOWS=6',
    'REENTRY_OOS_FROZEN=true',
    'REENTRY_OOS_NO_PREVIOUS_WINDOWS=true',
    'REENTRY_CANDLES_PER_WINDOW=3600',
    'REENTRY_PRIMARY=5m',
    'REENTRY_SCENARIOS=NONE,WAIT_WHILE_15M_RANGE,WAIT_12_BARS',
    'REENTRY_BLOCK_BOTH=true',
    'REENTRY_SOURCE=TESTNET',
    'REENTRY_CONTEXT=5m,15m',
    'REENTRY_HOURS_PER_WINDOW=300',
    'REENTRY_NO_LUNA=true',
    'REENTRY_NO_ORDERS=true',
    'REENTRY_EXPECTED_CAPITAL=1500'
)){
    if($rows -notcontains $field){throw ("OOS plan missing {0}" -f $field)}
}
$windows=@($rows | Where-Object {$_ -like 'REENTRY_WINDOW=*'})
if($windows.Count -ne 6){throw 'Exactly six OOS windows required'}
for($i=0;$i -lt 6;$i++){
    if($windows[$i] -notmatch '^REENTRY_WINDOW=(\d+);FROM=(\d+);ASOF=(\d+)$'){
        throw ("Malformed OOS window {0}" -f $windows[$i])
    }
    [long]$num=[long]$Matches[1]
    [long]$from=[long]$Matches[2]
    [long]$until=[long]$Matches[3]
    [long]$expectedEnd=1791845999999-([long]($i+9)*300L*3600000L)
    if($num -ne (10+$i) -or $until -ne $expectedEnd -or
       $until-$from+1 -ne (300L*3600000L)){
        throw ("OOS overlap, gap, or wrong length in window {0}" -f $num)
    }
}
$oldEnd=1791845999999-(8L*300L*3600000L)
if(([long]($windows[0] -replace '^.*;ASOF=','')) -ge $oldEnd){
    throw 'OOS must end before previous window 9 ends'
}
$rows2=@(& $runner -PrintOnly -StartWindow 12 -Windows 2 -AsOf 1791845999999)
if(@($rows2 | Where-Object {$_ -like 'REENTRY_WINDOW=*'}).Count -ne 2){
    throw 'Custom OOS window count invalid'
}
Write-Host 'PASS: frozen three strategies, six disjoint new 300h windows, no orders'
