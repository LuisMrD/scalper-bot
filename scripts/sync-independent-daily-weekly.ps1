<#
.SYNOPSIS
  Synchronize CLOSED Binance 1d and 1w candles into local MySQL for research.
.DESCRIPTION
  Explicit historical UTC date range; each /sync request is <=30 days.
  Weekly requests begin on the preceding UTC Monday, so a requested range
  does not accidentally omit its opening weekly candle.
  This script NEVER sends an order and does not start paid AI.
.EXAMPLE
  .\scripts\sync-independent-daily-weekly.ps1 -Symbols BTCUSDT,SOLUSDT,DOGEUSDT -FromUtc 2025-01-01 -UntilUtc 2026-10-15 -PrintOnly
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)]
    [ValidateNotNullOrEmpty()]
    [string[]]$Symbols,
    [ValidateSet('15m','1d','1w')]
    [string[]]$Intervals=@('1d','1w'),
    [Parameter(Mandatory=$true)]
    [ValidatePattern('^\d{4}-\d{2}-\d{2}$')]
    [string]$FromUtc,
    [Parameter(Mandatory=$true)]
    [ValidatePattern('^\d{4}-\d{2}-\d{2}$')]
    [string]$UntilUtc,
    [ValidatePattern('^https?://(localhost|127\.0\.0\.1)(:\d+)?$')]
    [string]$BaseUrl='http://127.0.0.1:8080',
    [switch]$FailOnEmptyDaily,
    [switch]$PrintOnly
)
$ErrorActionPreference='Stop'
$inv=[Globalization.CultureInfo]::InvariantCulture
$utc=[Globalization.DateTimeStyles]::AssumeUniversal -bor [Globalization.DateTimeStyles]::AdjustToUniversal
$first=[DateTime]::ParseExact($FromUtc,'yyyy-MM-dd',$inv,$utc)
$last=[DateTime]::ParseExact($UntilUtc,'yyyy-MM-dd',$inv,$utc).AddDays(1).AddMilliseconds(-1)
if($last -lt $first){throw 'UntilUtc must not precede FromUtc'}
if(-not $PrintOnly -and $last -ge [DateTime]::UtcNow.Date) {
    throw 'UntilUtc must denote a fully completed historical UTC day'
}
foreach($symbol in $Symbols) {
    if($symbol -cnotmatch '^[A-Z0-9]{5,32}$' -or -not $symbol.EndsWith('USDT')) {
        throw "Invalid configured USDT Futures symbol: $symbol"
    }
}
$epoch=[DateTime]::SpecifyKind([DateTime]'1970-01-01T00:00:00',[DateTimeKind]::Utc)
$oneDay=[TimeSpan]::FromDays(1)
$base=$BaseUrl.TrimEnd('/')
foreach($symbol in $Symbols) {
    foreach($interval in $Intervals) {
        $start=$first
        if($interval -eq '1w') {
            $daysSinceMonday=([int]$start.DayOfWeek+6)%7
            $start=$start.AddDays(-$daysSinceMonday)
        }
        while($start -le $last) {
            # 30 calendar days maximum, no missing/overlapping opening instants.
            $end=$start.AddDays(30).AddMilliseconds(-1)
            if($end -gt $last){$end=$last}
            [long]$fromMs=[long]($start-$epoch).TotalMilliseconds
            [long]$untilMs=[long]($end-$epoch).TotalMilliseconds
            $uri="$base/api/v1/market-data/sync?symbol=$symbol&interval=$interval&from=$fromMs&until=$untilMs"
            if($PrintOnly) {
                Write-Output "PLAN $symbol $interval $($start.ToString('yyyy-MM-dd'))..$($end.ToString('yyyy-MM-dd'))"
            } else {
                $r=Invoke-RestMethod -Method Post -Uri $uri -TimeoutSec 180 -ErrorAction Stop
                if($FailOnEmptyDaily -and $interval -in @('15m','1d') -and [int]$r.fetched -eq 0) {
                    throw "No $interval candles from the configured Binance market source for $symbol starting $($start.ToString('yyyy-MM-dd')). Historical range may be unavailable on TESTNET. Research did NOT complete; do not fill or invent missing bars."
                }
                Write-Host "$($r.symbol) $($r.interval): fetched=$($r.fetched), persisted=$($r.persisted), pages=$($r.pages)"
            }
            $start=$end.AddMilliseconds(1)
        }
    }
}
