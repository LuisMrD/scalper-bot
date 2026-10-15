<#
  Dynamic JAVA RETEST entry filters over 9x300h SOLUSDT 5m windows.
  No trades are subtracted after the fact: four independent portfolio replays.
  Nine windows are exploratory, NOT out of sample for these discovered filters.
#>
[CmdletBinding()]
param(
    [ValidateSet('SOLUSDT','BTCUSDT','ETHUSDT')][string]$Symbol='SOLUSDT',
    [ValidateRange(1,9)][int]$Windows=9,
    [long]$AsOf=1791845999999,
    [ValidateRange(21,500)][int]$ContextLimit=200,
    [ValidateRange(1,100)][int]$CostLimitPct=50,
    [ValidateRange(1,1000000000)][decimal]$ExpectedCapitalUsdt=1500,
    [ValidatePattern('^https?://(127\.0\.0\.1|localhost)(:\d+)?$')]
    [string]$BaseUrl='http://127.0.0.1:8080',
    [ValidateRange(30,1800)][int]$TimeoutSec=900,
    [switch]$PrintOnly
)
$ErrorActionPreference='Stop'
$BaseUrl=$BaseUrl.TrimEnd('/')
[long]$periodMs=300L*3600000L
if($AsOf -le 0 -or $AsOf % 900000L -ne 899999L){ throw 'AsOf nao e fechamento 15m UTC' }
function Money([object]$v,[string]$field){
    if($null -eq $v -or [string]::IsNullOrWhiteSpace([string]$v)){throw ("Decimal ausente: {0}" -f $field)}
    return [decimal]::Parse(([string]$v).Replace(',','.'),[System.Globalization.CultureInfo]::InvariantCulture)
}
function Near([object]$a,[object]$b,[decimal]$tol,[string]$name){
    $diff=[math]::Abs((Money $a $name)-(Money $b $name))
    if($diff -gt $tol){throw ("Valores divergentes {0}: delta={1}" -f $name,$diff)}
}
$plan=@()
for($i=1;$i -le $Windows;$i++){
    [long]$end=$AsOf-([long]($i-1)*$periodMs)
    $plan += [PSCustomObject]@{Window=$i;Start=$end-$periodMs+1;End=$end}
}
$expected=@('NONE','BLOCK_SHORT_DOWN_CANDIDATE','BLOCK_LONG_NONE','BLOCK_BOTH')
if($PrintOnly){
    Write-Output "ENTRY_FILTER_WINDOWS=$Windows"
    Write-Output 'ENTRY_FILTER_TIMEFRAME=5m'
    Write-Output 'ENTRY_FILTER_CANDLES=3600'
    Write-Output 'ENTRY_FILTER_DYNAMIC_REPLAY=true'
    Write-Output 'ENTRY_FILTER_NO_LUNA=true'
    Write-Output 'ENTRY_FILTER_NO_ORDERS=true'
    Write-Output ("ENTRY_FILTER_SCENARIOS={0}" -f ($expected -join ','))
    foreach($period in $plan){
        Write-Output ("ENTRY_FILTER_WINDOW={0};FROM={1};ASOF={2}" -f $period.Window,$period.Start,$period.End)
    }
    return
}
$health=Invoke-RestMethod -Uri "$BaseUrl/actuator/health" -TimeoutSec 15 -ErrorAction Stop
$status=Invoke-RestMethod -Uri "$BaseUrl/api/v1/backtest/status" -TimeoutSec 15 -ErrorAction Stop
$market=Invoke-RestMethod -Uri "$BaseUrl/api/v1/market-data/settings" -TimeoutSec 15 -ErrorAction Stop
$risk=Invoke-RestMethod -Uri "$BaseUrl/api/v1/risk/status" -TimeoutSec 15 -ErrorAction Stop
if($health.status -ne 'UP' -or $status.mode -ne 'SIMULATION_ONLY' -or
   $status.ordersEnabled -or $market.source -ne 'TESTNET' -or
   -not $risk.configured -or $risk.ordersEnabled){
    throw 'Backend nao confirma TESTNET e SIMULATION_ONLY sem ordens'
}
. (Join-Path $PSScriptRoot 'lib/binance-contract-filters.ps1')
$exchange=Invoke-RestMethod -Uri 'https://demo-fapi.binance.com/fapi/v1/exchangeInfo' -TimeoutSec 60
$filters=Symbol-ContractFilters -ExchangeInfo $exchange -Symbol $Symbol
$root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$out=Join-Path $root 'results'
$priorFile=Join-Path $out "entry-signal-windows-$Symbol-RETEST-$AsOf-5m-w1-$Windows.csv"
if(-not(Test-Path $priorFile)){
    throw ("CSV baseline da auditoria de entradas ausente: {0}. Execute audit-losing-entries.ps1" -f $priorFile)
}
$priorRows=@(Import-Csv $priorFile)
if($priorRows.Count -ne $Windows){throw 'Baseline incompleto'}
$windowRows=@()
$tradeRows=@()
foreach($period in $plan){
    [int]$w=$period.Window
    Write-Host ("Comparando 4 replays dinamicos no 5m, janela {0}/{1}..." -f $w,$Windows)
    $body=@{
        symbol=$Symbol; intervals=@('5m','15m'); steps=3600
        contextLimit=$ContextLimit; asOf=$period.End
        includeLuna=$false; includeDecisionHistory=$false
        priceTick=$filters.priceTick; quantityStep=$filters.quantityStep
        minimumQuantity=$filters.minimumQuantity; minimumNotionalUsdt=$filters.minimumNotionalUsdt
        breakEvenTriggerROverride='1.0'
        maxCostToInitialRiskPctOverride=$CostLimitPct
        pullbackEnabledOverride=$false; breakoutRetestEnabledOverride=$true
    } | ConvertTo-Json -Depth 8
    $post=@{
        Uri="$BaseUrl/api/v1/backtest/compare-entry-filters";Method='Post'
        Body=$body;ContentType='application/json';TimeoutSec=$TimeoutSec;ErrorAction='Stop'
    }
    try{$response=Invoke-RestMethod @post}
    catch{throw ("Replays dinamicos falharam na janela {0}: {1}" -f $w,$_.Exception.Message)}
    if($response.symbol -ne $Symbol -or $response.primaryInterval -ne '5m' -or
       [long]$response.fromOpenTime -ne [long]$period.Start -or
       [long]$response.asOfCloseTime -ne [long]$period.End -or
       [int]$response.replayedCandles -ne 3600){
        throw ("Janela {0}: periodo historico nao corresponde aos 3600 candles" -f $w)
    }
    $scenarios=@($response.summaries)
    $tradeData=@($response.trades)
    if($scenarios.Count -ne 4){throw ("Janela {0}: faltam cenarios" -f $w)}
    $base=@($scenarios | Where-Object {$_.filter -eq 'NONE'})
    $audited=@($priorRows | Where-Object {[int]$_.Window -eq $w})
    if($base.Count -ne 1 -or $audited.Count -ne 1 -or
       [long]$audited[0].FromOpen -ne [long]$period.Start -or
       [long]$audited[0].AsOf -ne [long]$period.End){
        throw ("Janela {0}: baseline ausente ou deslocado" -f $w)
    }
    Near $base[0].netClosedUsdt $audited[0].NetClosedUsdt ([decimal]'0.001') 'PnL original'
    if([int]$base[0].tradesClosed -ne [int]$audited[0].Trades -or
       [int]$base[0].filteredSignals -ne 0){throw ("Janela {0}: baseline mudou" -f $w)}
    $seen=@{}
    foreach($s in $scenarios){
        if($s.filter -notin $expected -or $seen.ContainsKey([string]$s.filter)){
            throw ("Janela {0}: filtro duplicado ou desconhecido" -f $w)
        }
        $seen[[string]$s.filter]=$true
        Near $s.startingEquityUsdt $ExpectedCapitalUsdt ([decimal]'0.0001') 'capital inicial'
        Near ((Money $s.markedEquityUsdt 'equity')-(Money $s.startingEquityUsdt 'capital')) $s.markedPnlUsdt ([decimal]'0.001') 'marked PnL'
        $matching=@($tradeData | Where-Object {$_.filter -eq $s.filter})
        if($matching.Count -ne [int]$s.tradesClosed){throw ("Janela {0}: trades incompletos" -f $w)}
        [decimal]$net=0
        foreach($t in $matching){$net+=Money $t.netPnlUsdt 'trade PnL'}
        Near $net $s.netClosedUsdt ([decimal]'0.001') 'reconciliacao trades'
        $windowRows += [PSCustomObject]@{
            Window=$w;Phase=if($w -le 3){'DISCOVERY'}else{'INSPECTED'}
            FromOpen=$period.Start;AsOf=$period.End;Filter=$s.filter
            SignalsRejected=$s.filteredSignals;TradesClosed=$s.tradesClosed
            Wins=$s.winningTrades;Losses=$s.losingTrades;RiskBlocked=$s.entriesBlockedByRisk
            StartingEquityUsdt=$s.startingEquityUsdt
            NetClosedUsdt=$s.netClosedUsdt;RealizedEquityUsdt=$s.realizedEquityUsdt
            MarkedEquityUsdt=$s.markedEquityUsdt;MarkedPnlUsdt=$s.markedPnlUsdt
            ReturnPct=$s.totalReturnPct;DrawdownPct=$s.maxDrawdownPct
            FeesUsdt=$s.feesUsdt;OpenPositionSide=$s.openPositionSide
            DeltaClosedUsdt=((Money $s.netClosedUsdt 'scenario')-(Money $base[0].netClosedUsdt 'baseline'))
            DeltaMarkedUsdt=((Money $s.markedPnlUsdt 'scenario')-(Money $base[0].markedPnlUsdt 'baseline'))
        }
    }
    if($seen.Count -ne 4){throw ("Janela {0}: matriz incompleta" -f $w)}
    foreach($t in $tradeData){
        $f=$t.entryFeatures
        if($null -eq $f -or $null -eq $f.signalCloseTime -or
           [long]$f.signalCloseTime -ge [long]$t.enteredAtOpenTime){
            throw ("Janela {0}: diagnostico de entrada ausente ou futuro" -f $w)
        }
        $tradeRows += [PSCustomObject]@{
            Window=$w;Filter=$t.filter;Side=$t.side
            EnteredAtOpenTime=$t.enteredAtOpenTime
            ExitCandleOpenTime=$t.exitCandleOpenTime
            EntryBreakoutState=$t.entryBreakoutState;ExitReason=$t.exitReason
            Quantity=$t.quantity;InitialRiskUsdt=$t.initialRiskUsdt
            NetPnlUsdt=$t.netPnlUsdt;FeesUsdt=$t.feesUsdt
            SignalAtCloseTime=$f.signalCloseTime
            EntryHourUtc=[DateTimeOffset]::FromUnixTimeMilliseconds(
                [long]$t.enteredAtOpenTime).UtcDateTime.Hour
            PrimaryTrend=$f.primaryTrend;Trend15m=$f.trend15m
            HigherTimeframeConflicts=$f.higherTimeframeConflicts
            VolumeLevel=$f.volumeLevel;TakerBalance=$f.takerBalance
            RelativeVolume=$f.relativeVolume;RelativeSpread=$f.relativeSpread
            TakerBuyShare=$f.takerBuyShare;EffortResult=$f.effortResult
            BarDirection=$f.barDirection;BarTrend=$f.trendBar;BarDoji=$f.doji
            BarInside=$f.insideBar;BarOutside=$f.outsideBar
            BullishRejection=$f.bullishRejection;BearishRejection=$f.bearishRejection
            PullbackCandidate=$f.pullbackCandidate
            ConfirmedPivotCount=$f.confirmedPivotCount;AtrAtSignal=$f.atrAtSignal
            StopDistancePct=$f.stopDistancePct;EntryGapPct=$f.entryGapPct
            EstimatedCostToInitialRiskPct=$f.estimatedCostToInitialRiskPct
            EstimatedRoundTripCostUsdt=$f.estimatedRoundTripCostUsdt
            DurationCandles=$t.durationCandles;MfeR=$t.mfeR
            MaeR=$t.maeR;NetRMultiple=$t.netRMultiple
        }
    }
}
if($windowRows.Count -ne 4*$Windows){throw 'A comparacao esta incompleta'}
$aggregate=@()
foreach($group in @($windowRows | Group-Object Filter)){
    if($group.Count -ne $Windows){throw ("Faltam janelas em {0}" -f $group.Name)}
    [decimal]$net=0;[decimal]$marked=0;[decimal]$delta=0
    $trades=0;$wins=0;$rejected=0;$positive=0
    foreach($r in $group.Group){
        $net+=Money $r.NetClosedUsdt 'net'
        $marked+=Money $r.MarkedPnlUsdt 'marked'
        [decimal]$d=Money $r.DeltaMarkedUsdt 'delta'
        $delta+=$d
        if($d -gt 0){$positive++}
        $trades+=[int]$r.TradesClosed
        $wins+=[int]$r.Wins
        $rejected+=[int]$r.SignalsRejected
    }
    $aggregate += [PSCustomObject]@{
        Filter=$group.Name;Windows=$Windows;Trades=$trades;Wins=$wins
        WinRatePct=if($trades -gt 0){[math]::Round(100.0*$wins/$trades,2)}else{0}
        SignalsRejected=$rejected;PositiveDeltaWindows=$positive
        SumNetClosedUsdt=$net;SumMarkedPnlUsdt=$marked;DeltaMarkedUsdt=$delta
    }
}
$stem="$Symbol-RETEST-$AsOf-5m-w1-$Windows"
$summaryFile=Join-Path $out "entry-filter-aggregate-$stem.csv"
$windowsFile=Join-Path $out "entry-filter-windows-$stem.csv"
$tradesFile=Join-Path $out "entry-filter-trades-$stem.csv"
$aggregate | Export-Csv -Path $summaryFile -NoTypeInformation -Encoding UTF8
$windowRows | Export-Csv -Path $windowsFile -NoTypeInformation -Encoding UTF8
$tradeRows | Export-Csv -Path $tradesFile -NoTypeInformation -Encoding UTF8
Write-Host 'Resultados exploratorios (janelas independentes):'
$aggregate | Sort-Object Filter | Format-Table Filter,Trades,WinRatePct,SignalsRejected,PositiveDeltaWindows,SumNetClosedUsdt,SumMarkedPnlUsdt,DeltaMarkedUsdt -AutoSize
Write-Host "Saved: $summaryFile"
Write-Host "Saved: $windowsFile"
Write-Host "Saved: $tradesFile"
# Reuse the newly exported point-in-time entry features to audit ALL new,
# preserved and removed closed trades without another database replay.
& (Join-Path $PSScriptRoot 'audit-replacement-entries.ps1') `
    -Symbol $Symbol -Windows $Windows -AsOf $AsOf -ResultsDir $out

