# Diagnostico economico por trade: 1m versus 5m (RETEST)

## Branch de pesquisa

`experiment/1m-vs-5m-trade-diagnostics` parte da
`experiment/5m-primary-300h`. Nenhuma mudanca no motor de ordem
ou nas regras de entrada/stop: somente os scripts de pesquisa e testes.
A branch `develop` nao e modificada.

Objetivo: responder por que RETEST no 5m ficou muito menos negativo
que no 1m, sobre 3 janelas iguais de 300 horas do SOLUSDT, sem inventar
causalidade a partir do P&L agregado.

## Execucao no Windows PowerShell 5.1

Antes do teste, conferir que o Spring Boot esta ativo com **US$ 1500**
de capital, risco 0,5% por trade, taxas 0,05%/lado, slippage 0,02%/lado,
`JAVA_DECISION_REQUIRE_HIGHER_TF=false` e ordens desligadas.

```powershell
git fetch origin
git switch --track origin/experiment/1m-vs-5m-trade-diagnostics
mvn spring-boot:run
```

Em outro terminal:

```powershell
.\scripts\compare-timeframe-trades.ps1 -PrintOnly

.\scripts\compare-timeframe-trades.ps1 `
    -Symbol SOLUSDT `
    -Setup RETEST `
    -Source testnet `
    -Windows 3 `
    -AsOf 1791845999999 `
    -ExpectedCapitalUsdt 1500
```

**Nao use `-AnalyzeExisting` na primeira execucao.** Os CSVs anteriores
`setup-windows` e `setup-summary` NAO incluem o historico individual
dos trades. O teste precisara reexecutar 6 backtests
(3 x 1m/18000 candles, 3 x 5m/3600 candles), todos sobre as mesmas
3 janelas de 300h. Nao executa ordens nem chama LLM.

Se faltar historico 1m/5m/15m, acrescentar `-SyncHistory`.
Para base separada com OHLCV publicos de producao, so com perfil
research e `-Source production -ConfirmIsolatedDatabase`.
Nao misture dados testnet e production no mesmo banco.

## Resultados gerados

- `results/setup-trades-SOLUSDT-1791845999999.csv` — trades 1m
- `results/setup-trades-SOLUSDT-5m-1791845999999.csv` — trades 5m
- `results/timeframe-trade-metrics-SOLUSDT-RETEST-1791845999999.csv`
  — expectativa, WinRate, ganho/perda medio, PF, P&L, fees,
  slippage, impacto das 3 melhores vencedoras. Inclui segmentos:
  ALL, SIDE (LONG/SHORT), 15M_REGIME, HTF_ALIGNMENT, EXIT_REASON e WINDOW.
- `results/timeframe-matched-windows-SOLUSDT-RETEST-1791845999999.csv`
  — retorno, drawdown, P&L fechado e aberto em cada janela nos dois timeframes.
- Os `setup-windows`/`setup-summary` normais tambem sao regenerados
  com `StartingEquityUsdt` e os filtros explicitamente pedidos.

As colunas `NetPnlUsdt` ja incluem fee e slippage. `FeesUsdt`
e `SlippageUsdt` sao decomposicao **diagnostica**; nao subtraia
esses custos de novo. O `ProfitFactor` usa a soma dos ganhos
LIQUIDOS dividida pelo valor absoluto das perdas LIQUIDAS.
`ExpectancyNetUsdt` e a media de NetPnlUsdt por trade fechado.
`NetWithoutTop3WinsUsdt` retira as 3 maiores vencedoras sem
reprocessar o mercado; **nao e um contrafactual executavel**,
pois remover uma operacao altera os estados da carteira.

Sinais e tendencias em `setup-trades` foram capturados no fechamento
do candle que autorizou a entrada, ANTES do fill da barra seguinte,
sem olhar o resultado futuro. `Trend15m` significa o ultimo
15m fechado ate o instante do sinal. O campo `HtfConflicts` indica
discordancias com o(s) timeframe(s) superior(es), sem impor bloqueio
quando `requireHigherTimeframeAlignment=false`.

Trades ainda ABERTOS nao sao tratados como ganhos/perdas realizados
nem entram em WinRate/ExpectancyNetUsdt. Seu P&L estimado aparece
separadamente em `timeframe-matched-windows`, assim como o
`totalReturnPct` marcado a mercado.

## Analise sem reexecutar backtests

Se os arquivos `setup-trades` e `setup-windows` do teste acima
continuarem presentes e com os mesmos filtros/configuracao:

```powershell
.\scripts\compare-timeframe-trades.ps1 `
    -AnalyzeExisting `
    -Symbol SOLUSDT `
    -Setup RETEST `
    -AsOf 1791845999999
```

`-AnalyzeExisting` valida os CSVs existentes, mas **nao pode atestar**
que o ambiente que os gerou tinha uma dada taxa/slippage, nem
o algoritmo/commit correspondente. Preserve os CSVs e o commit usados
para reproducibilidade. Para novos parametros, reexecute o backtest.

## Cuidados na interpretacao

O 1m e 5m compartilham o mesmo periodo UTC, nao os mesmos sinais
individuais. Alem de entradas, o 5m altera stops, trajetoria intrabar
(OHLC), trailing e o alcance temporal das analises. O 5m tem menor
resolucao para avaliar stops: o replay atual aplica uma hipotese
conservadora `stop-first`, sem conhecer a sequencia intrabar.
Dados de Testnet e 3 janelas tambem nao provam rentabilidade futura.

Investigue primeiro segmentos suficientes para inferir tendencias,
e depois valide hipotese em um periodo ainda nao usado para escolher
o setup ou timeframe (out of sample). Nenhum resultado autoriza operar
dinheiro real.
