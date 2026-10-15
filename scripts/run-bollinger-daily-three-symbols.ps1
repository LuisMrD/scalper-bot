<#
.SYNOPSIS
  BTCUSDT, SOLUSDT, DOGEUSDT: six disjoint 300h wall-clock windows on 1d.
.DESCRIPTION
  Research only. Four independent arms per symbol/window:
  CONTROL, LEGACY_BOLLINGER_STRUCTURAL, FILTERED_BOLLINGER_STRUCTURAL,
  FILTERED_BOLLINGER_EXTREME.
  300h is 12.5 days, therefore only TWELVE whole UTC daily candles fit
  in each 300h window. Do not invent half-candles or count warmup as trades.
  Requires local backend started with -IncludeDaily, Testnet and orders off.
.EXAMPLE
  .\scripts\start-backtest.ps1 -IncludeDaily -Symbols BTCUSDT,SOLUSDT,DOGEUSDT -CapitalUsdt 1500 -MaxCostToInitialRiskPct 50
  .\scripts\run-bollinger-daily-three-symbols.ps1 -PrintOnly
  .\scripts\run-bollinger-daily-three-symbols.ps1 -SyncHistory
#>
[CmdletBinding()]
param(
    [ValidateSet('BTCUSDT','SOLUSDT','DOGEUSDT')]
    [string[]]$Symbols=@('BTCUSDT','SOLUSDT','DOGEUSDT'),
    [ValidateRange(1,100)][int]$StartWindow=1,
    [ValidateRange(1,100)][int]$Windows=6,
    [long]$AsOf=1791763199999,
    [ValidateRange(21,500)][int]$ContextLimit=50,
    [decimal]$CapitalPerPortfolioUsdt=1500,
    [ValidateRange(1,100)][int]$CostLimitPct=50,
    [ValidatePattern('^https?://(localhost|127\.0\.0\.1)(:\d+)?$')]
    [string]$BaseUrl='http://127.0.0.1:8080',
    [ValidateRange(30,1800)][int]$TimeoutSec=900,
    [switch]$SyncHistory,
    [switch]$PrintOnly
)
$ErrorActionPreference='Stop'
[long]$dayMs=86400000L
[long]$windowMs=300L*3600000L
$arms=@('CONTROL','LEGACY_BOLLINGER_STRUCTURAL',
    'FILTERED_BOLLINGER_STRUCTURAL','FILTERED_BOLLINGER_EXTREME')
