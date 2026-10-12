# Configuracao local reprodutivel — Backtest BTCUSDT, ETHUSDT e SOLUSDT

Este roteiro fixa **as hipoteses do experimento**, nao os parametros ideais do mercado.
O inicializador configura `TRADING_SYMBOLS=BTCUSDT,ETHUSDT,SOLUSDT` para esta sessao.
O `application.yml` BASE continua com capital, risco por operacao, stop diario,
alavancagem, taxas e slippage **sem defaults financeiros**. O filtro de custo/risco
e o alinhamento estrito permanecem, respectivamente, desligado e ligado por padrao
fora do script.

## Executar no Windows PowerShell

Abra um terminal na **raiz do repositorio**, com JDK 25, Maven e Docker Desktop:

```powershell
git switch develop
git pull origin develop
docker compose up -d mysql
.\scripts\start-backtest.ps1
```

O script configura no processo do PowerShell e inicia Spring Boot em
`SPRING_PROFILES_ACTIVE=backtest`. Nenhuma ordem e enviada a Binance. O script
desabilita chamadas Luna, websocket e bootstrap **automatico**. Se voce precisar
importar candles, use o endpoint manual de bootstrap ou sync em outro terminal.

### Parametros deliberadamente fixados no script

| Variavel | Valor | Finalidade |
| --- | --- | --- |
| `TRADING_CAPITAL_USDT` | `300` | Capital de referencia da simulacao |
| `TRADING_RISK_PER_TRADE_PCT` | `0.5` | Risco estimado por entrada |
| `TRADING_DAILY_STOP_PCT` | `3` | Cota de perda diaria simulada |
| `TRADING_LEVERAGE` | `1` | Limite nocional 1x (pode mudar P&L vs testes anteriores) |
| `RISK_FEE_PER_SIDE_PCT` | `0.05` | Hipotese taker por lado |
| `RISK_SLIPPAGE_PER_SIDE_PCT` | `0.02` | Hipotese de slippage por lado |
| `RISK_BREAK_EVEN_TRIGGER_R` | `1.0` | Gatilho de break-even |
| `JAVA_DECISION_REQUIRE_HIGHER_TF` | `false` | Experimento sem alinhamento obrigatorio entre 1m/5m/15m |
| `RISK_MAX_COST_TO_INITIAL_RISK_PCT` | `30` | Filtro experimental ativo nessa sessao |
| `LUNA_ENABLED` | `false` | Sem chamadas pagas OpenAI |
| `TRADING_ORDERS_ENABLED` | `false` | Ordens reais bloqueadas |

O script aceita `-Symbols BTCUSDT,ETHUSDT,SOLUSDT`, `-CapitalUsdt`, `-RiskPerTradePct`, `-DailyStopPct`,
`-Leverage`, `-FeePerSidePct`, `-SlippagePerSidePct`,
`-BreakEvenTriggerR`, `-RequireHigherTf true|false` e
`-MaxCostToInitialRiskPct 0..100` para experimentos locais.
`-PrintOnly` imprime os valores e **nao executa Maven**:

```powershell
.\scripts\start-backtest.ps1 -PrintOnly

# Comparacao: mesmo servidor, mesmo asOf, mudar apenas override no POST.
# maxCostToInitialRiskPctOverride=0 (filtro desligado)
# maxCostToInitialRiskPctOverride=30 (filtro ativado)
```

Para iniciar com o filtro desligado por variavel de ambiente:

```powershell
.\scripts\start-backtest.ps1 -MaxCostToInitialRiskPct 0
```

O valor `0` do script **remove a variavel de ambiente**; valores 0 em
`RISK_MAX_COST_TO_INITIAL_RISK_PCT` no YAML falhariam na validacao
(`@DecimalMin` estritamente positivo). No JSON do backtest o valor `0`
**pode** ser utilizado como override desligado para somente aquela solicitacao.

## Comparacoes consistentes

