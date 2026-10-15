<#
.SYNOPSIS
  Compare BOTH, PULLBACK-only and RETEST-only on non-overlapping 1m or 5m windows.
.DESCRIPTION
  Java-only simulated backtest. Requests do not modify app-wide strategy, open
  real orders, or call Luna. Each window uses 300 hours by default: 18000 bars
  of 1m, or 3600 bars of 5m. The 5m experiment excludes 1m entirely.
  -SyncHistory explicitly fetches public historical klines from the selected
  Binance backend into its configured MySQL database (never automatic).
.EXAMPLE
  .\scripts\compare-setup-windows.ps1 -PrintOnly
.EXAMPLE
  .\scripts\compare-setup-windows.ps1 -Source testnet -Windows 3 -SyncHistory
.EXAMPLE
  .\scripts\compare-setup-windows.ps1 -Source production -ConfirmIsolatedDatabase -Windows 3 -SyncHistory
#>
[CmdletBinding()]
param(
    [ValidateSet('BTCUSDT','ETHUSDT','SOLUSDT')]
    [string]$Symbol = 'SOLUSDT',
    [ValidateRange(2,6)]
    [int]$Windows = 3,
    [ValidateRange(2,18000)]
    [int]$Steps = 18000,
    [ValidateSet('1m','5m')]
    [string]$PrimaryInterval = '1m',
    [ValidateRange(21,500)]
    [int]$ContextLimit = 200,
    [ValidateRange(1,100)]
    [int]$CostLimitPct = 50,
    [ValidateRange(0,[long]::MaxValue)]
    [long]$AsOf = 0,
    [ValidateSet('testnet','production')]
    [string]$Source = 'testnet',
    [ValidatePattern('^https?://(127\.0\.0\.1|localhost)(:\d+)?$')]
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [ValidateRange(30,1800)]
    [int]$TimeoutSec = 900,
    [switch]$SyncHistory,
    [switch]$ConfirmIsolatedDatabase,
    [switch]$PrintOnly,
    [switch]$NoCsv,
    [ValidateSet('ALL','BOTH','PULLBACK','RETEST')]
    [Alias('Setup')]
    [string]$SelectedSetup = 'ALL',
    [switch]$ExportTrades,
    [ValidateRange(0,1000000000)]
    [decimal]$ExpectedCapitalUsdt = 0
)

$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')
# Backwards-compatible default for existing 1m experiments. Only the 5m
# experiment changes the unit; never reinterpret 18000 x 5m as 300 hours.
if ($PrimaryInterval -eq '5m' -and -not $PSBoundParameters.ContainsKey('Steps')) {
    $Steps = 3600
}
$intervalMs = if ($PrimaryInterval -eq '5m') { [long]300000 } else { [long]60000 }
$intervals = if ($PrimaryInterval -eq '5m') { @('5m','15m') } else { @('1m','5m','15m') }
$hoursPerWindow = ($Steps * $intervalMs) / 3600000.0
$setups = @(
    [PSCustomObject]@{ Name='BOTH'; Pullback=$true; Retest=$true },
    [PSCustomObject]@{ Name='PULLBACK'; Pullback=$true; Retest=$false },
    [PSCustomObject]@{ Name='RETEST'; Pullback=$false; Retest=$true }
)
if ($SelectedSetup -ne 'ALL') { $setups = @($setups | Where-Object { $_.Name -eq $SelectedSetup }) }
$exchangeInfoUrl = if ($Source -eq 'production') {
    'https://fapi.binance.com/fapi/v1/exchangeInfo'
} else {
    'https://demo-fapi.binance.com/fapi/v1/exchangeInfo'
}

if ($PrintOnly) {
    Write-Output "SYMBOL=$Symbol"
    Write-Output "WINDOWS=$Windows"
    Write-Output "STEPS=$Steps"
    Write-Output "PRIMARY_INTERVAL=$PrimaryInterval"
    Write-Output "INTERVALS=$($intervals -join ',')"
    Write-Output "HOURS_PER_WINDOW=$hoursPerWindow"
    Write-Output "CONTEXT_LIMIT=$ContextLimit"
    Write-Output "COST_LIMIT_PCT=$CostLimitPct"
    Write-Output "SETUPS=$($setups.Name -join ',')"
    Write-Output "EXPORT_TRADES=$($ExportTrades.ToString().ToLowerInvariant())"
    Write-Output "EXPECTED_CAPITAL_USDT=$ExpectedCapitalUsdt"
    Write-Output "SOURCE=$Source"
    Write-Output "LUNA=false"
    Write-Output "ORDERS=false"
    Write-Output "SYNC_HISTORY=$($SyncHistory.ToString().ToLowerInvariant())"
    Write-Output "AS_OF=$(if ($AsOf) { $AsOf } else { "<latest local closed $PrimaryInterval candle>" })"
    return
}

