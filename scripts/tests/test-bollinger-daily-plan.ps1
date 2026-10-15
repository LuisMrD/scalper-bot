$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$runner=Join-Path $root 'scripts/run-bollinger-daily-three-symbols.ps1'
$startup=Join-Path $root 'scripts/start-backtest.ps1'
$default=@(& $startup -PrintOnly)
if($default -notcontains 'MARKET_DATA_INTERVALS=1m,5m,15m'){
    throw 'Existing backtest startup defaults must be preserved'
}
$daily=@(& $startup -IncludeDaily -PrintOnly)
if($daily -notcontains 'MARKET_DATA_INTERVALS=1m,5m,15m,1d'){
    throw 'Opt-in daily market data unavailable'
}
$plan=@(& $runner -PrintOnly)
foreach($line in @(
    'RESEARCH=DAILY_300H_4_INDEPENDENT_ARMS',
    'HOURS_PER_WINDOW=300',
    'WINDOWS=6',
    'COMPLETE_DAILY_CANDLES_PER_WINDOW=12',
    'TOTAL_INDEPENDENT_PORTFOLIOS=72',
    'STOP_MODES=STRUCTURAL,ONE_TICK_BEYOND_EXTREME',
    'MINIMUM_NET_REWARD_RISK=1.5',
    'NO_LUNA=true','NO_ORDERS=true'
)){
    if($plan -notcontains $line){throw "Missing daily invariant: $line"}
}
$windows=@($plan|Where-Object {$_ -match '^WINDOW=\d+;'})
if($windows.Count -ne 6){throw 'Expected six disjoint 300h windows'}
[long]$dayMs=86400000L
[long]$windowMs=300L*3600000L
$previous=$null
foreach($line in $windows){
    $parts=@{}
    foreach($piece in ($line -split ';')){
        $kv=$piece -split '=',2
        $parts[$kv[0]]=$kv[1]
    }
    [long]$ws=[long]$parts['WALL_START']
    [long]$we=[long]$parts['WALL_END']
    [long]$op=[long]$parts['FIRST_OPEN']
    [long]$dc=[long]$parts['DAILY_CLOSE']
    if($we-$ws+1 -ne $windowMs -or $op -lt $ws -or
       $dc -gt $we -or $dc-$op+1 -ne 12L*$dayMs -or
       $op%$dayMs -ne 0 -or $dc%$dayMs -ne $dayMs-1){
        throw "Non-complete daily window: $line"
    }
    if($null -ne $previous -and $we+1 -ne $previous){
        throw '300-hour wall-clock windows must be contiguous and disjoint'
    }
    $previous=$ws
}
Write-Host 'PASS: 3 symbols x 6 300h windows x 4 independent portfolios; 12 complete daily candles each'
