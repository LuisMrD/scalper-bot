# Etapa 7 — Motor Java de decisoes hipoteticas

## Contrato e integridade temporal

- `DecisionEngine#evaluate(MultiTimeframeMarketContext, PositionState)` e o contrato comum planejado com a IA.
- A entrada e formada pelas analises **da mesma amostra** de candles fechados, com um `requestedAsOfEpochMs` comum; os times de fechamento podem diferir entre timeframes.
- A **primeira janela temporal** (`intervals=1m,5m,15m`) e a de entrada; as demais validam tendencia. Ordem importa.
- O motor nao consulta a Binance nem executa ordens. Estado de posicao informado no endpoint e somente hipotetico.
- Historico insuficiente e lacunas geram HTTP 422 no MarketContextService; o motor nunca simula dados ausentes.
- Se a ultima barra do timeframe primario estiver mais velha que 2 vezes a duracao do candle (parametro configuravel), retorna `WAIT` com `STALE_MARKET_DATA`; para uma posicao hipotetica declara `HOLD` apenas como ausencia de ordem, **nao** como garantia de seguranca.
- `asOf` no endpoint passa diretamente ao mesmo MarketContextService da Etapa 6; informacoes posteriores nao sao carregadas.

## Entrada hipotetica

1. Direcao primaria deve ser `UP` ou `DOWN`, conforme pivôs confirmados. Range/indefinido => `WAIT`.
2. Se `require-higher-timeframe-alignment`, cada timeframe adicional deve ter tendencia igual; divergencia => `WAIT`.
3. `PULLBACK`: penultima barra corrige a tendencia; a ultima encerra alem do extremo da penultima com barra de tendencia ou rejeicao. O extremo da correcao deve tocar uma zona do tipo apropriado **ou** um nivel de retracao de Fibonacci calculados com pivôs confirmados. O sinal depende de um candle de retomada fechado.
4. `BREAKOUT_RETEST`: uma barra **anterior** rompe com fechamento o extremo de N barras anteriores; o fechamento atual testa a linha rompida dentro da tolerancia zonal do ATR e recupera o lado do rompimento. Fecha rejeitando a tese se houve invalidacao intermediaria.
5. Volume do ultimo candle precisa ser `NORMAL`/`HIGH` e nao contrariar o lado. Por padrao, exige `BUY_DOMINANT` para LONG ou `SELL_DOMINANT` para SHORT. O valor vem do taker buy share da Binance.
6. Sem setup e confirmacao suficientes: `WAIT`. Nenhum `confidence` numerico e produzido.

As janelas de 10 barras anteriores e 4 barras de reteste sao **heuristicas nossas**. Brooks e Coulling nao prescrevem estes parametros fixos. O setup e uma hipotese a ser testada, **nao** indicacao de lucro esperado.

## Posicao hipotetica ja aberta

- Para `positionSide=LONG/SHORT`, nunca retorna nova entrada.
- `EXIT` exige tendencia estrutural primaria confirmada para o lado oposto, candle final contra a posicao e volume `HIGH` com dominancia agressora oposta. Qualquer condicao faltante => `HOLD`.
- **A ausencia de `EXIT` nao equivale a risco controlado**: stop inicial e trailing stop ficam a cargo do futuro RiskManager e de ordens protetivas independentes do motor de decisao.
- Nao ha risco, stop ou take profit implicitos. Os parametros financeiros obrigatorios continuam sem defaults.

## API de simulacao

`GET /api/v1/decision/java?symbol=BTCUSDT&intervals=1m,5m,15m&limit=200&positionSide=FLAT&asOf=<epoch_ms>`

Enums de entrada: `LONG`, `SHORT`, `WAIT`.
Enums de gerenciamento: `NONE`, `HOLD`, `EXIT`.
Setup: `NONE`, `PULLBACK`, `BREAKOUT_RETEST`.
`reason` enum documenta a causa sem fazer afirmacoes sobre win rate.

## Limitacoes

- O calculo estrutural de pullback e versao inicial deliberadamente restrita: precisa do padrao de dois candles e proximidade de zona/retracao. Nao e uma implementacao exaustiva do metodo Al Brooks.
- A janela `recentCandles` do MarketContextBuilder precisa ter pelo menos `breakoutLookback + retestMaxBars + 1` candles para permitir retestes; caso contrario, esse setup naturalmente nao aparece.
- Um reteste nao assegura a sustentacao futura do rompimento; VPA apenas adiciona evidencias observaveis.
- Nao executa ordens nem dimensiona risco; resultados precisam ser validados por backtesting sem lookahead, incluindo taxas, funding, spread e slippage.
