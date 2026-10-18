<#
.SYNOPSIS
  PROSPECTIVE ONLY: compare CONTROL, CANDIDATE_A and CANDIDATE_B
  with a genuinely shared US$600 LONG/SHORT paper account.
.DESCRIPTION
  NEVER uses in-sample CSV subsets as independent backtests.
  No live orders, no GPT requests, no modification of the strategy.
  Without -InSample, a complete first 900h OOS window is only possible
  after 2026-11-22 12:00 UTC. -InSample deliberately replays only the
  twelve known historical windows and labels them HISTORICAL_INSAMPLE_ONLY.
  -HistoricalHoldout is a separate 2023-2024 data study, never prospective.
  -ShortOnlyCohort 2020_2021 or 2021_2022 disables the LONG engine entirely
  and replays two new fixed 12-window historical SHORT-only cohorts.
  Production public market-data requires -Source production and
  -ConfirmIsolatedDatabase and a dedicated research-only MySQL database.
.EXAMPLE
  .\scripts\run-btc-15m-shared-oos.ps1 -PrintOnly
.EXAMPLE
  .\scripts\run-btc-15m-shared-oos.ps1 -InSample -AsOfEpochMs 1792108799999 -Windows 12
.EXAMPLE
  .\scripts\run-btc-15m-shared-oos.ps1 -HistoricalHoldout -Source production -ConfirmIsolatedDatabase -AsOfEpochMs 1735689599999 -Windows 12
.EXAMPLE
  .\scripts\run-btc-15m-shared-oos.ps1 -AsOfEpochMs 1795348799999 -Windows 1
#>
[CmdletBinding()]
param(
    [long]$AsOfEpochMs = 0,
    [ValidateRange(1,12)][int]$Windows = 1,
    [ValidatePattern('^https?://(127\.0\.0\.1|localhost)(:\d+)?$')]
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [ValidateRange(30,1800)][int]$TimeoutSec = 900,
    [switch]$InSample,
    [switch]$HistoricalHoldout,
    [ValidateSet('2020_2021','2021_2022')][string]$ShortOnlyCohort,
    [ValidateSet('testnet','production')][string]$Source = 'testnet',
    [switch]$ConfirmIsolatedDatabase,
    [switch]$PrintOnly,
    [switch]$NoCsv
)
$ErrorActionPreference = 'Stop'
if ([int][bool]$InSample + [int][bool]$HistoricalHoldout +
    [int][bool](-not [string]::IsNullOrEmpty($ShortOnlyCohort)) -gt 1) {
    throw 'Choose only one of -InSample, -HistoricalHoldout or -ShortOnlyCohort'
}
if ($Source -eq 'production' -and -not $ConfirmIsolatedDatabase) {
    throw 'Production research requires -ConfirmIsolatedDatabase and a separate RESEARCH_DB_URL.'
}
if (($HistoricalHoldout -or $ShortOnlyCohort) -and $Source -ne 'production') {
    throw 'Historical 2023-2024 BTCUSD-M requires production historical candles in an isolated database.'
}
$BaseUrl = $BaseUrl.TrimEnd('/')
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$configFile = Join-Path $root 'scripts/config/btc-15m-mixed-entry-selection-12x900h.json'
$config = Get-Content $configFile -Raw | ConvertFrom-Json
if (@($config.symbols).Count -ne 1 -or $config.symbols[0] -cne 'BTCUSDT') {
    throw 'Frozen shared OOS experiment requires BTCUSDT only'
}
$barMs = [long]900000
$steps = [long]3600
$oosStart = [DateTimeOffset]::Parse('2026-10-16T00:00:00+00:00').ToUnixTimeMilliseconds()
$selectionClose = [DateTimeOffset]::Parse('2026-10-15T23:59:59.999+00:00').ToUnixTimeMilliseconds()
$windowMs = $steps * $barMs
$shortCohortEnd = if ($ShortOnlyCohort -eq '2020_2021') {
    [DateTimeOffset]::Parse('2021-09-30T23:59:59.999+00:00').ToUnixTimeMilliseconds()
} elseif ($ShortOnlyCohort -eq '2021_2022') {
    [DateTimeOffset]::Parse('2022-12-31T23:59:59.999+00:00').ToUnixTimeMilliseconds()
} else { [long]0 }
$sampleLabel = if ($InSample) { 'HISTORICAL_INSAMPLE_ONLY' }
    elseif ($HistoricalHoldout) { 'PRE_FROZEN_HISTORICAL_HOLDOUT' }
    elseif ($ShortOnlyCohort -eq '2020_2021') { 'SHORT_ONLY_HOLDOUT_2020_21' }
    elseif ($ShortOnlyCohort -eq '2021_2022') { 'SHORT_ONLY_HOLDOUT_2021_22' }
    else { 'STRICT_PROSPECTIVE_OOS' }
