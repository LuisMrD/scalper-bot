<#
.SYNOPSIS
  Compare trade-level economics of RETEST (or another setup) on matched
  300-hour 1m and 5m backtests, including entry-time regime snapshots.
.DESCRIPTION
  Six simulated replays by default: 3 windows x 2 primary timeframes.
  Never sends real orders or invokes Luna. Imports flattened closed-trade
  CSVs and computes NET expectancy, win/loss, fees, slippage decomposition,
  regime/side/exit splits, and concentration in the three best winners.
  -AnalyzeExisting reads CSVs created with -ExportTrades without rerunning.
.EXAMPLE
  .\scripts\compare-timeframe-trades.ps1 -PrintOnly
.EXAMPLE
  .\scripts\compare-timeframe-trades.ps1 -Source testnet -AsOf 1791845999999
.EXAMPLE
  .\scripts\compare-timeframe-trades.ps1 -AnalyzeExisting -AsOf 1791845999999
#>
[CmdletBinding()]
param(
    [ValidateSet('SOLUSDT','BTCUSDT','ETHUSDT')]
    [string]$Symbol = 'SOLUSDT',
    [ValidateSet('RETEST','PULLBACK','BOTH')]
    [string]$Setup = 'RETEST',
    [ValidateRange(2,6)]
    [int]$Windows = 3,
    [ValidateRange(0,[long]::MaxValue)]
    [long]$AsOf = 1791845999999,
    [ValidateRange(21,500)]
    [int]$ContextLimit = 200,
    [ValidateRange(1,100)]
    [int]$CostLimitPct = 50,
    [ValidateRange(1,1000000000)]
    [decimal]$ExpectedCapitalUsdt = 1500,
    [ValidateSet('testnet','production')]
    [string]$Source = 'testnet',
    [ValidatePattern('^https?://(127\.0\.0\.1|localhost)(:\d+)?$')]
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [ValidateRange(30,1800)]
    [int]$TimeoutSec = 900,
    [switch]$SyncHistory,
    [switch]$ConfirmIsolatedDatabase,
    [switch]$AnalyzeExisting,
    [switch]$PrintOnly
)
$ErrorActionPreference = 'Stop'
$one = [long]60000
$five = [long]300000
if ($AsOf -le 0 -or ($AsOf % $five) -ne ($five - 1)) {
    throw "AsOf=$AsOf must be an aligned CLOSED 5m timestamp (ending at xx:x4:59.999 UTC)."
}
if ($Source -eq 'production' -and -not $ConfirmIsolatedDatabase) {
    throw 'Production data require -ConfirmIsolatedDatabase and isolated RESEARCH_DB_URL.'
}
if ($PrintOnly) {
    Write-Output "SYMBOL=$Symbol"
    Write-Output "SETUP=$Setup"
    Write-Output "WINDOWS=$Windows"
    Write-Output 'TIMEFRAMES=1m,5m'
    Write-Output 'STEPS_1M=18000'
    Write-Output 'STEPS_5M=3600'
    Write-Output 'HOURS_PER_WINDOW=300'
    Write-Output "AS_OF=$AsOf"
    Write-Output "EXPECTED_CAPITAL_USDT=$ExpectedCapitalUsdt"
    Write-Output "SOURCE=$Source"
    Write-Output "ANALYZE_EXISTING=$($AnalyzeExisting.ToString().ToLowerInvariant())"
    Write-Output 'ORDERS=false'
    Write-Output 'LUNA=false'
    return
}

$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$out = Join-Path $root 'results'
$runner = Join-Path $PSScriptRoot 'compare-setup-windows.ps1'
. (Join-Path $PSScriptRoot 'lib/trade-diagnostics.ps1')

