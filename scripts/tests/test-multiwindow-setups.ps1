# Offline deterministic tests: no network, no orders, no DB, no LLM.
$ErrorActionPreference = 'Stop'
$global:calls = @()
$global:syncCalls = @()
$global:wrongCutoff = $false
$anchor = [long]1791846239999
$testSteps = 3
$testIntervalMs = [long]60000
$testIntervals = @('1m','5m','15m')

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
        $endMatch = [regex]::Match($Uri, '[?&]until=(\d+)')
        if (-not $endMatch.Success) { throw "Sync URL missing until: $Uri" }
        return [PSCustomObject]@{
            fetched=4;persisted=4;pages=1;until=[long]$endMatch.Groups[1].Value
        }
    }
    if ($Uri -match '/api/v1/backtest/run$') {
        $req = $Body | ConvertFrom-Json
        if ($req.includeLuna -or $req.includeDecisionHistory -ne $false -or $req.symbol -ne 'SOLUSDT' -or
                $req.maxCostToInitialRiskPctOverride -ne 50 -or
                $req.breakEvenTriggerROverride -ne '1.0' -or
                [decimal]$req.priceTick -ne [decimal]0.01 -or [decimal]$req.quantityStep -ne [decimal]0.01) {
            throw "Bad or unsafe historical replay request: $Body"
        }
        if ($req.steps -ne $testSteps -or
                (@($req.intervals) -join ',') -ne ($testIntervals -join ',')) {
            throw "Invalid replay intervals or window size: $Body"
        }
        $global:calls += $req
        $from = [long]$req.asOf - $req.steps * $testIntervalMs + 1
        $finish = if ($global:wrongCutoff) { [long]$req.asOf - $testIntervalMs } else { [long]$req.asOf }
        return [PSCustomObject]@{
            symbol='SOLUSDT'; intervals=$req.intervals; replayedCandles=$req.steps
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
if ($plan -notcontains 'STEPS=18000') {
    throw "300-hour scenarios must be the default."
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

# 3 x 18000 contiguous 1m candles = 900 hours of disjoint trading.
# Includes ~2.4d warmup; 40-day sync must be split into six <=7-day
# chunks for EACH timeframe and preserve exact boundaries without gaps.
$global:calls = @()
$global:syncCalls = @()
$testSteps = 18000
& $script -Steps 18000 -Windows 3 -AsOf $anchor -SyncHistory -NoCsv | Out-Null
if ($global:calls.Count -ne 9 -or $global:syncCalls.Count -ne 18) {
    throw "300-hour scenario must generate 9 replays and 18 weekly sync calls. Got $($global:calls.Count)/$($global:syncCalls.Count)"
}
foreach ($interval in @('1m','5m','15m')) {
    $history = @($global:syncCalls | Where-Object { $_ -match "interval=$interval&" })
    if ($history.Count -ne 6) { throw "Expected six sync chunks for $interval" }
    $previousEnd = $null
    foreach ($url in $history) {
        $a = [regex]::Match($url, '[?&]from=(\d+)')
        $b = [regex]::Match($url, '[?&]until=(\d+)')
        $from = [long]$a.Groups[1].Value; $end = [long]$b.Groups[1].Value
        if ($end -lt $from -or ($end - $from) -ge (7L * 24 * 60 * 60000)) {
            throw "Invalid seven-day chunk $interval : $url"
        }
        if ($null -ne $previousEnd -and $from -ne ($previousEnd + 1)) {
            throw "History chunks have a gap or overlap: $interval $url"
        }
        $previousEnd = $end
    }
}
foreach ($windowIndex in @(0,1,2)) {
    $expectedCutoff = $anchor - ([long]$windowIndex * 18000L * 60000L)
    $segment = @($global:calls | Select-Object -Skip ($windowIndex*3) -First 3)
    if ($segment.Count -ne 3 -or
            @($segment | Where-Object { [long]$_.asOf -ne $expectedCutoff }).Count -gt 0) {
        throw "300-hour windows are not disjoint at window $windowIndex"
    }
}

# 5m-only primary experiment must use 3600 5m bars to preserve exactly
# 300 trading hours. No 1m bar may appear in API requests or sync URLs.
$fivePlan = @(& $script -PrimaryInterval 5m -PrintOnly)
if ($fivePlan -notcontains 'PRIMARY_INTERVAL=5m' -or
        $fivePlan -notcontains 'INTERVALS=5m,15m' -or
        $fivePlan -notcontains 'STEPS=3600' -or
        $fivePlan -notcontains 'HOURS_PER_WINDOW=300') {
    throw "Incorrect 5m-primary time normalization: $($fivePlan -join '; ')"
}
$fiveAnchor = [long]1791845999999
$global:calls = @()
$global:syncCalls = @()
$testIntervalMs = [long]300000
$testIntervals = @('5m','15m')
$testSteps = 3600
& $script -PrimaryInterval 5m -Windows 3 -AsOf $fiveAnchor -SyncHistory -NoCsv | Out-Null
if ($global:calls.Count -ne 9 -or $global:syncCalls.Count -ne 12) {
    throw "5m scenario must run 9 full replays + 12 history chunks, actual $($global:calls.Count)/$($global:syncCalls.Count)"
}
if (@($global:syncCalls | Where-Object { $_ -match 'interval=1m&' }).Count -gt 0) {
    throw '1m history fetched despite 5m-only experiment.'
}
for ($windowIndex = 0; $windowIndex -lt 3; $windowIndex++) {
    $expectedEnd = $fiveAnchor - ([long]$windowIndex * 3600L * 300000L)
    $segment = @($global:calls | Select-Object -Skip ($windowIndex*3) -First 3)
    if ($segment.Count -ne 3 -or
            @($segment | Where-Object { [long]$_.asOf -ne $expectedEnd }).Count -gt 0) {
        throw "5m experiment window cutoffs are not separated by exactly 300 hours."
    }
}
$invalid5m = $false
try {
    & $script -PrimaryInterval 5m -AsOf $anchor -NoCsv | Out-Null
} catch {
    if ($_.Exception.Message -match 'closeTime of a 5m candle') { $invalid5m = $true }
}
if (-not $invalid5m) { throw 'Unaligned 1m cutoff accepted as a 5m close.' }

$testSteps = 3
$testIntervalMs = [long]60000
$testIntervals = @('1m','5m','15m')
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

Write-Host '1m baseline + 5m/15m-only 300-hour windows and UTC cutoffs smoke tests passed.'
