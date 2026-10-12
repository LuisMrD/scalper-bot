# Offline regression tests for Windows PowerShell 5.1-style REST JSON array shapes.
# No network calls, no Binance orders, no OpenAI, no MySQL writes.
$ErrorActionPreference = 'Stop'

$bootstrap = @()
foreach ($symbol in @('BTCUSDT','ETHUSDT','SOLUSDT')) {
    foreach ($interval in @('1m','5m','15m')) {
        $bootstrap += [PSCustomObject]@{
            symbol=$symbol; interval=$interval; from=1; until=2
            fetched=120; persisted=120; pages=1
        }
    }
}
$contracts = [PSCustomObject]@{
    symbols = @([PSCustomObject]@{
        symbol='SOLUSDT'
        status='TRADING'
        contractType='PERPETUAL'
        quoteAsset='USDT'
        filters=@(
            [PSCustomObject]@{filterType='PRICE_FILTER'; tickSize='0.0100'}
            [PSCustomObject]@{filterType='LOT_SIZE'; stepSize='0.1'; minQty='0.1'}
            [PSCustomObject]@{filterType='MARKET_LOT_SIZE'; stepSize='0.1'; minQty='0.1'}
            [PSCustomObject]@{filterType='MIN_NOTIONAL'; notional='5'}
        )
    })
}

$global:bootstrapCalled = 0
$global:backtestCalls = 0
$global:replayLimits = @()

function Invoke-RestMethod {
    [CmdletBinding()]
    param(
        [string]$Uri,
        [string]$Method = 'Get',
        [int]$TimeoutSec,
        [string]$ContentType,
        [string]$Body
    )

    if ($Uri -match '/actuator/health$') {
        return [PSCustomObject]@{ status='UP' }
    }
    if ($Uri -match '/api/v1/market-data/settings$') {
        return [PSCustomObject]@{ intervals=@('1m','5m','15m'); bootstrapEnabled=$false }
    }
    if ($Uri -match '/api/v1/market-data/bootstrap$') {
        $global:bootstrapCalled++
        # JSON arrays from Invoke-RestMethod may be emitted as ONE Object[].
        Write-Output -NoEnumerate $bootstrap
        return
    }
    if ($Uri -match '/api/v1/risk/status$') {
        return [PSCustomObject]@{configured=$true}
    }
    if ($Uri -match '/api/v1/backtest/status$') {
        return [PSCustomObject]@{ordersEnabled=$false; mode='SIMULATION_ONLY'}
    }
    if ($Uri -match '/fapi/v1/exchangeInfo$') {
        return $contracts
    }
    if ($Uri -match '/api/v1/market-data/candles\?') {
        # Another single-item JSON array, not a scalar candle.
        $bars = @([PSCustomObject]@{closed=$true; closeTime=1791823439999})
        Write-Output -NoEnumerate $bars
        return
    }
    if ($Uri -match '/api/v1/backtest/run$') {
        $req = $Body | ConvertFrom-Json
        if ($req.symbol -ne 'SOLUSDT' -or $req.includeLuna) {
            throw 'Unexpected pair or paid Luna enabled'
        }
        if ($req.priceTick -ne '0.01' -or $req.quantityStep -ne '0.1' -or
                $req.minimumNotionalUsdt -ne '5') {
            throw 'Unexpected contract filters in replay request'
        }
        if ([long]$req.asOf -ne 1791823439999) {
            throw 'Replay did not preserve shared asOf'
        }
        $global:backtestCalls++
        $global:replayLimits += [int]$req.maxCostToInitialRiskPctOverride
        return [PSCustomObject]@{
            symbol='SOLUSDT'; replayedCandles=3000
            javaPortfolio=[PSCustomObject]@{
                totalReturnPct=-0.5; maxDrawdownPct=0.2
                entrySignals=10; entriesBlocked=3; tradesClosed=5
                winningTrades=3; losingTrades=2; openPositionSide='FLAT'
            }
            javaDecisionHistory=@()
        }
    }
    throw "Unexpected outbound URL in offline smoke test: $Uri"
}

$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
& (Join-Path $root 'scripts/sync-backtest-data.ps1') -BaseUrl 'http://127.0.0.1:8080' | Out-Null
if ($global:bootstrapCalled -ne 1) {
    throw 'Expected one manual bootstrap call with 9 results'
}

& (Join-Path $root 'scripts/compare-pair-backtests.ps1') -Symbols SOLUSDT -CostLimits 0,30 `
    -BaseUrl 'http://127.0.0.1:8080' -NoCsv | Out-Null
if ($global:backtestCalls -ne 2 -or ($global:replayLimits -join ',') -ne '0,30') {
    throw "Expected two SOL replays at 0 and 30: got $($global:replayLimits -join ',')"
}
Write-Host 'Offline JSON-array bootstrap + SOL backtest smoke tests passed.'
