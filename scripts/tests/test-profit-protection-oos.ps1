# Offline regression: no DB, Binance, OpenAI or order API calls.
$ErrorActionPreference='Stop'
$runner=Join-Path (Split-Path $PSScriptRoot -Parent) 'validate-profit-protection-oos.ps1'
if (-not (Test-Path $runner)) { throw 'OOS runner not found' }
$tokens=$null
$parseErrors=$null
[System.Management.Automation.Language.Parser]::ParseFile(
    (Resolve-Path $runner).Path,[ref]$tokens,[ref]$parseErrors) | Out-Null
if ($parseErrors.Count -gt 0) {
    throw ("PowerShell parser errors: {0}" -f ($parseErrors | ForEach-Object Message | Out-String))
}
$argsPlan=@{
    Symbol='SOLUSDT'; Setup='RETEST'; Source='testnet'
    AsOf=[long]1791845999999; StartWindow=4; Windows=6; PrintOnly=$true
}
$raw=@(& $runner @argsPlan)
$lines=@($raw | Where-Object { $_ -is [string] })
foreach ($required in @(
    'OOS_START_WINDOW=4',
    'OOS_END_WINDOW=9',
    'OOS_HOURS_PER_WINDOW=300',
    'OOS_1M_CANDLES_PER_WINDOW=18000',
    'OOS_5M_CANDLES_PER_WINDOW=3600',
    'OOS_NO_LUNA=true',
    'OOS_NO_ORDERS=true'
)) {
    if ($lines -notcontains $required) { throw ("Missing plan field {0}" -f $required) }
}
$windows=@($lines | Where-Object { $_ -like 'OOS_WINDOW=*' })
if ($windows.Count -ne 6) { throw ("Expected six OOS windows, got {0}" -f $windows.Count) }
[long]$period=300L*3600000L
[long]$asOf=1791845999999
for ($index=0;$index -lt 6;$index++) {
    $row=$windows[$index]
    if ($row -notmatch '^OOS_WINDOW=(\d+);FROM=(\d+);ASOF=(\d+)$') {
        throw ("Invalid window description: {0}" -f $row)
    }
    [int]$w=[int]$Matches[1]
    [long]$from=[long]$Matches[2]
    [long]$cutoff=[long]$Matches[3]
    [long]$expectedEnd=$asOf-([long]($w-1)*$period)
    [long]$expectedStart=$expectedEnd-$period+1
    if ($w -ne (4+$index) -or $from -ne $expectedStart -or
        $cutoff -ne $expectedEnd -or
        $cutoff-$from+1 -ne $period) {
        throw ("Window {0}: overlap, shifted time, or incorrect candle count" -f $w)
    }
}
Write-Host 'PASS: 6 independent OOS windows of 300h (18000x1m, 3600x5m)'
