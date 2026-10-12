# Etapa 10 — Backtest comparativo (simulacao de pesquisa, nao trading)

## Regras temporais

- `POST /api/v1/backtest/run`, nunca GET (pode gerar chamadas pagas quando `includeLuna=true`).
- O servidor le `steps` candles fechados do **primeiro timeframe**, ordena cronologicamente e valida cada lacuna (ate **3000 candles**, Java). Para cada candle, carrega o contexto multi-timeframe ate **seu closeTime**, nunca depois.
- Os dados de cada timeframe sao obtidos do MySQL **uma vez por execucao**, com tamanho de janela calculado pela duracao do replay e `contextLimit`. Cada decisao recebe somente candles fechados ate o respectivo `asOf`; busca binaria e recorte em memoria impedem uso de candles futuros. Combinacoes exigindo mais de 60.000 candles em um unico timeframe sao recusadas; divida o periodo.
- Ambos os motores compartilham o MESMO objeto imutavel `MultiTimeframeMarketContext` para cada candle. Apenas `PositionState` difere, pois as carteiras sao independentes.
- Uma decisao no fechamento de T so executa na **abertura do candle T+1**. O ultimo candle da janela nao gera decisao (nao existe proximo candle para executar).
- Stops vigentes sao avaliados com `low/high` de cada candle, e gaps sao liquidados pelo pior entre preco de abertura e stop. Quando o sequenciamento OHLC e desconhecido, escolhemos **stop-first**, que pode ser pessimista.
- Ajustes de stop decididos no fechamento passam a valer no proximo candle. O motor de risco revalida margem e risco no proximo preco de entrada e pode recusar o sinal.

## Custos e P&L

- Fee e slippage configurados **por lado**, aplicados nas duas execucoes. Entrada de LONG recebe slippage acima do `open`; SHORT abaixo. Saidas tem direcao adversa inversa.
- O caixa inicial e `TRADING_CAPITAL_USDT` explicitamente definido; `JAVA` e `LUNA` comecam cada um com a mesma banca **separada**.
- Equity ao final inclui realizado e valor mark-to-market de posicao aberta, subtraindo estimativa de custo de liquidacao. Nao fecha a posicao artificialmente no ultimo candle.
- Reset da perda diaria e feito em UTC; taxa de entrada e saida reduz caixa. A classificacao de ganhos/perdas usa PnL liquido apos taxas da entrada e saida.
- Drawdown calculado pelos pontos de fechamento da serie, nao intrabar. A estimativa ignora funding, spread extra, liquidacao e possivel falta de profundidade de book.

## Luna: protecao de custos

- `LUNA_ENABLED=false` (padrao): nenhuma chamada OpenAI. Mesmo quando `true`, so executa com `includeLuna=true` **no POST** e chave configurada.
- `includeLuna=true` limita `steps` a 10 e realiza no maximo `steps-1` chamadas pagas; esse numero e para smoke/piloto e **nao** e amostra estatistica de rentabilidade.
- Quando desabilitado, `lunaPortfolio=null` e `comparativeResultsAvailable=false`: o sistema NAO inventa trades do Luna nem copia as decisoes do Java.
- Falhas na OpenAI nao sao substituidas por resultados Java. Nenhum destes endpoints envia ordens.

## Configuracao necessaria (sem defaults financeiros)

`TRADING_CAPITAL_USDT`, `TRADING_RISK_PER_TRADE_PCT`, `TRADING_DAILY_STOP_PCT`, `TRADING_LEVERAGE`, `RISK_FEE_PER_SIDE_PCT`, `RISK_SLIPPAGE_PER_SIDE_PCT`. `RISK_BREAK_EVEN_TRIGGER_R` e `RISK_STRUCTURAL_ATR_BUFFER` seguem a configuracao de risco existente.

Filtros `priceTick`, `quantityStep`, `minimumQuantity` e `minimumNotionalUsdt` devem ser retirados de `/fapi/v1/exchangeInfo` do instrumento e ambiente utilizados. Os valores no exemplo abaixo **nao sao especificacoes verificadas da Binance**.

```powershell
$body = @{
  symbol='BTCUSDT'
  intervals=@('1m','5m','15m')
  steps=30
  contextLimit=200
  includeLuna=$false
  priceTick='0.1'
  quantityStep='0.001'
  minimumQuantity='0.001'
  minimumNotionalUsdt='5'
} | ConvertTo-Json
Invoke-RestMethod -Method Post -ContentType 'application/json' -Body $body `
  'http://127.0.0.1:8080/api/v1/backtest/run'
