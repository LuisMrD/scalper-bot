# Dois setups de continuacao de tendencia — pesquisa 5m (branch isolada)

**Fonte:** documento original `analise_setups_video_Nd7cCEIhRy0.md`, baseado em evidencias visuais do vídeo Nd7cCEIhRy0, nao em transcricao integral de audio. O video demonstra exemplos em outros timeframes/ativos; 5m em cripto e **adaptacao experimental**. Nenhuma rentabilidade ou regra numerica e atribuida ao apresentador.

## EXTRAIDO DO VIDEO (evidencia qualitativa)
- **A, tendencia/pullback:** alta previa com topos/fundos ascendentes; retorno para regiao MM9/21 (sobretudo 21); vendedores com volume e amplitudes em contracao; fundo anterior preservado. Tocar a media nao compra. Tres alternativas de confirmacao: candle comprador de forca, martelo de rejeicao, superacao da maxima anterior, acompanhadas de volume de compradores. Stop abaixo do recuo; apos avanço, subir stop sob fundos ascendentes. Vetoes: venda forte, volume muito alto contra tendencia, perda do fundo.
- **B, consolidacao:** alta previa, MME21 apontando para cima; caixa estreita com range e volume menores. Resistencias definidas antes do breakout. O candle deve **fechar acima** da resistencia congelada e ter volume **acima da media anterior**; pavio sem fechamento nao vale. Entrada direta ou esperar suporte no reteste do topo antigo. Stop no reteste abaixo da menor minima do proprio reteste. Fechamento abaixo da base da caixa invalida a tese; voltar para dentro e alerta.
- **Somente LONG** nos exemplos descritos. Nao presumir estrategia SHORT espelhada.

## PROPOSTAS DE ENGENHARIA (revisaveis)
- Entrada somente em candles 5m fechados; opcao obrigatoria de tendencia maior `trendInterval`: `15m`, `1h` ou `4h` (a escolha e do usuario).
- EMA9/EMA21 como referencias de interesse; EMA21 ascendente para caixa. Pivos confirmados com candles a direita concluidos, nunca no tempo original do fundo sem confirmacao.
- Pullback exige perda de volume/corpos contra impulse, reacao de compradores e stop na minima do recuo. Comprador cresce via `takerBuyQuoteVolume` em relacao ao candle anterior; **proxy de agressao, nao garantia**.
- Caixa congelada como max(high)/min(low) dos `consolidationBars` candles **anteriores** ao breakout, com compressao entre metades anterior/posterior. Largura comparada ao ATR com periodo explicito. Volume do breakout comparado a `volumeLookbackBars` PREVIOS (breakout nao altera sua propria referencia).
- Stop da variante direta **configuravel**: `BOX_BASE` ou `BREAKOUT_CANDLE_LOW`. Para reteste: minima efetiva dos candles do reteste, nao minima do breakout.
- Sinal no close -> paper fill no proximo open, com spread+slippage adversos; stop-first no OHLC; trailing so apos `trailingActivationR` e sob LOW confirmado, sem afrouxamento.
- Tres carteiras **separadas**: `TREND_PULLBACK`, `RANGE_BREAKOUT`, `RANGE_BREAKOUT_RETEST`. Modo combinado permanece BLOQUEADO; E03 testa esta salvaguarda nesta etapa, NAO uma arbitragem ja aprovada.
- Paper sizing por distancia ao stop, custo estimado, riskPct, saldo, limite de perda diaria, alavancagem e filtros tick/quantity/minNotional; simulacao usa custos percentuais explicitamente fornecidos.
- `assumedFundingPer8hPct` e `assumedMaintenanceMarginPct` sao HIPOTESES EXPLICITAS. A estimativa de liquidacao nao equivale ao preco de liquidacao real da Binance (tier/notional/margin/mark price). Sem dados historicos de funding, *nao* afirmar que o valor foi observado.
- Eventos e parametros por pesquisa persistidos em MySQL via Flyway V3 (a migration V2 original de métricas é preservada). Logs, risco, estrategia Java/Luna e backtests preexistentes nao sao modificados.

## AINDA NAO DEFINIDOS — grade de parametros exclusivamente PARA TESTAR

