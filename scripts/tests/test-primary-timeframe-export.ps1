# Full offline regression through the actual 5m-vs-15m PowerShell CSV path.
# Executes mocked HTTP only; no MySQL, Binance network, orders or paid LLM.
$ErrorActionPreference='Stop'
$global:tfMockBacktests=0
$runner=Join-Path (Split-Path $PSScriptRoot -Parent) 'validate-primary-timeframe-oos.ps1'
$root=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$stem='SOLUSDT-RETEST-1791845999999-5m-vs-15m-w46-51'
$dest=Join-Path $root 'results'
$paths=@(
    (Join-Path $dest "primary-timeframe-oos-aggregate-$stem.csv"),
    (Join-Path $dest "primary-timeframe-oos-windows-$stem.csv"),
    (Join-Path $dest "primary-timeframe-oos-trades-$stem.csv")
)
foreach($path in $paths){
    if(Test-Path $path){throw "Refusing to overwrite existing real data: $path"}
}
function Invoke-RestMethod {
    [CmdletBinding()]
    param([string]$Uri,[string]$Method,[string]$Body,
          [string]$ContentType,[int]$TimeoutSec)
    switch -Wildcard ($Uri){
        '*/actuator/health' {return [pscustomobject]@{status='UP'}}
        '*/api/v1/backtest/status' {
            return [pscustomobject]@{mode='SIMULATION_ONLY';ordersEnabled=$false}
        }
        '*/api/v1/market-data/settings' {
            return [pscustomobject]@{
                source='TESTNET';websocketEnabled=$false;bootstrapEnabled=$false
                intervals=@('5m','15m','1h')
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
            return [pscustomobject]@{symbols=@([pscustomobject]@{
                symbol='SOLUSDT';status='TRADING';contractType='PERPETUAL'
                quoteAsset='USDT';filters=@(
                    [pscustomobject]@{filterType='PRICE_FILTER';tickSize='0.01'},
                    [pscustomobject]@{filterType='LOT_SIZE';stepSize='0.001';minQty='0.001'},
                    [pscustomobject]@{filterType='MIN_NOTIONAL';notional='5'}
                )
            })}
        }
        '*/api/v1/backtest/compare-5m-15m' {
            $global:tfMockBacktests++
            $req=ConvertFrom-Json $Body
            if($Method -ne 'Post' -or $req.symbol -ne 'SOLUSDT' -or
                [int]$req.steps -ne 3600 -or
                @($req.intervals).Count -ne 2 -or
                $req.intervals[0] -ne '5m' -or $req.intervals[1] -ne '15m' -or
                $req.includeLuna -or $req.pullbackEnabledOverride -or
                -not $req.breakoutRetestEnabledOverride){
                throw 'Mock received an unexpected/time-shifted backtest request'
            }
            [long]$asOf=[long]$req.asOf
            [long]$from=$asOf-(300L*3600000L)+1
            $arms=@(
                [pscustomobject]@{Arm='PRIMARY_5M_CONTEXT_15M';P='5m';H='15m';Bars=3600;Wait=12;L='2';S='-3'},
                [pscustomobject]@{Arm='PRIMARY_15M_CONTEXT_1H';P='15m';H='1h';Bars=1200;Wait=4;L='3';S='-2'}
            )
            $summaries=@();$trades=@()
            foreach($a in $arms){
                [decimal]$long=[decimal]::Parse($a.L,[System.Globalization.CultureInfo]::InvariantCulture)
                [decimal]$short=[decimal]::Parse($a.S,[System.Globalization.CultureInfo]::InvariantCulture)
                $summaries += [pscustomobject]@{
                    arm=$a.Arm;primaryInterval=$a.P;contextInterval=$a.H
                    replayedCandles=$a.Bars;cooldownBars=$a.Wait
                    tradesClosed=2;winningTrades=1;losingTrades=1
                    longTrades=1;shortTrades=1;longWins=1;shortWins=0
                    entryFilterBlockedSignals=3;cooldownBlockedSignals=4
                    startingEquityUsdt='1500';netClosedUsdt=($long+$short)
                    longNetClosedUsdt=$long;shortNetClosedUsdt=$short
                    markedPnlUsdt=($long+$short);feesUsdt='0.7'
                    maxDrawdownPct='0.5';openPositionSide='FLAT'
                }
                foreach($side in @('LONG','SHORT')){
                    [long]$shift=if($side -eq 'LONG'){30}else{39}
                    [long]$entry=$from+(900000L*$shift)
                    [decimal]$net=if($side -eq 'LONG'){$long}else{$short}
                    $trades += [pscustomobject]@{
                        arm=$a.Arm;side=$side;entrySetup='BREAKOUT_RETEST'
                        signalCloseTime=($entry-1)
                        enteredAtOpenTime=$entry;exitCandleOpenTime=($entry+900000L)
                        primaryTrend=if($side -eq 'LONG'){'UP'}else{'DOWN'}
                        contextTrend='RANGE';breakoutState='UP_FOLLOW_THROUGH'
                        volumeLevel='HIGH';exitReason='STOP'
                        initialRiskUsdt='5';netPnlUsdt=$net;netR='0.5'
                        mfeR='0.9';maeR='0.6';feesUsdt='0.35'
                        estimatedCostToInitialRiskPct='30'
                    }
                }
            }
            return [pscustomobject]@{
                symbol='SOLUSDT';fromOpenTime=$from;asOfCloseTime=$asOf
                periodHours=300;summaries=$summaries;trades=$trades
            }
        }
        default {throw "Unexpected network/API call in offline test: $Uri"}
    }
}
try {
    & $runner -StartWindow 46 -Windows 6 -AsOf 1791845999999
    if($global:tfMockBacktests -ne 6){throw "Expected 6 comparisons, got $global:tfMockBacktests"}
    foreach($file in $paths){if(-not(Test-Path $file)){throw "Missing mock CSV: $file"}}
    $a=@(Import-Csv $paths[0]);$w=@(Import-Csv $paths[1]);$t=@(Import-Csv $paths[2])
    if($a.Count -ne 2 -or $w.Count -ne 12 -or $t.Count -ne 24){
        throw "Expected 2 aggregates, 12 portfolios and 24 trades"
    }
    $base=@($a|Where-Object {$_.Arm -eq 'PRIMARY_5M_CONTEXT_15M'})
    $fifteen=@($a|Where-Object {$_.Arm -eq 'PRIMARY_15M_CONTEXT_1H'})
    if($base.Count -ne 1 -or $fifteen.Count -ne 1){throw 'Missing matched arm'}
    if([decimal]::Parse($base[0].SumNetClosedUsdt,[System.Globalization.CultureInfo]::InvariantCulture) -ne -6 -or
       [decimal]::Parse($fifteen[0].SumNetClosedUsdt,[System.Globalization.CultureInfo]::InvariantCulture) -ne 6 -or
       [decimal]::Parse($fifteen[0].DeltaMarkedVs5mUsdt,[System.Globalization.CultureInfo]::InvariantCulture) -ne 12){
        throw 'Financial aggregates do not match net side-level expected results'
    }
    if(@($w|Where-Object {$_.PrimaryInterval -eq '15m' -and
            $_.Candles -eq '1200' -and $_.CooldownBars -eq '4'}).Count -ne 6){
        throw '15m arm must preserve exactly 60-minute wait'
    }
    Write-Host 'PASS: 6 windows, 2 independent ledgers, matched 300h, side PnL and complete CSVs'
} finally {
    foreach($path in $paths){Remove-Item -LiteralPath $path -ErrorAction SilentlyContinue}
}
