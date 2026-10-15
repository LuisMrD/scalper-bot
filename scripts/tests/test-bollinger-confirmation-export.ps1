# Offline Windows PowerShell 5.1 financial export regression.
# Validates frozen Bollinger confirmed-pattern API and full six-window CSV path.
$ErrorActionPreference='Stop'
$script=Join-Path (Split-Path $PSScriptRoot -Parent) 'compare-bollinger-5m.ps1'
$tokens=$null;$errors=$null
[System.Management.Automation.Language.Parser]::ParseFile(
    (Resolve-Path $script).Path,[ref]$tokens,[ref]$errors) | Out-Null
if($errors.Count -gt 0){throw ("Bollinger script parse failure: {0}" -f ($errors|Out-String))}
$plan=@(& $script -PrintOnly)
foreach($expected in @(
    'BOLLINGER_START_WINDOW=34','BOLLINGER_END_WINDOW=39',
    'BOLLINGER_ALREADY_INSPECTED=true','BOLLINGER_WINDOWS=6',
    'BOLLINGER_PERIOD=20','BOLLINGER_DEVIATIONS=2',
    'BOLLINGER_SIGNAL=CLOSE_STRICTLY_OUTSIDE',
    'BOLLINGER_CONFIRMATION=HAMMER,STRENGTH,STRICT_BREAK',
    'BOLLINGER_MAX_WAIT_BARS=9',
    'BOLLINGER_NOT_AN_ENTRY=OUTSIDE_CLOSE_ALONE',
    'BOLLINGER_TAKE_PROFIT=FROZEN_MIDDLE_SMA20',
    'BOLLINGER_STOP_PRIORITY=STOP_FIRST','BOLLINGER_NO_LUNA=true',
    'BOLLINGER_NO_ORDERS=true'
)){
    if($plan -notcontains $expected){throw "Missing Bollinger invariant: $expected"}
}
$root=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$folder=Join-Path $root 'results'
$stem='SOLUSDT-RETEST-1791845999999-5m-w34-39'
$paths=@(
    (Join-Path $folder "bollinger-5m-aggregate-$stem.csv"),
    (Join-Path $folder "bollinger-5m-windows-$stem.csv"),
    (Join-Path $folder "bollinger-5m-trades-$stem.csv")
)
foreach($p in $paths){if(Test-Path $p){throw "Will not overwrite original research results: $p"}}
$script:mockRequests=0
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
            return [pscustomobject]@{source='TESTNET';websocketEnabled=$false
                bootstrapEnabled=$false;intervals=@('5m','15m')}
        }
        '*/api/v1/risk/status' {
            return [pscustomobject]@{configured=$true;ordersEnabled=$false}
        }
        '*/api/v1/backtest/research-config' {
            return [pscustomobject]@{
                ordersEnabled=$false;executionMode='DISABLED';symbols=@('SOLUSDT')
                requireHigherTimeframeAlignment=$false;requireDominantTakerVolume=$true
                breakoutLookback=10;retestMaxBars=4;maxPrimaryStalenessBars=2
                capitalUsdt='1500';riskPerTradePct='0.5';dailyStopPct='3'
                leverage=1;feePerSidePct='0.05';slippagePerSidePct='0.02'
                breakEvenTriggerR='1';structuralAtrBuffer='0.5'
                maxCostToInitialRiskPct='50'
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
            $script:mockRequests++
            $req=$Body|ConvertFrom-Json
            if($req.includeLuna -or [int]$req.steps -ne 3600 -or
               $req.symbol -ne 'SOLUSDT' -or $req.intervals[0] -ne '5m' -or
               $req.intervals[1] -ne '15m' -or $req.pullbackEnabledOverride -or
               -not $req.breakoutRetestEnabledOverride){
                throw 'Unexpected Bollinger research request'
            }
            [long]$until=[long]$req.asOf
            [long]$from=$until-3600L*300000L+1L
            $baseline='CONTROL'
            $bb='CONTROL_PLUS_BOLLINGER_SMA20_2SIGMA'
            $summary=@(
                [pscustomobject]@{
                    arm=$baseline;tradesClosed=1;winningTrades=0;losingTrades=1
                    longTrades=0;shortTrades=1;bbTrades=0
                    outsideBandCloses=0;bbConfirmedSignals=0
                    bbExpiredSignals=0;bbInvalidatedSignals=0
                    bbCooldownRejected=0;bbRiskApproved=0
                    entryFilterRejected=3;cooldownRejected=4
                    startingEquityUsdt='1500';netClosedUsdt='-5'
                    longClosedUsdt='0';shortClosedUsdt='-5'
                    markedPnlUsdt='-5';feesUsdt='0.25'
                    maxDrawdownPct='0.3';openPositionSide='FLAT'
                },
                [pscustomobject]@{
                    arm=$bb;tradesClosed=1;winningTrades=1;losingTrades=0
                    longTrades=1;shortTrades=0;bbTrades=1
                    outsideBandCloses=4;bbConfirmedSignals=2
                    bbExpiredSignals=1;bbInvalidatedSignals=1
                    bbCooldownRejected=0;bbRiskApproved=1
                    entryFilterRejected=3;cooldownRejected=4
                    startingEquityUsdt='1500';netClosedUsdt='3'
                    longClosedUsdt='3';shortClosedUsdt='0'
                    markedPnlUsdt='3';feesUsdt='0.2'
                    maxDrawdownPct='0.1';openPositionSide='FLAT'
                }
            )
            $controlTrade=[pscustomobject]@{
                arm=$baseline;side='SHORT';setup='BREAKOUT_RETEST'
                outsideBandCloseTime=0
                signalCloseTime=($from+299999L)
                enteredAtOpenTime=($from+300000L)
                exitedAtOpenTime=($from+600000L)
                confirmationPattern=$null;exitReason='STOP'
                frozenMiddleTargetUsdt=$null
                initialRiskUsdt='5';netPnlUsdt='-5';netR='-1'
                feesUsdt='0.25'
            }
            $bbTrade=[pscustomobject]@{
                arm=$bb;side='LONG';setup='BOLLINGER_MEAN_REVERSION'
                outsideBandCloseTime=($from+299999L)
                signalCloseTime=($from+599999L)
                enteredAtOpenTime=($from+600000L)
                exitedAtOpenTime=($from+900000L)
                confirmationPattern='HAMMER_OR_SHOOTING_STAR'
                exitReason='BB_MIDDLE_TARGET';frozenMiddleTargetUsdt='100'
                initialRiskUsdt='5';netPnlUsdt='3';netR='0.6';feesUsdt='0.2'
            }
            return [pscustomobject]@{
                symbol='SOLUSDT';fromOpenTime=$from;asOfCloseTime=$until
                candles=3600;bollingerPeriod=20;deviations=2
                summaries=$summary;trades=@($controlTrade,$bbTrade)
            }
        }
        default {throw "Unexpected API in offline test: $Uri"}
    }
}
try {
    & $script -StartWindow 34 -Windows 6 -AsOf 1791845999999
    if($script:mockRequests -ne 6){throw 'Expected exactly six independent comparisons'}
    foreach($p in $paths){if(-not (Test-Path $p)){throw "Missing output: $p"}}
    $a=@(Import-Csv $paths[0]);$w=@(Import-Csv $paths[1]);$t=@(Import-Csv $paths[2])
    if($a.Count -ne 2 -or $w.Count -ne 12 -or $t.Count -ne 12){
        throw "Wrong result cardinality: $($a.Count), $($w.Count), $($t.Count)"
    }
    $winner=@($a|Where-Object {$_.Arm -eq 'CONTROL_PLUS_BOLLINGER_SMA20_2SIGMA'})
    if($winner.Count -ne 1 -or [int]$winner[0].BbConfirmedSignals -ne 12 -or
       [decimal]$winner[0].DeltaMarkedVsControl -ne 48){
        throw 'Confirmation statistics or independent portfolio delta are wrong'
    }
    $confirmed=@($t|Where-Object {$_.Setup -eq 'BOLLINGER_MEAN_REVERSION'})
    if($confirmed.Count -ne 6 -or
       @($confirmed|Where-Object {
           $_.ConfirmationPattern -ne 'HAMMER_OR_SHOOTING_STAR' -or
           [long]$_.OutsideBandCloseTime -ge [long]$_.SignalCloseTime
       }).Count -ne 0){throw 'BB entry was not confirmed AFTER the outside close'}
    Write-Host 'PASS: two-stage Bollinger, six mocked windows, causal confirmations, PnL CSVs'
} finally {
    foreach($p in $paths){Remove-Item -LiteralPath $p -ErrorAction SilentlyContinue}
}
