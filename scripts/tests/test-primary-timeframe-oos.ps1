# Offline frozen timeframe plan; no Binance API, database or Spring application.
$ErrorActionPreference='Stop'
$runner=Join-Path (Split-Path $PSScriptRoot -Parent) 'validate-primary-timeframe-oos.ps1'
$tokens=$null;$errors=$null
[System.Management.Automation.Language.Parser]::ParseFile(
    (Resolve-Path $runner).Path,[ref]$tokens,[ref]$errors) | Out-Null
if($errors.Count -gt 0){throw ("Invalid PowerShell syntax: {0}" -f ($errors|Out-String))}
$plan=@(& $runner -PrintOnly)
foreach($key in @(
    'PRIMARY_TF_OOS_START_WINDOW=46',
    'PRIMARY_TF_OOS_END_WINDOW=51',
    'PRIMARY_TF_WINDOWS=6',
    'PRIMARY_TF_FROZEN=true',
    'PRIMARY_TF_UNTOUCHED_WINDOWS=true',
    'PRIMARY_TF_CANDLES_5M=3600',
    'PRIMARY_TF_CANDLES_15M=1200',
    'PRIMARY_TF_WAIT_MINUTES=60',
    'PRIMARY_TF_WAIT_BARS=12x5m,4x15m',
    'PRIMARY_TF_CONTEXTS=5m+15m,15m+1h',
    'PRIMARY_TF_SCENARIOS=PRIMARY_5M_CONTEXT_15M,PRIMARY_15M_CONTEXT_1H',
    'PRIMARY_TF_SOURCE=TESTNET',
    'PRIMARY_TF_NO_LUNA=true',
    'PRIMARY_TF_NO_ORDERS=true',
    'PRIMARY_TF_EXPECTED_CAPITAL=1500'
)){if($plan -notcontains $key){throw "Missing frozen timeframe rule: $key"}}
$spans=@($plan|Where-Object {$_ -like 'PRIMARY_TF_WINDOW=*'})
if($spans.Count -ne 6){throw 'Expected six untouched windows'}
[long]$hours=300L*3600000L
for([int]$i=0;$i -lt 6;$i++){
    if($spans[$i] -notmatch '^PRIMARY_TF_WINDOW=(\d+);FROM=(\d+);ASOF=(\d+)$'){
        throw "Malformed timeframe plan row $($spans[$i])"
    }
    [long]$window=[long]$Matches[1];[long]$start=[long]$Matches[2]
    [long]$end=[long]$Matches[3]
    if($window -ne $i+46 -or $end -ne 1791845999999-($window-1)*$hours -or
       $end-$start+1 -ne $hours -or $end%3600000 -ne 3599999){
        throw "Invalid window $window"
    }
}
$startupOutput=@(& (Join-Path (Split-Path $PSScriptRoot -Parent) 'start-backtest.ps1') -IncludeOneHour -PrintOnly)
if($startupOutput -notcontains 'MARKET_DATA_INTERVALS=1m,5m,15m,1h' -or
   $startupOutput -notcontains 'TRADING_ORDERS_ENABLED=false' -or
   $startupOutput -notcontains 'LUNA_ENABLED=false'){
    throw '15m backtest startup must explicitly enable 1h WITHOUT orders or Luna'
}
Write-Host 'PASS: six 300-hour matched 5m/15m studies, 1h historical context, Testnet only'
