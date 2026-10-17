# Estudo de selecao de entradas: fim de semana UTC + distancia do stop

**PAPER ONLY / sem ordens reais / PR draft / sem merge automatico**.

## Pergunta da pesquisa

Nos 450 dias de BTCUSDT (2025-07-23 ate 2026-10-15), uma selecao
RETROSPECTIVA de trades identificou perdas entre entradas de final de semana
e stops estruturais distantes do preco de sinal. Remover trades ruins
do CSV **nao equivale** a refazer o backtest: a carteira muda de estado,
passa a ter oportunidades diferentes, e as taxas e o risco se alteram.
Este experimento testa essa hipotese com simulacao sequencial completa,
em contas isoladas, e NAO garante a vantagem sugerida pelos CSVs.

**Nenhuma alteracao** no detector Price Action + Volume, nas EMAs,
nas entradas MIDAS, no stop estrutural, no trailing, na alavancagem,
na gestao diaria ou na politica de saida selecionada `DYNAMIC_TREND_60`.

## Entradas e gestao congeladas

- BTCUSDT USD-M Futures **TESTNET para metadados/historico persistido**;
  somente replay paper, sem colocar ordens.
- 15m fechado, direcao 1d EMA21 inteiramente fechada,
  pullback misto LONG original; pullback misto SHORT exige MIDAS
  ancorado no topo confirmado com reteste de faixa da curva.
- `DYNAMIC_TREND_60` em todas as contas: a partir do candle 30
  manter somente enquanto direcao diaria EMA21 fechada, alinhamento
  EMA9/EMA21 15m e pivô confirmado preservarem a tendencia; saida
  proximo open se perder a condicao, teto absoluto 60 candles.
- Stop inicial estrutural original e trailing por pivô (STOP-FIRST),
  stop diario 3%, risco 0,5% por trade, capital US$600 POR livro/janela,
  alavancagem 1x, taxa 0,05% por lado, slippage 0,02% por lado,
  spread 0,01%, funding hipotetico 0.
- 12 janelas independentes de 900h, 3600 candles 15m/janela,
  1000 candles historicos adicionais para MIDAS, sem reescrita
  de ancoragem retroativa para decisoes anteriores.
- 2 direcoes × 6 politicas × 12 janelas = **144 carteiras paper**,
  com capital reiniciado por livro/janela (nao compor os P&Ls
  como se fosse uma conta unica).

## Politicas avaliadas (ambas as direcoes)

| Politica | Dia UTC | Distancia inicial do stop |
|---|---|---|
| `BASELINE` | Sem filtro | Sem novo limite |
| `WEEKDAYS_ONLY` | Segunda a sexta | Sem novo limite |
| `STOP_045_ONLY` | Todos | Ate 0,45% |
| `COMBINED_045` | Segunda a sexta | Ate 0,45% |
| `STOP_050_ONLY` | Todos | Ate 0,50% (sensibilidade) |
| `COMBINED_050` | Segunda a sexta | Ate 0,50% (sensibilidade) |

**Definicao temporal precisa:** `WEEKDAYS_ONLY` verifica a data
UTC da **proxima abertura de candle de 15m**, instante em que
o simulador executaria a entrada; nao usa o dia do open do candle
que gerou o sinal nem o horario local do usuario. Um sinal confirmado
no domingo 23:59:59.999 UTC pode abrir segunda 00:00 UTC.

**Definicao da distancia:**

```text
distanciaPct = ABS(precoReferenciaSinal - stopEstruturalOriginal)
               / precoReferenciaSinal * 100
```

Usamos APENAS o preco de referencia do fechamento do sinal confirmado
e o stop estrutural original desse mesmo sinal; ambos existem antes
do fill. Nao utilizamos preco do proximo open, futura MFE, P&L ou
qualquer maxima/minima de candles futuros. Limite inclusivo: 0,45%
exato passa em `STOP_045_ONLY`; 0,46% falha nele e passa em
`STOP_050_ONLY`. Dados de distancia ausentes ou stop do lado
errado recusam somente as politicas de distancia, sem modificar
a carteira controle.

