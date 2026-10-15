<#
.SYNOPSIS
  RESEARCH: strict Bollinger close outside, SMA20 middle-target.
.DESCRIPTION
  First run on inspected 34..39 is an EXPLORATORY REPLAY, not out-of-sample.
  Compare frozen CONTROL with CONTROL + Bollinger-only when baseline WAITS.
  Strict 5m close outside SMA20 +/- 2 population deviations ARMS ONLY.
  Entry requires a later closed 5m hammer/shooting star, reversal force
  or break of previous high/low. Next candle open fill, within 9 bars.
  Frozen middle-SMA take profit, structural stop first, original risk.
  TESTNET, independent portfolios, zero orders and zero Luna.
.EXAMPLE
  .\scripts\compare-bollinger-5m.ps1 -PrintOnly
.EXAMPLE
  .\scripts\compare-bollinger-5m.ps1 -SyncHistory
#>
[CmdletBinding()]
param(
    [ValidateSet('SOLUSDT','DOGEUSDT','BTCUSDT')][string]$Symbol='SOLUSDT',
    [ValidateSet('testnet')][string]$Source='testnet',
    [ValidateRange(1,100)][int]$StartWindow=34,
    [ValidateSet(3,6,9)][int]$Windows=6,
    [long]$AsOf=1791845999999,
    [ValidateRange(21,500)][int]$ContextLimit=200,
    [ValidateRange(1,100)][int]$CostLimitPct=50,
    [ValidateRange(1,1000000000)][decimal]$ExpectedCapitalUsdt=1500,
    [ValidatePattern('^https?://(127\.0\.0\.1|localhost)(:\d+)?$')]
    [string]$BaseUrl='http://127.0.0.1:8080',
    [ValidateRange(30,1800)][int]$TimeoutSec=900,
    [switch]$SyncHistory,
    [switch]$PrintOnly,
    [ValidateRange(0,100)][int]$DiagnoseWindow=0
)
$ErrorActionPreference='Stop'
$BaseUrl=$BaseUrl.TrimEnd('/')
[long]$windowMs=300L*3600000L
if($AsOf -le 0 -or $AsOf % 900000L -ne 899999L){
    throw 'AsOf deve corresponder ao fechamento de candle 15m UTC'
}
$scenarios=@('CONTROL','CONTROL_PLUS_BOLLINGER_SMA20_2SIGMA')
function Money([object]$value,[string]$field){
    if($null -eq $value -or [string]::IsNullOrWhiteSpace([string]$value)){
        throw ("Campo decimal ausente: {0}" -f $field)
    }
    return [decimal]::Parse(([string]$value).Replace(',','.'),
        [System.Globalization.CultureInfo]::InvariantCulture)
}
function Near([object]$a,[object]$b,[decimal]$tolerance,[string]$field){
    [decimal]$distance=[math]::Abs((Money $a $field)-(Money $b $field))
    if($distance -gt $tolerance){
        throw ("Divergencia {0}: abs(delta)={1}, tolerancia={2}" -f $field,$distance,$tolerance)
    }
}
$plan=@()
for($idx=0;$idx -lt $Windows;$idx++){
    [int]$windowNumber=$StartWindow+$idx
    [long]$end=$AsOf-([long]($windowNumber-1)*$windowMs)
    [long]$from=$end-$windowMs+1
    if($from -lt 0){throw 'Periodo anterior a epoca Unix'}
    $plan += [PSCustomObject]@{
        Window=$windowNumber;From=$from;AsOf=$end
        FromUtc=[DateTimeOffset]::FromUnixTimeMilliseconds($from).UtcDateTime.ToString('yyyy-MM-dd HH:mm')
        UntilUtc=[DateTimeOffset]::FromUnixTimeMilliseconds($end).UtcDateTime.ToString('yyyy-MM-dd HH:mm')
    }
}
if($PrintOnly){
    Write-Output ("BOLLINGER_START_WINDOW={0}" -f $StartWindow)
    Write-Output ("BOLLINGER_END_WINDOW={0}" -f ($StartWindow+$Windows-1))
    Write-Output ("BOLLINGER_WINDOWS={0}" -f $Windows)
    Write-Output 'BOLLINGER_FROZEN=true'
    Write-Output 'BOLLINGER_ALREADY_INSPECTED=true'
    Write-Output 'BOLLINGER_CANDLES_PER_WINDOW=3600'
    Write-Output 'BOLLINGER_PERIOD=20'
    Write-Output 'BOLLINGER_DEVIATIONS=2'
    Write-Output 'BOLLINGER_SIGNAL=CLOSE_STRICTLY_OUTSIDE'
    Write-Output 'BOLLINGER_TAKE_PROFIT=FROZEN_MIDDLE_SMA20'
    Write-Output 'BOLLINGER_CONFIRMATION=HAMMER,STRENGTH,STRICT_BREAK'
    Write-Output 'BOLLINGER_MAX_WAIT_BARS=9'
    Write-Output 'BOLLINGER_NOT_AN_ENTRY=OUTSIDE_CLOSE_ALONE'
    Write-Output 'BOLLINGER_STOP_PRIORITY=STOP_FIRST'

    Write-Output 'BOLLINGER_WAIT_MINUTES=60'



    Write-Output 'BOLLINGER_MARKET_SYMBOLS=SOLUSDT,DOGEUSDT,BTCUSDT'
    Write-Output ("BOLLINGER_SCENARIOS={0}" -f ($scenarios -join ','))
    Write-Output 'BOLLINGER_SOURCE=TESTNET'
    Write-Output 'BOLLINGER_NO_LUNA=true'
    Write-Output 'BOLLINGER_NO_ORDERS=true'
    Write-Output ("BOLLINGER_EXPECTED_CAPITAL={0}" -f $ExpectedCapitalUsdt)
    foreach($record in $plan){
        Write-Output ("BOLLINGER_WINDOW={0};FROM={1};ASOF={2}" -f $record.Window,$record.From,$record.AsOf)
    }
    return
}
if($DiagnoseWindow -ne 0){
    if($DiagnoseWindow -lt $StartWindow -or $DiagnoseWindow -ge $StartWindow+$Windows){
        throw 'DiagnoseWindow must belong to the frozen six-window study'
    }
    if($SyncHistory){
        throw 'Diagnostic replay uses already synchronized TESTNET candles; omit -SyncHistory'
    }
    $plan=@($plan | Where-Object {$_.Window -eq $DiagnoseWindow})
    if($plan.Count -ne 1){throw 'Exactly one diagnostic window must be selected'}
    Write-Host ("DIAGNOSTICO somente da janela {0}; nao gera CSV parcial." -f $DiagnoseWindow)
}
$health=Invoke-RestMethod -Uri "$BaseUrl/actuator/health" -TimeoutSec 15 -ErrorAction Stop
$state=Invoke-RestMethod -Uri "$BaseUrl/api/v1/backtest/status" -TimeoutSec 15 -ErrorAction Stop
$market=Invoke-RestMethod -Uri "$BaseUrl/api/v1/market-data/settings" -TimeoutSec 15 -ErrorAction Stop
$risk=Invoke-RestMethod -Uri "$BaseUrl/api/v1/risk/status" -TimeoutSec 15 -ErrorAction Stop
$config=Invoke-RestMethod -Uri "$BaseUrl/api/v1/backtest/research-config" -TimeoutSec 15 -ErrorAction Stop
if($health.status -ne 'UP' -or $state.mode -ne 'SIMULATION_ONLY' -or
   $state.ordersEnabled -or $market.source -ne 'TESTNET' -or
   -not $risk.configured -or $risk.ordersEnabled -or
   $market.websocketEnabled -or $market.bootstrapEnabled -or
   $market.intervals -notcontains '5m' -or $market.intervals -notcontains '15m'){
    throw 'Backend deve estar em TESTNET/SIMULATION_ONLY, WS/bootstrap OFF, 5m+15m e sem ordens'
}
if($config.ordersEnabled -or $config.executionMode -notin @('DISABLED','BACKTEST') -or
   $config.symbols -notcontains $Symbol -or
   $config.requireHigherTimeframeAlignment -or
   -not $config.requireDominantTakerVolume -or
   [int]$config.breakoutLookback -ne 10 -or
   [int]$config.retestMaxBars -ne 4 -or
   [int]$config.maxPrimaryStalenessBars -ne 2){
    throw 'Config real difere: validar SOLUSDT, DISABLED/BACKTEST, higherTF=false, taker=true, lookback=10, retest=4, staleness=2'
}
Near $config.capitalUsdt $ExpectedCapitalUsdt ([decimal]'0.0001') 'capital efetivo'
Near $config.riskPerTradePct 0.5 ([decimal]'0.0001') 'risco por trade'
Near $config.dailyStopPct 3 ([decimal]'0.0001') 'stop diario'
if([int]$config.leverage -ne 1){throw 'Alavancagem efetiva deve ser 1x'}
Near $config.feePerSidePct 0.05 ([decimal]'0.0001') 'taxa por lado'
Near $config.slippagePerSidePct 0.02 ([decimal]'0.0001') 'slippage por lado'
Near $config.breakEvenTriggerR 1 ([decimal]'0.0001') 'gatilho BE'
Near $config.structuralAtrBuffer 0.5 ([decimal]'0.0001') 'buffer estrutural'
Near $config.maxCostToInitialRiskPct $CostLimitPct ([decimal]'0.0001') 'filtro de custo configurado'
Write-Host 'Config efetiva conferida: capital/risco/alavancagem/custos/Java/seguranca'

