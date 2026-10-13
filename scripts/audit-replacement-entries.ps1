<#
.SYNOPSIS
  Separa entradas novas/preservadas/removidas apos filtro BLOCK_BOTH.
.DESCRIPTION
  Analisa CSVs REAIS dos quatro replays dinamicos; sem requisicoes de rede.
  Cohorts por CHAVE (janela, lado, timestamp de entrada). Exit time pode
  mudar por dimensionamento, mas nao muda a identidade do sinal de entrada.
  Atributos preditivos sao apenas snapshots do sinal/abertura da entrada.
  Resultado, duracao, MFE/MAE e exit reason sao ROTULOS pos-entrada.
  Experimento exploratorio: as nove janelas ja foram inspecionadas.
.EXAMPLE
  .\scripts\audit-replacement-entries.ps1 -Symbol SOLUSDT -Windows 9
#>
[CmdletBinding()]
param(
    [ValidateSet('SOLUSDT','BTCUSDT','ETHUSDT')][string]$Symbol='SOLUSDT',
    [ValidateRange(1,9)][int]$Windows=9,
    [long]$AsOf=1791845999999,
    [string]$ResultsDir='',
    [switch]$PrintOnly
)
$ErrorActionPreference='Stop'
[long]$windowMs=300L*3600000L
if($AsOf -le 0 -or $AsOf % 900000L -ne 899999L){throw 'AsOf nao e fechamento 15m UTC'}
if([string]::IsNullOrWhiteSpace($ResultsDir)){
    $root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
    $ResultsDir=Join-Path $root 'results'
}
$stem="$Symbol-RETEST-$AsOf-5m-w1-$Windows"
$tradesFile=Join-Path $ResultsDir "entry-filter-trades-$stem.csv"
$windowsFile=Join-Path $ResultsDir "entry-filter-windows-$stem.csv"
if($PrintOnly){
    Write-Output "REPLACEMENT_TRADES=$tradesFile"
    Write-Output "REPLACEMENT_WINDOWS=$windowsFile"
    Write-Output 'REPLACEMENT_COHORTS=REMOVED,PRESERVED,NEW'
    Write-Output 'REPLACEMENT_ONLY_CLOSED_TRADES=true'
    Write-Output 'REPLACEMENT_NO_HTTP=true'
    return
}
function Money([object]$value,[string]$name){
    if($null -eq $value -or [string]::IsNullOrWhiteSpace([string]$value)){
        throw ("Decimal ausente: {0}" -f $name)
    }
    return [decimal]::Parse(([string]$value).Replace(',','.'),
        [System.Globalization.CultureInfo]::InvariantCulture)
}
function Near([object]$actual,[object]$expected,[decimal]$tolerance,[string]$name){
    [decimal]$difference=[math]::Abs((Money $actual $name)-(Money $expected $name))
    if($difference -gt $tolerance){
        throw ("Reconciliacao falhou {0}: abs(delta)={1}; tolerancia={2}" -f $name,$difference,$tolerance)
    }
}
function Outcome([decimal]$pnl){
    if($pnl -gt 0){return 'WIN'}
    if($pnl -lt 0){return 'LOSS'}
    return 'FLAT'
}
function GroupBucket([object]$value,[string]$name){
    if($null -eq $value -or [string]::IsNullOrWhiteSpace([string]$value)){return 'MISSING'}
    [decimal]$v=Money $value $name
    switch($name){
        'RelativeVolume' {
            if($v -lt [decimal]'0.8'){return '<0.8'}
            if($v -lt [decimal]'1.2'){return '0.8-<1.2'}
            return '>=1.2'
        }
        'TakerBuyShare' {
            if($v -lt [decimal]'0.4'){return '<0.4'}
            if($v -le [decimal]'0.6'){return '0.4-0.6'}
            return '>0.6'
        }
        'EstimatedCostToInitialRiskPct' {
            if($v -lt 20){return '<20%'}
            if($v -lt 40){return '20-<40%'}
            return '>=40%'
        }
    }
    return 'OTHER'
}
function Build-Row([object]$t,[int]$windowId,[string]$cohort,[decimal]$net,[object]$originalNet){
    return [PSCustomObject]@{
        Window=$windowId;Cohort=$cohort;Side=$t.Side
        EnteredAtOpenTime=$t.EnteredAtOpenTime
        SignalAtCloseTime=$t.SignalAtCloseTime
        EntryHourUtc=$t.EntryHourUtc
        ExitCandleOpenTime=$t.ExitCandleOpenTime
        EntryBreakoutState=$t.EntryBreakoutState
        PrimaryTrend=$t.PrimaryTrend;Trend15m=$t.Trend15m
        HigherTimeframeConflicts=$t.HigherTimeframeConflicts
        VolumeLevel=$t.VolumeLevel;TakerBalance=$t.TakerBalance
        RelativeVolume=$t.RelativeVolume;RelativeSpread=$t.RelativeSpread
        TakerBuyShare=$t.TakerBuyShare;EffortResult=$t.EffortResult
        BarDirection=$t.BarDirection;BarTrend=$t.BarTrend;BarDoji=$t.BarDoji
        BarInside=$t.BarInside;BarOutside=$t.BarOutside
        BullishRejection=$t.BullishRejection;BearishRejection=$t.BearishRejection
        PullbackCandidate=$t.PullbackCandidate
        ConfirmedPivotCount=$t.ConfirmedPivotCount;AtrAtSignal=$t.AtrAtSignal
        StopDistancePct=$t.StopDistancePct;EntryGapPct=$t.EntryGapPct
        EstimatedCostToInitialRiskPct=$t.EstimatedCostToInitialRiskPct
        EstimatedRoundTripCostUsdt=$t.EstimatedRoundTripCostUsdt
        InitialRiskUsdt=$t.InitialRiskUsdt
        NetPnlUsdt=$net;Outcome=(Outcome $net)
        OriginalNetPnlUsdt=if($null -ne $originalNet){$originalNet}else{''}
        PreservedPnlDeltaUsdt=if($null -ne $originalNet){$net-[decimal]$originalNet}else{''}
        ExitReason=$t.ExitReason;DurationCandles=$t.DurationCandles
        MfeR=$t.MfeR;MaeR=$t.MaeR;NetRMultiple=$t.NetRMultiple
    }
}
if(-not (Test-Path $tradesFile) -or -not(Test-Path $windowsFile)){
    throw "CSV de replays ausente. Execute .\scripts\compare-entry-filters.ps1 primeiro."
}
$trades=@(Import-Csv -Path $tradesFile)
$windowsReport=@(Import-Csv -Path $windowsFile)
if($windowsReport.Count -ne 4*$Windows -or $trades.Count -lt 1){
    throw 'Dados insuficientes: esperado 4 resumos por janela e trades exportados'
}
$requiredTrade=@(
    'Window','Filter','Side','EnteredAtOpenTime','ExitCandleOpenTime',
    'NetPnlUsdt','InitialRiskUsdt','EntryBreakoutState','SignalAtCloseTime',
    'EntryHourUtc','PrimaryTrend','Trend15m','HigherTimeframeConflicts',
    'VolumeLevel','TakerBalance','RelativeVolume','RelativeSpread',
    'TakerBuyShare','EffortResult','BarDirection','BarTrend','BarDoji',
    'StopDistancePct','EstimatedCostToInitialRiskPct'
)
foreach($col in $requiredTrade){
    if($col -notin @($trades[0].PSObject.Properties.Name)){
        throw ("Campo {0} ausente: atualize Spring Boot e reexecute compare-entry-filters.ps1" -f $col)
    }
}
$cohortRows=@()
$windowSummary=@()
foreach($period in 1..$Windows){
    [int]$windowId=$period
    [long]$end=$AsOf-([long]($windowId-1)*$windowMs)
    [long]$start=$end-$windowMs+1
    $original=@($trades | Where-Object { [int]$_.Window -eq $windowId -and $_.Filter -eq 'NONE' })
    $filtered=@($trades | Where-Object { [int]$_.Window -eq $windowId -and $_.Filter -eq 'BLOCK_BOTH' })
    $originalSummary=@($windowsReport | Where-Object { [int]$_.Window -eq $windowId -and $_.Filter -eq 'NONE' })
    $filteredSummary=@($windowsReport | Where-Object { [int]$_.Window -eq $windowId -and $_.Filter -eq 'BLOCK_BOTH' })
    if($originalSummary.Count -ne 1 -or $filteredSummary.Count -ne 1){
        throw ("Janela {0}: resumos NONE/BLOCK_BOTH incompletos" -f $windowId)
    }
    if([long]$originalSummary[0].FromOpen -ne $start -or [long]$originalSummary[0].AsOf -ne $end -or
       [long]$filteredSummary[0].FromOpen -ne $start -or [long]$filteredSummary[0].AsOf -ne $end){
        throw ("Janela {0}: limites temporais diferentes" -f $windowId)
    }
    $keysOriginal=@{}
    $keysFiltered=@{}
    foreach($scenarioName in @('NONE','BLOCK_BOTH')){
        $source=if($scenarioName -eq 'NONE'){$original}else{$filtered}
        foreach($trade in $source){
            [long]$entered=[long]$trade.EnteredAtOpenTime
            [long]$exited=[long]$trade.ExitCandleOpenTime
            [long]$signal=[long]$trade.SignalAtCloseTime
            if($entered -lt $start -or $exited -gt $end -or
               $signal -ge $entered -or $exited -lt $entered -or
               $trade.Side -notin @('LONG','SHORT')){
                throw ("Janela {0}: trade fora do periodo ou sinal futuro" -f $windowId)
            }
            $null=Money $trade.NetPnlUsdt 'NetPnlUsdt'
            if((Money $trade.InitialRiskUsdt 'InitialRiskUsdt') -le 0){
                throw ("Janela {0}: risco inicial invalido" -f $windowId)
            }
            $key="$($trade.Side)|$entered"
            $map=if($scenarioName -eq 'NONE'){$keysOriginal}else{$keysFiltered}
            if($map.ContainsKey($key)){
                throw ("Janela {0}: sinal de entrada duplicado em {1}" -f $windowId,$trade.Filter)
            }
            $map[$key]=$trade
        }
    }
    if($keysOriginal.Count -ne [int]$originalSummary[0].TradesClosed -or
       $keysFiltered.Count -ne [int]$filteredSummary[0].TradesClosed){
        throw ("Janela {0}: trades detalhados nao batem com os resumos" -f $windowId)
    }
    [decimal]$oldTotal=0
    [decimal]$newTotal=0
    foreach($t in $original){$oldTotal+=Money $t.NetPnlUsdt 'OriginalPnl'}
    foreach($t in $filtered){$newTotal+=Money $t.NetPnlUsdt 'FilteredPnl'}
    Near $oldTotal $originalSummary[0].NetClosedUsdt ([decimal]'0.001') 'baseline por janela'
    Near $newTotal $filteredSummary[0].NetClosedUsdt ([decimal]'0.001') 'filtrado por janela'

    [decimal]$removedTotal=0
    [decimal]$addedTotal=0
    [decimal]$preservedDelta=0
    $removed=0; $added=0; $preserved=0
    $removedWins=0; $removedLosses=0; $addedWins=0; $addedLosses=0
    foreach($key in $keysOriginal.Keys){
        $t=$keysOriginal[$key]
        [decimal]$pnl=Money $t.NetPnlUsdt 'OriginalPnl'
        if(-not $keysFiltered.ContainsKey($key)){
            $cohort='REMOVED';$removed++;$removedTotal+=$pnl
            if($pnl -gt 0){$removedWins++}elseif($pnl -lt 0){$removedLosses++}
        }else{continue}
        $cohortRows += (Build-Row $t $windowId $cohort $pnl $null)
    }
    foreach($key in $keysFiltered.Keys){
        $t=$keysFiltered[$key]
        [decimal]$pnl=Money $t.NetPnlUsdt 'FilteredPnl'
        if($keysOriginal.ContainsKey($key)){
            $cohort='PRESERVED';$preserved++
            [decimal]$prior=Money $keysOriginal[$key].NetPnlUsdt 'OriginalPnl'
            $preservedDelta+=($pnl-$prior)
            $cohortRows += (Build-Row $t $windowId $cohort $pnl $prior)
        }else{
            $cohort='NEW';$added++;$addedTotal+=$pnl
            if($pnl -gt 0){$addedWins++}elseif($pnl -lt 0){$addedLosses++}
            $cohortRows += (Build-Row $t $windowId $cohort $pnl $null)
        }
    }
    if($removed+$preserved -ne $original.Count -or
       $added+$preserved -ne $filtered.Count){
        throw ("Janela {0}: cohort incompleto" -f $windowId)
    }
    [decimal]$delta=$addedTotal-$removedTotal+$preservedDelta
    Near $delta ($newTotal-$oldTotal) ([decimal]'0.001') 'cohort net delta'
    $windowSummary += [PSCustomObject]@{
        Window=$windowId;OriginalTrades=$original.Count;FilteredTrades=$filtered.Count
        Removed=$removed;RemovedWins=$removedWins;RemovedLosses=$removedLosses
        New=$added;NewWins=$addedWins;NewLosses=$addedLosses
        Preserved=$preserved;OriginalClosedPnlUsdt=$oldTotal
        FilteredClosedPnlUsdt=$newTotal
        RemovedPnlUsdt=$removedTotal;NewPnlUsdt=$addedTotal
        PreservedPnlDeltaUsdt=$preservedDelta
        TotalDeltaUsdt=$delta
    }
}

