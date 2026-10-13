<#
.SYNOPSIS
  Valida 0.5R / 1R em seis NOVAS janelas de 300h, indices 4..9.
.DESCRIPTION
  OOS: as janelas 1..3 nunca entram nesta analise.
  1m = 18000 candles/janela. 5m = 3600 candles/janela.
  Usa trades originais congelados, sem recalcular entradas alternativas.
  Somente TESTNET, Java, pesquisa; nunca envia ordens nem chama Luna.
.EXAMPLE
  .\scripts\validate-profit-protection-oos.ps1 -PrintOnly
.EXAMPLE
  .\scripts\validate-profit-protection-oos.ps1 -Symbol SOLUSDT -SyncHistory
#>
[CmdletBinding()]
param(
    [ValidateSet('SOLUSDT','BTCUSDT','ETHUSDT')][string]$Symbol='SOLUSDT',
    [ValidateSet('RETEST','PULLBACK','BOTH')][string]$Setup='RETEST',
    [ValidateSet('testnet')][string]$Source='testnet',
    [ValidateRange(4,100)][int]$StartWindow=4,
    [ValidateRange(1,6)][int]$Windows=6,
    [long]$AsOf=1791845999999,
    [ValidateRange(21,500)][int]$ContextLimit=200,
    [ValidateRange(1,100)][int]$CostLimitPct=50,
    [ValidateRange(1,1000000000)][decimal]$ExpectedCapitalUsdt=1500,
    [ValidatePattern('^https?://(127\.0\.0\.1|localhost)(:\d+)?$')]
    [string]$BaseUrl='http://127.0.0.1:8080',
    [ValidateRange(30,1800)][int]$TimeoutSec=900,
    [switch]$SyncHistory,
    [switch]$PrintOnly
)
$ErrorActionPreference='Stop'
$BaseUrl=$BaseUrl.TrimEnd('/')
$root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$out=Join-Path $root 'results'
[long]$periodMs=300L*3600000L

function Money([object]$value,[string]$field) {
    if ($null -eq $value -or [string]::IsNullOrWhiteSpace([string]$value)) {
        throw ("Campo decimal ausente: {0}" -f $field)
    }
    return [decimal]::Parse(([string]$value).Replace(',','.'),
        [System.Globalization.CultureInfo]::InvariantCulture)
}
function Assert-Money([object]$actual,[object]$expected,[decimal]$tolerance,[string]$field) {
    [decimal]$delta=[math]::Abs((Money $actual $field)-(Money $expected $field))
    if ($delta -gt $tolerance) { throw ("Divergencia em {0}: delta={1}" -f $field,$delta) }
}
if ($AsOf -le 0 -or ($AsOf % 900000L) -ne 899999L) {
    throw 'AsOf deve ser o fechamento de um candle 15m UTC'
}
$plan=@()
for ($i=0; $i -lt $Windows; $i++) {
    [int]$window=$StartWindow+$i
    [long]$cutoff=$AsOf-([long]($window-1)*$periodMs)
    [long]$first=$cutoff-$periodMs+1
    if ($first -lt 0) { throw 'Periodo anterior a epoca Unix' }
    $plan += [PSCustomObject]@{
        Window=$window; FromOpen=$first; AsOf=$cutoff
        FromUtc=[DateTimeOffset]::FromUnixTimeMilliseconds($first).UtcDateTime.ToString('yyyy-MM-dd HH:mm')
        UntilUtc=[DateTimeOffset]::FromUnixTimeMilliseconds($cutoff).UtcDateTime.ToString('yyyy-MM-dd HH:mm')
    }
}
Write-Host ("OOS - {0} janelas, indices {1}..{2}; 300 horas por janela" -f $Windows,$StartWindow,($StartWindow+$Windows-1))
$plan | Format-Table Window,FromUtc,UntilUtc,FromOpen,AsOf -AutoSize
if ($PrintOnly) {
    Write-Output ("OOS_START_WINDOW={0}" -f $StartWindow)
    Write-Output ("OOS_END_WINDOW={0}" -f ($StartWindow+$Windows-1))
    Write-Output 'OOS_HOURS_PER_WINDOW=300'
    Write-Output 'OOS_1M_CANDLES_PER_WINDOW=18000'
    Write-Output 'OOS_5M_CANDLES_PER_WINDOW=3600'
    Write-Output 'OOS_NO_LUNA=true'
    Write-Output 'OOS_NO_ORDERS=true'
    Write-Output ("OOS_EXPECTED_CAPITAL_USDT={0}" -f $ExpectedCapitalUsdt)
    foreach ($planned in $plan) {
        Write-Output ("OOS_WINDOW={0};FROM={1};ASOF={2}" -f $planned.Window,$planned.FromOpen,$planned.AsOf)
    }
    Write-Host 'Nenhuma chamada remota foi realizada.'
    return
}