# A production research database must NOT be the same database used for testnet.
if ($Source -eq 'production' -and -not $ConfirmIsolatedDatabase) {
    throw 'Production research requires -ConfirmIsolatedDatabase and an isolated RESEARCH_DB_URL; never mix TESTNET and PRODUCTION candles.'
}

. (Join-Path $PSScriptRoot 'lib/binance-contract-filters.ps1')

function As-FlatArray {
    param([object]$Value)
    $rows = @($Value)
    while ($rows.Count -eq 1 -and $rows[0] -is [System.Array]) {
        $rows = @($rows[0])
    }
    return ,$rows
}

$health = Invoke-RestMethod -Uri "$BaseUrl/actuator/health" -TimeoutSec 15 -ErrorAction Stop
if ($health.status -ne 'UP') { throw "Local application not ready: $($health.status)" }
$status = Invoke-RestMethod -Uri "$BaseUrl/api/v1/backtest/status" -TimeoutSec 15 -ErrorAction Stop
if ($status.mode -ne 'SIMULATION_ONLY' -or $status.ordersEnabled) {
    throw 'Backtest endpoint is not simulation-only; refusing to run.'
}
$risk = Invoke-RestMethod -Uri "$BaseUrl/api/v1/risk/status" -TimeoutSec 15 -ErrorAction Stop
if (-not $risk.configured) {
    throw 'Risk settings incomplete: configure capital, risk, fees and slippage in application or environment.'
}
$settings = Invoke-RestMethod -Uri "$BaseUrl/api/v1/market-data/settings" -TimeoutSec 15 -ErrorAction Stop
if ([string]$settings.source -ne $Source.ToUpperInvariant()) {
    throw "Backend source '$($settings.source)' differs from requested '$Source'. Restart Spring with the correct isolated configuration."
}

Write-Host "Public market source: $($settings.source). NO TRADING ORDERS. NO LUNA."
Write-Host "Fetching exchangeInfo $exchangeInfoUrl"
$exchange = Invoke-RestMethod -Uri $exchangeInfoUrl -TimeoutSec 60 -ErrorAction Stop
$filters = Symbol-ContractFilters -ExchangeInfo $exchange -Symbol $Symbol
$filters | Format-Table -AutoSize

if ($AsOf -eq 0) {
    $uri = "$BaseUrl/api/v1/market-data/candles?symbol=$Symbol&interval=$PrimaryInterval&limit=1"
    $latest = As-FlatArray (Invoke-RestMethod -Uri $uri -TimeoutSec 30 -ErrorAction Stop)
    if ($latest.Count -ne 1 -or -not $latest[0].closed) {
        throw "No locally stored closed $PrimaryInterval candle for $Symbol. Use -AsOf with -SyncHistory or ingest history first."
    }
    $AsOf = [long]$latest[0].closeTime
}
if ($AsOf % $intervalMs -ne ($intervalMs - 1)) {
    throw "AsOf=$AsOf must be the UTC closeTime of a $PrimaryInterval candle (epoch milliseconds). Align the cutoff to the requested timeframe."
}

# The replay intervals are disjoint, while older indicator warm-up history can overlap.
$windowPlan = @()
for ($index=0; $index -lt $Windows; $index++) {
    $cutoff = $AsOf - ([long]$index * $Steps * $intervalMs)
    $firstOpen = $cutoff - ([long]$Steps * $intervalMs) + 1
    if ($firstOpen -lt 0) { throw 'Requested historical windows precede Unix epoch.' }
    $windowPlan += [PSCustomObject]@{
        Window = $index + 1
        FromOpen = $firstOpen
        AsOf = $cutoff
    }
}

Write-Host "Windows (each contains $Steps closed $PrimaryInterval candles, $([math]::Round($hoursPerWindow, 2)) hours):"
$windowPlan | Format-Table -AutoSize

