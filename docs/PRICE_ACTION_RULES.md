# Price Action — Etapa 3 (Brooks)

## Referencia e limites

**Referencia de leitura:** Al Brooks, *Operando Price Action: Tendencias*, especialmente
capitulos sobre barras, reversoes, segundas entradas, swings, canais e sinais de forca.
O capitulo de tendencias descreve que swings de alta costumam produzir correcoes
sucessivamente mais altas e novas maximas. Tambem distingue correcao local de reversao
mais ampla. Esses conceitos orientam a estrutura deste modulo.

**Adaptacoes nossas, NAO regras numericas de Brooks:**
- Corpo de tendencia: modulo do corpo >= 60% da amplitude da barra.
- Doji: modulo do corpo <= 25% da amplitude.
- Rejeicao por pavio: pavio >= 50% da amplitude, fechamento na metade correspondente.
- Pivot confirmado: 2 barras anteriores e 2 posteriores com extremos estritamente menores/maiores.
- Tendencia: dois ultimos pivots altos e baixos ascendentes (UP) ou descendentes (DOWN).
- Rompimento: fechamento alem do extremo das 10 barras anteriores.
- Follow-through: uma barra adicional fecha alem do extremo da barra de rompimento na mesma direcao.

Todas essas quantidades podem ser ajustadas por configuracao e devem ser calibradas
em backtest antes de qualquer estrategia ou sistema de decisao utilizá-las.

## Sem lookahead

Os pivots possuem `pivotOpenTime` e `confirmedAtCloseTime` separados. Um pivot
so consta no snapshot depois que seus candles de confirmacao ja fecharam.
Eventos de rompimento do candle atual sao candidatos; follow-through exige outro
candle fechado. Nao ha informacao futura nem antecipacao de barra aberta.

## Entrada, saida e confiabilidade

Esta etapa nao calcula operacoes, alavancagem, lucro, stop ou probabilidade de acerto.
O `pullbackCandidate` e apenas uma marca de contra-movimento com relacao ao trend
baseado em pivots; NAO e um setup validado nem uma ordem de compra/venda.

O algoritmo rejeita lacunas, timestamps fora de ordem, timeframes misturados,
candles abertos e quantidade de candles abaixo do minimo configurado, retornando
HTTP 422 quando o erro vem dos dados da analise.

## Endpoint (somente localhost)

`GET /api/v1/analysis/price-action?symbol=BTCUSDT&interval=5m&limit=150`

A resposta contem: `symbol`, `interval`, `asOfCloseTime`, `candlesAnalyzed`, `trend`,
`recentBars`, `confirmedPivots`, `breakout`, `pullbackCandidate`.

Os candles retornados por `MarketDataService.recent` chegam decrescentes e sao
invertidos antes de avaliar. Nao se modifica o banco nesta etapa.