$profiles=@()
$features=@(
    'Side','EntryBreakoutState','PrimaryTrend','Trend15m',
    'HigherTimeframeConflicts','VolumeLevel','TakerBalance','EffortResult',
    'BarDirection','BarTrend','BarDoji','BarInside','BarOutside',
    'BullishRejection','BearishRejection','PullbackCandidate','EntryHourUtc',
    'RelativeVolume','TakerBuyShare','EstimatedCostToInitialRiskPct'
)
foreach($cohort in @('NEW','PRESERVED','REMOVED')){
    $inCohort=@($cohortRows | Where-Object {$_.Cohort -eq $cohort})
    foreach($feature in $features){
        $groups=@{}
        foreach($trade in $inCohort){
            $value=$trade.PSObject.Properties[$feature].Value
            $bucket=if($feature -in @('RelativeVolume','TakerBuyShare','EstimatedCostToInitialRiskPct')){
                GroupBucket $value $feature
            }elseif($null -eq $value -or [string]::IsNullOrWhiteSpace([string]$value)){
                'MISSING'
            }else{[string]$value}
            if(-not $groups.ContainsKey($bucket)){$groups[$bucket]=@()}
            $groups[$bucket]+=$trade
        }
        foreach($bucket in $groups.Keys){
            $members=@($groups[$bucket])
            $wins=@($members | Where-Object {$_.Outcome -eq 'WIN'}).Count
            $losses=@($members | Where-Object {$_.Outcome -eq 'LOSS'}).Count
            [decimal]$pnl=0
            foreach($member in $members){$pnl+=Money $member.NetPnlUsdt 'PnL grupo'}
            $profiles += [PSCustomObject]@{
                Cohort=$cohort;Feature=$feature;Value=$bucket
                Trades=$members.Count;Wins=$wins;Losses=$losses
                WinRatePct=if($members.Count -gt 0){
                    [math]::Round(100.0*$wins/$members.Count,2)
                }else{0}
                NetPnlUsdt=$pnl;SmallSample=($members.Count -lt 5)
            }
        }
    }
}
$cohortFile=Join-Path $ResultsDir "entry-filter-replacement-trades-$stem.csv"
$summaryFile=Join-Path $ResultsDir "entry-filter-replacement-windows-$stem.csv"
$profileFile=Join-Path $ResultsDir "entry-filter-replacement-patterns-$stem.csv"
$cohortRows | Sort-Object Window,Cohort,EnteredAtOpenTime |
    Export-Csv -Path $cohortFile -NoTypeInformation -Encoding UTF8
$windowSummary | Export-Csv -Path $summaryFile -NoTypeInformation -Encoding UTF8
$profiles | Sort-Object Cohort,Feature,Value |
    Export-Csv -Path $profileFile -NoTypeInformation -Encoding UTF8
Write-Host 'GRUPOS DE ENTRADAS (observaveis no instante do sinal):'
$windowSummary | Format-Table Window,Removed,RemovedLosses,New,NewLosses,Preserved,TotalDeltaUsdt -AutoSize
Write-Host "Saved: $summaryFile"
Write-Host "Saved: $cohortFile"
Write-Host "Saved: $profileFile"