. (Join-Path $PSScriptRoot 'lib/binance-contract-filters.ps1')
$exchange=Invoke-RestMethod -Uri 'https://demo-fapi.binance.com/fapi/v1/exchangeInfo' -TimeoutSec 60
$filters=Symbol-ContractFilters -ExchangeInfo $exchange -Symbol $Symbol

$root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$out=Join-Path $root 'results'
$stem="$Symbol-RETEST-$AsOf-5m-w$StartWindow-$($StartWindow+$Windows-1)"
if($SyncHistory){
    [long]$warmup=([long]($ContextLimit+30)*15L*60000L)
    [long]$from=[long]$plan[-1].From-$warmup
    [long]$until=[long]$plan[0].AsOf
    [long]$chunkMs=7L*24L*3600000L
    foreach($tf in @('5m','15m')){
        [long]$cursor=$from
        while($cursor -le $until){
            [long]$end=[math]::Min(($cursor+$chunkMs-1),$until)
            $url="$BaseUrl/api/v1/market-data/sync?symbol=$Symbol&interval=$tf&from=$cursor&until=$end"
            Write-Host ("TESTNET sync {0} {1}..{2}" -f $tf,$cursor,$end)
            $response=Invoke-RestMethod -Uri $url -Method Post -TimeoutSec $TimeoutSec -ErrorAction Stop
            if($null -eq $response -or [long]$response.from -ne $cursor -or
               [long]$response.until -lt $end -or [int]$response.fetched -lt 1){
                throw ("Missing TESTNET candles {0} {1}" -f $tf,$cursor)
            }
            $cursor=$end+1
        }
    }
}
$windowsOut=@();$tradesOut=@()
foreach($record in $plan){
    [int]$number=$record.Window
    $body=@{
        symbol=$Symbol;intervals=@('5m','15m');steps=3600
        contextLimit=$ContextLimit;asOf=$record.AsOf
        includeLuna=$false;includeDecisionHistory=$false
        priceTick=$filters.priceTick;quantityStep=$filters.quantityStep
        minimumQuantity=$filters.minimumQuantity
        minimumNotionalUsdt=$filters.minimumNotionalUsdt
        breakEvenTriggerROverride='1.0'
        maxCostToInitialRiskPctOverride=$CostLimitPct
        pullbackEnabledOverride=$false;breakoutRetestEnabledOverride=$true
    } | ConvertTo-Json -Depth 8
    Write-Host ("BB20 strict 5m window {0}: two dynamic portfolios" -f $number)
    $args=@{Uri="$BaseUrl/api/v1/backtest/compare-bollinger-5m"
        Method='Post';ContentType='application/json';Body=$body
        TimeoutSec=$TimeoutSec;ErrorAction='Stop'}
    try{$report=Invoke-RestMethod @args}
    catch{
        $detail=$null
        if($_.ErrorDetails -and $_.ErrorDetails.Message){$detail=$_.ErrorDetails.Message}
        if([string]::IsNullOrWhiteSpace([string]$detail) -and $_.Exception.Response){
            try{
                $stream=$_.Exception.Response.GetResponseStream()
                if($stream){
                    $reader=New-Object System.IO.StreamReader($stream)
                    try{$detail=$reader.ReadToEnd()}finally{$reader.Dispose()}
                }
            }catch{}
        }
        if([string]::IsNullOrWhiteSpace([string]$detail)){$detail=$_.Exception.Message}
        throw ("Bollinger window {0}: {1}" -f $number,$detail)
    }
    if($report.symbol -ne $Symbol -or [long]$report.fromOpenTime -ne [long]$record.From -or
       [long]$report.asOfCloseTime -ne [long]$record.AsOf -or
       [int]$report.candles -ne 3600 -or [int]$report.bollingerPeriod -ne 20 -or
       (Money $report.deviations 'standard deviations') -ne 2){
        throw "Bollinger response time/period mismatch"
    }
    $arms=@($report.summaries);$trades=@($report.trades)
    if($arms.Count -ne 2 -or
       @($arms|ForEach-Object {$_.arm}|Sort-Object -Unique).Count -ne 2){
        throw "Expected two separate portfolio arms"
    }
    $control=@($arms|Where-Object {$_.arm -eq 'CONTROL'})
    if($control.Count -ne 1){throw "Missing unchanged control"}
    foreach($s in $arms){
        if($s.arm -notin $scenarios){throw "Unexpected arm: $($s.arm)"}
        Near $s.startingEquityUsdt $ExpectedCapitalUsdt ([decimal]'0.0001') 'capital'
        $matching=@($trades|Where-Object {$_.arm -eq $s.arm})
        if($matching.Count -ne [int]$s.tradesClosed){throw "Trade count mismatch"}
        $lng=@($matching|Where-Object {$_.side -eq 'LONG'})
        $srt=@($matching|Where-Object {$_.side -eq 'SHORT'})
        if($lng.Count -ne [int]$s.longTrades -or $srt.Count -ne [int]$s.shortTrades){
            throw "LONG/SHORT mismatch"
        }
        [decimal]$pnlLong=0;[decimal]$pnlShort=0;[int]$nBb=0
        foreach($t in $lng){$pnlLong+=Money $t.netPnlUsdt 'LONG net'}
        foreach($t in $srt){$pnlShort+=Money $t.netPnlUsdt 'SHORT net'}
        foreach($t in $matching){
            if($t.setup -eq 'BOLLINGER_MEAN_REVERSION'){
                $nBb++
                if($null -eq $t.frozenMiddleTargetUsdt -or
                    (Money $t.frozenMiddleTargetUsdt 'BB frozen target') -le 0 -or
                    [string]::IsNullOrWhiteSpace([string]$t.confirmationPattern) -or
                    [long]$t.outsideBandCloseTime -ge [long]$t.signalCloseTime -or
                    [long]$t.signalCloseTime-[long]$t.outsideBandCloseTime -gt 9L*300000L){
                    
                    throw "BB trade missing its signal-time SMA target"
                }
            }
        }
        Near $pnlLong $s.longClosedUsdt ([decimal]'0.001') 'LONG reconciliation'
        Near $pnlShort $s.shortClosedUsdt ([decimal]'0.001') 'SHORT reconciliation'
        Near ($pnlLong+$pnlShort) $s.netClosedUsdt ([decimal]'0.001') 'total'
        if($nBb -ne [int]$s.bbTrades -or
           ($s.arm -eq 'CONTROL' -and ($nBb -ne 0 -or [int]$s.outsideBandCloses -ne 0))){
            throw "Unexpected Bollinger attribution in control or BB portfolio"
        }
        $windowsOut += [pscustomobject]@{
            Window=$number;Arm=$s.arm;Phase='EXPLORATORY_ALREADY_INSPECTED'
            FromOpen=$record.From;AsOf=$record.AsOf
            CapitalUsdt=$s.startingEquityUsdt
            RiskPerTradePct=$config.riskPerTradePct
            DailyStopPct=$config.dailyStopPct
            Leverage=$config.leverage;FeePct=$config.feePerSidePct
            SlippagePct=$config.slippagePerSidePct
            CooldownMinutes=60;Period=20;Deviations=2
            TradesClosed=$s.tradesClosed;Wins=$s.winningTrades;Losses=$s.losingTrades
            LongTrades=$s.longTrades;ShortTrades=$s.shortTrades
            BbTrades=$s.bbTrades;OutsideCloses=$s.outsideBandCloses
            BbConfirmedSignals=$s.bbConfirmedSignals
            BbExpiredSignals=$s.bbExpiredSignals
            BbInvalidatedSignals=$s.bbInvalidatedSignals
            BbCooldownRejected=$s.bbCooldownRejected
            BbRiskApproved=$s.bbRiskApproved
            FilterRejected=$s.entryFilterRejected
            CooldownRejected=$s.cooldownRejected
            LongClosedUsdt=$s.longClosedUsdt;ShortClosedUsdt=$s.shortClosedUsdt
            NetClosedUsdt=$s.netClosedUsdt;MarkedPnlUsdt=$s.markedPnlUsdt
            FeesUsdt=$s.feesUsdt;MaxDrawdownPct=$s.maxDrawdownPct
            OpenPositionSide=$s.openPositionSide
            DeltaMarkedVsControl=((Money $s.markedPnlUsdt 'marked')-
                (Money $control[0].markedPnlUsdt 'control marked'))
        }
    }
    foreach($t in $trades){
        if($t.arm -notin $scenarios -or
           [long]$t.signalCloseTime -ge [long]$t.enteredAtOpenTime -or
           [long]$t.enteredAtOpenTime -lt [long]$record.From -or
           [long]$t.exitedAtOpenTime -gt [long]$record.AsOf){
            throw "Invalid trade timestamps or arm"
        }
        $tradesOut += [pscustomobject]@{
            Window=$number;Arm=$t.arm;Side=$t.side
            Setup=$t.setup;OutsideBandCloseTime=$t.outsideBandCloseTime
            SignalCloseTime=$t.signalCloseTime
            ConfirmationPattern=$t.confirmationPattern
            EnteredAtOpenTime=$t.enteredAtOpenTime
            ExitedAtOpenTime=$t.exitedAtOpenTime;ExitReason=$t.exitReason
            FrozenMiddleTargetUsdt=$t.frozenMiddleTargetUsdt
            InitialRiskUsdt=$t.initialRiskUsdt;NetPnlUsdt=$t.netPnlUsdt
            NetR=$t.netR;FeesUsdt=$t.feesUsdt
        }
    }
}
if($DiagnoseWindow -ne 0){
    if($windowsOut.Count -ne 2){throw 'Incomplete single-window diagnostic'}
    $windowsOut | Format-Table Window,Arm,TradesClosed,BbTrades,MarkedPnlUsdt -AutoSize
    Write-Host "Only diagnostic: no partial files"
    return
}
if($windowsOut.Count -ne 2*$Windows){throw 'Incomplete windows: no partial exports'}
$aggregate=@()
foreach($group in @($windowsOut|Group-Object Arm)){
    if($group.Count -ne $Windows){throw 'Missing scenario windows'}
    [decimal]$marked=0;[decimal]$realized=0;[decimal]$fees=0;[decimal]$delta=0
    [int]$trades=0;[int]$wins=0;[int]$bb=0;[int]$outside=0
    [int]$confirmed=0;[int]$expired=0;[int]$invalidated=0
    foreach($row in $group.Group){
        $marked+=Money $row.MarkedPnlUsdt 'marked'
        $realized+=Money $row.NetClosedUsdt 'realized'
        $fees+=Money $row.FeesUsdt 'fees'
        $delta+=Money $row.DeltaMarkedVsControl 'delta'
        $trades+=[int]$row.TradesClosed;$wins+=[int]$row.Wins
        $bb+=[int]$row.BbTrades;$outside+=[int]$row.OutsideCloses
        $confirmed+=[int]$row.BbConfirmedSignals
        $expired+=[int]$row.BbExpiredSignals
        $invalidated+=[int]$row.BbInvalidatedSignals
    }
    $aggregate += [pscustomobject]@{
        Arm=$group.Name;Windows=$group.Count
        Trades=$trades;Wins=$wins;BbTrades=$bb;OutsideCloses=$outside
        BbConfirmedSignals=$confirmed;BbExpiredSignals=$expired
        BbInvalidatedSignals=$invalidated
        SumNetClosedUsdt=$realized;SumMarkedPnlUsdt=$marked
        SumFeesUsdt=$fees;DeltaMarkedVsControl=$delta
    }
}
New-Item -Path $out -ItemType Directory -Force | Out-Null
$paths=@(
    (Join-Path $out "bollinger-5m-aggregate-$stem.csv"),
    (Join-Path $out "bollinger-5m-windows-$stem.csv"),
    (Join-Path $out "bollinger-5m-trades-$stem.csv")
)
foreach($p in $paths){if(Test-Path $p){throw "Existing research artifact: $p"}}
$aggregate|Export-Csv -Path $paths[0] -NoTypeInformation -Encoding UTF8
$windowsOut|Export-Csv -Path $paths[1] -NoTypeInformation -Encoding UTF8
if($tradesOut.Count -gt 0){
    $tradesOut|Export-Csv -Path $paths[2] -NoTypeInformation -Encoding UTF8
}else{
    # Zero trades is a valid outcome on an illiquid Testnet tape.
    # Still create a header-only file so multi-symbol reconciliation
    # never mistakes an empty ledger for a missing result.
    'Window,Arm,Side,Setup,OutsideBandCloseTime,SignalCloseTime,ConfirmationPattern,EnteredAtOpenTime,ExitedAtOpenTime,ExitReason,FrozenMiddleTargetUsdt,InitialRiskUsdt,NetPnlUsdt,NetR,FeesUsdt' |
        Set-Content -Path $paths[2] -Encoding UTF8
}
$aggregate|Format-Table Arm,Trades,BbTrades,SumMarkedPnlUsdt,DeltaMarkedVsControl -AutoSize
foreach($p in $paths){Write-Host "Saved: $p"}
