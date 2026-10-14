<#
.SYNOPSIS
  FROZEN 300h primary 5m vs 15m comparison.
.DESCRIPTION
  Six untouched 300h windows 40..45, after all 1..39 inspected.
  CONTROL; reject LONG with 15m RANGE + 5m UP_FOLLOW_THROUGH;
  reject LONG with 15m UP + 5m NORMAL volume.
  Three independent dynamic ledgers, same RETEST-only setups,
  BLOCK_BOTH, fixed 60min wait, unchanged SHORT and exits. No orders.
.EXAMPLE
  .\scripts\validate-primary-timeframe-oos.ps1 -PrintOnly
.EXAMPLE
  .\scripts\validate-primary-timeframe-oos.ps1 -SyncHistory
#>
[CmdletBinding()]
param(
    [ValidateSet('SOLUSDT')][string]$Symbol='SOLUSDT',
    [ValidateSet('testnet')][string]$Source='testnet',
    [ValidateRange(46,100)][int]$StartWindow=46,
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
if($AsOf -le 0 -or $AsOf % 3600000L -ne 3599999L){
    throw 'AsOf must be a completed hourly UTC close'
}
$scenarios=@('PRIMARY_5M_CONTEXT_15M','PRIMARY_15M_CONTEXT_1H')
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
    Write-Output ("PRIMARY_TF_OOS_START_WINDOW={0}" -f $StartWindow)
    Write-Output ("PRIMARY_TF_OOS_END_WINDOW={0}" -f ($StartWindow+$Windows-1))
    Write-Output ("PRIMARY_TF_WINDOWS={0}" -f $Windows)
    Write-Output 'PRIMARY_TF_FROZEN=true'
    Write-Output 'PRIMARY_TF_UNTOUCHED_WINDOWS=true'
    Write-Output 'PRIMARY_TF_CANDLES_5M=3600'
    Write-Output 'PRIMARY_TF_CANDLES_15M=1200'

    Write-Output 'PRIMARY_TF_WAIT_MINUTES=60'
    Write-Output 'PRIMARY_TF_WAIT_BARS=12x5m,4x15m'
    Write-Output 'PRIMARY_TF_CONTEXTS=5m+15m,15m+1h'



    Write-Output 'PRIMARY_TF_MARKET_SYMBOLS=SOLUSDT'
    Write-Output ("PRIMARY_TF_SCENARIOS={0}" -f ($scenarios -join ','))
    Write-Output 'PRIMARY_TF_SOURCE=TESTNET'
    Write-Output 'PRIMARY_TF_NO_LUNA=true'
    Write-Output 'PRIMARY_TF_NO_ORDERS=true'
    Write-Output ("PRIMARY_TF_EXPECTED_CAPITAL={0}" -f $ExpectedCapitalUsdt)
    foreach($record in $plan){
        Write-Output ("PRIMARY_TF_WINDOW={0};FROM={1};ASOF={2}" -f $record.Window,$record.From,$record.AsOf)
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
   $market.intervals -notcontains '5m' -or $market.intervals -notcontains '15m' -or
   $market.intervals -notcontains '1h'){
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

$root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$out=Join-Path $root 'results'
$stem="$Symbol-RETEST-$AsOf-5m-vs-15m-w$StartWindow-$($StartWindow+$Windows-1)"
if($SyncHistory){
    [long]$warmup=([long]($ContextLimit+30)*3600000L)
    [long]$from=[long]$plan[-1].From-$warmup
    [long]$until=[long]$plan[0].AsOf
    if($from -lt 0){throw 'Negative sync start'}
    [long]$chunkMs=7L*24L*3600000L
    foreach($tf in @('5m','15m','1h')){
        [long]$cursor=$from;$chunk=0
        while($cursor -le $until){
            [long]$end=[math]::Min(($cursor+$chunkMs-1),$until)
            $chunk++
            Write-Host ("Sync TESTNET {0}/{1} lot {2}: {3}..{4}" -f $Symbol,$tf,$chunk,$cursor,$end)
            $url="$BaseUrl/api/v1/market-data/sync?symbol=$Symbol&interval=$tf&from=$cursor&until=$end"
            try{
                $sync=Invoke-RestMethod -Uri $url -Method Post -TimeoutSec $TimeoutSec -ErrorAction Stop
            }catch{throw ("TESTNET sync {0} lot {1}: {2}" -f $tf,$chunk,$_.Exception.Message)}
            if($null -eq $sync -or [long]$sync.from -ne $cursor -or
                [long]$sync.until -lt $end -or [int]$sync.pages -lt 1 -or
                [int]$sync.fetched -lt 1){
                throw ("No TESTNET candles for {0} lot {1}; no synthetic/production fallback" -f $tf,$chunk)
            }
            $cursor=$end+1
        }
    }
}
New-Item -Path $out -ItemType Directory -Force | Out-Null
$windowRows=@();$tradeRows=@()
foreach($period in $plan){
    [int]$w=$period.Window
    $body=@{
        symbol=$Symbol;intervals=@('5m','15m');steps=3600
        contextLimit=$ContextLimit;asOf=$period.AsOf
        includeLuna=$false;includeDecisionHistory=$false
        priceTick=$filters.priceTick;quantityStep=$filters.quantityStep
        minimumQuantity=$filters.minimumQuantity
        minimumNotionalUsdt=$filters.minimumNotionalUsdt
        breakEvenTriggerROverride='1.0';maxCostToInitialRiskPctOverride=$CostLimitPct
        pullbackEnabledOverride=$false;breakoutRetestEnabledOverride=$true
    } | ConvertTo-Json -Depth 8
    Write-Host ("Timeframe 5m vs 15m window {0}: two fully independent portfolios" -f $w)
    $params=@{Uri="$BaseUrl/api/v1/backtest/compare-5m-15m"
              Method='Post';ContentType='application/json';Body=$body
              TimeoutSec=$TimeoutSec;ErrorAction='Stop'}
    try{$r=Invoke-RestMethod @params}
    catch{
        $detail=$null
        if($_.ErrorDetails -and $_.ErrorDetails.Message){$detail=[string]$_.ErrorDetails.Message}
        if([string]::IsNullOrWhiteSpace($detail) -and $_.Exception.Response){
            try{
                $stream=$_.Exception.Response.GetResponseStream()
                if($stream){
                    $reader=New-Object System.IO.StreamReader($stream)
                    try{$detail=$reader.ReadToEnd()}finally{$reader.Dispose()}
                }
            }catch{}
        }
        if([string]::IsNullOrWhiteSpace($detail)){$detail=$_.Exception.Message}
        throw ("Timeframe comparison window {0}: {1}" -f $w,$detail)
    }
    if($r.symbol -ne $Symbol -or [long]$r.fromOpenTime -ne [long]$period.From -or
       [long]$r.asOfCloseTime -ne [long]$period.AsOf -or [int]$r.periodHours -ne 300){
        throw "Window $($w): different 300-hour trading period"
    }
    $summaries=@($r.summaries);$trades=@($r.trades)
    if($summaries.Count -ne 2){throw "Window $($w): two portfolios expected"}
    $arms=@($summaries|ForEach-Object {$_.arm}|Sort-Object -Unique)
    if($arms.Count -ne 2 -or @($arms|Where-Object {$_ -notin $scenarios}).Count -gt 0){
        throw "Window $($w): unexpected timeframe arms"
    }
    foreach($s in $summaries){
        $primary=if($s.arm -eq 'PRIMARY_5M_CONTEXT_15M'){'5m'}else{'15m'}
        $context=if($primary -eq '5m'){'15m'}else{'1h'}
        [int]$bars=if($primary -eq '5m'){3600}else{1200}
        [int]$wait=if($primary -eq '5m'){12}else{4}
        if($s.primaryInterval -ne $primary -or $s.contextInterval -ne $context -or
           [int]$s.replayedCandles -ne $bars -or [int]$s.cooldownBars -ne $wait -or
           $null -eq $s.entryFilterBlockedSignals -or $null -eq $s.cooldownBlockedSignals -or
           $null -eq $s.longNetClosedUsdt -or $null -eq $s.shortNetClosedUsdt){
            throw "Window $($w): timeframe response fields incomplete"
        }
        Near $s.startingEquityUsdt $ExpectedCapitalUsdt ([decimal]'0.0001') 'capital'
        $matching=@($trades|Where-Object {$_.arm -eq $s.arm})
        $long=@($matching|Where-Object {$_.side -eq 'LONG'})
        $short=@($matching|Where-Object {$_.side -eq 'SHORT'})
        if($matching.Count -ne [int]$s.tradesClosed -or
           $long.Count -ne [int]$s.longTrades -or $short.Count -ne [int]$s.shortTrades){
            throw "Window $($w): trades do not reconcile with portfolio"
        }
        [decimal]$l=0;[decimal]$sh=0
        foreach($t in $long){$l+=Money $t.netPnlUsdt 'long pnl'}
        foreach($t in $short){$sh+=Money $t.netPnlUsdt 'short pnl'}
        Near $l $s.longNetClosedUsdt ([decimal]'0.001') 'long'
        Near $sh $s.shortNetClosedUsdt ([decimal]'0.001') 'short'
        Near ($l+$sh) $s.netClosedUsdt ([decimal]'0.001') 'total'
        $windowRows += [pscustomobject]@{
            Window=$w;Arm=$s.arm;FromOpen=$period.From;AsOf=$period.AsOf
            PrimaryInterval=$primary;ContextInterval=$context
            Candles=$bars;CooldownBars=$wait;CooldownMinutes=60
            CapitalUsdt=$s.startingEquityUsdt
            ResearchRiskPct=$config.riskPerTradePct
            ResearchDailyStopPct=$config.dailyStopPct
            ResearchLeverage=$config.leverage
            ResearchFeePerSidePct=$config.feePerSidePct
            ResearchSlippagePerSidePct=$config.slippagePerSidePct
            EntryFilterRejections=$s.entryFilterBlockedSignals
            CooldownRejections=$s.cooldownBlockedSignals
            Trades=$s.tradesClosed;Wins=$s.winningTrades;Losses=$s.losingTrades
            LongTrades=$long.Count;ShortTrades=$short.Count
            LongClosedPnlUsdt=$s.longNetClosedUsdt
            ShortClosedPnlUsdt=$s.shortNetClosedUsdt
            NetClosedUsdt=$s.netClosedUsdt;MarkedPnlUsdt=$s.markedPnlUsdt
            FeesUsdt=$s.feesUsdt;MaxDrawdownPct=$s.maxDrawdownPct
            OpenPositionSide=$s.openPositionSide
        }
    }
    foreach($t in $trades){
        if($t.arm -notin $scenarios -or
           [long]$t.signalCloseTime -ge [long]$t.enteredAtOpenTime -or
           [long]$t.enteredAtOpenTime -lt [long]$period.From -or
           [long]$t.exitCandleOpenTime -gt [long]$period.AsOf){
            throw "Window $($w): out-of-range or lookahead trade"
        }
        $tradeRows += [pscustomobject]@{
            Window=$w;Arm=$t.arm;Side=$t.side;EntrySetup=$t.entrySetup
            SignalCloseTime=$t.signalCloseTime
            EnteredAtOpenTime=$t.enteredAtOpenTime
            ExitCandleOpenTime=$t.exitCandleOpenTime
            PrimaryTrend=$t.primaryTrend;ContextTrend=$t.contextTrend
            BreakoutState=$t.breakoutState;VolumeLevel=$t.volumeLevel
            InitialRiskUsdt=$t.initialRiskUsdt;NetPnlUsdt=$t.netPnlUsdt
            NetR=$t.netR;MfeR=$t.mfeR;MaeR=$t.maeR
            FeesUsdt=$t.feesUsdt;ExitReason=$t.exitReason
            EstimatedCostToInitialRiskPct=$t.estimatedCostToInitialRiskPct
        }
    }
}
if($windowRows.Count -ne 2*$Windows){throw 'Missing windows: no partial CSV'}
$aggregate=@()
foreach($g in @($windowRows | Group-Object Arm)){
    if($g.Count -ne $Windows){throw "Incomplete arm $($g.Name)"}
    [decimal]$net=0;[decimal]$marked=0;[decimal]$l=0;[decimal]$sh=0;[decimal]$fees=0
    [decimal]$maxDD=0;[int]$trades=0;[int]$wins=0
    foreach($w in $g.Group){
        $net+=Money $w.NetClosedUsdt 'closed'
        $marked+=Money $w.MarkedPnlUsdt 'marked'
        $l+=Money $w.LongClosedPnlUsdt 'long'
        $sh+=Money $w.ShortClosedPnlUsdt 'short'
        $fees+=Money $w.FeesUsdt 'fees'
        $dd=Money $w.MaxDrawdownPct 'drawdown'
        if($dd -gt $maxDD){$maxDD=$dd}
        $trades+=[int]$w.Trades;$wins+=[int]$w.Wins
    }
    Near ($l+$sh) $net ([decimal]'0.001') 'aggregate pnl'
    $aggregate += [pscustomobject]@{
        Arm=$g.Name;Windows=$g.Count;Trades=$trades;Wins=$wins
        WinRatePct=if($trades -gt 0){[math]::Round(100.0*$wins/$trades,2)}else{0}
        LongClosedPnlUsdt=$l;ShortClosedPnlUsdt=$sh
        SumNetClosedUsdt=$net;SumMarkedPnlUsdt=$marked
        SumFeesUsdt=$fees;MaxDrawdownPct=$maxDD
    }
}
$control=@($aggregate|Where-Object {$_.Arm -eq 'PRIMARY_5M_CONTEXT_15M'})
if($control.Count -ne 1){throw 'Missing 5m baseline'}
foreach($a in $aggregate){
    $a | Add-Member -NotePropertyName DeltaMarkedVs5mUsdt -NotePropertyValue (
        (Money $a.SumMarkedPnlUsdt 'marked')-(Money $control[0].SumMarkedPnlUsdt 'baseline'))
}
$ag=Join-Path $out "primary-timeframe-oos-aggregate-$stem.csv"
$wi=Join-Path $out "primary-timeframe-oos-windows-$stem.csv"
$tr=Join-Path $out "primary-timeframe-oos-trades-$stem.csv"
$aggregate|Export-Csv $ag -NoTypeInformation -Encoding UTF8
$windowRows|Export-Csv $wi -NoTypeInformation -Encoding UTF8
$tradeRows|Export-Csv $tr -NoTypeInformation -Encoding UTF8
$aggregate|Format-Table Arm,Trades,WinRatePct,SumMarkedPnlUsdt,DeltaMarkedVs5mUsdt -AutoSize
Write-Host "Saved: $ag"
Write-Host "Saved: $wi"
Write-Host "Saved: $tr"