$health=Invoke-RestMethod -Uri "$BaseUrl/actuator/health" -TimeoutSec 15 -ErrorAction Stop
if ($health.status -ne 'UP') { throw 'Spring Boot indisponivel' }
$status=Invoke-RestMethod -Uri "$BaseUrl/api/v1/backtest/status" -TimeoutSec 15 -ErrorAction Stop
if ($status.mode -ne 'SIMULATION_ONLY' -or $status.ordersEnabled) { throw 'Backend nao e somente simulacao' }
$market=Invoke-RestMethod -Uri "$BaseUrl/api/v1/market-data/settings" -TimeoutSec 15 -ErrorAction Stop
if ($market.source -ne 'TESTNET') { throw 'Backend nao configurado para TESTNET' }
$risk=Invoke-RestMethod -Uri "$BaseUrl/api/v1/risk/status" -TimeoutSec 15 -ErrorAction Stop
if (-not $risk.configured -or $risk.ordersEnabled) { throw 'Politica de risco incompleta ou ordens habilitadas' }

. (Join-Path $PSScriptRoot 'lib/binance-contract-filters.ps1')
$exchange=Invoke-RestMethod -Uri 'https://demo-fapi.binance.com/fapi/v1/exchangeInfo' -TimeoutSec 60
$filters=Symbol-ContractFilters -ExchangeInfo $exchange -Symbol $Symbol

if ($SyncHistory) {
    [long]$warmup=([long]($ContextLimit+30)*15L*60000L)
    [long]$from=[long]$plan[-1].FromOpen-$warmup
    [long]$until=[long]$plan[0].AsOf
    if ($from -lt 0) { throw 'Inicio invalido de sincronizacao historica' }
    [long]$chunkMs=7L*24L*3600000L
    foreach ($tf in @('1m','5m','15m')) {
        [long]$cursor=$from
        $chunk=0
        while ($cursor -le $until) {
            [long]$end=[math]::Min(($cursor+$chunkMs-1),$until)
            $chunk++
            Write-Host ("Sync TESTNET {0} / lote {1}, {2}..{3}" -f $tf,$chunk,$cursor,$end)
            $url="$BaseUrl/api/v1/market-data/sync?symbol=$Symbol&interval=$tf&from=$cursor&until=$end"
            try {
                $resultSync=Invoke-RestMethod -Uri $url -Method Post -TimeoutSec $TimeoutSec -ErrorAction Stop
            } catch {
                throw ("Sync {0}/lote {1}: {2}" -f $tf,$chunk,$_.Exception.Message)
            }
            if ($null -eq $resultSync -or [long]$resultSync.from -ne $cursor -or
                [long]$resultSync.until -lt $end -or [int]$resultSync.pages -lt 1 -or
                [int]$resultSync.fetched -lt 1) {
                throw ("Historico indisponivel para {0}/lote {1}; nao inventar candles." -f $tf,$chunk)
            }
            $cursor=$end+1
        }
    }
}
New-Item -ItemType Directory -Path $out -Force | Out-Null

