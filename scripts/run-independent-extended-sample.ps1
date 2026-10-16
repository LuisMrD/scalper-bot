<#
.SYNOPSIS
  Freeze unchanged pullback-only 1d/1w rules over 12 or 24 x 30-day windows.
.DESCRIPTION
  Uses BTCUSDT / SOLUSDT / DOGEUSDT, 4 independent LONG/SHORT pullback paper
  portfolios, 30 daily bars per nonoverlapping window. 12 windows=360 days;
  24 windows=720 days. The 12-window period is NESTED in the 24-window period,
  so NEVER treat both studies as two independent samples or add their P&L.
  Optional -SyncHistory asks the local backend to retrieve daily and weekly
  candles from its configured TESTNET source (public data only). Exchange
  historical retention is not guaranteed: fail rather than fabricate candles.
.EXAMPLE
  .\scripts\run-independent-extended-sample.ps1 -Months 12 -PrintOnly
.EXAMPLE
  .\scripts\run-independent-extended-sample.ps1 -Months 12 -SyncHistory
.EXAMPLE
  .\scripts\run-independent-extended-sample.ps1 -Months 24 -SyncHistory
#>
[CmdletBinding()]
param(
    [ValidateSet(12,24)]
    [int]$Months=12,
    [ValidatePattern('^\d{4}-\d{2}-\d{2}$')]
    [string]$AsOfDateUtc='2026-10-15',
    [ValidatePattern('^https?://(localhost|127\.0\.0\.1)(:\d+)?$')]
    [string]$BaseUrl='http://127.0.0.1:8080',
    [switch]$SyncHistory,
    [switch]$PrintOnly
)
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$configFile=Join-Path $PSScriptRoot "config/independent-daily-weekly-${Months}x30d.json"
$baseFile=Join-Path $PSScriptRoot 'config/independent-daily-weekly-research.json'
$config=Get-Content -LiteralPath $configFile -Raw -Encoding UTF8 | ConvertFrom-Json
$control=Get-Content -LiteralPath $baseFile -Raw -Encoding UTF8 | ConvertFrom-Json
# Fail if any fees, portfolio sizing, pair or technical input changes with
# respect to the already evaluated baseline; only window count may differ.
$control.windows=$Months
$expected=$control | ConvertTo-Json -Depth 30 -Compress
$actual=$config | ConvertTo-Json -Depth 30 -Compress
if($expected -cne $actual) {
    throw "Study must preserve ALL baseline inputs except windows=$Months. Compare $baseFile and $configFile"
}
if([int]$config.steps -ne 30 -or [int]$config.windows -ne $Months) {
    throw 'Expected frozen nonoverlapping 30-daily-candle study windows'
}
$culture=[Globalization.CultureInfo]::InvariantCulture
$style=[Globalization.DateTimeStyles]::AssumeUniversal -bor
    [Globalization.DateTimeStyles]::AdjustToUniversal
$asOf=[DateTime]::ParseExact($AsOfDateUtc,'yyyy-MM-dd',$culture,$style)
if(-not $PrintOnly -and $asOf -ge [DateTime]::UtcNow.Date) {
    throw 'asOf must be an already completed UTC day'
}
$lastDay=$asOf.ToString('yyyy-MM-dd')
$firstDecisionDay=$asOf.AddDays(-([int]$config.steps*$Months-1))
# 66 weekly bars of calendar lead-in. At least 60 WHOLE prior 1w bars
# are needed by the existing replay, allowing some UTC-week alignment margin.
# The same lead-in automatically covers the 60 daily warmup candles.
$syncFrom=$firstDecisionDay.AddDays(-66*7).ToString('yyyy-MM-dd')
$symbols=@($config.symbols)
if($symbols.Count -ne 3) {
    throw 'Extended experiment expects the same three symbols as the control'
}
Write-Output "HORIZON_DAYS=$($Months * 30)"
Write-Output "WINDOWS=$Months"
Write-Output "STEPS=$($config.steps)"
Write-Output "SYMBOLS=$($symbols -join ',')"
Write-Output "PORTFOLIOS=$($Months * $symbols.Count * 4)"
Write-Output "STUDY_FROM_UTC=$($firstDecisionDay.ToString('yyyy-MM-dd'))"
Write-Output "STUDY_THROUGH_UTC=$lastDay"
Write-Output "SYNC_FROM_UTC=$syncFrom"
Write-Output "SYNC_THROUGH_UTC=$lastDay"
Write-Output 'STRATEGIES=TREND_PULLBACK,TREND_PULLBACK_MIXED,TREND_PULLBACK_SHORT,TREND_PULLBACK_MIXED_SHORT'
Write-Output 'WEEKLY_FILTER=CLOSED_WEEKLY_EMA21_SLOPE'
Write-Output 'EXECUTION=PAPER_ONLY'
Write-Output 'RESEARCH_LIMITATION=30_DAY_PORTFOLIO_RESETS; NESTED_12_24_MONTH_HORIZONS'
$run=Join-Path $PSScriptRoot 'run-independent-daily-weekly.ps1'
if($PrintOnly) {
    & $run -ConfigPath $configFile -AsOfDateUtc $lastDay -BaseUrl $BaseUrl -PrintOnly
    return
}
if($SyncHistory) {
    $base=$BaseUrl.TrimEnd('/')
    $settings=Invoke-RestMethod -Uri "$base/api/v1/market-data/settings" -TimeoutSec 15
    $status=Invoke-RestMethod -Uri "$base/api/v1/research/independent-setups/status" -TimeoutSec 15
    if($settings.source -ne 'TESTNET' -or
       $status.mode -ne 'PAPER_ONLY' -or
       $status.ordersEnabled -ne $false -or
       [int]$status.paperVariants -ne 4 -or
       $status.higherTrendRule -ne 'CLOSED_WEEKLY_EMA21_SLOPE') {
        throw 'Expected updated paper-only pullback backend with Binance TESTNET historical source'
    }
    Write-Output 'Syncing historical CLOSED 1d/1w candles, in <=30-day batches.'
    & (Join-Path $PSScriptRoot 'sync-independent-daily-weekly.ps1') `
        -Symbols $symbols `
        -FromUtc $syncFrom `
        -UntilUtc $lastDay `
        -BaseUrl $BaseUrl `
        -FailOnEmptyDaily
}
& $run -ConfigPath $configFile -AsOfDateUtc $lastDay -BaseUrl $BaseUrl
