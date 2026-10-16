# Estudo MIDAS — BTCUSDT, recuos mistos LONG/SHORT, 12 janelas × 900h

**Somente PAPER TRADING e histórico. Sem ordens reais.** PR #25 continua draft;
nenhuma integração de execução é ativada. Preserva exatamente a saída
BASELINE (stop estrutural original, trailing de pivôs, invalidação e prazo
máximo de 30 candles de 15 minutos = 7,5 horas).

## Objetivo

Testar se acrescentar uma curva MIDAS baseada em preço/volume melhora a
seleção dos MESMOS sinais de recuo misto LONG e SHORT, sem mudar a lógica
de detecção dos sinais, stops, custos ou dimensionamento de posição.

Universo congelado:
- BTCUSDT; candles de 15m fechados e filtro de direção EMA21 de 1d
  inteiramente fechado.
- Banca US$ 600 por carteira, alavancagem 1x, risco por operação 0,5%,
  limite diário 3%, taxa por lado 0,05%, slippage adverso 0,02% por lado,
  spread 0,01%, funding hipotético zero, máximo de custos/risco 50%.
- 12 janelas *independentes* de 900 horas, sem lacunas nem sobreposição
  (3600 candles avaliados por janela). Cada conta é reiniciada em US$600
  ao começar a janela; LONG e SHORT são contas separadas.
- 2 setups mistos × 3 filtros = **6 contas por janela, 72 ao todo**.
  Não somar P&L como se fosse uma única curva financeira composta.
- Histórico final de 2026-10-15 23:59:59.999 UTC, início 2025-07-23.
  Testar 450 dias já analisados não representa validação fora da amostra.

## Curva MIDAS experimental e pontos de ancoragem

Esta implementação utiliza uma curva **MIDAS-style**, semelhante a um VWAP
ancorado. É aproximação de engenharia em candles OHLCV, não reprodução
de todas as variantes de Levine nem de perfil de volume intrabar:

MIDAS(t) = SOMA[ ((high_i + low_i)/2) * baseVolume_i ] /
           SOMA[ baseVolume_i ], para cada candle i desde a âncora até t.

O peso é **baseVolume BTC** da Binance, não quoteVolume USDT. Se não há
volume acumulado ou âncora válida, o MIDAS é indisponível; **não** se
substitui por EMA nem por zero arbitrário.

- LONG: âncora no **último fundo de swing confirmado**.
- SHORT: âncora no **último topo de swing confirmado**.
- Confirmação por `pivotLeft=2` e `pivotRight=2` dos parâmetros
  congelados. Um candidato no candle `i-2` só fica confirmado após
  fechar o candle `i`; nenhum cálculo usa barra futura. Quando confirmado,
  refaz as somas desde aquele candle de pivô ATÉ o candle recém-fechado.
- A ancoragem é substituída quando um novo pivô de mesmo tipo confirma.
  Valores históricos da curva **não** são reescritos com o pivô novo.
- Uma vez ancorada, a soma continua incrementalmente para todos os
  candles seguintes. Não se limita aos últimos 60 candles usados pelos
  detectores existentes.
- **Aquecimento MIDAS = 1000 candles adicionais de 15m**, carregados
  antes da primeira decisão em cada janela, em vez dos 60 de contexto
  de entrada. Não garante pivô antes da primeira decisão; nesse caso
  os filtros MIDAS bloqueiam apenas seus próprios sinais.
- Cada janela reinicia a curva com o aquecimento anterior à sua data.
  Não se carrega estado do período futuro nem da janela seguinte.

## Três políticas independentes de entrada

Os setups originais precisam confirmar entrada e ser aprovados pelo
filtro de direção diário ANTES de qualquer teste MIDAS.

1. `BASELINE`: **idêntico ao estudo original de US$600**. O MIDAS
   não modifica nem bloqueia sua entrada; nem participa da saída.
2. `MIDAS_TREND`: após sinal válido, exige **fechamento atual acima
   do MIDAS para LONG**, ou **abaixo para SHORT**. Sinal que não tem
   âncora válida ou está do lado errado fica em WAIT apenas nesta
   carteira.