$summaryRows=@()
$tradeRows=@()
foreach ($tf in @('1m','5m')) {
    $steps=if ($tf -eq '1m') { 18000 } else { 3600 }
    $intervals=if ($tf -eq '1m') { @('1m','5m','15m') } else { @('5m','15m') }
    foreach ($window in $plan) {
        $w=[int]$window.Window
        $body=@{
            symbol=$Symbol; intervals=$intervals; steps=$steps
            contextLimit=$ContextLimit; asOf=[long]$window.AsOf
            includeLuna=$false; includeDecisionHistory=$false
            priceTick=$filters.priceTick; quantityStep=$filters.quantityStep
            minimumQuantity=$filters.minimumQuantity
            minimumNotionalUsdt=$filters.minimumNotionalUsdt
            breakEvenTriggerROverride='1.0'
            maxCostToInitialRiskPctOverride=$CostLimitPct
            pullbackEnabledOverride=($Setup -ne 'RETEST')
            breakoutRetestEnabledOverride=($Setup -ne 'PULLBACK')
        } | ConvertTo-Json -Depth 8
        Write-Host ("OOS: {0}/janela {1} - {2} candles ..." -f $tf,$w,$steps)
        $requestArgs=@{
            Uri="$BaseUrl/api/v1/backtest/compare-profit-protection"
            Method='Post'; Body=$body; ContentType='application/json'
            TimeoutSec=$TimeoutSec; ErrorAction='Stop'
        }
        try { $result=Invoke-RestMethod @requestArgs }
        catch {
            throw ("Falha {0}/janela {1}: {2}. Se faltam candles antigos, execute com -SyncHistory." -f $tf,$w,$_.Exception.Message)
        }
        if ($result.symbol -ne $Symbol -or $result.primaryInterval -ne $tf -or
            [long]$result.fromOpenTime -ne [long]$window.FromOpen -or
            [long]$result.asOfCloseTime -ne [long]$window.AsOf) {
            throw ("OOS {0}/janela {1}: replay deslocado" -f $tf,$w)
        }
        Assert-Money $result.startingEquityUsdt $ExpectedCapitalUsdt ([decimal]'0.0001') 'startingEquityUsdt'
        $summaries=@($result.summaries)
        $trades=@($result.trades)
        $count=[int]$result.originalTrades
        if ($summaries.Count -ne 6 -or $trades.Count -ne 6*$count) {
            throw ("OOS {0}/janela {1}: quantidade incorreta de cenarios" -f $tf,$w)
        }
        $uniqueScenarios=@{}
        $baseline=$null
        foreach ($s in $summaries) {
            [decimal]$trigger=Money $s.triggerR 'triggerR'
            if ($trigger -notin @([decimal]'0.5',[decimal]'1') -or
                $s.rule -notin @('FIRST_OPPOSITE_CANDLE','PRICE_ACTION','OPPOSITE_VOLUME')) {
                throw 'Regra ou gatilho inesperado'
            }
            $key="$trigger|$($s.rule)"
            if ($uniqueScenarios.ContainsKey($key)) { throw ("Cenario duplicado {0}" -f $key) }
            $uniqueScenarios[$key]=$true
            if ([int]$s.trades -ne $count) { throw ("Contagem de trades: {0}" -f $key) }
            if ($null -eq $baseline) { $baseline=$s.originalNetPnlUsdt }
            Assert-Money $s.originalNetPnlUsdt $baseline ([decimal]'0.001') 'baseline'
            Assert-Money ((Money $s.hypotheticalNetPnlUsdt 'alterado')-(Money $s.originalNetPnlUsdt 'original')) $s.deltaUsdt ([decimal]'0.001') 'delta resumo'

            $matching=@($trades | Where-Object {
                $_.rule -eq $s.rule -and (Money $_.triggerR 'triggerR trade') -eq $trigger
            })
            if ($matching.Count -ne $count) { throw ("Detalhes incompletos: {0}" -f $key) }
            [decimal]$oldSum=0; [decimal]$newSum=0
            $seen=@{}
            foreach ($t in $matching) {
                $tradeKey="$($t.side)|$($t.enteredAtOpenTime)|$($t.originalExitAtOpenTime)"
                if ($seen.ContainsKey($tradeKey)) { throw ("Trade duplicado: {0}" -f $tradeKey) }
                $seen[$tradeKey]=$true
                if ([long]$t.enteredAtOpenTime -lt [long]$window.FromOpen -or
                    [long]$t.originalExitAtOpenTime -gt [long]$window.AsOf -or
                    [long]$t.hypotheticalExitAtOpenTime -gt [long]$t.originalExitAtOpenTime -or
                    [long]$t.hypotheticalExitAtOpenTime -lt [long]$t.enteredAtOpenTime) {
                    throw ("Limite temporal incorreto: {0}" -f $tradeKey)
                }
                Assert-Money ((Money $t.hypotheticalNetPnlUsdt 'alt trade')-(Money $t.originalNetPnlUsdt 'original trade')) $t.deltaUsdt ([decimal]'0.001') 'delta trade'
                $oldSum+=Money $t.originalNetPnlUsdt 'original trade'
                $newSum+=Money $t.hypotheticalNetPnlUsdt 'alt trade'
            }
            Assert-Money $oldSum $s.originalNetPnlUsdt ([decimal]'0.001') 'reconciliacao original'
            Assert-Money $newSum $s.hypotheticalNetPnlUsdt ([decimal]'0.001') 'reconciliacao hipotetica'
            $summaryRows += [PSCustomObject]@{
                Phase='OOS'; Symbol=$Symbol; Timeframe=$tf; Window=$w
                FromOpen=$window.FromOpen; AsOf=$window.AsOf
                TriggerR=$trigger; Rule=$s.rule; Trades=$count
                ThresholdReached=$s.thresholdReached; SignalsTriggered=$s.signaledTrades
                EarlyExits=$s.earlyExits; OriginalWins=$s.originalWins
                AlternativeWins=$s.hypotheticalWins; LosingToWinning=$s.losingToWinning
                WinningToLosing=$s.winningToLosing
                OriginalPnlUsdt=$s.originalNetPnlUsdt
                AlternativePnlUsdt=$s.hypotheticalNetPnlUsdt; DeltaUsdt=$s.deltaUsdt
                AlternativeFeesUsdt=$s.hypotheticalFeesUsdt
            }
        }
        if ($uniqueScenarios.Count -ne 6) { throw 'Matriz incompleta' }
        foreach ($t in $trades) {
            $tradeRows += [PSCustomObject]@{
                Phase='OOS'; Symbol=$Symbol; Timeframe=$tf; Window=$w
                TriggerR=$t.triggerR; Rule=$t.rule; Side=$t.side
                EnteredAtOpenTime=$t.enteredAtOpenTime
                OriginalExitAtOpenTime=$t.originalExitAtOpenTime
                HypotheticalExitAtOpenTime=$t.hypotheticalExitAtOpenTime
                QualifiedAtCloseTime=$t.qualifiedAtCloseTime
                SignalAtCloseTime=$t.signalAtCloseTime
                OriginalExitReason=$t.originalExitReason
                ThresholdReached=$t.thresholdReached; SignalTriggered=$t.signalTriggered
                EarlyExit=$t.earlyExit; InitialRiskUsdt=$t.initialRiskUsdt
                Quantity=$t.quantity; EntryPrice=$t.entryPrice
                OriginalNetPnlUsdt=$t.originalNetPnlUsdt
                HypotheticalNetPnlUsdt=$t.hypotheticalNetPnlUsdt
                DeltaUsdt=$t.deltaUsdt; HypotheticalFeesUsdt=$t.hypotheticalFeesUsdt
            }
        }
        Write-Host ("OOS {0}/janela {1}: {2} trades, 6 cenarios validados" -f $tf,$w,$count)
    }
}
if ($summaryRows.Count -ne (12*$Windows)) { throw 'OOS incompleto: nao exportar tabela final' }
$aggregate=@()
foreach ($g in @($summaryRows | Group-Object Timeframe,TriggerR,Rule)) {
    if ($g.Count -ne $Windows) { throw ("Cenario incompleto: {0}" -f $g.Name) }
    $example=$g.Group[0]
    [decimal]$original=0; [decimal]$alternative=0; [decimal]$delta=0
    $positiveDelta=0; $positiveNet=0; $early=0; $n=0
    foreach ($row in $g.Group) {
        [decimal]$d=Money $row.DeltaUsdt 'delta'
        [decimal]$a=Money $row.AlternativePnlUsdt 'alternative'
        $original+=Money $row.OriginalPnlUsdt 'original'
        $alternative+=$a; $delta+=$d
        if ($d -gt 0) { $positiveDelta++ }
        if ($a -gt 0) { $positiveNet++ }
        $early+=[int]$row.EarlyExits; $n+=[int]$row.Trades
    }
    Assert-Money ($alternative-$original) $delta ([decimal]'0.001') 'delta agregado'
    $aggregate += [PSCustomObject]@{
        Phase='OOS'; Timeframe=$example.Timeframe
        TriggerR=$example.TriggerR; Rule=$example.Rule
        Windows=$Windows; Trades=$n; EarlyExits=$early
        WindowsWithPositiveDelta=$positiveDelta; WindowsWithPositiveNet=$positiveNet
        OriginalPnlUsdt=$original; AlternativePnlUsdt=$alternative; DeltaUsdt=$delta
    }
}
$stem="$Symbol-$Setup-$AsOf-w$StartWindow-$($StartWindow+$Windows-1)"
$aggregateFile=Join-Path $out "profit-protection-oos-aggregate-$stem.csv"
$summaryFile=Join-Path $out "profit-protection-oos-summary-$stem.csv"
$tradesFile=Join-Path $out "profit-protection-oos-trades-$stem.csv"
$aggregate | Export-Csv -Path $aggregateFile -NoTypeInformation -Encoding UTF8
$summaryRows | Export-Csv -Path $summaryFile -NoTypeInformation -Encoding UTF8
$tradeRows | Export-Csv -Path $tradesFile -NoTypeInformation -Encoding UTF8
Write-Host 'RESULTADO FORA DA AMOSTRA:'
$aggregate | Sort-Object Timeframe,TriggerR,Rule | Format-Table Timeframe,TriggerR,Rule,Trades,WindowsWithPositiveDelta,WindowsWithPositiveNet,OriginalPnlUsdt,AlternativePnlUsdt,DeltaUsdt -AutoSize
Write-Host "Saved: $aggregateFile"
Write-Host "Saved: $summaryFile"
Write-Host "Saved: $tradesFile"
