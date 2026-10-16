# Dois setups isolados — 1D operacional + 1W tendencia maior

Atualizacao do PR #25 solicitada em 2026-10-16. O fluxo preexistente `5m` + `15m/1h/4h` continua disponivel; o experimento atual deve informar **`entryInterval: "1d"` e `trendInterval: "1w"`** explicitamente.

## EXTRAIDO DO VIDEO
- Recuo para regiao das medias 9/21 numa alta com topos/fundos ascendentes, menor volume vendedor, menor tamanho de barras, estrutura intacta e gatilho comprador posterior. Tocar a media nao gera entrada.
- Consolidacao estreita depois de alta, volume e range caindo, breakout **com FECHAMENTO** acima de resistencia congelada e volume superior a media historica; entrada direta ou apos reteste; falha sob a base implica invalidacao.
- Principios apresentados em multiplos timeframes. **O video NAO determina operacoes exatamente em 1d/1w para criptomoedas**, nem percentuais, valores de stop, horizonte OOS ou ratios de confirmacao.

## PROPOSTAS DE ENGENHARIA IMPLEMENTADAS
- Candles de entrada: 1d, UTC (00:00 a 23:59:59.999); o sinal e conhecido no fechamento e fill simulado no **proximo open diario**, nunca na abertura do candle sinalizador.
- Tendencia maior: ultimo candle Binance **1w inteiramente FECHADO**, com semana de segunda-feira 00:00 UTC ate domingo 23:59:59.999 UTC. A analise Price Action preexistente classifica estrutura semanal. Somente abre LONG quando a semana conhecida confirma Trend.UP.
- **Segunda-feira ate sabado:** nao utiliza highs, lows ou volume da semana corrente ainda em formacao; usa a ultima semana concluida. Depois do fechamento de domingo, a semana concluida fica elegivel para decisoes daquele fechamento e seguintes.
- Continua usando EMA9 e EMA21 **diarias**, pivots diários confirmados e volume dos candles diarios. Os dois setups continuam independentes da Bollinger.
- **Sem lookahead:** `MarketContextService.prepareReplay` corta por `closeTime <= asOf`, a validacao semanal confirma fechamento e ancora UTC, a caixa nao usa o candle de breakout para criar a propria resistencia.
- Custo de funding continua hipotetico e obrigatorio no request. Como uma vela diaria cobre 24h, o modelo cobra **3 periodos de 8h por candle diário completo** enquanto a posicao permanece aberta. Sem historico real de funding ou preco de cada janela de 8h, isto e uma aproximacao, nao dado de execucao.
- `contextCandles` (minimo 60 por contrato atual) serve para AMBOS os timeframes do contexto: requer ao menos **60 semanas fechadas de historico**, alem dos candles diarios de aquecimento e estudo. Sem esse historico, a simulacao falha claramente, em vez de usar semana parcial.

## AINDA NAO DEFINIDOS
- Duracao de backtest OOS, criterio de aceitacao, valores de filtros, parametros de risco, alavancagem, custos, multiplicadores de volume/ATR, buffers, limite de holding e stop direto. Todos seguem fornecidos pelo usuario no JSON de `IndependentResearchRequest` / `IndependentSetupParameters`, sem defaults para estudo.
- Escolha entre suportes e ancoras de stop direto, alvo1, parciais, arbitragem combinada e SHORT permanecem em estudo. Nenhuma ordem real e enviada.

## Inicializacao local
No PowerShell do repositorio, na branch `feat/independent-trend-pullback-range-breakout-5m`:

```powershell
git fetch origin
git switch feat/independent-trend-pullback-range-breakout-5m
git pull --ff-only
docker compose up -d mysql
.\scripts\start-backtest.ps1 -IncludeDailyWeekly
```

O switch `-IncludeDailyWeekly` habilita historico `1d,1w`, **sem alterar** os defaults legados `1m,5m,15m` de inicializacao. O processo permanece PAPER_ONLY, ordens=false, Luna=false.

### Sincronizar historico com REST local
Outra janela PowerShell, com a aplicacao rodando:

```powershell
.\scripts\sync-independent-daily-weekly.ps1 -Symbols BTCUSDT,SOLUSDT,DOGEUSDT -FromUtc 2025-01-01 -UntilUtc 2026-10-15 -PrintOnly
.\scripts\sync-independent-daily-weekly.ps1 -Symbols BTCUSDT,SOLUSDT,DOGEUSDT -FromUtc 2025-01-01 -UntilUtc 2026-10-15
```

**As datas acima sao apenas exemplos de backfill**, nao periodo de estudo aprovado. Cada chamada REST `/api/v1/market-data/sync` sincroniza ate 30 dias; a semana e alinhada a segunda-feira UTC. Se a Testnet nao fornecer semanas antigas, o estudo deve relatar insuficiencia (nao preencher dados artificiais).

### Solicitar o backtest
`POST http://127.0.0.1:8080/api/v1/research/independent-setups/backtest`.

No corpo JSON `IndependentResearchRequest`:
- `symbol`: par Futures USDT configurado
- `entryInterval`: `"1d"`
- `trendInterval`: `"1w"`
- `steps`: quantidade **explicita** de candles diarios do periodo negociavel
- `contextCandles`: historico (aplicado aos dois timeframes, inclusive semanas)
- `asOf`: fechamento UTC em milissegundos do **ultimo candle diario fechado**
- `parameters`: objeto com TODOS os atributos de `IndependentSetupParameters` explicitos
- Capital, risco/operacao, stop diario, alavancagem, taxas, spread, slippage, funding, margem de manutencao e filtros de contrato continuam obrigatorios.

`POST .../walkforward` recebe `{"windows":[request1, request2, ...]}`; os estudos OOS nao podem sobrepor dias de operacao e precisam manter todos os custos/limiares congelados.

## Testes/regressao
- `IndependentSetupAcceptanceTest`: A01–A08, B01–B07, E01–E05 continuam aplicaveis aos detectores isolados;
- `IndependentDailyWeeklyTest`: exige 1w para entrada 1d, rejeita semana incompleta/futura, valida 5m e 1d, cobra 3 funding hipoteticos por 1d e detecta OOS sobreposto em dias;
- `test-independent-daily-weekly-plan.ps1`: garante startup antigo inalterado, opcao 1d/1w, sem ordens e script semanal alinhado a segunda-feira;
- `mvn -B -ntp verify` no workflow CI.

Nenhuma avaliacao OOS real foi executada neste PR, pois OHLCV reside no banco local do usuario.
