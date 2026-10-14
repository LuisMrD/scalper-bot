# Offline pre-flight; does not call HTTP, Binance or MySQL.
$ErrorActionPreference='Stop'
$script=Join-Path (Split-Path $PSScriptRoot -Parent) 'diagnose-short-regimes.ps1'
$tokens=$null;$errors=$null
[System.Management.Automation.Language.Parser]::ParseFile(
    (Resolve-Path $script).Path,[ref]$tokens,[ref]$errors) | Out-Null
if($errors.Count -gt 0){
    throw ("Invalid PowerShell: {0}" -f ($errors | ForEach-Object Message | Out-String))
}
$plan=@(& $script -PrintOnly)
foreach($expected in @(
    'SHORT_DIAG_WINDOWS=6',
    'SHORT_DIAG_START=22',
    'SHORT_DIAG_END=27',
    'SHORT_DIAG_POLICIES=CONTROL,SHORT_ONLY',
    'SHORT_DIAG_CANDLES=3600',
    'SHORT_DIAG_HOURS=300',
    'SHORT_DIAG_WAIT_MINUTES=60',
    'SHORT_DIAG_BUCKETS=3,6,9',
    'SHORT_DIAG_TESTNET_ONLY=true',
    'SHORT_DIAG_RETROSPECTIVE_ONLY=true',
    'SHORT_DIAG_NO_LUNA=true',
    'SHORT_DIAG_NO_ORDERS=true'
)){
    if($plan -notcontains $expected){throw "Missing diagnostic invariant: $expected"}
}
[long]$size=300L*3600000L
$periods=@($plan | Where-Object {$_ -like 'SHORT_DIAG_WINDOW=*'})
if($periods.Count -ne 6){throw 'Expected six already inspected windows'}
for($i=0;$i -lt 6;$i++){
    if($periods[$i] -notmatch '^SHORT_DIAG_WINDOW=(\d+);FROM=(\d+);ASOF=(\d+)$'){
        throw 'Malformed diagnostic window'
    }
    [long]$w=[long]$Matches[1]
    [long]$start=[long]$Matches[2]
    [long]$end=[long]$Matches[3]
    if($w -ne $i+22 -or $end -ne (1791845999999-([long]($w-1)*$size)) -or
       $end-$start+1 -ne $size){
        throw "Invalid window dates for $w"
    }
}
$older=@(& $script -PrintOnly -StartWindow 16 -Windows 6)
if($older -notcontains 'SHORT_DIAG_START=16' -or
   $older -notcontains 'SHORT_DIAG_END=21'){
    throw 'Previously inspected regime cohort 16..21 missing'
}
$source=Get-Content -LiteralPath $script -Raw
foreach($marker in @(
    '/short-regime-diagnostics',
    'SHORT_ONLY',
    'INITIAL',
    'MFE_R',
    'MAE_R',
    'EXIT_REASON',
    'FeatureTiming',
    'SignalCloseTime',
    'NetClosedUsdt'
)){
    if(-not $source.Contains($marker) -and $marker -ne 'INITIAL'){
        throw "Missing technical diagnostic field: $marker"
    }
}
Write-Host 'PASS: historical 6x300h SHORT diagnostics, 3/6/9 bands, no orders/Luna'
