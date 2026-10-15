# Validates the 9-window reentry experiment without HTTP, market data, or orders.
$ErrorActionPreference='Stop'
$script=Join-Path (Split-Path $PSScriptRoot -Parent) 'compare-reentry-waits.ps1'
if(-not (Test-Path $script)){throw 'Missing reentry wait runner'}
$tokens=$null
$parseErrors=$null
[System.Management.Automation.Language.Parser]::ParseFile(
    (Resolve-Path $script).Path,[ref]$tokens,[ref]$parseErrors) | Out-Null
if($parseErrors.Count -gt 0){
    throw ("Invalid PowerShell syntax: {0}" -f ($parseErrors | ForEach-Object Message | Out-String))
}
$rows=@(& $script -PrintOnly -Windows 9 -AsOf 1791845999999 -Symbol SOLUSDT)
foreach($required in @(
    'REENTRY_WINDOWS=9',
    'REENTRY_PRIMARY=5m',
    'REENTRY_CANDLES_PER_WINDOW=3600',
    'REENTRY_SCENARIOS=NONE,WAIT_WHILE_15M_RANGE,WAIT_3_BARS,WAIT_12_BARS',
    'REENTRY_BLOCK_BOTH=true',
    'REENTRY_NO_LUNA=true',
    'REENTRY_NO_ORDERS=true',
    'REENTRY_EXPECTED_CAPITAL=1500'
)){
    if($rows -notcontains $required){throw ("Missing invariant: {0}" -f $required)}
}
$windows=@($rows | Where-Object {$_ -like 'REENTRY_WINDOW=*'})
if($windows.Count -ne 9){throw 'Expected nine disjoint windows'}
for($i=0;$i -lt 9;$i++){
    if($windows[$i] -notmatch '^REENTRY_WINDOW=(\d+);FROM=(\d+);ASOF=(\d+)$'){
        throw ("Invalid window: {0}" -f $windows[$i])
    }
    [int]$w=[int]$Matches[1]
    [long]$from=[long]$Matches[2]
    [long]$until=[long]$Matches[3]
    [long]$end=1791845999999-([long]$i*300L*3600000L)
    if($w -ne ($i+1) -or $until -ne $end -or $until-$from+1 -ne 300L*3600000L){
        throw ("Window {0} overlaps, is shifted or has wrong size" -f $w)
    }
}
Write-Host 'PASS: 4 reentry policies, 9x300h windows, no HTTP/Luna/orders'
