<#
.SYNOPSIS
  BTC-only: twelve disjoint 900-hour 15m/1d windows; original stop, 3 MIDAS entry policies x 2 mixed setups.
.DESCRIPTION
  Runs BTC in six independent original-stop mixed LONG/SHORT paper accounts per window,
  exports windows, aggregate, trades, diagnostics, rejection counts and frozen
  configuration into results/. All strategy/capital/risk parameters come from
  an EXPLICIT editable research JSON. No order placement or paid AI.
  Contract filters are fetched from the same Binance Futures TESTNET source.
  -SyncHistory optionally synchronizes 15m/1d candles, paper only.
.EXAMPLE
  .\scripts\run-btc-15m-mixed-midas-12x900h.ps1 -PrintOnly -AsOfDateUtc 2026-10-15
.EXAMPLE
  .\scripts\run-btc-15m-mixed-midas-12x900h.ps1 -AsOfDateUtc 2026-10-15
#>
[CmdletBinding()]
param(
    [ValidatePattern('^https?://(localhost|127\.0\.0\.1)(:\d+)?$')]
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [string]$ConfigPath = (Join-Path $PSScriptRoot 'config/btc-15m-mixed-midas-12x900h.json'),
    [ValidatePattern('^\d{4}-\d{2}-\d{2}$')]
    [string]$AsOfDateUtc='2026-10-15',
    [switch]$SyncHistory,
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
$required = @('symbols','windows','steps','entryInterval','trendInterval','contextCandles','capitalUsdt',
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
if ($symbols.Count -ne 1 -or $symbols[0] -cne 'BTCUSDT') {
    throw 'Stop study must contain BTCUSDT only'
}
if($config.exitPolicy -cne 'BASELINE' -or $null -ne $config.exitPolicies -or
   [int]$config.midasWarmupBars -ne 1000 -or
   (($config.entryPolicies -join ',') -cne
   'BASELINE,MIDAS_TREND,MIDAS_PULLBACK_REJECTION')) {
    throw 'Original stop and exactly 3 frozen MIDAS entry policies with 1000 15m warmup required'
}
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
if ($windows -ne 12 -or $steps -ne 3600 -or $context -ne 60 -or
    [decimal]$config.capitalUsdt -ne 600 -or
    $config.entryInterval -ne '15m' -or $config.trendInterval -ne '1d') {
    throw 'Requires BTC 15m/1d, $600 capital and exactly 12 x 900h (3600 bars per window)'
}
$barMs = 900000L
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
    Write-Output 'RESEARCH=BTC_15M_MIXED_MIDAS_12X900H'
    Write-Output 'PRIMARY=15m'
    Write-Output 'HIGHER_TREND=1d'
    Write-Output 'ACTIVE_SETUP=PULLBACK_ONLY'
    Write-Output 'DAILY_FILTER=CLOSED_DAILY_EMA21_SLOPE'
    Write-Output 'WINDOW_HOURS=900'
    Write-Output 'TOTAL_HOURS=10800'
    Write-Output 'MAX_HOLDING_HOURS=7.5'
    Write-Output 'CAPITAL_RESET_EACH_900H_WINDOW=true'
    Write-Output 'ORDERS_ENABLED=false'
    Write-Output 'FINANCIAL_INPUTS=EXPLICIT_RESEARCH_CONFIG_NOT_VIDEO_DEFAULTS'
    Write-Output "SYMBOLS=$($symbols -join ',')"
    Write-Output "WINDOWS=$windows"
    Write-Output "STEPS=$steps"
    Write-Output "CONTEXT_HIGHER_BARS=$context"
    Write-Output "PORTFOLIOS=$($symbols.Count * $windows * 2 * @($config.entryPolicies).Count)"
    Write-Output "CAPITAL_USDT=$($config.capitalUsdt)"
    Write-Output 'EXIT_POLICY=BASELINE'
    Write-Output "ENTRY_POLICIES=$($config.entryPolicies -join ',')"
    Write-Output "MIDAS_WARMUP_BARS=$($config.midasWarmupBars)"
    Write-Output 'ENTRY_ARMS=TREND_PULLBACK_MIXED,TREND_PULLBACK_MIXED_SHORT'
    Write-Output 'DIAGNOSTICS=TWO_SHARED_SETUP_EVALUATIONS_PLUS_MIDAS_POLICIES'
    Write-Output "AS_OF=$($asOfMs -as [string])"
    if ($null -ne $asOfMs) {
        for ($w=1; $w -le $windows; $w++) {
            [long]$end=$asOfMs - [long]($w-1)*$steps*$barMs
            [long]$start=$end - [long]($steps-1)*$barMs
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
if ('15m' -notin @($settings.intervals) -or '1d' -notin @($settings.intervals)) {
    throw 'Restart backend using start-backtest.ps1 -IncludeDailyWeekly; 15m and 1d must be enabled'
}
$status = Invoke-RestMethod -Uri "$base/api/v1/research/independent-setups/status" -TimeoutSec 15
if ($status.mode -ne 'PAPER_ONLY' -or $status.ordersEnabled -ne $false -or
    $status.combinedStrategyEnabled -ne $false) {
    throw 'Backend is not in isolated paper-only mode'
}
if ([int]$status.paperVariants -ne 4 -or
    $status.higherTrendRule15m -ne 'CLOSED_DAILY_EMA21_SLOPE' -or
    @($status.activeSetups).Count -ne 2 -or
    @($status.activeSetups | Where-Object { $_ -match 'BREAKOUT|RANGE' }).Count -ne 0) {
    throw 'Backend is not running the latest pullback-only 15m/1d EMA21 research. Restart Spring Boot after git pull.'
}
$policyStatus = Invoke-RestMethod -Uri "$base/api/v1/research/independent-setups/midas-study/status" -TimeoutSec 15
if($policyStatus.mode -ne 'PAPER_ONLY' -or $policyStatus.ordersEnabled -ne $false -or
   $policyStatus.symbol -ne 'BTCUSDT' -or $policyStatus.windows -ne 12 -or $policyStatus.barsPerWindow -ne 3600 -or
   $policyStatus.capitalUsdt -ne 600 -or $policyStatus.baseArms -ne 2 -or
   $policyStatus.midasWarmupBars -ne 1000 -or
   (($policyStatus.entryPolicies -join ',') -cne ($config.entryPolicies -join ','))) {
    throw 'Restart backend: MIDAS /midas-study/status must match $600/12x900h and 3 policies'
}
if ($SyncHistory) {
    $endForSync = [DateTime]::ParseExact($AsOfDateUtc,'yyyy-MM-dd',
        [Globalization.CultureInfo]::InvariantCulture,
        ([Globalization.DateTimeStyles]::AssumeUniversal -bor
         [Globalization.DateTimeStyles]::AdjustToUniversal))
    $syncFromUtc=$endForSync.AddHours(-10800).AddDays(-75).ToString('yyyy-MM-dd')
    Write-Host "Syncing closed 15m/1d from $syncFromUtc through $AsOfDateUtc, TESTNET only."
    & (Join-Path $PSScriptRoot 'sync-independent-daily-weekly.ps1') `
        -Symbols $symbols `
        -Intervals @('15m','1d') `
        -FromUtc $syncFromUtc `
        -UntilUtc $AsOfDateUtc `
        -BaseUrl $BaseUrl `
        -FailOnEmptyDaily
}
$nowMs = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
if ($null -eq $asOfMs) {
    foreach ($symbol in $symbols) {
        $recent = Invoke-RestMethod -Uri "$base/api/v1/market-data/candles?symbol=$symbol&interval=15m&limit=1" -TimeoutSec 20
        $daily = @($recent)
        while ($daily.Count -eq 1 -and $daily[0] -is [System.Array]) {
            $daily = @($daily[0])
        }
        if ($daily.Count -ne 1 -or $daily[0].closed -ne $true) {
            throw "Missing last closed 15m candle for $symbol"
        }
        [long]$last = $daily[0].closeTime
        if ($null -eq $asOfMs -or $last -lt $asOfMs) { $asOfMs = $last }
    }
}
if ($asOfMs -ge $nowMs -or $asOfMs % $dayMs -ne $dayMs-1) {
    throw 'asOf must be an already completed UTC 15m candle close (AsOfDateUtc means 23:59:59.999 UTC)'
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
# OLDEST 15m window needs 60 fully completed 1d bars before first signal.
[long]$firstStudyClose = $asOfMs - [long]($windows*$steps-1)*$barMs
foreach($symbol in $symbols) {
    $daily = Invoke-RestMethod -Uri "$base/api/v1/market-data/candles?symbol=$symbol&interval=1d&limit=60&asOf=$firstStudyClose" -TimeoutSec 45
    $days = @($daily)
    while ($days.Count -eq 1 -and $days[0] -is [System.Array]) {
        $days = @($days[0])
    }
    $eligible = @($days | Where-Object { $_.closed -eq $true -and [long]$_.closeTime -le $firstStudyClose })
    if ($eligible.Count -lt $context) {
        throw "${symbol}: only $($eligible.Count) completed historical 1d candles before oldest 15m window; $context needed. Sync an older period."
    }
}
$baseArms = @('TREND_PULLBACK_MIXED','TREND_PULLBACK_MIXED_SHORT')
$arms = @()
foreach($arm in $baseArms){
    foreach($policy in @($config.entryPolicies)){
        $arms += ($arm + '__' + $policy)
    }
}
$windowRows = New-Object System.Collections.Generic.List[object]
$tradeRows = New-Object System.Collections.Generic.List[object]
$diagnosticRows = New-Object System.Collections.Generic.List[object]
$rejectionRows = New-Object System.Collections.Generic.List[object]
foreach ($symbol in $symbols) {
    # Oldest first: missing early history fails before running the recent
    # windows. Window IDs/asOf dates stay exactly the same (newest is 1).
    for ($w=$windows; $w -ge 1; $w--) {
        [long]$end = $asOfMs - [long]($w-1)*$steps*$barMs
        [long]$start = $end - [long]($steps-1)*$barMs
        $request = [ordered]@{
            symbol=$symbol; steps=$steps; contextCandles=$context
            entryInterval='15m'; trendInterval='1d'; asOf=$end
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
        Write-Host "Study $symbol, window $w/$windows, 900h / $steps closed 15m candles, asOf=$end..."
        $json=$request | ConvertTo-Json -Depth 20 -Compress
        try {
            $response=Invoke-RestMethod -Method Post -Uri "$base/api/v1/research/independent-setups/backtest-midas" -Body ([Text.Encoding]::UTF8.GetBytes($json)) -ContentType 'application/json; charset=utf-8' -TimeoutSec 300
        } catch {
            $details=if($_.ErrorDetails){$_.ErrorDetails.Message}else{''}
            throw "15m/daily paper replay FAILED for $symbol window $w asOf=$end : $($_.Exception.Message) $details"
        }
        if ($response.symbol -ne $symbol -or $response.interval -ne '15m' -or
            [long]$response.lastClose -ne $end -or
            [int]$response.completedBars -ne $steps -or
            @($response.portfolios.PSObject.Properties).Count -ne 6 -or
            -not $response.executionModel.Contains('BTC_MIXED_MIDAS_900H') -or
            -not $response.executionModel.Contains('NO_REAL_ORDERS')) {
            throw "Unexpected research response for $symbol window $w (fail closed)"
        }
        $expectedObservations=$baseArms.Count*($steps-1)
        $observations=@($response.events)
        if($observations.Count -ne $expectedObservations) {
            throw "Incomplete daily-reference per-bar diagnostics for $symbol window ${w}: got $($observations.Count), expected $expectedObservations"
        }
        foreach($observation in $observations) {
            if($observation.arm -notin $baseArms -or
                [long]$observation.higherCandleCloseTime -gt [long]$observation.closeTime -or
                [string]::IsNullOrWhiteSpace([string]$observation.higherTrend) -or
                $null -eq $observation.higherTrendApproved -or
                $null -eq $observation.candidateBeforeHigherTrend) {
                throw "Missing or noncausal daily reference in $symbol window $w"
            }
        }
        foreach ($arm in $arms) {
            $slot=$response.portfolios.PSObject.Properties[$arm]
            if ($null -eq $slot) { throw "Missing independent arm $arm for $symbol" }
            $p=$slot.Value
            $windowRows.Add([pscustomobject]@{
                Symbol=$symbol; Window=$w; Arm=$arm
                Side=if($arm.Contains('_SHORT__')){'SHORT'}else{'LONG'}
                ExitPolicy='BASELINE'
                BaseSetup=($arm -split '__',2)[0]
                EntryPolicy=($arm -split '__',2)[1]
                RunId=$response.runId
                FirstOpenTime=$response.firstOpen; LastCloseTime=$response.lastClose
                HigherFilter='CLOSED_DAILY_EMA21_SLOPE'
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
                    Symbol=$symbol;Window=$w;Arm=$arm
                    Side=if($arm.Contains('_SHORT__')){'SHORT'}else{'LONG'}
                ExitPolicy='BASELINE'
                BaseSetup=($arm -split '__',2)[0]
                EntryPolicy=($arm -split '__',2)[1]
                    RunId=$response.runId
                    SignalCloseTime=$t.signalCloseTime; EntryOpenTime=$t.entryOpenTime
                    ExitOpenTime=$t.exitOpenTime;Entry=$t.entry;Exit=$t.exit
                    InitialStop=$t.initialStop;Quantity=$t.quantity
                    NetPnlUsdt=$t.netPnlUsdt;TotalFeesUsdt=$t.totalFeesUsdt
                    FundingUsdt=$t.fundingUsdt;Reason=$t.reason
                    MfeR=$t.mfeR;MaeR=$t.maeR;NetR=$t.netR
                })
            }
        }
        foreach($event in @($response.events)) {
            if($null -eq $event) {continue}
            $signal=$event.signal
            $midTrend=$null
            $midRetest=$null
            if($null -ne $event.midasAssessments) {
                $midTrend=$event.midasAssessments.MIDAS_TREND
                $midRetest=$event.midasAssessments.MIDAS_PULLBACK_REJECTION
            }
            $diagnosticRows.Add([pscustomobject]@{
                Symbol=$symbol;Window=$w;Arm=$event.arm
                Side=if($event.arm.EndsWith('_SHORT')){'SHORT'}else{'LONG'}
                RunId=$response.runId
                CandleCloseTime=$event.closeTime;Status=$event.status;Reason=$event.reason
                HigherFilter='CLOSED_DAILY_EMA21_SLOPE'
                HigherTrend=$event.higherTrend
                HigherCloseTime=$event.higherCandleCloseTime
                HigherTrendApproved=$event.higherTrendApproved
                DailyCandidateBeforeHigherTrend=$event.candidateBeforeHigherTrend
                PassedFilters=($signal.passedFilters -join '|')
                RejectedFilters=($signal.rejectedFilters -join '|')
                ReferencePrice=$signal.referencePrice;StructuralStop=$signal.structuralStop
                BoxTop=$signal.frozenBoxTop;BoxBase=$signal.frozenBoxBase
                MidasValue=$midTrend.value
                MidasAnchorOpenTime=$midTrend.anchorOpenTime
                MidasTrendAllowed=$midTrend.allowed
                MidasTrendReason=$midTrend.reason
                MidasRetestAllowed=$midRetest.allowed
                MidasRetestReason=$midRetest.reason
                MidasRetestTouchedCloseTime=$midRetest.touchedCloseTime
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
        Symbol=$group[0].Symbol;Arm=$group[0].Arm
        Side=if($group[0].Arm.Contains('_SHORT__')){'SHORT'}else{'LONG'}
        ExitPolicy='BASELINE'
        BaseSetup=($group[0].Arm -split '__',2)[0]
        EntryPolicy=($group[0].Arm -split '__',2)[1]
        Windows=$group.Count
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
$stamp = "BTC-mixed-MIDAS-15m-1d-900h-$asOfMs-w1-$windows-run$([DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ'))"
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
    'Symbol,Window,Arm,Side,ExitPolicy,BaseSetup,EntryPolicy,RunId,SignalCloseTime,EntryOpenTime,ExitOpenTime,Entry,Exit,InitialStop,Quantity,NetPnlUsdt,TotalFeesUsdt,FundingUsdt,Reason,MfeR,MaeR,NetR' | Set-Content -Path $paths.trades -Encoding UTF8
}
if($diagnosticRows.Count -gt 0) {
    @($diagnosticRows.ToArray()) | Export-Csv -Path $paths.diagnostics -NoTypeInformation -Encoding UTF8
} else {
    'Symbol,Window,Arm,Side,RunId,CandleCloseTime,Status,Reason,HigherFilter,HigherTrend,HigherCloseTime,HigherTrendApproved,DailyCandidateBeforeHigherTrend,PassedFilters,RejectedFilters,ReferencePrice,StructuralStop,BoxTop,BoxBase,MidasValue,MidasAnchorOpenTime,MidasTrendAllowed,MidasTrendReason,MidasRetestAllowed,MidasRetestReason,MidasRetestTouchedCloseTime' | Set-Content -Path $paths.diagnostics -Encoding UTF8
}
if($rejectionRows.Count -gt 0) {
    @($rejectionRows.ToArray()) | Export-Csv -Path $paths.rejections -NoTypeInformation -Encoding UTF8
} else {
    'Symbol,Window,RunId,ArmAndFilter,Count' | Set-Content -Path $paths.rejections -Encoding UTF8
}
[System.IO.File]::WriteAllText($paths.config, $configText, (New-Object System.Text.UTF8Encoding($false)))
$aggregate | Format-Table -AutoSize
foreach($item in $paths.GetEnumerator()){ Write-Host "Saved $($item.Key): $($item.Value)" }
Write-Host 'PAPER ONLY: BTC, 2 mixed setups x 3 causal MIDAS entry policies x 12 independent 900h windows, $600 per arm/window. Original protective stop/trailing in ALL books. Positions reset across windows.'
