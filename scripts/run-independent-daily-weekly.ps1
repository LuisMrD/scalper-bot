<#
.SYNOPSIS
  Six disjoint 30-closed-daily-bar paper studies, with completed weekly trend.
.DESCRIPTION
  Runs BTC/SOL/DOGE in separate independent research accounts (4 arms each),
  exports windows, aggregate, trades, diagnostics, rejection counts and frozen
  configuration into results/. All strategy/capital/risk parameters come from
  an EXPLICIT editable research JSON. No order placement or paid AI.
  Contract filters are fetched from the same Binance Futures TESTNET source.
.EXAMPLE
  .\scripts\run-independent-daily-weekly.ps1 -PrintOnly -AsOfDateUtc 2026-10-15
.EXAMPLE
  .\scripts\run-independent-daily-weekly.ps1 -AsOfDateUtc 2026-10-15
#>
[CmdletBinding()]
param(
    [ValidatePattern('^https?://(localhost|127\.0\.0\.1)(:\d+)?$')]
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [string]$ConfigPath = (Join-Path $PSScriptRoot 'config/independent-daily-weekly-research.json'),
    [ValidatePattern('^\d{4}-\d{2}-\d{2}$')]
    [string]$AsOfDateUtc,
    [switch]$PrintOnly
)
$ErrorActionPreference = 'Stop'
$base = $BaseUrl.TrimEnd('/')
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$resolvedConfig = (Resolve-Path $ConfigPath).Path
$configText = [System.IO.File]::ReadAllText($resolvedConfig, [Text.Encoding]::UTF8)
$config = $configText | ConvertFrom-Json
if ($null -eq $config -or $null -eq $config.parameters) {
    throw 'Research config must include parameters'
}
$required = @('symbols','windows','steps','contextCandles','capitalUsdt',
    'riskPerTradePct','dailyStopPct','leverage','feePerSidePct',
    'slippagePerSidePct','spreadPct','assumedFundingPer8hPct',
    'assumedMaintenanceMarginPct','maxCostToInitialRiskPct',
    'maxHoldingCandles','parameters')
foreach ($name in $required) {
    if ($null -eq $config.PSObject.Properties[$name] -or
        $null -eq $config.PSObject.Properties[$name].Value) {
        throw "Missing explicit research configuration: $name"
    }
}
$technical = @('pivotLeft','pivotRight','pullbackMinBars','pullbackMaxBars',
    'emaTouchTolerancePct','pullbackVolumeContractionMaxRatio',
    'pullbackBodyContractionMaxRatio','bullishStrongBodyMinRatio',
    'rejectionWickToBodyMinRatio','buyerVolumeGrowthMinRatio',
    'exceptionalSellingVolumeMinRatio','consolidationBars','boxWidthMaxAtr',
    'boxProgressiveRangeMaxRatio','boxVolumeDryUpMaxRatio',
    'breakoutRelativeVolumeMinRatio','retestMaxBars','retestTolerancePct',
    'stopBufferTicks','atrPeriod','volumeLookbackBars','directStopRule',
    'trailingActivationR')
foreach ($name in $technical) {
    if ($null -eq $config.parameters.PSObject.Properties[$name] -or
        $null -eq $config.parameters.PSObject.Properties[$name].Value) {
        throw "Missing explicit setup parameter: $name"
    }
}
$symbols = @($config.symbols)
if ($symbols.Count -eq 0 -or @($symbols | Select-Object -Unique).Count -ne $symbols.Count) {
    throw 'Configure distinct USDT symbols'
}
foreach ($symbol in $symbols) {
    if ($symbol -cnotmatch '^[A-Z0-9]{5,32}$' -or -not $symbol.EndsWith('USDT')) {
        throw "Invalid Futures USDT symbol: $symbol"
    }
}
$windows = [int]$config.windows
$steps = [int]$config.steps
$context = [int]$config.contextCandles
if ($windows -lt 1 -or $windows -gt 24 -or $steps -lt 30 -or
    $context -lt 60 -or $context -gt 500) {
    throw 'Study requires 1..24 windows, >=30 daily bars/window and 60..500 warmup bars'
}
if ($config.parameters.directStopRule -notin @('BOX_BASE','BREAKOUT_CANDLE_LOW')) {
    throw 'directStopRule must be BOX_BASE or BREAKOUT_CANDLE_LOW'
}
if ([decimal]$config.capitalUsdt -le 0 -or
    [decimal]$config.riskPerTradePct -le 0 -or
    [decimal]$config.dailyStopPct -le 0 -or
    [int]$config.leverage -lt 1 -or [int]$config.maxHoldingCandles -lt 1) {
    throw 'Study financial inputs must be explicitly positive'
}
if ([decimal]$config.assumedFundingPer8hPct -ne 0) {
    Write-Warning 'Funding is an explicit hypothetical constant, not actual Binance funding history.'
} else {
    Write-Warning 'Zero funding is a TEST HYPOTHESIS; the real exchange may charge funding.'
}
$dayMs = 86400000L
$epoch = [DateTime]::SpecifyKind([DateTime]'1970-01-01T00:00:00',
    [DateTimeKind]::Utc)
