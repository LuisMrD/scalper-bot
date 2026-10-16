<#
.SYNOPSIS
  BTCUSDT, SOLUSDT, DOGEUSDT: six disjoint 30-candle UTC daily windows.
.DESCRIPTION
  Research only. Six independent arms per symbol/window:
  CONTROL, LEGACY_BOLLINGER_STRUCTURAL, FILTERED_BOLLINGER_STRUCTURAL,
  FILTERED_BOLLINGER_EXTREME, SEQUENCE_VPA_BOLLINGER_STRUCTURAL,
  SEQUENCE_VPA_BOLLINGER_EXTREME.
  Every window contains exactly 30 complete UTC daily candles (720 hours).
  Warmup candles are never traded and no window shares trading candles.
  Exports four CSVs, including paired structural/extreme filter diagnostics for
  every confirmed candidate, even when rejected by volume or trend.
  Requires local backend started with -IncludeDaily, Testnet and orders off.
.EXAMPLE
  .\scripts\start-backtest.ps1 -IncludeDaily -Symbols BTCUSDT,SOLUSDT,DOGEUSDT -CapitalUsdt 1500 -MaxCostToInitialRiskPct 50
  .\scripts\run-bollinger-daily-three-symbols.ps1 -PrintOnly
  .\scripts\run-bollinger-daily-three-symbols.ps1 -SyncHistory
#>
[CmdletBinding()]
param(
    [ValidateSet('BTCUSDT','SOLUSDT','DOGEUSDT')]
    [string[]]$Symbols=@('BTCUSDT','SOLUSDT','DOGEUSDT'),
    [ValidateRange(1,100)][int]$StartWindow=1,
    [ValidateSet(6)][int]$Windows=6,
    [long]$AsOf=1791763199999,
    [ValidateRange(21,500)][int]$ContextLimit=50,
    [decimal]$CapitalPerPortfolioUsdt=1500,
    [ValidateRange(1,100)][int]$CostLimitPct=50,
    [ValidatePattern('^https?://(localhost|127\.0\.0\.1)(:\d+)?$')]
    [string]$BaseUrl='http://127.0.0.1:8080',
    [ValidateRange(30,1800)][int]$TimeoutSec=900,
    [switch]$SyncHistory,
    [switch]$PrintOnly
)
$ErrorActionPreference='Stop'
[long]$dayMs=86400000L
[long]$windowMs=30L*$dayMs
$arms=@('CONTROL','LEGACY_BOLLINGER_STRUCTURAL',
    'FILTERED_BOLLINGER_STRUCTURAL','FILTERED_BOLLINGER_EXTREME',
    'SEQUENCE_VPA_BOLLINGER_STRUCTURAL','SEQUENCE_VPA_BOLLINGER_EXTREME')
