# BTC mixed LONG baseline / SHORT MIDAS: A/B isolado e OOS prospectivo

## Objetivo e escopo

**PAPER ONLY; nunca habilitar ordens reais.** A hipótese foi escolhida em
16/10/2026 APÓS analisar os resultados de BTCUSDT de 2025-07-23 a
2026-10-15. Portanto **nenhum resultado dessas 12 janelas de 900h é
validação fora da amostra**. O backtest histórico serve APENAS para
reproduzir a escolha e checar que o comportamento foi implementado
corretamente. Para validação genuína é preciso acompanhar novos candles
fechados DEPOIS de 15/10/2026.

**Banca:** US$600 POR CARTEIRA e janela (não capital combinado); LONG e
SHORT mistos independentes. Posição reiniciada por janela. Entrada no
próximo candle 15m fechado, tendência pela EMA21 do último candle diário
inteiramente fechado. Mantêm-se todos os demais parâmetros da configuração
congelada: risco 0,5% por operação, stop diário 3%, alavancagem 1x, taxas
0,05% por lado, spread 0,01%, slippage adverso 0,02% por lado,
funding hipotético 0, máximo 30 candles de 15m por trade.

## Políticas / quatro livros independentes por janela

| Livro paper | Entrada | Stop/saída |
|---|---|---|
| TREND_PULLBACK_MIXED__BASELINE | LONG recuo misto original | Stop estrutural e trailing originais |
| TREND_PULLBACK_MIXED_SHORT__BASELINE | SHORT recuo misto original | Idem |
| TREND_PULLBACK_MIXED__HYBRID_LONG_BASELINE_SHORT_MIDAS_RETEST | LONG idêntico ao controle; **sem MIDAS** | Idem |
| TREND_PULLBACK_MIXED_SHORT__HYBRID_LONG_BASELINE_SHORT_MIDAS_RETEST | SHORT original + MIDAS de recuo/rejeição | Idem |

O SHORT híbrido aplica o MESMO `MidasEntryFilter` já estudado:
preço fecha abaixo da curva MIDAS ancorada no último topo confirmado,
e um dos 5 candles fechados anteriores tocou a região da curva com
tolerância de 0,2%, sob a MESMA âncora. Preço/volume de confirmação
vêm do detector SHORT misto. A âncora é confirmada por pivô 2/2 fechado
e calculada incrementalmente usando 1000 candles 15m de aquecimento
adicionais; nunca usa um candle futuro. LONG híbrido retorna sempre
`MidasEntryPolicy.BASELINE`.

**Manejo:** as quatro carteiras retêm exatamente o stop inicial
estrutural, trailing por pivôs e limite máximo de 7,5h. Sem ATR,
break-even extra, reversão por volume ou novas condições de saída.

## 1. Comparação HISTÓRICA — não é OOS

~~~powershell
cd C:\Projetos\scalper-bot
git fetch origin
git switch feat/independent-trend-pullback-range-breakout-5m
git pull --ff-only

# Primeiro terminal: iniciar o backend em modo exclusivamente paper
.\scripts\start-backtest.ps1 -IncludeDailyWeekly -Symbols BTCUSDT -CapitalUsdt 600
~~~

~~~powershell
# Segundo terminal — apenas datas e carteiras
.\scripts\run-btc-15m-mixed-midas-hybrid.ps1 -AsOfDateUtc 2026-10-15 -PrintOnly

# Executar com MySQL/Testnet já sincronizados
.\scripts\run-btc-15m-mixed-midas-hybrid.ps1 -AsOfDateUtc 2026-10-15
~~~

Verifica **12 janelas de 900h x 4 carteiras = 48 livros paper**,
do dia 23/07/2025 ao dia 15/10/2026. Exporta seis CSV/JSON com
identificador `BTC-mixed-MIDAS-HYBRID-INSAMPLE-...`.
Nunca afirmar que este resultado confirma a vantagem fora da amostra.

## 2. Validação PROSPECTIVA — só depois de completar 900 horas novas

O primeiro candle da nova janela PRECISA começar **não antes de
2026-10-16T00:00:00Z**, após o último candle estudado. Assim, a primeira
janela prospectiva completa de 900h termina em
**2026-11-22T11:59:59.999Z**. Como o runner recebe uma **data UTC**
e fecha às 23:59:59.999Z, o primeiro `AsOfDateUtc` permitido
no runner é **2026-11-22**, que estuda um período com primeiro candle
em 16/10/2026 às 12:00 UTC. Não rodar antes do encerramento dos
candles; a verificação `asOf < clock` impede ler o futuro.

~~~powershell
# A PARTIR DO ENCERRAMENTO DE 2026-11-22 UTC (no Brasil: 21h -03)
# Primeiro conferir o plano, sem network:
.\scripts\run-btc-15m-mixed-midas-hybrid.ps1 -AsOfDateUtc 2026-11-22 -ProspectiveOos -PrintOnly

# Com histórico BTC 15m/1d real da TESTNET fechado e íntegro
.\scripts\run-btc-15m-mixed-midas-hybrid.ps1 -AsOfDateUtc 2026-11-22 -ProspectiveOos
~~~

O modo `-ProspectiveOos` roda **somente uma janela de 900 horas**
com **quatro livros paper**, chama o endpoint
`POST /api/v1/research/independent-setups/backtest-midas-hybrid-oos`
e **rejeita** no backend qualquer request com primeiro candle
anterior a 16/10/2026. O endpoint histórico usa
`POST .../backtest-midas-hybrid` e é rotulado **INSAMPLE_DIAGNOSTIC_ONLY**.

Histórico anterior ao período avaliado pode ser usado APENAS para
pré-aquecimento MIDAS (1000 candles) e EMA21 diária, nunca para calcular
novos trades do teste prospectivo. Caso a Testnet não tenha histórico
contínuo 15m/1d, interromper; não misturar candles da Binance produção
nem preencher vazios com candles sintéticos.

## Saídas e avaliação

Os seis arquivos apresentam as 4 carteiras por janela, `BaseSetup`,
`HybridPolicy`, `AppliedEntryFilter`, saídas STOP/MAX_HOLDING,
taxas, P&L realizado e marcado, MFE/MAE/NetR, drawdown,
diagnósticos e rejeições MIDAS. P&L conjunto de carteiras separadas
**não** é curva de patrimônio de uma conta única com posições simultâneas.

Exigir no teste prospectivo: LONG híbrido idêntico ao LONG controle
(entradas e P&L); SHORT híbrido comparado ao SHORT controle;
confirmar número de sinais bloqueados, expectativa por trade, taxas
e drawdown por carteira/janela, antes de qualquer conclusão operacional.
Não ativar ordens reais baseado em uma única janela prospectiva.