```

A coleta anterior deve cobrir `contextLimit` candles **para cada timeframe**, inclusive ANTES do primeiro candle da janela de replay. Se houver lacunas ou dados insuficientes, o teste falha em vez de interpolar.

## Limitacoes

- Replays online a partir do historico MySQL disponivel; nao faz download de dados automaticamente.
- O resultado inclui `javaDecisionHistory` e `lunaDecisionHistory` (este vazio quando `includeLuna=false`), com **uma entrada por candle de decisao**. Sao registros descritivos de sinal e bloqueios; nao equivalem a ordens efetivamente executadas.
- Cada registro inclui `candleOpenTime`, `candleCloseTime`, `positionSideAtDecision`, `entry`, `positionAction`, `setup`, `reason`, `primaryTrend`, `timeframeTrends`, `volumeLevel`, `takerBalance`, `relativeVolume`, `relativeSpread`, `effortResult`, `riskAction` e `riskReason`. A lista pode ter ate **2999 entradas para Java**; o retorno HTTP fica maior.
- Aumentar `steps` nao aumenta `contextLimit`: ele permanece limitado a 21..500 candles por decisao. O historico total necessario abrange a janela de contexto **antes do primeiro candle** do replay. Faltas/lacunas retornam HTTP 422.
- O limite de backtest Luna continua **10 candles**, por seguranca de custo. `includeLuna=false` nao gera chamadas OpenAI.
- Sem persistencia de carteira, ordens ou resultados. Sem otimizacao/hyperparameter tuning.
- Custos nominais e mercado Testnet nao sao evidencias de rentabilidade real.
- Futuros podem liquidar antes do stop. Liquidacao/funding/risco de contraparte ainda nao sao simulados.
- Sem validacao contra dados historicos de producao ainda. Use so como verificacao de codigo ate incorporar esse conjunto de dados e modelar latencia/liquidez.

## Exemplo: 3000 candles e diagnostico de todas as decisoes

```powershell
$body = @{
  symbol = "BTCUSDT"
  intervals = @("1m", "5m", "15m")
  steps = 3000
  contextLimit = 200
  includeLuna = $false
  priceTick = "0.1"
  quantityStep = "0.001"
  minimumQuantity = "0.001"
  minimumNotionalUsdt = "5"
} | ConvertTo-Json
$result = Invoke-RestMethod -Method Post -ContentType "application/json" -Uri "http://127.0.0.1:8080/api/v1/backtest/run" -Body $body
$result.javaDecisionHistory | Group-Object reason | Select-Object Name, Count
$result.javaDecisionHistory | Select-Object -Last 20 | Format-Table candleCloseTime,entry,reason,primaryTrend,volumeLevel,riskAction
```

## Etapa de diagnostico de saidas: MFE / MAE, R e custos

Cada `javaPortfolio.trades[]` ou `lunaPortfolio.trades[]` encerrado contem os campos originais e tambem:

| Campo | Unidade / significado |
| --- | --- |
| `initialStop` / `finalStop` | Preco de stop originalmente dimensionado / stop vigente quando saiu |
| `stopAdjustments` | Quantidade de ajustes de stop aplicados no fechamento de candles |
| `durationCandles` | Numero de candles nos quais a posicao estava efetivamente aberta, contando o candle de entrada e de stop intrabar |
| `durationMillis` | Diferenca entre horario de abertura do candle de saida e horario do candle de entrada; em saidas por STOP nao e o instante exato de disparo |
| `initialRiskUsdt` | `abs(entryPrice - initialStop) * quantity` (risco de preco bruto, exclui taxas) |
| `maxFavorableExcursionUsdt` / `maxAdverseExcursionUsdt` | MFE / MAE maximas BRUTAS ate a saida; ambas positivas, incluindo extrema intrabar quando a posicao ainda nao atingiu o stop |
| `mfeR` / `maeR` | MFE / MAE divididas por `initialRiskUsdt` |
| `netRMultiple` | P&L realizado apos as taxas / risco inicial de preco bruto |
| `feesUsdt` | Taxa de entrada + taxa de saida por operacao |
| `slippageUsdt` | Diferenca adversa entre precos de referencia e fills, somada na entrada e saida |

**Muito importante:** `grossPnlUsdt` JA considera o slippage nos precos `entryPrice` e `exitPrice`. O `netPnlUsdt` ja desconta `feesUsdt`. Portanto, o campo `slippageUsdt` e **somente decomposicao/diagnostico**, nao um custo a subtrair de novo.

No candle que aciona o stop, por nao sabermos a ordem intrabar de high e low a partir de OHLC, a simulacao so considera a abertura e o preco bruto do stop/gap para calcular os extremos observados nesse candle. Ela **nao credita** uma maxima/minima que pode ter ocorrido depois de o stop encerrar a posicao. Trata-se de estimativa conservadora, nao de MFE/MAE tick-by-tick.

A posicao ainda aberta ao final aparece em `javaPortfolio.openPositionDiagnostics` (ou `lunaPortfolio.openPositionDiagnostics`), sem criar um trade de saida artificial. `unrealizedNetPnlUsdt` contempla custos estimados de liquidacao e taxa de entrada; nao e ganho realizado.

### Inspecionar os resultados no PowerShell

```powershell
$result.javaPortfolio.trades |
    Select-Object side, durationCandles, initialStop, finalStop,
                  stopAdjustments, mfeR, maeR, netRMultiple, feesUsdt,
                  slippageUsdt, netPnlUsdt, exitReason |
    Format-Table -AutoSize

