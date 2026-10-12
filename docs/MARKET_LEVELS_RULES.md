# Etapa 5: niveis historicos e Fibonacci (somente leitura)

## Origem dos dados

A analise usa apenas candles **fechados**, consecutivos, do mesmo par/timeframe.
Reutiliza `PriceActionAnalyzer` (Etapa 3) para detectar pivots locais: um pivot
somente esta disponivel depois dos `PRICE_ACTION_PIVOT_RIGHT` candles fechados
que o confirmam. Nao ha consulta a candles futuros e nao ha indicacao de compra/venda.

## Zonas candidatas de suporte / resistencia

- `PIVOT_LOW`: zona agrupada a partir de fundos locais confirmados.
- `PIVOT_HIGH`: zona agrupada a partir de topos locais confirmados.
- A origem do pivot **nao garante** que o nivel continue atuando como suporte ou
  resistencia, inclusive quando o preco atual atravessa uma zona.
- `BELOW`, `ABOVE`, `OVERLAPPING`: localizacao da zona em relacao ao ultimo fechamento.
- `pivotCount`: numero de pivots confirmados agrupados, **nao numero de testes
  reais nem estimativa de probabilidade de defesa do nivel**.
- As zonas sao apresentadas da mais proxima para a mais distante do ultimo preco
  de fechamento, com limite configuravel; proximidade nao significa confiabilidade.

Agrupamento heuristico por tipo de pivot, em ordem de preco. A tolerancia absoluta e:

`max(ATR_simples * MARKET_LEVELS_ATR_MULTIPLIER, close * MARKET_LEVELS_MIN_ZONE_PCT / 100)`

O ATR usa True Range medio dos ultimos `MARKET_LEVELS_ATR_LOOKBACK` candles
fechados e o close anterior de cada um. O algoritmo limita a amplitude de
agrupamento para evitar zonas artificialmente extensas. Os limites `lowerBound`
e `upperBound` incluem meia tolerancia alem dos extremos do grupo.

Tolerancia e pivots dependem do timeframe. Historico maior, como 1h com `limit=500`,
pode fornecer niveis de varios dias; este modulo nao mistura timeframes.
O agrupamento por diferentes timeframes sera responsabilidade do `MarketContextBuilder`.

## Fibonacci

Seleciona os ultimos dois pivots cronologicos de tipos opostos, ambos confirmados,
com precos diferentes. Nao seleciona swings usando informacoes posteriores
indisponiveis no momento do snapshot.

- `direction`: `UP` ou `DOWN` conforme deslocamento do preco entre os pivots.
- `startPrice` e `endPrice`: ancoras no preco dos pivots confirmados.
- Retracoes `0.236`, `0.382`, `0.500`, `0.618`, `0.786`:
  `endPrice - (endPrice - startPrice) * ratio`.
- Extensoes `1.272`, `1.618`:
  `startPrice + (endPrice - startPrice) * ratio`.
- Se nao existir par de pivots opostos confirmado, `fibonacci` sera `null`.

As formulas servem a swings de alta e de baixa. Nao ha sinal de entrada,
confirmacao de reversao, forecast ou claim de efetividade estatistica.
Fibonacci e um recurso de confluencia complementar e **nao corresponde a um
criterio prescritivo de Al Brooks**.

## Valores iniciais ajustaveis

| Configuracao | Inicial | Significado |
|---|---|---|
| `MARKET_LEVELS_ATR_LOOKBACK` | 14 | Numero de True Ranges para media |
| `MARKET_LEVELS_ATR_MULTIPLIER` | 0.5 | Peso do ATR na tolerancia da zona |
| `MARKET_LEVELS_MIN_ZONE_PCT` | 0.10 | Piso percentual da tolerancia |
| `MARKET_LEVELS_MAX_ZONES` | 12 | Maximo de zonas retornadas |
| `PRICE_ACTION_PIVOT_LEFT` | 2 | Barras a esquerda para confirmar pivot |
| `PRICE_ACTION_PIVOT_RIGHT` | 2 | Barras a direita para confirmar pivot |

Esses valores sao hipoteses do projeto para testes, NAO parametros recomendados
pela obra de Brooks, nem parametros comprovados para lucratividade.

## API e exemplos

`GET /api/v1/analysis/market-levels?symbol=BTCUSDT&interval=5m&limit=200`

Para analise mais longa use `interval=1h&limit=500` apos sincronizar os
candles de 1h (`MARKET_DATA_INTERVALS` precisa conter `1h` na coleta).
A consulta devolve `asOfCloseTime`, `candlesAnalyzed`, `lastClose`, `atr`,
`zoneTolerance`, `zones` e `fibonacci`. Os numeros nao sao arredondados para
o tick size da Binance: **nao podem ser usados como preco de ordem sem
normalizacao pelo instrumento, gestao de risco e validacao posterior**.

Respostas de erro: `400` para `limit` menor que o minimo configurado;
`422` quando faltarem candles consecutivos ou o historico estiver invalido.
A Testnet nao necessariamente representa a liquidez nem o historico do mercado real.
