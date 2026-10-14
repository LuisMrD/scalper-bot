<#
.SYNOPSIS
  Retrospective expectancy audit using existing CSVs, no Spring Boot needed.
.DESCRIPTION
  Four 6-window groups: w16..21, 22..27, 28..33, 34..39.
  Exactly ONE CONTROL portfolio per window. No mixing/double counting
  independent policy replays or compounding separately reset equity.
  Entries are descriptive only; no filters/stop changes or forecasts.
.EXAMPLE
  .\scripts\analyze-expectancy-regimes.ps1 -PrintOnly
.EXAMPLE
  .\scripts\analyze-expectancy-regimes.ps1
#>
[CmdletBinding()]
param(
    [string]$ResultsDir=(Join-Path (Split-Path $PSScriptRoot -Parent) 'results'),
    [long]$AsOf=1791845999999,
    [ValidateRange(1,1000000000)][decimal]$ExpectedCapitalUsdt=1500,
    [switch]$PrintOnly
)
$ErrorActionPreference='Stop'
$Invariant=[System.Globalization.CultureInfo]::InvariantCulture
$cohorts=@(
    [pscustomobject]@{Name='w16-21';Prefix='short-regime';First=16;Last=21},
    [pscustomobject]@{Name='w22-27';Prefix='short-regime';First=22;Last=27},
    [pscustomobject]@{Name='w28-33';Prefix='short-range-oos';First=28;Last=33},
    [pscustomobject]@{Name='w34-39';Prefix='range-l2-oos';First=34;Last=39}
)
if($PrintOnly){
    Write-Output 'EXPECTANCY_SCOPE=INSPECTED_WINDOWS_ONLY'
    Write-Output 'EXPECTANCY_CONTROL_POLICIES=CONTROL'
    Write-Output 'EXPECTANCY_WINDOWS=16..39'
    Write-Output 'EXPECTANCY_COHORTS=16-21,22-27,28-33,34-39'
    Write-Output 'EXPECTANCY_NUMBER_OF_PORTFOLIOS=24'
    Write-Output 'EXPECTANCY_NO_ORDERS=true'
    Write-Output 'EXPECTANCY_NO_LUNA=true'
    Write-Output 'EXPECTANCY_NO_HTTP=true'
    Write-Output 'EXPECTANCY_NEVER_REPLAY_BY_DELETING_TRADES=true'
    Write-Output 'EXPECTANCY_3_6_9_BANDS=3,6,9,30,60,90'
    foreach($c in $cohorts){
        Write-Output ("EXPECTANCY_COHORT={0};FIRST={1};LAST={2}" -f $c.Name,$c.First,$c.Last)
    }
    return
}
function D([object]$v,[string]$field) {
    if($null -eq $v -or [string]::IsNullOrWhiteSpace([string]$v)){
        throw "Missing number: $field"
    }
    try {
        return [decimal]::Parse(([string]$v).Replace(',','.'),
            [System.Globalization.NumberStyles]::Float,
            [System.Globalization.CultureInfo]::InvariantCulture)
    }catch{throw "Invalid number in $field : $v"}
}
function OptionalD([object]$v) {
    if($null -eq $v -or [string]::IsNullOrWhiteSpace([string]$v)){
        return $null
    }
    return D $v 'optional metric'
}
function Eq([object]$left,[object]$right,[string]$name){
    if([math]::Abs((D $left $name)-(D $right $name)) -gt [decimal]'0.001'){
        throw "Financial reconciliation failed for $name : $left vs $right"
    }
}
function Bucket([decimal]$v,[decimal]$unit,[string]$label) {
    if($v -le 3*$unit){return "LE_3_$label"}
    if($v -le 6*$unit){return "GT_3_LE_6_$label"}
    if($v -le 9*$unit){return "GT_6_LE_9_$label"}
    return "GT_9_$label"
}
function Num([decimal]$v) { return $v.ToString('0.000000',[System.Globalization.CultureInfo]::InvariantCulture) }
$root=(Resolve-Path $ResultsDir -ErrorAction Stop).Path
$trades=New-Object System.Collections.Generic.List[object]
$windows=New-Object System.Collections.Generic.List[object]
$seenWindows=@{}
foreach($c in $cohorts){
    $suffix="SOLUSDT-RETEST-$AsOf-5m-$($c.Name).csv"
    $tp=Join-Path $root "$($c.Prefix)-trades-$suffix"
    $wp=Join-Path $root "$($c.Prefix)-windows-$suffix"
    foreach($file in @($tp,$wp)){if(-not (Test-Path -LiteralPath $file)){throw "Required historical file not found: $file"}}
    $sourceTrades=@(Import-Csv -LiteralPath $tp)
    $sourceWindows=@(Import-Csv -LiteralPath $wp)
    foreach($w in $sourceWindows | Where-Object {$_.Policy -eq 'CONTROL'}){
        [int]$id=[int]$w.Window
        if($id -lt $c.First -or $id -gt $c.Last -or $seenWindows.ContainsKey([string]$id)){
            throw "Duplicate/invalid CONTROL window: $id"
        }
        $seenWindows[[string]$id]=$true
        [decimal]$capital=D $w.StartingEquityUsdt "w$id capital"
        Eq $capital $ExpectedCapitalUsdt "w$id initial capital"
        [decimal]$net=D $w.NetClosedUsdt "w$id net closed"
        [decimal]$marked=D $w.MarkedPnlUsdt "w$id marked"
        [decimal]$long=D $w.LongClosedPnlUsdt "w$id LONG"
        [decimal]$short=D $w.ShortClosedPnlUsdt "w$id SHORT"
        Eq ($long+$short) $net "w$id sides"
        [int]$n=if($null -ne $w.TradesClosed -and $w.TradesClosed -ne ''){[int]$w.TradesClosed}else{[int]$w.Trades}
        $windows.Add([pscustomobject]@{
            Window=$id;Cohort=$c.Name;FromOpen=[long]$w.FromOpen;AsOf=[long]$w.AsOf
            InitialCapitalUsdt=$capital;TradesClosed=$n
            LongClosedPnlUsdt=$long;ShortClosedPnlUsdt=$short
            NetClosedUsdt=$net;MarkedPnlUsdt=$marked
            MaxDrawdownPct=D $w.MaxDrawdownPct "w$id drawdown"
            FeesUsdt=D $w.FeesUsdt "w$id fees"
            OpenPositionSide=[string]$w.OpenPositionSide
            ResearchStatus='RETROSPECTIVE_ALREADY_INSPECTED'
        })
    }
    foreach($t in $sourceTrades | Where-Object {$_.Policy -eq 'CONTROL'}){
        [int]$id=[int]$t.Window
        if($id -lt $c.First -or $id -gt $c.Last){throw "Trade found in wrong window: $id"}
        if($t.Side -notin @('LONG','SHORT')){throw "Unknown trade side: $($t.Side)"}
        [decimal]$risk=D $t.InitialRiskUsdt "w$id initial risk"
        if($risk -le 0){throw "Invalid initial risk in window $id"}
        [decimal]$net=D $t.NetPnlUsdt "w$id trade net"
        [decimal]$fee=D $t.FeesUsdt "w$id trade fee"
        [long]$entry=[long]$t.EnteredAtOpenTime
        [long]$signal=if($null -ne $t.SignalCloseTime -and $t.SignalCloseTime -ne ''){
            [long]$t.SignalCloseTime
        }else{[long]$t.SignalAtCloseTime}
        if($signal -ge $entry){throw "Future signal used in window $id"}
        [decimal]$cost=D $t.EstimatedCostToInitialRiskPct "w$id cost/risk"
        [decimal]$stop=D $t.StopDistancePct "w$id stop distance"
        if($cost -lt 0 -or $stop -le 0){throw "Invalid entry cost/stop in window $id"}
        $setup=if($null -ne $t.EntrySetup -and $t.EntrySetup -ne ''){$t.EntrySetup}else{'BREAKOUT_RETEST'}
        $mfe=OptionalD $t.MfeR
        $mae=OptionalD $t.MaeR
        $giveback=OptionalD $t.GivebackFromBestCloseUsdt
        $trades.Add([pscustomobject]@{
            Window=$id;Cohort=$c.Name;Policy='CONTROL';Side=$t.Side
            EntrySetup=$setup;EnteredAtOpenTime=$entry;SignalCloseTime=$signal
            ExitCandleOpenTime=[long]$t.ExitCandleOpenTime
            Trend5m=[string]$t.PrimaryTrend;Trend15m=[string]$t.Trend15m
            EntryBreakoutState=[string]$t.EntryBreakoutState
            VolumeLevel=[string]$t.VolumeLevel
            StopDistancePct=$stop;CostToRiskPct=$cost
            StopBand=(Bucket $stop 0.1 'x0.1PCT')
            CostBand=(Bucket $cost 10 'x10PCT')
            InitialRiskUsdt=$risk;NetPnlUsdt=$net;FeesUsdt=$fee
            NetR=($net/$risk)
            Outcome=if($net -gt 0){'WIN'}else{'NON_WIN'}
            ExitReason=[string]$t.ExitReason
            MfeR=$mfe;MaeR=$mae;GivebackFromBestCloseUsdt=$giveback
            PostEntryMetricsAvailable=($null -ne $mfe -and $null -ne $mae)
            Timing='ENTRY_KNOWN_VS_POST_ENTRY_SEPARATED'
        })
    }
}
if($seenWindows.Count -ne 24){throw "Expected 24 distinct CONTROL portfolios; got $($seenWindows.Count)"}
$byWindow=@{};foreach($t in $trades){$key=[string]$t.Window;if(-not $byWindow.ContainsKey($key)){$byWindow[$key]=New-Object System.Collections.Generic.List[object]};$byWindow[$key].Add($t)}
foreach($w in $windows){
    $items=@($byWindow[[string]$w.Window].ToArray())
    if($items.Count -ne $w.TradesClosed){throw "w$($w.Window): trade count does not match portfolio"}
    [decimal]$all=0;[decimal]$lng=0;[decimal]$shr=0;[decimal]$fees=0
    foreach($t in $items){
        $all+=$t.NetPnlUsdt;$fees+=$t.FeesUsdt
        if($t.Side -eq 'LONG'){$lng+=$t.NetPnlUsdt}else{$shr+=$t.NetPnlUsdt}
    }
    Eq $all $w.NetClosedUsdt "w$($w.Window) trade totals"
    Eq $lng $w.LongClosedPnlUsdt "w$($w.Window) LONG reconciliation"
    Eq $shr $w.ShortClosedPnlUsdt "w$($w.Window) SHORT reconciliation"
    Eq $fees $w.FeesUsdt "w$($w.Window) fee reconciliation"
}
function Metric([object[]]$subset,[string]$scope,[string]$side,
                [string]$feature,[string]$value,[string]$knownAt) {
    if($subset.Count -eq 0){return $null}
    [decimal]$winSum=0;[decimal]$nonWinSum=0;[decimal]$totalR=0
    [decimal]$totalFees=0;[decimal]$mfeSum=0;[decimal]$maeSum=0
    $positive=New-Object System.Collections.Generic.List[decimal]
    $nWin=0;$nNonwin=0;$postCount=0;$covered=@{}
    foreach($t in $subset){
        $totalR+=$t.NetR;$totalFees+=$t.FeesUsdt
        $covered[[string]$t.Window]=$true
        if($t.NetPnlUsdt -gt 0){
            $nWin++;$winSum+=$t.NetPnlUsdt;$positive.Add($t.NetPnlUsdt)
        }else{
            $nNonwin++;$nonWinSum+=$t.NetPnlUsdt
        }
        if($t.PostEntryMetricsAvailable){
            $postCount++;$mfeSum+=$t.MfeR;$maeSum+=$t.MaeR
        }
    }
    [decimal]$avgWin=if($nWin -gt 0){$winSum/$nWin}else{0}
    [decimal]$avgLoss=if($nNonwin -gt 0){-$nonWinSum/$nNonwin}else{0}
    [decimal]$requiredWinRate=if($avgWin+$avgLoss -gt 0){
        100*$avgLoss/($avgWin+$avgLoss)
    }else{0}
    [decimal]$requiredMeanWin=if($nWin -gt 0){-$nonWinSum/$nWin}else{0}
    $winSorted=@($positive | Sort-Object -Descending)
    [decimal]$top3=0;for($i=0;$i -lt [math]::Min(3,$winSorted.Count);$i++){$top3+=$winSorted[$i]}
    $n=$subset.Count
    return [pscustomobject]@{
        Scope=$scope;Side=$side;Feature=$feature;Value=$value
        FeatureTiming=$knownAt;Trades=$n;Wins=$nWin;NonWins=$nNonwin
        WinRatePct=(Num (100*[decimal]$nWin/$n))
        PnLNetUsdt=(Num ($winSum+$nonWinSum))
        ExpectancyUsdtPerTrade=(Num (($winSum+$nonWinSum)/$n))
        ExpectancyNetR=(Num ($totalR/$n))
        MeanWinUsdt=(Num $avgWin);MeanNonWinLossUsdt=(Num $avgLoss)
        RequiredWinRatePctWithCurrentPayoff=(Num $requiredWinRate)
        RequiredMeanWinUsdtWithCurrentWinRate=(Num $requiredMeanWin)
        ProfitFactor=if($nonWinSum -lt 0){Num ($winSum/(-$nonWinSum))}else{''}
        Top3WinningPnlUsdt=(Num $top3)
        Top3ShareOfGrossWinningsPct=if($winSum -gt 0){Num (100*$top3/$winSum)}else{''}
        TotalFeesAlreadyIncludedUsdt=(Num $totalFees)
        WindowsCovered=$covered.Count;SmallSample=($n -lt 9 -or $covered.Count -lt 3)
        PostEntryMetricTradeCount=$postCount
        MeanMfeROnAvailable=if($postCount -gt 0){Num ($mfeSum/$postCount)}else{''}
        MeanMaeROnAvailable=if($postCount -gt 0){Num ($maeSum/$postCount)}else{''}
        Interpretation='RETROSPECTIVE_NOT_A_TRADE_FILTER'
    }
}
$allTrades=@($trades.ToArray())
$metrics=New-Object System.Collections.Generic.List[object]
foreach($scope in @('ALL_WINDOWS')+@($cohorts | ForEach-Object {$_.Name})){
    $scopeTrades=if($scope -eq 'ALL_WINDOWS'){$allTrades}else{@($allTrades | Where-Object {$_.Cohort -eq $scope})}
    foreach($side in @('ALL','LONG','SHORT')){
        $subset=if($side -eq 'ALL'){$scopeTrades}else{@($scopeTrades | Where-Object {$_.Side -eq $side})}
        if($subset.Count -eq 0){continue}
        $overall=Metric $subset $scope $side 'ALL' 'ALL' 'ENTRY'
        if($null -ne $overall){$metrics.Add($overall)}
        if($side -eq 'ALL'){continue}
        foreach($field in @('Trend15m','Trend5m','EntryBreakoutState','VolumeLevel',
                'StopBand','CostBand','EntrySetup')){
            $groups=@($subset | Group-Object -Property $field)
            foreach($g in $groups){
                $row=Metric @($g.Group) $scope $side $field ([string]$g.Name) 'ENTRY'
                if($null -ne $row){$metrics.Add($row)}
            }
        }
        foreach($g in @($subset | Group-Object -Property ExitReason)){
            $row=Metric @($g.Group) $scope $side 'ExitReason' ([string]$g.Name) 'OUTCOME'
            if($null -ne $row){$metrics.Add($row)}
        }
        # Joint context: prevents mistaking an aggregate RANGE correlation
        # for an actionable side-independent directional rule.
        foreach($g in @($subset | Group-Object -Property Trend15m,EntryBreakoutState)){
            $row=Metric @($g.Group) $scope $side 'Trend15m+Breakout' ([string]$g.Name) 'ENTRY'
            if($null -ne $row){$metrics.Add($row)}
        }
    }
}
$stem="SOLUSDT-RETEST-$AsOf-5m-w16-39"
$windowOutput=Join-Path $root "expectancy-audit-windows-$stem.csv"
$tradeOutput=Join-Path $root "expectancy-audit-trades-$stem.csv"
$regimeOutput=Join-Path $root "expectancy-audit-regimes-$stem.csv"
foreach($output in @($windowOutput,$tradeOutput,$regimeOutput)){
    if(Test-Path -LiteralPath $output){throw "Will not overwrite an existing research artifact: $output"}
}
@($windows.ToArray() | Sort-Object Window) | Export-Csv $windowOutput -NoTypeInformation -Encoding UTF8
@($allTrades | Sort-Object Window,EnteredAtOpenTime) | Export-Csv $tradeOutput -NoTypeInformation -Encoding UTF8
@($metrics.ToArray() | Sort-Object Scope,Side,Feature,Value) | Export-Csv $regimeOutput -NoTypeInformation -Encoding UTF8
Write-Host 'EXPECTANCY AUDIT, inspected history only; no new signals or orders:'
@($metrics | Where-Object {$_.Scope -eq 'ALL_WINDOWS' -and $_.Feature -eq 'ALL'}) |
    Format-Table Side,Trades,WinRatePct,PnLNetUsdt,ExpectancyNetR,ProfitFactor,RequiredWinRatePctWithCurrentPayoff,WindowsCovered -AutoSize
Write-Host "Saved: $windowOutput"
Write-Host "Saved: $tradeOutput"
Write-Host "Saved: $regimeOutput"
