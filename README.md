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

## Executar com os parametros locais recomendados para backtesting

As configuracoes financeiras globais de `application.yml` continuam **sem defaults**.
Para reproduzir o experimento de BTCUSDT/ETHUSDT/SOLUSDT com US$ 300, risco de 0.5%, stop diario
de 3%, alavancagem 1x, taxa 0.05% e slippage 0.02% por lado, alinhamento de
timeframes desligado e filtro de custo/risco 30%, use:

```powershell
docker compose up -d mysql
.\scripts\start-backtest.ps1
```

Luna, envio de ordens e coleta automatica de mercado permanecem desligados.
O perfil de execucao e `backtest`. Para verificar as variaveis sem iniciar Maven:

```powershell
.\scripts\start-backtest.ps1 -PrintOnly
```

**Comparacao multi-par (sem ordens):** apos iniciar a aplicacao, em outro PowerShell,
sincronize 7 dias de candles Testnet de BTC, ETH e SOL e rode a grade com
limites custo/risco 0%, 30%, 50%, 75%, 100%:

```powershell
.\scripts\sync-backtest-data.ps1
.\scripts\compare-pair-backtests.ps1

# Opcional: comparar apenas SOLUSDT nos limites 0,30,100
.\scripts\compare-pair-backtests.ps1 -Symbols SOLUSDT -CostLimits 0,30,100
```

O comparador busca os filtros por contrato em Binance USD-M Testnet
(`exchangeInfo`); nao reaproveita automaticamente os de BTCUSDT.
Os resultados aparecem no console e em `results/*.csv`, fora do versionamento.

Veja [configuracao local para backtest](docs/LOCAL_BACKTEST_CONFIGURATION.md)
e [como comparar 0 vs 30%](docs/BACKTEST_RULES.md).

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

## Etapa 6 — Market Context Builder (somente leitura)

Um contexto por timeframe, construido com uma unica consulta de candles e mesmo corte historico para as tres analises:

```powershell
Invoke-RestMethod 'http://127.0.0.1:8080/api/v1/analysis/market-context?symbol=BTCUSDT&interval=5m&limit=200'
Invoke-RestMethod 'http://127.0.0.1:8080/api/v1/analysis/market-context/multi?symbol=BTCUSDT&intervals=1m,5m,15m&limit=200'
```

O parametro opcional `asOf` limita todos os candles a `closeTime <= asOf` em UTC epoch milissegundos; o uso futuro de backtest depende da persistencia dos dados e de um motor de replay ainda nao implementado.

Os contratos e limitacoes estao documentados em [docs/MARKET_CONTEXT_RULES.md](docs/MARKET_CONTEXT_RULES.md).


## Etapa 7 — JavaDecisionEngine (classificacao hipotetica)

O novo motor Java usa o `MarketContextBuilder` existente e a **primeira** janela temporal
como timeframe de entrada. As demais sao filtros de tendencia, se configurado.
A mesma estrutura de contexto sera reutilizada no motor de IA na etapa seguinte.

```powershell
# Primeiro sincronize historico suficiente nos tres timeframes (ex.: bootstrap de 7 dias).
Invoke-RestMethod 'http://127.0.0.1:8080/api/v1/decision/java?symbol=BTCUSDT&intervals=1m,5m,15m&limit=200'

# Simule uma posicao ja existente SEM consultar ou alterar a Binance:
Invoke-RestMethod 'http://127.0.0.1:8080/api/v1/decision/java?symbol=BTCUSDT&intervals=5m,15m&limit=200&positionSide=LONG'
```

`entry` admite `LONG`, `SHORT` ou `WAIT`; para posicoes existentes,
`positionAction` admite `HOLD` ou `EXIT` e `entry` sera sempre `WAIT`.
`asOf` aceita timestamp UTC em milissegundos para experimentos historicos sem lookahead.
`reason` e `setup` explicam a regra acionada; nao ha campo de win rate nem
probabilidade de sucesso. Parametros ficam em `java-decision` no `application.yml`.

**Nao ha ordens, calculo de tamanho de posicao, alavancagem, stop-loss,
trailing stop ou integracao GPT nesta etapa.** Consulte
[`docs/JAVA_DECISION_RULES.md`](docs/JAVA_DECISION_RULES.md).


## Etapa 8 — LunaDecisionEngine (opcional)

A integracao com GPT-6 Luna via `POST /v1/responses` fica **desligada por padrao**.
Nao ha chamadas automáticas, scheduler de IA, envio de ordens ou acesso a contas.
Consulte `docs/LUNA_DECISION_RULES.md` para seguranca, configuracao, exemplos e limites.

- `LUNA_ENABLED=false`: `GET /api/v1/decision/luna/status` retorna `enabled=false`,
  e `POST /api/v1/decision/luna/evaluate` retorna HTTP 503 sem nem buscar candles.
- `LUNA_ENABLED=true`: requer `OPENAI_API_KEY` valido para chamadas pagas sob demanda.
- `POST /api/v1/decision/luna/evaluate?symbol=BTCUSDT&intervals=1m,5m,15m&limit=200`
  retorna um `DecisionResult` experimental, no mesmo contrato do motor Java.
- Faltando dados, falhando API, JSON ou validacao: nao ha decisao executavel.
- O endpoint `GET /api/v1/decision/luna/status` nunca expoe o valor da API key.

## Etapa 9 — RiskPreview (somente simulacao)

O calculador de risco aceita decisoes JA geradas pelo motor Java ou Luna, com a
mesma politica para ambos. Sem parametros financeiros obrigatorios, o endpoint
retorna 422 e nao faz qualquer chamada externa de IA nem envia ordens.
Ver [`docs/RISK_MANAGEMENT_RULES.md`](docs/RISK_MANAGEMENT_RULES.md) para exemplo.
`GET /api/v1/risk/status` verifica se pode executar uma previa.
`POST /api/v1/risk/preview` propoe quantidade/stop ou manutencao de stop.

## Etapa 10 — Backtest historico comparativo

`POST /api/v1/backtest/run` usa a mesma sequencia de candles fechados e os mesmos snapshots de mercado para avaliar Java e (opcionalmente) Luna, com carteiras **independentes**, entradas/saidas no proximo candle e taxas/slippage separados. Sem envio de ordens. Configuracao financeira permanece obrigatoria e nao recebe defaults. Detalhes e exemplo de requisicao em [`docs/BACKTEST_RULES.md`](docs/BACKTEST_RULES.md).
