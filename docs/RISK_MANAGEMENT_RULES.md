# Etapa 9 — Gestao de risco e stops (PREVIEW ONLY)

**Nao existe ordem na Binance, posicao mantida em memoria, nem trailing protetivo real.**
O sistema calcula propostas descartaveis de entrada e de ajuste de stop; o chamador
precisara armazenar suas proprias carteiras independentes quando implementarmos backtesting.
Nao habilitar operacao real a partir desta etapa.

## Parametros obrigatorios (nenhum valor financeiro presumido)

- `TRADING_CAPITAL_USDT`: capital de referencia da simulacao.
- `TRADING_RISK_PER_TRADE_PCT`: percentual maximo estimado a perder na operacao.
- `TRADING_DAILY_STOP_PCT`: percentual maximo de perda realizada diaria a partir do capital de referencia.
- `TRADING_LEVERAGE`: alavancagem para limitar margem hipotetica (nao altera risco unitario).
- `RISK_FEE_PER_SIDE_PCT` e `RISK_SLIPPAGE_PER_SIDE_PCT`: custos hipoteticos de cada lado.

Tecnicos, configuraveis: `RISK_BREAK_EVEN_TRIGGER_R=1.0` e
`RISK_STRUCTURAL_ATR_BUFFER=0.5`. Sao parametros experimentais, nao regras de Brooks.

`GET /api/v1/risk/status` mostra se todos os parametros estao configurados.
`POST /api/v1/risk/preview` aceita um `DecisionResult` do Java ou Luna e
informacoes explicitas de carteira e filtros de instrumento.

Exemplo PowerShell (substitua os filtros pelo exchangeInfo real da sua corretora/conta):

```powershell
$d = Invoke-RestMethod 'http://127.0.0.1:8080/api/v1/decision/java?symbol=BTCUSDT&intervals=5m,15m&limit=200'
$payload = @{
    decision = $d
    equityUsdt = '300'
    realizedDailyPnlUsdt = '0'
    priceTick = '0.1'
    quantityStep = '0.001'
    minimumQuantity = '0.001'
    minimumNotionalUsdt = '5'
    candleLimit = 200
    position = $null
} | ConvertTo-Json -Depth 20
Invoke-RestMethod -Method Post -ContentType 'application/json' -Body $payload `
    'http://127.0.0.1:8080/api/v1/risk/preview'
```

Os numeros no corpo sao APENAS exemplo de preenchimento e nao devem ser
assumidos como filtros oficiais, taxa efetiva ou tolerancias universais.
O endpoint verifica simbolo/timeframe/asOf e fechamento exato em banco.
Se a decisao ja estiver defasada, o snapshot sera recusado ou a proposta sera
apenas ilustrativa, nunca negociavel.

## Abertura LONG/SHORT

- Apenas com `DecisionResult` confirmado: LONG/SHORT e reason compatível.
- Stop inicial: ultimo pivot confirmado da direcao defensiva (LOW para LONG,
  HIGH para SHORT), somado a buffer ATR; arredondado para o lado conservador.
- Se nao existe pivot do lado apropriado, a entrada e BLOQUEADA.
- Orçamento de risco: menor entre `% do menor capital/equity` e capacidade restante
  do limite de perda diaria. Custo por unidade inclui diferenca ao stop, taxas e
  slippage de entrada e saida; quantidade arredondada PARA BAIXO ao step.
- Margem limita notional a equity*leverage, mas **nao** modela preco de liquidacao,
  maintenance margin, funding, falha de marcação ou fills parciais.
- `priceTick`, `quantityStep`, `minimumQuantity`, `minimumNotionalUsdt` sao
  introduzidos pelo chamador; a etapa ainda nao extrai filtros reais da Binance.
- Nao ha estado global de carteira nem controle de varias posicoes simultaneas;
  `realizedDailyPnlUsdt` e `position` sao declaracoes do chamador. Portanto **nao ha
  limite diario garantido no nivel da conta** enquanto nao houver persistencia.

## Posicao existente

- Antes de calcular novos stops, verifica se o candle analisado TOCOU o stop
  que ja existia. `STOP_TOUCHED` tem prioridade, sem presumir fill perfeito.
- `EXIT_SIGNAL` considera somente decisao de reversao confirmada da engine;
  nenhum fechamento real e realizado.
- Com lucro no fechamento >= trigger em R do risco estrutural original, propoe
  break-even incluindo aproximacao de taxas/slippage de ida e volta.
- Pivots confirmados DEPOIS da abertura podem propor trailing estrutural com ATR.
- Stop so pode ficar mais favoravel; nunca amplia o risco. Um novo stop proposto
  entra em vigor **NO PROXIMO candle**, sem confundir max/min intrabar atual.

## Limitacoes importantes

- Nenhuma chamada a OpenAI e disparada por `/risk/preview`. O motor depende de
  decisao obtida anteriormente; Luna continua governado por `LUNA_ENABLED`.
- Nao calcula lucro realizado, mark price, spread real, funding ou liquidacao.
- O hipotetico break-even nao garante resultado liquido zero (gaps/slippage).
- Cotacao limitada a pares USDT por enquanto; outras moedas exigem conversao.
- Parametros tecnicos experimentais devem ser calibrados em backtesting.
- `RiskPreviewResult` e uma proposta estateless, nao substitui stop exchange-side.


## Filtro experimental: custo de ida-e-volta / risco inicial bruto

Opcional: `RISK_MAX_COST_TO_INITIAL_RISK_PCT=30`. Se a variavel estiver
ausente/vazia, **o filtro fica desligado** para preservar o comportamento-base.
O numero 30% e hipotese para teste, nao recomendacao de rentabilidade.

Depois de selecionar o stop estrutural confirmado, o motor compara **por unidade**:

```text
Risco inicial bruto = abs(preco de entrada - stop inicial)
Custo estimado de ida e volta = (entrada + stop) *
      (fee por lado + slippage por lado) / 100
Custo / risco (%) = 100 * custo estimado / risco inicial bruto
```

Se o quociente **ultrapassar** o limite (igualdade permitida), a entrada retorna
`action=BLOCKED`, `reason=COST_TO_RISK_TOO_HIGH`. O resultado aparece também
em `javaDecisionHistory[].riskAction` e `riskReason` do backtest.

A comparacao ocorre antes do dimensionamento e, no backtest, **novamente na
abertura do candle seguinte** utilizando o preco simulado de entrada com
slippage. Assim, um gap que aproxime a entrada do stop nao dribla o filtro.
Uma rejeicao na abertura seguinte conta em `entriesBlocked`, mas nao altera
retroativamente o motivo de risco emitido no fechamento do candle de sinal.

O criterio independe de quantidade e alavancagem; considera apenas custo de
ida e volta hipotetico ao stop, sem funding, liquidacao, liquidez insuficiente
ou gaps extremos. Sua aplicacao pode **reduzir sinais mas nao garante lucro**.

`GET /api/v1/risk/status` informa em `costFilter` se o limite do ambiente
esta ativo.

O filtro usa a mesma `RiskPolicy` para Java e Luna. Nenhuma ordem real e enviada.
