# Experimento RETEST 5m: filtros de entrada por BreakoutState

Escopo: **pesquisa local na Binance Futures Testnet**, exclusivamente motor Java.
Nenhuma ordem real, chamada Luna, alteracao de configuracao global ou migracao de banco.

## Hipoteses pre-registradas

- `BLOCK_SHORT_DOWN_CANDIDATE`: rejeita sinal RETEST SHORT quando o estado atual do analisador Brooks for `DOWN_CANDIDATE`.
- `BLOCK_LONG_NONE`: rejeita sinal RETEST LONG quando `BreakoutState=NONE`.
- `BLOCK_BOTH`: combina ambos.
- `NONE`: controle imutavel; segue a logica anterior.

O filtro eh consultado **apenas quando JavaDecisionEngine sinaliza uma nova entrada BREAKOUT_RETEST**. Nenhuma posicao existente ou operacao Luna eh filtrada. O estado eh capturado do candle **fechado** que gerou o sinal, nunca de barras futuras. Sinais bloqueados viram WAIT com motivo `ENTRY_BREAKOUT_FILTER`.

## Comparacao dinamica

`POST /api/v1/backtest/compare-entry-filters` recebe o JSON ja utilizado por `/api/v1/backtest/run`, com `intervals=["5m","15m"]`, `includeLuna=false`, `pullbackEnabledOverride=false` e `breakoutRetestEnabledOverride=true`.

Quatro carteiras completamente independentes sao simuladas. Cada entrada recusada altera a disponibilidade da carteira para entradas seguintes; stops, margens, custos e drawdown sao calculados novamente. Nenhum resultado eh estimado por subtracao de trades antigos.

A resposta inclui quatro resumos de cenario, quantidade de sinais recusados e as operacoes reais de cada replay. A soma dos `netPnlUsdt` das operacoes encerradas reconcilia com `netClosedUsdt` do mesmo cenario. O resultado marcado inclui posicoes abertas. O numero de sinais recusados nao equivale ao numero de trades que desaparecem (podem surgir entradas diferentes).

## Executar

1. Fazer pull da branch `experiment/1m-vs-5m-trade-diagnostics` e reiniciar Spring Boot apos compilacao.
2. Garantir a existencia de `results/entry-signal-windows-SOLUSDT-RETEST-1791845999999-5m-w1-9.csv`, proveniente da auditoria anterior.
3. Visualizar o plano: `./scripts/compare-entry-filters.ps1 -PrintOnly`.
4. Executar `./scripts/compare-entry-filters.ps1 -Symbol SOLUSDT -Windows 9 -AsOf 1791845999999 -ExpectedCapitalUsdt 1500`.

Saidas: `entry-filter-aggregate-*.csv`, `entry-filter-windows-*.csv`, `entry-filter-trades-*.csv`. Resultados das nove janelas **nao constituem validacao fora da amostra**: esses mesmos dados revelaram os padroes usados para criar os filtros. Apos estudar as diferencas no replay dinamico, reservar datas novas sem ajustar os filtros a elas.

Metricas prioritarias: PnL marcado e fechado apos taxas, drawdown, quantas janelas melhoraram, quantidade de sinais rejeitados, quantas operacoes novas surgiram e maiores vencedoras perdidas.
