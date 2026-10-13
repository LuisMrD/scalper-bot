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

## Auditoria das entradas substitutas

O comparador agora exporta tambem o snapshot `entryFeatures` do momento da entrada
para cada trade encerrado: tendencia primaria e 15m, volume relativo, agressao,
estado do rompimento, candle de sinal, stop inicial e custo estimado em relacao ao risco.

Ao executar `scripts/compare-entry-filters.ps1`, depois dos quatro replays, o
analisador offline `scripts/audit-replacement-entries.ps1` identifica tres grupos
usando a chave deterministica `(Window, Side, EnteredAtOpenTime)`:

- `REMOVED`: estava na carteira original (`NONE`), nao na `BLOCK_BOTH`.
- `PRESERVED`: a mesma entrada ocorreu em ambos os replays (pode ter PnL diferente).
- `NEW`: aparece no `BLOCK_BOTH`, mas nao no controle original.

A saida usa tres novos CSVs: `entry-filter-replacement-trades-*.csv`
(com snapshots e rotulos), `entry-filter-replacement-windows-*.csv`
(reconciliacao das entradas/pnl por janela), e `entry-filter-replacement-patterns-*.csv`
(distribuicoes de ganhos/perdas por caracteristica, separadas por grupo).

O validador exige, para cada janela:

`deltaPnL = PnL(NEW) - PnL(REMOVED) + deltaPnL(PRESERVED)`

e confronta com a diferenca entre PnL de trades encerrados de `BLOCK_BOTH`
e `NONE`. Isso NAO reconcilia automaticamente PnL marcado de posicoes ainda
abertas; o analista deve olhar tambem as colunas de equity marcada dos CSVs
de janelas. A lista de entradas refere-se somente a operacoes encerradas.

Pode reprocessar os CSVs offline sem reexecutar os backtests:

`./scripts/audit-replacement-entries.ps1 -Symbol SOLUSDT -Windows 9 -AsOf 1791845999999`

`EntryBreakoutState`, Price Action, agressao, volume, stop e custo sao atributos
conhecidos quando a entrada foi decidida ou preenchida. `Outcome`, `DurationCandles`,
`MfeR`, `MaeR`, `NetRMultiple` e `ExitReason` sao valores
do futuro e **jamais devem ser usados para decidir a entrada**.

Bandas de volume e custo sao descritivas e nao filtros aprovados. Grupos com menos
de cinco trades sao identificados como amostras pequenas. Nenhum filtro
adicional deve ser introduzido sem nova validacao historica fora da amostra.
