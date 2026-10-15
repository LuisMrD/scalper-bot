# End-to-end mocked REST export on PowerShell 5.1 / pwsh.
# NEVER overwrites real CSV and NEVER reaches Binance or external services.
$ErrorActionPreference='Stop'
$global:bbMockCalls=0
$runner=Join-Path (Split-Path $PSScriptRoot -Parent) 'compare-bollinger-5m.ps1'
$root=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$stem='SOLUSDT-RETEST-1791845999999-5m-w34-39'
$dest=Join-Path $root 'results'
$paths=@(
    (Join-Path $dest "bollinger-5m-aggregate-$stem.csv"),
    (Join-Path $dest "bollinger-5m-windows-$stem.csv"),
    (Join-Path $dest "bollinger-5m-trades-$stem.csv")
)
foreach($p in $paths){
    if(Test-Path $p){throw "Existing real file: refuse overwrite: $p"}
}
$tokens=$null;$errors=$null
[System.Management.Automation.Language.Parser]::ParseFile(
    (Resolve-Path $runner).Path,[ref]$tokens,[ref]$errors) | Out-Null
if($errors.Count -gt 0){throw ("Bollinger PS parser: {0}" -f ($errors|Out-String))}
$plan=@(& $runner -PrintOnly)
foreach($p in @(
    'BOLLINGER_START_WINDOW=34','BOLLINGER_END_WINDOW=39',
    'BOLLINGER_WINDOWS=6','BOLLINGER_ALREADY_INSPECTED=true',
    'BOLLINGER_PERIOD=20','BOLLINGER_DEVIATIONS=2',
    'BOLLINGER_SIGNAL=CLOSE_STRICTLY_OUTSIDE',
    'BOLLINGER_NOT_AN_ENTRY=OUTSIDE_CLOSE_ALONE',
    'BOLLINGER_MAX_WAIT_BARS=9',
    'BOLLINGER_CONFIRMATION=HAMMER,STRENGTH,STRICT_BREAK',
    'BOLLINGER_TAKE_PROFIT=FROZEN_MIDDLE_SMA20',
    'BOLLINGER_STOP_PRIORITY=STOP_FIRST',
    'BOLLINGER_SCENARIOS=CONTROL,CONTROL_PLUS_BOLLINGER_SMA20_2SIGMA',
    'BOLLINGER_NO_LUNA=true','BOLLINGER_NO_ORDERS=true')){
    if($plan -notcontains $p){throw "Bollinger research contract missing $p"}
}
function Invoke-RestMethod {
    [CmdletBinding()]
    param([string]$Uri,[string]$Method,[string]$Body,[string]$ContentType,
          [int]$TimeoutSec)
    switch -Wildcard ($Uri){
        '*/actuator/health' {return [pscustomobject]@{status='UP'}}
        '*/api/v1/backtest/status' {
            return [pscustomobject]@{mode='SIMULATION_ONLY';ordersEnabled=$false}
        }
        '*/api/v1/market-data/settings' {
            return [pscustomobject]@{source='TESTNET';websocketEnabled=$false
                bootstrapEnabled=$false;intervals=@('5m','15m')}
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
        '*/api/v1/backtest/compare-bollinger-5m' {
            $global:bbMockCalls++
            $req=ConvertFrom-Json $Body
            if($Method -ne 'Post' -or $req.symbol -ne 'SOLUSDT' -or
               [int]$req.steps -ne 3600 -or @($req.intervals).Count -ne 2 -or
               $req.intervals[0] -ne '5m' -or $req.intervals[1] -ne '15m' -or
               $req.includeLuna -or $req.includeDecisionHistory -or
               $req.pullbackEnabledOverride -or -not $req.breakoutRetestEnabledOverride){
                throw 'Unexpected or unsafe Bollinger simulation request'
            }
            [long]$end=[long]$req.asOf
            [long]$from=$end-300L*3600000L+1L
            $arms=@(
                [pscustomobject]@{Arm='CONTROL';Closed=-4;Trades=1;Long=1;Short=0;Bb=0;Outside=0;Approved=0},
                [pscustomobject]@{Arm='CONTROL_PLUS_BOLLINGER_SMA20_2SIGMA';Closed=-2;Trades=2;Long=1;Short=1;Bb=1;Outside=3;Approved=1}
            )
            $sum=@();$trades=@()
            foreach($a in $arms){
                $sum += [pscustomobject]@{
                    arm=$a.Arm;tradesClosed=$a.Trades
                    winningTrades=if($a.Bb -gt 0){1}else{0}
                    losingTrades=1;longTrades=$a.Long;shortTrades=$a.Short
                    bbTrades=$a.Bb;outsideBandCloses=$a.Outside
                    bbConfirmedSignals=if($a.Bb -gt 0){2}else{0}
                    bbExpiredSignals=if($a.Bb -gt 0){1}else{0}
                    bbInvalidatedSignals=0
                    bbCooldownRejected=0;bbRiskApproved=$a.Approved
                    entryFilterRejected=1;cooldownRejected=2
                    startingEquityUsdt='1500';netClosedUsdt=$a.Closed
                    longClosedUsdt='-4';shortClosedUsdt=if($a.Bb -gt 0){'2'}else{'0'}
                    markedPnlUsdt=$a.Closed;feesUsdt='0.3'
                    maxDrawdownPct='0.4';openPositionSide='FLAT'
                }
                $trades += [pscustomobject]@{
                    arm=$a.Arm;side='LONG';setup='BREAKOUT_RETEST'
                    outsideBandCloseTime=0;confirmationPattern=$null
                    signalCloseTime=($from+299999L)
                    enteredAtOpenTime=($from+300000L)
                    exitedAtOpenTime=($from+600000L);exitReason='STOP'
                    frozenMiddleTargetUsdt=$null;initialRiskUsdt='5'
                    netPnlUsdt='-4';netR='-0.8';feesUsdt='0.15'
                }
                if($a.Bb -gt 0){
                    $trades += [pscustomobject]@{
                        arm=$a.Arm;side='SHORT';setup='BOLLINGER_MEAN_REVERSION'
                        outsideBandCloseTime=($from+599999L)
                        confirmationPattern='STRONG_REVERSAL_CANDLE'
                        signalCloseTime=($from+899999L)
                        enteredAtOpenTime=($from+900000L)
                        exitedAtOpenTime=($from+1200000L);exitReason='BB_MIDDLE_TARGET'
                        frozenMiddleTargetUsdt='95';initialRiskUsdt='5'
                        netPnlUsdt='2';netR='0.4';feesUsdt='0.15'
                    }
                }
            }
            return [pscustomobject]@{
                symbol='SOLUSDT';fromOpenTime=$from;asOfCloseTime=$end
                candles=3600;bollingerPeriod=20;deviations=2
                summaries=$sum;trades=$trades
            }
        }
        default {throw "Unexpected network call: $Uri"}
    }
}
try {
    & $runner -StartWindow 34 -Windows 6 -AsOf 1791845999999
    if($global:bbMockCalls -ne 6){throw "Expected six dynamic windows"}
    foreach($p in $paths){if(-not(Test-Path $p)){throw "Missing Bollinger research artifact: $p"}}
    $aggregates=@(Import-Csv $paths[0])
    $windows=@(Import-Csv $paths[1])
    $trades=@(Import-Csv $paths[2])
    if($aggregates.Count -ne 2 -or $windows.Count -ne 12 -or $trades.Count -ne 18){
        throw "Expected 2 aggregates, 12 ledgers and 18 closed trades"
    }
    $bb=@($aggregates|Where-Object {$_.Arm -eq 'CONTROL_PLUS_BOLLINGER_SMA20_2SIGMA'})
    if($bb.Count -ne 1 -or
       [decimal]::Parse($bb[0].DeltaMarkedVsControl,
           [System.Globalization.CultureInfo]::InvariantCulture) -ne 12 -or
       [int]$bb[0].BbTrades -ne 6){
        throw "Financial improvement or BB attribution does not reconcile"
    }
    if(@($trades|Where-Object {$_.Setup -eq 'BOLLINGER_MEAN_REVERSION' -and
       $_.ExitReason -eq 'BB_MIDDLE_TARGET' -and $_.FrozenMiddleTargetUsdt -eq '95'}).Count -ne 6){
        throw 'FROZEN SMA target not included in trade details'
    }
    if(@($trades | Where-Object {$_.Setup -eq 'BOLLINGER_MEAN_REVERSION' -and
        ([long]$_.OutsideBandCloseTime -ge [long]$_.SignalCloseTime -or
          $_.ConfirmationPattern -ne 'STRONG_REVERSAL_CANDLE')}).Count -gt 0){
        throw 'Bollinger entry must be confirmed on a later completed 5m bar'
    }
    Write-Host 'PASS: six inspected windows, frozen-target BB entries, two financial ledgers and CSVs'
} finally {
    foreach($p in $paths){Remove-Item -LiteralPath $p -ErrorAction SilentlyContinue}
}
