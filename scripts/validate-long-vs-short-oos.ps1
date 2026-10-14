<#
.SYNOPSIS
  FROZEN OOS: CONTROL versus SHORT_ONLY versus macro-selected LONG trades.
.DESCRIPTION
  Six new windows 22..27, disjoint from previously inspected 1..21.
  Four fully dynamic portfolios: CONTROL, SHORT_ONLY,
  BTC/ETH 6h UP + 3h DIP, BTC/ETH 9h UP + 3h DIP.
  Frozen initial equity and costs, TESTNET only. No Luna or orders.
.EXAMPLE
  .\scripts\validate-long-vs-short-oos.ps1 -PrintOnly
.EXAMPLE
  .\scripts\validate-long-vs-short-oos.ps1 -SyncHistory
#>
[CmdletBinding()]
param(
    [ValidateSet('SOLUSDT','BTCUSDT','ETHUSDT')][string]$Symbol='SOLUSDT',
    [ValidateSet('testnet')][string]$Source='testnet',
    [ValidateRange(22,100)][int]$StartWindow=22,
    [ValidateSet(3,6,9)][int]$Windows=6,
    [long]$AsOf=1791845999999,
    [ValidateRange(21,500)][int]$ContextLimit=200,
    [ValidateRange(1,100)][int]$CostLimitPct=50,
    [ValidateRange(1,1000000000)][decimal]$ExpectedCapitalUsdt=1500,
    [ValidatePattern('^https?://(127\.0\.0\.1|localhost)(:\d+)?$')]
    [string]$BaseUrl='http://127.0.0.1:8080',
    [ValidateRange(30,1800)][int]$TimeoutSec=900,
    [switch]$SyncHistory,
    [switch]$PrintOnly
)
$ErrorActionPreference='Stop'
$BaseUrl=$BaseUrl.TrimEnd('/')
[long]$windowMs=300L*3600000L
if($AsOf -le 0 -or $AsOf % 900000L -ne 899999L){
    throw 'AsOf deve corresponder ao fechamento de candle 15m UTC'
}
$scenarios=@('CONTROL','SHORT_ONLY','BOTH_UP_6H_DIP_3H','BOTH_UP_9H_DIP_3H')
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
    Write-Output ("LONG_SHORT_OOS_START_WINDOW={0}" -f $StartWindow)
    Write-Output ("LONG_SHORT_OOS_END_WINDOW={0}" -f ($StartWindow+$Windows-1))
    Write-Output ("LONG_SHORT_WINDOWS={0}" -f $Windows)
    Write-Output 'LONG_SHORT_FROZEN=true'
    Write-Output 'LONG_SHORT_UNTOUCHED_WINDOWS=true'
    Write-Output 'LONG_SHORT_CANDLES_PER_WINDOW=3600'
    Write-Output 'LONG_SHORT_HOURS=3,6,9'
    Write-Output 'LONG_SHORT_WAIT_MINUTES=60'
    Write-Output 'LONG_SHORT_MARKET_SYMBOLS=SOLUSDT,BTCUSDT,ETHUSDT'
    Write-Output ("LONG_SHORT_SCENARIOS={0}" -f ($scenarios -join ','))
    Write-Output 'LONG_SHORT_SOURCE=TESTNET'
    Write-Output 'LONG_SHORT_NO_LUNA=true'
    Write-Output 'LONG_SHORT_NO_ORDERS=true'
    Write-Output ("LONG_SHORT_EXPECTED_CAPITAL={0}" -f $ExpectedCapitalUsdt)
    foreach($record in $plan){
        Write-Output ("LONG_SHORT_WINDOW={0};FROM={1};ASOF={2}" -f $record.Window,$record.From,$record.AsOf)
    }
    return
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
   $config.symbols -notcontains 'BTCUSDT' -or
   $config.symbols -notcontains 'ETHUSDT' -or
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
# No dependency on exploratory windows/CSVs. Frozen OOS starts after w9.
if($SyncHistory){
    [long]$warmup=([long]($ContextLimit+30)*15L*60000L)
    [long]$from=[long]$plan[-1].From-$warmup
    [long]$until=[long]$plan[0].AsOf
    if($from -lt 0){throw 'Inicio invalido de sincronizacao'}
    [long]$chunkMs=7L*24L*3600000L
    foreach($feed in @(
        [PSCustomObject]@{Symbol='SOLUSDT';Tf='5m'},
        [PSCustomObject]@{Symbol='SOLUSDT';Tf='15m'},
        [PSCustomObject]@{Symbol='BTCUSDT';Tf='15m'},
        [PSCustomObject]@{Symbol='ETHUSDT';Tf='15m'}
    )){
        $tf=$feed.Tf
        $pair=$feed.Symbol
        [long]$cursor=$from
        $chunk=0
        while($cursor -le $until){
            [long]$end=[math]::Min(($cursor+$chunkMs-1),$until)
            $chunk++
            Write-Host ("Sync TESTNET {0}/{1} lote {2}: {3}..{4}" -f $pair,$tf,$chunk,$cursor,$end)
            $url="$BaseUrl/api/v1/market-data/sync?symbol=$pair&interval=$tf&from=$cursor&until=$end"
            try{$sync=Invoke-RestMethod -Uri $url -Method Post -TimeoutSec $TimeoutSec -ErrorAction Stop}
            catch{throw ("Sync {0}/lote {1}: {2}" -f $tf,$chunk,$_.Exception.Message)}
            if($null -eq $sync -or [long]$sync.from -ne $cursor -or
               [long]$sync.until -lt $end -or [int]$sync.pages -lt 1 -or
               [int]$sync.fetched -lt 1){
                throw ("TESTNET nao forneceu candles para {0}/lote {1}; nao criar historico artificial" -f $tf,$chunk)
            }
            $cursor=$end+1
        }
    }
}