if(@($Symbols|Select-Object -Unique).Count -ne $Symbols.Count){
    throw 'Symbols cannot be duplicated'
}
if($AsOf -le 0 -or $AsOf%$dayMs -ne $dayMs-1){
    throw 'AsOf must be a completely closed UTC daily candle'
}
if($CapitalPerPortfolioUsdt -le 0){throw 'Capital must be positive'}
$BaseUrl=$BaseUrl.TrimEnd('/')
$plan=@()
for($i=0;$i -lt $Windows;$i++){
    $n=$StartWindow+$i
    [long]$wallEnd=$AsOf-([long]($n-1)*$windowMs)
    [long]$wallStart=$wallEnd-$windowMs+1
    # The latest completed 1d candle with close <= wallEnd.
    [long]$dailyEnd=([long][math]::Floor(($wallEnd+1)/[double]$dayMs))*$dayMs-1
    [long]$firstOpen=$dailyEnd-12L*$dayMs+1
    if($wallStart -lt 0 -or $firstOpen -lt $wallStart -or
       $dailyEnd -gt $wallEnd -or $firstOpen+12L*$dayMs-1 -ne $dailyEnd){
        throw "Window $n: cannot fit 12 whole closed daily candles"
    }
    $plan+= [pscustomobject]@{
        Window=$n;WallStart=$wallStart;WallEnd=$wallEnd
        FirstOpen=$firstOpen;DailyEnd=$dailyEnd
        WallStartUtc=[DateTimeOffset]::FromUnixTimeMilliseconds($wallStart).UtcDateTime.ToString('u')
        WallEndUtc=[DateTimeOffset]::FromUnixTimeMilliseconds($wallEnd).UtcDateTime.ToString('u')
    }
}
if($PrintOnly){
    Write-Output 'RESEARCH=DAILY_300H_4_INDEPENDENT_ARMS'
    Write-Output "SYMBOLS=$($Symbols -join ',')"
    Write-Output "WINDOWS=$Windows"
    Write-Output 'HOURS_PER_WINDOW=300'
    Write-Output 'COMPLETE_DAILY_CANDLES_PER_WINDOW=12'
    Write-Output "TOTAL_INDEPENDENT_PORTFOLIOS=$($Symbols.Count*$Windows*$arms.Count)"
    Write-Output "CAPITAL_PER_PORTFOLIO_USDT=$CapitalPerPortfolioUsdt"
    Write-Output 'BOLLINGER_WARMUP_IS_EXCLUDED_FROM_TRADING_WINDOW=true'
    Write-Output 'STOP_MODES=STRUCTURAL,ONE_TICK_BEYOND_EXTREME'
    Write-Output 'MINIMUM_NET_REWARD_RISK=1.5'
    Write-Output 'NO_ORDERS=true'
    Write-Output 'NO_LUNA=true'
    foreach($w in $plan){
        Write-Output ("WINDOW={0};WALL_START={1};WALL_END={2};FIRST_OPEN={3};DAILY_CLOSE={4}" -f
            $w.Window,$w.WallStart,$w.WallEnd,$w.FirstOpen,$w.DailyEnd)
    }
    return
}
$state=Invoke-RestMethod "$BaseUrl/api/v1/backtest/status" -TimeoutSec 15
$market=Invoke-RestMethod "$BaseUrl/api/v1/market-data/settings" -TimeoutSec 15
$config=Invoke-RestMethod "$BaseUrl/api/v1/backtest/research-config" -TimeoutSec 15
if($state.mode -ne 'SIMULATION_ONLY' -or $state.ordersEnabled -or
   $market.source -ne 'TESTNET' -or $market.websocketEnabled -or
   $market.bootstrapEnabled -or $market.intervals -notcontains '1d' -or
   $config.ordersEnabled -or $config.requireHigherTimeframeAlignment){
    throw 'Require simulation-only TESTNET, local historical 1d, no websocket or orders'
}
foreach($symbol in $Symbols){
    if($config.symbols -notcontains $symbol){throw "Symbol not configured: $symbol"}
}
$culture=[System.Globalization.CultureInfo]::InvariantCulture
function dec([object]$v){return [decimal]::Parse(([string]$v).Replace(',','.'),$culture)}
if([math]::Abs((dec $config.capitalUsdt)-$CapitalPerPortfolioUsdt) -gt 0.001 -or
   [math]::Abs((dec $config.riskPerTradePct)-0.5) -gt 0.001 -or
   [math]::Abs((dec $config.dailyStopPct)-3) -gt 0.001 -or
   [int]$config.leverage -ne 1 -or
   [math]::Abs((dec $config.feePerSidePct)-0.05) -gt 0.001 -or
   [math]::Abs((dec $config.slippagePerSidePct)-0.02) -gt 0.001 -or
   [math]::Abs((dec $config.maxCostToInitialRiskPct)-$CostLimitPct) -gt 0.001){
    throw 'Actual backtest risk/capital/cost configuration differs from the experiment'
}
. (Join-Path $PSScriptRoot 'lib/binance-contract-filters.ps1')
$exchange=Invoke-RestMethod 'https://demo-fapi.binance.com/fapi/v1/exchangeInfo' -TimeoutSec 60
$rows=New-Object System.Collections.Generic.List[object]
$tradeRows=New-Object System.Collections.Generic.List[object]
foreach($symbol in $Symbols){
    $filters=Symbol-ContractFilters -ExchangeInfo $exchange -Symbol $symbol
    if($SyncHistory){
        # 50 complete previous days minimum, plus 30 to cover indicator/pivot lookbacks.
        [long]$from=$plan[-1].WallStart - ([long]($ContextLimit+30)*$dayMs)
        [long]$until=$plan[0].WallEnd
        [long]$cursor=$from
        while($cursor -le $until){
            [long]$end=[math]::Min($cursor+7L*$dayMs-1,$until)
            $url="$BaseUrl/api/v1/market-data/sync?symbol=$symbol&interval=1d&from=$cursor&until=$end"
            $sync=Invoke-RestMethod -Uri $url -Method Post -TimeoutSec $TimeoutSec
            if($null -eq $sync -or [long]$sync.until -lt $end){
                throw "Incomplete daily Testnet sync for $symbol from $cursor"
            }
            $cursor=$end+1
        }
    }
    foreach($w in $plan){
        $body=@{
            symbol=$symbol;intervals=@('1d');steps=12;contextLimit=$ContextLimit
            asOf=$w.DailyEnd;includeLuna=$false;includeDecisionHistory=$false
            priceTick=$filters.priceTick;quantityStep=$filters.quantityStep
            minimumQuantity=$filters.minimumQuantity
            minimumNotionalUsdt=$filters.minimumNotionalUsdt
            breakEvenTriggerROverride='1.0'
            maxCostToInitialRiskPctOverride=$CostLimitPct
            pullbackEnabledOverride=$false;breakoutRetestEnabledOverride=$true
        } | ConvertTo-Json -Depth 8
        Write-Host ("DAILY {0} window {1} wall {2}..{3}" -f
            $symbol,$w.Window,$w.WallStartUtc,$w.WallEndUtc)
        $report=Invoke-RestMethod -Uri "$BaseUrl/api/v1/backtest/compare-bollinger-daily" `
            -Method Post -ContentType 'application/json' -Body $body -TimeoutSec $TimeoutSec
        if($report.symbol -ne $symbol -or $report.interval -ne '1d' -or
           [long]$report.firstCandleOpenTime -ne $w.FirstOpen -or
           [long]$report.lastCandleCloseTime -ne $w.DailyEnd -or
           [int]$report.completeDailyCandles -ne 12 -or
           [int]$report.period -ne 20 -or [decimal]$report.minimumNetRewardRisk -ne 1.5){
            throw "Daily response period or strategy mismatch for $symbol w$($w.Window)"
        }
        $summaries=@($report.summaries)
        if($summaries.Count -ne 4 -or
           @($summaries|ForEach-Object {$_.arm}|Sort-Object -Unique).Count -ne 4){
            throw "Missing independent comparison arms"
        }
        foreach($arm in $arms){
            $matching=@($summaries|Where-Object {$_.arm -eq $arm})
            if($matching.Count -ne 1){throw "Missing arm: $arm"}
            $s=$matching[0]
            if([math]::Abs((dec $s.startingEquityUsdt)-$CapitalPerPortfolioUsdt) -gt 0.001){
                throw "Capital shared or changed unexpectedly"
            }
            $ts=@($report.trades|Where-Object {$_.arm -eq $arm})
            if($ts.Count -ne [int]$s.tradesClosed){
                throw "Trade count mismatch for $arm"
            }
            $sum=0.0
            foreach($trade in $ts){
                if([long]$trade.signalCloseTime -ge [long]$trade.enteredAtOpenTime -or
                   [long]$trade.enteredAtOpenTime -lt $w.FirstOpen -or
                   [long]$trade.exitedAtOpenTime -gt $w.DailyEnd){
                    throw "Trade outside the completed daily window"
                }
                $sum += [double]$trade.netPnlUsdt
                $tradeRows.Add([pscustomobject]@{
                    Symbol=$symbol;Window=$w.Window;Arm=$arm
                    Side=$trade.side;Setup=$trade.setup
                    SignalCloseTime=$trade.signalCloseTime
                    OutsideBandCloseTime=$trade.outsideBandCloseTime
                    ConfirmationPattern=$trade.confirmationPattern
                    EnteredAtOpenTime=$trade.enteredAtOpenTime
                    ExitedAtOpenTime=$trade.exitedAtOpenTime
                    EntryPrice=$trade.entryPrice;InitialStop=$trade.initialStop
                    ExitPrice=$trade.exitPrice;FrozenTarget=$trade.frozenTarget
                    ExitReason=$trade.exitReason;InitialRiskUsdt=$trade.initialRiskUsdt
                    NetPnlUsdt=$trade.netPnlUsdt;NetRMultiple=$trade.netRMultiple
                    FeesUsdt=$trade.feesUsdt
                })
            }
            if([math]::Abs($sum-[double]$s.netClosedUsdt) -gt 0.01){
                throw "Closed PnL mismatch"
            }
            $rows.Add([pscustomobject]@{
                Symbol=$symbol;Window=$w.Window;Arm=$arm
                WallStart=$w.WallStart;WallEnd=$w.WallEnd
                FirstDailyOpen=$w.FirstOpen;LastDailyClose=$w.DailyEnd
                Candles=12;Hours=300;CapitalUsdt=$s.startingEquityUsdt
                Trades=$s.tradesClosed;Wins=$s.wins;Losses=$s.losses
                BbTrades=$s.bbTrades;OutsideBandCloses=$s.outsideBandCloses
                Confirmed=$s.confirmedSignals;Expired=$s.expiredSignals
                Invalidated=$s.invalidatedSignals;FilteredRejected=$s.filteredRejected
                RiskApproved=$s.riskApproved;ClosedUsdt=$s.netClosedUsdt
                MarkedUsdt=$s.markedPnlUsdt;FeesUsdt=$s.feesUsdt
                MaxDrawdownPct=$s.maxDrawdownPct;OpenPositionSide=$s.openPositionSide
            })
        }
    }
}
if($rows.Count -ne $Symbols.Count*$Windows*4){
    throw 'Incomplete study; not exporting partial CSVs'
}
$root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$out=Join-Path $root 'results'
New-Item -Path $out -ItemType Directory -Force | Out-Null
$stamp="1d-300h-$AsOf-w$StartWindow-$($StartWindow+$Windows-1)"
$windowsPath=Join-Path $out "bollinger-daily-windows-$stamp.csv"
$aggregatePath=Join-Path $out "bollinger-daily-aggregate-$stamp.csv"
$tradesPath=Join-Path $out "bollinger-daily-trades-$stamp.csv"
foreach($p in @($windowsPath,$aggregatePath,$tradesPath)){
    if(Test-Path -LiteralPath $p){throw "Will not overwrite existing file $p"}
}
$aggregate=@($rows|Group-Object Symbol,Arm|ForEach-Object {
    $group=@($_.Group)
    [decimal]$closed=0;[decimal]$marked=0;[decimal]$fees=0
    foreach($row in $group){
        $closed+=dec $row.ClosedUsdt
        $marked+=dec $row.MarkedUsdt
        $fees+=dec $row.FeesUsdt
    }
    [pscustomobject]@{
        Symbol=$group[0].Symbol;Arm=$group[0].Arm;Windows=$group.Count
        TotalTrades=($group|Measure-Object -Property Trades -Sum).Sum
        BbTrades=($group|Measure-Object -Property BbTrades -Sum).Sum
        SumClosedUsdt=$closed
        SumMarkedUsdt=$marked
        SumFeesUsdt=$fees
    }
})
if($aggregate.Count -ne $Symbols.Count*4 -or
    @($aggregate|Where-Object {$_.Windows -ne $Windows}).Count -ne 0){
    throw 'Missing symbol/scenario windows'
}
@($rows.ToArray()) | Export-Csv -Path $windowsPath -NoTypeInformation -Encoding UTF8
$aggregate | Export-Csv -Path $aggregatePath -NoTypeInformation -Encoding UTF8
if($tradeRows.Count -gt 0){
    @($tradeRows.ToArray()) | Export-Csv -Path $tradesPath -NoTypeInformation -Encoding UTF8
} else {
    'Symbol,Window,Arm,Side,Setup,SignalCloseTime,OutsideBandCloseTime,ConfirmationPattern,EnteredAtOpenTime,ExitedAtOpenTime,EntryPrice,InitialStop,ExitPrice,FrozenTarget,ExitReason,InitialRiskUsdt,NetPnlUsdt,NetRMultiple,FeesUsdt' |
        Set-Content -Path $tradesPath -Encoding UTF8
}
$aggregate | Format-Table -AutoSize
Write-Host "Saved: $aggregatePath"
Write-Host "Saved: $windowsPath"
Write-Host "Saved: $tradesPath"
