# Etapa 10 — Backtest comparativo (simulacao de pesquisa, nao trading)

## Regras temporais

- `POST /api/v1/backtest/run`, nunca GET (pode gerar chamadas pagas quando `includeLuna=true`).
- O servidor le `steps` candles fechados do **primeiro timeframe**, ordena cronologicamente e valida cada lacuna. Para cada candle, carrega o contexto multi-timeframe ate **seu closeTime**, nunca depois.
- Ambos os motores compartilham o MESMO objeto imutavel `MultiTimeframeMarketContext`. Apenas `PositionState` difere, pois as carteiras sao independentes.
- Uma decisao no fechamento de T so executa na **abertura do candle T+1**. O ultimo candle da janela nao gera decisao (nao existe proximo candle para executar).
- Stops vigentes sao avaliados com `low/high` de cada candle, e gaps sao liquidados pelo pior entre preco de abertura e stop. Quando o sequenciamento OHLC e desconhecido, escolhemos **stop-first**, que pode ser pessimista.
- Ajustes de stop decididos no fechamento passam a valer no proximo candle. O motor de risco revalida margem e risco no proximo preco de entrada e pode recusar o sinal.

## Custos e P&L

- Fee e slippage configurados **por lado**, aplicados nas duas execucoes. Entrada de LONG recebe slippage acima do `open`; SHORT abaixo. Saidas tem direcao adversa inversa.
- O caixa inicial e `TRADING_CAPITAL_USDT` explicitamente definido; `JAVA` e `LUNA` comecam cada um com a mesma banca **separada**.
- Equity ao final inclui realizado e valor mark-to-market de posicao aberta, subtraindo estimativa de custo de liquidacao. Nao fecha a posicao artificialmente no ultimo candle.
- Reset da perda diaria e feito em UTC; taxa de entrada e saida reduz caixa. A classificacao de ganhos/perdas usa PnL liquido apos taxas da entrada e saida.
- Drawdown calculado pelos pontos de fechamento da serie, nao intrabar. A estimativa ignora funding, spread extra, liquidacao e possivel falta de profundidade de book.

## Luna: protecao de custos

- `LUNA_ENABLED=false` (padrao): nenhuma chamada OpenAI. Mesmo quando `true`, so executa com `includeLuna=true` **no POST** e chave configurada.
- `includeLuna=true` limita `steps` a 10 e realiza no maximo `steps-1` chamadas pagas; esse numero e para smoke/piloto e **nao** e amostra estatistica de rentabilidade.
- Quando desabilitado, `lunaPortfolio=null` e `comparativeResultsAvailable=false`: o sistema NAO inventa trades do Luna nem copia as decisoes do Java.
- Falhas na OpenAI nao sao substituidas por resultados Java. Nenhum destes endpoints envia ordens.

## Configuracao necessaria (sem defaults financeiros)

`TRADING_CAPITAL_USDT`, `TRADING_RISK_PER_TRADE_PCT`, `TRADING_DAILY_STOP_PCT`, `TRADING_LEVERAGE`, `RISK_FEE_PER_SIDE_PCT`, `RISK_SLIPPAGE_PER_SIDE_PCT`. `RISK_BREAK_EVEN_TRIGGER_R` e `RISK_STRUCTURAL_ATR_BUFFER` seguem a configuracao de risco existente.

Filtros `priceTick`, `quantityStep`, `minimumQuantity` e `minimumNotionalUsdt` devem ser retirados de `/fapi/v1/exchangeInfo` do instrumento e ambiente utilizados. Os valores no exemplo abaixo **nao sao especificacoes verificadas da Binance**.

```powershell
$body = @{
  symbol='BTCUSDT'
  intervals=@('1m','5m','15m')
  steps=30
  contextLimit=200
  includeLuna=$false
  priceTick='0.1'
  quantityStep='0.001'
  minimumQuantity='0.001'
  minimumNotionalUsdt='5'
} | ConvertTo-Json
Invoke-RestMethod -Method Post -ContentType 'application/json' -Body $body `
  'http://127.0.0.1:8080/api/v1/backtest/run'
```

A coleta anterior deve cobrir `contextLimit` candles **para cada timeframe**, inclusive ANTES do primeiro candle da janela de replay. Se houver lacunas ou dados insuficientes, o teste falha em vez de interpolar.

## Limitacoes

- Replays online a partir do historico MySQL disponivel; nao faz download de dados automaticamente.
- Sem persistencia de carteira, ordens ou resultados. Sem otimizacao/hyperparameter tuning.
- Custos nominais e mercado Testnet nao sao evidencias de rentabilidade real.
- Futuros podem liquidar antes do stop. Liquidacao/funding/risco de contraparte ainda nao sao simulados.
- Sem validacao contra dados historicos de producao ainda. Use so como verificacao de codigo ate incorporar esse conjunto de dados e modelar latencia/liquidez.
