<#
.SYNOPSIS
  Compare BTCUSDT, ETHUSDT and SOLUSDT using identical replay cutoffs.
.DESCRIPTION
  Requires running local app and preloaded continuous market data.
  Obtains each USD-M PERPETUAL symbol's filters from Binance Futures TESTNET
  exchangeInfo; NEVER assumes BTC tick/quantity applies to SOL/ETH.
  Runs Java-only, no orders and no OpenAI calls. Records portfolio metrics to CSV.
.EXAMPLE
  .\scripts\compare-pair-backtests.ps1
.EXAMPLE
  .\scripts\compare-pair-backtests.ps1 -Symbols SOLUSDT -CostLimits 0,30,100
.EXAMPLE
  .\scripts\compare-pair-backtests.ps1 -AsOf 1791823439999 -Steps 3000
.EXAMPLE
  .\scripts\compare-pair-backtests.ps1 -PrintOnly
#>
[CmdletBinding()]
param(
    [ValidateSet('BTCUSDT', 'ETHUSDT', 'SOLUSDT')]
    [string[]]$Symbols = @('BTCUSDT', 'ETHUSDT', 'SOLUSDT'),

    [ValidateRange(2, 3000)]
    [int]$Steps = 3000,

    [ValidateRange(21, 500)]
    [int]$ContextLimit = 200,

    [ValidateScript({ $_.Count -gt 0 -and @($_ | Where-Object { $_ -lt 0 -or $_ -gt 100 }).Count -eq 0 })]
    [int[]]$CostLimits = @(0, 30, 50, 75, 100),

    [ValidateRange(0, [long]::MaxValue)]
    [long]$AsOf = 0,

    [ValidatePattern('^https?://(127\.0\.0\.1|localhost)(:\d+)?$')]
    [string]$BaseUrl = 'http://127.0.0.1:8080',

    [string]$ExchangeInfoUrl = 'https://demo-fapi.binance.com/fapi/v1/exchangeInfo',

    [ValidateRange(30, 1800)]
    [int]$TimeoutSec = 900,

    [switch]$PrintOnly,
    [switch]$NoCsv
)

$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')

if ($PrintOnly) {
    Write-Output "SYMBOLS=$($Symbols -join ',')"
    Write-Output "COST_LIMITS=$($CostLimits -join ',')"
    Write-Output "STEPS=$Steps"
    Write-Output "CONTEXT_LIMIT=$ContextLimit"
    Write-Output 'LUNA=false'
    Write-Output "AS_OF=$(if ($AsOf -gt 0) { $AsOf } else { '<latest common stored 1m candle>' })"
    Write-Output "EXCHANGE_INFO=$ExchangeInfoUrl"
    return
}

function Parse-PositiveDecimal {
    param([object]$Value, [string]$Name)
    if ($null -eq $Value -or [string]::IsNullOrWhiteSpace([string]$Value)) {
        throw "Missing required Binance exchangeInfo filter: $Name"
    }
    $parsed = [decimal]::Parse([string]$Value, [System.Globalization.CultureInfo]::InvariantCulture)
    if ($parsed -le 0) { throw "Invalid non-positive Binance filter $Name=$Value" }
    return $parsed
}

function Read-OptionalPositiveDecimal {
    param([object]$Value, [string]$Name)
    if ($null -eq $Value -or [string]::IsNullOrWhiteSpace([string]$Value)) {
        return $null
    }
    $parsed = Parse-PositiveDecimal $Value $Name
    return $parsed
}

