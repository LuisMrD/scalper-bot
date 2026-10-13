# Offline deterministic tests: no network, no orders, no DB, no LLM.
$ErrorActionPreference = 'Stop'
$global:calls = @()
$global:syncCalls = @()
$global:wrongCutoff = $false
$anchor = [long]1791846239999
$testSteps = 3

function Invoke-RestMethod {
    [CmdletBinding()]
    param([string]$Uri, [string]$Method = 'Get', [string]$ContentType,
          [string]$Body, [int]$TimeoutSec)
    if ($Uri -match '/actuator/health$') {
        return [PSCustomObject]@{status='UP'}
    }
    if ($Uri -match '/api/v1/backtest/status$') {
        return [PSCustomObject]@{mode='SIMULATION_ONLY'; ordersEnabled=$false}
    }
    if ($Uri -match '/api/v1/risk/status$') {
        return [PSCustomObject]@{configured=$true}
    }
    if ($Uri -match '/api/v1/market-data/settings$') {
        return [PSCustomObject]@{source='TESTNET'}
    }
    if ($Uri -match '/fapi/v1/exchangeInfo$') {
        return [PSCustomObject]@{symbols=@(
            [PSCustomObject]@{
                symbol='SOLUSDT'; status='TRADING'; contractType='PERPETUAL'; quoteAsset='USDT'
                filters=@(
                    [PSCustomObject]@{filterType='PRICE_FILTER'; tickSize='0.0100'},
                    [PSCustomObject]@{filterType='LOT_SIZE'; minQty='0.01'; stepSize='0.01'},
                    [PSCustomObject]@{filterType='MARKET_LOT_SIZE'; minQty='0.01'; stepSize='0.01'},
                    [PSCustomObject]@{filterType='MIN_NOTIONAL'; notional='5'}
                )
            })
        }
    }
    if ($Uri -match '/api/v1/market-data/sync\?') {
        $global:syncCalls += $Uri
        return [PSCustomObject]@{fetched=4;persisted=4;pages=1}
    }
    if ($Uri -match '/api/v1/backtest/run$') {
        $req = $Body | ConvertFrom-Json
        if ($req.includeLuna -or $req.symbol -ne 'SOLUSDT' -or
                $req.maxCostToInitialRiskPctOverride -ne 50 -or
                $req.breakEvenTriggerROverride -ne '1.0' -or
                [decimal]$req.priceTick -ne [decimal]0.01 -or [decimal]$req.quantityStep -ne [decimal]0.01) {
            throw "Bad or unsafe historical replay request: $Body"
        }
        if ($req.steps -ne $testSteps -or @($req.intervals).Count -ne 3) {
            throw "Invalid replay intervals or window size: $Body"
        }
        $global:calls += $req
        $from = [long]$req.asOf - $req.steps * 60000L + 1
        $finish = if ($global:wrongCutoff) { [long]$req.asOf - 60000L } else { [long]$req.asOf }
        return [PSCustomObject]@{
            symbol='SOLUSDT';replayedCandles=$req.steps
            fromCandleOpenTime=$from;throughCandleCloseTime=$finish
            javaPortfolio=[PSCustomObject]@{
                totalReturnPct=0.5;maxDrawdownPct=0.1
                trades=@([PSCustomObject]@{netPnlUsdt=1.5})
                tradesClosed=1;winningTrades=1;losingTrades=0
                openPositionSide='FLAT'; entriesBlocked=0;entrySignals=1
                openPositionDiagnostics=$null
            }
        }
    }
    throw "Unexpected mock REST URL: $Uri"
}

$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$script = Join-Path $root 'scripts/compare-setup-windows.ps1'
$plan = @(& $script -PrintOnly)
if ($plan -notcontains 'SETUPS=BOTH,PULLBACK,RETEST' -or
        $plan -notcontains 'LUNA=false' -or
        $plan -notcontains 'SOURCE=testnet') {
    throw "Incorrect setup plan: $($plan -join '; ')"
}

& $script -Steps $testSteps -Windows 2 -AsOf $anchor -SyncHistory -NoCsv | Out-Null
if ($global:calls.Count -ne 6 -or $global:syncCalls.Count -ne 3) {
    throw "Expected 2 windows x 3 setups plus 3 manual history syncs: $($global:calls.Count)/$($global:syncCalls.Count)"
}
$expected = @(
    "$anchor|True|True", "$anchor|True|False", "$anchor|False|True",
    "$($anchor-$testSteps*60000)|True|True",
    "$($anchor-$testSteps*60000)|True|False",
    "$($anchor-$testSteps*60000)|False|True"
)
for($i=0; $i -lt 6; $i++) {
    $req = $global:calls[$i]
    $actual = "$($req.asOf)|$($req.pullbackEnabledOverride)|$($req.breakoutRetestEnabledOverride)"
    if ($actual -ne $expected[$i]) {
        throw "Wrong experiment order/period: $actual vs $($expected[$i])"
    }
}

$global:wrongCutoff=$true
$blocked=$false
try {
    & $script -Steps $testSteps -Windows 2 -AsOf $anchor -NoCsv | Out-Null
} catch {
    if ($_.Exception.Message -match 'shifted its time period') { $blocked=$true }
}
if (-not $blocked) { throw 'Time-shifted backend response was not rejected.' }
$blocked=$false
try {
    & $script -Steps $testSteps -Windows 2 -AsOf ($anchor - 1) -NoCsv | Out-Null
} catch {
    if ($_.Exception.Message -match 'closeTime of a 1m candle') { $blocked=$true }
}
if (-not $blocked) { throw 'Invalid historical candle cutoff was not rejected.' }

$blocked=$false
try {
    & $script -Steps $testSteps -Windows 2 -AsOf $anchor -Source production -NoCsv | Out-Null
} catch {
    if ($_.Exception.Message -match 'ConfirmIsolatedDatabase') { $blocked=$true }
}
if (-not $blocked) { throw 'Production data did not require isolated database acknowledgement.' }

Write-Host 'Multi-window offline setup isolation, provenance and cutoff smoke tests passed.'