if ($SyncHistory) {
    # Warm up higher-timeframe (15m) contexts BEFORE the oldest replay candle.
    # Add 30 bars for pivot/ATR and as-of alignment margin. Sync is idempotent
    # and uses only the local research app's selected Binance public-data URL.
    $warmupMs = ([long]($ContextLimit + 30) * 15 * 60000L)
    $from = $windowPlan[-1].FromOpen - $warmupMs
    if ($from -lt 0) { throw 'Sync window begins before Unix epoch.' }
    $until = $AsOf
    # The backend permits <=30 days/call and stops after maxPagesPerRequest
    # (default: 30 pages x 1000 bars). Seven-day chunks keep 1m below
    # 10080 bars per request and ensure the 40-day test remains complete.
    # Use closed 1m bar boundaries and advance to the next OPEN (no gaps).
    $chunkMs = [long]7 * 24 * 60 * 60000L
    foreach ($interval in $intervals) {
        $cursor = [long]$from
        $chunk = 0
        while ($cursor -le $until) {
            $end = [math]::Min($cursor + $chunkMs - 1, $until)
            $chunk++
            Write-Host "Synchronizing public $Source klines: $Symbol $interval chunk $chunk from $cursor to $end"
            $url = "$BaseUrl/api/v1/market-data/sync?symbol=$Symbol&interval=$interval&from=$cursor&until=$end"
            try {
                $sync = Invoke-RestMethod -Method Post -Uri $url -TimeoutSec $TimeoutSec -ErrorAction Stop
            } catch {
                $errorBody = if ($_.ErrorDetails -and $_.ErrorDetails.Message) { $_.ErrorDetails.Message } else { $_.Exception.Message }
                throw "History sync failed for $Symbol/$interval chunk $chunk ($cursor..$end): $errorBody"
            }
            if (-not $sync -or [long]$sync.until -lt $end -or [int]$sync.pages -lt 0) {
                throw "History sync returned incomplete response for $Symbol/$interval chunk $chunk"
            }
            Write-Host "$interval chunk $chunk -> fetched=$($sync.fetched); persisted=$($sync.persisted); pages=$($sync.pages)"
            $cursor = [long]$end + 1
        }
    }
}

