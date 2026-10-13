<#
 Exporta indicadores conhecidos NO MOMENTO DA ENTRADA dos trades RETEST no 5m.
 9 janelas historicas x 300 horas, Java local; somente SIMULATION_ONLY.
 Os campos futuros (PnL, duracao, MFE etc.) sao ROTULOS, nunca features.
#>
[CmdletBinding()]
param(
  [ValidateSet('SOLUSDT','BTCUSDT','ETHUSDT')][string]$Symbol='SOLUSDT',
  [ValidateSet('RETEST','PULLBACK','BOTH')][string]$Setup='RETEST',
  [ValidateRange(1,9)][int]$Windows=9,
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
[long]$durationMs=300L*3600000L
if ($AsOf -le 0 -or $AsOf % 900000L -ne 899999L) { throw 'AsOf deve ser fechamento UTC 15m' }
$plan=@()
for($n=1;$n -le $Windows;$n++) {
  [long]$cutoff=$AsOf-([long]($n-1)*$durationMs)
  [long]$from=$cutoff-$durationMs+1
  $plan += [PSCustomObject]@{Number=$n; From=$from; AsOf=$cutoff}
}
if($PrintOnly) {
  Write-Output "AUDIT_WINDOWS=$Windows"
  Write-Output 'AUDIT_TIMEFRAME=5m'
  Write-Output 'AUDIT_CANDLES=3600'
  Write-Output 'AUDIT_NO_LUNA=true'
  Write-Output 'AUDIT_NO_ORDERS=true'
  foreach($period in $plan) {
    Write-Output ("AUDIT_WINDOW={0};FROM={1};ASOF={2}" -f $period.Number,$period.From,$period.AsOf)
  }
  return
}
$BaseUrl=$BaseUrl.TrimEnd('/')
$h=Invoke-RestMethod -Uri "$BaseUrl/actuator/health" -TimeoutSec 15 -ErrorAction Stop
$st=Invoke-RestMethod -Uri "$BaseUrl/api/v1/backtest/status" -TimeoutSec 15 -ErrorAction Stop
$md=Invoke-RestMethod -Uri "$BaseUrl/api/v1/market-data/settings" -TimeoutSec 15 -ErrorAction Stop
$risk=Invoke-RestMethod -Uri "$BaseUrl/api/v1/risk/status" -TimeoutSec 15 -ErrorAction Stop
if ($h.status -ne 'UP' -or $st.mode -ne 'SIMULATION_ONLY' -or $st.ordersEnabled -or
    $md.source -ne 'TESTNET' -or -not $risk.configured -or $risk.ordersEnabled) {
  throw 'Backend nao esta configurado para backtest Java isolado TESTNET sem ordens'
}
. (Join-Path $PSScriptRoot 'lib/binance-contract-filters.ps1')
$exchange=Invoke-RestMethod -Uri 'https://demo-fapi.binance.com/fapi/v1/exchangeInfo' -TimeoutSec 60
$filters=Symbol-ContractFilters -ExchangeInfo $exchange -Symbol $Symbol
$culture=[System.Globalization.CultureInfo]::InvariantCulture
function Dec([object]$v) {
  if ($null -eq $v -or [string]::IsNullOrWhiteSpace([string]$v)) { return '' }
  return ([decimal]$v).ToString($culture)
}
$details=@()
$windowsReport=@()
foreach($period in $plan) {
  $w=[int]$period.Number
  Write-Host ("Auditoria de entradas 5m, janela {0}/{1}..." -f $w,$Windows)
  $json=@{
    symbol=$Symbol; intervals=@('5m','15m'); steps=3600
    contextLimit=$ContextLimit; asOf=$period.AsOf; includeLuna=$false
    includeDecisionHistory=$false; priceTick=$filters.priceTick
    quantityStep=$filters.quantityStep
    minimumQuantity=$filters.minimumQuantity
    minimumNotionalUsdt=$filters.minimumNotionalUsdt
    breakEvenTriggerROverride='1.0'
    maxCostToInitialRiskPctOverride=$CostLimitPct
    pullbackEnabledOverride=($Setup -ne 'RETEST')
    breakoutRetestEnabledOverride=($Setup -ne 'PULLBACK')
  } | ConvertTo-Json -Depth 8
  try {
    $args=@{
      Uri="$BaseUrl/api/v1/backtest/run"; Method='Post'
      Body=$json; ContentType='application/json'; TimeoutSec=$TimeoutSec
      ErrorAction='Stop'
    }
    $run=Invoke-RestMethod @args
  } catch {
    throw ("Backtest falhou em 5m/janela {0}: {1}" -f $w,$_.Exception.Message)
  }
  if ($run.symbol -ne $Symbol -or [long]$run.fromCandleOpenTime -ne [long]$period.From -or
    [long]$run.throughCandleCloseTime -ne [long]$period.AsOf -or
    [int]$run.replayedCandles -ne 3600) { throw ("Replay historico deslocado na janela {0}" -f $w) }
  $portfolio=$run.javaPortfolio
  if ($null -eq $portfolio -or
      [math]::Abs([decimal]$portfolio.startingEquityUsdt-$ExpectedCapitalUsdt) -gt [decimal]'0.0001') {
    throw ("Capital inconsistente na janela {0}" -f $w)
  }
  $trades=@($portfolio.trades)
  if ($trades.Count -ne [int]$portfolio.tradesClosed) { throw ("Trades ausentes na janela {0}" -f $w) }
  [decimal]$total=0
  foreach($trade in $trades) {
    $diagnostics=$trade.entryDiagnostics
    if($null -eq $diagnostics -or $null -eq $diagnostics.atSignal -or
       $null -eq $diagnostics.atSignal.signal) {
      throw ("Trade sem snapshot no sinal, janela {0}" -f $w)
    }
    $snap=$diagnostics.atSignal
    $sig=$snap.signal
    $bar=$snap.lastBarPattern
    $htf=@($sig.timeframeTrends | Where-Object { $_.interval -eq '15m' } | Select-Object -First 1)
    $trend15=if($htf.Count) { $htf[0].trend } else { '' }
    if($Setup -eq 'RETEST' -and $sig.setup -ne 'BREAKOUT_RETEST') {
      throw ("Trade nao RETEST, janela {0}" -f $w)
    }
    if([long]$trade.enteredAtOpenTime -lt [long]$period.From -or
       [long]$trade.exitCandleOpenTime -gt [long]$period.AsOf) {
      throw ("Trade fora da janela {0}" -f $w)
    }
    [decimal]$net=[decimal]$trade.netPnlUsdt
    [decimal]$initialRisk=[decimal]$trade.initialRiskUsdt
    if ($initialRisk -le 0) { throw 'Risco inicial nao positivo' }
    $total+=$net
    $hour=[DateTimeOffset]::FromUnixTimeMilliseconds([long]$trade.enteredAtOpenTime).UtcDateTime.Hour
    $details += [PSCustomObject]@{
      Window=$w; Phase=if($w -le 3) {'DISCOVERY'} else {'OOS_AFTER_SELECTION'}
      Symbol=$Symbol; Setup=$Setup; Timeframe='5m'; Side=$trade.side
      EnteredAtOpenTime=$trade.enteredAtOpenTime
      SignalAtCloseTime=$sig.candleCloseTime
      EntryHourUtc=$hour; OriginalExitAtOpenTime=$trade.exitCandleOpenTime
      PrimaryTrend=$sig.primaryTrend; Trend15m=$trend15
      HigherTimeframeConflicts=$snap.higherTimeframeConflicts
      VolumeLevel=$sig.volumeLevel; TakerBalance=$sig.takerBalance
      RelativeVolume=(Dec $sig.relativeVolume)
      RelativeSpread=(Dec $sig.relativeSpread)
      TakerBuyShare=(Dec $snap.takerBuyShareAtSignal)
      EffortResult=$sig.effortResult; BreakoutState=$snap.breakoutState
      PullbackCandidate=$snap.pullbackCandidate
      ConfirmedPivotCount=$snap.confirmedPivotCount
      AtrAtSignal=(Dec $snap.atrAtSignal)
      SignalClosePrice=(Dec $snap.signalClosePrice)
      BarDirection=if($null -ne $bar) {$bar.direction} else {''}
      BarTrend=if($null -ne $bar) {$bar.trendBar} else {$null}
      BarDoji=if($null -ne $bar) {$bar.doji} else {$null}
      BarInside=if($null -ne $bar) {$bar.insideBar} else {$null}
      BarOutside=if($null -ne $bar) {$bar.outsideBar} else {$null}
      BullishRejection=if($null -ne $bar) {$bar.bullishRejection} else {$null}
      BearishRejection=if($null -ne $bar) {$bar.bearishRejection} else {$null}
      EntryGapPct=(Dec $diagnostics.entryGapPct)
      StopDistancePct=(Dec $diagnostics.stopDistancePct)
      EstimatedCostToRiskPct=(Dec $diagnostics.estimatedCostToInitialRiskPct)
      EstimatedRoundTripCostUsdt=(Dec $diagnostics.estimatedRoundTripCostUsdt)
      EntryPrice=(Dec $trade.entryPrice); InitialStop=(Dec $diagnostics.initialStop)
      InitialRiskUsdt=(Dec $initialRisk); Quantity=(Dec $trade.quantity)
      Outcome=if($net -gt 0) {'WIN'} elseif($net -lt 0) {'LOSS'} else {'FLAT'}
      NetPnlUsdt=(Dec $net); NetR=(Dec $trade.netRMultiple)
      ExitReason=$trade.exitReason; DurationCandles=$trade.durationCandles
      MfeR=(Dec $trade.mfeR); MaeR=(Dec $trade.maeR)
    }
  }
  $windowsReport += [PSCustomObject]@{
    Window=$w; Phase=if($w -le 3) {'DISCOVERY'} else {'OOS_AFTER_SELECTION'}
    FromOpen=$period.From; AsOf=$period.AsOf; Trades=$trades.Count
    Wins=@($trades | Where-Object { [decimal]$_.netPnlUsdt -gt 0 }).Count
    NetClosedUsdt=(Dec $total)
  }
}
if ($details.Count -ne [int](($windowsReport | Measure-Object -Property Trades -Sum).Sum)) {
  throw 'Auditoria de entradas incompleta'
}
$root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$out=Join-Path $root 'results'
New-Item -Path $out -ItemType Directory -Force | Out-Null
$stem="$Symbol-$Setup-$AsOf-5m-w1-$Windows"
$file=Join-Path $out "entry-signal-audit-$stem.csv"
$windowFile=Join-Path $out "entry-signal-windows-$stem.csv"
$details | Export-Csv -Path $file -NoTypeInformation -Encoding UTF8
$windowsReport | Export-Csv -Path $windowFile -NoTypeInformation -Encoding UTF8
$windowsReport | Format-Table Window,Phase,Trades,Wins,NetClosedUsdt -AutoSize
Write-Host "Saved: $file"
Write-Host "Saved: $windowFile"
