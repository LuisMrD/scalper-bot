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

## Comparacao experimental de saidas por tempo (SOLUSDT)

`maxHoldingCandles` e opcional no `POST /api/v1/backtest/run`.
`null` ou ausencia = politica anterior, sem tempo maximo.
Valores `1..3000` = simula fechamento apos esse numero de candles
primarios em que a posicao esteve aberta (contando o candle de entrada).
Valores zero, negativos e acima de 3000 sao recusados.

O limite e avaliado **no fechamento** do candle N.
Uma saida `TIME_LIMIT` e executada **somente na abertura N+1**,
com fee e slippage simulados; se o candle N+1 nao existe, a posicao
permanece aberta e **nao** inventamos liquidacao. Stops intrabar sao
avaliados antes da decisao de fechar no tempo. Um `EXIT_SIGNAL` ja
agendado tem prioridade sobre `TIME_LIMIT` no mesmo fechamento.
Gaps de abertura continuam afetando o preco executado.

Na resposta, cada `javaPortfolio.trades[].timing` e
`javaPortfolio.openPositionDiagnostics.timing` apresenta:

| Campo | Significado |
| --- | --- |
| `closeHistory[]` | P&L liquido **estimado para liquidacao a cada fechamento** enquanto ainda em posicao |
| `closeHistory[].estimatedNetPnlUsdt` | Incorpora taxa real da entrada, fee de saida estimada e slippage adverso de saida |
| `closeHistory[].stopForNextCandle` | Stop apos decisao do candle, so valido no candle seguinte |
| `closeHistory[].decisionReason` / `riskReason` | Motivos emitidos pelos motores Java/Luna e risco para correlacionar com reversoes |
| `firstNetPositiveCloseTime` | Primeiro candle com P&L de liquidacao estimado positivo |
| `netPositiveCloseCandles` | Quantos fechamentos apresentaram P&L de liquidacao estimado positivo |
| `maxEstimatedNetPnlUsdt` / `maxEstimatedNetPnlAtCloseTime` | Maior P&L positivo **ou negativo** entre os fechamentos observados e seu instante |
| `netPnlAt15Candles` / `netPnlAt30Candles` / `netPnlAt60Candles` / `netPnlAt120Candles` | Fotografias da posicao se ainda estava aberta no fechamento correspondente; `null` caso contrario |
| `givebackFromBestCloseUsdt` | `max(0, melhor P&L liquido estimado em fechamento - P&L liquido realizado)`; para posicao ainda aberta, usa P&L de liquidacao estimado mais recente |
| `firstConfirmedReversalCloseTime` | Primeiro `CONFIRMED_REVERSAL` registrado durante a posicao, se houve |

**Interpretacao:** o P&L de `closeHistory` e um mark-to-market
contrafactual ao fechamento do candle, **nao e uma ordem executavel
ao mesmo preco**. O modelo de replays executa saidas no proximo
open, inclusive ao acionar limite de permanencia. Nao usamos o high/low
inteiro de um candle de stop para inferir lucro futuro apos a saida.
Nenhum novo take-profit, ordem real, preco tick-by-tick ou financiamento foi
adicionado. Com `includeLuna=false`, nenhuma chamada paga e feita.

### Comparacao reprodutivel SOLUSDT com filtro 50%

Em PowerShell na raiz do repositorio, com Spring Boot e MySQL ligados e
historico de SOLUSDT sincronizado:

```powershell
.\scripts\compare-pair-backtests.ps1 `
  -Symbols SOLUSDT `
  -CostLimits 50 `
  -HoldingLimits 0,15,30,60,120 `
  -AsOf 1791846239999
```

`HoldingLimits=0` equivale ao baseline **sem tempo maximo**.
A tabela e o CSV `results/exit-comparison-<asOf>.csv` contem
`MaxHoldingCandles`, retorno, drawdown, P&L encerrado/aberto,
quantidade de saídas TIME_LIMIT, duracao media e vitorias/derrotas.

**Cuidado:** comparar os mesmos candles nao congela as entradas. Sair
antecipadamente muda quando a carteira esta livre, e portanto altera
o caminho de decisoes futuras. Para isolar o efeito da saida em
operacoes rigorosamente identicas e preciso congelar as entradas
em um experimento separado. Nao otimize a regra num unico periodo
curto da Testnet; repita em multiplas janelas e fora da amostra.

