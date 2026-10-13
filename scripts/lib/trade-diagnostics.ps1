# Pure offline calculations on flattened closed trades. P&L already includes
# adverse slippage in fill prices and fees: never subtract them a second time.
$script:TradeAuditCulture = [System.Globalization.CultureInfo]::InvariantCulture

function Read-TradeDecimal {
    param([object]$Value, [string]$Field)
    if ($null -eq $Value -or [string]::IsNullOrWhiteSpace([string]$Value)) {
        throw "Missing numeric audit field $Field"
    }
    # PnL CSVs from pt-BR have decimal commas; trades use invariant decimal dots.
    # A comma in these CSVs is ALWAYS a decimal separator, never thousands.
    $literal = ([string]$Value).Trim()
    if ($literal -notmatch '^[+-]?[0-9]+([.,][0-9]+)?\z') {
        throw "Invalid ungrouped decimal for $Field : $literal"
    }
    $normalized = $literal.Replace(',', '.')
    $styles = ([System.Globalization.NumberStyles]::AllowLeadingSign -bor [System.Globalization.NumberStyles]::AllowDecimalPoint)
    return [decimal]::Parse($normalized, $styles, $script:TradeAuditCulture)
}

function Sum-TradeDecimal {
    param([object[]]$Values)
    [decimal]$sum = 0
    foreach ($value in @($Values)) { $sum += [decimal]$value }
    return $sum
}

function Round-TradeDecimal {
    param([decimal]$Value)
    return [math]::Round($Value, 4)
}

function Get-TradeAuditMetrics {
    param(
        [object[]]$Trades,
        [string]$PrimaryInterval,
        [string]$SegmentType = 'ALL',
        [string]$Segment = 'ALL'
    )
    $items = @($Trades)
    [decimal[]]$pnls = @($items | ForEach-Object { Read-TradeDecimal $_.NetPnlUsdt 'NetPnlUsdt' })
    [decimal[]]$wins = @($pnls | Where-Object { $_ -gt 0 })
    [decimal[]]$losses = @($pnls | Where-Object { $_ -lt 0 })
    $grossWin = Sum-TradeDecimal $wins
    $grossLoss = -(Sum-TradeDecimal $losses)
    $net = Sum-TradeDecimal $pnls
    $fees = Sum-TradeDecimal @($items | ForEach-Object { Read-TradeDecimal $_.FeesUsdt 'FeesUsdt' })
    $slippage = Sum-TradeDecimal @($items | ForEach-Object { Read-TradeDecimal $_.SlippageUsdt 'SlippageUsdt' })
    $netRs = Sum-TradeDecimal @($items | ForEach-Object { Read-TradeDecimal $_.NetR 'NetR' })
    $bestThree = Sum-TradeDecimal @($wins | Sort-Object -Descending | Select-Object -First 3)

    $cnt = $items.Count
    $winCount = $wins.Count
    $lossCount = $losses.Count
    return [PSCustomObject]@{
        PrimaryInterval = $PrimaryInterval
        SegmentType = $SegmentType
        Segment = $Segment
        ClosedTrades = $cnt
        Wins = $winCount
        Losses = $lossCount
        Breakevens = $cnt - $winCount - $lossCount
        WinRatePct = if ($cnt) { Round-TradeDecimal (100 * $winCount / $cnt) } else { $null }
        AvgWinUsdt = if ($winCount) { Round-TradeDecimal ($grossWin / $winCount) } else { $null }
        AvgLossUsdt = if ($lossCount) { Round-TradeDecimal (-$grossLoss / $lossCount) } else { $null }
        ExpectancyNetUsdt = if ($cnt) { Round-TradeDecimal ($net / $cnt) } else { $null }
        ExpectancyNetR = if ($cnt) { Round-TradeDecimal ($netRs / $cnt) } else { $null }
        ProfitFactor = if ($grossLoss -gt 0) { Round-TradeDecimal ($grossWin / $grossLoss) } else { $null }
        GrossWinningPnlUsdt = Round-TradeDecimal $grossWin
        GrossLosingPnlUsdt = Round-TradeDecimal $grossLoss
        NetClosedPnlUsdt = Round-TradeDecimal $net
        TotalFeesUsdt = Round-TradeDecimal $fees
        TotalSlippageUsdtDiagnostic = Round-TradeDecimal $slippage
        Top3WinningPnlUsdt = Round-TradeDecimal $bestThree
        Top3ShareOfGrossWinsPct = if ($grossWin -gt 0) { Round-TradeDecimal (100 * $bestThree / $grossWin) } else { $null }
        NetWithoutTop3WinsUsdt = Round-TradeDecimal ($net - $bestThree)
    }
}
) {
        throw "Invalid ungrouped decimal for $Field : $literal"
    }
    $normalized = $literal.Replace(',', '.')
    $styles = ([System.Globalization.NumberStyles]::AllowLeadingSign -bor [System.Globalization.NumberStyles]::AllowDecimalPoint)
    return [decimal]::Parse($normalized, $styles, $script:TradeAuditCulture)
}

