# Etapa 6 — MarketContextBuilder

## Objetivo

Entregar um **contexto deterministico e somente leitura** com Price Action (Brooks), Volume Price Analysis (Coulling), niveis historicos, ATR e Fibonacci para futuros motores independentes `JavaDecisionEngine` e `LunaDecisionEngine`.

Nao calcula recomendacoes de LONG, SHORT, HOLD ou EXIT. Nao invoca API da OpenAI nem envia ordens a Binance.

## Integridade temporal / anti-lookahead

- Por timeframe, `MarketContextService` chama **uma unica vez** `MarketDataService.recentAtOrBefore(symbol, interval, limit, cutoff)`.
- O cutoff `asOf` e um timestamp UTC em **milissegundos**, capturado uma vez por requisicao; se ausente, usa o instante atual do servidor. Valores futuros ou negativos sao rejeitados com HTTP 400.
- A consulta JPA inclui `closed=true` e `closeTimeEpochMs <= cutoff`, ordena do mais recente para o mais antigo e limita a janela.
- O service reordena os candles em ordem cronologica ascendente, e **uma unica lista** alimenta os tres analisadores existentes.
- A aplicacao recusa dados incompletos, barras abertas, timestamps futuros, gaps, timeframes ou pares misturados. HTTP 422 indica que o contexto nao pode ser calculado com seguranca. O request multiplos timeframes falha inteiro se qualquer timeframe falhar (sem conclusoes parciais).
- Os snapshots resultantes compartilham o mesmo `asOfCloseTime` **dentro do timeframe**; em `/multi`, diferentes intervalos podem ter ultimos fechamentos diferentes, mas nenhum deles ultrapassa o cutoff compartilhado.
- `asOf` aplicado a candles ja coletados permite consultas historicas reproduziveis; ainda nao existe um motor de replay ou backtest completo.

## Endpoints

```http
GET /api/v1/analysis/market-context?symbol=BTCUSDT&interval=5m&limit=200
GET /api/v1/analysis/market-context/multi?symbol=BTCUSDT&intervals=1m,5m,15m&limit=200
GET /api/v1/analysis/market-context?symbol=BTCUSDT&interval=5m&limit=200&asOf=1791800000000
```

- `limit` = quantidade maxima de candles por timeframe (21..500 com os parametros padrao).
- `market-context.recent-candles` = quantidade de OHLCV recente anexada ao snapshot (padrao 30).
- `recentCandles` contem o fim da janela em ordem cronologica ascendente.
- `priceAction`, `volume` e `marketLevels` preservam os modelos/semantica dos modulos existentes.
- `requestedAsOfEpochMs` e o cutoff comum; `asOfCloseTime` e o ultimo fechamento REALMENTE presente em cada timeframe.
- De forma intencional, **nao** ha pontuacao de confianca, probabilidade de sucesso ou sinal operacional.

## Condicoes de falha e limitacoes

- 400: timeframes invalidos/duplicados, corte futuro ou limite de candles insuficiente.
- 422: historico incompleto ou com lacunas; aplica-se a consulta inteira.
- Dados da Binance Testnet ainda nao devem ser confundidos com dados do mercado real.
- O estado da fonte dos candles nao e persistido na chave unica de instrumentos; nao misture Testnet e producao em um mesmo banco.
- Para teste de ambos os motores, passar **a mesma instancia serializada do snapshot** aos dois, sem reconsultar os candles independentemente.

## Verificacao

```powershell
mvn clean test
mvn spring-boot:run
Invoke-RestMethod 'http://127.0.0.1:8080/api/v1/analysis/market-context?symbol=BTCUSDT&interval=5m&limit=200'
Invoke-RestMethod 'http://127.0.0.1:8080/api/v1/analysis/market-context/multi?symbol=BTCUSDT&intervals=1m,5m,15m&limit=200'
```

Para usar 200 candles de `15m`, sincronizar historico suficiente com `POST /api/v1/market-data/bootstrap` e `MARKET_DATA_BOOTSTRAP_DAYS=7`. Nao ha invocacao de modelo de IA nem custo de API OpenAI nesta etapa.
