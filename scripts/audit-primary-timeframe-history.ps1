<#
.SYNOPSIS
  READ-ONLY forensic audit of Testnet candles and decision funnel, w46..51.
.DESCRIPTION
  Uses previously inspected 300h windows 46..51, no new setup or policy.
  Measures 5m/15m/1h activity, OHLC aggregation and every decision
  rejection stage from position-aware replay snapshots.
  Reads LOCAL TESTNET MySQL only; never syncs or modifies candles.
.EXAMPLE
  .\scripts\audit-primary-timeframe-history.ps1 -PrintOnly
.EXAMPLE
  .\scripts\audit-primary-timeframe-history.ps1 -SyncHistory
#>
[CmdletBinding()]
param(
    [ValidateSet('SOLUSDT')][string]$Symbol='SOLUSDT',
    [ValidateSet('testnet')][string]$Source='testnet',
    [ValidateRange(46,100)][int]$StartWindow=46,
    [ValidateSet(3,6,9)][int]$Windows=6,
    [long]$AsOf=1791845999999,
    [ValidateRange(21,500)][int]$ContextLimit=200,
    [ValidateRange(1,100)][int]$CostLimitPct=50,
    [ValidateRange(1,1000000000)][decimal]$ExpectedCapitalUsdt=1500,
    [ValidatePattern('^https?://(127\.0\.0\.1|localhost)(:\d+)?$')]
    [string]$BaseUrl='http://127.0.0.1:8080',
    [ValidateRange(30,1800)][int]$TimeoutSec=900,
    [switch]$PrintOnly
)
$ErrorActionPreference='Stop'
$BaseUrl=$BaseUrl.TrimEnd('/')
[long]$windowMs=300L*3600000L
if($AsOf -le 0 -or $AsOf % 3600000L -ne 3599999L){
    throw 'AsOf must be a completed hourly UTC close'
}
$scenarios=@('PRIMARY_5M_CONTEXT_15M','PRIMARY_15M_CONTEXT_1H')
function Money([object]$value,[string]$field){
    if($null -eq $value -or [string]::IsNullOrWhiteSpace([string]$value)){
        throw ("Campo decimal ausente: {0}" -f $field)
    }
    return [decimal]::Parse(([string]$value).Replace(',','.'),
        [System.Globalization.CultureInfo]::InvariantCulture)
}
function Near([object]$a,[object]$b,[decimal]$tolerance,[string]$field){
    [decimal]$distance=[math]::Abs((Money $a $field)-(Money $b $field))
    if($distance -gt $tolerance){
        throw ("Divergencia {0}: abs(delta)={1}, tolerancia={2}" -f $field,$distance,$tolerance)
    }
}
$plan=@()
for($idx=0;$idx -lt $Windows;$idx++){
    [int]$windowNumber=$StartWindow+$idx
    [long]$end=$AsOf-([long]($windowNumber-1)*$windowMs)
    [long]$from=$end-$windowMs+1
    if($from -lt 0){throw 'Periodo anterior a epoca Unix'}
    $plan += [PSCustomObject]@{
        Window=$windowNumber;From=$from;AsOf=$end
        FromUtc=[DateTimeOffset]::FromUnixTimeMilliseconds($from).UtcDateTime.ToString('yyyy-MM-dd HH:mm')
        UntilUtc=[DateTimeOffset]::FromUnixTimeMilliseconds($end).UtcDateTime.ToString('yyyy-MM-dd HH:mm')
    }
}
if($PrintOnly){
    Write-Output 'HISTORY_AUDIT_WINDOWS=46..51'
    Write-Output 'HISTORY_AUDIT_RESEARCH_STATUS=RETROSPECTIVE_INSPECTED'
    Write-Output 'HISTORY_AUDIT_INTERVALS=5m,15m,1h'
    Write-Output 'HISTORY_AUDIT_EXPECTED=3600,1200,300'
    Write-Output 'HISTORY_AUDIT_NO_SYNC=true'
    Write-Output 'HISTORY_AUDIT_NO_ORDERS=true'
    Write-Output 'HISTORY_AUDIT_NO_LUNA=true'
    foreach($period in $plan){
        Write-Output ("HISTORY_AUDIT_WINDOW={0};FROM={1};ASOF={2}" -f $period.Window,$period.From,$period.AsOf)
    }
    return
}
$health=Invoke-RestMethod -Uri "$BaseUrl/actuator/health" -TimeoutSec 15 -ErrorAction Stop
$state=Invoke-RestMethod -Uri "$BaseUrl/api/v1/backtest/status" -TimeoutSec 15 -ErrorAction Stop
$market=Invoke-RestMethod -Uri "$BaseUrl/api/v1/market-data/settings" -TimeoutSec 15 -ErrorAction Stop
$risk=Invoke-RestMethod -Uri "$BaseUrl/api/v1/risk/status" -TimeoutSec 15 -ErrorAction Stop
$config=Invoke-RestMethod -Uri "$BaseUrl/api/v1/backtest/research-config" -TimeoutSec 15 -ErrorAction Stop
if($health.status -ne 'UP' -or $state.mode -ne 'SIMULATION_ONLY' -or
   $state.ordersEnabled -or $market.source -ne 'TESTNET' -or
   -not $risk.configured -or $risk.ordersEnabled -or
   $market.websocketEnabled -or $market.bootstrapEnabled -or
   $market.intervals -notcontains '5m' -or $market.intervals -notcontains '15m' -or
   $market.intervals -notcontains '1h'){
    throw 'Backend deve estar em TESTNET/SIMULATION_ONLY, WS/bootstrap OFF, 5m+15m e sem ordens'
}
if($config.ordersEnabled -or $config.executionMode -notin @('DISABLED','BACKTEST') -or
   $config.symbols -notcontains $Symbol -or
   $config.requireHigherTimeframeAlignment -or
   -not $config.requireDominantTakerVolume -or
   [int]$config.breakoutLookback -ne 10 -or
   [int]$config.retestMaxBars -ne 4 -or
   [int]$config.maxPrimaryStalenessBars -ne 2){
    throw 'Config real difere: validar SOLUSDT, DISABLED/BACKTEST, higherTF=false, taker=true, lookback=10, retest=4, staleness=2'
}
Near $config.capitalUsdt $ExpectedCapitalUsdt ([decimal]'0.0001') 'capital efetivo'
Near $config.riskPerTradePct 0.5 ([decimal]'0.0001') 'risco por trade'
Near $config.dailyStopPct 3 ([decimal]'0.0001') 'stop diario'
if([int]$config.leverage -ne 1){throw 'Alavancagem efetiva deve ser 1x'}
Near $config.feePerSidePct 0.05 ([decimal]'0.0001') 'taxa por lado'
Near $config.slippagePerSidePct 0.02 ([decimal]'0.0001') 'slippage por lado'
Near $config.breakEvenTriggerR 1 ([decimal]'0.0001') 'gatilho BE'
Near $config.structuralAtrBuffer 0.5 ([decimal]'0.0001') 'buffer estrutural'
Near $config.maxCostToInitialRiskPct $CostLimitPct ([decimal]'0.0001') 'filtro de custo configurado'
Write-Host 'Config efetiva conferida: capital/risco/alavancagem/custos/Java/seguranca'