## Diagnostico das entradas: operacoes vencedoras contra perdedoras

**Apenas para pesquisa; nao muda setups, stops ou ordens.** Cada posicao de
`javaPortfolio.trades[]` e a posicao aberta opcional exibem
`entryDiagnostics` com campos:

| Campo | Captura |
| --- | --- |
| `atSignal.signal` | `DecisionTrace` imutavel do candle FECHADO onde foi decidido entrar: `setup`, `reason`, `primaryTrend`, `timeframeTrends[]` (com seus `asOfCloseTime`), `volumeLevel`, `takerBalance`, `relativeVolume`, `relativeSpread`, `effortResult` |
| `atSignal.lastBarPattern` | Direcao e padroes Brooks do candle de sinal, se detectados |
| `atSignal.breakoutState`, `pullbackCandidate`, `confirmedPivotCount` | Condicoes estruturais confirmadas ate aquele fechamento |
| `atSignal.atrAtSignal`, `takerBuyShareAtSignal` | ATR e proporcao de agressao observados no candle fechado |
| `atSignal.higherTimeframeConflicts` | Quantidade de timeframes superiores cujo `Trend` divergia da tendencia principal (inclusive RANGE/UNDETERMINED) |
| `atSignal.signalClosePrice` | Fechamento da barra de sinal, **nao** preco de execucao |
| `fillOpenTime`, `filledEntryPrice`, `initialStop` | Execucao simulada na abertura seguinte e stop original |
| `stopDistancePct` | 100 × distancia absoluta fill-stop / fill |
| `entryGapPct` | 100 × (fill - close do candle de sinal) / close do sinal; assinado |
| `estimatedRoundTripCostUsdt` | Estimativa de taxa+slippage hipotetico dos dois lados com preco real de fill e stop estrutural (quantidade da operacao) |
| `estimatedCostToInitialRiskPct` | Essa estimativa dividida pelo risco BRUTO ate o stop original, ×100. Nao e resultado financeiro final |

**Garantia temporal:** os fatores técnicos são congelados no fechamento do
candle de sinal; o calculo do custo/risco usa APENAS o preco da abertura
seguinte e o stop preexistente. Se a proposta for rejeitada antes de executar,
nao aparece em `trades[]`. O rótulo vencedor/perdedor é atribuido SOMENTE
APOS o trade ser fechado; nao e usado para decidir entrar.

Exemplo com o SOLUSDT, custos 50%, 3000 candles, mesmo periodo dos testes:

```powershell
.\scripts\compare-pair-backtests.ps1 `
  -Symbols SOLUSDT `
  -CostLimits 50 `
  -HoldingLimits 0 `
  -AsOf 1791846239999 `
  -AnalyzeEntries