3. `MIDAS_PULLBACK_REJECTION`: além de `MIDAS_TREND`, exige que
   pelo menos um dos **5 candles fechados anteriores** tenha negociado
   dentro da zona MIDAS daquele respectivo candle, com tolerância
   **0,2% para cima/baixo** (usa o `retestTolerancePct` original).
   Esse candle tocado deve pertencer à **MESMA âncora** da confirmação
   atual. A confirmação de recuo compradora/vendedora vem do detector
   de entrada misto já existente; não acrescenta candle futuro.
   Não há saída adicional por MIDAS.

Todos os fills ainda ocorrem no open do **próximo candle**, com spread,
taxas e slippage. Stops intrabar permanecem STOP FIRST. Não existe
nenhuma nova alavancagem ou permissão de ordens.

## Endpoints, diagnósticos e relatórios

- `GET /api/v1/research/independent-setups/midas-study/status`:
  confirma papel (PAPER_ONLY), 3 políticas, 1000 candles extra de
  aquecimento, BTC/$600/12x900h.
- `POST /api/v1/research/independent-setups/backtest-midas`:
  mesma estrutura de request do estudo anterior, mas exige BTCUSDT,
  15m/1d, 3600 barras de estudo, 60 de contexto dos setups e US$600.
  O serviço consulta **4600 candles 15m** contínuos por janela;
  emite diagnósticos para 3599 candles x 2 braços por janela
  (86.376 eventos, sem triplicar os sinais originais).
- A API retorna separadamente seis carteiras por janela, motivos
  MIDAS_NO_CONFIRMED_ANCHOR_OR_VOLUME, MIDAS_WRONG_SIDE_OF_CURVE,
  MIDAS_NO_CONFIRMED_RETEST, e valores/âncoras/instantes de toque
  nos eventos `midasAssessments`.
- O PowerShell exporta seis arquivos `windows`, `aggregate`, `trades`,
  `diagnostics`, `rejections`, `config` usando prefixo
  `BTC-mixed-MIDAS-15m-1d-900h`. O CSV de diagnóstico inclui
  `MidasValue`, `MidasAnchorOpenTime`, `MidasTrendAllowed`,
  `MidasTrendReason`, `MidasRetestAllowed`,
  `MidasRetestReason`, `MidasRetestTouchedCloseTime`.
- Cada `Arm` composto preserva o setup `BaseSetup` e
  `EntryPolicy` (separados por `__`); `ExitPolicy` é sempre BASELINE.

## Executar

Com o histórico 15m/1d do último estudo de 450 dias já no MySQL,
é provável que só faltem alguns candles anteriores à janela mais antiga
para o aquecimento MIDAS de 1000 barras. A Binance TESTNET pode não
fornecer dados suficientemente antigos; não usar dados de produção nem
fabricar candles ausentes.

No terminal do backend após atualizar a branch:

~~~powershell
cd C:\Projetos\scalper-bot
git fetch origin
git switch feat/independent-trend-pullback-range-breakout-5m
git pull --ff-only
.\scripts\start-backtest.ps1 -IncludeDailyWeekly -Symbols BTCUSDT -CapitalUsdt 600
~~~

Em outro terminal:

~~~powershell
# Apenas plano offline (12 janelas, 72 carteiras)
.\scripts\run-btc-15m-mixed-midas-12x900h.ps1 -AsOfDateUtc 2026-10-15 -PrintOnly

# Executa o estudo com o histórico local; falta de candles é um erro explícito
.\scripts\run-btc-15m-mixed-midas-12x900h.ps1 -AsOfDateUtc 2026-10-15

# Somente se faltar histórico local:
.\scripts\run-btc-15m-mixed-midas-12x900h.ps1 -AsOfDateUtc 2026-10-15 -SyncHistory
~~~

A confirmação de robustez deve ocorrer em novos dados temporais não
empregados na seleção dessas três políticas ou seus parâmetros.