New-Item -Path $out -ItemType Directory -Force | Out-Null
$windowsOut=@()
$tradesOut=@()
foreach($record in $plan){
    [int]$w=$record.Window
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
    Write-Host ("LONG vs SHORT 5m / janela {0}, quatro carteiras dinamicas..." -f $w)
    $requestArgs=@{
        Uri="$BaseUrl/api/v1/backtest/compare-long-vs-short"
        Method='Post';Body=$body;ContentType='application/json'
        TimeoutSec=$TimeoutSec;ErrorAction='Stop'
    }
    try{$response=Invoke-RestMethod @requestArgs}
    catch{
        $detail=if($_.ErrorDetails -and $_.ErrorDetails.Message){
            $_.ErrorDetails.Message
        }else{$_.Exception.Message}
        throw ("LONG vs SHORT na janela {0}: {1}" -f $w,$detail)
    }
    if($response.symbol -ne $Symbol -or $response.primaryInterval -ne '5m' -or
       [long]$response.fromOpenTime -ne [long]$record.From -or
       [long]$response.asOfCloseTime -ne [long]$record.AsOf -or
       [int]$response.replayedCandles -ne 3600){
        throw ("Janela {0}: periodos ou candles divergentes" -f $w)
    }
    $summaries=@($response.summaries)
    $trades=@($response.trades)
    if($summaries.Count -ne 4){throw ("Janela {0}: faltam quatro cenarios" -f $w)}
    $allPresent=@($summaries | ForEach-Object {$_.policy} | Sort-Object -Unique)
    if($allPresent.Count -ne 4 -or @($allPresent | Where-Object {$_ -notin $scenarios}).Count -ne 0){
        throw ("Janela {0}: contratos da comparacao inesperados" -f $w)
    }
    $control=@($summaries | Where-Object {$_.policy -eq 'CONTROL'})
    if($control.Count -ne 1){throw ("Janela {0}: falta controle" -f $w)}
    if([int]$control[0].macroBlockedLongSignals -ne 0 -or
       [int]$control[0].shortOnlyBlockedLongSignals -ne 0){
        throw ("Janela {0}: controle original rejeitou LONG adicional" -f $w)
    }
    $seen=@{}
    foreach($s in $summaries){
        if($s.policy -notin $scenarios -or $seen.ContainsKey([string]$s.policy)){
            throw ("Janela {0}: cenario duplicado ou inesperado" -f $w)
        }
        $seen[[string]$s.policy]=$true
        Near $s.startingEquityUsdt $ExpectedCapitalUsdt ([decimal]'0.0001') 'capital inicial'
        Near ((Money $s.markedEquityUsdt 'markedEquity')-
              (Money $s.startingEquityUsdt 'capital')) $s.markedPnlUsdt ([decimal]'0.001') 'PnL marcado'
        $matching=@($trades | Where-Object {$_.policy -eq $s.policy})
        if($matching.Count -ne [int]$s.tradesClosed){
            throw ("Janela {0}, {1}: trades incompletos" -f $w,$s.policy)
        }
        [decimal]$closedSum=0
        foreach($t in $matching){$closedSum+=Money $t.netPnlUsdt 'trade netPnl'}
        Near $closedSum $s.netClosedUsdt ([decimal]'0.001') 'soma trades'
        $longTrades=@($matching | Where-Object {$_.side -eq 'LONG'})
        $shortTrades=@($matching | Where-Object {$_.side -eq 'SHORT'})
        if($longTrades.Count -ne [int]$s.longTrades -or
           $shortTrades.Count -ne [int]$s.shortTrades -or
           ($s.policy -eq 'SHORT_ONLY' -and $longTrades.Count -ne 0)){
            throw ("Janela {0}: SHORT_ONLY ou contagem por lado invalida" -f $w)
        }
        [decimal]$longPnl=0;[decimal]$shortPnl=0
        foreach($t in $longTrades){$longPnl+=Money $t.netPnlUsdt 'long pnl'}
        foreach($t in $shortTrades){$shortPnl+=Money $t.netPnlUsdt 'short pnl'}
        Near $longPnl $s.longClosedNetPnlUsdt ([decimal]'0.001') 'PnL LONG'
        Near $shortPnl $s.shortClosedNetPnlUsdt ([decimal]'0.001') 'PnL SHORT'
        Near ($longPnl+$shortPnl) $closedSum ([decimal]'0.001') 'PnL dos dois lados'
        $windowsOut += [PSCustomObject]@{
            Window=$w;Phase='OOS_FROZEN'
            FromOpen=$record.From;AsOf=$record.AsOf;Policy=$s.policy
            MagicMacroHours='3,6,9';CooldownMinutes=60
            ResearchCapitalUsdt=$config.capitalUsdt
            ResearchRiskPct=$config.riskPerTradePct
            ResearchDailyStopPct=$config.dailyStopPct
            ResearchLeverage=$config.leverage
            ResearchFeePerSidePct=$config.feePerSidePct
            ResearchSlippagePerSidePct=$config.slippagePerSidePct
            ResearchHigherTimeframeAlignment=$config.requireHigherTimeframeAlignment
            ResearchDominantTaker=$config.requireDominantTakerVolume
            RejectedByEntryFilter=$s.filteredSignals
            RejectedByReentryWait=$s.reentryBlockedSignals
            RejectedByMacroLong=$s.macroBlockedLongSignals
            RejectedByShortOnly=$s.shortOnlyBlockedLongSignals
            TradesClosed=$s.tradesClosed;Wins=$s.winningTrades;Losses=$s.losingTrades
            LongTrades=$s.longTrades;LongWins=$s.longWinners
            ShortTrades=$s.shortTrades;ShortWins=$s.shortWinners
            LongClosedPnlUsdt=$s.longClosedNetPnlUsdt
            ShortClosedPnlUsdt=$s.shortClosedNetPnlUsdt
            RiskBlocked=$s.entriesBlockedByRisk
            StartingEquityUsdt=$s.startingEquityUsdt
            NetClosedUsdt=$s.netClosedUsdt
            RealizedEquityUsdt=$s.realizedEquityUsdt
            MarkedEquityUsdt=$s.markedEquityUsdt
            MarkedPnlUsdt=$s.markedPnlUsdt;MaxDrawdownPct=$s.maxDrawdownPct
            FeesUsdt=$s.feesUsdt;OpenPositionSide=$s.openPositionSide
            DeltaClosedUsdt=((Money $s.netClosedUsdt 'net')-
                             (Money $control[0].netClosedUsdt 'controlNet'))
            DeltaMarkedUsdt=((Money $s.markedPnlUsdt 'marked')-
                             (Money $control[0].markedPnlUsdt 'controlMarked'))
        }
    }
    if($seen.Count -ne 4){throw ("Janela {0}: faltam os 4 cenarios congelados" -f $w)}
    foreach($t in $trades){
        $f=$t.entryFeatures
        if($null -eq $f -or $null -eq $f.signalCloseTime -or
           [long]$f.signalCloseTime -ge [long]$t.enteredAtOpenTime -or
           [long]$t.enteredAtOpenTime -lt [long]$record.From -or
           [long]$t.exitCandleOpenTime -gt [long]$record.AsOf){
            throw ("Janela {0}: trade ou diagnostico fora do periodo" -f $w)
        }
        $tradesOut += [PSCustomObject]@{
            Window=$w;Policy=$t.policy;Side=$t.side
            EnteredAtOpenTime=$t.enteredAtOpenTime
            ExitCandleOpenTime=$t.exitCandleOpenTime
            EntryBreakoutState=$t.entryBreakoutState
            ExitReason=$t.exitReason;InitialRiskUsdt=$t.initialRiskUsdt
            NetPnlUsdt=$t.netPnlUsdt;FeesUsdt=$t.feesUsdt
            SignalAtCloseTime=$f.signalCloseTime
            PrimaryTrend=$f.primaryTrend;Trend15m=$f.trend15m
            HigherTimeframeConflicts=$f.higherTimeframeConflicts
            VolumeLevel=$f.volumeLevel;TakerBalance=$f.takerBalance
            RelativeVolume=$f.relativeVolume;RelativeSpread=$f.relativeSpread
            TakerBuyShare=$f.takerBuyShare;EffortResult=$f.effortResult
            BarDirection=$f.barDirection;BarTrend=$f.trendBar;BarDoji=$f.doji
            BarInside=$f.insideBar;BarOutside=$f.outsideBar
            BullishRejection=$f.bullishRejection;BearishRejection=$f.bearishRejection
            StopDistancePct=$f.stopDistancePct
            EstimatedCostToInitialRiskPct=$f.estimatedCostToInitialRiskPct
        }
    }
}
if($windowsOut.Count -ne (4*$Windows)){throw 'OOS incompleto: nao exportar resultados parciais'}
$aggregate=@()
foreach($g in @($windowsOut | Group-Object Policy)){
    if($g.Count -ne $Windows){throw ("Politica incompleta: {0}" -f $g.Name)}
    [decimal]$closed=0;[decimal]$marked=0;[decimal]$deltaClosed=0;[decimal]$deltaMarked=0
    $trades=0;$wins=0;$longTrades=0;$shortTrades=0;$longPnl=0D;$shortPnl=0D
    $filterRejected=0;$waitRejected=0;$macroRejected=0;$shortOnlyRejected=0
    $improved=0;$positiveNet=0
    foreach($r in $g.Group){
        $closed+=Money $r.NetClosedUsdt 'closed'
        $marked+=Money $r.MarkedPnlUsdt 'marked'
        [decimal]$dc=Money $r.DeltaClosedUsdt 'deltaClosed'
        [decimal]$dm=Money $r.DeltaMarkedUsdt 'deltaMarked'
        $deltaClosed+=$dc;$deltaMarked+=$dm
        if($dm -gt 0){$improved++}
        if((Money $r.MarkedPnlUsdt 'marked') -gt 0){$positiveNet++}
        $trades+=[int]$r.TradesClosed;$wins+=[int]$r.Wins
        $filterRejected+=[int]$r.RejectedByEntryFilter
        $waitRejected+=[int]$r.RejectedByReentryWait
        $macroRejected+=[int]$r.RejectedByMacroLong
        $shortOnlyRejected+=[int]$r.RejectedByShortOnly
        $longTrades+=[int]$r.LongTrades;$shortTrades+=[int]$r.ShortTrades
        $longPnl+=Money $r.LongClosedPnlUsdt 'long PnL'
        $shortPnl+=Money $r.ShortClosedPnlUsdt 'short PnL'
    }
    $aggregate += [PSCustomObject]@{
        Policy=$g.Name;Windows=$Windows;Trades=$trades;Wins=$wins
        WinRatePct=if($trades -gt 0){[math]::Round(100.0*$wins/$trades,2)}else{0}
        EntryFilterRejections=$filterRejected
        ReentryWaitRejections=$waitRejected
        MacroLongRejections=$macroRejected
        ShortOnlyLongRejections=$shortOnlyRejected
        LongTrades=$longTrades;ShortTrades=$shortTrades
        LongClosedPnlUsdt=$longPnl;ShortClosedPnlUsdt=$shortPnl
        ImprovedWindows=$improved;PositiveNetWindows=$positiveNet
        SumNetClosedUsdt=$closed;SumMarkedPnlUsdt=$marked
        DeltaClosedUsdt=$deltaClosed;DeltaMarkedUsdt=$deltaMarked
    }
}
$aggregateFile=Join-Path $out "long-vs-short-oos-aggregate-$stem.csv"
$windowsFile=Join-Path $out "long-vs-short-oos-windows-$stem.csv"
$tradesFile=Join-Path $out "long-vs-short-oos-trades-$stem.csv"
$aggregate | Export-Csv -Path $aggregateFile -NoTypeInformation -Encoding UTF8
$windowsOut | Export-Csv -Path $windowsFile -NoTypeInformation -Encoding UTF8
$tradesOut | Export-Csv -Path $tradesFile -NoTypeInformation -Encoding UTF8
Write-Host 'LONG vs SHORT: quatro cenarios congelados nas janelas ineditas:'
$aggregate | Sort-Object Policy |
    Format-Table Policy,LongTrades,ShortTrades,ImprovedWindows,PositiveNetWindows,SumMarkedPnlUsdt,DeltaMarkedUsdt -AutoSize
Write-Host "Saved: $aggregateFile"
Write-Host "Saved: $windowsFile"
Write-Host "Saved: $tradesFile"
