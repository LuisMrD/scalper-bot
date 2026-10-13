<#
.SYNOPSIS
  Compare BTCUSDT, ETHUSDT and SOLUSDT using identical replay cutoffs.
.DESCRIPTION
  Requires running local app and preloaded continuous market data.
  Obtains each USD-M PERPETUAL symbol's filters from Binance Futures TESTNET
  exchangeInfo; NEVER assumes BTC tick/quantity applies to SOL/ETH.
  Runs Java-only, no orders and no OpenAI calls. Records portfolio metrics to CSV.
.EXAMPLE
  .\scripts\compare-pair-backtests.ps1
.EXAMPLE
  .\scripts\compare-pair-backtests.ps1 -Symbols SOLUSDT -CostLimits 0,30,100
.EXAMPLE
  .\scripts\compare-pair-backtests.ps1 -AsOf 1791823439999 -Steps 3000
.EXAMPLE
  .\scripts\compare-pair-backtests.ps1 -PrintOnly
#>
[CmdletBinding()]
param(
    [ValidateSet('BTCUSDT', 'ETHUSDT', 'SOLUSDT')]
    [string[]]$Symbols = @('BTCUSDT', 'ETHUSDT', 'SOLUSDT'),

    [ValidateRange(2, 3000)]
    [int]$Steps = 3000,

    [ValidateRange(21, 500)]
    [int]$ContextLimit = 200,

    [ValidateScript({ $_.Count -gt 0 -and @($_ | Where-Object { $_ -lt 0 -or $_ -gt 100 }).Count -eq 0 })]
    [int[]]$CostLimits = @(0, 30, 50, 75, 100),

    [ValidateScript({ $_.Count -gt 0 -and @($_ | Where-Object { $_ -lt 0 -or $_ -gt 3000 }).Count -eq 0 })]
    [int[]]$HoldingLimits = @(0),

    [ValidateRange(0, [long]::MaxValue)]
    [long]$AsOf = 0,

    [ValidatePattern('^https?://(127\.0\.0\.1|localhost)(:\d+)?$')]
    [string]$BaseUrl = 'http://127.0.0.1:8080',

    [string]$ExchangeInfoUrl = 'https://demo-fapi.binance.com/fapi/v1/exchangeInfo',

    [ValidateRange(30, 1800)]
    [int]$TimeoutSec = 900,

    [switch]$PrintOnly,
    [switch]$NoCsv,
    [switch]$AnalyzeEntries
)

$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')

if ($PrintOnly) {
    Write-Output "SYMBOLS=$($Symbols -join ',')"
    Write-Output "COST_LIMITS=$($CostLimits -join ',')"
    Write-Output "HOLDING_LIMITS=$($HoldingLimits -join ',')"
    Write-Output "STEPS=$Steps"
    Write-Output "CONTEXT_LIMIT=$ContextLimit"
    Write-Output 'LUNA=false'
    Write-Output "ANALYZE_ENTRIES=$($AnalyzeEntries.ToString().ToLowerInvariant())"
    Write-Output "AS_OF=$(if ($AsOf -gt 0) { $AsOf } else { '<latest common stored 1m candle>' })"
    Write-Output "EXCHANGE_INFO=$ExchangeInfoUrl"
    return
}

. (Join-Path $PSScriptRoot 'lib/binance-contract-filters.ps1')

