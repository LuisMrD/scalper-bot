<#
.SYNOPSIS
  Start LOCAL Spring paper research using public Binance USD-M PRODUCTION data
  in a separate MySQL database. NEVER sends orders or calls an LLM.
.DESCRIPTION
  Requires a separately CREATED trading_bot_research MySQL schema. This
  environment MUST NOT share storage with the old TESTNET paper backtest.
.EXAMPLE
  .\scripts\start-btc-historical-research.ps1 -PrintOnly
.EXAMPLE
  .\scripts\start-btc-historical-research.ps1
#>
[CmdletBinding()]
param(
    [ValidatePattern('^jdbc:mysql://127\.0\.0\.1:3307/trading_bot_research\?')]
    [string]$ResearchDbUrl =
        'jdbc:mysql://127.0.0.1:3307/trading_bot_research?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true',
    [ValidateNotNullOrEmpty()][string]$ResearchDbUsername = 'trading',
    [ValidateNotNullOrEmpty()][string]$ResearchDbPassword = 'trading_local_only',
    [switch]$PrintOnly
)
$ErrorActionPreference='Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
# Explicit simulation-only defaults. -PrintOnly writes config without starting.
& (Join-Path $PSScriptRoot 'start-backtest.ps1') -IncludeDailyWeekly -Symbols BTCUSDT -CapitalUsdt 600 -MaxCostToInitialRiskPct 50 -PrintOnly | Out-Null

$env:SPRING_PROFILES_ACTIVE='backtest,research'
$env:RESEARCH_DB_URL=$ResearchDbUrl
$env:RESEARCH_DB_USERNAME=$ResearchDbUsername
$env:RESEARCH_DB_PASSWORD=$ResearchDbPassword
$env:TRADING_ORDERS_ENABLED='false'
$env:LUNA_ENABLED='false'
$env:MARKET_DATA_WS_ENABLED='false'
$env:MARKET_DATA_BOOTSTRAP_ENABLED='false'
$env:TRADING_SYMBOLS='BTCUSDT'
if ($PrintOnly) {
    Write-Output 'PROFILES=backtest,research'
    Write-Output "RESEARCH_DB_URL=$ResearchDbUrl"
    Write-Output 'SOURCE=PRODUCTION_PUBLIC_READ_ONLY'
    Write-Output 'ORDERS=false; LUNA=false; WS=false; BOOTSTRAP=false'
    Write-Output 'WARNING: Requires a dedicated trading_bot_research schema; NEVER reuse TESTNET schema'
    return
}
if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    throw 'Maven/JDK 25 not available in PATH'
}
Push-Location $root
try {
    Write-Host 'Public Binance production klines, ISOLATED local research database.'
    Write-Host "Dedicated MySQL schema: trading_bot_research. No orders, no LLM."
    & cmd.exe /d /c 'mvn spring-boot:run'
    if ($LASTEXITCODE -ne 0) { throw "Spring Maven exited $LASTEXITCODE" }
} finally {
    Pop-Location
}
