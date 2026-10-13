<#
  Simulation-only profit-protection research: 0.5R / 1R x three opposition rules.
  Preserves entries from original 1m/5m RETEST windows and validates original CSVs.
  Usage: .\scripts\compare-profit-protection.ps1 -Symbol SOLUSDT -Setup RETEST -Source testnet -Windows 3 -AsOf 1791845999999
#>
[CmdletBinding()]
param(
    [ValidateSet('SOLUSDT','BTCUSDT','ETHUSDT')][string]$Symbol='SOLUSDT',
    [ValidateSet('RETEST','PULLBACK','BOTH')][string]$Setup='RETEST',
    [ValidateRange(1,6)][int]$Windows=3,
    [long]$AsOf=1791845999999,
    [ValidateRange(21,500)][int]$ContextLimit=200,
    [ValidateRange(1,100)][int]$CostLimitPct=50,
    [ValidateSet('testnet')][string]$Source='testnet',
    [ValidatePattern('^https?://(127\.0\.0\.1|localhost)(:\d+)?$')][string]$BaseUrl='http://127.0.0.1:8080',
    [ValidateRange(30,1800)][int]$TimeoutSec=900
)
$ErrorActionPreference='Stop'
$BaseUrl=$BaseUrl.TrimEnd('/')
$root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$out=Join-Path $root 'results'
function Money([object]$n) {
    if ($null -eq $n -or ([string]$n).Trim() -eq '') { throw 'Valor decimal ausente' }
    return [decimal]::Parse(([string]$n).Replace(',','.'),[System.Globalization.CultureInfo]::InvariantCulture)
}
function Near([object]$a,[object]$b,[decimal]$epsilon) {
    return [math]::Abs((Money $a)-(Money $b)) -le $epsilon
}
if ($AsOf -le 0 -or ($AsOf % 300000L) -ne 299999L) { throw 'AsOf nao esta alinhado ao fechamento de 5m' }
$health=Invoke-RestMethod -Uri "$BaseUrl/actuator/health" -TimeoutSec 15
if ($health.status -ne 'UP') { throw 'Spring Boot nao esta UP' }
$state=Invoke-RestMethod -Uri "$BaseUrl/api/v1/backtest/status" -TimeoutSec 15
if ($state.mode -ne 'SIMULATION_ONLY' -or $state.ordersEnabled) { throw 'Backend nao confirmou modo seguro de simulacao' }
$market=Invoke-RestMethod -Uri "$BaseUrl/api/v1/market-data/settings" -TimeoutSec 15
if ($market.source -ne 'TESTNET') { throw 'Backend nao esta no ambiente TESTNET' }
$risk=Invoke-RestMethod -Uri "$BaseUrl/api/v1/risk/status" -TimeoutSec 15
if (-not $risk.configured) { throw 'Politica de risco nao configurada' }
. (Join-Path $PSScriptRoot 'lib/binance-contract-filters.ps1')
$exchange=Invoke-RestMethod -Uri 'https://demo-fapi.binance.com/fapi/v1/exchangeInfo' -TimeoutSec 60
$filters=Symbol-ContractFilters -ExchangeInfo $exchange -Symbol $Symbol