$rows = @()
$tradeRows = @()
$culture = [System.Globalization.CultureInfo]::InvariantCulture
foreach ($period in $windowPlan) {
    foreach ($setup in $setups) {
        $body = @{
            symbol = $Symbol
            intervals = $intervals
            steps = $Steps
            contextLimit = $ContextLimit
            asOf = $period.AsOf
            includeLuna = $false
            # Per-entry snapshots still included; omit ~18000 decision traces.
            includeDecisionHistory = $false
            priceTick = $filters.priceTick
            quantityStep = $filters.quantityStep
            minimumQuantity = $filters.minimumQuantity
            minimumNotionalUsdt = $filters.minimumNotionalUsdt
            breakEvenTriggerROverride = '1.0'
            maxCostToInitialRiskPctOverride = $CostLimitPct
            pullbackEnabledOverride = $setup.Pullback
            breakoutRetestEnabledOverride = $setup.Retest
        } | ConvertTo-Json -Depth 8

        Write-Host "Window $($period.Window)/$Windows, $($setup.Name), asOf=$($period.AsOf)..."
        try {
            $run = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/backtest/run" -ContentType 'application/json' -Body $body -TimeoutSec $TimeoutSec -ErrorAction Stop
        } catch {
            throw "Replay failed for window $($period.Window) / $($setup.Name), asOf=$($period.AsOf). Missing/gapped candles or insufficient 5m/15m warm-up? $($_.Exception.Message)"
        }
        if ($run.symbol -ne $Symbol -or
                (@($run.intervals) -join ',') -ne ($intervals -join ',') -or
                [int]$run.replayedCandles -ne $Steps -or
                [long]$run.fromCandleOpenTime -ne [long]$period.FromOpen -or
                [long]$run.throughCandleCloseTime -ne [long]$period.AsOf) {
            throw "Replay shifted its time period for $($setup.Name) window $($period.Window). Abort without misleading comparison."
        }
        $p = $run.javaPortfolio
        if ($ExpectedCapitalUsdt -gt 0 -and [decimal]$p.startingEquityUsdt -ne $ExpectedCapitalUsdt) {
            throw "Starting capital is $($p.startingEquityUsdt) USDT, expected $ExpectedCapitalUsdt USDT. Verify TRADING_CAPITAL_USDT in the running Spring process."
        }
        $trades = As-FlatArray $p.trades
        if ($trades.Count -ne [int]$p.tradesClosed) {
            throw "Closed-trade array length differs from tradesClosed for $($setup.Name), window $($period.Window)."
        }
        $netClosed = if ($trades.Count) {
            [double](($trades | Measure-Object -Property netPnlUsdt -Sum).Sum)
        } else { 0.0 }
        $netOpen = if ($p.openPositionDiagnostics) {
            [double]$p.openPositionDiagnostics.unrealizedNetPnlUsdt
        } else { 0.0 }
        $rows += [PSCustomObject]@{
            Source = $Source.ToUpperInvariant()
            Symbol = $Symbol
            PrimaryInterval = $PrimaryInterval
            HoursPerWindow = $hoursPerWindow
            StartingEquityUsdt = ([decimal]$p.startingEquityUsdt).ToString($culture)
            Window = $period.Window
            FromOpen = $period.FromOpen
            AsOf = $period.AsOf
            Setup = $setup.Name
            ReturnPct = [math]::Round([double]$p.totalReturnPct,4)
            DrawdownPct = [math]::Round([double]$p.maxDrawdownPct,4)
            NetClosedUsdt = [math]::Round($netClosed,4)
            NetOpenUsdt = [math]::Round($netOpen,4)
            TradesClosed = [int]$p.tradesClosed
            Wins = [int]$p.winningTrades
            Losses = [int]$p.losingTrades
            OpenSide = $p.openPositionSide
            EntriesBlocked = [int]$p.entriesBlocked
            Signals = [int]$p.entrySignals
        }
        if ($ExportTrades) {
            foreach ($trade in $trades) {
                $entry = $trade.entryDiagnostics
                if ($null -eq $entry -or $null -eq $entry.atSignal -or $null -eq $entry.atSignal.signal) {
                    throw "Missing entry-time snapshot for closed trade: $PrimaryInterval/$($period.Window)/$($setup.Name)."
                }
                $signal = $entry.atSignal.signal
                if ([long]$trade.enteredAtOpenTime -lt [long]$period.FromOpen -or
                    [long]$trade.exitCandleOpenTime -gt [long]$period.AsOf -or
                    [long]$trade.exitCandleOpenTime -lt [long]$trade.enteredAtOpenTime -or
                    ($setup.Name -ne 'BOTH' -and [string]$signal.setup -ne $(if ($setup.Name -eq 'RETEST') { 'BREAKOUT_RETEST' } else { 'PULLBACK' })) -or
                    $signal.primaryTrend -notin @('UP','DOWN')) {
                    throw "Invalid entry-time audit record for $PrimaryInterval/$($period.Window)/$($setup.Name)."
                }
                $trend5m = @($signal.timeframeTrends | Where-Object { $_.interval -eq '5m' } | Select-Object -First 1)
                $trend15m = @($signal.timeframeTrends | Where-Object { $_.interval -eq '15m' } | Select-Object -First 1)
                $tradeRows += [PSCustomObject]@{
                    Source = $Source.ToUpperInvariant()
                    Symbol = $Symbol
                    PrimaryInterval = $PrimaryInterval
                    HoursPerWindow = $hoursPerWindow
                    Window = $period.Window
                    Setup = $setup.Name
                    FromOpen = $period.FromOpen
                    AsOf = $period.AsOf
                    Side = $trade.side
                    SignalSetup = $signal.setup
                    EnteredAtOpenTime = $trade.enteredAtOpenTime
                    ExitCandleOpenTime = $trade.exitCandleOpenTime
                    ExitReason = $trade.exitReason
                    DurationCandles = $trade.durationCandles
                    DurationMinutes = ([decimal]$trade.durationMillis / 60000).ToString($culture)
                    NetPnlUsdt = ([decimal]$trade.netPnlUsdt).ToString($culture)
                    GrossPnlUsdt = ([decimal]$trade.grossPnlUsdt).ToString($culture)
                    FeesUsdt = ([decimal]$trade.feesUsdt).ToString($culture)
                    SlippageUsdt = ([decimal]$trade.slippageUsdt).ToString($culture)
                    NetR = ([decimal]$trade.netRMultiple).ToString($culture)
                    InitialRiskUsdt = ([decimal]$trade.initialRiskUsdt).ToString($culture)
                    MfeR = ([decimal]$trade.mfeR).ToString($culture)
                    MaeR = ([decimal]$trade.maeR).ToString($culture)
                    StopAdjustments = $trade.stopAdjustments
                    EntryTrend = $signal.primaryTrend
                    Trend5m = if ($trend5m.Count) { $trend5m[0].trend } else { '' }
                    Trend15m = if ($trend15m.Count) { $trend15m[0].trend } else { '' }
                    HtfConflicts = $entry.atSignal.higherTimeframeConflicts
                    VolumeLevel = $signal.volumeLevel
                    TakerBalance = $signal.takerBalance
                    RelativeVolume = if ($null -ne $signal.relativeVolume) { ([decimal]$signal.relativeVolume).ToString($culture) } else { '' }
                    EstimatedCostToRiskPct = if ($null -ne $entry.estimatedCostToInitialRiskPct) { ([decimal]$entry.estimatedCostToInitialRiskPct).ToString($culture) } else { '' }
                    BreakoutState = $entry.atSignal.breakoutState
                    EntryGapPct = if ($null -ne $entry.entryGapPct) { ([decimal]$entry.entryGapPct).ToString($culture) } else { '' }
                }
            }
        }
    }
}

