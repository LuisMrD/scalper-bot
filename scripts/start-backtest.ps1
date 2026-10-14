<#
.SYNOPSIS
  Start a LOCAL, simulation-only backtest with reproducible test assumptions.
.DESCRIPTION
  Sets the environment for THIS PowerShell process, then starts Spring Boot.
  Values are experimental for historical backtesting ONLY, not live-trading defaults.
  Normal application.yml keeps financial defaults empty and orders blocked.
.EXAMPLE
  .\scripts\start-backtest.ps1
.EXAMPLE
  .\scripts\start-backtest.ps1 -MaxCostToInitialRiskPct 0 -PrintOnly
.EXAMPLE
  .\scripts\start-backtest.ps1 -Leverage 1 -MaxCostToInitialRiskPct 30
#>
[CmdletBinding()]
param(
    [ValidateSet('BTCUSDT', 'ETHUSDT', 'SOLUSDT')]
    [string[]]$Symbols = @('BTCUSDT', 'ETHUSDT', 'SOLUSDT'),

    [ValidateRange(0, 100)]
    [decimal]$MaxCostToInitialRiskPct = 30,

    [ValidateSet('true', 'false')]
    [string]$RequireHigherTf = 'false',

    [string]$CapitalUsdt = '300',
    [string]$RiskPerTradePct = '0.5',
    [string]$DailyStopPct = '3',

    [ValidateRange(1, 125)]
    [int]$Leverage = 1,

    [string]$FeePerSidePct = '0.05',
    [string]$SlippagePerSidePct = '0.02',
    [string]$BreakEvenTriggerR = '1.0',

    [switch]$IncludeOneHour,

    [switch]$PrintOnly
)

$ErrorActionPreference = 'Stop'

# Spring Boot does NOT load .env automatically. All values below are explicit
# session settings for a read-only/simulated local backtest.
$settings = [ordered]@{
    SPRING_PROFILES_ACTIVE = 'backtest'
    TRADING_SYMBOLS = ($Symbols -join ',')
    MARKET_DATA_INTERVALS = $(if($IncludeOneHour){'1m,5m,15m,1h'}else{'1m,5m,15m'})
    TRADING_ORDERS_ENABLED = 'false'
    LUNA_ENABLED = 'false'
    MARKET_DATA_WS_ENABLED = 'false'
    MARKET_DATA_BOOTSTRAP_ENABLED = 'false'
    TRADING_CAPITAL_USDT = $CapitalUsdt
    TRADING_RISK_PER_TRADE_PCT = $RiskPerTradePct
    TRADING_DAILY_STOP_PCT = $DailyStopPct
    TRADING_LEVERAGE = [string]$Leverage
    RISK_FEE_PER_SIDE_PCT = $FeePerSidePct
    RISK_SLIPPAGE_PER_SIDE_PCT = $SlippagePerSidePct
    RISK_BREAK_EVEN_TRIGGER_R = $BreakEvenTriggerR
    JAVA_DECISION_REQUIRE_HIGHER_TF = $RequireHigherTf
}

# 0 is supported as a backtest request override, but NOT as environment
# config (@DecimalMin > 0). Unset the variable to disable the global filter.
if ($MaxCostToInitialRiskPct -eq 0) {
    $settings['RISK_MAX_COST_TO_INITIAL_RISK_PCT'] = $null
} else {
    $settings['RISK_MAX_COST_TO_INITIAL_RISK_PCT'] =
        $MaxCostToInitialRiskPct.ToString([System.Globalization.CultureInfo]::InvariantCulture)
}

foreach ($name in $settings.Keys) {
    [System.Environment]::SetEnvironmentVariable($name, $settings[$name], 'Process')
}

if ($PrintOnly) {
    foreach ($name in $settings.Keys) {
        $value = $settings[$name]
        if ($null -eq $value) { $value = '<unset>' }
        Write-Output "$name=$value"
    }
    return
}

if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    throw "Maven (mvn) not found in PATH. Install Maven and JDK 25 before starting."
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location $repoRoot
try {
    Write-Host 'Starting local BACKTEST mode (orders OFF, Luna OFF, Binance auto-collection OFF).'
    Write-Host "Configured symbols: $($settings['TRADING_SYMBOLS'])"
    Write-Host 'Database must already be available; run: docker compose up -d mysql'
    & mvn spring-boot:run
    if ($LASTEXITCODE -ne 0) { throw "Maven failed with exit code $LASTEXITCODE" }
}
finally {
    Pop-Location
}