if(@($Symbols|Select-Object -Unique).Count -ne $Symbols.Count){
    throw 'Symbols cannot be duplicated'
}
if($AsOf -le 0 -or $AsOf%$dayMs -ne $dayMs-1){
    throw 'AsOf must be a completely closed UTC daily candle'
}
if($CapitalPerPortfolioUsdt -le 0){throw 'Capital must be positive'}
$BaseUrl=$BaseUrl.TrimEnd('/')
$plan=@()
for($i=0;$i -lt $Windows;$i++){
    $n=$StartWindow+$i
    [long]$wallEnd=$AsOf-([long]($n-1)*$windowMs)
    [long]$wallStart=$wallEnd-$windowMs+1
    # AsOf is always a completed UTC daily close: exactly 30 disjoint bars.
    [long]$dailyEnd=$wallEnd
    [long]$firstOpen=$wallStart
    if($wallStart -lt 0 -or $firstOpen%$dayMs -ne 0 -or
       $dailyEnd%$dayMs -ne $dayMs-1 -or
       $firstOpen+30L*$dayMs-1 -ne $dailyEnd){
        throw "Window ${n}: expected exactly 30 complete UTC daily candles"
    }
    $plan+= [pscustomobject]@{
        Window=$n;WallStart=$wallStart;WallEnd=$wallEnd
        FirstOpen=$firstOpen;DailyEnd=$dailyEnd
        WallStartUtc=[DateTimeOffset]::FromUnixTimeMilliseconds($wallStart).UtcDateTime.ToString('u')
        WallEndUtc=[DateTimeOffset]::FromUnixTimeMilliseconds($wallEnd).UtcDateTime.ToString('u')
    }
}
if($PrintOnly){
    Write-Output 'RESEARCH=DAILY_30_CANDLES_6_INDEPENDENT_ARMS'
    Write-Output "SYMBOLS=$($Symbols -join ',')"
    Write-Output "WINDOWS=$Windows"
    Write-Output 'HOURS_PER_WINDOW=720'
    Write-Output 'COMPLETE_DAILY_CANDLES_PER_WINDOW=30'
    Write-Output "TOTAL_INDEPENDENT_PORTFOLIOS=$($Symbols.Count*$Windows*$arms.Count)"
    Write-Output "CAPITAL_PER_PORTFOLIO_USDT=$CapitalPerPortfolioUsdt"
    Write-Output 'BOLLINGER_WARMUP_IS_EXCLUDED_FROM_TRADING_WINDOW=true'
    Write-Output 'STOP_MODES=STRUCTURAL,ONE_TICK_BEYOND_EXTREME'
    Write-Output 'MINIMUM_NET_REWARD_RISK=1.5'
    Write-Output 'SIGNAL_DIAGNOSTICS=BOTH_STOPS_ALL_FILTERS'
    Write-Output 'VPA_MODES=DIRECTIONAL_LEGACY,SEQUENCE_EFFORT_RESULT'
    Write-Output 'CSV_OUTPUTS=WINDOWS,AGGREGATE,TRADES,DIAGNOSTICS'
    Write-Output 'NO_ORDERS=true'
    Write-Output 'NO_LUNA=true'
    foreach($w in $plan){
        Write-Output ("WINDOW={0};WALL_START={1};WALL_END={2};FIRST_OPEN={3};DAILY_CLOSE={4}" -f
            $w.Window,$w.WallStart,$w.WallEnd,$w.FirstOpen,$w.DailyEnd)
    }
    return
}
$state=Invoke-RestMethod "$BaseUrl/api/v1/backtest/status" -TimeoutSec 15
$market=Invoke-RestMethod "$BaseUrl/api/v1/market-data/settings" -TimeoutSec 15
$config=Invoke-RestMethod "$BaseUrl/api/v1/backtest/research-config" -TimeoutSec 15
if($state.mode -ne 'SIMULATION_ONLY' -or $state.ordersEnabled -or
   $market.source -ne 'TESTNET' -or $market.websocketEnabled -or
   $market.bootstrapEnabled -or $market.intervals -notcontains '1d' -or
   $config.ordersEnabled -or $config.requireHigherTimeframeAlignment){
    throw 'Require simulation-only TESTNET, local historical 1d, no websocket or orders'
}
foreach($symbol in $Symbols){
    if($config.symbols -notcontains $symbol){throw "Symbol not configured: $symbol"}
}
$culture=[System.Globalization.CultureInfo]::InvariantCulture
function dec([object]$v){return [decimal]::Parse(([string]$v).Replace(',','.'),$culture)}
if([math]::Abs((dec $config.capitalUsdt)-$CapitalPerPortfolioUsdt) -gt 0.001 -or
   [math]::Abs((dec $config.riskPerTradePct)-0.5) -gt 0.001 -or
   [math]::Abs((dec $config.dailyStopPct)-3) -gt 0.001 -or
   [int]$config.leverage -ne 1 -or
   [math]::Abs((dec $config.feePerSidePct)-0.05) -gt 0.001 -or
   [math]::Abs((dec $config.slippagePerSidePct)-0.02) -gt 0.001 -or
   [math]::Abs((dec $config.maxCostToInitialRiskPct)-$CostLimitPct) -gt 0.001){
    throw 'Actual backtest risk/capital/cost configuration differs from the experiment'
}
. (Join-Path $PSScriptRoot 'lib/binance-contract-filters.ps1')
$exchange=Invoke-RestMethod 'https://demo-fapi.binance.com/fapi/v1/exchangeInfo' -TimeoutSec 60
$rows=New-Object System.Collections.Generic.List[object]
$tradeRows=New-Object System.Collections.Generic.List[object]
$diagnosticRows=New-Object System.Collections.Generic.List[object]
foreach($symbol in $Symbols){
    $filters=Symbol-ContractFilters -ExchangeInfo $exchange -Symbol $symbol
    if($SyncHistory){
        # 50 complete previous days minimum, plus 30 to cover indicator/pivot lookbacks.
        [long]$from=$plan[-1].WallStart - ([long]($ContextLimit+30)*$dayMs)
        [long]$until=$plan[0].WallEnd
        [long]$cursor=$from
        while($cursor -le $until){
            [long]$end=[math]::Min($cursor+7L*$dayMs-1,$until)
            $url="$BaseUrl/api/v1/market-data/sync?symbol=$symbol&interval=1d&from=$cursor&until=$end"
            $sync=Invoke-RestMethod -Uri $url -Method Post -TimeoutSec $TimeoutSec
            if($null -eq $sync -or [long]$sync.until -lt $end){
                throw "Incomplete daily Testnet sync for $symbol from $cursor"
            }
            $cursor=$end+1
        }
    }
    foreach($w in $plan){
        $body=@{
            symbol=$symbol;intervals=@('1d');steps=30;contextLimit=$ContextLimit
            asOf=$w.DailyEnd;includeLuna=$false;includeDecisionHistory=$false
            priceTick=$filters.priceTick;quantityStep=$filters.quantityStep
            minimumQuantity=$filters.minimumQuantity
            minimumNotionalUsdt=$filters.minimumNotionalUsdt
            breakEvenTriggerROverride='1.0'
            maxCostToInitialRiskPctOverride=$CostLimitPct
            pullbackEnabledOverride=$false;breakoutRetestEnabledOverride=$true
        } | ConvertTo-Json -Depth 8
        Write-Host ("DAILY {0} window {1} wall {2}..{3}" -f
            $symbol,$w.Window,$w.WallStartUtc,$w.WallEndUtc)
        $report=Invoke-RestMethod -Uri "$BaseUrl/api/v1/backtest/compare-bollinger-daily" `
            -Method Post -ContentType 'application/json' -Body $body -TimeoutSec $TimeoutSec
        if($report.symbol -ne $symbol -or $report.interval -ne '1d' -or
           [long]$report.firstCandleOpenTime -ne $w.FirstOpen -or
           [long]$report.lastCandleCloseTime -ne $w.DailyEnd -or
           [int]$report.completeDailyCandles -ne 30 -or
           [int]$report.period -ne 20 -or [decimal]$report.minimumNetRewardRisk -ne 1.5){
            throw "Daily response period or strategy mismatch for $symbol w$($w.Window)"
        }
        $summaries=@($report.summaries)
        if($null -eq $report.signalDiagnostics){
            throw "Daily API lacks signalDiagnostics; rebuild and restart the current branch"
        }
        $signals=@($report.signalDiagnostics)
        if(@($signals|Where-Object {
            $_.arm -notin @('FILTERED_BOLLINGER_STRUCTURAL','FILTERED_BOLLINGER_EXTREME',
                'SEQUENCE_VPA_BOLLINGER_STRUCTURAL','SEQUENCE_VPA_BOLLINGER_EXTREME')
        }).Count -ne 0){
            throw "Unexpected diagnostic arm"
        }
        foreach($signal in $signals){
            if([long]$signal.confirmationCloseTime -lt $w.FirstOpen -or
                [long]$signal.confirmationCloseTime -gt $w.DailyEnd -or
                [long]$signal.triggerCloseTime -ge [long]$signal.confirmationCloseTime){
                throw 'Diagnostic outside closed daily replay'
            }
            $structural=$signal.structural
            $extreme=$signal.extreme
            if($null -eq $structural -or $null -eq $extreme -or
                $structural.stopMode -ne 'STRUCTURAL' -or
                $extreme.stopMode -ne 'EXTREME_CANDLE' -or
                $structural.signalClosePrice -ne $extreme.signalClosePrice -or
                $structural.frozenMiddleTarget -ne $extreme.frozenMiddleTarget){
                throw 'Mismatched dual-stop diagnostic snapshot'
            }
            $expectedVpaMode=if($signal.arm -like 'SEQUENCE_VPA_BOLLINGER_*'){
                'SEQUENCE_EFFORT_RESULT'
            } else {
                'DIRECTIONAL_LEGACY'
            }
            if($structural.volumeMode -ne $expectedVpaMode -or
                $extreme.volumeMode -ne $expectedVpaMode -or
                ($expectedVpaMode -eq 'SEQUENCE_EFFORT_RESULT' -and
                    $null -eq $structural.sequenceEvidence)){
                throw "Volume mode / sequence evidence mismatch for $($signal.arm)"
            }
            $diagnosticRows.Add([pscustomobject]@{
                Symbol=$symbol;Window=$w.Window;Arm=$signal.arm
                TriggerCloseTime=$signal.triggerCloseTime
                ConfirmationCloseTime=$signal.confirmationCloseTime
                Side=$signal.side;Pattern=$signal.confirmationPattern
                CooldownBlocked=$signal.cooldownBlocked
                SignalClosePrice=$structural.signalClosePrice
                FrozenMiddleTarget=$structural.frozenMiddleTarget
                TargetDistancePrice=$structural.targetDistancePrice
                PrimaryTrend=$structural.primaryTrend
                PrimaryBreakout=$structural.primaryBreakout
                StrongHigherTimeframeTrend=$structural.strongHigherTimeframeTrend
                VolumeCloseTime=$structural.volumeCloseTime
                VolumeLevel=$structural.volumeLevel
                TakerBalance=$structural.takerBalance
                EffortResult=$structural.effortResult
                ExtremeLow=$structural.extremeLow
                ExtremeHigh=$structural.extremeHigh
                VolumeMode=$structural.volumeMode
                SequenceBars=$structural.sequenceEvidence.sequenceCandles
                TriggerRelativeVolume=$structural.sequenceEvidence.triggerRelativeVolume
                TriggerRelativeSpread=$structural.sequenceEvidence.triggerRelativeSpread
                ConfirmationRelativeVolume=$structural.sequenceEvidence.confirmationRelativeVolume
                TriggerAbsorption=$structural.sequenceEvidence.triggerAbsorption
                ContinuationPressureFaded=$structural.sequenceEvidence.continuationPressureFaded
                FailedContinuation=$structural.sequenceEvidence.failedContinuation
                ConfirmationRejection=$structural.sequenceEvidence.confirmationRejection
                StrongContinuation=$structural.sequenceEvidence.strongContinuation
                StructuralApproved=$structural.approved
                StructuralFirstRejection=$structural.firstRejection
                StructuralAllFailures=($structural.failedChecks -join '|')
                StructuralStop=$structural.stopPrice
                StructuralStopDistance=$structural.stopDistancePrice
                StructuralNetReward=$structural.netRewardPrice
                StructuralRisk=$structural.totalRiskPrice
                StructuralNetRR=$structural.netRewardRisk
                StructuralCostToRiskPct=$structural.costToRiskPct
                ExtremeApproved=$extreme.approved
                ExtremeFirstRejection=$extreme.firstRejection
                ExtremeAllFailures=($extreme.failedChecks -join '|')
                ExtremeStop=$extreme.stopPrice
                ExtremeStopDistance=$extreme.stopDistancePrice
                ExtremeNetReward=$extreme.netRewardPrice
                ExtremeRisk=$extreme.totalRiskPrice
                ExtremeNetRR=$extreme.netRewardRisk
                ExtremeCostToRiskPct=$extreme.costToRiskPct
            })
        }
        if($summaries.Count -ne $arms.Count -or
           @($summaries|ForEach-Object {$_.arm}|Sort-Object -Unique).Count -ne $arms.Count){
            throw "Missing independent comparison arms"
        }
        foreach($arm in $arms){
            $matching=@($summaries|Where-Object {$_.arm -eq $arm})
            if($matching.Count -ne 1){throw "Missing arm: $arm"}
            $s=$matching[0]
            if(($arm -like 'FILTERED_BOLLINGER_*' -or
                 $arm -like 'SEQUENCE_VPA_BOLLINGER_*') -and
                @($signals|Where-Object {$_.arm -eq $arm}).Count -ne [int]$s.confirmedSignals){
                throw "Missing per-signal filter diagnostics for $arm"
            }
            if([math]::Abs((dec $s.startingEquityUsdt)-$CapitalPerPortfolioUsdt) -gt 0.001){
                throw "Capital shared or changed unexpectedly"
            }
            $ts=@($report.trades|Where-Object {$_.arm -eq $arm})
            if($ts.Count -ne [int]$s.tradesClosed){
                throw "Trade count mismatch for $arm"
            }
            $sum=0.0
            foreach($trade in $ts){
                if([long]$trade.signalCloseTime -ge [long]$trade.enteredAtOpenTime -or
                   [long]$trade.enteredAtOpenTime -lt $w.FirstOpen -or
                   [long]$trade.exitedAtOpenTime -gt $w.DailyEnd){
                    throw "Trade outside the completed daily window"
                }
                $sum += [double]$trade.netPnlUsdt
                $tradeRows.Add([pscustomobject]@{
                    Symbol=$symbol;Window=$w.Window;Arm=$arm
                    Side=$trade.side;Setup=$trade.setup
                    SignalCloseTime=$trade.signalCloseTime
                    OutsideBandCloseTime=$trade.outsideBandCloseTime
                    ConfirmationPattern=$trade.confirmationPattern
                    EnteredAtOpenTime=$trade.enteredAtOpenTime
                    ExitedAtOpenTime=$trade.exitedAtOpenTime
                    EntryPrice=$trade.entryPrice;InitialStop=$trade.initialStop
                    ExitPrice=$trade.exitPrice;FrozenTarget=$trade.frozenTarget
                    ExitReason=$trade.exitReason;InitialRiskUsdt=$trade.initialRiskUsdt
                    NetPnlUsdt=$trade.netPnlUsdt;NetRMultiple=$trade.netRMultiple
                    FeesUsdt=$trade.feesUsdt
                })
            }
            if([math]::Abs($sum-[double]$s.netClosedUsdt) -gt 0.01){
                throw "Closed PnL mismatch"
            }
            $rows.Add([pscustomobject]@{
                Symbol=$symbol;Window=$w.Window;Arm=$arm
                WallStart=$w.WallStart;WallEnd=$w.WallEnd
                FirstDailyOpen=$w.FirstOpen;LastDailyClose=$w.DailyEnd
                Candles=30;Hours=720;CapitalUsdt=$s.startingEquityUsdt
                Trades=$s.tradesClosed;Wins=$s.wins;Losses=$s.losses
                BbTrades=$s.bbTrades;OutsideBandCloses=$s.outsideBandCloses
                Confirmed=$s.confirmedSignals;Expired=$s.expiredSignals
                Invalidated=$s.invalidatedSignals;FilteredRejected=$s.filteredRejected
                RiskApproved=$s.riskApproved;ClosedUsdt=$s.netClosedUsdt
                MarkedUsdt=$s.markedPnlUsdt;FeesUsdt=$s.feesUsdt
                MaxDrawdownPct=$s.maxDrawdownPct;OpenPositionSide=$s.openPositionSide
            })
        }
    }
}
if($rows.Count -ne $Symbols.Count*$Windows*$arms.Count){
    throw 'Incomplete study; not exporting partial CSVs'
}
$root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$out=Join-Path $root 'results'
New-Item -Path $out -ItemType Directory -Force | Out-Null
$stamp="1d-30candles-seqvpa-$AsOf-w$StartWindow-$($StartWindow+$Windows-1)"
$windowsPath=Join-Path $out "bollinger-daily-windows-$stamp.csv"
$aggregatePath=Join-Path $out "bollinger-daily-aggregate-$stamp.csv"
$tradesPath=Join-Path $out "bollinger-daily-trades-$stamp.csv"
$diagnosticsPath=Join-Path $out "bollinger-daily-diagnostics-$stamp.csv"
foreach($p in @($windowsPath,$aggregatePath,$tradesPath,$diagnosticsPath)){
    if(Test-Path -LiteralPath $p){throw "Will not overwrite existing file $p"}
}
$aggregate=@($rows|Group-Object Symbol,Arm|ForEach-Object {
    $group=@($_.Group)
    [decimal]$closed=0;[decimal]$marked=0;[decimal]$fees=0
    foreach($row in $group){
        $closed+=dec $row.ClosedUsdt
        $marked+=dec $row.MarkedUsdt
        $fees+=dec $row.FeesUsdt
    }
    [pscustomobject]@{
        Symbol=$group[0].Symbol;Arm=$group[0].Arm;Windows=$group.Count
        TotalTrades=($group|Measure-Object -Property Trades -Sum).Sum
        BbTrades=($group|Measure-Object -Property BbTrades -Sum).Sum
        SumClosedUsdt=$closed
        SumMarkedUsdt=$marked
        SumFeesUsdt=$fees
    }
})
if($aggregate.Count -ne $Symbols.Count*$arms.Count -or
    @($aggregate|Where-Object {$_.Windows -ne $Windows}).Count -ne 0){
    throw 'Missing symbol/scenario windows'
}
@($rows.ToArray()) | Export-Csv -Path $windowsPath -NoTypeInformation -Encoding UTF8
$aggregate | Export-Csv -Path $aggregatePath -NoTypeInformation -Encoding UTF8
if($tradeRows.Count -gt 0){
    @($tradeRows.ToArray()) | Export-Csv -Path $tradesPath -NoTypeInformation -Encoding UTF8
} else {
    'Symbol,Window,Arm,Side,Setup,SignalCloseTime,OutsideBandCloseTime,ConfirmationPattern,EnteredAtOpenTime,ExitedAtOpenTime,EntryPrice,InitialStop,ExitPrice,FrozenTarget,ExitReason,InitialRiskUsdt,NetPnlUsdt,NetRMultiple,FeesUsdt' |
        Set-Content -Path $tradesPath -Encoding UTF8
}
if($diagnosticRows.Count -gt 0){
    @($diagnosticRows.ToArray()) | Export-Csv -Path $diagnosticsPath -NoTypeInformation -Encoding UTF8
} else {
    'Symbol,Window,Arm,TriggerCloseTime,ConfirmationCloseTime,Side,Pattern,CooldownBlocked,SignalClosePrice,FrozenMiddleTarget,TargetDistancePrice,PrimaryTrend,PrimaryBreakout,StrongHigherTimeframeTrend,VolumeCloseTime,VolumeLevel,TakerBalance,EffortResult,ExtremeLow,ExtremeHigh,VolumeMode,SequenceBars,TriggerRelativeVolume,TriggerRelativeSpread,ConfirmationRelativeVolume,TriggerAbsorption,ContinuationPressureFaded,FailedContinuation,ConfirmationRejection,StrongContinuation,StructuralApproved,StructuralFirstRejection,StructuralAllFailures,StructuralStop,StructuralStopDistance,StructuralNetReward,StructuralRisk,StructuralNetRR,StructuralCostToRiskPct,ExtremeApproved,ExtremeFirstRejection,ExtremeAllFailures,ExtremeStop,ExtremeStopDistance,ExtremeNetReward,ExtremeRisk,ExtremeNetRR,ExtremeCostToRiskPct' |
        Set-Content -Path $diagnosticsPath -Encoding UTF8
}
$aggregate | Format-Table -AutoSize
Write-Host "Saved: $aggregatePath"
Write-Host "Saved: $windowsPath"
Write-Host "Saved: $tradesPath"
Write-Host "Saved: $diagnosticsPath"
