<#
  RETROSPECTIVE ONLY: SHORT regimes, stops and exit outcomes in historical SOL.
  Independent dynamic control and SHORT_ONLY replays, NO new entry filters.
  Previously studied windows 1..27; no claims of an untouched test.
#>
[CmdletBinding()]
param(
    [ValidateSet('SOLUSDT')][string]$Symbol='SOLUSDT',
    [ValidateRange(1,27)][int]$StartWindow=22,
    [ValidateSet(3,6,9)][int]$Windows=6,
    [long]$AsOf=1791845999999,
    [ValidatePattern('^https?://(127\.0\.0\.1|localhost)(:\d+)?$')]
    [string]$BaseUrl='http://127.0.0.1:8080',
    [ValidateRange(30,1800)][int]$TimeoutSec=900,
    [switch]$PrintOnly
)
$ErrorActionPreference='Stop'
$BaseUrl=$BaseUrl.TrimEnd('/')
[long]$periodMs=300L*3600000L
if($StartWindow+$Windows-1 -gt 27){throw 'Diagnose only inspected windows 1..27'}
if($AsOf%900000L -ne 899999L){throw 'AsOf must end on a 15m UTC close'}
$plan=@()
for($i=0;$i -lt $Windows;$i++){
    $n=$StartWindow+$i
    [long]$end=$AsOf-([long]($n-1)*$periodMs)
    $plan += [PSCustomObject]@{Window=$n;From=$end-$periodMs+1;AsOf=$end}
}
if($PrintOnly){
    Write-Output "SHORT_DIAG_WINDOWS=$Windows"
    Write-Output "SHORT_DIAG_START=$StartWindow"
    Write-Output "SHORT_DIAG_END=$($StartWindow+$Windows-1)"
    Write-Output 'SHORT_DIAG_POLICIES=CONTROL,SHORT_ONLY'
    Write-Output 'SHORT_DIAG_CANDLES=3600'
    Write-Output 'SHORT_DIAG_HOURS=300'
    Write-Output 'SHORT_DIAG_WAIT_MINUTES=60'
    Write-Output 'SHORT_DIAG_BUCKETS=3,6,9'
    Write-Output 'SHORT_DIAG_TESTNET_ONLY=true'
    Write-Output 'SHORT_DIAG_RETROSPECTIVE_ONLY=true'
    Write-Output 'SHORT_DIAG_NO_LUNA=true'
    Write-Output 'SHORT_DIAG_NO_ORDERS=true'
    foreach($p in $plan){Write-Output ("SHORT_DIAG_WINDOW={0};FROM={1};ASOF={2}" -f $p.Window,$p.From,$p.AsOf)}
    return
}
function N([object]$value,[string]$field) {
    if($null -eq $value -or [string]::IsNullOrWhiteSpace([string]$value)){throw "Missing $field"}
    return [decimal]::Parse(([string]$value).Replace(',','.'),
        [System.Globalization.CultureInfo]::InvariantCulture)
}
function Check([object]$a,[object]$b,[string]$field){
    if([math]::Abs((N $a $field)-(N $b $field)) -gt [decimal]'0.001'){throw "PnL mismatch: $field"}
}
function Band([decimal]$v,[decimal]$base,[string]$unit) {
    if($v -le (3*$base)){return "LE3_$unit"}
    if($v -le (6*$base)){return "3TO6_$unit"}
    if($v -le (9*$base)){return "6TO9_$unit"}
    return "GT9_$unit"
}
$health=Invoke-RestMethod -Uri "$BaseUrl/actuator/health" -TimeoutSec 15
$status=Invoke-RestMethod -Uri "$BaseUrl/api/v1/backtest/status" -TimeoutSec 15
$market=Invoke-RestMethod -Uri "$BaseUrl/api/v1/market-data/settings" -TimeoutSec 15
$config=Invoke-RestMethod -Uri "$BaseUrl/api/v1/backtest/research-config" -TimeoutSec 15
if($health.status -ne 'UP' -or $status.mode -ne 'SIMULATION_ONLY' -or
   $status.ordersEnabled -or $market.source -ne 'TESTNET' -or
   $market.websocketEnabled -or $market.bootstrapEnabled -or $config.ordersEnabled -or
   $config.executionMode -notin @('DISABLED','BACKTEST') -or
   $config.symbols -notcontains 'SOLUSDT' -or
   $config.requireHigherTimeframeAlignment -or
   $market.intervals -notcontains '5m' -or $market.intervals -notcontains '15m'){
    throw 'Must run with TESTNET/SIMULATION_ONLY, 5m+15m, orders/WS OFF, higherTF=false'
}
Check $config.capitalUsdt 1500 'capital'
Check $config.riskPerTradePct 0.5 'risk'
Check $config.dailyStopPct 3 'daily stop'
Check $config.leverage 1 'leverage'
Check $config.feePerSidePct 0.05 'fee'
Check $config.slippagePerSidePct 0.02 'slippage'
Check $config.breakEvenTriggerR 1 'break-even'
Check $config.structuralAtrBuffer 0.5 'ATR buffer'
Check $config.maxCostToInitialRiskPct 50 'cost/risk'
if(-not $config.requireDominantTakerVolume -or [int]$config.breakoutLookback -ne 10 -or
   [int]$config.retestMaxBars -ne 4 -or [int]$config.maxPrimaryStalenessBars -ne 2){
    throw 'JAVA decision settings diverge from frozen RETEST experiments'
}
. (Join-Path $PSScriptRoot 'lib/binance-contract-filters.ps1')
$exchange=Invoke-RestMethod -Uri 'https://demo-fapi.binance.com/fapi/v1/exchangeInfo' -TimeoutSec 60
$f=Symbol-ContractFilters -ExchangeInfo $exchange -Symbol $Symbol
$tradeRows=@()
$windowRows=@()
foreach($w in $plan){
    $body=@{
        symbol='SOLUSDT';intervals=@('5m','15m');steps=3600
        contextLimit=200;asOf=$w.AsOf;includeLuna=$false
        includeDecisionHistory=$false;priceTick=$f.priceTick
        quantityStep=$f.quantityStep;minimumQuantity=$f.minimumQuantity
        minimumNotionalUsdt=$f.minimumNotionalUsdt
        breakEvenTriggerROverride='1.0';maxCostToInitialRiskPctOverride=50
        pullbackEnabledOverride=$false;breakoutRetestEnabledOverride=$true
    } | ConvertTo-Json -Depth 5
    Write-Host ("SHORT regime diagnosis window {0}: CONTROL + SHORT_ONLY" -f $w.Window)
    $args=@{Uri="$BaseUrl/api/v1/backtest/short-regime-diagnostics";Method='Post'
        ContentType='application/json';Body=$body;TimeoutSec=$TimeoutSec;ErrorAction='Stop'}
    $r=Invoke-RestMethod @args
    if($r.symbol -ne 'SOLUSDT' -or [long]$r.fromOpenTime -ne [long]$w.From -or
       [long]$r.asOfCloseTime -ne [long]$w.AsOf -or
       [int]$r.replayedCandles -ne 3600){throw "Window $($w.Window): inconsistent candle tape"}
    if(@($r.summaries).Count -ne 2){throw "Window $($w.Window): expected two portfolios"}
    foreach($s in @($r.summaries)){
        $ts=@($r.trades | Where-Object {$_.policy -eq $s.policy})
        if($s.policy -notin @('CONTROL','SHORT_ONLY') -or
           $ts.Count -ne [int]$s.tradesClosed -or
           ($s.policy -eq 'SHORT_ONLY' -and [int]$s.longTrades -ne 0)){
            throw "Window $($w.Window): invalid portfolio or SHORT_ONLY"
        }
        [decimal]$net=0;[decimal]$l=0;[decimal]$sh=0
        $longCount=0;$shortCount=0;$shortWins=0
        foreach($t in $ts){
            [decimal]$v=N $t.netPnlUsdt 'trade net'
            $net+=$v
            if($t.side -eq 'SHORT'){
                $sh+=$v;$shortCount++
                if($v -gt 0){$shortWins++}
            }elseif($t.side -eq 'LONG'){$l+=$v;$longCount++}
            else{throw 'Invalid trade direction'}
        }
        Check $net $s.netClosedUsdt 'closed'
        Check $l $s.longClosedUsdt 'long pnl'
        Check $sh $s.shortClosedUsdt 'short pnl'
        Check ($l+$sh) $net 'sum directions'
        if($longCount -ne [int]$s.longTrades -or $shortCount -ne [int]$s.shortTrades -or
           $shortWins -ne [int]$s.shortWins -or $null -eq $s.blockedByCooldown -or
           $null -eq $s.blockedByEntryFilter -or $null -eq $s.shortOnlyBlockedLongSignals){
            throw "Window $($w.Window): missing financial/blocked signal counts"
        }
        $windowRows += [PSCustomObject]@{
            Window=$w.Window;Policy=$s.policy;FromOpen=$w.From;AsOf=$w.AsOf
            Trades=$s.tradesClosed;Wins=$s.winningTrades;Losses=$s.losingTrades
            LongTrades=$longCount;ShortTrades=$shortCount;ShortWins=$shortWins
            NetClosedUsdt=$s.netClosedUsdt;LongClosedPnlUsdt=$l
            ShortClosedPnlUsdt=$sh;MarkedPnlUsdt=$s.markedPnlUsdt
            StartingEquityUsdt=$s.startingEquityUsdt;FeesUsdt=$s.feesUsdt
            MaxDrawdownPct=$s.maxDrawdownPct;OpenPositionSide=$s.openPositionSide
            EntryFilterRejections=$s.blockedByEntryFilter
            CooldownRejections=$s.blockedByCooldown
            ShortOnlyLongRejections=$s.shortOnlyBlockedLongSignals
        }
    }
    foreach($t in @($r.trades)){
        if([long]$t.signalCloseTime -ge [long]$t.enteredAtOpenTime -or
           [long]$t.enteredAtOpenTime -lt [long]$w.From -or
           [long]$t.exitCandleOpenTime -gt [long]$w.AsOf){
            throw "Window $($w.Window): lookahead or entry/exit out of range"
        }
        $tradeRows += [PSCustomObject]@{
            Window=$w.Window;Policy=$t.policy;Side=$t.side
            EnteredAtOpenTime=$t.enteredAtOpenTime
            SignalCloseTime=$t.signalCloseTime;ExitCandleOpenTime=$t.exitCandleOpenTime
            EntryBreakoutState=$t.entryBreakoutState;PrimaryTrend=$t.primaryTrend
            Trend15m=$t.trend15m;VolumeLevel=$t.volumeLevel
            TakerBalance=$t.takerBalance;EffortResult=$t.effortResult
            BarDirection=$t.barDirection;TrendBar=$t.trendBar
            BullishRejection=$t.bullishRejection;BearishRejection=$t.bearishRejection
            HigherTimeframeConflicts=$t.higherTimeframeConflicts
            RelativeVolume=$t.relativeVolume;RelativeSpread=$t.relativeSpread
            TakerBuyShare=$t.takerBuyShare;StopDistancePct=$t.stopDistancePct
            EntryGapPct=$t.entryGapPct
            EstimatedCostToInitialRiskPct=$t.estimatedCostToInitialRiskPct
            ATRAtSignal=$t.atrAtSignal;InitialStop=$t.initialStop
            EntryPrice=$t.entryPrice;Quantity=$t.quantity
            InitialRiskUsdt=$t.initialRiskUsdt
            ExitReason=$t.exitReason;FinalStop=$t.finalStop;ExitPrice=$t.exitPrice
            DurationCandles=$t.durationCandles;DurationMillis=$t.durationMillis
            StopAdjustments=$t.stopAdjustments
            GrossPnlUsdt=$t.grossPnlUsdt;NetPnlUsdt=$t.netPnlUsdt
            FeesUsdt=$t.feesUsdt;SlippageUsdt=$t.slippageUsdt
            NetR=$t.netR;MfeR=$t.mfeR;MaeR=$t.maeR
            MaxEstimatedNetPnlUsdt=$t.maxEstimatedNetPnlUsdt
            GivebackFromBestCloseUsdt=$t.givebackFromBestCloseUsdt
            NetPnlAt30Candles=$t.netPnlAt30Candles
            NetPnlAt60Candles=$t.netPnlAt60Candles
            NetPositiveCloseCandles=$t.netPositiveCloseCandles
            FirstConfirmedReversalCloseTime=$t.firstConfirmedReversalCloseTime
            Outcome=if((N $t.netPnlUsdt 'outcome') -gt 0){'WIN'}else{'NON_WIN'}
            Cohort='UNKNOWN'
        }
    }
}
if($windowRows.Count -ne 2*$Windows){throw 'Incomplete research portfolios'}
foreach($w in $plan.Window){
    $control=@($tradeRows | Where-Object {$_.Window -eq $w -and $_.Policy -eq 'CONTROL'})
    $short=@($tradeRows | Where-Object {$_.Window -eq $w -and $_.Policy -eq 'SHORT_ONLY'})
    $cKeys=@{};$sKeys=@{}
    foreach($t in $control){
        $key="$($t.Side)|$($t.EnteredAtOpenTime)"
        if($cKeys.ContainsKey($key)){throw "Duplicate control entry: $key"}
        $cKeys[$key]=$true
    }
    foreach($t in $short){
        $key="$($t.Side)|$($t.EnteredAtOpenTime)"
        if($sKeys.ContainsKey($key)){throw "Duplicate SHORT_ONLY entry: $key"}
        $sKeys[$key]=$true
    }
    foreach($t in (@($control)+@($short))){
        $key="$($t.Side)|$($t.EnteredAtOpenTime)"
        $t.Cohort=if($cKeys.ContainsKey($key) -and $sKeys.ContainsKey($key)){
            'SHARED'
        }elseif($t.Policy -eq 'CONTROL'){'CONTROL_ONLY'}else{'SHORT_ONLY_ONLY'}
    }
}
$events=@()
foreach($t in $tradeRows){
    if($t.Side -ne 'SHORT'){continue}
    $featureRows=@(
        @{Feature='TREND_5M';Value=$t.PrimaryTrend;Timing='ENTRY'},
        @{Feature='TREND_15M';Value=$t.Trend15m;Timing='ENTRY'},
        @{Feature='BREAKOUT_STATE';Value=$t.EntryBreakoutState;Timing='ENTRY'},
        @{Feature='VOLUME_LEVEL';Value=$t.VolumeLevel;Timing='ENTRY'},
        @{Feature='TAKER_BALANCE';Value=$t.TakerBalance;Timing='ENTRY'},
        @{Feature='EFFORT_RESULT';Value=$t.EffortResult;Timing='ENTRY'},
        @{Feature='STOP_DISTANCE';Value=(Band (N $t.StopDistancePct 'stop pct') 0.1 'X0.1PCT');Timing='ENTRY'},
        @{Feature='COST_TO_RISK';Value=(Band (N $t.EstimatedCostToInitialRiskPct 'cost') 10 'X10PCT');Timing='ENTRY'},
        @{Feature='EXIT_REASON';Value=$t.ExitReason;Timing='OUTCOME'},
        @{Feature='MFE_R';Value=(Band (N $t.MfeR 'mfe') 0.1 'X0.1R');Timing='OUTCOME'},
        @{Feature='MAE_R';Value=(Band (N $t.MaeR 'mae') 0.1 'X0.1R');Timing='OUTCOME'},
        @{Feature='DURATION_CANDLES';Value=(Band ([decimal]$t.DurationCandles) 1 'BARS');Timing='OUTCOME'},
        @{Feature='REPLAY_COHORT';Value=$t.Cohort;Timing='RETROSPECTIVE'}
    )
    foreach($f in $featureRows){
        foreach($scope in @('ALL',("W{0}" -f $t.Window))){
            $events += [PSCustomObject]@{
                Scope=$scope;Window=$t.Window;Policy=$t.Policy
                Feature=$f.Feature;FeatureTiming=$f.Timing;Value=[string]$f.Value
                Outcome=$t.Outcome;NetPnlUsdt=$t.NetPnlUsdt
                NetR=$t.NetR;MfeR=$t.MfeR;MaeR=$t.MaeR
            }
        }
    }
}
$patterns=@()
foreach($g in @($events | Group-Object Scope,Policy,Feature,Value)){
    $a=$g.Group[0]
    [decimal]$total=0;[decimal]$sumR=0;[decimal]$sumMfe=0;[decimal]$sumMae=0
    $wins=0
    foreach($item in $g.Group){
        $total+=N $item.NetPnlUsdt 'net'
        $sumR+=N $item.NetR 'netR'
        $sumMfe+=N $item.MfeR 'mfeR'
        $sumMae+=N $item.MaeR 'maeR'
        if($item.Outcome -eq 'WIN'){$wins++}
    }
    $coverage=@($g.Group | Select-Object -ExpandProperty Window -Unique).Count
    $patterns += [PSCustomObject]@{
        Scope=$a.Scope;Policy=$a.Policy;Side='SHORT'
        Feature=$a.Feature;FeatureTiming=$a.FeatureTiming;Value=$a.Value
        Trades=$g.Count;Wins=$wins;Losses=$g.Count-$wins
        WinRatePct=[math]::Round(100.0*$wins/$g.Count,2)
        NetPnlUsdt=$total;MeanNetUsdt=[math]::Round($total/$g.Count,6)
        MeanNetR=[math]::Round($sumR/$g.Count,6)
        MeanMfeR=[math]::Round($sumMfe/$g.Count,6)
        MeanMaeR=[math]::Round($sumMae/$g.Count,6)
        WindowsCovered=$coverage;SmallSample=($g.Count -lt 9 -or $coverage -lt 3)
    }
}
$root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$out=Join-Path $root 'results'
New-Item -ItemType Directory -Path $out -Force | Out-Null
$stem="$Symbol-RETEST-$AsOf-5m-w$StartWindow-$($StartWindow+$Windows-1)"
$windowsPath=Join-Path $out "short-regime-windows-$stem.csv"
$tradesPath=Join-Path $out "short-regime-trades-$stem.csv"
$patternsPath=Join-Path $out "short-regime-patterns-$stem.csv"
$windowRows | Export-Csv -Path $windowsPath -NoTypeInformation -Encoding UTF8
$tradeRows | Export-Csv -Path $tradesPath -NoTypeInformation -Encoding UTF8
$patterns | Export-Csv -Path $patternsPath -NoTypeInformation -Encoding UTF8
Write-Host 'SHORT diagnostic (retrospective; MFE/MAE/exit reasons are POST-ENTRY):'
$patterns | Where-Object {$_.Scope -eq 'ALL' -and $_.Policy -eq 'CONTROL' -and
    $_.Feature -in @('TREND_15M','BREAKOUT_STATE','EXIT_REASON')} |
    Sort-Object Feature,Value | Format-Table Feature,Value,Trades,WinRatePct,NetPnlUsdt,MeanNetR,SmallSample -AutoSize
Write-Host "Saved: $windowsPath"
Write-Host "Saved: $tradesPath"
Write-Host "Saved: $patternsPath"
