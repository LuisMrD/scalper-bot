# Etapa 4 — Volume Price Analysis (VPA)

Esta implementacao se inspira na ideia de **esforco versus resultado** discutida por Anna Coulling. Os criterios numericos a seguir sao **heuristicas criadas para o robo**, nao regras ou previsoes atribuidas a autora.

## Fonte, unidade e limites

- Usa apenas candles fechados da Binance USD-M Futures armazenados na Etapa 2.
- `relativeVolume` = volume negociado em moeda de cotacao (`quoteVolume`) do candle atual / media dos **N candles imediatamente anteriores** do mesmo par e timeframe.
- `relativeSpread` = amplitude `high - low` atual / media da amplitude dos N candles anteriores. "Spread" aqui significa **amplitude da barra** na terminologia VPA, nao spread bid-ask do livro de ofertas.
- `takerBuyShare` = volume agressor de compra em moeda de cotacao / volume total em moeda de cotacao do mesmo candle.
- Se um denominador for zero, o respectivo indicador e `null`/`UNAVAILABLE`, nunca infinito, NaN ou valor inventado.
- `takerBuyShare` e proxy da proporcao de compras agressoras no candle, **nao CVD de negociacoes individuais**, nem prova de absorcao ou intervencao institucional.
- A observacao requer no minimo `lookback + 1` candles **continuos e fechados**, ordenados por tempo crescente e do mesmo instrumento/timeframe. Rejeita lacunas, duplicatas e candles abertos.

## Categorias descritivas

- `HIGH` / `LOW`: volume relativo acima/abaixo do limite configurado.
- `WIDE` / `NARROW`: amplitude relativa acima/abaixo do limite configurado.
- `HIGH_EFFORT_SMALL_SPREAD`: volume alto acompanhado de amplitude reduzida. Pode indicar diversas situacoes, inclusive concentracao de liquidez, e **nao determina a direcao**.
- `BULLISH_WIDE_SPREAD_HIGH_VOLUME` e `BEARISH_WIDE_SPREAD_HIGH_VOLUME`: volume e amplitude elevados, corpo direcional e fechamento proximo de um extremo, de acordo com limites configurados.
- `HIGH_VOLUME_UPPER_REJECTION` e `HIGH_VOLUME_LOWER_REJECTION`: pavio dominante e volume alto. Nao e sinal de trade nem previsao de reversao.
- `BUY_DOMINANT` / `SELL_DOMINANT`: taker buy share acima de limiar ou abaixo do complementar; nao equivale a desequilibrio do livro de ofertas.

## Configuracao inicial — sujeita a testes empiricos

`lookback=20`, `recent-count=20`, `high-volume-ratio=1.8`, `low-volume-ratio=0.6`, `wide-spread-ratio=1.5`, `narrow-spread-ratio=0.7`, `strong-taker-share=0.6`, `min-body-ratio=0.55`, `extreme-close-ratio=0.75`.

Esses valores sao parametros de classificacao, **nao** configuracoes de risco financeiro; os parametros de banca, alavancagem e stop continuam sem defaults.

## API

`GET /api/v1/analysis/volume?symbol=BTCUSDT&interval=5m&limit=150`

A resposta contem observacoes cronologicas, timestamp UTC em epoch ms, volume de base e cotacao, indices relativos e rotulos. Endpoint de somente leitura: **nenhuma ordem e enviada** e a IA nao e consultada.

## Sem lookahead / uso futuro

As medias de referencia sao calculadas so com velas anteriores a vela analisada. O modulo nao utiliza informacoes de candles futuros nem compara pares distintos. Os sinais sao descricoes que a etapa de contexto e os motores Java/Luna poderao usar posteriormente, juntamente com Price Action, estrutura e controles de risco.
