$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$wrapper=Join-Path $root 'scripts/run-independent-extended-sample.ps1'
$controls=Join-Path $root 'scripts/config/independent-daily-weekly-research.json'
$baseline=Get-Content -LiteralPath $controls -Raw -Encoding UTF8 | ConvertFrom-Json

foreach($months in @(12,24)) {
    $config=Get-Content -LiteralPath (Join-Path $root "scripts/config/independent-daily-weekly-${months}x30d.json") -Raw -Encoding UTF8 | ConvertFrom-Json
    $baseline.windows=$months
    if(($baseline | ConvertTo-Json -Depth 30 -Compress) -cne
       ($config | ConvertTo-Json -Depth 30 -Compress)) {
        throw "Config $months must not change risk, capital, coins, technical rules, or execution costs"
    }
    $plan=@(& $wrapper -Months $months -AsOfDateUtc 2026-10-15 -PrintOnly)
    $from=if($months -eq 12){'2025-10-21'}else{'2024-10-26'}
    $sync=if($months -eq 12){'2024-07-16'}else{'2023-07-22'}
    foreach($required in @(
        "HORIZON_DAYS=$($months*30)",
        "WINDOWS=$months",
        'STEPS=30',
        'SYMBOLS=BTCUSDT,SOLUSDT,DOGEUSDT',
        "PORTFOLIOS=$($months*3*4)",
        "STUDY_FROM_UTC=$from",
        'STUDY_THROUGH_UTC=2026-10-15',
        "SYNC_FROM_UTC=$sync",
        'SYNC_THROUGH_UTC=2026-10-15',
        'STRATEGIES=TREND_PULLBACK,TREND_PULLBACK_MIXED,TREND_PULLBACK_SHORT,TREND_PULLBACK_MIXED_SHORT',
        'WEEKLY_FILTER=CLOSED_WEEKLY_EMA21_SLOPE',
        'EXECUTION=PAPER_ONLY',
        'ACTIVE_SETUP=PULLBACK_ONLY',
        'ORDERS_ENABLED=false',
        'RESEARCH_LIMITATION=30_DAY_PORTFOLIO_RESETS; NESTED_12_24_MONTH_HORIZONS'
    )){
        if($plan -notcontains $required){throw "Missing $months-window offline plan: $required"}
    }
    if(@($plan|Where-Object{$_ -match '^WINDOW=\d+ FROM='}).Count -ne $months) {
        throw "Expected $months separate daily windows in offline plan"
    }
    Write-Host "PASS: $months independent 30-day windows with frozen 1d/1w pullback controls and no network"
}
