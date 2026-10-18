<#
.SYNOPSIS
  Launch paper-only EURUSD Forex CSV research. No Binance data calls/orders.
#>
[CmdletBinding()]
param([string]$CsvDirectory='data/forex',[switch]$PrintOnly)
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$folder=Join-Path $root $CsvDirectory
$env:SPRING_PROFILES_ACTIVE='backtest,forex-research'
$env:FOREX_CSV_DIR=$folder
$env:TRADING_SYMBOLS='BTCUSDT' # dormant legacy Binance module, not FX pair
$env:TRADING_ORDERS_ENABLED='false'
$env:LUNA_ENABLED='false'
$env:MARKET_DATA_WS_ENABLED='false'
$env:MARKET_DATA_BOOTSTRAP_ENABLED='false'
$env:FOREX_DB_URL='jdbc:mysql://127.0.0.1:3307/trading_bot_forex?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true'
$env:FOREX_DB_USERNAME='trading'
$env:FOREX_DB_PASSWORD='trading_local_only'
$env:MARKET_DATA_INTERVALS='15m'
$env:TRADING_CAPITAL_USDT='600'
$env:TRADING_RISK_PER_TRADE_PCT='0.5'
$env:TRADING_DAILY_STOP_PCT='3'
$env:TRADING_LEVERAGE='1'
$env:RISK_FEE_PER_SIDE_PCT='0.05'
$env:RISK_SLIPPAGE_PER_SIDE_PCT='0.02'
$env:RISK_MAX_COST_TO_INITIAL_RISK_PCT='50'
if($PrintOnly) {
    Write-Output 'MODE=FOREX_CSV_PAPER_ONLY_NO_ORDERS'
    Write-Output 'MARKET=FOREX_OTC_SPOT'
    Write-Output "CSV_DIR=$folder"
    Write-Output 'DATABASE=trading_bot_forex'
    Write-Output 'BROKER_ORDERS=DISABLED'
    return
}
if(-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    throw 'Java 25 and Maven required'
}
Push-Location $root
try {
    & cmd.exe /d /c 'mvn spring-boot:run'
    if($LASTEXITCODE -ne 0) { throw "Spring Boot failed: Maven exit $LASTEXITCODE" }
}finally{Pop-Location}