# Only report complete sets. None of the scenarios may silently skip a window.
if ($rows.Count -ne $Windows * $setups.Count) { throw 'Incomplete scenario matrix.' }
Write-Host 'Per-window results (same price tape for all three setups):'
$rows | Format-Table Window,Setup,ReturnPct,DrawdownPct,NetClosedUsdt,NetOpenUsdt,TradesClosed,Wins,Losses,OpenSide -AutoSize

$summary = @()
foreach ($setup in $setups) {
    $subset = @($rows | Where-Object { $_.Setup -eq $setup.Name })
    $return = @($subset | Sort-Object ReturnPct | ForEach-Object { [double]$_.ReturnPct })
    $median = if ($return.Count % 2 -eq 1) {
        $return[[int][math]::Floor($return.Count / 2)]
    } else {
        ($return[$return.Count/2-1] + $return[$return.Count/2]) / 2
    }
    $totalWins = [int](($subset | Measure-Object -Property Wins -Sum).Sum)
    $totalLosses = [int](($subset | Measure-Object -Property Losses -Sum).Sum)
    $closed = [int](($subset | Measure-Object -Property TradesClosed -Sum).Sum)
    $summary += [PSCustomObject]@{
        Source = $Source.ToUpperInvariant()
        Symbol = $Symbol
        PrimaryInterval = $PrimaryInterval
        HoursPerWindow = $hoursPerWindow
        Setup = $setup.Name
        Windows = $subset.Count
        PositiveWindows = @($subset | Where-Object { $_.ReturnPct -gt 0 }).Count
        AvgReturnPct = [math]::Round([double](($subset | Measure-Object -Property ReturnPct -Average).Average),4)
        MedianReturnPct = [math]::Round([double]$median,4)
        WorstReturnPct = [math]::Round([double]$return[0],4)
        BestReturnPct = [math]::Round([double]$return[-1],4)
        AvgDrawdownPct = [math]::Round([double](($subset | Measure-Object -Property DrawdownPct -Average).Average),4)
        ClosedTrades = $closed
        Wins = $totalWins
        Losses = $totalLosses
        WinRatePct = if ($closed -gt 0) { [math]::Round(100.0*$totalWins/$closed,2) } else { $null }
        SumClosedPnlUsdt = [math]::Round([double](($subset | Measure-Object -Property NetClosedUsdt -Sum).Sum),4)
        OpenPositionsAtCutoff = @($subset | Where-Object { $_.OpenSide -ne 'FLAT' }).Count
    }
}
Write-Host 'Independent-window summary (average return is NOT cumulative return):'
$summary | Format-Table Setup,Windows,PositiveWindows,AvgReturnPct,MedianReturnPct,WorstReturnPct,AvgDrawdownPct,ClosedTrades,Wins,Losses,OpenPositionsAtCutoff -AutoSize

if (-not $NoCsv) {
    $out = Join-Path (Resolve-Path (Join-Path $PSScriptRoot '..')).Path 'results'
    New-Item -ItemType Directory -Force -Path $out | Out-Null
    $outputSuffix = if ($PrimaryInterval -eq '5m') { "-5m" } else { "" }
    $rawFile = Join-Path $out ("setup-windows-{0}{1}-{2}.csv" -f $Symbol,$outputSuffix,$AsOf)
    $sumFile = Join-Path $out ("setup-summary-{0}{1}-{2}.csv" -f $Symbol,$outputSuffix,$AsOf)
    $rows | Export-Csv -Path $rawFile -NoTypeInformation -Encoding UTF8
    $summary | Export-Csv -Path $sumFile -NoTypeInformation -Encoding UTF8
    Write-Host "Saved: $rawFile"
    Write-Host "Saved: $sumFile"
    if ($ExportTrades) {
        $tradeFile = Join-Path $out ("setup-trades-{0}{1}-{2}.csv" -f $Symbol,$outputSuffix,$AsOf)
        if ($tradeRows.Count -eq 0) {
            # Explicit empty-trade results: do not reuse stale CSV from an earlier run.
            if (Test-Path $tradeFile) { Remove-Item -Path $tradeFile -Force }
            Write-Host "No closed trades: $tradeFile not written"
        } else {
            $tradeRows | Export-Csv -Path $tradeFile -NoTypeInformation -Encoding UTF8
            Write-Host "Saved: $tradeFile ($($tradeRows.Count) closed trades)"
        }
    }
}
