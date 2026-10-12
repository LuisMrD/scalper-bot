# Binance Scalping Bot — Etapa 2 (Market Data)

Projeto exclusivo da Binance **USDⓈ-M Futures**, com **Java 25**, **Spring Boot 4.1.1**, **Maven**, **MySQL 8.4** e **Flyway**. Comeca em BTCUSDT, mas permite diversos pares. Mantida a estrutura de pacotes criada na Etapa 1. **Nao ha envio de ordens, estrategia, chamadas GPT ou paper trading nesta etapa.**

## Requisitos no Windows

- JDK 25 e Maven 3.9+ (`java -version`, `mvn -version`).
- Docker Desktop (opcional se ja possui MySQL) e portas 3307/8080 disponiveis.
- Acesso HTTPS/WSS da sua rede a Binance Futures Testnet.

```powershell
docker compose up -d mysql
mvn clean test
mvn spring-boot:run
```

`http://127.0.0.1:8080/actuator/health`

## Etapa 2: Market Data publico, somente leitura

- `BinancePublicClient`: GET `/fapi/v1/exchangeInfo` e GET `/fapi/v1/klines` (sem API key).
- `BinanceExchangeInfo`: valida simbolos configurados como contratos perpetuos ativos no USD-M.
- `BinanceKlineDecoder`: interpreta OHLCV, negocios e taker-buy dos payloads REST/WS; ignora WebSocket nao-kline, candles abertos e COIN-M (st=2).
- `MarketDataService`: busca historico em paginas por tempo, aceita somente candles fechados, suporta multi-par e multi-timeframe e sincroniza novamente sem duplicar.
- `CandleStorageService`: persiste com chave unica `(instrument_id,timeframe,open_time_epoch_ms)` e UPSERT atomico no MySQL.
- `BinanceKlineWebSocket`: opcional, assina streams `@kline_{interval}`, filtra x=true e tenta reconectar automaticamente. Usa o WS publico; nao e user data stream.
- `MarketDataScheduler`: reconcilia por REST uma janela recente periodicamente para recuperar buracos apos instabilidades do WebSocket. Bootstrap historico opcional.
- Flyway V2: adiciona numero de negocios e volume taker buy da moeda de cotacao.
- Endpoints REST locais para sincronizar e consultar candles fechados.

## Inicializacao segura

Por padrao, **Market Data automatico esta desligado**, evitando requisicoes inesperadas. Para executar uma sincronizacao inicial manual de 7 dias:

```powershell
$env:SPRING_PROFILES_ACTIVE = 'testnet'
$env:TRADING_SYMBOLS = 'BTCUSDT,ETHUSDT'
$env:MARKET_DATA_INTERVALS = '1m,5m,15m'
mvn spring-boot:run

# Em outro terminal, na mesma maquina:
Invoke-RestMethod -Method Post 'http://127.0.0.1:8080/api/v1/market-data/bootstrap'
Invoke-RestMethod 'http://127.0.0.1:8080/api/v1/market-data/candles?symbol=BTCUSDT&interval=5m&limit=10'
```

O `bootstrap` usa os dias configurados (`MARKET_DATA_BOOTSTRAP_DAYS`, padrao 7). Para coleta continua, defina **antes de iniciar**:

```powershell
$env:MARKET_DATA_WS_ENABLED = 'true'
$env:MARKET_DATA_BOOTSTRAP_ENABLED = 'true'
$env:MARKET_DATA_WS_PATH = '/market/stream'
mvn spring-boot:run
```

Se a Testnet ainda usar a rota anterior, teste `MARKET_DATA_WS_PATH='/stream'`. Os hosts continuam restritos a Testnet no perfil `testnet`.