$result.javaPortfolio.openPositionDiagnostics | Format-List
```

### Comparar gatilhos de break-even sem reiniciar Spring

`breakEvenTriggerROverride` e opcional no corpo da requisicao. Se omitido, vale `RISK_BREAK_EVEN_TRIGGER_R` do ambiente. O override afeta somente o replay solicitado; nao muda o .env nem outras sessoes:

```powershell
$base = @{
    symbol = "BTCUSDT"
    intervals = @("1m", "5m", "15m")
    steps = 3000
    contextLimit = 200
    includeLuna = $false
    priceTick = "0.1"
    quantityStep = "0.001"
    minimumQuantity = "0.001"
    minimumNotionalUsdt = "5"
    asOf = 1791823439999 # SUBSTITUA pelo fechamento UTC em ms do ultimo candle no banco
}
$results = foreach ($r in @("1.0","1.5","2.0")) {
    $body = $base.Clone()
    $body.breakEvenTriggerROverride = $r
    $data = Invoke-RestMethod -Method Post -ContentType "application/json" -Body ($body | ConvertTo-Json) -Uri "http://127.0.0.1:8080/api/v1/backtest/run"
    [PSCustomObject]@{
        breakEvenR = $r
        returnPct = $data.javaPortfolio.totalReturnPct
        drawdownPct = $data.javaPortfolio.maxDrawdownPct
        tradesClosed = $data.javaPortfolio.tradesClosed
        entrySignals = $data.javaPortfolio.entrySignals
    }
}
$results | Format-Table -AutoSize
```

Os tres testes acima usam os **mesmos candles** e mesmo instante final, mas NAO garantem as mesmas entradas. Como alteracoes no stop mudam o momento de encerramento, cada carteira pode estar livre ou ocupada em momentos diferentes. Para comparar saídas com **entradas 100% identicas**, sera necessario um experimento especifico de replay de entradas congeladas. Um resultado em uma janela de 3000 candles nao prova robustez fora da amostra.


## Comparacao experimental do filtro custo/risco

O campo opcional `maxCostToInitialRiskPctOverride` do POST do backtest
tem 3 semanticas:

- Omitido/null: herda `RISK_MAX_COST_TO_INITIAL_RISK_PCT` da aplicacao.
- `0`: desliga o filtro **somente nesse replay**, mesmo se ativo no ambiente.
- `30` (ou outro numero > 0 ate 100): utiliza esse limite somente nesse replay.

Isso preserva a politica global da aplicacao e permite comparar a mesma janela
historica sem reiniciar o Spring Boot. O Luna continua condicionado a
`LUNA_ENABLED=true`, `includeLuna=true`, e ao limite de 10 candles.

```powershell
# Use o timestamp final do replay que voce executou ANTES da atualizacao.
$fixedAsOf = [long]$result.throughCandleCloseTime
$common = @{
  symbol = "BTCUSDT"
  intervals = @("1m", "5m", "15m")
  steps = 3000
  contextLimit = 200
  asOf = $fixedAsOf
  includeLuna = $false
  priceTick = "0.1"
  quantityStep = "0.001"
  minimumQuantity = "0.001"
  minimumNotionalUsdt = "5"
  breakEvenTriggerROverride = "1.0"
}
$comparison = foreach ($pct in @(0,30)) {
  $req = $common.Clone()
  $req.maxCostToInitialRiskPctOverride = $pct
  $run = Invoke-RestMethod -Method Post `
    -Uri "http://127.0.0.1:8080/api/v1/backtest/run" `
    -ContentType "application/json" -Body ($req | ConvertTo-Json)
  [PSCustomObject]@{
    CostLimitPct = $pct
    EntrySignals = $run.javaPortfolio.entrySignals
    EntriesBlocked = $run.javaPortfolio.entriesBlocked
    BlockedAtDecision = @($run.javaDecisionHistory |
      Where-Object { $_.riskReason -eq "COST_TO_RISK_TOO_HIGH" }).Count
    ClosedTrades = $run.javaPortfolio.tradesClosed
    ReturnPct = $run.javaPortfolio.totalReturnPct
    MaxDrawdownPct = $run.javaPortfolio.maxDrawdownPct
  }
}
$comparison | Format-Table -AutoSize
```

`EntrySignals` inclui sinais bloqueados na avaliacao de risco.
`BlockedAtDecision` identifica especificamente o novo motivo no fechamento.
`EntriesBlocked` tambem conta bloqueios por outros motivos e recheques na
abertura seguinte. Como o tempo em posicao pode mudar, os dois cenarios nao
necessariamente executam os mesmos trades, embora os candles sejam identicos.

**Atencao:** Se voce ja tinha `breakEvenTriggerROverride` configurado, mantenha
o mesmo valor nos dois cenarios. Valores de exemplo dos filtros Binance nao
foram verificados; substitua pelos da sua corretora/ambiente.