$researchRoute = if ($InSample) { 'backtest-insample' }
    elseif ($HistoricalHoldout) { 'backtest-historical-holdout' }
    elseif ($ShortOnlyCohort -eq '2020_2021') { 'backtest-short-holdout-2020-21' }
    elseif ($ShortOnlyCohort -eq '2021_2022') { 'backtest-short-holdout-2021-22' }
    else { 'backtest' }
$plans = @('CONTROL:LONG=BASELINE,SHORT=BASELINE',
           'CANDIDATE_A:LONG=WEEKDAYS_ONLY,SHORT=COMBINED_045',
           'CANDIDATE_B:LONG=WEEKDAYS_ONLY,SHORT=COMBINED_050')
if ($PrintOnly) {
    Write-Output 'MODE=PAPER_ONLY'
    Write-Output "SOURCE=$Source"
    Write-Output "EVIDENCE=$sampleLabel"
    Write-Output 'MARKET=BTCUSDT 15m/closed-1d; MIDAS_SHORT_RETEST; DYNAMIC_TREND_60'
    Write-Output 'WALLET=SHARED_USD_600; MAX_OPEN_POSITIONS=1; TIE_BREAK=LONG_FIRST'
    Write-Output 'FROZEN_CUTOFF_UTC=2026-10-15T23:59:59.999Z'
    Write-Output 'FIRST_COMPLETE_OOS_END_UTC=2026-11-22T11:59:59.999Z'
    Write-Output 'INSAMPLE_LAST_AS_OF_EPOCH_MS=1792108799999'
    if ($ShortOnlyCohort) {
        Write-Output "SHORT_ONLY=true; COHORT=$ShortOnlyCohort"
        Write-Output "COHORT_LAST_AS_OF_EPOCH_MS=$shortCohortEnd"
    }
    Write-Output "AS_OF_EPOCH_MS=$(if($AsOfEpochMs -eq 0){'<latest stored closed 15m>'}else{$AsOfEpochMs})"
    Write-Output "WINDOWS=$Windows; WINDOW_BARS=$steps"
    $plans | ForEach-Object { Write-Output $_ }
    return
}
$status = Invoke-RestMethod "$BaseUrl/api/v1/research/independent-setups/shared-oos/status" -TimeoutSec 20
if ($status.mode -cne 'PAPER_ONLY' -or $status.ordersEnabled -or
    $status.sharedLongShortEquity -ne $true -or $status.maxConcurrentPositions -ne 1 -or
    $status.holdingPolicy -cne 'DYNAMIC_TREND_60') {
    throw 'Server is NOT running the correct paper-only shared OOS endpoint'
}
$mode = Invoke-RestMethod "$BaseUrl/api/v1/market-data/settings" -TimeoutSec 20
if ([string]$mode.source -cne $Source.ToUpperInvariant()) {
    throw "Backend market source $($mode.source) differs from required $Source. Stop server and start research profile using a separate DB."
}
if ($AsOfEpochMs -eq 0) {
    $latest = @(Invoke-RestMethod "$BaseUrl/api/v1/market-data/candles?symbol=BTCUSDT&interval=15m&limit=1" -TimeoutSec 30)
    while ($latest.Count -eq 1 -and $latest[0] -is [array]) { $latest = @($latest[0]) }
    if ($latest.Count -ne 1 -or $latest[0].closed -ne $true)
        { throw 'No persisted closed 15m BTC candle' }
    $AsOfEpochMs = [long]$latest[0].closeTime
}
if ($AsOfEpochMs % $barMs -ne $barMs - 1) {
    throw 'AsOfEpochMs must close exactly at the end of a UTC 15m candle'
}
$firstOpen = $AsOfEpochMs - ($steps * $Windows * $barMs) + 1L
if ($InSample) {
    [long]$offset = $selectionClose - $AsOfEpochMs
    if ($offset -lt 0 -or $offset % $windowMs -ne 0 -or
        [math]::Floor($offset / $windowMs) + $Windows -gt 12) {
        throw 'Historical mode requires an aligned subset of the 12 original 900h in-sample windows. Use -AsOfEpochMs 1792108799999 -Windows 12.'
    }
} elseif ($HistoricalHoldout) {
    [long]$holdoutStart=[DateTimeOffset]::Parse('2023-01-01T00:00:00+00:00').ToUnixTimeMilliseconds()
    [long]$holdoutEnd=[DateTimeOffset]::Parse('2024-12-31T23:59:59.999+00:00').ToUnixTimeMilliseconds()
    [long]$offset = $holdoutEnd - $AsOfEpochMs
    if ($firstOpen -lt $holdoutStart -or $offset -lt 0 -or
        $offset % $windowMs -ne 0 -or
        [math]::Floor($offset / $windowMs) + $Windows -gt 12) {
        throw 'Historical holdout requires an aligned subset of the 12 frozen 900h windows ending 2024-12-31T23:59:59.999Z.'
    }
} elseif ($ShortOnlyCohort) {
    [long]$shortOffset = $shortCohortEnd - $AsOfEpochMs
    [long]$earliestHoldout = [DateTimeOffset]::Parse(
        '2020-01-01T00:00:00+00:00').ToUnixTimeMilliseconds()
    if ($shortOffset -lt 0 -or $shortOffset % $windowMs -ne 0 -or
        [math]::Floor($shortOffset / $windowMs) + $Windows -gt 12 -or
        $firstOpen -lt $earliestHoldout) {
        throw 'SHORT_ONLY accepts only a subset of the 12 fixed nonoverlapping 900h windows ending on the selected 2021/2022 cohort boundary.'
    }
} elseif ($firstOpen -lt $oosStart) {
    throw "Not genuine OOS: earliest requested open $firstOpen precedes 2026-10-16T00:00Z. No data can be relabeled as OOS."
}
# Public contract metadata; never approximate tick/lot/min-notional.
$exchangeUrl = if ($Source -eq 'production') {
    'https://fapi.binance.com/fapi/v1/exchangeInfo'
} else { 'https://demo-fapi.binance.com/fapi/v1/exchangeInfo' }
$exchange = Invoke-RestMethod $exchangeUrl -TimeoutSec 60
$contract = @($exchange.symbols | Where-Object {
    $_.symbol -ceq 'BTCUSDT' -and $_.status -ceq 'TRADING' -and
    $_.contractType -ceq 'PERPETUAL' -and $_.quoteAsset -ceq 'USDT'
})
if ($contract.Count -ne 1) { throw "No active $Source BTCUSDT perpetual" }
$priceFilter = @($contract[0].filters | Where-Object filterType -eq 'PRICE_FILTER')
$lot = @($contract[0].filters | Where-Object filterType -eq 'LOT_SIZE')
$notional = @($contract[0].filters | Where-Object filterType -eq 'MIN_NOTIONAL')
if ($priceFilter.Count -ne 1 -or $lot.Count -ne 1 -or $notional.Count -ne 1) {
    throw 'Incomplete BTCUSDT exchangeInfo'
}
$minNotional = if ($null -ne $notional[0].notional) {
    [decimal]$notional[0].notional
} else { [decimal]$notional[0].minNotional }
$filters = [ordered]@{
    priceTick = [decimal]$priceFilter[0].tickSize
    quantityStep = [decimal]$lot[0].stepSize
    minimumQuantity = [decimal]$lot[0].minQty
    minimumNotionalUsdt = $minNotional
}
foreach ($v in $filters.Values) { if ($v -le 0) { throw 'Invalid exchangeInfo contract filter' } }
$rows = [Collections.Generic.List[object]]::new()
$trades = [Collections.Generic.List[object]]::new()
$events = [Collections.Generic.List[object]]::new()
foreach ($w in $Windows..1) {
    [long]$asOf = $AsOfEpochMs - ($w - 1L) * $steps * $barMs
    [long]$start = $asOf - $steps * $barMs + 1L
    if (-not $InSample -and -not $HistoricalHoldout -and -not $ShortOnlyCohort -and $start -lt $oosStart) {
        throw "Window $w crosses frozen OOS cutoff"
    }
    if ($InSample -and $asOf -gt $selectionClose) {
        throw "Window $w exceeds the historical selection cutoff"
    }
    if ($HistoricalHoldout -and ($start -lt $holdoutStart -or $asOf -gt $holdoutEnd)) {
        throw "Window $w exceeds frozen 2023-24 holdout bounds"
    }
    if ($ShortOnlyCohort) {
        if ($asOf -gt $shortCohortEnd -or $start -lt $earliestHoldout) {
            throw "Window ${w} is outside frozen SHORT-only cohort"
        }
    }
    $body = [ordered]@{
        symbol = 'BTCUSDT'; steps = 3600; contextCandles = 60
        entryInterval = '15m'; trendInterval = '1d'; asOf = $asOf
        parameters = $config.parameters
        capitalUsdt = $config.capitalUsdt
        riskPerTradePct = $config.riskPerTradePct
        dailyStopPct = $config.dailyStopPct; leverage = $config.leverage
        feePerSidePct = $config.feePerSidePct
        slippagePerSidePct = $config.slippagePerSidePct
        spreadPct = $config.spreadPct
        assumedFundingPer8hPct = $config.assumedFundingPer8hPct
        assumedMaintenanceMarginPct = $config.assumedMaintenanceMarginPct
        priceTick = $filters.priceTick; quantityStep = $filters.quantityStep
        minimumQuantity = $filters.minimumQuantity
        minimumNotionalUsdt = $filters.minimumNotionalUsdt
        maxCostToInitialRiskPct = $config.maxCostToInitialRiskPct
        maxHoldingCandles = $config.maxHoldingCandles
    }
    Write-Host "Replaying $sampleLabel window ${w}: $start..$asOf"
    $json = $body | ConvertTo-Json -Depth 24 -Compress
    $report = Invoke-RestMethod -Method Post `
        -Uri "$BaseUrl/api/v1/research/independent-setups/shared-oos/$researchRoute" `
        -Body ([Text.Encoding]::UTF8.GetBytes($json)) `
        -ContentType 'application/json; charset=utf-8' -TimeoutSec $TimeoutSec
    if ($report.symbol -cne 'BTCUSDT' -or $report.interval -cne '15m' -or
        [long]$report.firstOpen -ne $start -or [long]$report.lastClose -ne $asOf -or
        [int]$report.completedBars -ne 3600 -or
        -not $report.executionModel.Contains($sampleLabel) -or
        -not $report.executionModel.Contains('ONE_POSITION_TOTAL') -or
        @($report.sharedPortfolios.PSObject.Properties).Count -ne 3) {
        throw "Invalid/mislabeled shared research result for window $w"
    }
    foreach ($policy in @('CONTROL','CANDIDATE_A','CANDIDATE_B')) {
        $slot = $report.sharedPortfolios.PSObject.Properties[$policy]
        if ($null -eq $slot) { throw "Missing independently re-simulated $policy" }
        $p = $slot.Value
        $completedTrades = @($p.trades | Where-Object { $null -ne $_ })
        if ($ShortOnlyCohort -and @($completedTrades |
                Where-Object { $_.side -cne 'SHORT' }).Count -gt 0) {
            throw "LONG trade detected in SHORT-only cohort window ${w}, policy $policy"
        }
        $grossProfit = [decimal]0; $grossLoss = [decimal]0
        foreach ($item in $completedTrades) {
            $net = [decimal]$item.trade.netPnlUsdt
            if ($net -gt 0) { $grossProfit += $net }
            elseif ($net -lt 0) { $grossLoss += -$net }
            $trades.Add([pscustomobject]@{
                Window = $w; Policy = $policy; Side = $item.side
                SignalCloseTime = $item.trade.signalCloseTime
                EntryOpenTime = $item.trade.entryOpenTime
                ExitOpenTime = $item.trade.exitOpenTime
                Entry = $item.trade.entry; Exit = $item.trade.exit
                InitialStop = $item.trade.initialStop
                Quantity = $item.trade.quantity
                NetPnlUsdt = $item.trade.netPnlUsdt
                TotalFeesUsdt = $item.trade.totalFeesUsdt
                FundingUsdt = $item.trade.fundingUsdt
                Reason = $item.trade.reason
                NetR = $item.trade.netR
            })
        }
        $rows.Add([pscustomobject]@{
            Window = $w; Policy = $policy; StartOpenTime = $start
            LastCloseTime = $asOf; InitialCapital = $p.initialCapital
            FinalMarkedEquity = $p.finalMarkedEquity
            RealizedNetUsdt = $p.realizedNetUsdt
            ClosedTrades = $p.closedTrades; Wins = $p.wins
            ProfitFactorNet = if ($grossLoss -gt 0) { $grossProfit / $grossLoss } else { $null }
            MaxDrawdownPct = $p.worstDrawdownPct
            TotalFeesUsdt = $p.totalFeesUsdt
            TotalFundingUsdt = $p.totalFundingUsdt
            AcceptedSignals = $p.acceptedSignals
            BlockedEntries = $p.blockedEntries
            OpenPosition = $p.openPosition
        })
    }
    foreach ($e in @($report.events)) {
        if ($ShortOnlyCohort -and $e.side -cne 'SHORT') {
            throw "LONG signal detected in SHORT-only cohort window ${w}"
        }
        $events.Add([pscustomobject]@{
            Window = $w; CandleCloseTime = $e.candleCloseTime
            Side = $e.side; SetupStatus = $e.setupStatus
            HigherTrend = $e.higherTrend
            HigherCloseTime = $e.higherCloseTime
            MidasReason = $e.midasReason
            Control = $e.policyReasons.CONTROL
            CandidateA = $e.policyReasons.CANDIDATE_A
            CandidateB = $e.policyReasons.CANDIDATE_B
        })
    }
}
$rows | Format-Table -AutoSize
if (-not $NoCsv) {
    $out = Join-Path $root 'results'
    New-Item -ItemType Directory -Path $out -Force | Out-Null
    $tag = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')
    $stem = "BTC-MIDAS-SHARED-$sampleLabel-900h-$AsOfEpochMs-$tag"
    $rows | Export-Csv (Join-Path $out "shared-windows-$stem.csv") -NoTypeInformation -Encoding UTF8
    $trades | Export-Csv (Join-Path $out "shared-trades-$stem.csv") -NoTypeInformation -Encoding UTF8
    $events | Export-Csv (Join-Path $out "shared-events-$stem.csv") -NoTypeInformation -Encoding UTF8
    ([ordered]@{asOfEpochMs=$AsOfEpochMs;windows=$Windows;sampleType=$sampleLabel;cutoffUtc='2026-10-15T23:59:59.999Z';
        wallet='SHARED'; direction=if($ShortOnlyCohort){'SHORT_ONLY'}else{'LONG_AND_SHORT'};
        shortOnlyCohort=$ShortOnlyCohort; source=$Source;
        priority=if($ShortOnlyCohort){'NOT_APPLICABLE'}else{'LONG_FIRST'};
        funding='ASSUMED_0_NOT_HISTORICAL';
        policies=$plans; originalConfig=$config} |
        ConvertTo-Json -Depth 24) | Set-Content (Join-Path $out "shared-config-$stem.json") -Encoding UTF8
    Write-Host "Exported: $out\*$stem*"
}
