# Shared Binance USD-M TESTNET filter parsing for research-only scripts.
function Parse-PositiveDecimal {
    param([object]$Value, [string]$Name)
    if ($null -eq $Value -or [string]::IsNullOrWhiteSpace([string]$Value)) {
        throw "Missing required Binance exchangeInfo filter: $Name"
    }
    $parsed = [decimal]::Parse([string]$Value, [System.Globalization.CultureInfo]::InvariantCulture)
    if ($parsed -le 0) { throw "Invalid non-positive Binance filter $Name=$Value" }
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
        if ($null -ne $filter.stepSize -and [string]$filter.stepSize -notmatch '^0+(\.0+)?$') {
            $steps += (Parse-PositiveDecimal $filter.stepSize "$Symbol.quantityStep")
        }
        if ($null -ne $filter.minQty -and [string]$filter.minQty -notmatch '^0+(\.0+)?$') {
            $mins += (Parse-PositiveDecimal $filter.minQty "$Symbol.minimumQuantity")
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
            if ($null -ne $notional.$field -and [string]$notional.$field -notmatch '^0+(\.0+)?$') {
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