if (-not $AnalyzeExisting) {
    foreach ($tf in @('1m','5m')) {
        $steps = if ($tf -eq '1m') { 18000 } else { 3600 }
        Write-Host "Replaying $Setup on ${tf}: $Windows x 300h, capital $ExpectedCapitalUsdt USDT, cutoff $AsOf"
        & $runner -PrimaryInterval $tf -Symbol $Symbol -Setup $Setup `
            -Steps $steps -Windows $Windows -AsOf $AsOf -ContextLimit $ContextLimit `
            -CostLimitPct $CostLimitPct -ExpectedCapitalUsdt $ExpectedCapitalUsdt `
            -Source $Source -BaseUrl $BaseUrl -TimeoutSec $TimeoutSec `
            -SyncHistory:$SyncHistory -ConfirmIsolatedDatabase:$ConfirmIsolatedDatabase `
            -ExportTrades
    }
}

$windowsByTf = @{}
$tradesByTf = @{}
foreach ($tf in @('1m','5m')) {
    $suffix = if ($tf -eq '5m') { '-5m' } else { '' }
    $windowCsv = Join-Path $out ("setup-windows-{0}{1}-{2}.csv" -f $Symbol,$suffix,$AsOf)
    $tradeCsv = Join-Path $out ("setup-trades-{0}{1}-{2}.csv" -f $Symbol,$suffix,$AsOf)
    if (-not (Test-Path $windowCsv)) { throw "Missing $windowCsv. Run without -AnalyzeExisting." }
    $windowRows = @(Import-Csv -Path $windowCsv | Where-Object { $_.Setup -eq $Setup })
    if ($windowRows.Count -ne $Windows) {
        throw "${tf}: expected exactly $Windows rows for setup $Setup, got $($windowRows.Count)"
    }
    $expectedTradeCount = 0
    foreach ($row in $windowRows) {
        if ($row.Source -ne $Source.ToUpperInvariant() -or
            $row.Symbol -ne $Symbol -or
            $row.PrimaryInterval -ne $tf -or
            [decimal]$row.StartingEquityUsdt -ne $ExpectedCapitalUsdt -or
            [decimal]$row.HoursPerWindow -ne 300) {
            throw "${tf}: CSV provenance, capital or 300-hour duration is inconsistent"
        }
        $expectedEnd = $AsOf - ([long]([int]$row.Window - 1) * 300L * 3600000L)
        $expectedFrom = $expectedEnd - 300L * 3600000L + 1
        if ([long]$row.AsOf -ne $expectedEnd -or [long]$row.FromOpen -ne $expectedFrom) {
            throw "${tf}: invalid time boundaries for window $($row.Window)"
        }
        $expectedTradeCount += [int]$row.TradesClosed
    }
    $rows = if (Test-Path $tradeCsv) { @(Import-Csv -Path $tradeCsv | Where-Object { $_.Setup -eq $Setup }) } else { @() }
    if ($rows.Count -ne $expectedTradeCount) {
        throw "${tf}: expected $expectedTradeCount closed trades, found $($rows.Count) in $tradeCsv"
    }
    foreach ($trade in $rows) {
        if ($trade.Source -ne $Source.ToUpperInvariant() -or $trade.Symbol -ne $Symbol -or
            $trade.PrimaryInterval -ne $tf -or $trade.EntryTrend -notin @('UP','DOWN') -or
            $trade.Trend15m -notin @('UP','DOWN','RANGE','UNDETERMINED')) {
            throw "${tf}: invalid trade provenance, signal trend, or 15m snapshot"
        }
        $match = @($windowRows | Where-Object { [int]$_.Window -eq [int]$trade.Window })
        if ($match.Count -ne 1 -or
            [long]$trade.EnteredAtOpenTime -lt [long]$match[0].FromOpen -or
            [long]$trade.ExitCandleOpenTime -gt [long]$match[0].AsOf) {
            throw "${tf}: trade timestamp outside its historical window"
        }
    }
    [decimal]$sumFromTrades = Sum-TradeDecimal @($rows | ForEach-Object { Read-TradeDecimal $_.NetPnlUsdt 'NetPnlUsdt' })
    [decimal]$sumFromWindow = Sum-TradeDecimal @($windowRows | ForEach-Object { Read-TradeDecimal $_.NetClosedUsdt 'NetClosedUsdt' })
    if ([math]::Abs($sumFromTrades - $sumFromWindow) -gt [decimal]0.01) {
        throw "${tf}: closed-trade PnL does not reconcile with exported window PnL"
    }
    $windowsByTf[$tf] = $windowRows
    $tradesByTf[$tf] = $rows
}
$oneWindows = @($windowsByTf['1m'])
$fiveWindows = @($windowsByTf['5m'])
for ($w = 1; $w -le $Windows; $w++) {
    $a = @($oneWindows | Where-Object { [int]$_.Window -eq $w })
    $b = @($fiveWindows | Where-Object { [int]$_.Window -eq $w })
    if ($a.Count -ne 1 -or $b.Count -ne 1 -or
        [long]$a[0].FromOpen -ne [long]$b[0].FromOpen -or
        [long]$a[0].AsOf -ne [long]$b[0].AsOf) {
        throw "1m/5m not aligned on window $w: refusing invalid comparison"
    }
}

$metrics = @()
$windows = @()
foreach ($tf in @('1m','5m')) {
    $trades = @($tradesByTf[$tf])
    $summary = Get-TradeAuditMetrics -Trades $trades -PrimaryInterval $tf
    $metrics += $summary
    foreach ($side in @('LONG','SHORT')) {
        $group = @($trades | Where-Object { $_.Side -eq $side })
        if ($group.Count) {
            $metrics += Get-TradeAuditMetrics -Trades $group -PrimaryInterval $tf -SegmentType 'SIDE' -Segment $side
        }
    }
    foreach ($trend in @('UP','DOWN','RANGE','UNDETERMINED')) {
        $group = @($trades | Where-Object { $_.Trend15m -eq $trend })
        if ($group.Count) {
            $metrics += Get-TradeAuditMetrics -Trades $group -PrimaryInterval $tf -SegmentType '15M_REGIME' -Segment $trend
        }
    }
    foreach ($conflict in @('ALIGNED','CONFLICT')) {
        $group = @($trades | Where-Object {
            if ($conflict -eq 'ALIGNED') { [int]$_.HtfConflicts -eq 0 }
            else { [int]$_.HtfConflicts -gt 0 }
        })
        if ($group.Count) {
            $metrics += Get-TradeAuditMetrics -Trades $group -PrimaryInterval $tf -SegmentType 'HTF_ALIGNMENT' -Segment $conflict
        }
    }
    foreach ($exitReason in @($trades | Select-Object -ExpandProperty ExitReason -Unique)) {
        $group = @($trades | Where-Object { $_.ExitReason -eq $exitReason })
        if ($group.Count) {
            $metrics += Get-TradeAuditMetrics -Trades $group -PrimaryInterval $tf -SegmentType 'EXIT_REASON' -Segment $exitReason
        }
    }
    foreach ($row in @($windowsByTf[$tf] | Sort-Object { [int]$_.Window })) {
        $groups = @($trades | Where-Object { [int]$_.Window -eq [int]$row.Window })
        $metrics += Get-TradeAuditMetrics -Trades $groups -PrimaryInterval $tf -SegmentType 'WINDOW' -Segment $row.Window
        $windows += [PSCustomObject]@{
            PrimaryInterval = $tf
            Setup = $Setup
            Window = $row.Window
            FromOpen = $row.FromOpen
            AsOf = $row.AsOf
            ReturnPct = $row.ReturnPct
            DrawdownPct = $row.DrawdownPct
            NetClosedUsdt = $row.NetClosedUsdt
            NetOpenUsdt = $row.NetOpenUsdt
            ClosedTrades = $row.TradesClosed
            OpenSide = $row.OpenSide
        }
    }
}
Write-Host 'CLOSED-TRADE economics (fees already in net PnL; slippage already in fills):'
$metrics | Where-Object { $_.SegmentType -eq 'ALL' } |
    Format-Table PrimaryInterval,ClosedTrades,WinRatePct,AvgWinUsdt,AvgLossUsdt,ExpectancyNetUsdt,ExpectancyNetR,ProfitFactor,NetClosedPnlUsdt,TotalFeesUsdt,TotalSlippageUsdtDiagnostic,NetWithoutTop3WinsUsdt -AutoSize
Write-Host 'Breakdown: side, 15m regime, HTF alignment and exit reasons:'
$metrics | Where-Object { $_.SegmentType -notin @('ALL','WINDOW') } |
    Format-Table PrimaryInterval,SegmentType,Segment,ClosedTrades,WinRatePct,ExpectancyNetUsdt,NetClosedPnlUsdt -AutoSize
Write-Host 'Matched 300-hour periods; marked-to-market returns include OPEN trades:'
$windows | Format-Table PrimaryInterval,Window,ReturnPct,DrawdownPct,NetClosedUsdt,NetOpenUsdt,ClosedTrades,OpenSide -AutoSize
New-Item -ItemType Directory -Force -Path $out | Out-Null
$name = "$Symbol-$Setup-$AsOf"
$metricsFile = Join-Path $out "timeframe-trade-metrics-$name.csv"
$windowsFile = Join-Path $out "timeframe-matched-windows-$name.csv"
$metrics | Export-Csv -Path $metricsFile -NoTypeInformation -Encoding UTF8
$windows | Export-Csv -Path $windowsFile -NoTypeInformation -Encoding UTF8
Write-Host "Saved: $metricsFile"
Write-Host "Saved: $windowsFile"
Write-Host 'Trade-level source files: setup-trades-<symbol>[-5m]-<asOf>.csv'
