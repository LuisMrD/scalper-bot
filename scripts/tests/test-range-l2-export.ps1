# Real end-to-end export using mock HTTP responses, without network, Binance,
# database or actual trades. Exercises exact Java DTO fields through CSV write.
$ErrorActionPreference='Stop'
$global:mockBacktestPosts=0
$runner=Join-Path (Split-Path $PSScriptRoot -Parent) 'validate-range-l2-oos.ps1'
$root=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$stem='SOLUSDT-RETEST-1791845999999-5m-w34-39'
$dest=Join-Path $root 'results'
$paths=@(
    (Join-Path $dest "range-l2-oos-aggregate-$stem.csv"),
    (Join-Path $dest "range-l2-oos-windows-$stem.csv"),
    (Join-Path $dest "range-l2-oos-trades-$stem.csv")
)
foreach($path in $paths){
    if(Test-Path $path){throw "Refusing to overwrite actual research file in mock: $path"}
}

function Invoke-RestMethod {
    [CmdletBinding()]
    param([string]$Uri,[string]$Method,[string]$Body,
          [string]$ContentType,[int]$TimeoutSec)
    switch -Wildcard ($Uri) {
        '*/actuator/health' {
            return [pscustomobject]@{status='UP'}
        }
        '*/api/v1/backtest/status' {
            return [pscustomobject]@{mode='SIMULATION_ONLY';ordersEnabled=$false}
        }
        '*/api/v1/market-data/settings' {
            return [pscustomobject]@{
                source='TESTNET';websocketEnabled=$false
                bootstrapEnabled=$false;intervals=@('5m','15m')
            }
        }
        '*/api/v1/risk/status' {
            return [pscustomobject]@{configured=$true;ordersEnabled=$false}
        }
        '*/api/v1/backtest/research-config' {
            return [pscustomobject]@{
                ordersEnabled=$false;executionMode='DISABLED'
                symbols=@('SOLUSDT');requireHigherTimeframeAlignment=$false
                requireDominantTakerVolume=$true;breakoutLookback=10
                retestMaxBars=4;maxPrimaryStalenessBars=2
                capitalUsdt='1500';riskPerTradePct='0.5'
                dailyStopPct='3';leverage=1;feePerSidePct='0.05'
                slippagePerSidePct='0.02';breakEvenTriggerR='1'
                structuralAtrBuffer='0.5';maxCostToInitialRiskPct='50'
            }
        }
        'https://demo-fapi.binance.com/fapi/v1/exchangeInfo' {
            return [pscustomobject]@{
                symbols=@([pscustomobject]@{
                    symbol='SOLUSDT';status='TRADING';contractType='PERPETUAL'
                    quoteAsset='USDT';filters=@(
                        [pscustomobject]@{filterType='PRICE_FILTER';tickSize='0.01'},
                        [pscustomobject]@{filterType='LOT_SIZE';stepSize='0.001';minQty='0.001'},
                        [pscustomobject]@{filterType='MIN_NOTIONAL';notional='5'}
                    )
                })
            }
        }
        '*/api/v1/backtest/compare-range-l2' {
            $global:mockBacktestPosts++
            $request=ConvertFrom-Json $Body
            if($Method -ne 'Post' -or $request.symbol -ne 'SOLUSDT' -or
               [int]$request.steps -ne 3600 -or $request.includeLuna -or
               $request.pullbackEnabledOverride -or
               -not $request.breakoutRetestEnabledOverride){
                throw 'Unexpected request under mocked backtest'
            }
            [long]$asOf=[long]$request.asOf
            [long]$from=$asOf-(300L*3600000L)+1
            $scenarioSpecs=@(
                [pscustomobject]@{Policy='CONTROL';Long='2.5';Short='-3.5';Blocked=0},
                [pscustomobject]@{Policy='BLOCK_ALL_15M_RANGE_SHORTS';Long='1.5';Short='-1.5';Blocked=2},
                [pscustomobject]@{Policy='REPLACE_RANGE_SHORT_RETEST_WITH_L2';Long='4.5';Short='-3.0';Blocked=1}
            )
            $summaries=@()
            $trades=@()
            foreach($spec in $scenarioSpecs){
                [decimal]$net=[decimal]::Parse($spec.Long,[System.Globalization.CultureInfo]::InvariantCulture)+
                    [decimal]::Parse($spec.Short,[System.Globalization.CultureInfo]::InvariantCulture)
                $summaries += [pscustomobject]@{
                    policy=$spec.Policy;tradesClosed=2;winningTrades=1
                    losingTrades=1;shortTrades=1;shortWinners=0;longTrades=1
                    entryFilterBlockedSignals=3;cooldownBlockedSignals=4
                    rangeRetestBlockedSignals=$spec.Blocked
                    l2Signals=if($spec.Policy -eq 'REPLACE_RANGE_SHORT_RETEST_WITH_L2'){1}else{0}
                    l2ClosedTrades=if($spec.Policy -eq 'REPLACE_RANGE_SHORT_RETEST_WITH_L2'){1}else{0}
                    l2BlockedByCooldown=if($spec.Policy -eq 'REPLACE_RANGE_SHORT_RETEST_WITH_L2'){1}else{0}
                    startingEquityUsdt='1500';netClosedUsdt=$net
                    shortNetClosedUsdt=$spec.Short;longNetClosedUsdt=$spec.Long
                    markedPnlUsdt=$net;feesUsdt='0.7'
                    maxDrawdownPct='0.2';openPositionSide='FLAT'
                }
                foreach($dir in @('LONG','SHORT')){
                    $profit=if($dir -eq 'LONG'){$spec.Long}else{$spec.Short}
                    [long]$entryIndex=if($dir -eq 'LONG'){100}else{110}
                    [long]$entry=$from+300000L*$entryIndex
                    $trades += [pscustomobject]@{
                        policy=$spec.Policy;side=$dir
                        entrySetup=if($dir -eq 'SHORT' -and $spec.Policy -eq 'REPLACE_RANGE_SHORT_RETEST_WITH_L2'){'RANGE_FALSE_BREAK_L2'}else{'BREAKOUT_RETEST'}
                        enteredAtOpenTime=$entry
                        exitCandleOpenTime=($entry+3000000L)
                        entryBreakoutState='DOWN_FOLLOW_THROUGH'
                        exitReason='STOP';initialRiskUsdt='5'
                        netPnlUsdt=$profit;feesUsdt='0.35'
                        entryFeatures=[pscustomobject]@{
                            signalCloseTime=($entry-1)
                            primaryTrend='RANGE';trend15m='RANGE'
                            higherTimeframeConflicts=0
                            volumeLevel='NORMAL';takerBalance='SELL_DOMINANT'
                            relativeVolume='1.5';relativeSpread='1.2'
                            takerBuyShare='0.3';effortResult='NORMAL'
                            barDirection='DOWN';trendBar=$true;doji=$false
                            insideBar=$false;outsideBar=$false
                            bullishRejection=$false;bearishRejection=$true
                            stopDistancePct='0.5';estimatedCostToInitialRiskPct='30'
                        }
                    }
                }
            }
            return [pscustomobject]@{
                symbol='SOLUSDT';primaryInterval='5m'
                fromOpenTime=$from;asOfCloseTime=$asOf;replayedCandles=3600
                summaries=$summaries;trades=$trades
            }
        }
        default {throw "Unexpected external call in mock: $Uri"}
    }
}
try {
    & $runner -StartWindow 34 -Windows 6 -AsOf 1791845999999 -DiagnoseWindow 39
    if($global:mockBacktestPosts -ne 1){
        throw "Single-window diagnostic invoked $global:mockBacktestPosts backtests, expected 1"
    }
    foreach($path in $paths) {
        if(Test-Path $path){throw "Diagnostic mode must not write partial CSV: $path"}
    }
    & $runner -StartWindow 34 -Windows 6 -AsOf 1791845999999
    if($global:mockBacktestPosts -ne 7){
        throw "Full and diagnostic mode must invoke 7 total API comparisons"
    }
    foreach($path in $paths){
        if(-not(Test-Path $path)){throw "Missing generated mock CSV: $path"}
    }
    $windowRows=@(Import-Csv $paths[1])
    $aggregateRows=@(Import-Csv $paths[0])
    $tradeRows=@(Import-Csv $paths[2])
    if($windowRows.Count -ne 18 -or $aggregateRows.Count -ne 3 -or
       $tradeRows.Count -ne 36){
        throw 'Mock must export 18 window records, 3 summaries and 36 trades'
    }
    if(@($tradeRows | Where-Object {$_.EntrySetup -eq 'RANGE_FALSE_BREAK_L2'}).Count -ne 6){
        throw 'One independently simulated L2 SHORT trade required per mock window'
    }
    foreach($row in $windowRows){
        if($null -eq $row.L2Signals -or $null -eq $row.L2ClosedTrades -or
           $null -eq $row.L2BlockedByCooldown){
            throw 'Missing L2 counters in exported windows'
        }
    }
    foreach($row in $windowRows){
        if([string]::IsNullOrWhiteSpace($row.LongClosedPnlUsdt) -or
           [string]::IsNullOrWhiteSpace($row.ShortClosedPnlUsdt)){
            throw "Missing long/short PnL in exported CSV for window $($row.Window)"
        }
        [decimal]$ln=[decimal]::Parse($row.LongClosedPnlUsdt,[System.Globalization.CultureInfo]::InvariantCulture)
        [decimal]$sn=[decimal]::Parse($row.ShortClosedPnlUsdt,[System.Globalization.CultureInfo]::InvariantCulture)
        [decimal]$net=[decimal]::Parse($row.NetClosedUsdt,[System.Globalization.CultureInfo]::InvariantCulture)
        if($ln+$sn -ne $net){throw 'PnL side total mismatch in exported CSV'}
    }
    foreach($row in $aggregateRows){
        if([int]$row.Windows -ne 6 -or [int]$row.Trades -ne 12){
            throw 'Aggregate did not include all 6 independent windows'
        }
        [decimal]$net=[decimal]::Parse($row.SumNetClosedUsdt,[System.Globalization.CultureInfo]::InvariantCulture)
        [decimal]$ln=[decimal]::Parse($row.LongClosedPnlUsdt,[System.Globalization.CultureInfo]::InvariantCulture)
        [decimal]$sn=[decimal]::Parse($row.ShortClosedPnlUsdt,[System.Globalization.CultureInfo]::InvariantCulture)
        if($net -ne $ln+$sn){throw "Invalid aggregate LONG/SHORT PnL for $($row.Policy)"}
    }
    Write-Host 'PASS: full L2 PowerShell aggregation against exact mocked Java API schema'
} finally {
    foreach($path in $paths){Remove-Item -LiteralPath $path -ErrorAction SilentlyContinue}
}
