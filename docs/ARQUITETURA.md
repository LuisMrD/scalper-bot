# Arquitetura — Etapa 2

## Escopo

Exclusivo Binance Futures USDⓈ-M, multi-par (BTCUSDT inicialmente).

## Componentes efetivamente implementados

- `config`: perfis, configuracoes e protecao de execucao (ordens sempre bloqueadas).
- `system`: endpoint de status sem credenciais.
- `integration/binance`: client REST publico, decoder de klines, validacao `exchangeInfo` e WS publico opcional.
- `market/model`: `CandleInterval`, `MarketCandle`, entidades JPA de instrumentos/candles.
- `market/repository`: repositorios JPA de consulta.
- `market/service`: paginacao/ingestao REST, UPSERT MySQL, bootstrap e reconciliacao.
- `market/api`: API local para consulta/sincronizacao de candles fechados.
- `db/migration`: V1 original preservada; V2 complementa trades e volume taker buy.

## Componentes planejados e NAO implementados

1. Analisadores: Brooks, Coulling, Fibonacci e historico.
2. MarketContextBuilder e eventos de oportunidades.
3. JavaDecisionEngine e LunaDecisionEngine.
4. Estrategias de entrada: pullback e rompimento com reteste.
5. Risco, stops, trailing e portfolios simulados independentes.
6. Backtesting financeiro e paper trading.
7. Executor real de ordens Testnet, separado dos simuladores.
8. Comparacao financeira dos motores apos todos os custos.

## Invariantes

- Persistir somente **candles fechados** (REST verifica close-time; WS exige `x=true`).
- Nunca deduzir dados futuros de candles ainda abertos.
- UNIQUE `(instrument_id,timeframe,open_time_epoch_ms)` e UPSERT para tornar reimports idempotentes.
- Times Binance como epoch ms UTC, OHLCV em `BigDecimal`.
- Sem valores financeiros default ou permissoes de ordens ate desenvolvimento do modulo de risco.
- Nunca misturar historicos de Testnet e producao dentro do mesmo banco, pois nao ha coluna de origem na chave primaria.
- Reconciliação REST compensa falhas WS dentro da janela configurada.
- API da aplicacao apenas em 127.0.0.1.