```

O script imprime cada trade fechado com seus fatores no instante da entrada
e um resumo por `WIN` vs `LOSS` com resultado, risco, volume,
alinhamento multi-timeframe, setups e duracao media. Salva em
`results/entry-conditions-1791846239999.csv` e
`results/entry-groups-1791846239999.csv` além do CSV habitual,
sem incluir a posicao ainda aberta na tabela de vitorias/derrotas.

**Limitacoes:** somente 11 trades fechados em 50 horas de Testnet nao
permitem inferir significancia estatistica nem usar as diferenças observadas
como filtro lucrativo. Repetir em muitas janelas fora da amostra antes de
alterar o algoritmo, principalmente se as entradas e saídas variam entre
estrategias simuladas. Não misturar Testnet e producao no mesmo banco.

## Teste em multiplas janelas sem sobreposicao e tres setups

O `POST /api/v1/backtest/run` aceita os novos campos opcionais:

- `pullbackEnabledOverride` boolean ou `null`;
- `breakoutRetestEnabledOverride` boolean ou `null`.

`null` herda a configuracao do `java-decision` global; `true/false`
substitui **somente neste POST**. Ambos falsos sao invalidos.
O servico instancia um `JavaDecisionEngine` isolado e imutavel para
cada replay, sem mudar a estrategia de outros requests ou a API em tempo real.
O `LUNA` nao recebe override de setup; por seguranca, o script sempre
solicita `includeLuna=false`.

### Comparacao em tres janelas de 3000 candles (Testnet)

Inicie a aplicacao com MySQL e parametros de risco que ja usa, e execute:

```powershell
.\scripts\compare-setup-windows.ps1 -PrintOnly
.\scripts\compare-setup-windows.ps1 -Source testnet -Windows 3 -Steps 3000 -AsOf 1791846239999 -SyncHistory
```

Se o historico necessario ja estiver sincronizado, omita `-SyncHistory`.
Sem `-AsOf`, o script tenta ancorar no ultimo fechamento local de 1m;
para um banco vazio, informe `-AsOf` e `-SyncHistory` explicitamente.

O script executa automaticamente (BOTH, PULLBACK, RETEST) em cada
janela com `CostLimitPct=50`, `ContextLimit=200`,
`breakEvenTriggerROverride=1.0`, mesmos filtros `exchangeInfo`,
mesmos custos do ambiente, e sem `maxHoldingCandles`.
Nao e necessario editar YAML nem reiniciar Spring entre os 3 setups.

**Janelas sao disjuntas para candles negociados:** o `asOf` diminui
`3000*60.000` ms a cada janela. Cada resposta deve reportar o
`fromCandleOpenTime` e `throughCandleCloseTime` exatos esperados,
alem de 3000 candles, ou o script ABORTA. Os candles anteriores de
aquecimento dos indicadores 5m/15m podem se repetir entre janelas;
eles sao apenas contexto, nao trades do replay. Se 1m/5m/15m tem
lacunas ou aquecimento insuficiente, a API retorna erro; nao
interpolamos precos nem substituimos a janela.

Em uma coleta padrao de **sete dias**, tres janelas de 3000 minutos
mais aproximadamente 200 candles anteriores de 15m podem exceder o
historico; use `-SyncHistory` para tentar ampliar via klines publicos.
A disponibilidade e os limites da API Binance continuam aplicaveis;
nao presumimos que toda a historia sera retornada. A chamada `-SyncHistory`
faz ingestao publica idempotente no banco local, nao abre ordens.

Arquivos:

- `results/setup-windows-SOLUSDT-<asOf>.csv`: retorno marcado,
  retorno realizado das posicoes fechadas, P&L aberto, DD, win/loss,
  quantidade de trades, intervalo UTC e setup de cada janela.
- `results/setup-summary-SOLUSDT-<asOf>.csv`: quantidade de janelas
  positivas, retorno **medio/mediano** por janela, pior resultado,
  DD medio e agregado descritivo de trades.

Cada janela inicia com **banca ficticia nova de US$ 300**. Os retornos
medios nao sao rendimento composto de uma conta real.
Resultados marcados incluem posicoes abertas, nao realizadas.
Repetir teste em varias janelas melhora a investigacao, mas ainda
nao garante independencia estatistica, pois regimes macro e contexto
tecnico podem atravessar os limites do replay.

### Pesquisa com OHLCV publico de producao (opt-in, SEM ordens)

Para evitar misturar OHLCV da **Testnet** e de **producao**, a aplicacao
possui o perfil opcional `research` em `application-research.yml`,
que utiliza os klines publicos de `https://fapi.binance.com` e
**exige um banco/schema MySQL separado** com credenciais
`RESEARCH_DB_URL`, `RESEARCH_DB_USERNAME` e
`RESEARCH_DB_PASSWORD`. Crie previamente o novo schema e usuario
com suas credenciais locais, distinto de `trading_bot` Testnet. Nao
aponte `RESEARCH_DB_URL` para o banco usado na Testnet!

Um exemplo de variaveis na sessao PowerShell dedicada a pesquisa:

```powershell
$env:SPRING_PROFILES_ACTIVE = 'research'
$env:RESEARCH_DB_URL = 'jdbc:mysql://127.0.0.1:3307/trading_bot_research?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true'
$env:RESEARCH_DB_USERNAME = '<usuario-research>'
$env:RESEARCH_DB_PASSWORD = '<senha-research>'
$env:TRADING_SYMBOLS = 'SOLUSDT'
$env:TRADING_CAPITAL_USDT = '300'
$env:TRADING_RISK_PER_TRADE_PCT = '0.5'
$env:TRADING_DAILY_STOP_PCT = '3'
$env:TRADING_LEVERAGE = '1'
$env:RISK_FEE_PER_SIDE_PCT = '0.05'
$env:RISK_SLIPPAGE_PER_SIDE_PCT = '0.02'
$env:JAVA_DECISION_REQUIRE_HIGHER_TF = 'false'
$env:LUNA_ENABLED = 'false'
$env:TRADING_ORDERS_ENABLED = 'false'
mvn spring-boot:run
```

