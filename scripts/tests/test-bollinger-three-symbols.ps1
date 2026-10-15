# Offline check for 3 independent symbols, six 300h windows. No backend.
$ErrorActionPreference='Stop'
$runner=Join-Path (Split-Path $PSScriptRoot -Parent) 'run-bollinger-three-symbols.ps1'
$tokens=$null;$errors=$null
[System.Management.Automation.Language.Parser]::ParseFile(
    (Resolve-Path $runner).Path,[ref]$tokens,[ref]$errors) | Out-Null
if($errors.Count -gt 0){throw ("Three-symbol script syntax: {0}" -f ($errors|Out-String))}
$plan=@(& $runner -PrintOnly)
foreach($key in @(
    'THREE_SYMBOLS=SOLUSDT,DOGEUSDT,BTCUSDT',
    'THREE_SYMBOLS_WINDOWS=6','THREE_SYMBOLS_HOURS_PER_WINDOW=300',
    'THREE_SYMBOLS_START_WINDOW=34','THREE_SYMBOLS_END_WINDOW=39',
    'THREE_SYMBOLS_CAPITAL_PER_PORTFOLIO_USDT=1500',
    'THREE_SYMBOLS_CAPITAL_PER_ARM_ACROSS_SYMBOLS_USDT=4500',
    'THREE_SYMBOLS_INDEPENDENT_SYMBOL_WINDOW_PAIRS=18',
    'THREE_SYMBOLS_INDEPENDENT_REPLAY_LEDGERS=36',
    'THREE_SYMBOLS_NO_SHARED_BALANCE=true',
    'THREE_SYMBOLS_TIMEFRAME=5m','THREE_SYMBOLS_CONTEXT=15m',
    'THREE_SYMBOLS_BOLLINGER_CONFIRMATION=STRICT_OUTSIDE_THEN_PRICE_ACTION',
    'THREE_SYMBOLS_NO_LUNA=true','THREE_SYMBOLS_NO_ORDERS=true',
    'THREE_SYMBOLS_SOURCE=TESTNET')){
    if($plan -notcontains $key){throw "Missing independent portfolio contract: $key"}
}
foreach($symbol in @('SOLUSDT','DOGEUSDT','BTCUSDT')){
    foreach($key in @("THREE_SYMBOLS_INSTRUMENT=$symbol",
        'BOLLINGER_START_WINDOW=34','BOLLINGER_END_WINDOW=39',
        'BOLLINGER_WINDOWS=6','BOLLINGER_PERIOD=20','BOLLINGER_DEVIATIONS=2',
        'BOLLINGER_NO_LUNA=true','BOLLINGER_NO_ORDERS=true',
        'BOLLINGER_MARKET_SYMBOLS=SOLUSDT,DOGEUSDT,BTCUSDT')){
        if($plan -notcontains $key){throw "Incomplete $symbol plan: $key"}
    }
}
$startup=Join-Path (Split-Path $PSScriptRoot -Parent) 'start-backtest.ps1'
$settings=@(& $startup -Symbols SOLUSDT,DOGEUSDT,BTCUSDT -CapitalUsdt 1500 -MaxCostToInitialRiskPct 50 -PrintOnly)
if($settings -notcontains 'TRADING_SYMBOLS=SOLUSDT,DOGEUSDT,BTCUSDT' -or
    $settings -notcontains 'TRADING_CAPITAL_USDT=1500' -or
    $settings -notcontains 'TRADING_ORDERS_ENABLED=false' -or
    $settings -notcontains 'LUNA_ENABLED=false' -or
    $settings -notcontains 'MARKET_DATA_INTERVALS=1m,5m,15m'){
    throw 'Historical startup config is incorrect or unsafe'
}
try {
    & $runner -Symbols SOLUSDT,SOLUSDT -PrintOnly | Out-Null
    throw 'Duplicate symbols were unexpectedly accepted'
} catch {
    if($_.Exception.Message -notlike '*Duplicate symbols*'){throw}
}
Write-Host 'PASS: three asset independent 1500 USDT portfolios, 6x300h, TESTNET no orders/Luna'