function Sum-TradeDecimal {
    param([object[]]$Values)
    [decimal]$sum = 0
    foreach ($value in @($Values)) { $sum += [decimal]$value }
    return $sum
}

function Round-TradeDecimal {
    param([decimal]$Value)
    return [math]::Round($Value, 4)
}

function Get-TradeAuditMetrics {
    param(
        [object[]]$Trades,
        [string]$PrimaryInterval,
        [string]$SegmentType = 'ALL',
        [string]$Segment = 'ALL'
    )
    $items = @($Trades)
    [decimal[]]$pnls = @($items | ForEach-Object { Read-TradeDecimal $_.NetPnlUsdt 'NetPnlUsdt' })
    [decimal[]]$wins = @($pnls | Where-Object { $_ -gt 0 })
    [decimal[]]$losses = @($pnls | Where-Object { $_ -lt 0 })
    $grossWin = Sum-TradeDecimal $wins
    $grossLoss = -(Sum-TradeDecimal $losses)
    $net = Sum-TradeDecimal $pnls
    $fees = Sum-TradeDecimal @($items | ForEach-Object { Read-TradeDecimal $_.FeesUsdt 'FeesUsdt' })
    $slippage = Sum-TradeDecimal @($items | ForEach-Object { Read-TradeDecimal $_.SlippageUsdt 'SlippageUsdt' })
    $netRs = Sum-TradeDecimal @($items | ForEach-Object { Read-TradeDecimal $_.NetR 'NetR' })
    $bestThree = Sum-TradeDecimal @($wins | Sort-Object -Descending | Select-Object -First 3)

    $cnt = $items.Count
    $winCount = $wins.Count
    $lossCount = $losses.Count
    return [PSCustomObject]@{
        PrimaryInterval = $PrimaryInterval
        SegmentType = $SegmentType
        Segment = $Segment
        ClosedTrades = $cnt
        Wins = $winCount
        Losses = $lossCount
        Breakevens = $cnt - $winCount - $lossCount
        WinRatePct = if ($cnt) { Round-TradeDecimal (100 * $winCount / $cnt) } else { $null }
        AvgWinUsdt = if ($winCount) { Round-TradeDecimal ($grossWin / $winCount) } else { $null }
        AvgLossUsdt = if ($lossCount) { Round-TradeDecimal (-$grossLoss / $lossCount) } else { $null }
        ExpectancyNetUsdt = if ($cnt) { Round-TradeDecimal ($net / $cnt) } else { $null }
        ExpectancyNetR = if ($cnt) { Round-TradeDecimal ($netRs / $cnt) } else { $null }
        ProfitFactor = if ($grossLoss -gt 0) { Round-TradeDecimal ($grossWin / $grossLoss) } else { $null }
        GrossWinningPnlUsdt = Round-TradeDecimal $grossWin
        GrossLosingPnlUsdt = Round-TradeDecimal $grossLoss
        NetClosedPnlUsdt = Round-TradeDecimal $net
        TotalFeesUsdt = Round-TradeDecimal $fees
        TotalSlippageUsdtDiagnostic = Round-TradeDecimal $slippage
        Top3WinningPnlUsdt = Round-TradeDecimal $bestThree
        Top3ShareOfGrossWinsPct = if ($grossWin -gt 0) { Round-TradeDecimal (100 * $bestThree / $grossWin) } else { $null }
        NetWithoutTop3WinsUsdt = Round-TradeDecimal ($net - $bestThree)
    }
}
