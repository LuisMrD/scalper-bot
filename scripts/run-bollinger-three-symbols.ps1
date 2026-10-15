<#
.SYNOPSIS
  SOLUSDT DOGEUSDT BTCUSDT: six windows of 300h, 1500 USDT EACH independent ledger.
.DESCRIPTION
  Two backtest arms per symbol/window (CONTROL and CONFIRMED BOLLINGER).
  No cash shared between tickers or scenarios; TESTNET/SIMULATION_ONLY only.
  Already inspected windows 34..39 are retrospective, not new OOS.
.EXAMPLE
  .\scripts\run-bollinger-three-symbols.ps1 -PrintOnly
.EXAMPLE
  .\scripts\run-bollinger-three-symbols.ps1 -SyncHistory
#>
[CmdletBinding()]
param(
    [ValidateSet('SOLUSDT','DOGEUSDT','BTCUSDT')]
    [string[]]$Symbols=@('SOLUSDT','DOGEUSDT','BTCUSDT'),
    [ValidateRange(1,100)][int]$StartWindow=34,
    [ValidateSet(3,6,9)][int]$Windows=6,
    [long]$AsOf=1791845999999,
    [ValidateRange(1,1000000000)][decimal]$CapitalPerPortfolioUsdt=1500,
    [ValidateRange(21,500)][int]$ContextLimit=200,
    [ValidateRange(1,100)][int]$CostLimitPct=50,
    [ValidatePattern('^https?://(127\.0\.0\.1|localhost)(:\d+)?$')]
    [string]$BaseUrl='http://127.0.0.1:8080',
    [ValidateRange(30,1800)][int]$TimeoutSec=900,
    [switch]$SyncHistory,
    [switch]$PrintOnly
)
$ErrorActionPreference='Stop'
if(@($Symbols|Select-Object -Unique).Count -ne $Symbols.Count){
    throw 'Duplicate symbols are not allowed: would double-count capital.'
}
if($AsOf -le 0 -or $AsOf%900000L -ne 899999L){
    throw 'AsOf must be the UTC close of a completed 15m candle'
}
$runner=Join-Path $PSScriptRoot 'compare-bollinger-5m.ps1'
$root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$out=Join-Path $root 'results'
$lastWindow=$StartWindow+$Windows-1
$stamp="RETEST-$AsOf-5m-w$StartWindow-$lastWindow"
$capital=[decimal]$Symbols.Count*$CapitalPerPortfolioUsdt
if($PrintOnly){
    Write-Output "THREE_SYMBOLS=$($Symbols -join ',')"
    Write-Output "THREE_SYMBOLS_WINDOWS=$Windows"
    Write-Output 'THREE_SYMBOLS_HOURS_PER_WINDOW=300'
    Write-Output "THREE_SYMBOLS_START_WINDOW=$StartWindow"
    Write-Output "THREE_SYMBOLS_END_WINDOW=$lastWindow"
    Write-Output "THREE_SYMBOLS_CAPITAL_PER_PORTFOLIO_USDT=$CapitalPerPortfolioUsdt"
    Write-Output "THREE_SYMBOLS_CAPITAL_PER_ARM_ACROSS_SYMBOLS_USDT=$capital"
    Write-Output "THREE_SYMBOLS_INDEPENDENT_SYMBOL_WINDOW_PAIRS=$($Symbols.Count*$Windows)"
    Write-Output "THREE_SYMBOLS_INDEPENDENT_REPLAY_LEDGERS=$($Symbols.Count*$Windows*2)"
    Write-Output 'THREE_SYMBOLS_NO_SHARED_BALANCE=true'
    Write-Output 'THREE_SYMBOLS_TIMEFRAME=5m'
    Write-Output 'THREE_SYMBOLS_CONTEXT=15m'
    Write-Output 'THREE_SYMBOLS_BOLLINGER_CONFIRMATION=STRICT_OUTSIDE_THEN_PRICE_ACTION'
    Write-Output 'THREE_SYMBOLS_NO_LUNA=true'
    Write-Output 'THREE_SYMBOLS_NO_ORDERS=true'
    Write-Output 'THREE_SYMBOLS_SOURCE=TESTNET'
    foreach($symbol in $Symbols){
        Write-Output "THREE_SYMBOLS_INSTRUMENT=$symbol"
        $args=@{Symbol=$symbol;StartWindow=$StartWindow;Windows=$Windows
            AsOf=$AsOf;ContextLimit=$ContextLimit;CostLimitPct=$CostLimitPct
            ExpectedCapitalUsdt=$CapitalPerPortfolioUsdt;PrintOnly=$true}
        & $runner @args
    }
    return
}
$aggregates=New-Object System.Collections.Generic.List[object]
$windowsData=New-Object System.Collections.Generic.List[object]
$allTrades=New-Object System.Collections.Generic.List[object]
foreach($symbol in $Symbols){
    Write-Host ("Symbol {0}: {1} independent 300h control vs Bollinger comparisons" -f $symbol,$Windows)
    $args=@{Symbol=$symbol;StartWindow=$StartWindow;Windows=$Windows
        AsOf=$AsOf;ContextLimit=$ContextLimit;CostLimitPct=$CostLimitPct
        ExpectedCapitalUsdt=$CapitalPerPortfolioUsdt;BaseUrl=$BaseUrl
        TimeoutSec=$TimeoutSec;SyncHistory=$SyncHistory}
    & $runner @args
    $stem="$symbol-$stamp"
    $ag=Join-Path $out "bollinger-5m-aggregate-$stem.csv"
    $wi=Join-Path $out "bollinger-5m-windows-$stem.csv"
    $tr=Join-Path $out "bollinger-5m-trades-$stem.csv"
    foreach($p in @($ag,$wi,$tr)){
        if(-not(Test-Path -LiteralPath $p)){throw "Missing completed symbol CSV: $p"}
    }
    $a=@(Import-Csv -LiteralPath $ag)
    $w=@(Import-Csv -LiteralPath $wi)
    $t=@(Import-Csv -LiteralPath $tr)
    if($a.Count -ne 2 -or $w.Count -ne 2*$Windows){
        throw "Symbol $symbol has missing independent ledgers"
    }
    foreach($row in $a){
        if([int]$row.Windows -ne $Windows){throw "Incomplete arm for $symbol"}
        $aggregates.Add([pscustomobject]@{
            Symbol=$symbol;Arm=$row.Arm;Windows=$row.Windows
            CapitalPerPortfolioUsdt=$CapitalPerPortfolioUsdt
            Trades=$row.Trades;Wins=$row.Wins;BbTrades=$row.BbTrades
            OutsideCloses=$row.OutsideCloses
            BbConfirmedSignals=$row.BbConfirmedSignals
            BbExpiredSignals=$row.BbExpiredSignals
            BbInvalidatedSignals=$row.BbInvalidatedSignals
            SumNetClosedUsdt=$row.SumNetClosedUsdt
            SumMarkedPnlUsdt=$row.SumMarkedPnlUsdt
            SumFeesUsdt=$row.SumFeesUsdt
            DeltaMarkedVsControl=$row.DeltaMarkedVsControl
            CapitalNotShared=$true;ResearchPhase='RETROSPECTIVE_INSPECTED'
        })
    }
    foreach($row in $w){
        if($row.Arm -notin @('CONTROL','CONTROL_PLUS_BOLLINGER_SMA20_2SIGMA') -or
           [decimal]$row.CapitalUsdt -ne $CapitalPerPortfolioUsdt){
            throw "Portfolio capital is not independent for $symbol w$($row.Window)"
        }
        $windowsData.Add([pscustomobject]@{
            Symbol=$symbol;Window=$row.Window;Arm=$row.Arm
            CapitalUsdt=$row.CapitalUsdt;TradesClosed=$row.TradesClosed
            BbTrades=$row.BbTrades;OutsideCloses=$row.OutsideCloses
            BbConfirmedSignals=$row.BbConfirmedSignals
            NetClosedUsdt=$row.NetClosedUsdt;MarkedPnlUsdt=$row.MarkedPnlUsdt
            FeesUsdt=$row.FeesUsdt;MaxDrawdownPct=$row.MaxDrawdownPct
            OpenPositionSide=$row.OpenPositionSide
            DeltaMarkedVsControl=$row.DeltaMarkedVsControl
        })
    }
    foreach($row in $t){
        $allTrades.Add([pscustomobject]@{
            Symbol=$symbol;Window=$row.Window;Arm=$row.Arm;Side=$row.Side
            Setup=$row.Setup;OutsideBandCloseTime=$row.OutsideBandCloseTime
            SignalCloseTime=$row.SignalCloseTime;ConfirmationPattern=$row.ConfirmationPattern
            EnteredAtOpenTime=$row.EnteredAtOpenTime
            ExitedAtOpenTime=$row.ExitedAtOpenTime;ExitReason=$row.ExitReason
            FrozenMiddleTargetUsdt=$row.FrozenMiddleTargetUsdt
            InitialRiskUsdt=$row.InitialRiskUsdt;NetPnlUsdt=$row.NetPnlUsdt
            NetR=$row.NetR;FeesUsdt=$row.FeesUsdt
        })
    }
}
if($aggregates.Count -ne 2*$Symbols.Count -or
   $windowsData.Count -ne 2*$Symbols.Count*$Windows){
    throw 'Missing a symbol/window/strategy ledger. No combined export.'
}
$combined=@(
    (Join-Path $out "bollinger-three-symbols-aggregate-$stamp.csv"),
    (Join-Path $out "bollinger-three-symbols-windows-$stamp.csv"),
    (Join-Path $out "bollinger-three-symbols-trades-$stamp.csv")
)
foreach($p in $combined){if(Test-Path -LiteralPath $p){throw "Will not overwrite $p"}}
@($aggregates.ToArray())|Export-Csv -LiteralPath $combined[0] -NoTypeInformation -Encoding UTF8
@($windowsData.ToArray())|Export-Csv -LiteralPath $combined[1] -NoTypeInformation -Encoding UTF8
if($allTrades.Count -gt 0){
    @($allTrades.ToArray())|Export-Csv -LiteralPath $combined[2] -NoTypeInformation -Encoding UTF8
}else{
    'Symbol,Window,Arm,Side,Setup,OutsideBandCloseTime,SignalCloseTime,ConfirmationPattern,EnteredAtOpenTime,ExitedAtOpenTime,ExitReason,FrozenMiddleTargetUsdt,InitialRiskUsdt,NetPnlUsdt,NetR,FeesUsdt' |
        Set-Content -LiteralPath $combined[2] -Encoding UTF8
}
Write-Host 'SOL DOGE BTC: 1500 USDT independent per symbol/window/arm. No shared capital.'
$aggregates | Format-Table Symbol,Arm,Trades,BbTrades,SumMarkedPnlUsdt,DeltaMarkedVsControl -AutoSize
foreach($p in $combined){Write-Host "Saved: $p"}