try {
    $health = Invoke-RestMethod -Uri "$BaseUrl/actuator/health" -TimeoutSec 15 -ErrorAction Stop
    if ($health.status -ne 'UP') { throw "The local application is not ready: $($health.status)" }

    $status = Invoke-RestMethod -Uri "$BaseUrl/api/v1/risk/status" -TimeoutSec 15 -ErrorAction Stop
    if (-not $status.configured) {
        throw "Risk configuration is incomplete. Start with scripts/start-backtest.ps1."
    }
    $backtest = Invoke-RestMethod -Uri "$BaseUrl/api/v1/backtest/status" -TimeoutSec 15 -ErrorAction Stop
    if ($backtest.ordersEnabled -or $backtest.mode -ne 'SIMULATION_ONLY') {
        throw 'Backtest endpoint is not confirmed simulation-only; aborting.'
    }

    Write-Host "Fetching USD-M testnet exchangeInfo: $ExchangeInfoUrl"
    $exchange = Invoke-RestMethod -Uri $ExchangeInfoUrl -TimeoutSec 60 -ErrorAction Stop
    $filters = @($Symbols | ForEach-Object { Symbol-ContractFilters -ExchangeInfo $exchange -Symbol $_ })
    $filters | Format-Table -AutoSize

    if ($AsOf -eq 0) {
        $latest = foreach ($symbol in $Symbols) {
            $uri = "$BaseUrl/api/v1/market-data/candles?symbol=$symbol&interval=1m&limit=1"
            $response = Invoke-RestMethod -Uri $uri -TimeoutSec 60 -ErrorAction Stop
            $rows = @($response)
            while ($rows.Count -eq 1 -and $rows[0] -is [System.Array]) {
                $rows = @($rows[0])
            }
            if ($rows.Count -ne 1 -or -not $rows[0].closed) {
                throw "No closed 1m candle for $symbol in local database; run sync-backtest-data.ps1 first."
            }
            [long]$rows[0].closeTime
        }
        $AsOf = [long](($latest | Measure-Object -Minimum).Minimum)
    }

    Write-Host "Common cutoff (asOf UTC epoch ms): $AsOf"
    Write-Host "Replaying $Steps closed 1m candles per pair, context $ContextLimit (5m/15m)."
    $results = @()
    $entryRows = @()
    foreach ($filter in $filters) {
        foreach ($limit in $CostLimits) {
          foreach ($holding in $HoldingLimits) {
            $payload = @{
                symbol = $filter.symbol
                intervals = @('1m','5m','15m')
                steps = $Steps
                contextLimit = $ContextLimit
                asOf = $AsOf
                includeLuna = $false
                priceTick = $filter.priceTick
                quantityStep = $filter.quantityStep
                minimumQuantity = $filter.minimumQuantity
                minimumNotionalUsdt = $filter.minimumNotionalUsdt
                breakEvenTriggerROverride = '1.0'
                maxCostToInitialRiskPctOverride = $limit
                maxHoldingCandles = $(if ($holding -gt 0) { $holding } else { $null })
            } | ConvertTo-Json -Depth 8

            Write-Host "Testing $($filter.symbol), cost/risk $limit%, max holding $(if ($holding) {$holding} else {'none'}) candles ..."
            try {
                $run = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/backtest/run" `
                    -ContentType 'application/json' -Body $payload `
                    -TimeoutSec $TimeoutSec -ErrorAction Stop
                if ($run.symbol -ne $filter.symbol -or $run.replayedCandles -ne $Steps) {
                    throw "Unexpected replay response for $($filter.symbol)"
                }
                $p = $run.javaPortfolio
                if ($AnalyzeEntries) {
                    foreach ($trade in $p.trades) {
                        $entry = $trade.entryDiagnostics
                        if ($null -eq $entry -or $null -eq $entry.atSignal) {
                            throw "Missing pre-entry diagnostics on $($filter.symbol); restart with latest develop."
                        }
                        $signal = $entry.atSignal
                        $trace = $signal.signal
                        $pattern = $signal.lastBarPattern
                        $mtf = $trace.timeframeTrends
                        $entryRows += [PSCustomObject]@{
                            Pair = $filter.symbol
                            CostLimitPct = $limit
                            HoldingLimit = $holding
                            AsOf = $AsOf
                            Outcome = $(if ($trade.netPnlUsdt -gt 0) { 'WIN' } elseif ($trade.netPnlUsdt -lt 0) { 'LOSS' } else { 'FLAT' })
                            Side = $trade.side
                            SignalCloseTime = $trace.candleCloseTime
                            FillOpenTime = $entry.fillOpenTime
                            FillPrice = $entry.filledEntryPrice
                            NetPnlUsdt = $trade.netPnlUsdt
                            NetR = $trade.netRMultiple
                            ExitReason = $trade.exitReason
                            DurationCandles = $trade.durationCandles
                            Setup = $trace.setup
                            SignalReason = $trace.reason
                            PrimaryTrend = $trace.primaryTrend
                            T1 = $(if ($mtf.Count -gt 0) { $mtf[0].trend } else { $null })
                            T5 = $(if ($mtf.Count -gt 1) { $mtf[1].trend } else { $null })
                            T15 = $(if ($mtf.Count -gt 2) { $mtf[2].trend } else { $null })
                            HigherTfConflicts = $signal.higherTimeframeConflicts
                            TrendBar = $(if ($pattern) { $pattern.trendBar } else { $null })
                            Doji = $(if ($pattern) { $pattern.doji } else { $null })
                            BullishRejection = $(if ($pattern) { $pattern.bullishRejection } else { $null })
                            BearishRejection = $(if ($pattern) { $pattern.bearishRejection } else { $null })
                            BreakoutState = $signal.breakoutState
                            PullbackCandidate = $signal.pullbackCandidate
                            ConfirmedPivots = $signal.confirmedPivotCount
                            AtrAtSignal = $signal.atrAtSignal
                            VolumeLevel = $trace.volumeLevel
                            TakerBalance = $trace.takerBalance
                            EffortResult = $trace.effortResult
                            RelativeVolume = $trace.relativeVolume
                            RelativeSpread = $trace.relativeSpread
                            TakerBuyShare = $signal.takerBuyShareAtSignal
                            StopDistancePct = $entry.stopDistancePct
                            GapFromSignalClosePct = $entry.entryGapPct
                            CostToRiskPct = $entry.estimatedCostToInitialRiskPct
                            EstimatedRoundTripCostUsdt = $entry.estimatedRoundTripCostUsdt
                        }
                    }
                }
                $costBlocked = @($run.javaDecisionHistory |
                    Where-Object { $_.riskReason -eq 'COST_TO_RISK_TOO_HIGH' }).Count
                $results += [PSCustomObject]@{
                    Pair = $filter.symbol
                    LimitPct = $limit
                    MaxHoldingCandles = $holding
                    AsOf = $AsOf
                    ReturnPct = [math]::Round([double]$p.totalReturnPct, 4)
                    DrawdownPct = [math]::Round([double]$p.maxDrawdownPct, 4)
                    NetClosedUsdt = [math]::Round([double](($p.trades |
                        Measure-Object -Property netPnlUsdt -Sum).Sum), 4)
                    NetOpenUsdt = $(if ($p.openPositionDiagnostics) {
                        [math]::Round([double]$p.openPositionDiagnostics.unrealizedNetPnlUsdt, 4)
                    } else { 0 })
                    AvgDurationCandles = $(if ($p.tradesClosed -gt 0) {
                        [math]::Round([double](($p.trades |
                            Measure-Object -Property durationCandles -Average).Average), 1)
                    } else { $null })
                    TimeExits = @($p.trades | Where-Object { $_.exitReason -eq 'TIME_LIMIT' }).Count
                    EntrySignals = $p.entrySignals
                    Blocked = $p.entriesBlocked
                    BlockedByCostAtDecision = $costBlocked
                    Closed = $p.tradesClosed
                    Wins = $p.winningTrades
                    Losses = $p.losingTrades
                    Open = $p.openPositionSide
                    Status = 'OK'
                    Error = ''
                }
            }
            catch {
                $message = $_.Exception.Message
                if ($_.ErrorDetails -and $_.ErrorDetails.Message) {
                    $message += ' ' + $_.ErrorDetails.Message
                }
                Write-Warning "Backtest failed for $($filter.symbol), cost $limit%, holding ${holding}: $message"
                $results += [PSCustomObject]@{
                    Pair = $filter.symbol
                    LimitPct = $limit
                    MaxHoldingCandles = $holding
                    AsOf = $AsOf
                    ReturnPct = $null
                    DrawdownPct = $null
                    NetClosedUsdt = $null
                    NetOpenUsdt = $null
                    AvgDurationCandles = $null
                    TimeExits = $null
                    EntrySignals = $null
                    Blocked = $null
                    BlockedByCostAtDecision = $null
                    Closed = $null
                    Wins = $null
                    Losses = $null
                    Open = $null
                    Status = 'ERROR'
                    Error = $message
                }
            }
          }
        }
    }

    $results | Format-Table Pair, LimitPct, MaxHoldingCandles, ReturnPct, DrawdownPct, NetClosedUsdt, NetOpenUsdt,
        Closed, Wins, Losses, TimeExits, AvgDurationCandles, Status -AutoSize

    if ($AnalyzeEntries) {
        if (@($entryRows).Count -eq 0) {
            Write-Warning 'No closed operations with entry diagnostics to compare.'
        } else {
            Write-Host "Executed entry conditions vs outcome (no future candles at signal):"
            $entryRows | Sort-Object Pair, CostLimitPct, HoldingLimit, SignalCloseTime |
                Format-Table Pair, Outcome, Side, Setup, PrimaryTrend,
                    T5, T15, HigherTfConflicts, VolumeLevel, TakerBalance,
                    RelativeVolume, CostToRiskPct, NetPnlUsdt -AutoSize
            $grouped = @($entryRows | Group-Object Pair, CostLimitPct, HoldingLimit, Outcome |
                ForEach-Object {
                    $r = $_.Group
                    [PSCustomObject]@{
                        Pair = $r[0].Pair
                        CostLimitPct = $r[0].CostLimitPct
                        HoldingLimit = $r[0].HoldingLimit
                        Outcome = $r[0].Outcome
                        Trades = $r.Count
                        NetPnlUsdt = [math]::Round([double](($r |
                            Measure-Object -Property NetPnlUsdt -Sum).Sum), 4)
                        AvgNetPnlUsdt = [math]::Round([double](($r |
                            Measure-Object -Property NetPnlUsdt -Average).Average), 4)
                        AvgCostRiskPct = [math]::Round([double](($r |
                            Measure-Object -Property CostToRiskPct -Average).Average), 4)
                        AvgRelativeVolume = [math]::Round([double](($r |
                            Measure-Object -Property RelativeVolume -Average).Average), 4)
                        AvgDurationCandles = [math]::Round([double](($r |
                            Measure-Object -Property DurationCandles -Average).Average), 1)
                        HigherTfConflictTrades = @($r |
                            Where-Object { $_.HigherTfConflicts -gt 0 }).Count
                        PullbackTrades = @($r | Where-Object { $_.Setup -eq 'PULLBACK' }).Count
                        RetestTrades = @($r |
                            Where-Object { $_.Setup -eq 'BREAKOUT_RETEST' }).Count
                    }
                })
            Write-Host 'Group comparison (WIN vs LOSS; grouping is descriptive, not a predictive score):'
            $grouped | Sort-Object Pair, CostLimitPct, HoldingLimit, Outcome |
                Format-Table -AutoSize
            if (-not $NoCsv) {
                $repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
                $outputDir = Join-Path $repoRoot 'results'
                New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
                $rawOutput = Join-Path $outputDir ("entry-conditions-{0}.csv" -f $AsOf)
                $groupOutput = Join-Path $outputDir ("entry-groups-{0}.csv" -f $AsOf)
                $entryRows | Export-Csv -Path $rawOutput -NoTypeInformation -Encoding UTF8
                $grouped | Export-Csv -Path $groupOutput -NoTypeInformation -Encoding UTF8
                Write-Host "Entry comparison CSVs: $rawOutput ; $groupOutput"
            }
        }
    }

    if (-not $NoCsv) {
        $repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
        $outputDir = Join-Path $repoRoot 'results'
        New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
        $filePrefix = if (@($HoldingLimits | Where-Object { $_ -gt 0 }).Count -gt 0) {
            "exit-comparison"
        } else { "pair-comparison" }
        $output = Join-Path $outputDir ("{0}-{1}.csv" -f $filePrefix, $AsOf)
        $results | Export-Csv -Path $output -NoTypeInformation -Encoding UTF8
        Write-Host "CSV saved: $output"
    }
    if (@($results | Where-Object { $_.Status -eq 'ERROR' }).Count -gt 0) {
        throw 'At least one replay failed. Check Status=ERROR and Error column/CSV before comparing.'
    }
}
catch {
    Write-Error ("Pair comparison failed: " + $_.Exception.Message)
    throw
}
