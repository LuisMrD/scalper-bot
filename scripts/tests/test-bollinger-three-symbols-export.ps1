# Offline Windows PowerShell 5.1 financial export regression.
# Validates frozen Bollinger confirmed-pattern API and full six-window CSV path.
$ErrorActionPreference='Stop'
$script=Join-Path (Split-Path $PSScriptRoot -Parent) 'run-bollinger-three-symbols.ps1'
$tokens=$null;$errors=$null
[System.Management.Automation.Language.Parser]::ParseFile(
    (Resolve-Path $script).Path,[ref]$tokens,[ref]$errors) | Out-Null
if($errors.Count -gt 0){throw ("Bollinger script parse failure: {0}" -f ($errors|Out-String))}
$plan=@(& $script -PrintOnly)
foreach($expected in @(
    'THREE_SYMBOLS=SOLUSDT,DOGEUSDT,BTCUSDT',
    'THREE_SYMBOLS_INDEPENDENT_REPLAY_LEDGERS=36',
    'THREE_SYMBOLS_NO_SHARED_BALANCE=true',
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
$stem='RETEST-1791845999999-5m-w34-39'
$allSymbols=@('SOLUSDT','DOGEUSDT','BTCUSDT')
$paths=@()
foreach($symbol in $allSymbols){
    $perSymbol="$symbol-$stem"
    $paths += Join-Path $folder "bollinger-5m-aggregate-$perSymbol.csv"
    $paths += Join-Path $folder "bollinger-5m-windows-$perSymbol.csv"
    $paths += Join-Path $folder "bollinger-5m-trades-$perSymbol.csv"
}
$paths += Join-Path $folder "bollinger-three-symbols-aggregate-$stem.csv"
$paths += Join-Path $folder "bollinger-three-symbols-windows-$stem.csv"
$paths += Join-Path $folder "bollinger-three-symbols-trades-$stem.csv"
foreach($p in $paths){if(Test-Path $p){throw "Will not overwrite real CSV: $p"}}
$global:bbConfirmedMockCalls=0
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
                ordersEnabled=$false;executionMode='DISABLED';symbols=@('SOLUSDT','DOGEUSDT','BTCUSDT')
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
            },[pscustomobject]@{
                symbol='DOGEUSDT';status='TRADING';contractType='PERPETUAL'
                quoteAsset='USDT';filters=@(
                    [pscustomobject]@{filterType='PRICE_FILTER';tickSize='0.00001'},
                    [pscustomobject]@{filterType='LOT_SIZE';stepSize='1';minQty='1'},
                    [pscustomobject]@{filterType='MIN_NOTIONAL';notional='5'}
                )
            },[pscustomobject]@{
                symbol='BTCUSDT';status='TRADING';contractType='PERPETUAL'
                quoteAsset='USDT';filters=@(
                    [pscustomobject]@{filterType='PRICE_FILTER';tickSize='0.1'},
                    [pscustomobject]@{filterType='LOT_SIZE';stepSize='0.001';minQty='0.001'},
                    [pscustomobject]@{filterType='MIN_NOTIONAL';notional='100'}
                )
            })}
        }
        '*/api/v1/backtest/compare-bollinger-5m' {
            $global:bbConfirmedMockCalls++
            $req=$Body|ConvertFrom-Json
            $contractExpected=switch($req.symbol){
                'SOLUSDT'{@('0.01','0.001','5')}
                'DOGEUSDT'{@('0.00001','1','5')}
                'BTCUSDT'{@('0.1','0.001','100')}
                default{throw "Unknown instrument $($req.symbol)"}
            }
            if([decimal]$req.priceTick -ne [decimal]$contractExpected[0] -or
               [decimal]$req.quantityStep -ne [decimal]$contractExpected[1] -or
               [decimal]$req.minimumNotionalUsdt -ne [decimal]$contractExpected[2]){
                throw "Symbol-specific contract filters lost for $($req.symbol)"
            }
            if($req.includeLuna -or [int]$req.steps -ne 3600 -or
               $req.symbol -notin $allSymbols -or $req.intervals[0] -ne '5m' -or
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
                symbol=$req.symbol;fromOpenTime=$from;asOfCloseTime=$until
                candles=3600;bollingerPeriod=20;deviations=2
                summaries=$summary;trades=@($controlTrade,$bbTrade)
            }
        }
        default {throw "Unexpected API in offline test: $Uri"}
    }
}
try {
    & $script -StartWindow 34 -Windows 6 -AsOf 1791845999999
    if($global:bbConfirmedMockCalls -ne 18){
        throw "Expected eighteen requests, got $global:bbConfirmedMockCalls"
    }
    foreach($p in $paths){if(-not(Test-Path $p)){throw "Missing mock CSV: $p"}}
    $a=@(Import-Csv $paths[9]);$w=@(Import-Csv $paths[10]);$t=@(Import-Csv $paths[11])
    if($a.Count -ne 6 -or $w.Count -ne 36 -or $t.Count -ne 36){
        throw "Expected 6 aggregate arms, 36 portfolios, 36 closed trades"
    }
    foreach($symbol in $allSymbols){
        $subset=@($a | Where-Object {$_.Symbol -eq $symbol})
        $pairs=@($w | Where-Object {$_.Symbol -eq $symbol})
        if($subset.Count -ne 2 -or $pairs.Count -ne 12){
            throw "$symbol portfolio independence missing"
        }
        foreach($p in $pairs){
            if([decimal]$p.CapitalUsdt -ne 1500){
                throw "$symbol did not start independently with 1500 USDT"
            }
        }
        $bb=@($subset | Where-Object {$_.Arm -eq 'CONTROL_PLUS_BOLLINGER_SMA20_2SIGMA'})
        if($bb.Count -ne 1 -or [int]$bb[0].BbConfirmedSignals -ne 12 -or
           [decimal]$bb[0].DeltaMarkedVsControl -ne 48){
            throw "$symbol dynamic ledger reconciliation mismatch"
        }
    }
    $confirmed=@($t | Where-Object {$_.Setup -eq 'BOLLINGER_MEAN_REVERSION'})
    if($confirmed.Count -ne 18 -or
       @($confirmed | Where-Object {
           [long]$_.OutsideBandCloseTime -ge [long]$_.SignalCloseTime -or
           $_.ConfirmationPattern -ne 'HAMMER_OR_SHOOTING_STAR'
       }).Count -ne 0){
        throw 'Confirmed Bollinger trade must follow the outside-band close'
    }
    Write-Host 'PASS: SOL DOGE BTC contract filters, 36 independent 1500 portfolios, confirmed BB and 12 CSVs'
} finally {
    foreach($p in $paths){Remove-Item -LiteralPath $p -ErrorAction SilentlyContinue}
}
