# Offline validation of the dynamic filter comparison plan; no HTTP or trading.
$ErrorActionPreference='Stop'
$runner=Join-Path (Split-Path $PSScriptRoot -Parent) 'compare-entry-filters.ps1'
$tokens=$null
$errors=$null
[System.Management.Automation.Language.Parser]::ParseFile(
    (Resolve-Path $runner).Path,[ref]$tokens,[ref]$errors) | Out-Null
if($errors.Count -gt 0){throw ("PowerShell syntax errors: {0}" -f ($errors | ForEach-Object Message | Out-String))}
$lines=@(& $runner -Symbol SOLUSDT -Windows 9 -AsOf 1791845999999 -PrintOnly)
foreach($mandatory in @(
    'ENTRY_FILTER_WINDOWS=9',
    'ENTRY_FILTER_TIMEFRAME=5m',
    'ENTRY_FILTER_CANDLES=3600',
    'ENTRY_FILTER_DYNAMIC_REPLAY=true',
    'ENTRY_FILTER_NO_LUNA=true',
    'ENTRY_FILTER_NO_ORDERS=true',
    'ENTRY_FILTER_SCENARIOS=NONE,BLOCK_SHORT_DOWN_CANDIDATE,BLOCK_LONG_NONE,BLOCK_BOTH'
)){
    if($lines -notcontains $mandatory){throw ("Missing invariant {0}" -f $mandatory)}
}
$windows=@($lines | Where-Object {$_ -like 'ENTRY_FILTER_WINDOW=*'})
if($windows.Count -ne 9){throw 'Expected 9 windows'}
for($i=0;$i -lt 9;$i++){
    if($windows[$i] -notmatch '^ENTRY_FILTER_WINDOW=(\d+);FROM=(\d+);ASOF=(\d+)$'){
        throw ("Malformed plan: {0}" -f $windows[$i])
    }
    [int]$w=[int]$Matches[1]
    [long]$from=[long]$Matches[2]
    [long]$until=[long]$Matches[3]
    [long]$expected=1791845999999-([long]$i*300L*3600000L)
    if($w -ne $i+1 -or $until -ne $expected -or $until-$from+1 -ne 300L*3600000L){
        throw ("Misaligned window: {0}" -f $w)
    }
}
Write-Host 'PASS: four dynamic entry filters, 9 non-overlapping 300h windows, safe offline'