. (Join-Path $PSScriptRoot 'lib/binance-contract-filters.ps1')
$exchange=Invoke-RestMethod -Uri 'https://demo-fapi.binance.com/fapi/v1/exchangeInfo' -TimeoutSec 60
$filters=Symbol-ContractFilters -ExchangeInfo $exchange -Symbol $Symbol


$root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$out=Join-Path $root 'results'
$stem="$Symbol-RETEST-$AsOf-5m-vs-15m-w$StartWindow-$($StartWindow+$Windows-1)"
$qualityRows=@();$crossRows=@();$trendRows=@();$funnelRows=@()
foreach($period in $plan){
    $w=[int]$period.Window
    $body=@{
        symbol=$Symbol;intervals=@('5m','15m');steps=3600
        contextLimit=$ContextLimit;asOf=$period.AsOf
        includeLuna=$false;includeDecisionHistory=$true
        priceTick=$filters.priceTick;quantityStep=$filters.quantityStep
        minimumQuantity=$filters.minimumQuantity
        minimumNotionalUsdt=$filters.minimumNotionalUsdt
        breakEvenTriggerROverride='1.0'
        maxCostToInitialRiskPctOverride=$CostLimitPct
        pullbackEnabledOverride=$false;breakoutRetestEnabledOverride=$true
    } | ConvertTo-Json -Depth 8
    Write-Host ("AUDIT 5m/15m/1h Testnet historical window {0}" -f $w)
    $params=@{Uri="$BaseUrl/api/v1/backtest/audit-primary-timeframes"
              Method='Post';ContentType='application/json';Body=$body
              TimeoutSec=$TimeoutSec;ErrorAction='Stop'}
    try{$response=Invoke-RestMethod @params}
    catch{
        $details=$null
        if($_.ErrorDetails -and $_.ErrorDetails.Message){$details=[string]$_.ErrorDetails.Message}
        if([string]::IsNullOrWhiteSpace($details) -and $_.Exception.Response){
            try{
                $stream=$_.Exception.Response.GetResponseStream()
                if($stream){
                    $reader=New-Object System.IO.StreamReader($stream)
                    try{$details=$reader.ReadToEnd()}finally{$reader.Dispose()}
                }
            }catch{}
        }
        if([string]::IsNullOrWhiteSpace($details)){$details=$_.Exception.Message}
        throw ("HISTORY AUDIT window {0}: {1}" -f $w,$details)
    }
    if($response.symbol -ne $Symbol -or [long]$response.fromOpenTime -ne [long]$period.From -or
       [long]$response.asOfCloseTime -ne [long]$period.AsOf -or
       [int]$response.periodHours -ne 300){
        throw ("Window {0}: audit spans differ from frozen 300h period" -f $w)
    }
    $q=@($response.candles);$cross=@($response.crossTimeframes)
    $trends=@($response.trends);$funnel=@($response.funnel)
    if($q.Count -ne 3 -or $cross.Count -ne 2 -or $trends.Count -ne 16){
        throw ("Window {0}: candle/rollup/trend diagnostic incomplete" -f $w)
    }
    foreach($c in $q){
        $expected=switch($c.interval){'5m'{3600};'15m'{1200};'1h'{300};default{throw 'Unexpected interval'}}
        if([int]$c.expectedBars -ne $expected -or [int]$c.observedBars -ne $expected -or
           [int]$c.noActivityBars -lt 0 -or [int]$c.noActivityBars -gt $expected -or
           [int]$c.longestNoActivityRunBars -gt $expected){
            throw ("Window {0}: unexpected candle counts in {1}" -f $w,$c.interval)
        }
        $qualityRows += [pscustomobject]@{
            Window=$w;FromOpen=$period.From;AsOf=$period.AsOf;Interval=$c.interval
            ExpectedBars=$c.expectedBars;ObservedBars=$c.observedBars
            ZeroTradeBars=$c.zeroTradeBars;ZeroBaseVolumeBars=$c.zeroBaseVolumeBars
            NoActivityBars=$c.noActivityBars;NoActivityPct=$c.noActivityPct
            ZeroRangeBars=$c.zeroRangeBars;ZeroRangeWithTradesBars=$c.zeroRangeWithTradesBars
            InconsistentVolumeTradeBars=$c.inconsistentVolumeTradeBars
            LongestNoActivityRunBars=$c.longestNoActivityRunBars
            LongestNoActivityMinutes=$c.longestNoActivityMinutes
        }
    }
    foreach($x in $cross){
        if(($x.smallerInterval -eq '5m' -and $x.largerInterval -eq '15m') -or
           ($x.smallerInterval -eq '15m' -and $x.largerInterval -eq '1h')) {
            $crossRows += [pscustomobject]@{
                Window=$w;FromOpen=$period.From;AsOf=$period.AsOf
                Small=$x.smallerInterval;Large=$x.largerInterval
                Rollups=$x.expectedRollups;OhlcMismatches=$x.ohlcMismatchRollups
                VolumeMismatches=$x.volumeMismatchRollups
            }
        }else{throw 'Unexpected rollup pair'}
    }
    foreach($t in $trends){
        if($t.arm -notin $scenarios -or $t.interval -notin @('5m','15m','1h') -or
           $t.trend -notin @('UP','DOWN','RANGE','UNDETERMINED')){
            throw ("Window {0}: invalid trend record" -f $w)
        }
        $trendRows += [pscustomobject]@{
            Window=$w;Arm=$t.arm;Interval=$t.interval;Role=$t.role
            Trend=$t.trend;Observations=$t.observations
            TotalUniqueCandles=$t.totalUniqueCandles
        }
    }
    foreach($arm in $scenarios){
        $subset=@($funnel|Where-Object {$_.arm -eq $arm})
        $expectedDecisions=if($arm -eq 'PRIMARY_5M_CONTEXT_15M'){3599}else{1199}
        [int]$sum=0
        foreach($f in $subset){
            if([int]$f.totalDecisionCandles -ne $expectedDecisions -or
               [int]$f.decisions -lt 0){throw "Invalid decision denominator"}
            $sum += [int]$f.decisions
            $funnelRows += [pscustomobject]@{
                Window=$w;Arm=$arm;Stage=$f.stage;Reason=$f.reason
                Decisions=$f.decisions;TotalDecisionCandles=$f.totalDecisionCandles
                ProposedEntries=$f.proposedEntries;FilledEntries=$f.filledEntries
                BlockedByRisk=$f.blockedByRisk
            }
        }
        if($sum -ne $expectedDecisions){
            throw ("Window {0}, {1}: decision stages do not add up" -f $w,$arm)
        }
    }
}
if($qualityRows.Count -ne 3*$Windows -or $crossRows.Count -ne 2*$Windows -or
   $trendRows.Count -ne 16*$Windows -or $funnelRows.Count -lt 2*$Windows){
    throw 'Audit incomplete: no partial CSV output'
}
$files=@(
    (Join-Path $out "primary-timeframe-audit-candles-$stem.csv"),
    (Join-Path $out "primary-timeframe-audit-cross-$stem.csv"),
    (Join-Path $out "primary-timeframe-audit-trends-$stem.csv"),
    (Join-Path $out "primary-timeframe-audit-funnel-$stem.csv")
)
foreach($file in $files){if(Test-Path $file){throw "Refusing to overwrite an existing audit: $file"}}
New-Item -ItemType Directory -Path $out -Force | Out-Null
$qualityRows|Export-Csv -Path $files[0] -NoTypeInformation -Encoding UTF8
$crossRows|Export-Csv -Path $files[1] -NoTypeInformation -Encoding UTF8
$trendRows|Export-Csv -Path $files[2] -NoTypeInformation -Encoding UTF8
$funnelRows|Export-Csv -Path $files[3] -NoTypeInformation -Encoding UTF8
Write-Host 'Historical quality: Testnet-only, no data writes. Counts are NOT proof of tradeability.'
$qualityRows | Format-Table Window,Interval,NoActivityPct,LongestNoActivityMinutes,ZeroRangeWithTradesBars -AutoSize
foreach($file in $files){Write-Host "Saved: $file"}