function Symbol-ContractFilters {
    param([object]$ExchangeInfo, [string]$Symbol)
    $matches = @($ExchangeInfo.symbols | Where-Object { $_.symbol -eq $Symbol })
    if ($matches.Count -ne 1) {
        throw "Binance TESTNET exchangeInfo does not contain unique symbol $Symbol"
    }
    $row = $matches[0]
    if ($row.status -ne 'TRADING' -or $row.contractType -ne 'PERPETUAL' -or $row.quoteAsset -ne 'USDT') {
        throw "$Symbol is not an active Binance TESTNET USD-M perpetual contract"
    }
    $pf = @($row.filters | Where-Object { $_.filterType -eq 'PRICE_FILTER' } | Select-Object -First 1)
    $lot = @($row.filters | Where-Object { $_.filterType -eq 'LOT_SIZE' } | Select-Object -First 1)
    $marketLot = @($row.filters | Where-Object { $_.filterType -eq 'MARKET_LOT_SIZE' } | Select-Object -First 1)
    if ($pf.Count -ne 1 -or $lot.Count -ne 1) {
        throw "PRICE_FILTER and LOT_SIZE are required for $Symbol"
    }
    $tick = Parse-PositiveDecimal $pf[0].tickSize "$Symbol.PRICE_FILTER.tickSize"

    $steps = @()
    $mins = @()
    foreach ($filter in @($lot[0]) + $marketLot) {
        if ($null -ne $filter.stepSize -and
                [string]$filter.stepSize -notmatch '^0+(\.0+)?
    }
    if ($steps.Count -eq 0 -or $mins.Count -eq 0) {
        throw "Missing positive LOT_SIZE/MARKET_LOT_SIZE quantities for $Symbol"
    }
    # Conservative constraints: quantities must meet both lot and market-lot limits.
    $step = ($steps | Measure-Object -Maximum).Maximum
    $minQty = ($mins | Measure-Object -Maximum).Maximum

    $minNotionals = @()
    foreach ($notional in @($row.filters | Where-Object {
                $_.filterType -eq 'MIN_NOTIONAL' -or $_.filterType -eq 'NOTIONAL' })) {
        foreach ($field in @('notional', 'minNotional')) {
            if ($null -ne $notional.$field -and
                    [string]$notional.$field -notmatch '^0+(\.0+)?
        }
    }
    if ($minNotionals.Count -eq 0) {
        throw "No positive MIN_NOTIONAL/NOTIONAL filter found for $Symbol. Do not assume a value."
    }
    $minNotional = ($minNotionals | Measure-Object -Maximum).Maximum
    $culture = [System.Globalization.CultureInfo]::InvariantCulture

    return [PSCustomObject]@{
        symbol = $Symbol
        priceTick = ([decimal]$tick).ToString($culture)
        quantityStep = ([decimal]$step).ToString($culture)
        minimumQuantity = ([decimal]$minQty).ToString($culture)
        minimumNotionalUsdt = ([decimal]$minNotional).ToString($culture)
    }
}

try {
    $health = Invoke-RestMethod -Uri "$BaseUrl/actuator/health" -TimeoutSec 15 -ErrorAction Stop
    if ($health.status -ne 'UP') { throw "The local application is not ready: $($health.status)" }

    $status = Invoke-RestMethod -Uri "$BaseUrl/api/v1/risk/status" -TimeoutSec 15 -ErrorAction Stop
    if (-not $status.configured) {
        throw "Risk configuration is incomplete. Start with scripts/start-backtest.ps1."
    }
    $backtest = Invoke-RestMethod -Uri "$BaseUrl/api/v1/backtest/status" -TimeoutSec 15 -ErrorAction Stop
    if ($backtest.ordersEnabled -or $backtest.mode -ne 'SIMULATION_ONLY') {
        throw 'Backtest endpoint is not confirmed simulation-only; aborting.'
    }

    Write-Host "Fetching USD-M testnet exchangeInfo: $ExchangeInfoUrl"
    $exchange = Invoke-RestMethod -Uri $ExchangeInfoUrl -TimeoutSec 60 -ErrorAction Stop
    $filters = @($Symbols | ForEach-Object { Symbol-ContractFilters -ExchangeInfo $exchange -Symbol $_ })
    $filters | Format-Table -AutoSize

    if ($AsOf -eq 0) {
        $latest = foreach ($symbol in $Symbols) {
            $uri = "$BaseUrl/api/v1/market-data/candles?symbol=$symbol&interval=1m&limit=1"
            $rows = @(Invoke-RestMethod -Uri $uri -TimeoutSec 60 -ErrorAction Stop)
            if ($rows.Count -ne 1 -or -not $rows[0].closed) {
                throw "No closed 1m candle for $symbol in local database; run sync-backtest-data.ps1 first."
            }
            [long]$rows[0].closeTime
        }
        $AsOf = [long](($latest | Measure-Object -Minimum).Minimum)
    }

    Write-Host "Common cutoff (asOf UTC epoch ms): $AsOf"
    Write-Host "Replaying $Steps closed 1m candles per pair, context $ContextLimit (5m/15m)."
    $results = @()
    foreach ($filter in $filters) {
        foreach ($limit in $CostLimits) {
            $payload = @{
                symbol = $filter.symbol
                intervals = @('1m','5m','15m')
                steps = $Steps
                contextLimit = $ContextLimit
                asOf = $AsOf
                includeLuna = $false
                priceTick = $filter.priceTick
                quantityStep = $filter.quantityStep
                minimumQuantity = $filter.minimumQuantity
                minimumNotionalUsdt = $filter.minimumNotionalUsdt
                breakEvenTriggerROverride = '1.0'
                maxCostToInitialRiskPctOverride = $limit
            } | ConvertTo-Json -Depth 8

            Write-Host "Testing $($filter.symbol), cost/risk $limit% ..."
            try {
                $run = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/backtest/run" `
                    -ContentType 'application/json' -Body $payload `
                    -TimeoutSec $TimeoutSec -ErrorAction Stop
                if ($run.symbol -ne $filter.symbol -or $run.replayedCandles -ne $Steps) {
                    throw "Unexpected replay response for $($filter.symbol)"
                }
                $p = $run.javaPortfolio
                $costBlocked = @($run.javaDecisionHistory |
                    Where-Object { $_.riskReason -eq 'COST_TO_RISK_TOO_HIGH' }).Count
                $results += [PSCustomObject]@{
                    Pair = $filter.symbol
                    LimitPct = $limit
                    AsOf = $AsOf
                    ReturnPct = [math]::Round([double]$p.totalReturnPct, 4)
                    DrawdownPct = [math]::Round([double]$p.maxDrawdownPct, 4)
                    EntrySignals = $p.entrySignals
                    Blocked = $p.entriesBlocked
                    BlockedByCostAtDecision = $costBlocked
                    Closed = $p.tradesClosed
                    Wins = $p.winningTrades
                    Losses = $p.losingTrades
                    Open = $p.openPositionSide
                    Status = 'OK'
                    Error = ''
                }
            }
            catch {
                $message = $_.Exception.Message
                if ($_.ErrorDetails -and $_.ErrorDetails.Message) {
                    $message += ' ' + $_.ErrorDetails.Message
                }
                Write-Warning "Backtest failed for $($filter.symbol), cost $limit%: $message"
                $results += [PSCustomObject]@{
                    Pair = $filter.symbol
                    LimitPct = $limit
                    AsOf = $AsOf
                    ReturnPct = $null
                    DrawdownPct = $null
                    EntrySignals = $null
                    Blocked = $null
                    BlockedByCostAtDecision = $null
                    Closed = $null
                    Wins = $null
                    Losses = $null
                    Open = $null
                    Status = 'ERROR'
                    Error = $message
                }
            }
        }
    }

    $results | Format-Table Pair, LimitPct, ReturnPct, DrawdownPct, EntrySignals,
        Blocked, Closed, Wins, Losses, Open, Status -AutoSize

    if (-not $NoCsv) {
        $repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
        $outputDir = Join-Path $repoRoot 'results'
        New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
        $output = Join-Path $outputDir ("pair-comparison-{0}.csv" -f $AsOf)
        $results | Export-Csv -Path $output -NoTypeInformation -Encoding UTF8
        Write-Host "CSV saved: $output"
    }
    if (@($results | Where-Object { $_.Status -eq 'ERROR' }).Count -gt 0) {
        throw 'At least one replay failed. Check Status=ERROR and Error column/CSV before comparing.'
    }
}
catch {
    Write-Error ("Pair comparison failed: " + $_.Exception.Message)
    throw
}
) {
            $steps += (Read-OptionalPositiveDecimal $filter.stepSize "$Symbol.quantityStep")
        }
        if ($null -ne $filter.minQty -and
                [string]$filter.minQty -notmatch '^0+(\.0+)?
    }
    if ($steps.Count -eq 0 -or $mins.Count -eq 0) {
        throw "Missing positive LOT_SIZE/MARKET_LOT_SIZE quantities for $Symbol"
    }
    # Conservative constraints: quantities must meet both lot and market-lot limits.
    $step = ($steps | Measure-Object -Maximum).Maximum
    $minQty = ($mins | Measure-Object -Maximum).Maximum

    $minNotionals = @()
    foreach ($notional in @($row.filters | Where-Object {
                $_.filterType -eq 'MIN_NOTIONAL' -or $_.filterType -eq 'NOTIONAL' })) {
        foreach ($field in @('notional', 'minNotional')) {
            if ($null -ne $notional.$field -and [decimal]$notional.$field -gt 0) {
                $minNotionals += (Parse-PositiveDecimal $notional.$field "$Symbol.$field")
            }
        }
    }
    if ($minNotionals.Count -eq 0) {
        throw "No positive MIN_NOTIONAL/NOTIONAL filter found for $Symbol. Do not assume a value."
    }
    $minNotional = ($minNotionals | Measure-Object -Maximum).Maximum
    $culture = [System.Globalization.CultureInfo]::InvariantCulture

    return [PSCustomObject]@{
        symbol = $Symbol
        priceTick = ([decimal]$tick).ToString($culture)
        quantityStep = ([decimal]$step).ToString($culture)
        minimumQuantity = ([decimal]$minQty).ToString($culture)
        minimumNotionalUsdt = ([decimal]$minNotional).ToString($culture)
    }
}

try {
    $health = Invoke-RestMethod -Uri "$BaseUrl/actuator/health" -TimeoutSec 15 -ErrorAction Stop
    if ($health.status -ne 'UP') { throw "The local application is not ready: $($health.status)" }

    $status = Invoke-RestMethod -Uri "$BaseUrl/api/v1/risk/status" -TimeoutSec 15 -ErrorAction Stop
    if (-not $status.configured) {
        throw "Risk configuration is incomplete. Start with scripts/start-backtest.ps1."
    }
    $backtest = Invoke-RestMethod -Uri "$BaseUrl/api/v1/backtest/status" -TimeoutSec 15 -ErrorAction Stop
    if ($backtest.ordersEnabled -or $backtest.mode -ne 'SIMULATION_ONLY') {
        throw 'Backtest endpoint is not confirmed simulation-only; aborting.'
    }

    Write-Host "Fetching USD-M testnet exchangeInfo: $ExchangeInfoUrl"
    $exchange = Invoke-RestMethod -Uri $ExchangeInfoUrl -TimeoutSec 60 -ErrorAction Stop
    $filters = @($Symbols | ForEach-Object { Symbol-ContractFilters -ExchangeInfo $exchange -Symbol $_ })
    $filters | Format-Table -AutoSize

    if ($AsOf -eq 0) {
        $latest = foreach ($symbol in $Symbols) {
            $uri = "$BaseUrl/api/v1/market-data/candles?symbol=$symbol&interval=1m&limit=1"
            $rows = @(Invoke-RestMethod -Uri $uri -TimeoutSec 60 -ErrorAction Stop)
            if ($rows.Count -ne 1 -or -not $rows[0].closed) {
                throw "No closed 1m candle for $symbol in local database; run sync-backtest-data.ps1 first."
            }
            [long]$rows[0].closeTime
        }
        $AsOf = [long](($latest | Measure-Object -Minimum).Minimum)
    }

    Write-Host "Common cutoff (asOf UTC epoch ms): $AsOf"
    Write-Host "Replaying $Steps closed 1m candles per pair, context $ContextLimit (5m/15m)."
    $results = @()
    foreach ($filter in $filters) {
        foreach ($limit in $CostLimits) {
            $payload = @{
                symbol = $filter.symbol
                intervals = @('1m','5m','15m')
                steps = $Steps
                contextLimit = $ContextLimit
                asOf = $AsOf
                includeLuna = $false
                priceTick = $filter.priceTick
                quantityStep = $filter.quantityStep
                minimumQuantity = $filter.minimumQuantity
                minimumNotionalUsdt = $filter.minimumNotionalUsdt
                breakEvenTriggerROverride = '1.0'
                maxCostToInitialRiskPctOverride = $limit
            } | ConvertTo-Json -Depth 8

            Write-Host "Testing $($filter.symbol), cost/risk $limit% ..."
            try {
                $run = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/backtest/run" `
                    -ContentType 'application/json' -Body $payload `
                    -TimeoutSec $TimeoutSec -ErrorAction Stop
                if ($run.symbol -ne $filter.symbol -or $run.replayedCandles -ne $Steps) {
                    throw "Unexpected replay response for $($filter.symbol)"
                }
                $p = $run.javaPortfolio
                $costBlocked = @($run.javaDecisionHistory |
                    Where-Object { $_.riskReason -eq 'COST_TO_RISK_TOO_HIGH' }).Count
                $results += [PSCustomObject]@{
                    Pair = $filter.symbol
                    LimitPct = $limit
                    AsOf = $AsOf
                    ReturnPct = [math]::Round([double]$p.totalReturnPct, 4)
                    DrawdownPct = [math]::Round([double]$p.maxDrawdownPct, 4)
                    EntrySignals = $p.entrySignals
                    Blocked = $p.entriesBlocked
                    BlockedByCostAtDecision = $costBlocked
                    Closed = $p.tradesClosed
                    Wins = $p.winningTrades
                    Losses = $p.losingTrades
                    Open = $p.openPositionSide
                    Status = 'OK'
                    Error = ''
                }
            }
            catch {
                $message = $_.Exception.Message
                if ($_.ErrorDetails -and $_.ErrorDetails.Message) {
                    $message += ' ' + $_.ErrorDetails.Message
                }
                Write-Warning "Backtest failed for $($filter.symbol), cost $limit%: $message"
                $results += [PSCustomObject]@{
                    Pair = $filter.symbol
                    LimitPct = $limit
                    AsOf = $AsOf
                    ReturnPct = $null
                    DrawdownPct = $null
                    EntrySignals = $null
                    Blocked = $null
                    BlockedByCostAtDecision = $null
                    Closed = $null
                    Wins = $null
                    Losses = $null
                    Open = $null
                    Status = 'ERROR'
                    Error = $message
                }
            }
        }
    }

    $results | Format-Table Pair, LimitPct, ReturnPct, DrawdownPct, EntrySignals,
        Blocked, Closed, Wins, Losses, Open, Status -AutoSize

    if (-not $NoCsv) {
        $repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
        $outputDir = Join-Path $repoRoot 'results'
        New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
        $output = Join-Path $outputDir ("pair-comparison-{0}.csv" -f $AsOf)
        $results | Export-Csv -Path $output -NoTypeInformation -Encoding UTF8
        Write-Host "CSV saved: $output"
    }
    if (@($results | Where-Object { $_.Status -eq 'ERROR' }).Count -gt 0) {
        throw 'At least one replay failed. Check Status=ERROR and Error column/CSV before comparing.'
    }
}
catch {
    Write-Error ("Pair comparison failed: " + $_.Exception.Message)
    throw
}
) {
            $mins += (Read-OptionalPositiveDecimal $filter.minQty "$Symbol.minimumQuantity")
        }
    }
    if ($steps.Count -eq 0 -or $mins.Count -eq 0) {
        throw "Missing positive LOT_SIZE/MARKET_LOT_SIZE quantities for $Symbol"
    }
    # Conservative constraints: quantities must meet both lot and market-lot limits.
    $step = ($steps | Measure-Object -Maximum).Maximum
    $minQty = ($mins | Measure-Object -Maximum).Maximum

    $minNotionals = @()
    foreach ($notional in @($row.filters | Where-Object {
                $_.filterType -eq 'MIN_NOTIONAL' -or $_.filterType -eq 'NOTIONAL' })) {
        foreach ($field in @('notional', 'minNotional')) {
            if ($null -ne $notional.$field -and [decimal]$notional.$field -gt 0) {
                $minNotionals += (Parse-PositiveDecimal $notional.$field "$Symbol.$field")
            }
        }
    }
    if ($minNotionals.Count -eq 0) {
        throw "No positive MIN_NOTIONAL/NOTIONAL filter found for $Symbol. Do not assume a value."
    }
    $minNotional = ($minNotionals | Measure-Object -Maximum).Maximum
    $culture = [System.Globalization.CultureInfo]::InvariantCulture

    return [PSCustomObject]@{
        symbol = $Symbol
        priceTick = ([decimal]$tick).ToString($culture)
        quantityStep = ([decimal]$step).ToString($culture)
        minimumQuantity = ([decimal]$minQty).ToString($culture)
        minimumNotionalUsdt = ([decimal]$minNotional).ToString($culture)
    }
}

try {
    $health = Invoke-RestMethod -Uri "$BaseUrl/actuator/health" -TimeoutSec 15 -ErrorAction Stop
    if ($health.status -ne 'UP') { throw "The local application is not ready: $($health.status)" }

    $status = Invoke-RestMethod -Uri "$BaseUrl/api/v1/risk/status" -TimeoutSec 15 -ErrorAction Stop
    if (-not $status.configured) {
        throw "Risk configuration is incomplete. Start with scripts/start-backtest.ps1."
    }
    $backtest = Invoke-RestMethod -Uri "$BaseUrl/api/v1/backtest/status" -TimeoutSec 15 -ErrorAction Stop
    if ($backtest.ordersEnabled -or $backtest.mode -ne 'SIMULATION_ONLY') {
        throw 'Backtest endpoint is not confirmed simulation-only; aborting.'
    }

    Write-Host "Fetching USD-M testnet exchangeInfo: $ExchangeInfoUrl"
    $exchange = Invoke-RestMethod -Uri $ExchangeInfoUrl -TimeoutSec 60 -ErrorAction Stop
    $filters = @($Symbols | ForEach-Object { Symbol-ContractFilters -ExchangeInfo $exchange -Symbol $_ })
    $filters | Format-Table -AutoSize

    if ($AsOf -eq 0) {
        $latest = foreach ($symbol in $Symbols) {
            $uri = "$BaseUrl/api/v1/market-data/candles?symbol=$symbol&interval=1m&limit=1"
            $rows = @(Invoke-RestMethod -Uri $uri -TimeoutSec 60 -ErrorAction Stop)
            if ($rows.Count -ne 1 -or -not $rows[0].closed) {
                throw "No closed 1m candle for $symbol in local database; run sync-backtest-data.ps1 first."
            }
            [long]$rows[0].closeTime
        }
        $AsOf = [long](($latest | Measure-Object -Minimum).Minimum)
    }

    Write-Host "Common cutoff (asOf UTC epoch ms): $AsOf"
    Write-Host "Replaying $Steps closed 1m candles per pair, context $ContextLimit (5m/15m)."
    $results = @()
    foreach ($filter in $filters) {
        foreach ($limit in $CostLimits) {
            $payload = @{
                symbol = $filter.symbol
                intervals = @('1m','5m','15m')
                steps = $Steps
                contextLimit = $ContextLimit
                asOf = $AsOf
                includeLuna = $false
                priceTick = $filter.priceTick
                quantityStep = $filter.quantityStep
                minimumQuantity = $filter.minimumQuantity
                minimumNotionalUsdt = $filter.minimumNotionalUsdt
                breakEvenTriggerROverride = '1.0'
                maxCostToInitialRiskPctOverride = $limit
            } | ConvertTo-Json -Depth 8

            Write-Host "Testing $($filter.symbol), cost/risk $limit% ..."
            try {
                $run = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/backtest/run" `
                    -ContentType 'application/json' -Body $payload `
                    -TimeoutSec $TimeoutSec -ErrorAction Stop
                if ($run.symbol -ne $filter.symbol -or $run.replayedCandles -ne $Steps) {
                    throw "Unexpected replay response for $($filter.symbol)"
                }
                $p = $run.javaPortfolio
                $costBlocked = @($run.javaDecisionHistory |
                    Where-Object { $_.riskReason -eq 'COST_TO_RISK_TOO_HIGH' }).Count
                $results += [PSCustomObject]@{
                    Pair = $filter.symbol
                    LimitPct = $limit
                    AsOf = $AsOf
                    ReturnPct = [math]::Round([double]$p.totalReturnPct, 4)
                    DrawdownPct = [math]::Round([double]$p.maxDrawdownPct, 4)
                    EntrySignals = $p.entrySignals
                    Blocked = $p.entriesBlocked
                    BlockedByCostAtDecision = $costBlocked
                    Closed = $p.tradesClosed
                    Wins = $p.winningTrades
                    Losses = $p.losingTrades
                    Open = $p.openPositionSide
                    Status = 'OK'
                    Error = ''
                }
            }
            catch {
                $message = $_.Exception.Message
                if ($_.ErrorDetails -and $_.ErrorDetails.Message) {
                    $message += ' ' + $_.ErrorDetails.Message
                }
                Write-Warning "Backtest failed for $($filter.symbol), cost $limit%: $message"
                $results += [PSCustomObject]@{
                    Pair = $filter.symbol
                    LimitPct = $limit
                    AsOf = $AsOf
                    ReturnPct = $null
                    DrawdownPct = $null
                    EntrySignals = $null
                    Blocked = $null
                    BlockedByCostAtDecision = $null
                    Closed = $null
                    Wins = $null
                    Losses = $null
                    Open = $null
                    Status = 'ERROR'
                    Error = $message
                }
            }
        }
    }

    $results | Format-Table Pair, LimitPct, ReturnPct, DrawdownPct, EntrySignals,
        Blocked, Closed, Wins, Losses, Open, Status -AutoSize

    if (-not $NoCsv) {
        $repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
        $outputDir = Join-Path $repoRoot 'results'
        New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
        $output = Join-Path $outputDir ("pair-comparison-{0}.csv" -f $AsOf)
        $results | Export-Csv -Path $output -NoTypeInformation -Encoding UTF8
        Write-Host "CSV saved: $output"
    }
    if (@($results | Where-Object { $_.Status -eq 'ERROR' }).Count -gt 0) {
        throw 'At least one replay failed. Check Status=ERROR and Error column/CSV before comparing.'
    }
}
catch {
    Write-Error ("Pair comparison failed: " + $_.Exception.Message)
    throw
}
) {
                $minNotionals += (Read-OptionalPositiveDecimal $notional.$field "$Symbol.$field")
            }
        }
    }
    if ($minNotionals.Count -eq 0) {
        throw "No positive MIN_NOTIONAL/NOTIONAL filter found for $Symbol. Do not assume a value."
    }
    $minNotional = ($minNotionals | Measure-Object -Maximum).Maximum
    $culture = [System.Globalization.CultureInfo]::InvariantCulture

    return [PSCustomObject]@{
        symbol = $Symbol
        priceTick = ([decimal]$tick).ToString($culture)
        quantityStep = ([decimal]$step).ToString($culture)
        minimumQuantity = ([decimal]$minQty).ToString($culture)
        minimumNotionalUsdt = ([decimal]$minNotional).ToString($culture)
    }
}

try {
    $health = Invoke-RestMethod -Uri "$BaseUrl/actuator/health" -TimeoutSec 15 -ErrorAction Stop
    if ($health.status -ne 'UP') { throw "The local application is not ready: $($health.status)" }

    $status = Invoke-RestMethod -Uri "$BaseUrl/api/v1/risk/status" -TimeoutSec 15 -ErrorAction Stop
    if (-not $status.configured) {
        throw "Risk configuration is incomplete. Start with scripts/start-backtest.ps1."
    }
    $backtest = Invoke-RestMethod -Uri "$BaseUrl/api/v1/backtest/status" -TimeoutSec 15 -ErrorAction Stop
    if ($backtest.ordersEnabled -or $backtest.mode -ne 'SIMULATION_ONLY') {
        throw 'Backtest endpoint is not confirmed simulation-only; aborting.'
    }

    Write-Host "Fetching USD-M testnet exchangeInfo: $ExchangeInfoUrl"
    $exchange = Invoke-RestMethod -Uri $ExchangeInfoUrl -TimeoutSec 60 -ErrorAction Stop
    $filters = @($Symbols | ForEach-Object { Symbol-ContractFilters -ExchangeInfo $exchange -Symbol $_ })
    $filters | Format-Table -AutoSize

    if ($AsOf -eq 0) {
        $latest = foreach ($symbol in $Symbols) {
            $uri = "$BaseUrl/api/v1/market-data/candles?symbol=$symbol&interval=1m&limit=1"
            $rows = @(Invoke-RestMethod -Uri $uri -TimeoutSec 60 -ErrorAction Stop)
            if ($rows.Count -ne 1 -or -not $rows[0].closed) {
                throw "No closed 1m candle for $symbol in local database; run sync-backtest-data.ps1 first."
            }
            [long]$rows[0].closeTime
        }
        $AsOf = [long](($latest | Measure-Object -Minimum).Minimum)
    }

    Write-Host "Common cutoff (asOf UTC epoch ms): $AsOf"
    Write-Host "Replaying $Steps closed 1m candles per pair, context $ContextLimit (5m/15m)."
    $results = @()
    foreach ($filter in $filters) {
        foreach ($limit in $CostLimits) {
            $payload = @{
                symbol = $filter.symbol
                intervals = @('1m','5m','15m')
                steps = $Steps
                contextLimit = $ContextLimit
                asOf = $AsOf
                includeLuna = $false
                priceTick = $filter.priceTick
                quantityStep = $filter.quantityStep
                minimumQuantity = $filter.minimumQuantity
                minimumNotionalUsdt = $filter.minimumNotionalUsdt
                breakEvenTriggerROverride = '1.0'
                maxCostToInitialRiskPctOverride = $limit
            } | ConvertTo-Json -Depth 8

            Write-Host "Testing $($filter.symbol), cost/risk $limit% ..."
            try {
                $run = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/backtest/run" `
                    -ContentType 'application/json' -Body $payload `
                    -TimeoutSec $TimeoutSec -ErrorAction Stop
                if ($run.symbol -ne $filter.symbol -or $run.replayedCandles -ne $Steps) {
                    throw "Unexpected replay response for $($filter.symbol)"
                }
                $p = $run.javaPortfolio
                $costBlocked = @($run.javaDecisionHistory |
                    Where-Object { $_.riskReason -eq 'COST_TO_RISK_TOO_HIGH' }).Count
                $results += [PSCustomObject]@{
                    Pair = $filter.symbol
                    LimitPct = $limit
                    AsOf = $AsOf
                    ReturnPct = [math]::Round([double]$p.totalReturnPct, 4)
                    DrawdownPct = [math]::Round([double]$p.maxDrawdownPct, 4)
                    EntrySignals = $p.entrySignals
                    Blocked = $p.entriesBlocked
                    BlockedByCostAtDecision = $costBlocked
                    Closed = $p.tradesClosed
                    Wins = $p.winningTrades
                    Losses = $p.losingTrades
                    Open = $p.openPositionSide
                    Status = 'OK'
                    Error = ''
                }
            }
            catch {
                $message = $_.Exception.Message
                if ($_.ErrorDetails -and $_.ErrorDetails.Message) {
                    $message += ' ' + $_.ErrorDetails.Message
                }
                Write-Warning "Backtest failed for $($filter.symbol), cost $limit%: $message"
                $results += [PSCustomObject]@{
                    Pair = $filter.symbol
                    LimitPct = $limit
                    AsOf = $AsOf
                    ReturnPct = $null
                    DrawdownPct = $null
                    EntrySignals = $null
                    Blocked = $null
                    BlockedByCostAtDecision = $null
                    Closed = $null
                    Wins = $null
                    Losses = $null
                    Open = $null
                    Status = 'ERROR'
                    Error = $message
                }
            }
        }
    }

    $results | Format-Table Pair, LimitPct, ReturnPct, DrawdownPct, EntrySignals,
        Blocked, Closed, Wins, Losses, Open, Status -AutoSize

    if (-not $NoCsv) {
        $repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
        $outputDir = Join-Path $repoRoot 'results'
        New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
        $output = Join-Path $outputDir ("pair-comparison-{0}.csv" -f $AsOf)
        $results | Export-Csv -Path $output -NoTypeInformation -Encoding UTF8
        Write-Host "CSV saved: $output"
    }
    if (@($results | Where-Object { $_.Status -eq 'ERROR' }).Count -gt 0) {
        throw 'At least one replay failed. Check Status=ERROR and Error column/CSV before comparing.'
    }
}
catch {
    Write-Error ("Pair comparison failed: " + $_.Exception.Message)
    throw
}
) {
            $steps += (Read-OptionalPositiveDecimal $filter.stepSize "$Symbol.quantityStep")
        }
        if ($null -ne $filter.minQty -and
                [string]$filter.minQty -notmatch '^0+(\.0+)?
    }
    if ($steps.Count -eq 0 -or $mins.Count -eq 0) {
        throw "Missing positive LOT_SIZE/MARKET_LOT_SIZE quantities for $Symbol"
    }
    # Conservative constraints: quantities must meet both lot and market-lot limits.
    $step = ($steps | Measure-Object -Maximum).Maximum
    $minQty = ($mins | Measure-Object -Maximum).Maximum

    $minNotionals = @()
    foreach ($notional in @($row.filters | Where-Object {
                $_.filterType -eq 'MIN_NOTIONAL' -or $_.filterType -eq 'NOTIONAL' })) {
        foreach ($field in @('notional', 'minNotional')) {
            if ($null -ne $notional.$field -and [decimal]$notional.$field -gt 0) {
                $minNotionals += (Parse-PositiveDecimal $notional.$field "$Symbol.$field")
            }
        }
    }
    if ($minNotionals.Count -eq 0) {
        throw "No positive MIN_NOTIONAL/NOTIONAL filter found for $Symbol. Do not assume a value."
    }
    $minNotional = ($minNotionals | Measure-Object -Maximum).Maximum
    $culture = [System.Globalization.CultureInfo]::InvariantCulture

    return [PSCustomObject]@{
        symbol = $Symbol
        priceTick = ([decimal]$tick).ToString($culture)
        quantityStep = ([decimal]$step).ToString($culture)
        minimumQuantity = ([decimal]$minQty).ToString($culture)
        minimumNotionalUsdt = ([decimal]$minNotional).ToString($culture)
    }
}

try {
    $health = Invoke-RestMethod -Uri "$BaseUrl/actuator/health" -TimeoutSec 15 -ErrorAction Stop
    if ($health.status -ne 'UP') { throw "The local application is not ready: $($health.status)" }

    $status = Invoke-RestMethod -Uri "$BaseUrl/api/v1/risk/status" -TimeoutSec 15 -ErrorAction Stop
    if (-not $status.configured) {
        throw "Risk configuration is incomplete. Start with scripts/start-backtest.ps1."
    }
    $backtest = Invoke-RestMethod -Uri "$BaseUrl/api/v1/backtest/status" -TimeoutSec 15 -ErrorAction Stop
    if ($backtest.ordersEnabled -or $backtest.mode -ne 'SIMULATION_ONLY') {
        throw 'Backtest endpoint is not confirmed simulation-only; aborting.'
    }

    Write-Host "Fetching USD-M testnet exchangeInfo: $ExchangeInfoUrl"
    $exchange = Invoke-RestMethod -Uri $ExchangeInfoUrl -TimeoutSec 60 -ErrorAction Stop
    $filters = @($Symbols | ForEach-Object { Symbol-ContractFilters -ExchangeInfo $exchange -Symbol $_ })
    $filters | Format-Table -AutoSize

    if ($AsOf -eq 0) {
        $latest = foreach ($symbol in $Symbols) {
            $uri = "$BaseUrl/api/v1/market-data/candles?symbol=$symbol&interval=1m&limit=1"
            $rows = @(Invoke-RestMethod -Uri $uri -TimeoutSec 60 -ErrorAction Stop)
            if ($rows.Count -ne 1 -or -not $rows[0].closed) {
                throw "No closed 1m candle for $symbol in local database; run sync-backtest-data.ps1 first."
            }
            [long]$rows[0].closeTime
        }
        $AsOf = [long](($latest | Measure-Object -Minimum).Minimum)
    }

    Write-Host "Common cutoff (asOf UTC epoch ms): $AsOf"
    Write-Host "Replaying $Steps closed 1m candles per pair, context $ContextLimit (5m/15m)."
    $results = @()
    foreach ($filter in $filters) {
        foreach ($limit in $CostLimits) {
            $payload = @{
                symbol = $filter.symbol
                intervals = @('1m','5m','15m')
                steps = $Steps
                contextLimit = $ContextLimit
                asOf = $AsOf
                includeLuna = $false
                priceTick = $filter.priceTick
                quantityStep = $filter.quantityStep
                minimumQuantity = $filter.minimumQuantity
                minimumNotionalUsdt = $filter.minimumNotionalUsdt
                breakEvenTriggerROverride = '1.0'
                maxCostToInitialRiskPctOverride = $limit
            } | ConvertTo-Json -Depth 8

            Write-Host "Testing $($filter.symbol), cost/risk $limit% ..."
            try {
                $run = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/backtest/run" `
                    -ContentType 'application/json' -Body $payload `
                    -TimeoutSec $TimeoutSec -ErrorAction Stop
                if ($run.symbol -ne $filter.symbol -or $run.replayedCandles -ne $Steps) {
                    throw "Unexpected replay response for $($filter.symbol)"
                }
                $p = $run.javaPortfolio
                $costBlocked = @($run.javaDecisionHistory |
                    Where-Object { $_.riskReason -eq 'COST_TO_RISK_TOO_HIGH' }).Count
                $results += [PSCustomObject]@{
                    Pair = $filter.symbol
                    LimitPct = $limit
                    AsOf = $AsOf
                    ReturnPct = [math]::Round([double]$p.totalReturnPct, 4)
                    DrawdownPct = [math]::Round([double]$p.maxDrawdownPct, 4)
                    EntrySignals = $p.entrySignals
                    Blocked = $p.entriesBlocked
                    BlockedByCostAtDecision = $costBlocked
                    Closed = $p.tradesClosed
                    Wins = $p.winningTrades
                    Losses = $p.losingTrades
                    Open = $p.openPositionSide
                    Status = 'OK'
                    Error = ''
                }
            }
            catch {
                $message = $_.Exception.Message
                if ($_.ErrorDetails -and $_.ErrorDetails.Message) {
                    $message += ' ' + $_.ErrorDetails.Message
                }
                Write-Warning "Backtest failed for $($filter.symbol), cost $limit%: $message"
                $results += [PSCustomObject]@{
                    Pair = $filter.symbol
                    LimitPct = $limit
                    AsOf = $AsOf
                    ReturnPct = $null
                    DrawdownPct = $null
                    EntrySignals = $null
                    Blocked = $null
                    BlockedByCostAtDecision = $null
                    Closed = $null
                    Wins = $null
                    Losses = $null
                    Open = $null
                    Status = 'ERROR'
                    Error = $message
                }
            }
        }
    }

    $results | Format-Table Pair, LimitPct, ReturnPct, DrawdownPct, EntrySignals,
        Blocked, Closed, Wins, Losses, Open, Status -AutoSize

    if (-not $NoCsv) {
        $repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
        $outputDir = Join-Path $repoRoot 'results'
        New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
        $output = Join-Path $outputDir ("pair-comparison-{0}.csv" -f $AsOf)
        $results | Export-Csv -Path $output -NoTypeInformation -Encoding UTF8
        Write-Host "CSV saved: $output"
    }
    if (@($results | Where-Object { $_.Status -eq 'ERROR' }).Count -gt 0) {
        throw 'At least one replay failed. Check Status=ERROR and Error column/CSV before comparing.'
    }
}
catch {
    Write-Error ("Pair comparison failed: " + $_.Exception.Message)
    throw
}
) {
            $mins += (Read-OptionalPositiveDecimal $filter.minQty "$Symbol.minimumQuantity")
        }
    }
    if ($steps.Count -eq 0 -or $mins.Count -eq 0) {
        throw "Missing positive LOT_SIZE/MARKET_LOT_SIZE quantities for $Symbol"
    }
    # Conservative constraints: quantities must meet both lot and market-lot limits.
    $step = ($steps | Measure-Object -Maximum).Maximum
    $minQty = ($mins | Measure-Object -Maximum).Maximum

    $minNotionals = @()
    foreach ($notional in @($row.filters | Where-Object {
                $_.filterType -eq 'MIN_NOTIONAL' -or $_.filterType -eq 'NOTIONAL' })) {
        foreach ($field in @('notional', 'minNotional')) {
            if ($null -ne $notional.$field -and [decimal]$notional.$field -gt 0) {
                $minNotionals += (Parse-PositiveDecimal $notional.$field "$Symbol.$field")
            }
        }
    }
    if ($minNotionals.Count -eq 0) {
        throw "No positive MIN_NOTIONAL/NOTIONAL filter found for $Symbol. Do not assume a value."
    }
    $minNotional = ($minNotionals | Measure-Object -Maximum).Maximum
    $culture = [System.Globalization.CultureInfo]::InvariantCulture

    return [PSCustomObject]@{
        symbol = $Symbol
        priceTick = ([decimal]$tick).ToString($culture)
        quantityStep = ([decimal]$step).ToString($culture)
        minimumQuantity = ([decimal]$minQty).ToString($culture)
        minimumNotionalUsdt = ([decimal]$minNotional).ToString($culture)
    }
}

try {
    $health = Invoke-RestMethod -Uri "$BaseUrl/actuator/health" -TimeoutSec 15 -ErrorAction Stop
    if ($health.status -ne 'UP') { throw "The local application is not ready: $($health.status)" }

    $status = Invoke-RestMethod -Uri "$BaseUrl/api/v1/risk/status" -TimeoutSec 15 -ErrorAction Stop
    if (-not $status.configured) {
        throw "Risk configuration is incomplete. Start with scripts/start-backtest.ps1."
    }
    $backtest = Invoke-RestMethod -Uri "$BaseUrl/api/v1/backtest/status" -TimeoutSec 15 -ErrorAction Stop
    if ($backtest.ordersEnabled -or $backtest.mode -ne 'SIMULATION_ONLY') {
        throw 'Backtest endpoint is not confirmed simulation-only; aborting.'
    }

    Write-Host "Fetching USD-M testnet exchangeInfo: $ExchangeInfoUrl"
    $exchange = Invoke-RestMethod -Uri $ExchangeInfoUrl -TimeoutSec 60 -ErrorAction Stop
    $filters = @($Symbols | ForEach-Object { Symbol-ContractFilters -ExchangeInfo $exchange -Symbol $_ })
    $filters | Format-Table -AutoSize

    if ($AsOf -eq 0) {
        $latest = foreach ($symbol in $Symbols) {
            $uri = "$BaseUrl/api/v1/market-data/candles?symbol=$symbol&interval=1m&limit=1"
            $rows = @(Invoke-RestMethod -Uri $uri -TimeoutSec 60 -ErrorAction Stop)
            if ($rows.Count -ne 1 -or -not $rows[0].closed) {
                throw "No closed 1m candle for $symbol in local database; run sync-backtest-data.ps1 first."
            }
            [long]$rows[0].closeTime
        }
        $AsOf = [long](($latest | Measure-Object -Minimum).Minimum)
    }

    Write-Host "Common cutoff (asOf UTC epoch ms): $AsOf"
    Write-Host "Replaying $Steps closed 1m candles per pair, context $ContextLimit (5m/15m)."
    $results = @()
    foreach ($filter in $filters) {
        foreach ($limit in $CostLimits) {
            $payload = @{
                symbol = $filter.symbol
                intervals = @('1m','5m','15m')
                steps = $Steps
                contextLimit = $ContextLimit
                asOf = $AsOf
                includeLuna = $false
                priceTick = $filter.priceTick
                quantityStep = $filter.quantityStep
                minimumQuantity = $filter.minimumQuantity
                minimumNotionalUsdt = $filter.minimumNotionalUsdt
                breakEvenTriggerROverride = '1.0'
                maxCostToInitialRiskPctOverride = $limit
            } | ConvertTo-Json -Depth 8

            Write-Host "Testing $($filter.symbol), cost/risk $limit% ..."
            try {
                $run = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/backtest/run" `
                    -ContentType 'application/json' -Body $payload `
                    -TimeoutSec $TimeoutSec -ErrorAction Stop
                if ($run.symbol -ne $filter.symbol -or $run.replayedCandles -ne $Steps) {
                    throw "Unexpected replay response for $($filter.symbol)"
                }
                $p = $run.javaPortfolio
                $costBlocked = @($run.javaDecisionHistory |
                    Where-Object { $_.riskReason -eq 'COST_TO_RISK_TOO_HIGH' }).Count
                $results += [PSCustomObject]@{
                    Pair = $filter.symbol
                    LimitPct = $limit
                    AsOf = $AsOf
                    ReturnPct = [math]::Round([double]$p.totalReturnPct, 4)
                    DrawdownPct = [math]::Round([double]$p.maxDrawdownPct, 4)
                    EntrySignals = $p.entrySignals
                    Blocked = $p.entriesBlocked
                    BlockedByCostAtDecision = $costBlocked
                    Closed = $p.tradesClosed
                    Wins = $p.winningTrades
                    Losses = $p.losingTrades
                    Open = $p.openPositionSide
                    Status = 'OK'
                    Error = ''
                }
            }
            catch {
                $message = $_.Exception.Message
                if ($_.ErrorDetails -and $_.ErrorDetails.Message) {
                    $message += ' ' + $_.ErrorDetails.Message
                }
                Write-Warning "Backtest failed for $($filter.symbol), cost $limit%: $message"
                $results += [PSCustomObject]@{
                    Pair = $filter.symbol
                    LimitPct = $limit
                    AsOf = $AsOf
                    ReturnPct = $null
                    DrawdownPct = $null
                    EntrySignals = $null
                    Blocked = $null
                    BlockedByCostAtDecision = $null
                    Closed = $null
                    Wins = $null
                    Losses = $null
                    Open = $null
                    Status = 'ERROR'
                    Error = $message
                }
            }
        }
    }

    $results | Format-Table Pair, LimitPct, ReturnPct, DrawdownPct, EntrySignals,
        Blocked, Closed, Wins, Losses, Open, Status -AutoSize

    if (-not $NoCsv) {
        $repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
        $outputDir = Join-Path $repoRoot 'results'
        New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
        $output = Join-Path $outputDir ("pair-comparison-{0}.csv" -f $AsOf)
        $results | Export-Csv -Path $output -NoTypeInformation -Encoding UTF8
        Write-Host "CSV saved: $output"
    }
    if (@($results | Where-Object { $_.Status -eq 'ERROR' }).Count -gt 0) {
        throw 'At least one replay failed. Check Status=ERROR and Error column/CSV before comparing.'
    }
}
catch {
    Write-Error ("Pair comparison failed: " + $_.Exception.Message)
    throw
}
