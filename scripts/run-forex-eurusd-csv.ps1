<#
.SYNOPSIS
  Execute explicit historical EURUSD bid/ask SHORT paper backtest.
  Does not place orders or retrieve Binance market data.
.EXAMPLE
  .\scripts\run-forex-eurusd-csv.ps1 -StartUtc '2023-01-02' -UntilUtc '2023-12-29' -PrintOnly
#>
[CmdletBinding()]
param(
    [ValidateSet('EURUSD','GBPUSD','AUDUSD','NZDUSD')][string]$Pair='EURUSD',
    [Parameter(Mandatory=$true)][string]$StartUtc,
    [Parameter(Mandatory=$true)][string]$UntilUtc,
    [decimal]$CapitalUsd=600,
    [decimal]$RiskPerTradePct=0.5,
    [decimal]$DailyStopPct=3,
    [ValidateRange(1,100)][int]$Leverage=10,
    [decimal]$CommissionUsdPer100kPerSide=3.5,
    [decimal]$SlippagePipsPerSide=0.2,
    [decimal]$StopBufferPips=1,
    [decimal]$MinUnits=1000,
    [decimal]$UnitStep=1000,
    [ValidateRange(1,200)][int]$MaxHoldingBars=60,
    [switch]$TickVolumeMidasProxy,
    [switch]$PrintOnly,
    [ValidatePattern('^http://(127\.0\.0\.1|localhost)(:\d+)?$')]
    [string]$BaseUrl='http://127.0.0.1:8080'
)
$ErrorActionPreference='Stop'
$start=[DateTimeOffset]::ParseExact($StartUtc,'yyyy-MM-dd',
    [Globalization.CultureInfo]::InvariantCulture,
    [Globalization.DateTimeStyles]::AssumeUniversal)
$last=[DateTimeOffset]::ParseExact($UntilUtc,'yyyy-MM-dd',
    [Globalization.CultureInfo]::InvariantCulture,
    [Globalization.DateTimeStyles]::AssumeUniversal)
if($last -le $start) { throw 'UntilUtc must be after StartUtc' }
# 23:45 UTC final 15m BAR OPEN; files must include the day specified.
$payload=[ordered]@{
    pair=$Pair
    startOpenUtc=$start.ToUnixTimeMilliseconds()
    lastOpenUtc=$last.ToUnixTimeMilliseconds()+85500000
    capitalUsd=$CapitalUsd
    riskPerTradePct=$RiskPerTradePct
    dailyStopPct=$DailyStopPct
    leverage=$Leverage
    commissionUsdPer100kPerSide=$CommissionUsdPer100kPerSide
    slippagePipsPerSide=$SlippagePipsPerSide
    stopBufferPips=$StopBufferPips
    minUnits=$MinUnits
    unitStep=$UnitStep
    maxHoldingBars=$MaxHoldingBars
    tickVolumeMidasProxy=[bool]$TickVolumeMidasProxy
}
if($PrintOnly){
    Write-Output 'MODE=FOREX_OTC_PAPER_ONLY'
    Write-Output 'STRATEGY=NEW_FOREX_PRICE_ACTION_SHORT_V1_NOT_BINANCE'
    Write-Output 'SOURCE=LOCAL_BID_ASK_CSV'
    Write-Output 'FINANCING=NOT_MODELLED'
    $payload|ConvertTo-Json -Depth 8
    return
}
$status=Invoke-RestMethod -Uri "$BaseUrl/api/v1/research/forex/status" -TimeoutSec 10
if($status.mode -cne 'CSV_PAPER_ONLY_NO_ORDERS' -or
    $status.market -cne 'FOREX_OTC_SPOT') { throw 'Not a safe Forex paper-only backend' }
$body=$payload|ConvertTo-Json -Depth 8 -Compress
$report=Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/research/forex/backtest" -Body ([Text.Encoding]::UTF8.GetBytes($body)) -ContentType 'application/json; charset=utf-8' -TimeoutSec 900
if($report.market -cne 'FOREX_OTC_SPOT' -or $report.pair -cne $Pair){
    throw 'Unexpected market in report'
}
$root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$out=Join-Path $root 'results'
New-Item -ItemType Directory -Path $out -Force | Out-Null
$tag=[DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')
$stem="forex-$Pair-$StartUtc-$UntilUtc-$tag"
$report | ConvertTo-Json -Depth 24 | Set-Content (Join-Path $out "$stem.json") -Encoding UTF8
@($report.trades) | Export-Csv (Join-Path $out "$stem-trades.csv") -NoTypeInformation -Encoding UTF8
$report | Select-Object pair,strategy,candles,eligibleSignals,winningTrades,realizedUsd,feesUsd,worstDrawdownPct,openAtEnd | Format-List
Write-Host "Exported: $out\$stem.json"