**Ordem dos filtros:** detector Price Action + Volume → filtro 1d
fechado → LONG original/SHORT MIDAS retest → politica de selecao
independente → simulacao na proxima abertura, com comissoes e custos.
Cada politica recebe sinal e estado de conta proprios. Ao recusar,
gera os motivos `ENTRY_UTC_WEEKEND`,
`ENTRY_STOP_DISTANCE_GT_045_PCT`,
`ENTRY_STOP_DISTANCE_GT_050_PCT` ou
`ENTRY_INVALID_STOP_DISTANCE` na contagem de rejeicoes. Politica
combinada registra TODOS os motivos aplicaveis, inclusive sobreposicoes,
sem contagem duplicada dentro da mesma conta/sinal.

## API e arquivos

- `GET /api/v1/research/independent-setups/entry-selection/status`
  confirma 6 politicas, 12×900h, BTC, US$600, MIDAS hibrido,
  `DYNAMIC_TREND_60`, stop original, UTC e papel `PAPER_ONLY`.
- `POST /api/v1/research/independent-setups/backtest-entry-selection`
  recebe `IndependentResearchRequest` igual ao backtest dinamico;
  exige BTCUSDT, 15m/1d, `steps=3600`, `contextCandles=60`,
  `capitalUsdt=600`, `maxHoldingCandles=30`
  (gatilho da saida dinamica).
- `scripts/config/btc-15m-mixed-entry-selection-12x900h.json`
  congela riscos, taxas e parametros antigos, acrescenta apenas
  `entrySelectionPolicies`, `weekendBasis`,
  `stopDistanceDefinition` e `selectedHoldingPolicy`.
- `scripts/run-btc-15m-mixed-entry-selection.ps1` exporta seis
  arquivos em `results/`: `windows`, `aggregate`, `trades`,
  `diagnostics`, `rejections`, `config`, com prefixo
  `BTC-mixed-MIDAS-ENTRY-SELECTION-INSAMPLE`.
  `EntrySelectionPolicy` e `HoldingPolicy` sao identificadas
  nos CSVs. `diagnostics` registra referencia, stop, distancia,
  dia UTC, aceite de cada politica e motivos combinados.

## Executar

~~~powershell
cd C:\Projetos\scalper-bot
git fetch origin
git switch feat/independent-trend-pullback-range-breakout-5m
git pull --ff-only

# Terminal 1: REINICIAR backend paper, ja com BTC 15m/1d no MySQL
.\scripts\start-backtest.ps1 -IncludeDailyWeekly -Symbols BTCUSDT -CapitalUsdt 600

# Terminal 2: 144 livros paper em 12 janelas
.\scripts\run-btc-15m-mixed-entry-selection.ps1 -AsOfDateUtc 2026-10-15 -PrintOnly

# Execucao completa historica no banco local
.\scripts\run-btc-15m-mixed-entry-selection.ps1 -AsOfDateUtc 2026-10-15
~~~

Se faltar historico, `-SyncHistory` tenta complementar via TESTNET,
sem usar ordens reais nem fabricar candles. A Testnet pode nao
reter periodos antigos. O runner interrompe no erro de continuidade.

## Controle de validade

**Antes de comparar lucro**, verificar se `BASELINE` reproduziu
exatamente as carteiras LONG e SHORT `DYNAMIC_TREND_60` do
experimento anterior: trades, horario, stop, fee, motivo, NetR e
resultado por janela. Uma divergencia invalida o teste.

Comparar lucro LIQUIDO, numero de oportunidades, fees, profit factor,
drawdown por janela, aporte e resultado dos primeiros 300 vs ultimos
150 dias; medir concentracao das duas maiores vencedoras.
Se houver ganho apenas no historico usado para formular a hipotese,
ele podera ser sobreajuste. Repetir com parametros congelados em
historico nao usado no desenvolvimento (p.ex. 2020-2024 se
disponivel com dados completos) e, depois, dados prospectivos.
