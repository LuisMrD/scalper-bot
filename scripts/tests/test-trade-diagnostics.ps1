# Offline deterministic trade-metric smoke tests. No network, DB, orders or LLM.
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '../lib/trade-diagnostics.ps1')

function TradeRow([string]$pnl,[string]$fee,[string]$slip,[string]$r) {
    [PSCustomObject]@{
        NetPnlUsdt=$pnl; FeesUsdt=$fee; SlippageUsdt=$slip; NetR=$r
    }
}
$trades = @(
    (TradeRow '8' '0.5' '0.2' '2'),
    (TradeRow '2' '0.5' '0.2' '0.5'),
    (TradeRow '-5' '0.5' '0.2' '-1'),
    (TradeRow '-1' '0.5' '0.2' '-0.2')
)
$a = Get-TradeAuditMetrics -Trades $trades -PrimaryInterval '5m'
if ($a.ClosedTrades -ne 4 -or $a.Wins -ne 2 -or $a.Losses -ne 2 -or
    $a.WinRatePct -ne 50 -or $a.AvgWinUsdt -ne 5 -or
    $a.AvgLossUsdt -ne -3 -or $a.ExpectancyNetUsdt -ne 1 -or
    $a.ExpectancyNetR -ne 0.325 -or
    $a.ProfitFactor -ne 1.6667 -or $a.NetClosedPnlUsdt -ne 4 -or
    $a.TotalFeesUsdt -ne 2 -or $a.TotalSlippageUsdtDiagnostic -ne 0.8 -or
    $a.Top3WinningPnlUsdt -ne 10 -or
    $a.Top3ShareOfGrossWinsPct -ne 100 -or
    $a.NetWithoutTop3WinsUsdt -ne -6) {
    throw "Trade metrics failed: $($a | ConvertTo-Json -Depth 6)"
}
$b = Get-TradeAuditMetrics -Trades @((TradeRow '0' '0.3' '0.1' '0')) -PrimaryInterval '1m'
if ($b.Breakevens -ne 1 -or $b.Wins -ne 0 -or $b.Losses -ne 0 -or
    $null -ne $b.ProfitFactor -or $b.ExpectancyNetUsdt -ne 0) {
    throw 'Flat trade handling failed.'
}
$c = Get-TradeAuditMetrics -Trades @() -PrimaryInterval '1m'
if ($c.ClosedTrades -ne 0 -or $null -ne $c.ExpectancyNetUsdt -or
    $c.NetClosedPnlUsdt -ne 0) { throw 'No-trade handling failed.' }
$d = Get-TradeAuditMetrics -Trades @((TradeRow '1' '0.1' '0.1' '0.2')) -PrimaryInterval '5m'
if ($null -ne $d.ProfitFactor -or $d.NetWithoutTop3WinsUsdt -ne 0) {
    throw 'No losing trades handling failed.'
}
$plan = @(& (Join-Path $PSScriptRoot '../compare-timeframe-trades.ps1') -PrintOnly)
foreach ($expected in @(
    'SETUP=RETEST','WINDOWS=3','TIMEFRAMES=1m,5m',
    'STEPS_1M=18000','STEPS_5M=3600','HOURS_PER_WINDOW=300',
    'AS_OF=1791845999999','EXPECTED_CAPITAL_USDT=1500',
    'ORDERS=false','LUNA=false'
)) {
    if ($plan -notcontains $expected) {
        throw "Incorrect comparison plan: expected $expected"
    }
}
$badCutoff = $false
try {
    & (Join-Path $PSScriptRoot '../compare-timeframe-trades.ps1') -AsOf 1791846239999 -PrintOnly | Out-Null
} catch {
    if ($_.Exception.Message -match 'aligned CLOSED 5m') { $badCutoff = $true }
}
if (-not $badCutoff) { throw 'Comparison accepted an unaligned 1m cutoff.' }
$unsafe = $false
try {
    & (Join-Path $PSScriptRoot '../compare-timeframe-trades.ps1') -Source production -PrintOnly | Out-Null
} catch {
    if ($_.Exception.Message -match 'ConfirmIsolatedDatabase') { $unsafe = $true }
}
if (-not $unsafe) { throw 'Production comparison accepted without isolated-database acknowledgement.' }
Write-Host 'Offline 1m/5m trade expectancy, costs, concentration and safety tests passed.'