$summaries=@()
$details=@()
foreach ($tf in @('1m','5m')) {
    $steps=if ($tf -eq '1m') { 18000 } else { 3600 }
    $suffix=if ($tf -eq '1m') { '' } else { '-5m' }
    $windowCsv=Join-Path $out "setup-windows-$Symbol$suffix-$AsOf.csv"
    $tradeCsv=Join-Path $out "setup-trades-$Symbol$suffix-$AsOf.csv"
    if (-not (Test-Path $windowCsv) -or -not (Test-Path $tradeCsv)) {
        throw ("CSVs originais nao encontrados: {0} / {1}" -f $windowCsv,$tradeCsv)
    }
    $windowsOriginal=@(Import-Csv $windowCsv | Where-Object { $_.Setup -eq $Setup })
    $tradesOriginal=@(Import-Csv $tradeCsv | Where-Object { $_.Setup -eq $Setup })
    # Fail with a schema-specific message if an old or incompatible CSV was supplied.
    if ($tradesOriginal.Count -gt 0) {
        $columns=@($tradesOriginal[0].PSObject.Properties.Name)
        foreach ($required in @('EnteredAtOpenTime','ExitCandleOpenTime','Side','ExitReason',
                                 'NetPnlUsdt','InitialRiskUsdt','Window','Setup')) {
            if ($required -notin $columns) {
                throw ("{0}: coluna obrigatoria ausente no CSV original: {1}" -f $tf,$required)
            }
        }
    }
    if ($windowsOriginal.Count -ne $Windows) { throw ("{0}: janelas incompletas" -f $tf) }
    foreach ($window in $windowsOriginal) {
        $w=[int]$window.Window
        $end=$AsOf-([long]($w-1)*300L*3600000L)
        $start=$end-300L*3600000L+1
        if ([long]$window.AsOf -ne $end -or [long]$window.FromOpen -ne $start -or $window.Source -ne 'TESTNET') {
            throw ("{0}/janela {1}: periodo ou origem diferente" -f $tf,$w)
        }
        $originals=@($tradesOriginal | Where-Object { [int]$_.Window -eq $w })
        if ($originals.Count -ne [int]$window.TradesClosed) { throw ("{0}/janela {1}: trades incompletos" -f $tf,$w) }
        $intervals=if ($tf -eq '1m') { @('1m','5m','15m') } else { @('5m','15m') }
        $body=@{
            symbol=$Symbol; intervals=$intervals; steps=$steps; contextLimit=$ContextLimit
            asOf=$end; includeLuna=$false; includeDecisionHistory=$false
            priceTick=$filters.priceTick; quantityStep=$filters.quantityStep
            minimumQuantity=$filters.minimumQuantity; minimumNotionalUsdt=$filters.minimumNotionalUsdt
            breakEvenTriggerROverride='1.0'; maxCostToInitialRiskPctOverride=$CostLimitPct
            pullbackEnabledOverride=($Setup -ne 'RETEST')
            breakoutRetestEnabledOverride=($Setup -ne 'PULLBACK')
        } | ConvertTo-Json -Depth 8
        Write-Host ("Testando protecao de lucro {0}/janela {1} / cutoff={2} ..." -f $tf,$w,$end)
        $arguments=@{
            Uri="$BaseUrl/api/v1/backtest/compare-profit-protection"
            Method='Post'; Body=$body; ContentType='application/json'; TimeoutSec=$TimeoutSec
        }
        try { $result=Invoke-RestMethod @arguments }
        catch {
            $errorMessage=if ($_.ErrorDetails -and $_.ErrorDetails.Message) { $_.ErrorDetails.Message } else { $_.Exception.Message }
            throw ("Falha no comparador {0}/janela {1}: {2}" -f $tf,$w,$errorMessage)
        }
        if ($result.symbol -ne $Symbol -or $result.primaryInterval -ne $tf -or
            [long]$result.fromOpenTime -ne $start -or [long]$result.asOfCloseTime -ne $end -or
            [int]$result.originalTrades -ne $originals.Count) {
            throw ("{0}/janela {1}: replay nao reproduziu o original" -f $tf,$w)
        }
        $six=@($result.summaries)
        $all=@($result.trades)
        if ($six.Count -ne 6 -or $all.Count -ne 6*$originals.Count) {
            throw ("{0}/janela {1}: faltam cenarios de saida" -f $tf,$w)
        }
        $originalIndex=@{}
        foreach ($t in $originals) {
            $key="$($t.EnteredAtOpenTime)|$($t.ExitCandleOpenTime)|$($t.Side)"
            if ($originalIndex.ContainsKey($key)) { throw "Trade duplicado: $key" }
            $originalIndex[$key]=$t
        }
        foreach ($s in $six) {
            $trigger=Money $s.triggerR
            if ($trigger -notin @([decimal]'0.5',[decimal]'1.0') -or
                $s.rule -notin @('FIRST_OPPOSITE_CANDLE','PRICE_ACTION','OPPOSITE_VOLUME') -or
                [int]$s.trades -ne $originals.Count -or
                -not (Near $s.originalNetPnlUsdt $window.NetClosedUsdt ([decimal]'0.01')) -or
                -not (Near ((Money $s.hypotheticalNetPnlUsdt)-(Money $s.originalNetPnlUsdt)) $s.deltaUsdt ([decimal]'0.0001'))) {
                throw ("{0}/janela {1}: resumo inconsistente, regra {2}, gatilho {3}" -f $tf,$w,$s.rule,$trigger)
            }
            $summaries += [PSCustomObject]@{
                Timeframe=$tf; Window=$w; TriggerR=$trigger; Rule=$s.rule
                Trades=$s.trades; ThresholdReached=$s.thresholdReached
                Signals=$s.signaledTrades; EarlyExits=$s.earlyExits
                OriginalWins=$s.originalWins; AlternativeWins=$s.hypotheticalWins
                LosingToWinning=$s.losingToWinning; WinningToLosing=$s.winningToLosing
                OriginalPnlUsdt=$s.originalNetPnlUsdt
                AlternativePnlUsdt=$s.hypotheticalNetPnlUsdt; DeltaUsdt=$s.deltaUsdt
                AlternativeFeesUsdt=$s.hypotheticalFeesUsdt
            }
        }
        $seen=@{}
        foreach ($t in $all) {
            $key="$($t.enteredAtOpenTime)|$($t.originalExitAtOpenTime)|$($t.side)"
            $scenario="$key|$($t.rule)|$(Money $t.triggerR)"
            if (-not $originalIndex.ContainsKey($key) -or $seen.ContainsKey($scenario)) {
                throw ("{0}/janela {1}: trade ausente ou duplicado: {2}" -f $tf,$w,$scenario)
            }
            $seen[$scenario]=$true
            $old=$originalIndex[$key]
            # The original setup-trades CSV exports NetPnlUsdt, InitialRiskUsdt,
            # ExitReason and timestamps, but NOT EntryPrice or Quantity.
            # Check only data that actually exists in the original export.
            if (-not (Near $old.NetPnlUsdt $t.originalNetPnlUsdt ([decimal]'0.01')) -or
                -not (Near $old.InitialRiskUsdt $t.initialRiskUsdt ([decimal]'0.0001')) -or
                [string]$old.ExitReason -ne [string]$t.originalExitReason -or
                -not (Near ((Money $t.hypotheticalNetPnlUsdt)-(Money $t.originalNetPnlUsdt)) $t.deltaUsdt ([decimal]'0.0001'))) {
                throw ("{0}/janela {1}: dados originais divergentes no trade {2}" -f $tf,$w,$key)
            }
            $details += [PSCustomObject]@{
                Timeframe=$tf; Window=$w; TriggerR=$t.triggerR; Rule=$t.rule
                Side=$t.side; EnteredAtOpenTime=$t.enteredAtOpenTime
                OriginalExitAtOpenTime=$t.originalExitAtOpenTime
                HypotheticalExitAtOpenTime=$t.hypotheticalExitAtOpenTime
                QualifiedAtCloseTime=$t.qualifiedAtCloseTime
                SignalAtCloseTime=$t.signalAtCloseTime
                ThresholdReached=$t.thresholdReached; SignalTriggered=$t.signalTriggered
                EarlyExit=$t.earlyExit; OriginalExitReason=$t.originalExitReason
                InitialRiskUsdt=$t.initialRiskUsdt
                Quantity=$t.quantity; EntryPrice=$t.entryPrice
                OriginalNetPnlUsdt=$t.originalNetPnlUsdt
                HypotheticalNetPnlUsdt=$t.hypotheticalNetPnlUsdt
                DeltaUsdt=$t.deltaUsdt; HypotheticalFeesUsdt=$t.hypotheticalFeesUsdt
            }
        }
    }
}
if ($summaries.Count -ne 12*$Windows) { throw 'Quantidade de janelas/estrategias incorreta' }
$aggregate=@()
foreach ($group in @($summaries | Group-Object Timeframe,TriggerR,Rule)) {
    $example=$group.Group[0]
    [decimal]$base=0; [decimal]$alternative=0; [decimal]$difference=0
    $qualified=0; $early=0
    foreach ($row in $group.Group) {
        $base+=Money $row.OriginalPnlUsdt
        $alternative+=Money $row.AlternativePnlUsdt
        $difference+=Money $row.DeltaUsdt
        $qualified+=[int]$row.ThresholdReached
        $early+=[int]$row.EarlyExits
    }
    $aggregate += [PSCustomObject]@{
        Timeframe=$example.Timeframe; TriggerR=$example.TriggerR; Rule=$example.Rule
        Trades=($group.Group | Measure-Object Trades -Sum).Sum
        ThresholdReached=$qualified; EarlyExits=$early
        OriginalPnlUsdt=$base; AlternativePnlUsdt=$alternative; DeltaUsdt=$difference
    }
}
$summaryFile=Join-Path $out "profit-protection-summary-$Symbol-$Setup-$AsOf.csv"
$tradesFile=Join-Path $out "profit-protection-trades-$Symbol-$Setup-$AsOf.csv"
$summaries | Export-Csv -Path $summaryFile -NoTypeInformation -Encoding UTF8
$details | Export-Csv -Path $tradesFile -NoTypeInformation -Encoding UTF8
Write-Host 'COMPARACAO 1m/5m (custo liquido de comissao e slippage):'
$aggregate | Sort-Object Timeframe,TriggerR,Rule | Format-Table Timeframe,TriggerR,Rule,Trades,ThresholdReached,EarlyExits,OriginalPnlUsdt,AlternativePnlUsdt,DeltaUsdt -AutoSize
Write-Host "Saved: $summaryFile"
Write-Host "Saved: $tradesFile"