$asOfMs = $null
if (-not [string]::IsNullOrWhiteSpace($AsOfDateUtc)) {
    $culture = [Globalization.CultureInfo]::InvariantCulture
    $style = [Globalization.DateTimeStyles]::AssumeUniversal -bor
        [Globalization.DateTimeStyles]::AdjustToUniversal
    $utcDate = [DateTime]::ParseExact($AsOfDateUtc, 'yyyy-MM-dd', $culture, $style)
    $asOfMs = [long]($utcDate.AddDays(1) - $epoch).TotalMilliseconds - 1L
}
if ($PrintOnly) {
    Write-Output 'RESEARCH=INDEPENDENT_DAILY_WITH_CLOSED_WEEKLY_TREND'
    Write-Output 'PRIMARY=1d'
    Write-Output 'HIGHER_TREND=1w'
    Write-Output 'ORDERS_ENABLED=false'
    Write-Output 'FINANCIAL_INPUTS=EXPLICIT_RESEARCH_CONFIG_NOT_VIDEO_DEFAULTS'
    Write-Output "SYMBOLS=$($symbols -join ',')"
    Write-Output "WINDOWS=$windows"
    Write-Output "STEPS=$steps"
    Write-Output "CONTEXT_WEEKS=$context"
    Write-Output "PORTFOLIOS=$($symbols.Count * $windows * 4)"
    Write-Output "AS_OF=$($asOfMs -as [string])"
    if ($null -ne $asOfMs) {
        for ($w=1; $w -le $windows; $w++) {
            [long]$end=$asOfMs - [long]($w-1)*$steps*$dayMs
            [long]$start=$end - [long]($steps-1)*$dayMs
            Write-Output "WINDOW=$w FROM=$start THROUGH=$end"
        }
    }
    return
}
$health = Invoke-RestMethod -Uri "$base/actuator/health" -TimeoutSec 15
if ($health.status -ne 'UP') { throw "Backend not ready: $($health.status)" }
$settings = Invoke-RestMethod -Uri "$base/api/v1/market-data/settings" -TimeoutSec 15
if ($settings.source -ne 'TESTNET') {
    throw "Expected Binance Futures TESTNET market source, got $($settings.source)"
}
if ('1d' -notin @($settings.intervals) -or '1w' -notin @($settings.intervals)) {
    throw 'Restart backend using start-backtest.ps1 -IncludeDailyWeekly'
}
$status = Invoke-RestMethod -Uri "$base/api/v1/research/independent-setups/status" -TimeoutSec 15
if ($status.mode -ne 'PAPER_ONLY' -or $status.ordersEnabled -ne $false -or
    $status.combinedStrategyEnabled -ne $false) {
    throw 'Backend is not in isolated paper-only mode'
}
$nowMs = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
if ($null -eq $asOfMs) {
    foreach ($symbol in $symbols) {
        $recent = Invoke-RestMethod -Uri "$base/api/v1/market-data/candles?symbol=$symbol&interval=1d&limit=1" -TimeoutSec 20
        $daily = @($recent)
        while ($daily.Count -eq 1 -and $daily[0] -is [System.Array]) {
            $daily = @($daily[0])
        }
        if ($daily.Count -ne 1 -or $daily[0].closed -ne $true) {
            throw "Missing last closed daily candle for $symbol"
        }
        [long]$last = $daily[0].closeTime
        if ($null -eq $asOfMs -or $last -lt $asOfMs) { $asOfMs = $last }
    }
}
if ($asOfMs -ge $nowMs -or $asOfMs % $dayMs -ne $dayMs-1) {
    throw 'asOf must be an already completed UTC daily candle close'
}
$source = 'https://demo-fapi.binance.com/fapi/v1/exchangeInfo'
$exchange = Invoke-RestMethod -Uri $source -TimeoutSec 45
$filtersBySymbol = @{}
foreach ($symbol in $symbols) {
    $instrument = @($exchange.symbols | Where-Object {
        $_.symbol -ceq $symbol -and $_.status -ceq 'TRADING' -and
        $_.contractType -ceq 'PERPETUAL' -and $_.quoteAsset -ceq 'USDT'
    })
    if ($instrument.Count -ne 1) { throw "No active USDT perpetual TESTNET contract: $symbol" }
    $priceFilter = @($instrument[0].filters | Where-Object { $_.filterType -eq 'PRICE_FILTER' })
    $lot = @($instrument[0].filters | Where-Object { $_.filterType -eq 'LOT_SIZE' })
    $minimum = @($instrument[0].filters | Where-Object { $_.filterType -eq 'MIN_NOTIONAL' })
    if ($priceFilter.Count -ne 1 -or $lot.Count -ne 1 -or $minimum.Count -ne 1) {
        throw "Missing PRICE_FILTER / LOT_SIZE / MIN_NOTIONAL exchange metadata: $symbol"
    }
    $minimumNotional = if ($null -ne $minimum[0].notional) {
        [decimal]$minimum[0].notional
    } else { [decimal]$minimum[0].minNotional }
    $filtersBySymbol[$symbol] = [ordered]@{
        priceTick = [decimal]$priceFilter[0].tickSize
        quantityStep = [decimal]$lot[0].stepSize
        minimumQuantity = [decimal]$lot[0].minQty
        minimumNotionalUsdt = $minimumNotional
    }
    foreach($field in @('priceTick','quantityStep','minimumQuantity','minimumNotionalUsdt')) {
        if ($filtersBySymbol[$symbol][$field] -le 0) {
            throw "Invalid exchange instrument filter $symbol/$field"
        }
    }
}
# Conservative preflight: the OLDEST window needs 60 completed weekly bars
# before its earliest signal, not 60 from the latest study cutoff.
[long]$firstStudyClose = $asOfMs - [long]($windows*$steps-1)*$dayMs
foreach($symbol in $symbols) {
    $weekly = Invoke-RestMethod -Uri "$base/api/v1/market-data/candles?symbol=$symbol&interval=1w&limit=500" -TimeoutSec 45
    $weeks = @($weekly)
    while ($weeks.Count -eq 1 -and $weeks[0] -is [System.Array]) {
        $weeks = @($weeks[0])
    }
    $eligible = @($weeks | Where-Object { $_.closed -eq $true -and [long]$_.closeTime -le $firstStudyClose })
    if ($eligible.Count -lt $context) {
        throw "${symbol}: only $($eligible.Count) completed historical 1w candles before oldest 1d window; $context needed. Sync an older period or reduce the selected OOS horizon."
    }
}
$arms = @('TREND_PULLBACK','TREND_PULLBACK_MIXED',
    'RANGE_BREAKOUT','RANGE_BREAKOUT_RETEST')