Na **outra** janela PowerShell:

```powershell
.\scripts\compare-setup-windows.ps1 -Source production -ConfirmIsolatedDatabase -Windows 3 -Steps 3000 -SyncHistory -AsOf 1791846239999
```

`-ConfirmIsolatedDatabase` e uma declaracao explicita do operador;
a API **nao verifica fisicamente** que o schema MySQL foi isolado.
A API `/api/v1/market-data/settings` identifica a origem efetiva do
backend (`source=TESTNET/PRODUCTION`), e o script rejeita origem
diferente da requisitada. Se a Binance bloquear o acesso regional ou
limitar dados, a pesquisa nao podera prosseguir ate que o historico
publico esteja disponivel. Nenhum resultado de producao foi
executado nem comprovado neste PR.

**Observacao de validade:** a escolha atual do PULLBACK foi feita
olhando a janela de `asOf=1791846239999`, entao essa janela e
"in-sample". Para medir generalizacao, use periodos de outras datas,
especialmente os historicamente anteriores ou posteriores nao usados
para definir parametros, e nao ajuste filtros com base apenas no melhor
resultado de cada janela.

## Replay estendido: tres janelas de 300 horas (SOLUSDT)

A partir desta versao, `compare-setup-windows.ps1` usa por padrao
**3 janelas x 18.000 candles fechados de 1m** = 300h por janela,
900h (37,5 dias) de pregacao continua nos contratos futuros.
Cada janela e um **UNICO replay** com carteira, posicao e stops
continuos (nao e soma de 6 replays de 3000 candles).

```powershell
.\scripts\compare-setup-windows.ps1 -PrintOnly
.\scripts\compare-setup-windows.ps1 `
    -Source testnet `
    -Windows 3 `
    -Steps 18000 `
    -AsOf 1791846239999 `
    -SyncHistory
```

Novo limite do `POST /api/v1/backtest/run`: `steps=2..18000`
(`includeLuna=true` continua limitado a **10 steps**).
`GET /api/v1/backtest/status` indica esse limite.
No comparador a requisicao envia `includeDecisionHistory=false`:
omite a lista completa de ~18.000 `DecisionTrace` por resposta,
mas **preserva** as diagnosticas das entradas executadas, as
operacoes fechadas e a posicao aberta, assim como fee/slippage e
P&L completo. Para requests anteriores, o campo e opcional:
ausente ou `true` conserva a resposta detalhada anterior.

A sincronizacao manual usa **chunks contiguos de 7 dias** para os
intervalos 1m, 5m e 15m. A janela total de coleta costuma cobrir
aproximadamente **40 dias**, somando 37,5 dias de trades e o
aquecimento de indicadores (~2,4 dias para contextLimit=200). Isso
evita o limite de 30 dias por requisicao da API e impede o teto
configurado de 30 paginas x 1000 candles de truncar uma requisicao
de 1m. As chamadas sao idempotentes no MySQL.

**Importante:** a Binance Testnet nao garante oferecer os 40 dias
de dados completos. Se faltarem barras ou historico dos timeframes
superiores, o replay falha de forma explicita em vez de preencher
precos artificiais. Caso isso aconteca, use o perfil de pesquisa
`research` com uma base dedicada, dados publicos de producao e
`-Source production -ConfirmIsolatedDatabase`, conforme instrucoes
nesta documentacao. Nao transfira candles de producao para
o banco da Testnet.

O processamento de 18.000 candles por request e mais pesado:
9 backtests com analise Price Action/VPA/indicadores podem levar
bastante tempo em um computador local. `-TimeoutSec` pode ser
ajustado entre 30 e 1800 segundos caso precise, mas isso nao
altera a validade do backtest. O parametro `-Steps 3000`
continua disponivel para comparacoes curtas. Os CSVs usam o mesmo
padrao `results/setup-windows-<symbol>-<asOf>.csv` e
`results/setup-summary-<symbol>-<asOf>.csv`, portanto salve os
anteriores antes de reutilizar o mesmo `asOf`.
