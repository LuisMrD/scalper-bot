# Pesquisa atual — somente recuo em tendência (1D / 1W)

**Esta é a configuração vigente do PR #25.** Os documentos anteriores
`independent-5m-setups-research.md` e `independent-daily-weekly-research.md`
registram experimentos históricos com caixas; **esses setups de caixa foram
desativados e não participam do endpoint, dos resultados ou do walk-forward**.
Arquivos e migrations antigas permanecem somente para compatibilidade e histórico;
não são invocados pela pesquisa atual. Código de entradas reais continua desligado.

## As únicas quatro carteiras

| Identificador | Lado | Condição diária |
|---|---|---|
| `TREND_PULLBACK` | LONG | Pullback estrito com candles baixistas |
| `TREND_PULLBACK_MIXED` | LONG | Pullback com candles mistos, mas queda líquida |
| `TREND_PULLBACK_SHORT` | SHORT | Recuo altista estrito durante tendência de baixa |
| `TREND_PULLBACK_MIXED_SHORT` | SHORT | Recuo altista com candles mistos, mas alta líquida |

Os **filtros de entrada do próprio pullback** continuam: swing confirmado,
alinhamento EMA9/21 diário, retorno à zona das médias, diminuição de volume e
corpos durante o recuo, ausência de rompimento de fundo/topo estrutural,
confirmação de resposta compradora/vendedora por candle fechado e volume de
agressores. Abertura paper no candle seguinte, stop estrutural, trailing,
taxas, spread, slippage, alavancagem e limites de risco permanecem inalterados.

## Único filtro de direção do gráfico maior

Para o primário 1D, o gráfico maior é **1W** da Binance (segunda-feira
00:00:00 UTC até domingo 23:59:59.999 UTC). O novo filtro usa somente a
**inclinação da EMA21 semanal** com candles **integralmente fechados**:
- `EMA21_atual > EMA21_anterior`: autoriza **LONG**;
- `EMA21_atual < EMA21_anterior`: autoriza **SHORT**;
- `EMA21_atual == EMA21_anterior`: bloqueia ambas as direções.

A classificação Brooks `UP`, `DOWN`, `RANGE`, `UNDETERMINED` **não é
mais requisito de entrada**. Um `RANGE` Brooks pode ser elegível se a inclinação
da EMA21 semanal for direcional. Por decisão de segurança, semanas abertas,
insuficientes ou lacunadas não serão usadas. São necessários 22 candles
semanais completos para calcular a diferença entre duas EMA21. O snapshot da
aplicação deve disponibilizar pelo menos 22 candles em
`market-context.recent-candles` (padrão atual: 30).

**A EMA21 como medidor único de tendência semanal é uma escolha explícita
desta iteração de engenharia, não uma regra numérica atribuída a Al Brooks
ou Anna Coulling.** Ela simplifica o filtro, mas não garante que o mercado
esteja efetivamente em tendência. Sem lookahead nem uso da semana em andamento.

## Parâmetros de compatibilidade

O JSON `scripts/config/independent-daily-weekly-research.json` ainda inclui
campos antigos sobre caixa (`consolidationBars`, `boxWidthMaxAtr`,
`boxVolumeDryUpMaxRatio` etc.) para manter o formato de
`IndependentSetupParameters` e permitir reproduzir estudos anteriores.
**Esses campos não são consultados por nenhum dos quatro detectores ativos.**
Valores financeiros e de pullback permanecem editáveis e explícitos; resultados
de simulação não são execução de corretora.

## Execução

```powershell
git fetch origin
git switch feat/independent-trend-pullback-range-breakout-5m
git pull --ff-only
# Terminal 1: reinicie o backend para carregar o código novo
.\scripts\start-backtest.ps1 -IncludeDailyWeekly -Symbols BTCUSDT,SOLUSDT,DOGEUSDT
# Terminal 2:
.\scripts\run-independent-daily-weekly.ps1 -AsOfDateUtc 2026-10-15 -PrintOnly
.\scripts\run-independent-daily-weekly.ps1 -AsOfDateUtc 2026-10-15
```

São três moedas × seis janelas independentes de 30 candles × quatro braços,
**72 carteiras independentes**, com 29 datas elegíveis para sinais por janela.
O script continua exportando cinco CSVs (janelas, agregado, trades,
diagnóstico, rejeições) e o JSON congelado em `results/`. Campos
`HigherTrend` e `HigherTrendApproved` no diagnóstico agora significam
**EMA21 semanal ascendente/descendente/plana**, não `PriceActionSnapshot.Trend`.
Os resultados históricos permanecem em seus próprios arquivos, sem mistura.

**Sem negociação real e sem arbitragem combinada entre os quatro braços.**
