# Offline integration regression for 24-window standalone expectancy analysis.
# Windows PowerShell 5.1 + pwsh. No MySQL, HTTP, orders or LLM.
$ErrorActionPreference='Stop'
$script=Join-Path (Split-Path $PSScriptRoot -Parent) 'analyze-expectancy-regimes.ps1'
$tokens=$null;$errors=$null
[System.Management.Automation.Language.Parser]::ParseFile(
    (Resolve-Path $script).Path,[ref]$tokens,[ref]$errors) | Out-Null
if($errors.Count -gt 0){throw ("PowerShell parser errors: {0}" -f ($errors | Out-String))}
$plan=@(& $script -PrintOnly)
foreach($line in @(
    'EXPECTANCY_SCOPE=INSPECTED_WINDOWS_ONLY',
    'EXPECTANCY_CONTROL_POLICIES=CONTROL',
    'EXPECTANCY_WINDOWS=16..39',
    'EXPECTANCY_COHORTS=16-21,22-27,28-33,34-39',
    'EXPECTANCY_NUMBER_OF_PORTFOLIOS=24',
    'EXPECTANCY_NO_ORDERS=true',
    'EXPECTANCY_NO_LUNA=true',
    'EXPECTANCY_NO_HTTP=true',
    'EXPECTANCY_NEVER_REPLAY_BY_DELETING_TRADES=true'
)){if($plan -notcontains $line){throw "Missing audit invariant: $line"}}
$dir=Join-Path ([System.IO.Path]::GetTempPath()) ("expectancy-test-"+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $dir -Force | Out-Null
try {
    $groups=@(
        @{Prefix='short-regime';Start=16;End=21},
        @{Prefix='short-regime';Start=22;End=27},
        @{Prefix='short-range-oos';Start=28;End=33},
        @{Prefix='range-l2-oos';Start=34;End=39}
    )
    [long]$rootAsOf=1791845999999
    [long]$span=300L*3600000L
    foreach($co in $groups){
        $winRows=@();$tradeRows=@()
        for([int]$id=$co.Start;$id -le $co.End;$id++){
            [long]$end=$rootAsOf-([long]($id-1)*$span)
            [long]$from=$end-$span+1
            $winRows += [pscustomobject]@{
                Policy='CONTROL';Window=$id;FromOpen=$from;AsOf=$end
                Trades=2;TradesClosed=2;LongClosedPnlUsdt='6';ShortClosedPnlUsdt='-3'
                StartingEquityUsdt='1500';NetClosedUsdt='3';MarkedPnlUsdt='3'
                FeesUsdt='0.6';MaxDrawdownPct='0.3';OpenPositionSide='FLAT'
            }
            $tradeRows += [pscustomobject]@{
                Policy='CONTROL';Window=$id;Side='LONG'
                EnteredAtOpenTime=($from+300000L);SignalAtCloseTime=($from+299999L)
                SignalCloseTime=($from+299999L);ExitCandleOpenTime=($from+600000L)
                InitialRiskUsdt='6';NetPnlUsdt='6';FeesUsdt='0.3'
                EntrySetup='BREAKOUT_RETEST';PrimaryTrend='UP';Trend15m='RANGE'
                EntryBreakoutState='UP_FOLLOW_THROUGH';VolumeLevel='HIGH'
                StopDistancePct='0.3';EstimatedCostToInitialRiskPct='30'
                MfeR='1.8';MaeR='0.6';GivebackFromBestCloseUsdt='0.3'
                ExitReason='STOP'
            }
            $tradeRows += [pscustomobject]@{
                Policy='CONTROL';Window=$id;Side='SHORT'
                EnteredAtOpenTime=($from+900000L);SignalAtCloseTime=($from+899999L)
                SignalCloseTime=($from+899999L);ExitCandleOpenTime=($from+1200000L)
                InitialRiskUsdt='6';NetPnlUsdt='-3';FeesUsdt='0.3'
                EntrySetup='BREAKOUT_RETEST';PrimaryTrend='DOWN';Trend15m='DOWN'
                EntryBreakoutState='DOWN_FOLLOW_THROUGH';VolumeLevel='HIGH'
                StopDistancePct='0.6';EstimatedCostToInitialRiskPct='60'
                MfeR='0.9';MaeR='0.3';GivebackFromBestCloseUsdt='0.6'
                ExitReason='STOP'
            }
        }
        $suffix="SOLUSDT-RETEST-$rootAsOf-5m-w$($co.Start)-$($co.End).csv"
        $winRows | Export-Csv -Path (Join-Path $dir "$($co.Prefix)-windows-$suffix") -NoTypeInformation -Encoding UTF8
        $tradeRows | Export-Csv -Path (Join-Path $dir "$($co.Prefix)-trades-$suffix") -NoTypeInformation -Encoding UTF8
    }
    & $script -ResultsDir $dir -AsOf $rootAsOf -ExpectedCapitalUsdt 1500
    $stem="SOLUSDT-RETEST-$rootAsOf-5m-w16-39"
    $w=@(Import-Csv (Join-Path $dir "expectancy-audit-windows-$stem.csv"))
    $t=@(Import-Csv (Join-Path $dir "expectancy-audit-trades-$stem.csv"))
    $m=@(Import-Csv (Join-Path $dir "expectancy-audit-regimes-$stem.csv"))
    if($w.Count -ne 24 -or $t.Count -ne 48 -or $m.Count -lt 30){
        throw "Incomplete expectancy CSVs: windows=$($w.Count), trades=$($t.Count), regimes=$($m.Count)"
    }
    $overall=@($m | Where-Object {$_.Scope -eq 'ALL_WINDOWS' -and
        $_.Side -eq 'ALL' -and $_.Feature -eq 'ALL'})
    if($overall.Count -ne 1 -or [int]$overall[0].Trades -ne 48 -or
       [decimal]::Parse($overall[0].PnLNetUsdt,[System.Globalization.CultureInfo]::InvariantCulture) -ne 72){
        throw 'Expected 48 trades, total +72, exactly one aggregate'
    }
    $sides=@($m | Where-Object {$_.Scope -eq 'ALL_WINDOWS' -and
        $_.Side -in @('LONG','SHORT') -and $_.Feature -eq 'ALL'})
    if($sides.Count -ne 2){throw 'Side-aware expectancy reports missing'}
    $long=@($sides | Where-Object {$_.Side -eq 'LONG'})[0]
    $short=@($sides | Where-Object {$_.Side -eq 'SHORT'})[0]
    if([decimal]::Parse($long.PnLNetUsdt,[System.Globalization.CultureInfo]::InvariantCulture) -ne 144 -or
       [decimal]::Parse($short.PnLNetUsdt,[System.Globalization.CultureInfo]::InvariantCulture) -ne -72){
        throw 'LONG/SHORT expected net +144/-72 miscalculated'
    }
    if([int]$overall[0].PostEntryMetricTradeCount -ne 48){
        throw 'Outcome metrics coverage was not reported'
    }
    Write-Host 'PASS: all 24 CONTROL windows, expectancy in USDT/R, profitability thresholds and side reconciliation'
} finally {
    Remove-Item -LiteralPath $dir -Recurse -Force -ErrorAction SilentlyContinue
}
