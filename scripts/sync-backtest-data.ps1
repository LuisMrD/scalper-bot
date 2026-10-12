<#
.SYNOPSIS
  Loads historical Binance USD-M testnet candles into LOCAL MySQL via the app.
.DESCRIPTION
  Calls the application's existing manual /market-data/bootstrap endpoint.
  Automatic bootstrap/WS stays OFF. Does not submit orders to Binance.
  Requires Spring Boot running via scripts/start-backtest.ps1.
.EXAMPLE
  .\scripts\sync-backtest-data.ps1
#>
[CmdletBinding()]
param(
    [ValidatePattern('^https?://(127\.0\.0\.1|localhost)(:\d+)?$')]
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [ValidateRange(30, 1800)]
    [int]$TimeoutSec = 900
)

$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')

try {
    $health = Invoke-RestMethod -Uri "$BaseUrl/actuator/health" -TimeoutSec 15 -ErrorAction Stop
    if ($health.status -ne 'UP') { throw "App not ready: health=$($health.status)" }

    $settings = Invoke-RestMethod -Uri "$BaseUrl/api/v1/market-data/settings" -TimeoutSec 15 -ErrorAction Stop
    Write-Host "Configured intervals: $($settings.intervals -join ', ')"
    Write-Host 'Syncing all configured pairs/timeframes using public market-data REST (may take minutes)...'

    # Invoke-RestMethod (notably Windows PowerShell 5.1) can emit a JSON array
    # as ONE pipeline object. Wrapping the call in @() then nests Object[],
    # and Select-Object cannot find 'symbol' on the outer array.
    $response = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/market-data/bootstrap" `
        -TimeoutSec $TimeoutSec -ErrorAction Stop
    $results = @($response)
    while ($results.Count -eq 1 -and $results[0] -is [System.Array]) {
        $results = @($results[0])
    }
    if ($results.Count -eq 0) { throw 'No sync results returned by application.' }
    foreach ($r in $results) {
        if ($null -eq $r -or $null -eq $r.PSObject.Properties['symbol'] -or
                $null -eq $r.PSObject.Properties['interval']) {
            throw "Unexpected bootstrap JSON response; expected a list of SyncResult objects."
        }
    }
    $results | Select-Object symbol, interval, fetched, persisted, pages | Format-Table -AutoSize
    $symbols = @($results | Select-Object -ExpandProperty symbol -Unique)
    $intervals = @($results | Select-Object -ExpandProperty interval -Unique)
    Write-Host "Synced pairs: $($symbols -join ', '); timeframes: $($intervals -join ', ')."

    foreach ($symbol in $symbols) {
        foreach ($interval in @('1m', '5m', '15m')) {
            if (@($results | Where-Object { $_.symbol -eq $symbol -and $_.interval -eq $interval }).Count -ne 1) {
                throw "Missing sync result for $symbol/$interval. Verify MARKET_DATA_INTERVALS."
            }
        }
    }
    foreach ($r in $results) {
        if ($r.fetched -eq 0) {
            Write-Warning "No new candles fetched for $($r.symbol)/$($r.interval). Review testnet availability."
        }
    }
    Write-Host 'Sync finished. The backtest validates 3000 consecutive 1m candles and warm-up context.'
    Write-Host 'If the replay returns HTTP 422 (gaps/insufficient context), sync older windows explicitly.'
}
catch {
    Write-Error ("Historical sync failed: " + $_.Exception.Message)
    throw
}
