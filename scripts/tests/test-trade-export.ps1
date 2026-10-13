# End-to-end offline mock of a 5m RETEST entry and trade-export CSV.
# No external HTTP, DB, order API, LLM, or 18000-candle replay.
$ErrorActionPreference = 'Stop'
$anchor = [long](1791845999999L - 600L * 300000L)
$global:runs = @()
function Invoke-RestMethod {
    [CmdletBinding()]
    param([string]$Uri,[string]$Method='Get',[string]$ContentType,
          [string]$Body,[int]$TimeoutSec)
    if ($Uri -match '/actuator/health$') { return [PSCustomObject]@{status='UP'} }
    if ($Uri -match '/api/v1/backtest/status$') {
        return [PSCustomObject]@{mode='SIMULATION_ONLY';ordersEnabled=$false}
    }
    if ($Uri -match '/api/v1/risk/status$') { return [PSCustomObject]@{configured=$true} }
    if ($Uri -match '/api/v1/market-data/settings$') { return [PSCustomObject]@{source='TESTNET'} }
    if ($Uri -match '/fapi/v1/exchangeInfo$') {
        return [PSCustomObject]@{symbols=@([PSCustomObject]@{
            symbol='SOLUSDT'; status='TRADING'; contractType='PERPETUAL';quoteAsset='USDT'
            filters=@(
                [PSCustomObject]@{filterType='PRICE_FILTER';tickSize='0.01'},
                [PSCustomObject]@{filterType='LOT_SIZE';minQty='0.01';stepSize='0.01'},
                [PSCustomObject]@{filterType='MARKET_LOT_SIZE';minQty='0.01';stepSize='0.01'},
                [PSCustomObject]@{filterType='MIN_NOTIONAL';notional='5'}
            )
        })}
    }
    if ($Uri -match '/api/v1/backtest/run$') {
        $req = $Body | ConvertFrom-Json
        if ($req.includeLuna -or $req.includeDecisionHistory -ne $false -or
            (@($req.intervals) -join ',') -ne '5m,15m' -or
            $req.pullbackEnabledOverride -ne $false -or
            $req.breakoutRetestEnabledOverride -ne $true -or
            $req.maxCostToInitialRiskPctOverride -ne 50) {
            throw "Unsafe or wrongly configured 5m-only RETEST: $Body"
        }
        $global:runs += $req
        $first = [long]$req.asOf - $req.steps * 300000L + 1
        $signal = [PSCustomObject]@{
            setup='BREAKOUT_RETEST';primaryTrend='UP';volumeLevel='HIGH'
            takerBalance='BUY_DOMINANT'; relativeVolume='1.9'
            timeframeTrends=@(
                [PSCustomObject]@{interval='5m';trend='UP';asOfCloseTime=$first},
                [PSCustomObject]@{interval='15m';trend='RANGE';asOfCloseTime=$first}
            )
        }
        $diag = [PSCustomObject]@{
            atSignal=[PSCustomObject]@{
                signal=$signal;higherTimeframeConflicts=1;breakoutState='UP_FOLLOW_THROUGH'
            }
            estimatedCostToInitialRiskPct='25';entryGapPct='0.01'
        }
        $trade = [PSCustomObject]@{
            side='LONG';enteredAtOpenTime=$first;exitCandleOpenTime=($first+300000L)
            quantity='0.5';grossPnlUsdt='1.7';netPnlUsdt='1.5';exitReason='STOP'
            durationCandles=2;durationMillis=300000
            initialRiskUsdt='1.2';netRMultiple='1.25'
            feesUsdt='0.2';slippageUsdt='0.05';mfeR='2';maeR='0.5'
            stopAdjustments=1; entryDiagnostics=$diag
        }
        return [PSCustomObject]@{
            symbol='SOLUSDT';intervals=$req.intervals;replayedCandles=$req.steps
            fromCandleOpenTime=$first;throughCandleCloseTime=[long]$req.asOf
            javaPortfolio=[PSCustomObject]@{
                startingEquityUsdt='1500';totalReturnPct='0.1';maxDrawdownPct='0.2'
                trades=@($trade);tradesClosed=1;winningTrades=1;losingTrades=0
                openPositionSide='FLAT';entriesBlocked=0;entrySignals=1
                openPositionDiagnostics=$null
            }
        }
    }
    throw "Unexpected REST mock request: $Uri"
}
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$runner = Join-Path $root 'scripts/compare-setup-windows.ps1'
$out = Join-Path $root 'results'
$paths = @(
    (Join-Path $out "setup-windows-SOLUSDT-5m-$anchor.csv"),
    (Join-Path $out "setup-summary-SOLUSDT-5m-$anchor.csv"),
    (Join-Path $out "setup-trades-SOLUSDT-5m-$anchor.csv")
)
try {
    foreach ($path in $paths) { if (Test-Path $path) { throw "Fixture would overwrite existing $path" } }
    & $runner -PrimaryInterval 5m -Symbol SOLUSDT -Setup RETEST -Steps 3 `
        -Windows 2 -AsOf $anchor -ExpectedCapitalUsdt 1500 -ExportTrades | Out-Null
    if ($global:runs.Count -ne 2 -or
        @($global:runs | Where-Object { $_.steps -ne 3 }).Count -gt 0) {
        throw 'Wrong number of 5m RETEST mock requests'
    }
    foreach ($path in $paths) { if (-not (Test-Path $path)) { throw "Missing output $path" } }
    $rows = @(Import-Csv $paths[2])
    if ($rows.Count -ne 2 -or $rows[0].PrimaryInterval -ne '5m' -or
        $rows[0].SignalSetup -ne 'BREAKOUT_RETEST' -or
        $rows[0].Trend15m -ne 'RANGE' -or
        $rows[0].EntryTrend -ne 'UP' -or
        [decimal]$rows[0].NetPnlUsdt -ne 1.5 -or
        [decimal]$rows[0].FeesUsdt -ne 0.2 -or
        [decimal]$rows[0].SlippageUsdt -ne 0.05) {
        throw "Invalid exported entry-snapshot/trade CSV: $($rows | ConvertTo-Json)"
    }
    $wrongCapitalBlocked = $false
    try {
        & $runner -PrimaryInterval 5m -Setup RETEST -Steps 3 -Windows 2 `
            -AsOf $anchor -ExpectedCapitalUsdt 300 -NoCsv | Out-Null
    } catch {
        if ($_.Exception.Message -match 'Starting capital') { $wrongCapitalBlocked = $true }
    }
    if (-not $wrongCapitalBlocked) { throw 'Wrong bank amount was accepted.' }
} finally {
    foreach ($path in $paths) { if (Test-Path $path) { Remove-Item $path -Force } }
}
Write-Host '5m RETEST mocked trade CSV snapshot export, cost provenance, and 1500-USDT capital guard passed.'
