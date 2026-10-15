# Experimento: entradas e gestao no timeframe 5m (sem 1m)

Branch: `experiment/5m-primary-300h` (isolada; nao alterar develop).

## Motivo e funcionamento

O `BacktestService` ja seleciona o **primeiro intervalo** de `BacktestRequest.intervals` como fonte de candles para o replay, entrada, avaliacao de stop e saida. O `JavaDecisionEngine` tambem trata o primeiro contexto como *timeframe primario*. Por isso nao alteramos o algoritmo Java: a alteracao fica somente no orquestrador `scripts/compare-setup-windows.ps1`.

- Baseline: `intervals=['1m','5m','15m']`, `steps=18000`.
- Experimento: `intervals=['5m','15m']`, `steps=3600`. **Nao carrega o 1m**.
- Os dois cobrem **300 horas de mercado por janela**, 3 janelas contiguas no tempo e 3 estrategias (BOTH/PULLBACK/RETEST) em cada janela.
- `requireHigherTimeframeAlignment=false` segue sendo lido do ambiente, portanto 15m apenas fornece contexto diagnostico e nao veta tendencia do 5m. `true` exige alinhamento 5m/15m e deve ser considerado outro experimento.
- `contextLimit=200` agora significa 200 candles em 5m (e 200 em 15m), nao 200 candles em 1m. A janela de analise tecnica e, por isso, temporalmente maior.
- Swing, pivots, Fibonacci, volume/VPA, ATR, filtros de risco, break-even e trailing utilizam o **5m** como timeframe primario. Entradas decididas no fechamento do 5m executam na abertura do proximo 5m; nenhuma decisão vem de um candle de 1m.

## Execucao

Atualize o branch e inicie o Spring Boot com a mesma banca e parametros dos experimentos anteriores:

```powershell
git fetch origin
git switch experiment/5m-primary-300h
mvn spring-boot:run
```

Em outro PowerShell, confirme o planejamento:

```powershell
.\scripts\compare-setup-windows.ps1 -PrimaryInterval 5m -PrintOnly
```

Resultado esperado: `PRIMARY_INTERVAL=5m`, `INTERVALS=5m,15m`, `STEPS=3600`, `HOURS_PER_WINDOW=300`.

Rode a bateria com fim **alinhado ao fechamento de 5m**:

```powershell
.\scripts\compare-setup-windows.ps1 `
    -PrimaryInterval 5m `
    -Source testnet `
    -Windows 3 `
    -Steps 3600 `
    -AsOf 1791845999999 `
    -SyncHistory
```

O `asOf` antigo `1791846239999` corresponde ao fechamento de 1m
(23:03:59.999 UTC), **nao** a um fechamento de 5m. O ultimo 5m fechado
antes dele foi `1791845999999` (22:59:59.999 UTC). O comparador rejeita
timestamp desalinhado em vez de usar, sem avisar, candles diferentes.

Se o banco ja possuir historico completo de 5m e 15m, remova
`-SyncHistory`. Se faltarem barras ou warm-up, o backtest falha.

Os CSVs usam nomes distintos dos resultados originais:

- `results/setup-windows-SOLUSDT-5m-1791845999999.csv`
- `results/setup-summary-SOLUSDT-5m-1791845999999.csv`

## Baseline comparavel em 1m

Para confrontar os resultados, use exatamente o mesmo periodo UTC
(alinhado ao final do 5m), banca e parametros de risco, executando
separadamente o baseline no **mesmo branch**, sem reiniciar o servidor:

```powershell
.\scripts\compare-setup-windows.ps1 `
    -PrimaryInterval 1m `
    -Source testnet `
    -Windows 3 `
    -Steps 18000 `
    -AsOf 1791845999999
```

Os dados de 1m devem estar previamente sincronizados (use
`-SyncHistory` se necessario). O baseline gera os CSVs antigos
`setup-windows-SOLUSDT-1791845999999.csv` e
`setup-summary-SOLUSDT-1791845999999.csv`.

## Limitacoes do comparativo

A experiencia 5m muda **mais do que a frequencia das entradas**:
janelas de indicadores, confirmacao de pivots, execucao dos stops,
timing de reversao e trailing tambem operam em barras 5m.
Um candle 5m contem movimentos internos que OHLC nao sequencia;
o simulador preserva a politica de stop-first, mas ha **menor
precisao de execucao intrabar** do que no 1m. Nunca assumir que
uma simulacao de 5m produz automaticamente melhores fills.
Um teste que mude apenas o timeframe do sinal, mantendo
fills/stops em 1m, exigiria um mecanismo de replay distinto.

Os resultados sao simulados, sem ordens reais nem chamadas de LLM.
O script herda capital, alavancagem, custos e parametros tecnicos
do ambiente Spring Boot. Fixe `JAVA_DECISION_REQUIRE_HIGHER_TF=false`
para comparabilidade com os ultimos backtests; se uma variavel de
ambiente estiver definida, ela prevalece sobre o YAML.
