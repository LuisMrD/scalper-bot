# Experimento BTC: recuo misto LONG/SHORT com saída por reversão de preço e volume

**PAPER ONLY**, sem ordens reais. Este experimento é separado do estudo
de stops, não altera os demais endpoints e não habilita setups de caixa.
Implementa a hipótese nova discutida em 16/10/2026: investigar saída
por **reversão confirmada de price action + volume**, mantendo o stop
estrutural, o trailing atual e o limite de 30 candles (7,5 horas) como proteção.

## Universo e controle

- Somente **BTCUSDT**, timeframe de entrada **15m**, contexto do **último 1d
  completamente fechado**, direção maior pela inclinação da EMA21 diária.
- Somente **TREND_PULLBACK_MIXED** (LONG) e
  **TREND_PULLBACK_MIXED_SHORT** (SHORT). Os detectores existentes de recuo
  misto não são modificados. Recuso tradicional e breakout não participam.
- Seis janelas não sobrepostas de **600 horas = 2.400 candles 15m**,
  encerrando em `2026-10-15T23:59:59.999Z`.
- Duas políticas independentes em cada braço: `BASELINE` (stop estrutural
  com trailing original e máximo 30 candles) e
  `PRICE_ACTION_VOLUME_REVERSAL` (todas essas proteções **mais** uma
  saída por reversão confirmada no fechamento).
- **24 carteiras paper** no total = 1 moeda × 6 janelas × 2 setups mistos ×
  2 políticas de saída. Cada carteira inicia com US$1.500 hipotéticos;
  não há soma legítima de contas distintas como se fossem uma única conta.
- Parâmetros de preço, EMA9/21 de entrada, volume, estrutura, stops iniciais,
  trailing, tamanho da posição, custos, funding assumido e gestão de risco são
  idênticos aos do controle `independent-15m-daily-research.json`. O arquivo
  `scripts/config/btc-15m-mixed-reversal-study.json` congela as entradas.

## Definição exata do novo sinal de saída (hipótese de engenharia)

A confirmação ocorre exclusivamente **após fechar o candle atual de 15m**:

**Para encerrar um LONG:**

1. Candle fecha abaixo da abertura, com corpo real / amplitude total **>= 0,65**.
2. O **fechamento** rompe para baixo a mínima do candle fechado anterior.
3. Volume agressor **vendedor** = `quoteVolume - takerBuyQuoteVolume` do
   candle atual deve superar **1,2×** o do candle anterior.
4. Volume total em cotação do candle atual deve superar a média dos **20 candles
   fechados anteriores**, excluindo o candle de confirmação.

**Para encerrar um SHORT:** tudo é espelhado: candle altista de corpo forte,
fechamento acima da máxima anterior, volume agressor comprador atual
`takerBuyQuoteVolume` superior a 1,2× o anterior, mais volume total maior
que a média dos 20 candles anteriores.

Esses números são **hipóteses operacionais de pesquisa** que reaproveitam
os limites de `bullishStrongBodyMinRatio`,
`buyerVolumeGrowthMinRatio` e `volumeLookbackBars`; não devem ser
apresentados como citação literal do vídeo, de Brooks ou de Coulling.
Sinais dependentes de apenas pavio, apenas volume ou apenas candle contrário
**não** provocam encerramento.

Quando as quatro condições forem satisfeitas, o encerramento é **agendado
para o open do candle seguinte** usando os mesmos spread/slippage adversos e
taxas de execução. Não existe execução no candle que acaba de confirmar;
sem lookahead. O stop estrutural original continua ativo intrabar com prioridade
sobre novas confirmações de saída. O máximo de 7,5 horas continua valendo.

## Relatórios e limitações

O endpoint adicional
`POST /api/v1/research/independent-setups/backtest-reversal` aceita o mesmo
JSON do estudo 15m anterior, mas exige BTCUSDT/15m/1d, 2.400 candles e
60 candles de aquecimento. O endpoint
`GET /api/v1/research/independent-setups/reversal-study/status` comprova
`PAPER_ONLY`, os dois braços e as duas políticas.

O script `scripts/run-btc-15m-mixed-reversal.ps1` exporta seis artefatos com
prefixo `BTC-mixed-reversal-15m-1d-600h` para `results/`:
`windows`, `aggregate`, `trades`, `diagnostics`, `rejections` e
`config`. Campos financeiros incluem `BaseSetup` e `ExitPolicy`, razão da
saída `STOP` / `MAX_HOLDING` / `REVERSAL_PRICE_VOLUME`, MFE/MAE/NetR,
resultado realizado, taxas, funding, mark-to-market e drawdown.

Os diagnósticos de **entradas** são compartilhados entre as duas políticas,
sem duplicar os mesmos eventos. O conjunto efetivo de operações abertas pode
divergir ao longo do tempo quando políticas encerram posições em momentos
diferentes. Por isso, comparar P&L sem conferir número e identidade dos trades
pode induzir conclusões incorretas.

A janela reinicia o saldo e pode terminar com posição marcada a mercado,
não necessariamente encerrada. A sequência OHLC dentro de um candle permanece
desconhecida; a simulação usa prioridade de stop. Funding está explicitamente
fixado em zero no teste, e não é histórico real. Não há garantia de lucro nem
validação fora da amostra: valores escolhidos neste período precisarão de
teste subsequente em dados não usados nesta hipótese.

## Execução

Depois de atualizar a branch `feat/independent-trend-pullback-range-breakout-5m`,
reinicie o backend:

```powershell
.\scripts\start-backtest.ps1 -IncludeDailyWeekly -Symbols BTCUSDT
```

Em outro terminal:

```powershell
# Apenas conferir as 6 janelas e 24 carteiras, sem chamadas à API:
.\scripts\run-btc-15m-mixed-reversal.ps1 -AsOfDateUtc 2026-10-15 -PrintOnly

# Usar os candles BTC 15m/1d já armazenados no MySQL:
.\scripts\run-btc-15m-mixed-reversal.ps1 -AsOfDateUtc 2026-10-15

# Sincronizar apenas se estiver faltando histórico:
.\scripts\run-btc-15m-mixed-reversal.ps1 -AsOfDateUtc 2026-10-15 -SyncHistory
```
