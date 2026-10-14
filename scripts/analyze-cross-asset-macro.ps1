<#
  Observational association of SOLUSDT trades with pre-entry BTC/ETH macro.
  1h / 4h / 24h rolling returns from strictly closed TESTNET 15m candles.
  Read-only: does NOT change strategy, replay decisions, or send orders.
  Requires existing 1..9 and 10..15 reentry trade/window CSVs.
#>
[CmdletBinding()]
param(
    [ValidateSet('SOLUSDT')][string]$Symbol='SOLUSDT',
    [ValidateSet('testnet')][string]$Source='testnet',
    [ValidateRange(1,15)][int]$StartWindow=1,
    [ValidateRange(1,15)][int]$Windows=15,
    [long]$AsOf=1791845999999,
    [ValidatePattern('^https?://(127\.0\.0\.1|localhost)(:\d+)?$')]
    [string]$BaseUrl='http://127.0.0.1:8080',
    [ValidateRange(30,1800)][int]$TimeoutSec=900,
    [switch]$SyncHistory,
    [switch]$PrintOnly
)
$ErrorActionPreference='Stop'
$BaseUrl=$BaseUrl.TrimEnd('/')
[long]$periodMs=300L*3600000L
if($StartWindow+$Windows-1 -gt 15){throw 'Only existing windows 1..15 are allowed'}
if($AsOf -le 0 -or $AsOf%900000L -ne 899999L){throw 'AsOf must align to a closed 15m UTC bar'}
$policies=@('NONE','WAIT_WHILE_15M_RANGE','WAIT_12_BARS')
$plan=@()
for($w=$StartWindow;$w -lt ($StartWindow+$Windows);$w++){
    [long]$end=$AsOf-([long]($w-1)*$periodMs)
    $plan += [PSCustomObject]@{Window=$w;From=$end-$periodMs+1;AsOf=$end}
}
if($PrintOnly){
    Write-Output "MACRO_START_WINDOW=$StartWindow"
    Write-Output "MACRO_END_WINDOW=$($StartWindow+$Windows-1)"
    Write-Output "MACRO_WINDOWS=$Windows"
    Write-Output 'MACRO_SYMBOLS=SOLUSDT,BTCUSDT,ETHUSDT'
    Write-Output 'MACRO_HORIZONS=1h,4h,24h'
    Write-Output 'MACRO_SOURCE=TESTNET_CLOSED_15M'
    Write-Output 'MACRO_POLICIES=NONE,WAIT_WHILE_15M_RANGE,WAIT_12_BARS'
    Write-Output 'MACRO_NO_ORDERS=true'
    Write-Output 'MACRO_NO_LUNA=true'
    Write-Output 'MACRO_OBSERVATIONAL_ONLY=true'
    foreach($p in $plan){Write-Output ("MACRO_WINDOW={0};FROM={1};ASOF={2}" -f $p.Window,$p.From,$p.AsOf)}
    return
}
function Money([object]$v,[string]$name){
    if($null -eq $v -or [string]::IsNullOrWhiteSpace([string]$v)){throw ("Missing decimal: {0}" -f $name)}
    return [decimal]::Parse(([string]$v).Replace(',','.'),[System.Globalization.CultureInfo]::InvariantCulture)
}
function Near([object]$a,[object]$b,[decimal]$limit,[string]$name){
    if([math]::Abs((Money $a $name)-(Money $b $name)) -gt $limit){throw ("Mismatch: {0}" -f $name)}
}
function Alignment([string]$side,[decimal]$btc,[decimal]$eth){
    $factor=if($side -eq 'LONG'){1}else{-1}
    [int]$a=[math]::Sign($btc)*$factor
    [int]$b=[math]::Sign($eth)*$factor
    if($a -gt 0 -and $b -gt 0){return 'CONSONANT_BOTH'}
    if($a -lt 0 -and $b -lt 0){return 'CONTRARY_BOTH'}
    if($a -eq 0 -or $b -eq 0){return 'NEUTRAL'}
    return 'MIXED'
}
function MacroDirection([decimal]$btc,[decimal]$eth){
    if($btc -gt 0 -and $eth -gt 0){return 'BTC_ETH_UP'}
    if($btc -lt 0 -and $eth -lt 0){return 'BTC_ETH_DOWN'}
    if($btc -eq 0 -or $eth -eq 0){return 'NEUTRAL'}
    return 'BTC_ETH_SPLIT'
}
$root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$out=Join-Path $root 'results'
$inputData=@{}
foreach($segment in @(
    [PSCustomObject]@{Start=1;End=9;Phase='HISTORICAL_1_9';Stem="$Symbol-RETEST-$AsOf-5m-w1-9";Prefix='reentry-wait'},
    [PSCustomObject]@{Start=10;End=15;Phase='HISTORICAL_10_15';Stem="$Symbol-RETEST-$AsOf-5m-w10-15";Prefix='reentry-oos'}
)){
    if(@($plan | Where-Object {$_.Window -ge $segment.Start -and $_.Window -le $segment.End}).Count -gt 0){
        $windowCsv=Join-Path $out "$($segment.Prefix)-windows-$($segment.Stem).csv"
        $tradeCsv=Join-Path $out "$($segment.Prefix)-trades-$($segment.Stem).csv"
        if(-not(Test-Path $windowCsv) -or -not(Test-Path $tradeCsv)){
            throw ("Required prior CSV missing: {0} or {1}" -f $windowCsv,$tradeCsv)
        }
        $inputData[$segment.Phase]=[PSCustomObject]@{
            Summaries=@(Import-Csv $windowCsv)
            Trades=@(Import-Csv $tradeCsv)
        }
    }
}
$health=Invoke-RestMethod -Uri "$BaseUrl/actuator/health" -TimeoutSec 15
$status=Invoke-RestMethod -Uri "$BaseUrl/api/v1/backtest/status" -TimeoutSec 15
$market=Invoke-RestMethod -Uri "$BaseUrl/api/v1/market-data/settings" -TimeoutSec 15
$config=Invoke-RestMethod -Uri "$BaseUrl/api/v1/backtest/research-config" -TimeoutSec 15
$risk=Invoke-RestMethod -Uri "$BaseUrl/api/v1/risk/status" -TimeoutSec 15
if($health.status -ne 'UP' -or $status.mode -ne 'SIMULATION_ONLY' -or
   $status.ordersEnabled -or $risk.ordersEnabled -or -not $risk.configured -or
   $market.source -ne 'TESTNET' -or $market.websocketEnabled -or $market.bootstrapEnabled -or
   $market.intervals -notcontains '15m' -or $config.ordersEnabled -or
   $config.executionMode -notin @('DISABLED','BACKTEST') -or
   $config.symbols -notcontains 'SOLUSDT' -or
   $config.symbols -notcontains 'BTCUSDT' -or $config.symbols -notcontains 'ETHUSDT' -or
   $config.requireHigherTimeframeAlignment){
    throw 'Set TESTNET, orders/WS/bootstrap OFF, higherTF=false, symbols SOLUSDT,BTCUSDT,ETHUSDT'
}
Near $config.capitalUsdt 1500 ([decimal]'0.0001') 'capital'
Near $config.riskPerTradePct 0.5 ([decimal]'0.0001') 'risk'
Near $config.feePerSidePct 0.05 ([decimal]'0.0001') 'fee'
Near $config.slippagePerSidePct 0.02 ([decimal]'0.0001') 'slippage'
if($SyncHistory){
    [long]$start=$plan[-1].From-(25L*3600000L)
    [long]$end=$plan[0].AsOf
    [long]$chunkMs=7L*24L*3600000L
    foreach($pair in @('BTCUSDT','ETHUSDT')){
        [long]$cursor=$start
        $chunk=0
        while($cursor -le $end){
            [long]$until=[math]::Min($end,$cursor+$chunkMs-1)
            $chunk++
            Write-Host ("Sync {0} TESTNET 15m, batch {1}" -f $pair,$chunk)
            $url="$BaseUrl/api/v1/market-data/sync?symbol=$pair&interval=15m&from=$cursor&until=$until"
            try{$resp=Invoke-RestMethod -Uri $url -Method Post -TimeoutSec $TimeoutSec -ErrorAction Stop}
            catch{throw ("Cannot sync {0} batch {1}: {2}" -f $pair,$chunk,$_.Exception.Message)}
            if($null -eq $resp -or [long]$resp.from -ne $cursor -or
                [long]$resp.until -lt $until -or [int]$resp.fetched -le 0){
                throw ("Testnet macro candles unavailable for {0} batch {1}; no substitutes" -f $pair,$chunk)
            }
            $cursor=$until+1
        }
    }
}
$observations=@()
$reconciled=@()
foreach($period in $plan){
    [int]$w=$period.Window
    $phase=if($w -le 9){'HISTORICAL_1_9'}else{'HISTORICAL_10_15'}
    $input=$inputData[$phase]
    $trades=@($input.Trades | Where-Object {[int]$_.Window -eq $w -and $_.Policy -in $policies})
    $summaries=@($input.Summaries | Where-Object {[int]$_.Window -eq $w -and $_.Policy -in $policies})
    if($trades.Count -eq 0 -or $summaries.Count -ne 3){
        throw ("Window {0}: missing data for frozen policies" -f $w)
    }
    foreach($s in $summaries){
        if([long]$s.FromOpen -ne [long]$period.From -or [long]$s.AsOf -ne [long]$period.AsOf){
            throw ("Window {0}: unexpected dates" -f $w)
        }
        $matching=@($trades | Where-Object {$_.Policy -eq $s.Policy})
        if($matching.Count -ne [int]$s.TradesClosed){throw ("Window {0}: missing closed trades" -f $w)}
        [decimal]$total=0
        foreach($t in $matching){$total+=Money $t.NetPnlUsdt 'tradePnL'}
        Near $total $s.NetClosedUsdt ([decimal]'0.001') 'reconciledPnL'
    }
    $timestamps=@($trades | ForEach-Object {[long]$_.SignalAtCloseTime} | Sort-Object -Unique)
    $body=@{
        symbol='SOLUSDT';fromOpenTime=[long]$period.From
        asOfCloseTime=[long]$period.AsOf;signalCloseTimes=$timestamps
    } | ConvertTo-Json -Depth 5
    Write-Host ("Analyzing window {0}: {1} trades, {2} unique signals" -f $w,$trades.Count,$timestamps.Count)
    $requestArgs=@{
        Uri="$BaseUrl/api/v1/backtest/cross-asset-macro"
        Method='Post';Body=$body;ContentType='application/json'
        TimeoutSec=$TimeoutSec;ErrorAction='Stop'
    }
    try{$response=Invoke-RestMethod @requestArgs}
    catch{throw ("Window {0}: missing BTC/ETH macro context: {1}; try -SyncHistory" -f $w,$_.Exception.Message)}
    if($response.symbol -ne $Symbol -or $response.marketDataSource -ne 'LOCAL_CLOSED_15M' -or
       [long]$response.fromOpenTime -ne [long]$period.From -or
       [long]$response.asOfCloseTime -ne [long]$period.AsOf -or
       @($response.observations).Count -ne $timestamps.Count){
        throw ("Window {0}: macro response mismatch" -f $w)
    }
    $byTimestamp=@{}
    foreach($snapshot in @($response.observations)){
        [long]$signal=[long]$snapshot.signalCloseTime
        if($byTimestamp.ContainsKey([string]$signal) -or
           [long]$snapshot.btc.lastClosed15mTime -gt $signal -or
           [long]$snapshot.eth.lastClosed15mTime -gt $signal -or
           [long]$snapshot.btc.lastClosed15mTime -ne [long]$snapshot.eth.lastClosed15mTime){
            throw ("Window {0}: duplicate, future or unaligned macro snapshot" -f $w)
        }
        $byTimestamp[[string]$signal]=$snapshot
    }
    foreach($t in $trades){
        [long]$signal=[long]$t.SignalAtCloseTime
        [long]$entry=[long]$t.EnteredAtOpenTime
        if($signal -ge $entry -or $entry -lt [long]$period.From -or
           [long]$t.ExitCandleOpenTime -gt [long]$period.AsOf){
            throw ("Window {0}: trade outside range or future signal" -f $w)
        }
        $m=$byTimestamp[[string]$signal]
        if($null -eq $m){throw ("Missing macro for SOL signal {0}" -f $signal)}
        [decimal]$net=Money $t.NetPnlUsdt 'net'
        [decimal]$riskAmount=Money $t.InitialRiskUsdt 'risk'
        if($riskAmount -le 0 -or $t.Side -notin @('LONG','SHORT')){throw 'Invalid initial risk or side'}
        [decimal]$btc1=Money $m.btc.return1hPct 'btc1'
        [decimal]$btc4=Money $m.btc.return4hPct 'btc4'
        [decimal]$btc24=Money $m.btc.return24hPct 'btc24'
        [decimal]$eth1=Money $m.eth.return1hPct 'eth1'
        [decimal]$eth4=Money $m.eth.return4hPct 'eth4'
        [decimal]$eth24=Money $m.eth.return24hPct 'eth24'
        $observations += [PSCustomObject]@{
            Window=$w;Phase=$phase;Policy=$t.Policy;Side=$t.Side
            EnteredAtOpenTime=$entry;SignalAtCloseTime=$signal
            ExitCandleOpenTime=$t.ExitCandleOpenTime
            SolTrend5m=$t.PrimaryTrend;SolTrend15m=$t.Trend15m
            SolBreakout=$t.EntryBreakoutState
            InitialRiskUsdt=$riskAmount;NetPnlUsdt=$net
            NetR=[math]::Round(($net/$riskAmount),6)
            Outcome=if($net -gt 0){'WIN'}elseif($net -lt 0){'LOSS'}else{'FLAT'}
            Macro15mCloseTime=$m.btc.lastClosed15mTime
            Btc1hPct=$btc1;Eth1hPct=$eth1
            Btc4hPct=$btc4;Eth4hPct=$eth4
            Btc24hPct=$btc24;Eth24hPct=$eth24
            Alignment1h=(Alignment $t.Side $btc1 $eth1)
            Alignment4h=(Alignment $t.Side $btc4 $eth4)
            Alignment24h=(Alignment $t.Side $btc24 $eth24)
            Market1h=(MacroDirection $btc1 $eth1)
            Market4h=(MacroDirection $btc4 $eth4)
            Market24h=(MacroDirection $btc24 $eth24)
        }
    }
    foreach($s in $summaries){
        $reconciled += [PSCustomObject]@{
            Window=$w;Phase=$phase;Policy=$s.Policy;Trades=$s.TradesClosed
            ClosedPnlUsdt=$s.NetClosedUsdt;MarkedPnlUsdt=$s.MarkedPnlUsdt
            MaxDrawdownPct=$s.MaxDrawdownPct;FeesUsdt=$s.FeesUsdt
            MacroCoverage='COMPLETE';DistinctSignalTimes=$timestamps.Count
        }
    }
}
if($reconciled.Count -ne 3*$Windows -or $observations.Count -lt 1){
    throw 'Incomplete results; no partial CSV will be exported'
}
$events=@()
foreach($trade in $observations){
    foreach($phase in @($trade.Phase,'ALL_1_15')){
        foreach($h in @('1h','4h','24h')){
            foreach($feature in @('SOL_CONSONANCE','BTC_ETH_DIRECTION')){
                $value=if($feature -eq 'SOL_CONSONANCE'){
                    $trade.PSObject.Properties["Alignment$h"].Value
                }else{$trade.PSObject.Properties["Market$h"].Value}
                $events += [PSCustomObject]@{
                    Phase=$phase;Window=$trade.Window;Policy=$trade.Policy
                    Side=$trade.Side;Horizon=$h;Feature=$feature;Value=$value
                    Outcome=$trade.Outcome;NetPnlUsdt=$trade.NetPnlUsdt;NetR=$trade.NetR
                }
            }
        }
    }
}
$patterns=@()
foreach($group in @($events | Group-Object Phase,Policy,Side,Horizon,Feature,Value)){
    $example=$group.Group[0]
    $count=$group.Count
    $win=@($group.Group | Where-Object {$_.Outcome -eq 'WIN'}).Count
    $loss=@($group.Group | Where-Object {$_.Outcome -eq 'LOSS'}).Count
    [decimal]$pnl=0;[decimal]$sumR=0
    foreach($t in $group.Group){
        $pnl+=Money $t.NetPnlUsdt 'pnl'
        $sumR+=Money $t.NetR 'netR'
    }
    $coverage=@($group.Group | Select-Object -ExpandProperty Window -Unique).Count
    $patterns += [PSCustomObject]@{
        Phase=$example.Phase;Policy=$example.Policy;Side=$example.Side
        Horizon=$example.Horizon;Feature=$example.Feature;Value=$example.Value
        Trades=$count;Wins=$win;Losses=$loss
        WinRatePct=[math]::Round((100.0*$win/$count),2)
        NetPnlUsdt=$pnl;ExpectancyUsdt=[math]::Round(($pnl/$count),6)
        AverageNetR=[math]::Round(($sumR/$count),6)
        WindowsRepresented=$coverage
        SmallSample=($count -lt 10 -or $coverage -lt 3)
    }
}
$stem="$Symbol-$AsOf-w$StartWindow-$($StartWindow+$Windows-1)"
New-Item -Path $out -ItemType Directory -Force | Out-Null
$tradesCsv=Join-Path $out "macro-consonance-trades-$stem.csv"
$patternsCsv=Join-Path $out "macro-consonance-patterns-$stem.csv"
$windowsCsv=Join-Path $out "macro-consonance-windows-$stem.csv"
$observations | Export-Csv -Path $tradesCsv -NoTypeInformation -Encoding UTF8
$patterns | Export-Csv -Path $patternsCsv -NoTypeInformation -Encoding UTF8
$reconciled | Export-Csv -Path $windowsCsv -NoTypeInformation -Encoding UTF8
Write-Host 'RETROSPECTIVE CONSONANCE (not a new OOS):'
$patterns | Where-Object {
    $_.Phase -eq 'ALL_1_15' -and $_.Policy -eq 'WAIT_12_BARS' -and
    $_.Horizon -eq '4h' -and $_.Feature -eq 'SOL_CONSONANCE'
} | Sort-Object Side,Value | Format-Table Side,Value,Trades,WinRatePct,NetPnlUsdt,AverageNetR,WindowsRepresented,SmallSample -AutoSize
Write-Host "Saved: $tradesCsv"
Write-Host "Saved: $patternsCsv"
Write-Host "Saved: $windowsCsv"