$windowRows = New-Object System.Collections.Generic.List[object]
$tradeRows = New-Object System.Collections.Generic.List[object]
$diagnosticRows = New-Object System.Collections.Generic.List[object]
$rejectionRows = New-Object System.Collections.Generic.List[object]
foreach ($symbol in $symbols) {
    for ($w=1; $w -le $windows; $w++) {
        [long]$end = $asOfMs - [long]($w-1)*$steps*$dayMs
        [long]$start = $end - [long]($steps-1)*$dayMs
        $request = [ordered]@{
            symbol=$symbol; steps=$steps; contextCandles=$context
            entryInterval='1d'; trendInterval='1w'; asOf=$end
            parameters=$config.parameters
            capitalUsdt=$config.capitalUsdt
            riskPerTradePct=$config.riskPerTradePct
            dailyStopPct=$config.dailyStopPct
            leverage=$config.leverage
            feePerSidePct=$config.feePerSidePct
            slippagePerSidePct=$config.slippagePerSidePct
            spreadPct=$config.spreadPct
            assumedFundingPer8hPct=$config.assumedFundingPer8hPct
            assumedMaintenanceMarginPct=$config.assumedMaintenanceMarginPct
            priceTick=$filtersBySymbol[$symbol].priceTick
            quantityStep=$filtersBySymbol[$symbol].quantityStep
            minimumQuantity=$filtersBySymbol[$symbol].minimumQuantity
            minimumNotionalUsdt=$filtersBySymbol[$symbol].minimumNotionalUsdt
            maxCostToInitialRiskPct=$config.maxCostToInitialRiskPct
            maxHoldingCandles=$config.maxHoldingCandles
        }
        Write-Host "Study $symbol, window $w/$windows, $steps closed 1d candles, asOf=$end..."
        $json=$request | ConvertTo-Json -Depth 20 -Compress
        try {
            $response=Invoke-RestMethod -Method Post -Uri "$base/api/v1/research/independent-setups/backtest" -Body ([Text.Encoding]::UTF8.GetBytes($json)) -ContentType 'application/json; charset=utf-8' -TimeoutSec 300
        } catch {
            $details=if($_.ErrorDetails){$_.ErrorDetails.Message}else{''}
            throw "Daily/weekly paper replay FAILED for $symbol window $w asOf=$end : $($_.Exception.Message) $details"
        }
        if ($response.symbol -ne $symbol -or $response.interval -ne '1d' -or
            [long]$response.lastClose -ne $end -or
            [int]$response.completedBars -ne $steps -or
            @($response.portfolios.PSObject.Properties).Count -ne 4 -or
            -not $response.executionModel.Contains('NO_REAL_ORDERS')) {
            throw "Unexpected research response for $symbol window $w (fail closed)"
        }
        $expectedObservations=$arms.Count*($steps-1)
        $observations=@($response.events)
        if($observations.Count -ne $expectedObservations) {
            throw "Incomplete weekly per-bar diagnostics for $symbol window ${w}: got $($observations.Count), expected $expectedObservations"
        }
        foreach($observation in $observations) {
            if($observation.arm -notin $arms -or
                [long]$observation.higherCandleCloseTime -gt [long]$observation.closeTime -or
                [string]::IsNullOrWhiteSpace([string]$observation.higherTrend) -or
                $null -eq $observation.higherTrendApproved -or
                $null -eq $observation.candidateBeforeHigherTrend) {
                throw "Missing or noncausal weekly reference in $symbol window $w"
            }
        }
        foreach ($arm in $arms) {
            $slot=$response.portfolios.PSObject.Properties[$arm]
            if ($null -eq $slot) { throw "Missing independent arm $arm for $symbol" }
            $p=$slot.Value
            $windowRows.Add([pscustomobject]@{
                Symbol=$symbol; Window=$w; Arm=$arm; RunId=$response.runId
                FirstOpenTime=$response.firstOpen; LastCloseTime=$response.lastClose
                CapitalUsdt=$p.initialCapital
                RealizedNetUsdt=$p.realizedNetUsdt
                MarkedPnlUsdt=([decimal]$p.finalMarkedEquity - [decimal]$p.initialCapital)
                FinalMarkedEquity=$p.finalMarkedEquity
                ClosedTrades=$p.closedTrades; Wins=$p.wins
                WinRatePct=if([int]$p.closedTrades -gt 0){100*[decimal]$p.wins/[decimal]$p.closedTrades}else{$null}
                FeesUsdt=$p.totalFeesUsdt; FundingUsdt=$p.totalFundingUsdt
                MaxDrawdownPct=$p.worstDrawdownPct
                OpenPosition=$p.openPosition
                AcceptedSignals=$p.acceptedSignals; BlockedEntries=$p.blockedEntries
            })
            foreach ($t in @($p.trades)) {
                if($null -eq $t) {continue}
                $tradeRows.Add([pscustomobject]@{
                    Symbol=$symbol;Window=$w;Arm=$arm;RunId=$response.runId
                    SignalCloseTime=$t.signalCloseTime; EntryOpenTime=$t.entryOpenTime
                    ExitOpenTime=$t.exitOpenTime;Entry=$t.entry;Exit=$t.exit
                    InitialStop=$t.initialStop;Quantity=$t.quantity
                    NetPnlUsdt=$t.netPnlUsdt;TotalFeesUsdt=$t.totalFeesUsdt
                    FundingUsdt=$t.fundingUsdt;Reason=$t.reason
                })
            }
        }
        foreach($event in @($response.events)) {
            if($null -eq $event) {continue}
            $signal=$event.signal
            $diagnosticRows.Add([pscustomobject]@{
                Symbol=$symbol;Window=$w;Arm=$event.arm;RunId=$response.runId
                CandleCloseTime=$event.closeTime;Status=$event.status;Reason=$event.reason
                HigherTrend=$event.higherTrend
                HigherCloseTime=$event.higherCandleCloseTime
                HigherTrendApproved=$event.higherTrendApproved
                DailyCandidateBeforeHigherTrend=$event.candidateBeforeHigherTrend
                PassedFilters=($signal.passedFilters -join '|')
                RejectedFilters=($signal.rejectedFilters -join '|')
                ReferencePrice=$signal.referencePrice;StructuralStop=$signal.structuralStop
                BoxTop=$signal.frozenBoxTop;BoxBase=$signal.frozenBoxBase
            })
        }
        foreach($reason in @($response.rejectionCounts.PSObject.Properties)) {
            if($null -eq $reason){continue}
            $rejectionRows.Add([pscustomobject]@{
                Symbol=$symbol;Window=$w;RunId=$response.runId
                ArmAndFilter=$reason.Name;Count=$reason.Value
            })
        }
    }
}
if($windowRows.Count -ne $symbols.Count*$windows*$arms.Count) {
    throw 'Incomplete independent portfolio results. No CSV will be saved.'
}
$aggregate = @($windowRows.ToArray() | Group-Object Symbol,Arm | ForEach-Object {
    $group=@($_.Group)
    [pscustomobject]@{
        Symbol=$group[0].Symbol;Arm=$group[0].Arm;Windows=$group.Count
        ClosedTrades=($group | Measure-Object -Property ClosedTrades -Sum).Sum
        Wins=($group | Measure-Object -Property Wins -Sum).Sum
        RealizedNetUsdt=($group | Measure-Object -Property RealizedNetUsdt -Sum).Sum
        MarkedPnlUsdt=($group | Measure-Object -Property MarkedPnlUsdt -Sum).Sum
        FeesUsdt=($group | Measure-Object -Property FeesUsdt -Sum).Sum
        FundingUsdt=($group | Measure-Object -Property FundingUsdt -Sum).Sum
        WorstWindowDrawdownPct=($group | Measure-Object -Property MaxDrawdownPct -Maximum).Maximum
        OpenPositions=@($group | Where-Object {$_.OpenPosition -eq $true}).Count
        AcceptedSignals=($group | Measure-Object -Property AcceptedSignals -Sum).Sum
        BlockedEntries=($group | Measure-Object -Property BlockedEntries -Sum).Sum
    }
} | Sort-Object Symbol,Arm)
$out=Join-Path $root 'results'
if(-not (Test-Path $out)){ New-Item -ItemType Directory -Path $out | Out-Null }
$stamp = "1d-1w-$asOfMs-w1-$windows-run$([DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ'))"
$paths = [ordered]@{
    windows=Join-Path $out "independent-windows-$stamp.csv"
    aggregate=Join-Path $out "independent-aggregate-$stamp.csv"
    trades=Join-Path $out "independent-trades-$stamp.csv"
    diagnostics=Join-Path $out "independent-diagnostics-$stamp.csv"
    rejections=Join-Path $out "independent-rejections-$stamp.csv"
    config=Join-Path $out "independent-config-$stamp.json"
}
@($windowRows.ToArray()) | Export-Csv -Path $paths.windows -NoTypeInformation -Encoding UTF8
$aggregate | Export-Csv -Path $paths.aggregate -NoTypeInformation -Encoding UTF8
if($tradeRows.Count -gt 0) {
    @($tradeRows.ToArray()) | Export-Csv -Path $paths.trades -NoTypeInformation -Encoding UTF8
} else {
    'Symbol,Window,Arm,RunId,SignalCloseTime,EntryOpenTime,ExitOpenTime,Entry,Exit,InitialStop,Quantity,NetPnlUsdt,TotalFeesUsdt,FundingUsdt,Reason' | Set-Content -Path $paths.trades -Encoding UTF8
}
if($diagnosticRows.Count -gt 0) {
    @($diagnosticRows.ToArray()) | Export-Csv -Path $paths.diagnostics -NoTypeInformation -Encoding UTF8
} else {
    'Symbol,Window,Arm,RunId,CandleCloseTime,Status,Reason,HigherTrend,HigherCloseTime,HigherTrendApproved,DailyCandidateBeforeHigherTrend,PassedFilters,RejectedFilters,ReferencePrice,StructuralStop,BoxTop,BoxBase' | Set-Content -Path $paths.diagnostics -Encoding UTF8
}
if($rejectionRows.Count -gt 0) {
    @($rejectionRows.ToArray()) | Export-Csv -Path $paths.rejections -NoTypeInformation -Encoding UTF8
} else {
    'Symbol,Window,RunId,ArmAndFilter,Count' | Set-Content -Path $paths.rejections -Encoding UTF8
}
[System.IO.File]::WriteAllText($paths.config, $configText, (New-Object System.Text.UTF8Encoding($false)))
$aggregate | Format-Table -AutoSize
foreach($item in $paths.GetEnumerator()){ Write-Host "Saved $($item.Key): $($item.Value)" }
Write-Host 'PAPER ONLY. Financial results are independent per arm/window; marked PnL includes open positions.'
