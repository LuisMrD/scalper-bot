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


## Ampliação histórica com parâmetros congelados: 360 e 720 dias

Estudo exclusivamente de recuo LONG/SHORT no 1D, com EMA21 do **último
semanal 1W completamente fechado** para orientação. O experimento de 16/10/2026
com 6×30 candles permanece como referência e **não sofre reotimização**.
O script `scripts/run-independent-extended-sample.ps1` escolhe, sem alterar
os outros parâmetros, entre:

| Horizonte aproximado | Janelas independentes | Primeira data de estudo UTC (asOf 2026-10-15) | Carteiras (BTC/SOL/DOGE × 4 braços) |
|---|---|---|---|
| 12 × 30 dias = 360 dias | 12 | 2025-10-21 | 144 |
| 24 × 30 dias = 720 dias | 24 | 2024-10-26 | 288 |

**Não são 12 ou 24 meses-calendário exatos:** são 360 e 720 dias completos,
respectivamente. A amostra de 12 janelas está inteiramente **incluída**
na de 24; compará-las é válido, mas **somar seus P&Ls como duas amostras
independentes não é**. Cada janela reinicia quatro carteiras de capital
hipotético US$ 1.500 e reserva o último dia para encerramento/marcação,
sem nova entrada. Há até 29 oportunidades de sinal diário por janela.
Lucro de janelas e braços independentes não equivale à curva de capital
contínua de um único robô; posições ainda abertas no fim da janela
são marcadas a mercado, não necessariamente realizadas.

### Execução local (PowerShell)

Primeiro, inicie o backend atualizado em outra janela, sem ordens:

```powershell
.\scripts\start-backtest.ps1 -IncludeDailyWeekly -Symbols BTCUSDT,SOLUSDT,DOGEUSDT
```

No segundo terminal, inspecione o plano **sem consultas externas**:

```powershell
.\scripts\run-independent-extended-sample.ps1 -Months 12 -PrintOnly
.\scripts\run-independent-extended-sample.ps1 -Months 24 -PrintOnly
```

Para solicitar os candles ausentes da origem TESTNET configurada e executar
**primeiro** a amostra de 12 janelas:

```powershell
.\scripts\run-independent-extended-sample.ps1 -Months 12 -SyncHistory -AsOfDateUtc 2026-10-15
```

Em seguida, somente se a origem disponibilizar o histórico completo:

```powershell
.\scripts\run-independent-extended-sample.ps1 -Months 24 -SyncHistory -AsOfDateUtc 2026-10-15
```

Caso os candles já estejam completamente no MySQL, retire
`-SyncHistory` e execute diretamente. O wrapper sempre usa
`scripts/config/independent-daily-weekly-{12,24}x30d.json`,
verificando que são **idênticos à configuração de 6 janelas, exceto
pelo número de janelas**. Os seis arquivos de cada estudo são escritos
em `results/` com os identificadores `w1-12` ou `w1-24`; não
substituem o experimento original `w1-6`.

### Retenção e integridade dos dados

A amostra de 12 janelas exige sincronização de referência começando em
**2024-07-16 UTC**, enquanto a de 24 começa em **2023-07-22 UTC**.
Estas datas incluem margem de 66 semanas antes do primeiro sinal,
para satisfazer o requisito de **60 semanas totalmente fechadas**
no contexto do replay, além dos 60 candles diários de aquecimento.
Se o arquivo local já contém esses candles não é necessário ressincronizar.

**A Binance Futures TESTNET pode não oferecer candles tão antigos**.
`-SyncHistory` usa APENAS a fonte TESTNET já configurada no backend.
A sincronização se interrompe quando um bloco diário não retorna candle
algum. O replay executa as janelas da mais antiga para a mais recente,
de modo a detectar precocemente falta de histórico; se há lacunas,
o backend rejeita a janela em vez de construir candles sintéticos.
Uma falha significa **estudo incompleto**: não se devem divulgar
métricas de 12 ou 24 meses nem presumir retenção histórica da Testnet.
Não substitua silenciosamente candles da Testnet por preços de produção:
uma eventual migração para mercado público histórico real exige
estratégia própria de proveniência dos dados.

**Regra de segurança:** simulador paper-only, sem ordens reais,
sem treinamento IA pago, sem relaxar filtros técnicos para ampliar
artificialmente o número de operações. A comparação deve relatar
separadamente P&L líquido realizado, posições marcadas a mercado,
número de trades distintos (mesmo LONG/SHORT strict vs mixed pode
capturar o mesmo evento), ganho/perda médios, drawdown e operações
interrompidas nas fronteiras das janelas.