- Reconexao WebSocket conferida a cada 30 segundos (configuravel).
- Reconciliacao REST a cada 60 segundos quando WS ou bootstrap automatico estiver habilitado.
- A rotina de reconciliacao olha para os ultimos 120 minutos, ou ao menos duas barras do timeframe, o que for maior.
- Uma falha em REST nao faz retry em loop: a tentativa ocorre novamente no proximo ciclo; erros 429 e 418 nao devem ser ignorados.
- Nao ha garantia de recuperacao de lacunas historicas maiores que a janela de reconciliacao sem chamar o `bootstrap` ou `/sync`.

## Endpoints disponiveis (somente localhost)

| Metodo | URL | Finalidade |
|---|---|---|
| GET | `/api/v1/market-data/settings` | Estado de coleta |
| GET | `/api/v1/market-data/candles?symbol=BTCUSDT&interval=1m&limit=100` | Ultimos candles fechados (mais recente primeiro) |
| POST | `/api/v1/market-data/bootstrap` | Sincronizar janela inicial para todos pares/timeframes |
| POST | `/api/v1/market-data/sync?symbol=BTCUSDT&interval=5m&from=...&until=...` | Sincronizar um intervalo explicito em epoch ms UTC |
| GET | `/api/v1/system/status` | Status geral, sem ordens |

`/sync` restringe a busca a **30 dias** por requisicao e possui teto configuravel de paginas (por padrao 30). Se o resultado registrar `pages=30` e nao cobrir o intervalo completo, continue em janelas menores. O numero `persisted` conta barras enviadas para UPSERT, incluindo atualizacoes de barras existentes; nao e apenas novos inserts.

### Exemplo de sincronizacao de 1 hora

```powershell
$to = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$from = $to - 3600000
Invoke-RestMethod -Method Post "http://127.0.0.1:8080/api/v1/market-data/sync?symbol=BTCUSDT&interval=1m&from=$from&until=$to"
```

## Configuracoes principais

| Variavel | Padrao | Significado |
|---|---|---|
| `TRADING_SYMBOLS` | `BTCUSDT` | Lista separada por virgula |
| `MARKET_DATA_INTERVALS` | `1m,5m,15m` | Timeframes suportados (ate 1d, sem 1M) |
| `MARKET_DATA_WS_ENABLED` | `false` | Consumo continuo por WebSocket |
| `MARKET_DATA_BOOTSTRAP_ENABLED` | `false` | Bootstrap automatico na primeira rodada |
| `MARKET_DATA_BOOTSTRAP_DAYS` | `7` | Dias de historico |
| `MARKET_DATA_PAGE_LIMIT` | `1000` | Registros REST por pagina (max 1500) |
| `MARKET_DATA_MAX_PAGES` | `30` | Paginas maximas por solicitacao |
| `MARKET_DATA_RECONCILE_MINUTES` | `120` | Janela de reparo REST |
| `MARKET_DATA_WS_PATH` | `/market/stream` | Prefixo de streams; legado `/stream` |
| `TRADING_ORDERS_ENABLED` | `false` | Qualquer `true` falha o startup |

**Perfis:** `local`, `backtest`, `paper` e `testnet`. Todos apenas coletam dados nesta etapa. A Testnet aponta para `demo-fapi.binance.com` e `demo-fstream.binance.com`. Para um backtesting de mercado real em etapas futuras, sera importante coletar tambem dados da producao *somente leitura* e documentar a fonte de cada conjunto; atualmente o projeto usa a Testnet por seguranca e ainda nao distingue o ambiente de origem na chave unica dos candles. Nao misture dados de ambientes distintos no mesmo banco.

## Banco de dados

- `instruments`: simbolos ativos validados pelo `exchangeInfo` do ambiente configurado.
- `candles`: somente candles fechados, por instrumento/timeframe/open time, armazenados em UTC epoch ms e DECIMAL.
- V1 mantida inalterada, V2 acrescenta `number_of_trades` e `taker_buy_quote_volume`.
- `ddl-auto=validate`, sem alteracoes automaticas do Hibernate.

## Como funcionam os pacotes