| Hipotese configuravel | Grade inicial proposta | Nao inferir do video |
|---|---|---|
| Trend maior | 15m / 1h / 4h | a escolha quantitativa para cripto |
| Pivo (left/right) | 2/2, 3/3, 4/4 | criterio exato de swing |
| Recuo min-max | 2-3, 3-5, 4-7 candles | duracao saudavel |
| Tolerancia na regiao EMA9/21 | 0,1%, 0,25%, 0,5% | distancia ate media |
| Volume medio relativo no recuo | <=0,70 / 0,85 / 1,0 do periodo previo | contracao |
| Corpo medio do recuo | <=0,70 / 0,85 / 1,0 do periodo previo | 'candles menores' |
| Candle de forca, corpo/range | >=0,55 / 0,65 / 0,75 | 'candle forte' |
| Martelo, pavio inferior/corpo | >=1,5 / 2,0 / 2,5 | qualidade da rejeicao |
| Crescimento `takerBuyQuoteVolume` | >1,05x / 1,20x / 1,50x do candle anterior | pressao compradora |
| Venda anormal | >=1,5x / 2,0x / 2,5x do volume precedente | exaustao da tese |
| Caixa | 6 / 10 / 14 candles | extensao minima |
| Largura maxima | 1,0 / 1,5 / 2,0 ATR | estreiteza |
| Contracao de range e volume entre metades | <=0,70 / 0,85 / 0,95 | qualidade da pausa |
| ATR e volume historico | 7/14/21 e 10/20/30 candles | periodos de referencia |
| Volume do rompimento | estritamente >1,0 / 1,3 / 1,6x da media anterior | 'acima da media' |
| Maximo para reteste | 3 / 6 / 9 candles | timeout |
| Tolerancia do reteste | 0,05% / 0,20% / 0,40% | suporte no antigo topo |
| Stop direto | BOX_BASE / BREAKOUT_CANDLE_LOW | ancora ainda aberta |
| Buffer do stop | 0 / 1 / 2 ticks | buffer nao definido |
| Ativacao do trailing | 0,5 / 1 / 1,5 R | regra antes do primeiro alvo |
| Max holding | 12 / 24 / 48 candles | limite de tempo |

**Capital, % risco/trade, stop diario, alavancagem, preco tick, quantity step, min notional, fees, spread, slippage, funding, maintenance margin e limite de custo/risco NAO possuem valores padrao.** Fornecer explicitamente em cada `IndependentResearchRequest` (DTO record) ou na grade experimental validada pelo usuario. Nao executar o produto cartesiano indiscriminadamente; congelar poucas hipoteses **antes** de cada OOS.

## Endpoints adicionados (somente pesquisa)
- `GET /api/v1/research/independent-setups/status`: modo PAPER_ONLY, execucao real desabilitada.
- `POST /api/v1/research/independent-setups/backtest`: JSON conforme `IndependentResearchRequest`, tres portfolios independentes, filtro de tendencia maior, sinais/rejeicoes, P&L marcado, taxas/funding hipoteticos; persiste `runId`.
- `POST /api/v1/research/independent-setups/walkforward`: `{"windows":[request1,request2,...]}`. De 2 a 24 janelas OOS explicitas, sem sobreposicao, com mesmas regras/custos em todas; recompila resultados independentemente por janela e reporta expectancy liquida, profit factor, pior drawdown e numero de trades. **Nenhuma otimizacao no holdout**.

O asOf informado e o horario de fechamento UTC da ultima vela 5m do estudo. `steps` sao candles negociaveis; `contextCandles` e aquecimento somente. O request retorna erro se qualquer candle 5m/higher TF estiver aberto/ausente ou se faltar configuracao. A etapa exige MySQL e historico local adequado; nenhum saldo ou resultado financeiro e inventado.

## Cobertura
`IndependentSetupAcceptanceTest` nomeia e exercita **A01–A08, B01–B07 e E01–E05**, mapeados a secao 9 do documento. E03 confirma que arbitragem combinada permanece DESLIGADA (testar risco agregado quando o modo for implementado/aprovado). Os testes sinteticos validam invariantes; nao demonstram edge de mercado. Regressao Java/Maven completa obrigatoria.

## Protocolo OOS / walk-forward
1. Fixar grade reduzida de parametros no periodo de desenvolvimento sem ajustar ao holdout.
2. Separar janelas cronologicas totalmente sem sobreposicao por ativo e regime; taxa, spread, funding e slippage identicos entre brazos comparados.
3. Medir P&L liquido fechado, marcado, trades por janela, win rate, payoff, expectancy, profit factor, maxDD, e filtros rejeitados; considerar spreads e funding nao-historicos como limitacao.
4. Comparar setups A e B **independentes** com baseline original (e Bollinger somente quando seu PR for incorporado por decisao explicita). Nao combinar/atribuir retorno ao novo setup antes de arbitragem e risco agregado aprovados.
5. Rejeitar estrategias sem amostra suficiente, com comportamento instavel por moeda ou cuja suposta vantagem desapareca com custos.
