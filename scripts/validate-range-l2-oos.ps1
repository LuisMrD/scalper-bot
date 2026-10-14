<#
.SYNOPSIS
  FROZEN OOS: causal failed breakout plus second SHORT leg (L2 approximation).
.DESCRIPTION
  Six new 300h windows 34..39, disjoint from all inspected 1..33.
  CONTROL; BLOCK_ALL_RANGE; or replace RANGE SHORT RETEST with new causal
  failed-breakout / confirmed-pivot / second-bearish-leg setup.
  Preserve BLOCK_BOTH, 60-minute wait, all LONG rules and old exits.
  Never use future candles; TESTNET-only, no Luna, no exchange orders.
.EXAMPLE
  .\scripts\validate-range-l2-oos.ps1 -PrintOnly
.EXAMPLE
  .\scripts\validate-range-l2-oos.ps1 -SyncHistory
#>
[CmdletBinding()]
param(
    [ValidateSet('SOLUSDT')][string]$Symbol='SOLUSDT',
    [ValidateSet('testnet')][string]$Source='testnet',
    [ValidateRange(34,100)][int]$StartWindow=34,
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
$scenarios=@('CONTROL','BLOCK_ALL_15M_RANGE_SHORTS','REPLACE_RANGE_SHORT_RETEST_WITH_L2')
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
    Write-Output ("RANGE_L2_OOS_START_WINDOW={0}" -f $StartWindow)
    Write-Output ("RANGE_L2_OOS_END_WINDOW={0}" -f ($StartWindow+$Windows-1))
    Write-Output ("RANGE_L2_WINDOWS={0}" -f $Windows)
    Write-Output 'RANGE_L2_FROZEN=true'
    Write-Output 'RANGE_L2_UNTOUCHED_WINDOWS=true'
    Write-Output 'RANGE_L2_CANDLES_PER_WINDOW=3600'
    Write-Output 'RANGE_L2_RANGE_BARS_15M=30'
    Write-Output 'RANGE_L2_WAIT_MINUTES=60'
    Write-Output 'RANGE_L2_FAILURE_MAX_5M_BARS=9'
    Write-Output 'RANGE_L2_FIRST_ATTEMPT_MAX_5M_BARS=3'
    Write-Output 'RANGE_L2_REQUIRES_CONFIRMED_PIVOT_STOP=true'
    Write-Output 'RANGE_L2_MARKET_SYMBOLS=SOLUSDT'
    Write-Output ("RANGE_L2_SCENARIOS={0}" -f ($scenarios -join ','))
    Write-Output 'RANGE_L2_SOURCE=TESTNET'
    Write-Output 'RANGE_L2_NO_LUNA=true'
    Write-Output 'RANGE_L2_NO_ORDERS=true'
    Write-Output ("RANGE_L2_EXPECTED_CAPITAL={0}" -f $ExpectedCapitalUsdt)
    foreach($record in $plan){
        Write-Output ("RANGE_L2_WINDOW={0};FROM={1};ASOF={2}" -f $record.Window,$record.From,$record.AsOf)
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
# Fresh windows 34..39; prior result CSVs are not input to this OOS.
if($SyncHistory){
    [long]$warmup=([long]($ContextLimit+30)*15L*60000L)
    [long]$from=[long]$plan[-1].From-$warmup
    [long]$until=[long]$plan[0].AsOf
    if($from -lt 0){throw 'Inicio invalido de sincronizacao'}
    [long]$chunkMs=7L*24L*3600000L
    foreach($feed in @(
        [PSCustomObject]@{Symbol='SOLUSDT';Tf='5m'},
        [PSCustomObject]@{Symbol='SOLUSDT';Tf='15m'}
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
    Write-Host ("FAILED BREAK L2 5m / janela {0}, tres carteiras dinamicas..." -f $w)
    $requestArgs=@{
        Uri="$BaseUrl/api/v1/backtest/compare-range-l2"
        Method='Post';Body=$body;ContentType='application/json'
        TimeoutSec=$TimeoutSec;ErrorAction='Stop'
    }
    try{$response=Invoke-RestMethod @requestArgs}
    catch{
        # Windows PowerShell 5.1 often hides the response body of HTTP 422.
        # Read error JSON from HTTP stream, without touching prior API runs.
        $detail=$null
        if($_.ErrorDetails -and $_.ErrorDetails.Message){
            $detail=[string]$_.ErrorDetails.Message
        }
        if([string]::IsNullOrWhiteSpace($detail) -and $_.Exception.Response){
            try {
                $stream=$_.Exception.Response.GetResponseStream()
                if($null -ne $stream){
                    $reader=New-Object System.IO.StreamReader($stream)
                    try {$detail=$reader.ReadToEnd()} finally {$reader.Dispose()}
                }
            } catch {}
        }
        if([string]::IsNullOrWhiteSpace($detail)){$detail=$_.Exception.Message}
        try {
            $parsed=$detail | ConvertFrom-Json -ErrorAction Stop
            if($parsed.message){$detail=[string]$parsed.message}
            elseif($parsed.detail){$detail=[string]$parsed.detail}
            elseif($parsed.error -and $parsed.error -is [string]){$detail=[string]$parsed.error}
        } catch {}
        throw ("FAILED BREAK L2 na janela {0}: {1}" -f $w,$detail)
    }
    if($response.symbol -ne $Symbol -or $response.primaryInterval -ne '5m' -or
       [long]$response.fromOpenTime -ne [long]$record.From -or
       [long]$response.asOfCloseTime -ne [long]$record.AsOf -or
       [int]$response.replayedCandles -ne 3600){
        throw ("Janela {0}: periodos ou candles divergentes" -f $w)
    }
    $summaries=@($response.summaries)
    $trades=@($response.trades)
    if($summaries.Count -ne 3){throw ("Janela {0}: faltam tres cenarios" -f $w)}
    $allPresent=@($summaries | ForEach-Object {$_.policy} | Sort-Object -Unique)
    if($allPresent.Count -ne 3 -or @($allPresent | Where-Object {$_ -notin $scenarios}).Count -ne 0){
        throw ("Janela {0}: contratos da comparacao inesperados" -f $w)
    }
    $control=@($summaries | Where-Object {$_.policy -eq 'CONTROL'})
    if($control.Count -ne 1){throw ("Janela {0}: falta controle" -f $w)}
    if([int]$control[0].rangeRetestBlockedSignals -ne 0 -or
       [int]$control[0].l2Signals -ne 0){
        throw ("Janela {0}: controle alterado por L2" -f $w)
    }
    $seen=@{}
    foreach($s in $summaries){
        if($s.policy -notin $scenarios -or $seen.ContainsKey([string]$s.policy)){
            throw ("Janela {0}: cenario duplicado ou inesperado" -f $w)
        }
        $seen[[string]$s.policy]=$true
        Near $s.startingEquityUsdt $ExpectedCapitalUsdt ([decimal]'0.0001') 'capital inicial'
        if($null -eq $s.entryFilterBlockedSignals -or
           $null -eq $s.cooldownBlockedSignals -or
           $null -eq $s.rangeRetestBlockedSignals -or
           $null -eq $s.l2Signals -or
           $null -eq $s.l2ClosedTrades -or
           $null -eq $s.l2BlockedByCooldown){
            throw ("Janela {0}, {1}: campos de contagem ausentes no endpoint" -f $w,$s.policy)
        }
        $matching=@($trades | Where-Object {$_.policy -eq $s.policy})
        if($matching.Count -ne [int]$s.tradesClosed){
            throw ("Janela {0}, {1}: trades incompletos" -f $w,$s.policy)
        }
        [decimal]$closedSum=0
        foreach($t in $matching){$closedSum+=Money $t.netPnlUsdt 'trade netPnl'}
        Near $closedSum $s.netClosedUsdt ([decimal]'0.001') 'soma trades'
        $longTrades=@($matching | Where-Object {$_.side -eq 'LONG'})
        $shortTrades=@($matching | Where-Object {$_.side -eq 'SHORT'})
        $l2Trades=@($matching | Where-Object {$_.entrySetup -eq 'RANGE_FALSE_BREAK_L2'})
        if($l2Trades.Count -ne [int]$s.l2ClosedTrades -or
           $l2Trades.Count -gt [int]$s.l2Signals -or
           ($s.policy -ne 'REPLACE_RANGE_SHORT_RETEST_WITH_L2' -and
            ($l2Trades.Count -gt 0 -or [int]$s.l2Signals -gt 0)) -or
           @($l2Trades | Where-Object {$_.side -ne 'SHORT'}).Count -gt 0){
            throw ("Janela {0}: setup L2 indevido ou contagem de L2 divergente" -f $w)
        }
        if($longTrades.Count -ne [int]$s.longTrades -or
           $shortTrades.Count -ne [int]$s.shortTrades){
            throw ("Janela {0}: contagem LONG/SHORT invalida" -f $w)
        }
        # No implicit empty PowerShell properties: the API record schema is
        # longNetClosedUsdt / shortNetClosedUsdt. A misspelled property became
        # $null and failed only at the final aggregate, after 6 full replays.
        if($null -eq $s.longNetClosedUsdt -or
           [string]::IsNullOrWhiteSpace([string]$s.longNetClosedUsdt) -or
           $null -eq $s.shortNetClosedUsdt -or
           [string]::IsNullOrWhiteSpace([string]$s.shortNetClosedUsdt)){
            throw ("Janela {0}, cenario {1}: PnL LONG/SHORT ausente no DTO da API" -f $w,$s.policy)
        }
        [decimal]$longPnl=0;[decimal]$shortPnl=0
        foreach($t in $longTrades){$longPnl+=Money $t.netPnlUsdt 'long pnl'}
        foreach($t in $shortTrades){$shortPnl+=Money $t.netPnlUsdt 'short pnl'}
        Near $longPnl $s.longNetClosedUsdt ([decimal]'0.001') 'PnL LONG'
        Near $shortPnl $s.shortNetClosedUsdt ([decimal]'0.001') 'PnL SHORT'
        Near ($longPnl+$shortPnl) $closedSum ([decimal]'0.001') 'PnL dos dois lados'
        $windowsOut += [PSCustomObject]@{
            Window=$w;Phase='OOS_FROZEN'
            FromOpen=$record.From;AsOf=$record.AsOf;Policy=$s.policy
            RangeBars15m=30;L2MaxAge5mBars=9;FirstAttemptMax5mBars=3
            CooldownMinutes=60
            ResearchCapitalUsdt=$config.capitalUsdt
            ResearchRiskPct=$config.riskPerTradePct
            ResearchDailyStopPct=$config.dailyStopPct
            ResearchLeverage=$config.leverage
            ResearchFeePerSidePct=$config.feePerSidePct
            ResearchSlippagePerSidePct=$config.slippagePerSidePct
            ResearchHigherTimeframeAlignment=$config.requireHigherTimeframeAlignment
            ResearchDominantTaker=$config.requireDominantTakerVolume
            RejectedByEntryFilter=$s.entryFilterBlockedSignals
            RejectedByReentryWait=$s.cooldownBlockedSignals
            RejectedByRangeShort=$s.rangeRetestBlockedSignals
            L2Signals=$s.l2Signals;L2ClosedTrades=$s.l2ClosedTrades
            L2BlockedByCooldown=$s.l2BlockedByCooldown
            TradesClosed=$s.tradesClosed;Wins=$s.winningTrades;Losses=$s.losingTrades
            LongTrades=$s.longTrades
            ShortTrades=$s.shortTrades;ShortWins=$s.shortWinners
            LongClosedPnlUsdt=$s.longNetClosedUsdt
            ShortClosedPnlUsdt=$s.shortNetClosedUsdt
            StartingEquityUsdt=$s.startingEquityUsdt
            NetClosedUsdt=$s.netClosedUsdt
            MarkedPnlUsdt=$s.markedPnlUsdt;MaxDrawdownPct=$s.maxDrawdownPct
            FeesUsdt=$s.feesUsdt;OpenPositionSide=$s.openPositionSide
            DeltaClosedUsdt=((Money $s.netClosedUsdt 'net')-
                             (Money $control[0].netClosedUsdt 'controlNet'))
            DeltaMarkedUsdt=((Money $s.markedPnlUsdt 'marked')-
                             (Money $control[0].markedPnlUsdt 'controlMarked'))
        }
    }
    if($seen.Count -ne 3){throw ("Janela {0}: faltam os 3 cenarios congelados" -f $w)}
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
            EntrySetup=$t.entrySetup
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
if($DiagnoseWindow -ne 0){
    if($windowsOut.Count -ne 3){throw 'Diagnostico incompleto: tres portfolios esperados'}
    Write-Host ("DIAGNOSTICO concluido para janela {0}; nenhum CSV foi gerado." -f $DiagnoseWindow)
    return
}
if($windowsOut.Count -ne (3*$Windows)){throw 'OOS incompleto: nao exportar resultados parciais'}
# Validate exported window schema before attempting grouping or writing CSVs.
foreach($row in $windowsOut){
    if($null -eq $row.LongClosedPnlUsdt -or
       $null -eq $row.ShortClosedPnlUsdt){
        throw ("Janela {0}, cenario {1}: PnL por lado nao foi exportado" -f $row.Window,$row.Policy)
    }
    Near ((Money $row.LongClosedPnlUsdt 'PnL LONG')+
          (Money $row.ShortClosedPnlUsdt 'PnL SHORT')) $row.NetClosedUsdt ([decimal]'0.001') 'PnL total por lado'
}

$aggregate=@()
foreach($g in @($windowsOut | Group-Object Policy)){
    if($g.Count -ne $Windows){throw ("Politica incompleta: {0}" -f $g.Name)}
    [decimal]$closed=0;[decimal]$marked=0;[decimal]$deltaClosed=0;[decimal]$deltaMarked=0
    $trades=0;$wins=0;$longTrades=0;$shortTrades=0
    [decimal]$longPnl=0
    [decimal]$shortPnl=0
    $filterRejected=0;$waitRejected=0;$rangeRejected=0
    $l2Signals=0;$l2Trades=0;$l2Wait=0
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
        $rangeRejected+=[int]$r.RejectedByRangeShort
        $l2Signals+=[int]$r.L2Signals
        $l2Trades+=[int]$r.L2ClosedTrades
        $l2Wait+=[int]$r.L2BlockedByCooldown
        $longTrades+=[int]$r.LongTrades;$shortTrades+=[int]$r.ShortTrades
        $longPnl+=Money $r.LongClosedPnlUsdt 'long PnL'
        $shortPnl+=Money $r.ShortClosedPnlUsdt 'short PnL'
    }
    $aggregate += [PSCustomObject]@{
        Policy=$g.Name;Windows=$Windows;Trades=$trades;Wins=$wins
        WinRatePct=if($trades -gt 0){[math]::Round(100.0*$wins/$trades,2)}else{0}
        EntryFilterRejections=$filterRejected
        ReentryWaitRejections=$waitRejected
        RangeShortRejections=$rangeRejected
        L2Signals=$l2Signals;L2ClosedTrades=$l2Trades
        L2BlockedByCooldown=$l2Wait
        LongTrades=$longTrades;ShortTrades=$shortTrades
        LongClosedPnlUsdt=$longPnl;ShortClosedPnlUsdt=$shortPnl
        ImprovedWindows=$improved;PositiveNetWindows=$positiveNet
        SumNetClosedUsdt=$closed;SumMarkedPnlUsdt=$marked
        DeltaClosedUsdt=$deltaClosed;DeltaMarkedUsdt=$deltaMarked
    }
}
$aggregateFile=Join-Path $out "range-l2-oos-aggregate-$stem.csv"
$windowsFile=Join-Path $out "range-l2-oos-windows-$stem.csv"
$tradesFile=Join-Path $out "range-l2-oos-trades-$stem.csv"
$aggregate | Export-Csv -Path $aggregateFile -NoTypeInformation -Encoding UTF8
$windowsOut | Export-Csv -Path $windowsFile -NoTypeInformation -Encoding UTF8
$tradesOut | Export-Csv -Path $tradesFile -NoTypeInformation -Encoding UTF8
Write-Host 'BROOKS INSPIRED FAILED BREAK + L2: tres cenarios congelados nas janelas ineditas:'
$aggregate | Sort-Object Policy |
    Format-Table Policy,LongTrades,ShortTrades,L2ClosedTrades,ImprovedWindows,PositiveNetWindows,SumMarkedPnlUsdt,DeltaMarkedUsdt -AutoSize
Write-Host "Saved: $aggregateFile"
Write-Host "Saved: $windowsFile"
Write-Host "Saved: $tradesFile"
