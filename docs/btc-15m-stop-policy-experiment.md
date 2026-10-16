# Experimento de stops: BTCUSDT, 15m com referência 1d, 6 × 600h

**PAPER ONLY** — não habilita ordens, não altera o endpoint antigo
`POST /api/v1/research/independent-setups/backtest`. Endpoint separado:
`POST /api/v1/research/independent-setups/backtest-stops`,
mesmo contrato `IndependentResearchRequest`, mas exigindo BTCUSDT, 15m/1d,
`steps=2400`, `contextCandles=60`. Versões e parâmetros:
`scripts/config/btc-15m-stop-study.json`.

## Controles e hipóteses

Cada janela de 600h contém 2400 candles **15m fechados** e o último candle
é apenas de marcação/gestão (sem gerar ordem para candle futuro).
Cada janela reinicia capital e posição. São seis janelas não sobrepostas,
do dia 19/05/2026 até 15/10/2026 (UTC), as mesmas do estudo 15m anterior.

Uma execução do novo endpoint calcula os **mesmos quatro sinais de recuo**
(LONG tradicional/misto e SHORT tradicional/misto), usando a direção EMA21
dos candles diários **totalmente fechados**. Cada sinal é repassado a
**cinco carteiras financeiras independentes**, com as mesmas taxas, spread,
slippage, funding hipotético, alavancagem, orçamento de risco, tamanho
mínimo e limite diário. Assim, temos **20 carteiras por janela e 120 no total**.
Sem estratégia de caixa. Todas as hipóteses abaixo são propostas de
engenharia para comparação, não regras retiradas do vídeo.

| Política / sufixo `Arm` | Stop inicial | Regra após entrada |
|---|---|---|
| `BASELINE` | Estrutural + 1 tick | Trailing estrutural original (ativa em 1R), idêntico à implementação anterior |
| `ATR_025` | LONG: fundo − 0,25 ATR14; SHORT: topo + 0,25 ATR14 (preserva 1 tick original) | Trailing estrutural original |
| `ATR_050` | LONG: fundo − 0,50 ATR14; SHORT: topo + 0,50 ATR14 (preserva 1 tick original) | Trailing estrutural original |
| `BREAK_EVEN_1_5R` | Original | Acrescenta stop de equilíbrio após **fechamento** favorável de +1,5R, cobrindo a taxa de entrada, futura taxa de saída, spread/slippage adversos e funding já debitado; trailing estrutural **continua ativo** |
| `ATR_TRAIL_2_5` | Original | Substitui trailing estrutural por máximo favorável (LONG) / mínimo favorável (SHORT) ±2,5 × ATR14; só ativa após fechamento em +1,5R |

Observações:
- ATR14 usa **14 candles 15m já fechados**, sem dados futuros.
- A margem ATR soma-se ao stop estrutural/tick anterior; não o substitui.
  O risco de entrada e a quantidade de contratos são recalculados usando
  a **distância efetiva** do novo stop, custos estimados e filtros de mercado.
  **Não** amplia arbitrariamente a perda máxima por operação.
- Stop LONG arredonda para baixo (tick); SHORT para cima.
  O break-even arredonda no **sentido conservador para lucro não negativo**
  depois dos custos estimados (LONG para cima, SHORT para baixo).
- Os stops são testados sobre máxima/mínima intrabar com prioridade de stop;
  alterações por ATR ou break-even calculadas **após o fechamento** só
  passam a valer no candle seguinte. Isso evita lookahead.
- Stops nunca voltam para trás. Gap pode causar perda superior ao orçamento
  inicialmente pretendido. As taxas/slippage/funding permanecem hipóteses
  configuradas, não livro de ordens real.
- MFE e MAE são apresentados em múltiplos do risco inicial por unidade
  (colunas `MfeR`, `MaeR`), mais `NetR`. São **extremos da faixa OHLC**
  observada enquanto a posição está aberta. Se stop e excursão ocorrerem
  no mesmo candle, **a ordem intrabar é desconhecida**; esses extremos não
  provam que o preço favorável tenha ocorrido antes do stop.

**Limites de interpretação:** A comparação mede *políticas independentes*,
não a execução de 5 stops sobre uma posição única. A janela de 12/24 meses
ou dados de outras moedas não participam. O último candle de cada janela
marca a mercado eventual posição aberta; não faz fechamento forçado.
O total de P&L por carteira e janela não equivale a uma curva contínua
de capital, nem se deve somar braços coincidentes como se fossem trades
distintos. Para avaliar qual política generaliza, escolher uma hipótese
nesse período e depois validá-la **fora da amostra**, sem reajustá-la
com o mesmo conjunto de trades.

## Execução PowerShell

Atualize o branch `feat/independent-trend-pullback-range-breakout-5m` e
reinicie o backend com
`./scripts/start-backtest.ps1 -IncludeDailyWeekly -Symbols BTCUSDT,SOLUSDT,DOGEUSDT`.
O backend fornece `GET /api/v1/research/independent-setups/stop-study/status`,
que o script verifica antes de iniciar. A rota rejeita qualquer símbolo
que não seja BTC, qualquer timeframe fora de 15m/1d ou outro número
de candles. Nenhum endpoint de ordens é usado.

```powershell
# Plano offline, sem MySQL ou Binance
.\scripts\run-btc-15m-stop-study.ps1 -AsOfDateUtc 2026-10-15 -PrintOnly

# Reutiliza histórico BTC 15m/1d já sincronizado no MySQL
.\scripts\run-btc-15m-stop-study.ps1 -AsOfDateUtc 2026-10-15

# Somente caso não exista o histórico fechado necessário
.\scripts\run-btc-15m-stop-study.ps1 -AsOfDateUtc 2026-10-15 -SyncHistory
```

Os resultados ficam em `results/`, separados do estudo 15m original, com
prefixo `independent-*-BTC-stop-15m-1d-600h-...`. O script exporta
seis arquivos: `windows`, `aggregate`, `trades`, `diagnostics`,
`rejections`, `config`. As linhas financeiras têm `StopPolicy`,
`BaseSetup`, `Side` e `Arm` no identificador composto. Os diagnósticos
de entrada são **compartilhados por política**, pois as entradas são
avaliadas uma vez, sem quintuplicar artificialmente a contagem de sinais.

O banco registra uma execução por símbolo/janela, com configuração de
experimento identificada em `parameter_json`, e somente quatro eventos
de entrada por candle avaliado. Não há ordens reais.
