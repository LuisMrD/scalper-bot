# Offline fixture for the replacement-entry reconciler. No HTTP or MySQL.
$ErrorActionPreference='Stop'
$runner=Join-Path (Split-Path $PSScriptRoot -Parent) 'audit-replacement-entries.ps1'
$tokens=$null
$errors=$null
[System.Management.Automation.Language.Parser]::ParseFile(
    (Resolve-Path $runner).Path,[ref]$tokens,[ref]$errors) | Out-Null
if($errors.Count -gt 0){throw ("PowerShell parser errors: {0}" -f ($errors | ForEach-Object Message | Out-String))}
$folder=Join-Path ([System.IO.Path]::GetTempPath()) ('replacements-test-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $folder -Force | Out-Null
try{
    $asof=[long]1791845999999
    $from=$asof-(300L*3600000L)+1
    $stem="SOLUSDT-RETEST-$asof-5m-w1-1"
    function Trade([string]$filter,[string]$side,[long]$entered,[string]$net){
        return [PSCustomObject]@{
            Window=1;Filter=$filter;Side=$side;EnteredAtOpenTime=$entered
            ExitCandleOpenTime=$entered+300000
            SignalAtCloseTime=$entered-1
            EntryHourUtc=12;NetPnlUsdt=$net;InitialRiskUsdt='2'
            EntryBreakoutState='UP_FOLLOW_THROUGH'
            PrimaryTrend='UP';Trend15m='UP';HigherTimeframeConflicts=0
            VolumeLevel='NORMAL';TakerBalance='BALANCED'
            RelativeVolume='1.1';RelativeSpread='1.0';TakerBuyShare='0.5'
            EffortResult='NEUTRAL';BarDirection='BULL'
            BarTrend='True';BarDoji='False';BarInside='False'
            StopDistancePct='0.5';EstimatedCostToInitialRiskPct='21'
            DurationCandles=1;MfeR='0.3';MaeR='0.2';NetRMultiple=$net
            ExitReason='STOP';Quantity='1';FeesUsdt='0.1'
        }
    }
    $first=$from+600000
    $removed=$from+1200000
    $added=$from+1800000
    $trades=@(
        (Trade 'NONE' 'LONG' $first '-2'),
        (Trade 'NONE' 'SHORT' $removed '-5'),
        (Trade 'BLOCK_BOTH' 'LONG' $first '-3'),
        (Trade 'BLOCK_BOTH' 'SHORT' $added '-4')
    )
    $trades | Export-Csv -Path (Join-Path $folder "entry-filter-trades-$stem.csv") -NoTypeInformation -Encoding UTF8
    $summary=@(
        [PSCustomObject]@{Window=1;Filter='NONE';FromOpen=$from;AsOf=$asof;TradesClosed=2;NetClosedUsdt='-7'},
        [PSCustomObject]@{Window=1;Filter='BLOCK_SHORT_DOWN_CANDIDATE';FromOpen=$from;AsOf=$asof;TradesClosed=1;NetClosedUsdt='-2'},
        [PSCustomObject]@{Window=1;Filter='BLOCK_LONG_NONE';FromOpen=$from;AsOf=$asof;TradesClosed=2;NetClosedUsdt='-7'},
        [PSCustomObject]@{Window=1;Filter='BLOCK_BOTH';FromOpen=$from;AsOf=$asof;TradesClosed=2;NetClosedUsdt='-7'}
    )
    $summary | Export-Csv -Path (Join-Path $folder "entry-filter-windows-$stem.csv") -NoTypeInformation -Encoding UTF8
    & $runner -Symbol SOLUSDT -Windows 1 -AsOf $asof -ResultsDir $folder | Out-Null
    $cohorts=@(Import-Csv (Join-Path $folder "entry-filter-replacement-trades-$stem.csv"))
    $windowReport=@(Import-Csv (Join-Path $folder "entry-filter-replacement-windows-$stem.csv"))
    $profiles=@(Import-Csv (Join-Path $folder "entry-filter-replacement-patterns-$stem.csv"))
    foreach($name in @('NEW','REMOVED','PRESERVED')){
        if(@($cohorts | Where-Object {$_.Cohort -eq $name}).Count -ne 1){
            throw ("Incorrect cohort count: {0}" -f $name)
        }
    }
    if($windowReport.Count -ne 1 -or
       [int]$windowReport[0].NewLosses -ne 1 -or
       [int]$windowReport[0].RemovedLosses -ne 1 -or
       [decimal]$windowReport[0].PreservedPnlDeltaUsdt -ne -1 -or
       [decimal]$windowReport[0].TotalDeltaUsdt -ne 0){
        throw 'Reconciliation of new/removed/preserved PnL failed'
    }
    if(@($profiles | Where-Object {
        $_.Cohort -eq 'NEW' -and $_.Feature -eq 'Side' -and $_.Value -eq 'SHORT' -and
        [int]$_.Trades -eq 1 -and [int]$_.Losses -eq 1
    }).Count -ne 1){throw 'New losing entry profile missing'}
    Write-Host 'PASS: new/removed/preserved trade identity and PnL reconciliation'
}finally{
    Remove-Item -Path $folder -Recurse -Force -ErrorAction SilentlyContinue
}
