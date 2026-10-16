# BTC MIDAS híbrido — saídas de 30/45/60 candles vs continuidade dinâmica até 60

## Objetivo

Experimento **PAPER_ONLY**, sem ordens reais ou merge. As entradas continuam
exatamente iguais às selecionadas anteriormente: **LONG misto sem MIDAS;
SHORT misto somente após filtro MIDAS de recuo/rejeição**. Usa o MESMO
stop inicial estrutural, trailing por pivô, invalidação, risco e custos.
Isola exclusivamente o efeito do prazo máximo e da decisão por
continuidade de tendência. Os resultados dos 450 dias são IN-SAMPLE;
nunca tratar como validação prospectiva.

Configuração congelada:
- BTCUSDT 15m, tendência EMA21 no último candle 1d fechado,
  capital US$600 **por carteira/janela**, risco por operação 0,5%,
  limite diário 3%, alavancagem 1, taxas 0,05% por lado, slippage
  adverso 0,02% por lado, spread 0,01%, funding hipotético 0.
- 12 janelas independentes × 900h, 3600 candles por janela, último
  candle UTC em 2026-10-15T23:59:59.999Z; 1.000 candles adicionais
  de aquecimento causal MIDAS e os 60 candles de contexto de entrada.
- 2 direções mistas × 4 políticas de manutenção × 12 janelas =
  **96 carteiras paper** separadas, cada uma reiniciando em US$600.
  Comparações são por janela e direção; nunca interpretar o total de
  USDT como patrimônio de uma única conta composta.
- As políticas partilham os MESMOS sinais de entrada, mas podem
  preencher operações diferentes porque o tempo de permanência
  bloqueia/permite oportunidades posteriores.

## Quatro políticas de saída

| Política | Limite | Condição depois de 30 candles |
|---|---|---|
| `BASELINE_30` | 30 × 15m = 7,5h | Sem condição adicional, saída no próximo open |
| `HOLD_45` | 45 × 15m = 11,25h | Apenas stop estrutural/trailing/invalidação originais |
| `HOLD_60` | 60 × 15m = 15h | Apenas stop estrutural/trailing/invalidação originais |
| `DYNAMIC_TREND_60` | Máximo 60 × 15m = 15h | Em CADA fechamento desde 30, continuar SÓ se todos os critérios abaixo forem verdadeiros |

A condição dinâmica foi congelada como **hipótese de engenharia**, não
regra literal de Brooks/Coulling:

1. Direção da **EMA21 diária inteiramente fechada** é `UP` para
   LONG ou `DOWN` para SHORT; `FLAT` e referência ausente não
   autorizam continuação.
2. EMA9 15m do candle fechado está **acima da EMA21** para LONG e
   **abaixo** para SHORT.
3. EMA21 15m tem inclinação na direção operada (valor atual vs valor
   calculado no fechamento anterior).
4. Fechamento atual ainda está do lado favorável da EMA21 15m.
5. Existe último **pivô estrutural confirmado** 2/2 nos candles
   fechados recentes. LONG precisa fechar acima do último fundo
   confirmado; SHORT abaixo do último topo confirmado. Pavio que
   cruza o nível não equivale a fechamento de quebra.

Se QUALQUER condição falhar a partir do candle 30, agenda saída no
**próximo open de 15m**, com razões `DYNAMIC_DAILY_TREND_LOST`,
`DYNAMIC_15M_EMA_TREND_LOST`,
`DYNAMIC_NO_CONFIRMED_STRUCTURE` ou
`DYNAMIC_15M_STRUCTURE_BROKEN`. Se todas permanecerem favoráveis,
mantém a posição até o limite absoluto de 60 candles, a menos que
stop/trailing/invalidação encerrassem antes. Motivos do prazo máximo:
`MAX_HOLDING` (controle), `MAX_HOLDING_45` e `MAX_HOLDING_60`.
A proteção do stop é calculada intrabar com prioridade e continua
inalterada nas quatro políticas. Nenhuma decisão usa a máxima/mínima
de candles futuros. Há OHLC stop-first sem sequência intrabar conhecida.

## Endpoint e relatórios

`GET /api/v1/research/independent-setups/dynamic-holding/status`:
confirma paper-only, BTC, 12×900h, US$600, 4 políticas, stop original e
entrada híbrida congelada.

`POST /api/v1/research/independent-setups/backtest-dynamic-holding`:
mesmo request explícito do estudo híbrido anterior, validando
BTCUSDT/15m/1d, steps=3600, contextCandles=60,
capitalUsdt=600 e maxHoldingCandles=30. **O valor 30 do request é o
ponto-base e gatilho da continuação**; o limite de 45/60 é exclusivo
das políticas locais experimentais, não muda o endpoint original.

O script exporta `windows`, `aggregate`, `trades`,
`diagnostics`, `rejections`, `config` em seis CSV/JSON,
prefixados `BTC-mixed-MIDAS-DYNAMIC-HOLD-INSAMPLE...`.
Os trades incluem P&L realizado/fees/NetR/MFE/MAE, motivo e
`HoldingPolicy`; duração efetiva pode ser calculada como
`(ExitOpenTime-EntryOpenTime)/900000` candles. Os diagnósticos
por candle de ENTRADA não são triplicados; no máximo duas avaliações
por 15m candle, uma por direção.

## Executar — PowerShell Windows 11

~~~powershell
cd C:\Projetos\scalper-bot
git fetch origin
git switch feat/independent-trend-pullback-range-breakout-5m
git pull --ff-only

# TERMINAL 1 (reiniciar backend)
.\scripts\start-backtest.ps1 -IncludeDailyWeekly -Symbols BTCUSDT -CapitalUsdt 600

# TERMINAL 2: conferir 12 janelas x 8 livros = 96 (sem network)
.\scripts\run-btc-15m-mixed-dynamic-holding.ps1 -AsOfDateUtc 2026-10-15 -PrintOnly

# Backtest no MySQL BTC 15m e 1d já sincronizados
.\scripts\run-btc-15m-mixed-dynamic-holding.ps1 -AsOfDateUtc 2026-10-15
~~~

Use `-SyncHistory` somente se faltar algum candle na TESTNET. Não
misturar automaticamente histórico de produção com TESTNET; não
substituir dados indisponíveis por candles sintéticos.

## Como avaliar

Comparar cada direção e janela, lucro líquido, taxas, motivos STOP e
MAX_HOLDING, perdas devolvidas, duração média, MFE/NetR e drawdown.
Verificar primeiro que `BASELINE_30` coincide exatamente com o
**braço híbrido de 30 candles** do experimento anterior. Depois
comparar 45,60 e dinâmica sem escolher vencedor com base nas
maiores duas operações apenas. Uma política que aumente P&L neste
período ainda é hipótese in-sample; validar sem novos ajustes nos
dados posteriores à seleção, inclusive eventuais custos/funding reais.