- `integration/binance`: conversa com as APIs externas.
- `market/model`: representa os dados do mercado.
- `market/service`: realiza importacao, consulta e gravacao dos candles.
- `market/repository`: consultas ao banco pelo Spring Data JPA.
- `market/api`: endpoints locais de consulta e sincronizacao.
- `config`: parametros, ambientes e protecoes de execucao.

## Limites e verificacao

- **Nao existe suporte a ordens** (nem Testnet), GPT, Brooks, Coulling ou indicadores de trading nesta etapa.
- Nao trata WebSocket user data stream, book de ofertas, Open Interest ou funding ainda.
- WebSocket public streaming possui limites de conexoes e rotacao; o cliente reconecta e sincroniza por REST, mas uma pane prolongada pode exigir bootstrap manual.
- A rede e as APIs da Binance nao foram acessadas do ambiente de autoria. Os testes estao no codigo, mas requerem execucao local com Maven/JDK25. Execute `mvn clean test` e `mvn spring-boot:run` antes de considerar a etapa validada na sua maquina.

Documentacao Binance: https://developers.binance.com/en/docs/products/derivatives-trading-usds-futures/general-info

## Etapa 3: Price Action (analise de candles fechados)

O novo modulo `analysis/brooks` produz um snapshot de contexto, **sem enviar ordens**.
Depois de sincronizar candles usando os endpoints da Etapa 2:

```powershell
Invoke-RestMethod 'http://127.0.0.1:8080/api/v1/analysis/price-action?symbol=BTCUSDT&interval=5m&limit=150'
```

Se nao houver pelo menos 20 candles consecutivos fechados (ou o minimo configurado),
a analise responde com HTTP 422, em vez de supor uma tendencia. O endpoint continua
restrito ao endereco local do servidor.

As classificacoes, parametros, diferenca entre conceitos de Brooks e heuristicas do
algoritmo e cuidados contra **lookahead bias** estao em [`docs/PRICE_ACTION_RULES.md`](docs/PRICE_ACTION_RULES.md).

**As regras numericas ainda sao prototipos testaveis**, nao um sistema comprovadamente
lucrativo. A etapa de Volume Price Analysis (Anna Coulling) sera feita separadamente.

## Etapa 4 — Volume Price Analysis (Coulling)

O endpoint descritivo de volume pode ser consultado apos a coleta de candles:

```http
GET /api/v1/analysis/volume?symbol=BTCUSDT&interval=5m&limit=150
```

Exige ao menos `VOLUME_LOOKBACK + 1` candles consecutivos e fechados. A media de referencia usa somente candles anteriores (sem lookahead). O resultado nao constitui sinal para abrir ordens. Os parametros estao em `application.yml` e a metodologia em [`docs/VOLUME_PRICE_ANALYSIS_RULES.md`](docs/VOLUME_PRICE_ANALYSIS_RULES.md).

## Etapa 5 — Suportes / resistencias historicos e Fibonacci

Depois de coletar candles consecutivos, consulte:

```powershell
Invoke-RestMethod 'http://127.0.0.1:8080/api/v1/analysis/market-levels?symbol=BTCUSDT&interval=5m&limit=200'
```

Esse endpoint retorna zonas candidatas construidas com pivots locais **confirmados**
pelo analisador Price Action e niveis de retracao/extensao do ultimo swing
confirmado de topos/fundos opostos. O resultado inclui ATR simples, tolerancia
da zona, numero de pivots agrupados e horarios de confirmacao. Nao executa ordens.

Para um contexto de mais dias, primeiro colete candles `1h` e consulte
`interval=1h&limit=500`. A API aceita no maximo 500 candles por consulta.

Metodologia, limites e parametros configuraveis:
[`docs/MARKET_LEVELS_RULES.md`](docs/MARKET_LEVELS_RULES.md).

**Observacao:** a Testnet serve para validar o processamento, nao para
estimar desempenho de estrategias sobre o mercado real.