Para comparar dois backtests sem mudar o periodo, fixe
`asOf` com o mesmo timestamp de fechamento; mantenha `steps`,
`contextLimit`, timeframes, capital, alavancagem, fee, slippage e
`breakEvenTriggerROverride` iguais. Leia
[`BACKTEST_RULES.md`](BACKTEST_RULES.md) para o script comparativo 0 vs 30.

**Atenção:** a versao antiga dos testes poderia estar usando alavancagem
ou configuracoes locais diferentes. Estes valores nao reproduzirao
automaticamente a mesma serie de entradas/P&L se a configuracao anterior
nao for identica. A hipotese de 0.02% de slippage nao e uma cotacao de mercado.
Os testes com Binance Testnet nao representam execucao de ordens na producao.

## Testar BTCUSDT, ETHUSDT e SOLUSDT

**Importante sobre qualidade dos dados:** o repositorio usa a Binance Futures
**TESTNET**, nao dados historicos da producao. Mesmo que SOL apresente movimentos
maiores na Testnet, isso nao prova que a estrategia sera lucrativa no mercado real.
Nao misture Testnet e producao no mesmo banco MySQL, pois a chave dos candles nao
diferencia a origem.

No Windows, dentro da pasta do repositorio:

**Terminal 1 — servidor (deixe aberto):**

```powershell
docker compose up -d mysql
.\scripts\start-backtest.ps1
```

Em Cmder/CMD use:

```bat
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-backtest.ps1
```

**Terminal 2 — carregar candles da Testnet:**

```powershell
.\scripts\sync-backtest-data.ps1
```

O script chama `POST /api/v1/market-data/bootstrap` sob demanda. Ele carrega
por padrao sete dias para **cada par e timeframe 1m, 5m e 15m**;
a sincronizacao pode demorar alguns minutos. Nao chama Luna e nao executa ordens.
O bootstrap automatico continua desligado. O endpoint usa os URLs de Testnet
configurados na aplicacao.

**Terminal 2 — executar comparacao por par (15 backtests Java):**

```powershell
.\scripts\compare-pair-backtests.ps1
```

Por padrao executa os limites `0,30,50,75,100` (em porcentagem) para os
tres pares com `steps=3000`, `contextLimit=200`, `breakEvenTriggerROverride=1.0`.
Cada par obtem seus proprios `priceTick`, `quantityStep`,
`minimumQuantity` e `minimumNotionalUsdt` de
`https://demo-fapi.binance.com/fapi/v1/exchangeInfo`. Caso a Testnet nao
publique algum filtro necessario, o comparador falha explicitamente em vez de
assumir valores de BTC. Certifique-se de que o app e a fonte exchangeInfo
apontam ambos para a **mesma Testnet**.

O comparador determina o `asOf` a partir do mais antigo entre os fechamentos
do ultimo candle 1m armazenado para cada par, garantindo corte temporal comum;
se houver falta de historico continuo no MySQL, a requisicao retorna HTTP 422.
Um horario igual nao significa o mesmo numero de trades, nem garante
condicoes de liquidez equivalentes.

Os dados sao apresentados na tela e salvos como
`results/pair-comparison-<asOf>.csv` (pasta ignorada pelo Git).
Linhas falhadas sao marcadas como `ERROR`, nunca como rentabilidade zero.

**Somente SOLUSDT, limites 0, 30 e 100:**

```powershell
.\scripts\compare-pair-backtests.ps1 -Symbols SOLUSDT -CostLimits 0,30,100
```

**Somente inspecionar opcoes, sem rede nem execucao:**

```powershell
.\scripts\compare-pair-backtests.ps1 -PrintOnly
```

**Repetir uma data historica fixa:**

```powershell
.\scripts\compare-pair-backtests.ps1 -AsOf 1791823439999
```

Substitua o timestamp acima por um fechamento **efetivamente armazenado**
na base, em milissegundos UTC. Para comparar com resultados anteriores,
verifique tambem que capital, leverage, fee, slippage e configuracoes de
tendencia sao identicos. A selecao automatica `asOf` usa o menor dos
ultimos fechamentos, nao tenta preencher lacunas.

Nao e possivel extrapolar a rentabilidade real sem dados de producao,
custo/liqidez reais, varias janelas e periodo fora da amostra.
