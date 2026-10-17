# Novo estudo: recuo 15m com direção diária, 6 janelas × 600 horas

Esta é uma NOVA configuração de pesquisa. O experimento anterior de 1D/1W
continua disponível e inalterado; a família ativa no modo de pesquisa continua
exclusivamente os **quatro recuos**, sem consolidação/rompimento de caixa:

- `TREND_PULLBACK`: LONG, candles de recuo todos baixistas;
- `TREND_PULLBACK_MIXED`: LONG, candles mistos e deslocamento líquido baixista;
- `TREND_PULLBACK_SHORT`: SHORT, recuo comprador todo altista;
- `TREND_PULLBACK_MIXED_SHORT`: SHORT, candles mistos e deslocamento líquido altista.

## Timeframes e janelas

- **Entrada: 15m**, usando somente candles fechados da Binance Futures Testnet
  persistidos no MySQL, sem candle parcial.
- **Tendência maior: 1d**, inclinação da EMA21 calculada entre duas leituras
  com ao menos 22 candles **diários UTC completos e consecutivos**. Inclinação
  para cima permite somente LONG; para baixo, somente SHORT; plana bloqueia.
  O candle diário que ainda está sendo formado nunca pode ser consultado.
  Classificação Brooks RANGE não bloqueia se a direção da EMA diária confirma.
- **600 horas/janela = 25 dias corridos = 2.400 candles 15m**, excluindo apenas
  o último candle para novas confirmações que não possam ser executadas no
  open seguinte. Há seis blocos adjacentes e **não sobrepostos** de 600h, cobrindo
  3.600 horas (150 dias) no total.
- **3 símbolos** BTCUSDT, SOLUSDT, DOGEUSDT × 6 janelas × 4 braços = **72 contas
  paper independentes**. A execução financeira **reinicia o capital** ao mudar
  de janela, como no experimento 1d/1w original. Não confundir P&L somado de
  contas independentes com resultado de uma conta única/contínua.
- Para cutoff `2026-10-15T23:59:59.999Z`: primeiro candle de entrada
  abre em `2026-05-19T00:00:00.000Z` e fecha em
  `2026-05-19T00:14:59.999Z`. A última janela termina em
  `2026-10-15T23:59:59.999Z`.
- Há **60 candles 15m antes do primeiro candle da janela** de aquecimento
  técnico e **60 candles 1d completos antes da decisão** para o contexto diário.
  O script opcional de sincronização escolhe uma margem conservadora desde
  `2026-03-04 UTC` até `2026-10-15 UTC`, dividindo chamadas em blocos de
  no máximo 30 dias, com 15m e 1d explicitamente selecionados.

## Regras e pressupostos financeiros

Os números de price action/volume, EMA9/21 de ENTRADA, confirmação fechada,
stop estrutural, trailing, `capitalUsdt=1500`, risco 0,5%, stop diário 3%,
alavancagem 1, taxas, slippage, spread e funding assumido zero permanecem
**idênticos ao JSON 1d/1w**. Alteramos apenas intervalos e número de candles
necessários para 600 horas. Uma unidade de "candle" agora é 15 minutos:

- `maxHoldingCandles=30` corresponde a **7,5 horas** (e não 30 dias);
- `pullbackMinBars=3` a `pullbackMaxBars=5` significam 45 a 75 minutos;
- `volumeLookbackBars=20` significa 5 horas;
- `trailingActivationR=1` mantém 1R, sem alterar a estratégia.

Isso **não prova** comportamento equivalente entre 1d e 15m.
O valor de funding zero continua hipótese de pesquisa e não histórico real.

## Como executar (Windows PowerShell)

```powershell
cd C:\Projetos\scalper-bot
git fetch origin
git switch feat/independent-trend-pullback-range-breakout-5m
git pull --ff-only
```

Reinicie o Spring Boot após atualizar:

```powershell
.\scripts\start-backtest.ps1 -IncludeDailyWeekly -Symbols BTCUSDT,SOLUSDT,DOGEUSDT
```

Em um **segundo terminal**:

```powershell
# Somente plano: sem MySQL, sem consulta à Binance, sem backtest
.\scripts\run-independent-15m-daily.ps1 -AsOfDateUtc 2026-10-15 -PrintOnly

# Sincronização 15m/1d necessária + execução completa
.\scripts\run-independent-15m-daily.ps1 -AsOfDateUtc 2026-10-15 -SyncHistory

# Se os candles 15m/1d desse período JÁ estão íntegros no MySQL:
.\scripts\run-independent-15m-daily.ps1 -AsOfDateUtc 2026-10-15
```

O runner exige TESTNET, `PAPER_ONLY`, `ordersEnabled=false` e o backend
atualizado com a política `CLOSED_DAILY_EMA21_SLOPE`. Para cada símbolo,
verifica a existência de pelo menos 60 candles diários completos
antes do início do estudo. Consulta os filtros Binance do instrumento para
cálculo paper. O serviço rejeita lacunas na sequência de candles 15m/1d.
Não preenche dados faltantes nem inventa preços.

**Resultados:** em `results/`, prefixo
`independent-{aggregate,windows,trades,diagnostics,rejections,config}-15m-1d-600h-...`,
com timestamp e janela para não substituir os antigos 1d/1w. Sinais
diagnosticados em até **172.728 avaliações de braço** (3 símbolos × 6 janelas ×
2.399 fechamentos avaliáveis × 4 braços), por isso o CSV de diagnósticos
pode ser bastante grande e a execução pode demorar.

**Atenção à continuidade:** a janela termina sem forçar encerramento
arbitrário de posição; posições abertas são marcadas a mercado e a carteira
reinicia na próxima janela. Esses estudos independentes NÃO são equivalentes
a backtest com capital contínuo de 150 dias. Sem ordens reais e sem IA paga.
