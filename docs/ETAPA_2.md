# Etapa 2 — Market Data

## Objetivo

Concluir ingestao de dados OHLCV Binance USD-M Futures via REST e WebSocket,
com historico inicial, multiplos pares, multiplos timeframes e persistencia idempotente.

## Classes principais

- `config/MarketDataProperties`: configuracoes validadas do coletor.
- `market/model/CandleInterval`: duracoes dos timeframes.
- `market/model/MarketCandle`: snapshot valido de mercado.
- `integration/binance/BinancePublicClient`: endpoints REST publicos.
- `integration/binance/BinanceExchangeInfo`: metadados oficiais dos pares.
- `integration/binance/BinanceKlineDecoder`: payloads REST + WS.
- `integration/binance/BinanceKlineWebSocket`: conexao opcional em tempo real e reconexao.
- `market/service/MarketDataService`: sincronizacao, leitura, reparo historico.
- `market/service/CandleStorageService`: UPSERT SQL atomicamente.
- `market/service/MarketDataScheduler`: bootstrap e reconciliacao periodica.
- `market/api/MarketDataController`: endpoints de leitura/sincronizacao.
- `db/migration/V2__add_futures_kline_metrics.sql`: campos complementares.

## Verificacao local

1. `docker compose up -d mysql`
2. `mvn clean test`
3. `mvn spring-boot:run`
4. GET `/actuator/health` e `/api/v1/market-data/settings`
5. POST `/api/v1/market-data/bootstrap`
6. GET `/api/v1/market-data/candles?symbol=BTCUSDT&interval=1m&limit=5`
7. Repetir o bootstrap: nao deve duplicar linhas no MySQL.
8. Habilitar `MARKET_DATA_WS_ENABLED=true`, reiniciar e verificar candles fechados.
9. Desligar a rede temporariamente e observar reconexao e reconciliacao REST.
10. Confirmar que `TRADING_ORDERS_ENABLED=true` impede inicializacao.

Sem confirmacao de testes de integracao no ambiente de autoria. Nenhuma ordem e criada.
