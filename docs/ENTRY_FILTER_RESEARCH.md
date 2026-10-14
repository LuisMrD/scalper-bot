# Experimento RETEST 5m: filtros de entrada por BreakoutState

Escopo: **pesquisa local na Binance Futures Testnet**, exclusivamente motor Java.
Nenhuma ordem real, chamada Luna, alteracao de configuracao global ou migracao de banco.

## Hipoteses pre-registradas

- `BLOCK_SHORT_DOWN_CANDIDATE`: rejeita sinal RETEST SHORT quando o estado atual do analisador Brooks for `DOWN_CANDIDATE`.
- `BLOCK_LONG_NONE`: rejeita sinal RETEST LONG quando `BreakoutState=NONE`.
- `BLOCK_BOTH`: combina ambos.
- `NONE`: controle imutavel; segue a logica anterior.

O filtro eh consultado **apenas quando JavaDecisionEngine sinaliza uma nova entrada BREAKOUT_RETEST**. Nenhuma posicao existente ou operacao Luna eh filtrada. O estado eh capturado do candle **fechado** que gerou o sinal, nunca de barras futuras. Sinais bloqueados viram WAIT com motivo `ENTRY_BREAKOUT_FILTER`.

## Comparacao dinamica

`POST /api/v1/backtest/compare-entry-filters` recebe o JSON ja utilizado por `/api/v1/backtest/run`, com `intervals=["5m","15m"]`, `includeLuna=false`, `pullbackEnabledOverride=false` e `breakoutRetestEnabledOverride=true`.

Quatro carteiras completamente independentes sao simuladas. Cada entrada recusada altera a disponibilidade da carteira para entradas seguintes; stops, margens, custos e drawdown sao calculados novamente. Nenhum resultado eh estimado por subtracao de trades antigos.

A resposta inclui quatro resumos de cenario, quantidade de sinais recusados e as operacoes reais de cada replay. A soma dos `netPnlUsdt` das operacoes encerradas reconcilia com `netClosedUsdt` do mesmo cenario. O resultado marcado inclui posicoes abertas. O numero de sinais recusados nao equivale ao numero de trades que desaparecem (podem surgir entradas diferentes).

## Executar

1. Fazer pull da branch `experiment/1m-vs-5m-trade-diagnostics` e reiniciar Spring Boot apos compilacao.
2. Garantir a existencia de `results/entry-signal-windows-SOLUSDT-RETEST-1791845999999-5m-w1-9.csv`, proveniente da auditoria anterior.
3. Visualizar o plano: `./scripts/compare-entry-filters.ps1 -PrintOnly`.
4. Executar `./scripts/compare-entry-filters.ps1 -Symbol SOLUSDT -Windows 9 -AsOf 1791845999999 -ExpectedCapitalUsdt 1500`.

Saidas: `entry-filter-aggregate-*.csv`, `entry-filter-windows-*.csv`, `entry-filter-trades-*.csv`. Resultados das nove janelas **nao constituem validacao fora da amostra**: esses mesmos dados revelaram os padroes usados para criar os filtros. Apos estudar as diferencas no replay dinamico, reservar datas novas sem ajustar os filtros a elas.

Metricas prioritarias: PnL marcado e fechado apos taxas, drawdown, quantas janelas melhoraram, quantidade de sinais rejeitados, quantas operacoes novas surgiram e maiores vencedoras perdidas.

## Auditoria das entradas substitutas

O comparador agora exporta tambem o snapshot `entryFeatures` do momento da entrada
para cada trade encerrado: tendencia primaria e 15m, volume relativo, agressao,
estado do rompimento, candle de sinal, stop inicial e custo estimado em relacao ao risco.

Ao executar `scripts/compare-entry-filters.ps1`, depois dos quatro replays, o
analisador offline `scripts/audit-replacement-entries.ps1` identifica tres grupos
usando a chave deterministica `(Window, Side, EnteredAtOpenTime)`:

- `REMOVED`: estava na carteira original (`NONE`), nao na `BLOCK_BOTH`.
- `PRESERVED`: a mesma entrada ocorreu em ambos os replays (pode ter PnL diferente).
- `NEW`: aparece no `BLOCK_BOTH`, mas nao no controle original.

A saida usa tres novos CSVs: `entry-filter-replacement-trades-*.csv`
(com snapshots e rotulos), `entry-filter-replacement-windows-*.csv`
(reconciliacao das entradas/pnl por janela), e `entry-filter-replacement-patterns-*.csv`
(distribuicoes de ganhos/perdas por caracteristica, separadas por grupo).

O validador exige, para cada janela:

`deltaPnL = PnL(NEW) - PnL(REMOVED) + deltaPnL(PRESERVED)`

e confronta com a diferenca entre PnL de trades encerrados de `BLOCK_BOTH`
e `NONE`. Isso NAO reconcilia automaticamente PnL marcado de posicoes ainda
abertas; o analista deve olhar tambem as colunas de equity marcada dos CSVs
de janelas. A lista de entradas refere-se somente a operacoes encerradas.

Pode reprocessar os CSVs offline sem reexecutar os backtests:

`./scripts/audit-replacement-entries.ps1 -Symbol SOLUSDT -Windows 9 -AsOf 1791845999999`

`EntryBreakoutState`, Price Action, agressao, volume, stop e custo sao atributos
conhecidos quando a entrada foi decidida ou preenchida. `Outcome`, `DurationCandles`,
`MfeR`, `MaeR`, `NetRMultiple` e `ExitReason` sao valores
do futuro e **jamais devem ser usados para decidir a entrada**.

Bandas de volume e custo sao descritivas e nao filtros aprovados. Grupos com menos
de cinco trades sao identificados como amostras pequenas. Nenhum filtro
adicional deve ser introduzido sem nova validacao historica fora da amostra.

## Experimento de espera de reentrada apos BLOCK_BOTH

Hipotese gerada a partir das **34 novas entradas** em 9 janelas inspecionadas:
17 ocorreram com tendencia 15m em `RANGE` e encerraram com perda.
Nao utilizar esse resultado para supor causalidade ou validade fora da amostra.

Endpoint exclusivamente de pesquisa:
`POST /api/v1/backtest/compare-reentry-waits` com o mesmo JSON do
backtest original (`intervals=["5m","15m"]`, `includeLuna=false`,
`pullbackEnabledOverride=false`,
`breakoutRetestEnabledOverride=true`). O endpoint recusa outros setups/timeframes.

Executa **quatro replays Java completos independentes**, todos com
`EntryBreakoutFilter.BLOCK_BOTH` ativo, sem chamar Luna ou enviar ordens:

- `NONE`: controle exato da estrategia `BLOCK_BOTH`, sem espera adicional.
- `WAIT_WHILE_15M_RANGE`: quando BLOCK_BOTH rejeita um sinal **enquanto o 15m
  fechado indica RANGE**, arma um bloqueio para novos RETESTs ate o primeiro
  fechamento de 5m em que o snapshot mais recente do 15m deixa RANGE. Se
  a rejeicao ocorre com 15m direcional, nao ha bloqueio por RANGE.
- `WAIT_3_BARS`: apos uma rejeicao de BLOCK_BOTH, rejeita os proximos
  tres sinais/candles de 5m enquanto a carteira estiver livre,
  independente da tendencia 15m. Equivale a 15 minutos de candles fechados.
- `WAIT_12_BARS`: mesmo mecanismo durante 12 candles de 5m (60 minutos).

Em ambos os controles de tempo, um novo sinal que tambem seja rejeitado
por BLOCK_BOTH reinicia o contador. A rejeicao original sempre tem prioridade
sobre a espera. Sinais durante espera recebem `ENTRY_REENTRY_WAIT`, distinguindo-os
do motivo `ENTRY_BREAKOUT_FILTER`. A contagem `rejectedByReentryWait` conta
sinais efetivamente recusados pelo tempo/estrutura, NAO numero de candles
aguardados. Nenhuma posicao aberta eh encerrada por esse experimento.

O estado pertence unicamente a cada replay, reiniciado por janela e
avaliado sempre depois do fechamento do candle. O snapshot 15m jamais
inclui candles futuros. Entradas subsequentes, dimensionamento, stop,
taxas, exposicao e drawdown sao recalculados por carteira.

Executar apos sincronizar os candles originais:

`./scripts/compare-reentry-waits.ps1 -Symbol SOLUSDT -Windows 9 -AsOf 1791845999999 -ExpectedCapitalUsdt 1500`

Requer o CSV anterior de baseline `entry-filter-windows-...` para confrontar
exatamente o PnL fechado, PnL marcado e numero de trades de
`BLOCK_BOTH`. Produz:

- `results/reentry-wait-aggregate-*.csv`: PnL, melhoria por janela, sinais rejeitados
- `results/reentry-wait-windows-*.csv`: custos, PnL marcado, drawdown e entradas por periodo
- `results/reentry-wait-trades-*.csv`: trades realmente executados por cenario, com snapshot de entrada

As 9 janelas ja serviram para identificar a hipotese e sao **exploratorias**,
nao OOS. Nao declarar lucro demonstrado nem escolher parametrizacao definitiva
por esses resultados: reservar periodos nao vistos para confirmacao.

## Validacao independente: regras congeladas em janelas 10..15

Pre-registro: os resultados das janelas 1..9 sao apenas exploratorios.
Nao ajustar criterios apos observar as janelas 10..15. Executar tres
alternativas ja definidas antes desta etapa, todas com RETEST e BLOCK_BOTH:

1. `NONE`: controle com os dois filtros, sem espera.
2. `WAIT_WHILE_15M_RANGE`: trava reentrada ate contexto 15m nao RANGE
   depois de uma rejeicao que ocorreu em RANGE.
3. `WAIT_12_BARS`: espera fixa de 12 candles fechados de 5m (60 minutos).

O endpoint Java continua devolvendo uma quarta alternativa para compatibilidade,
mas o runner OOS ignora `WAIT_3_BARS` e exporta apenas as tres acima.

Amostra: seis periodos de 300 horas nao sobrepostos, **indices 10, 11, 12, 13,
14 e 15**, alinhados com o `AsOf=1791845999999`. Historico total 1800 horas
(75 dias), sem reutilizar nenhuma barra de resultado das janelas 1..9.
Dados de contexto anteriores a janela sao usados somente para aquecimento,
sem PnL durante o aquecimento.

O novo endpoint somente leitura `GET /api/v1/backtest/research-config`
permite ao runner conferir parametros efetivos, nao apenas o YAML que pode
ser sobrescrito por variaveis de ambiente. O validador exige:

- `SOLUSDT` configurado; perfil DISABLED ou BACKTEST; ordens bloqueadas.
- Fonte TESTNET; websocket e bootstrap desativados; 5m e 15m habilitados.
- Capital 1500, risco 0.5%, stop diario 3%, alavancagem 1.
- Fee por lado 0.05%, slippage por lado 0.02%, BE 1R,
  buffer estrutural 0.5, maxCostToInitialRiskPct 50.
- `JAVA_DECISION_REQUIRE_HIGHER_TF=false`,
  `JAVA_DECISION_REQUIRE_TAKER=true`, breakoutLookback 10,
  retestMaxBars 4, maxPrimaryStalenessBars 2.

O request fixa `breakEvenTriggerROverride=1.0`,
`maxCostToInitialRiskPctOverride=50`, RETEST-only, candles 3600,
timeframes [5m,15m], sem Luna, para cada cenario.

Ver plano sem HTTP:

`./scripts/validate-reentry-oos.ps1 -PrintOnly -StartWindow 10 -Windows 6`

Executar apos iniciar Spring Boot com as configuracoes congeladas:

`./scripts/validate-reentry-oos.ps1 -Symbol SOLUSDT -Source testnet -StartWindow 10 -Windows 6 -AsOf 1791845999999 -ExpectedCapitalUsdt 1500 -SyncHistory`

A flag `-SyncHistory` recupera historico PUBLICO da Testnet somente em 5m/15m,
em lotes de ate 7 dias. Se a Testnet nao oferecer historico completo, parar
e registrar indisponibilidade, **nao substituir silenciosamente por producao,
outro simbolo ou candles artificiais**. Repeticoes podem omitir `-SyncHistory`
se os dados ja estiverem completos no MySQL local.

Os tres CSVs sao:

- `results/reentry-oos-aggregate-SOLUSDT-RETEST-1791845999999-5m-w10-15.csv`
- `results/reentry-oos-windows-SOLUSDT-RETEST-1791845999999-5m-w10-15.csv`
- `results/reentry-oos-trades-SOLUSDT-RETEST-1791845999999-5m-w10-15.csv`

Analise prioriza PnL marcado e fechado, delta por janela,
quantas janelas tiveram melhoria, drawdown, taxas, entradas perdidas
e novas entradas, alavancagem/risco efetivos. Os resultados nao garantem
viabilidade futura e o PnL de janelas reiniciadas nao e retorno composto.

## Analise observacional da macro BTC/ETH nas entradas SOL

Objetivo: testar **consonancia contemporanea**, nao filtrar nem alterar
a estrategia. O resultado das operacoes ja e conhecido e os 15 periodos
anteriores ja foram inspecionados. Todas as associacoes deste estudo sao
**retrospectivas**; mesmo as janelas 10..15 NAO sao uma nova validacao
fora da amostra da hipotese de consonancia.

Dados: trade CSVs de reentrada das janelas 1..9 e 10..15 (original
`BLOCK_BOTH`, `WAIT_WHILE_15M_RANGE`, `WAIT_12_BARS`) e candles
TESTNET 15m de BTCUSDT e ETHUSDT gravados no MySQL local. Nao usa
candle atual incompleto, mercado spot, ordens ou Luna. A configuracao
precisa incluir `TRADING_SYMBOLS=SOLUSDT,BTCUSDT,ETHUSDT` para permitir
sincronizar/ler esses pares. Isto NAO habilita negociacao.

Para cada fechamento do sinal de entrada SOL:
- localizar o ultimo candle BTC/ETH de 15m com fechamento <= sinal SOL;
- computar retornos percentuais acumulados nas 4 barras anteriores (1h),
  nas 16 barras anteriores (4h) e nas 96 barras anteriores (24h);
- invalidar a janela caso exista gap entre barras, atraso >=15min ou
  timestamps BTC/ETH dessincronizados;
- classificar retornos positivos/negativos por direcao da entrada
  SOL (LONG e SHORT), independentemente de resultados posteriores.

Classes:
- `CONSONANT_BOTH`: BTC e ETH se moveram na direcao da entrada SOL.
- `CONTRARY_BOTH`: ambos se moveram contra a direcao da entrada SOL.
- `MIXED`: um concorda, outro discorda.
- `NEUTRAL`: um dos retornos e exatamente zero.

Tambem mostrar `BTC_ETH_UP`, `BTC_ETH_DOWN`,
`BTC_ETH_SPLIT` e `NEUTRAL` independentemente da direcao SOL.
Retornos continuos ficam no CSV detalhado, permitindo analises posteriores
sem criar limiares otimizados no historico.

Executar com Spring Boot local e TESTNET (somente leitura/market-data):

`./scripts/analyze-cross-asset-macro.ps1 -PrintOnly`

`./scripts/analyze-cross-asset-macro.ps1 -Symbol SOLUSDT -StartWindow 1 -Windows 15 -AsOf 1791845999999 -SyncHistory`

`-SyncHistory` importa BTC/ETH 15m ate 25h antes do inicio da janela
mais antiga e ate o final da janela mais recente, em lotes de 7 dias.
Os candles da SOL ja constam do CSV, portanto nao sao sincronizados
novamente neste estudo. Em execucoes posteriores, omitir o parametro.
Se nao houver BTC/ETH 15m historico suficiente na Testnet, o estudo
PARA, sem recorrer silenciosamente a fontes reais, candles inventados
ou valores futuros.

Saidas:
- `results/macro-consonance-trades-SOLUSDT-1791845999999-w1-15.csv`
- `results/macro-consonance-patterns-SOLUSDT-1791845999999-w1-15.csv`
- `results/macro-consonance-windows-SOLUSDT-1791845999999-w1-15.csv`

O relatorio agrupa LONG/SHORT separadamente, horizontes 1h/4h/24h,
politicas de reentrada e janelas 1..9, 10..15 e agregadas. Agrupamentos
com menos de 10 trades ou menos de tres janelas sao sinalizados como
amostra pequena. PnL ja inclui custos. A soma dos trades fechados
deve reconciliar exatamente ao CSV previo. Cada janela parte do capital
original; somar os PnLs nao significa compor um portfolio unico.

Atencao: um conjunto de 15 janelas consecutivas, operacoes serialmente
correlacionadas e retornos com intervalos sobrepostos NAO gera centenas
de observacoes independentes. Para usar consonancia em uma regra de
entrada, exigir um novo replay dinamico e um holdout historico inedito.
A historica da Binance Futures Testnet pode nao reproduzir fielmente
o comportamento do mercado real de BTC/ETH.

## Politica experimental de parametros 3, 6 e 9 (2026-10-14)

Decisao explicita: **novos parametros de pesquisa** devem usar a
familia decimal 3/6/9: por exemplo, 3, 6, 9, 30, 60, 90, 300,
600, 900, 3000 etc. O codigo `MagicResearchNumbers` valida
inteiros positivos removendo zeros finais e exigindo 3/6/9.
`60` minutos continua sendo o cooldown valido usado no controle.
Isso e uma **convencao de selecao de parametros**, nao uma premissa
estatistica ou causal: valores da familia nao possuem vantagem
demonstrada por sua representacao decimal.

Nao modificar retroativamente taxas, capital, stops, alavancagem,
candles 5m, arquitetura ou regras de seguranca para adaptar os numeros:
a restricao se aplica a **novas hipoteses**, mantendo controles
comparaveis. Documentar cada variacao antes de testar e avaliar
custos, trades, perdas, drawdown e consistencia fora da amostra.

### Teste congelado de macro BTC/ETH em SOL LONG

A associacao historica encontrada para macro +24h e dip -1h foi
exploratoria; como 1h/24h nao pertencem a familia decidida,
**este teste NAO reproduz exatamente os horizontes anteriores**.
Ele pre-registra os horizontes novos 3h/6h/9h e dip 3h,
sem escolher valores olhando os resultados.

Todos os seis cenarios usam a estrategia SOL RETEST 5m/15m,
`EntryBreakoutFilter.BLOCK_BOTH` e `ReentryWaitPolicy.WAIT_12_BARS`
(60 min). Somente sinais LONG que passam pelos filtros anteriores
sao avaliados; **sinais SHORT nunca sao bloqueados pelo macro**.

- `NONE`: controle inalterado.
- `BOTH_UP_3H`: BTC e ETH estritamente positivos em 3h.
- `BOTH_UP_6H`: BTC e ETH estritamente positivos em 6h.
- `BOTH_UP_9H`: BTC e ETH estritamente positivos em 9h.
- `BOTH_UP_6H_DIP_3H`: ambos positivos em 6h E negativos em 3h.
- `BOTH_UP_9H_DIP_3H`: ambos positivos em 9h E negativos em 3h.

Igualdade a zero falha no teste de tendencia e no teste de dip.
O calculo usa 15m fechados do MySQL TESTNET:
3h = 12 barras, 6h = 24 barras, 9h = 36 barras
anteriores ao fechamento do sinal da SOL. Nao ha lookahead;
lacunas ou timestamps defasados invalidam o replay. Cada politica
possui sua propria simulacao dinamica do livro de ordens e stops,
incluindo custos. Nenhum filtro e ligado ao motor Java normal.

Endpoint: `POST /api/v1/backtest/compare-magic-macro`
recebe o mesmo `BacktestRequest` 5m+15m RETEST-only de 3600 candles,
`includeLuna=false`. Nao envia ordens nem invoca Luna.

### Holdout inedito

O script `scripts/validate-magic-macro-oos.ps1` exige periodos ainda
nao usados nos estudos de selecao (indices a partir de **16**).
Padrao: **6 janelas de 300h, indices 16..21**, retrocedendo a partir
de `AsOf=1791845999999`, cada uma com capital reiniciado de 1500 USDT.
Aceita 3, 6 ou 9 janelas, nunca escolhe numero de janelas olhando
o PnL obtido. O script valida o perfil real de risco, codigo Java,
TESTNET, Luna e ordens desligados, sem ajustar parametros no meio do teste.
Nenhum arquivo dos 15 periodos anteriores e usado na comparacao.

Verificar plano sem rede:

`./scripts/validate-magic-macro-oos.ps1 -PrintOnly`

Executar (Spring Boot local ja iniciado com BTCUSDT/ETHUSDT/SOLUSDT
habilitados e os mesmos parametros financeiros do historico):

`./scripts/validate-magic-macro-oos.ps1 -Symbol SOLUSDT -StartWindow 16 -Windows 6 -AsOf 1791845999999 -ExpectedCapitalUsdt 1500 -SyncHistory`

O `-SyncHistory` tenta importar SOL 5m/15m e BTC/ETH 15m
**somente da Binance Futures TESTNET**, com aquecimento suficiente.
Se a fonte nao disponibilizar esses meses historicos,
o estudo e interrompido: nao usar Binance de producao, candles
inventados ou fontes alternativas sem nova decisao de pesquisa.
Resultados de simulacao TESTNET nao comprovam desempenho real.

Saidas na pasta `results`, com sufixo
`SOLUSDT-RETEST-1791845999999-5m-w16-21.csv`:

- `magic-macro-oos-aggregate-...`
- `magic-macro-oos-windows-...`
- `magic-macro-oos-trades-...`

Analise deve priorizar PnL liquido marcado vs `NONE`,
PnL dos encerrados, drawdown, cobertura por janela, SHORTs
inalterados, novas entradas que surgiram, ganhadoras perdidas,
e maior winner isolado. A soma dos PnLs de 6 carteiras
independentes nao representa rentabilidade composta.

## Confirmacao SHORT_ONLY vs LONG macro (janelas ineditas 22..27)

Pre-registro realizado **antes de observar** o resultado das janelas 22..27,
todas anteriores e sem sobreposicao aos estudos 1..21. Sao seis blocos
independentes de **300 horas** (3600 candles 5m e contexto 15m).
Capital de 1500 USDT reiniciado por janela, risco 0.5% por trade,
stop diario 3%, alavancagem 1, fee 0.05% por lado, slippage 0.02%
por lado, trigger BE 1R e filtro de custo/risco de 50%.

Os **quatro cenarios congelados**:

- `CONTROL`: RETEST com `BLOCK_BOTH + WAIT_12_BARS`, sem filtro macro.
- `SHORT_ONLY`: mesmo controle, mas rejeita todo sinal de entrada LONG
  RETEST depois do bloqueio de rompimento e do cooldown de 60 min.
  **Nao bloqueia entradas SHORT nem altera gerenciamento de posicoes**.
- `BOTH_UP_6H_DIP_3H`: so permite LONG quando retornos fechados
  BTC e ETH sao positivos em 6h e negativos em 3h.
- `BOTH_UP_9H_DIP_3H`: idem com tendencia positiva em 9h e dip negativo em 3h.

Os filtros nao excluem trades de CSV; cada cenario executa o motor
Java e o ledger desde o inicio da sua janela, com seu proprio stop,
equity, custos e capacidade disponivel. O SHORT_ONLY pode executar
novas vendas em timestamps diferentes do controle: as regras SHORT
sao iguais, **as execucoes SHORT nao precisam ser identicas**.

O metodo `runLongVersusShort` em `BacktestService` aceita apenas
SOLUSDT, `intervals=[5m,15m]`, `steps=3600`, RETEST-only,
`includeLuna=false`. O endpoint de pesquisa
`POST /api/v1/backtest/compare-long-vs-short` retorna os quatro
portfolios independentes, contadores para entradas rejeitadas
e PnL separado para LONG e SHORT. O endpoint regular e a estrategia
principal permanecem inalterados.

Parametros novos usam a familia numerica 3/6/9: macro 3h, 6h e 9h;
cooldown 60 min; **6 janelas** e **300h** cada. Sao convencoes,
nao evidencias matematicas de vantagem dos algarismos.

Antes de importar dados, validar as configuracoes efetivas usando
`GET /api/v1/backtest/research-config`. O script exige todos os
custos/risco do estudo, TESTNET, WS/bootstrap desligados e
`TRADING_SYMBOLS` incluindo SOL, BTC e ETH, com ordens/Luna OFF.

Testar plano local sem rede:
`./scripts/validate-long-vs-short-oos.ps1 -PrintOnly`

Executar:
`./scripts/validate-long-vs-short-oos.ps1 -Symbol SOLUSDT -StartWindow 22 -Windows 6 -AsOf 1791845999999 -ExpectedCapitalUsdt 1500 -SyncHistory`

`-SyncHistory` busca apenas na Binance Futures Testnet candles
SOL 5m/15m, BTC 15m e ETH 15m em lotes historicos. Erros de
disponibilidade, candles ausentes ou gaps invalidam a janela;
nao gerar dados artificiais e nao alternar silenciosamente
para producao. Se os candles ja estao completos no MySQL,
executar sem `-SyncHistory`.

O script exporta tres CSVs com sufixo
`SOLUSDT-RETEST-1791845999999-5m-w22-27.csv`:

- `long-vs-short-oos-aggregate-...` (resultado acumulado dos 6 replays
  independentes, melhoria vs controle, trades por direcao)
- `long-vs-short-oos-windows-...` (custos, drawdown, PnL marcado/fechado,
  delta, sinais bloqueados, configuracao efetiva)
- `long-vs-short-oos-trades-...` (operacoes executadas, snapshots de
  entrada e resultados)

Validacoes automaticas: cada portfolio reporta capital de 1500 USDT,
PnL marcado = equity marcada - capital; soma PnL trades = PnL fechado,
soma LONG+SHORT = PnL fechado, SHORT_ONLY nao pode ter trades LONG.
Conferir se os resultados dependem de uma unica janela/winner e se
LONGs selecionados superam a alternativa sem nenhuma compra.

OOS vale apenas para os **quatro cenarios predefinidos**.
Apos inspecionar resultados 22..27, deixa de ser amostra inedita
para qualquer novo ajuste escolhido em funcao deles.

## Diagnostico retrospectivo de SHORTs: tendencia, volume, stops, saidas (2026-10-14)

Problema observado: nas janelas 10..15, perdas SHORT concentradas; nas janelas
22..27, o `CONTROL` registrou 53 SHORTs com PnL fechado agregado de
aproximadamente -149.86 USDT, mais que as perdas das LONGs. A hipotese nao
e de que basta bloquear SHORTs: as janelas 16..21 registraram SHORTs
positivos. A deterioracao pode depender do contexto de mercado e da saida.

Esta etapa e **diagnostico**, nao nova estrategia nem validacao OOS.
Usa apenas periodos ja inspecionados, reexecutando os mesmos controles
`CONTROL` e `SHORT_ONLY` do experimento anterior de forma dinamica,
com `BLOCK_BOTH + WAIT_12_BARS`. Comparar os dois permite distinguir
SHORTs compartilhados de novos SHORTs que surgiram por capacidade de
carteira liberada. As regras SHORT nao mudam entre as carteiras.

Endpoint read-only: `POST /api/v1/backtest/short-regime-diagnostics`.
Exige SOLUSDT 5m+15m, 3600 candles, RETEST-only, `includeLuna=false`.
Cada portfolio e isolado, sem ordens e sem chamadas pagas a IA.

Os diagnosticos sao divididos em categorias com cronologia explicita:

- **Disponiveis na entrada:** tendencia 5m/15m, estado do rompimento,
  volume, agressividade taker, padrao da barra, spread/volume relativo,
  stop inicial estrutural, ATR, distancia percentual do stop, gap
  e custo esperado em relacao ao risco inicial.
- **Somente depois da abertura e saida:** MFE/MAE, R realizado,
  stops ajustados, motivo da saida, duracao, custos realizados,
  melhor PnL em candle fechado e devolucao do melhor resultado
  ate a saida. Estas variaveis NAO podem ser usadas como condicoes
  de entrada sem um segundo estudo causal/temporal apropriado.
- **Comparacao retrospectiva entre carteiras:** entradas `SHARED`,
  `CONTROL_ONLY` e `SHORT_ONLY_ONLY`, por lado e timestamp.
  Este grupo tambem NAO era conhecido no momento do sinal.

As faixas exploratorias 3/6/9 sao usadas somente para **agrupar
distribuicoes**, nao para bloquear sinal:
distancia stop de 0.3/0.6/0.9%,
custo/risco 30/60/90%,
excursao MFE/MAE 0.3/0.6/0.9 R,
duracao 3/6/9 candles de 5m.
As faixas NAO sao calibradas por desempenho.

Executar em Spring Boot TESTNET, com mesmas configuracoes financeiras
dos testes anteriores e candles SOL ja sincronizados:

`./scripts/diagnose-short-regimes.ps1 -PrintOnly`

`./scripts/diagnose-short-regimes.ps1 -StartWindow 22 -Windows 6`

`./scripts/diagnose-short-regimes.ps1 -StartWindow 16 -Windows 6`

Nao precisa sincronizar BTC/ETH (o controle nao consulta macro) nem
importar candles outra vez. Se faltar historico SOL 5m/15m, importar
somente da Testnet antes de rodar; nunca preencher lacunas com candles
sinteticos. Outras janelas anteriores podem ser examinadas com
`-StartWindow` e `-Windows 3|6|9` contanto que nao ultrapassem 27.

Para cada grupo solicitado (por exemplo, `w22-27`) sao gerados:

- `results/short-regime-windows-SOLUSDT-RETEST-1791845999999-5m-w22-27.csv`
- `results/short-regime-trades-SOLUSDT-RETEST-1791845999999-5m-w22-27.csv`
- `results/short-regime-patterns-SOLUSDT-RETEST-1791845999999-5m-w22-27.csv`

O script valida numero de trades e PnL em cada portfolio e lado,
periodos disjuntos, timestamps do sinal anteriores a entrada,
dados de risco efetivos e contagens de sinais bloqueados. Os grupos
marcam `FeatureTiming=ENTRY|OUTCOME|RETROSPECTIVE` e
`SmallSample` quando poucos trades ou menos de 3 janelas.
Media/expectativa negativas por subtipo sao associacoes retrospectivas;
nao filtram entradas e NAO demonstram causalidade.

Interpretacao futura: contrastar w16-21 e w22-27 por
`TREND_15M`, `BREAKOUT_STATE`, `STOP_DISTANCE`,
`VOLUME_LEVEL`, `EXIT_REASON`, excursao em R e timing.
Depois, se surgir uma hipotese estavel, congelar seus parametros 3/6/9
e testa-la em historia NAO utilizada anteriormente com replay completo.

## Experimento Brooks: LOCALIZACAO do SHORT no RANGE do 15m (2026-10-14)

Motivacao: em janelas ja observadas 22..27, perdas das vendas estavam
fortemente concentradas em `trend15m=RANGE`. Entretanto, no bloco 16..21,
vendas sob `RANGE` foram positivas, tornando injustificavel afirmar
que lateralizacao sempre implica venda ruim.

Conceito de Brooks (referencias externas): ranges tendem a alternar
compras/vendas e rejeitar rompimentos; entradas de venda junto ao
**topo** de uma faixa e testes de rompimentos fracassados podem ter
melhor localizacao que vendas no meio/fundo. A leitura oficial de
`Trading Price Action Trends`, capitulo *Horizontal Lines:
Swing Points and Other Key Price Levels*, trata niveis horizontais e
reversoes apos falsas quebras, incluindo segundas entradas:
https://onlinelibrary.wiley.com/doi/10.1002/9781119202592.ch17
O manual Brooks Trading Course reforca evitar trades no terco central
e vender perto do extremo superior de um trading range:
https://www.brookstradingcourse.com/how-to-trade-manual/trading-ranges/

### Tres hipoteses congeladas (nao retocar olhando o OOS)

1. `CONTROL`: RETEST 5m + 15m contexto, BLOCK_BOTH e WAIT_12_BARS
   (60 minutos), sem restricao adicional.
2. `BLOCK_ALL_15M_RANGE_SHORTS`: mesma estrategia, rejeitar apenas
   SHORT em `trend15m=RANGE`; LONGs e SHORTs fora de RANGE iguais
   na definicao de setup.
3. `UPPER_THIRD_OF_30_CLOSED_15M_BARS`: mesma estrategia; quando o
   15m for RANGE, so permitir SHORT se o fechamento do **sinal de 5m**
   estiver no terco SUPERIOR da faixa high/low dos ultimos 30 candles
   15m ja fechados. Quando o 15m nao for RANGE, nao aplicar o filtro.

A formula operacional do terceiro teste e uma **heuristica nossa**:
`(closeSignal5m - minLow30) * 3 >= (maxHigh30 - minLow30) * 2`.
Nao e uma formula numérica de Al Brooks. Nao exige ou cria um
setup de reversao nova, segunda entrada L2 ou ordem limite:
apenas filtra o setup BREAKOUT_RETEST ja existente pela localizacao.
A selecao deve ser marcada **no candle fechado do sinal**, com
fill somente na proxima abertura de 5m, sem ler a faixa atual em
formacao, nem candles futuros. Ausencia de 30 candles 15m contiguos
e fechados, atraso >=15min ou amplitude zero abortam o replay,
ao inves de adivinhar a posicao no range.

`BacktestService.runShortRange` e
`POST /api/v1/backtest/compare-short-range` fazem tres simulacoes
dinamicas e independentes, recalculando capacidade da carteira,
ordens SIMULADAS, stops, fees e risco. Nao alteram endpoints antigos,
motor normal ou posicoes em aberto. O contador de sinais barrados
`rangeShortBlockedSignals` nao significa trades eliminados na mesma
quantidade, pois os replays geram entradas substitutas.

Parametros novos obedecem a familia 3/6/9: **3** estrategias,
**30** candles 15m na faixa, divisoes em **3** partes, cooldown **60**
minutos, **6** janelas de **300** horas (3600 candles 5m por janela).
Trata-se de convencao experimental, nao de pressuposto estatistico
sobre o algarismo.

### Amostra historica reservada

Indices **28..33**, disjuntos de todas as janelas 1..27 ja inspecionadas.
Cada janela reinicia com 1500 USDT, 0.5% risco, stop diario 3%,
alavancagem 1, fee 0.05% por lado, slippage 0.02% por lado,
BE 1R, custo/risco maximo 50%. Mesmo `AsOf=1791845999999`;
sem Luna, sem ordens, TESTNET somente.

`./scripts/validate-short-range-oos.ps1 -PrintOnly`

`./scripts/validate-short-range-oos.ps1 -Symbol SOLUSDT -StartWindow 28 -Windows 6 -AsOf 1791845999999 -ExpectedCapitalUsdt 1500 -SyncHistory`

O `-SyncHistory` importa SOLUSDT 5m e 15m em lotes de ate sete dias
na Binance Futures Testnet, com aquecimento. Historico mais antigo
pode nao existir. Nesse caso parar sem usar futuro, dados de producao
ou sintese. Se ja estiver no MySQL, omitir a sincronizacao.

O script cria arquivos:
- `results/short-range-oos-aggregate-SOLUSDT-RETEST-1791845999999-5m-w28-33.csv`
- `results/short-range-oos-windows-SOLUSDT-RETEST-1791845999999-5m-w28-33.csv`
- `results/short-range-oos-trades-SOLUSDT-RETEST-1791845999999-5m-w28-33.csv`

Analise primaria: delta de PnL marcado vs controle, consistencia
por janela, side-specific closed PnL, taxa de acerto, drawdown,
tamanho da amostra no terco superior, ganhos sacrificados,
trades novos, pior perdedora e concentracao dos vencedores.
Se o filtro de faixa superior resultar em pouquissimos SHORTs,
isso nao estabelece vantagem do PRICE ACTION nem da classificacao
em tercos. OOS vira conjunto inspecionado depois deste experimento.

### Ideia futura nao implementada

Uma variante ainda mais proxima da logica Brooks seria
**false breakout da borda superior + segunda entrada de venda (L2)**
em range, ou aguardando follow-through adverso antes de entrar.
Isto requer um detector causal de *duas tentativas* e a
definicao de pivots confirmados; nao e equivalente a simplesmente
filtrar BREAKOUT_RETEST no topo. Implementar separadamente somente
se a hipotese de localizacao de RANGE demonstrar sinal suficiente
e apos reservar historia nova.

## L2 apos falso rompimento: setup novo e separado (2026-10-14)

Esta implementacao e uma **aproximacao operacional inspirada** na
leitura de falha de rompimento e segunda tentativa de venda descrita
por Al Brooks. Nao e uma traducao integral dos seus setups L2, nem
a simples aplicacao de um filtro sobre `BREAKOUT_RETEST`.

As janelas 1..33 foram previamente analisadas e nao sao uma nova
amostra independente. Antes de olhar os resultados seguintes, ficam
**congeladas** as janelas 34..39 (6 periodos de 300 horas independentes,
a partir do mesmo `AsOf=1791845999999`), os parametros e os 3
cenarios abaixo:

- `CONTROL`: RETEST 5m, contexto 15m, `BLOCK_BOTH` e
  `WAIT_12_BARS` (60 min), sem novo setup L2.
- `BLOCK_ALL_15M_RANGE_SHORTS`: controle bloqueando SHORT RETEST
  se a tendencia fechada do 15m for `RANGE`.
- `REPLACE_RANGE_SHORT_RETEST_WITH_L2`: bloqueia os SHORT RETEST
  originais em `RANGE`, mas pode gerar uma **nova entrada SHORT**
  de setup `RANGE_FALSE_BREAK_L2` segundo o detector abaixo.
  Fora do `RANGE`, mantem a estrategia RETEST anterior, sem mudancas.
  LONGs, saidas e gestao das posicoes existentes seguem iguais.

Detector causal (dados exclusivamente encerrados):
1. Um candle de 5m testa acima da **resistencia anterior**, definida
   pelo MAX HIGH de **30 candles de 15m fechados ANTES da barra de
   falha**. A mesma barra de 5m fecha ABAIXO da antiga resistencia
   (falso rompimento, wick). Nao calcula o range incluindo essa
   maxima futura/atual.
2. Dentro de **3 candles de 5m** apos a falha, exige uma primeira
   barra baixista cujo fechamento fica abaixo do fechamento da falha.
3. Exige um repique bullish posterior, fechado acima do primeiro
   fechamento de venda mas abaixo da maxima do falso rompimento,
   sem outra superacao dessa maxima.
4. Em ate **9 candles de 5m** desde a falha, exige segunda barra
   baixista fechando abaixo da minima da primeira barra baixista e
   da minima da barra imediatamente anterior. Nao vender se esse
   segundo fechamento ja estiver no terco inferior da antiga faixa.
5. O contexto 15m atual tem de ser `RANGE`; agressao no ultimo 5m
   `SELL_DOMINANT`, volume diferente de LOW/UNAVAILABLE; posicao
   FLAT. Um **pivo HIGH confirmado** e escolhido pelo mesmo
   `RiskEngine` existente; deve estar >= maxima do falso rompimento
   para que o stop estrutural com buffer fique alem da invalidacao.
6. Se `BLOCK_BOTH` tiver rejeitado o sinal original, a nova entrada
   nao o burla. Se a espera de 60 min estiver ativa, nenhum L2 abre.
   O mesmo rompimento falho nao pode originar varias novas entradas.
   O L2, se aprovado pelo motor de risco, entra **apenas na proxima
   abertura de 5m**, com slippage adverso, taxa, risco, validacoes de
   Binance Testnet, stop-first e trailing existentes.

O novo `DecisionResult.Setup.RANGE_FALSE_BREAK_L2` e
`Reason.RANGE_FALSE_BREAK_L2_CONFIRMED` sao emitidos SOMENTE
pela rota experimental `BacktestService.runL2Range`. A checagem
de risco aceita o novo motivo somente para SHORTs com esse setup;
o motor normal `JavaDecisionEngine` nao passa a produzir L2.

A nova API `POST /api/v1/backtest/compare-range-l2`
executa tres portfolios inteiramente independentes, com
`includeLuna=false`, RETEST-only original e precos/custos reais
do modelo de simulacao. A resposta traz PnL LONG e SHORT separado,
quantos sinais L2 foram emitidos, quantos foram bloqueados pelo
cooldown e quantos trades encerrados eram de setup L2.
Esses numeradores SAO DIFERENTES, devido a bloqueios do risco,
gaps ou posicoes abertas no fim de janela.

A familia numerica: 3 cenarios, maximo 3 barras para primeiro
impulso, 9 barras para segunda tentativa, 30 candles de 15m para
resistencia anterior, espera 60 minutos, 6 janelas de 300 horas.
O numero 3/6/9 seleciona nossas heuristicas; nao e uma regra
matematica prescrita por Al Brooks.

Plano sem rede:

`./scripts/validate-range-l2-oos.ps1 -PrintOnly`

Com backend Spring Boot local iniciado em TESTNET/SIMULATION_ONLY,
capital 1500, risco 0.5%, stop diario 3%, leverage 1, fee 0.05%
por lado, slippage 0.02%, BE 1R, alinhamento 15m nao obrigatorio
e Luna/ordens/WS/bootstrap desligados:

`./scripts/validate-range-l2-oos.ps1 -Symbol SOLUSDT -StartWindow 34 -Windows 6 -AsOf 1791845999999 -ExpectedCapitalUsdt 1500 -SyncHistory`

A sincronizacao usa APENAS SOL 5m/15m Testnet; se nao houver
dados suficientes ou aparecerem lacunas, o estudo deve falhar,
sem buscar candles da Binance producao ou fabricados.
Para repetir com historico completo ja salvo no MySQL, omitir
`-SyncHistory`. Nao e necessaria integracao BTC/ETH.

Arquivos `results`, com sufixo
`SOLUSDT-RETEST-1791845999999-5m-w34-39.csv`:
- `range-l2-oos-aggregate-...`
- `range-l2-oos-windows-...`
- `range-l2-oos-trades-...`

Auditar por janela PnL fechado e marcado, drawdown, custos,
quantos trades LONG/SHORT, numero de L2 realmente executados,
concentracao nos maiores ganhadores, oportunidades substituidas
e sinais rejeitados. Se nao houver L2 suficiente, reportar
baixa cobertura, nao concluir que a estrategia funciona ou
falha por ausencia de trades. Ao inspecionar 34..39, esses
periodos deixam de ser holdout para eventuais regras seguintes.

### Testnet w39: faixa de 15m constante SEM negociacoes

Auditoria direta do MySQL local, 2026-10-14. Em torno do sinal
`1750215599999`, o banco tinha exatamente **30 candles fechados
15m** e **90 candles 5m** contiguos com OHLC = **147.70 USDT**,
volume base/quote = 0, trades = 0. Portanto, nao era uma
resistencia operavel: nao ocorreu negociacao nessas 7,5 horas,
e nenhum timeframe contradiz o outro.

Mudanca de tratamento **apenas no detector L2 experimental**:
`ClosedRangeLocation.atIfTraded` retorna `Optional.empty()`
SOMENTE quando os 30 candles contiguos possuem high=low E
volume/trades/taker igual a zero. A oportunidade L2 desse candidato
e descartada; nao gera sinal, ordem, pivots nem precos sinteticos.
`FailedBreakoutL2Detector` acumula
`zeroActivityFlatCandidates`, exposto no replay/API/CSVs
como `L2FlatHistoryCandidates` (contador de **avaliacoes de
candidatos**, nao de candles unicos ou sinais suprimidos).

Os erros verdadeiros continuam retornando HTTP 422: falta de
historico, buracos nos timestamps, candle do 15m defasado, ou
amplitude zero COM volume/trades diferente de zero. A antiga
`ClosedRangeLocation.at` usada pela pesquisa anterior do terco
superior continua estrita, sem novo comportamento.

O tratamento de **nao ha setup** em ausencia de negociacoes foi
definido em resposta a um erro de dados, depois de ja inspecionar
o historico da janela 39. Logo, a reexecucao das janelas 34..39
deve ser identificada como **reparo metodologico retrospectivo**
da amostra, nao holdout estritamente intocado para escolha de
variacoes adicionais. Mantem-se os parametros congelados
do setup (3, 9, 30, 60 e seis vezes 300 horas).

Mesmo que o replay passe, horas sem trades da Testnet reduzem a
representatividade da simulacao para o mercado real. Nao substituir
por dados de producao, nao eliminar janelas silenciosamente e
nao declarar lucratividade comprovada.

## Auditoria de expectativa financeira, LONG/SHORT e contexto (2026-10-14)

A etapa seguinte **nao implementa mais um filtro** de entrada ou saida.
O objetivo e entender por que taxa de acerto e ganho/perda medio nao
produzem expectativa positiva, antes de tentar "maximizar lucro".

Fonte unica do controle: quatro blocos de CSVs historicos ja
processados (w16..21, w22..27, w28..33, w34..39), cada um
com seis janelas de 300h e 1500 USDT de capital reiniciado.
Para cada janela utiliza **somente Policy=CONTROL**, jamais
mistura trades de dois replays independentes no mesmo portfolio.
As pesquisas w1..15 usam uma definicao e fases de selecao
distintas e nao entram automaticamente nesta comparacao w16..39.

Execute **offline**, sem iniciar Spring Boot, Binance, Luna
ou sincronizar candles:

`./scripts/analyze-expectancy-regimes.ps1 -PrintOnly`

`./scripts/analyze-expectancy-regimes.ps1`

O script exige, na pasta `results`, os arquivos
`short-regime-{trades,windows}-...w16-21.csv`,
`short-regime-{trades,windows}-...w22-27.csv`,
`short-range-oos-{trades,windows}-...w28-33.csv`
e `range-l2-oos-{trades,windows}-...w34-39.csv`.
Na ausencia de um arquivo, falha explicitamente; nao completa
historico ou gera metricas com janelas silenciosamente faltantes.

Cria tres arquivos:
- `expectancy-audit-windows-SOLUSDT-RETEST-1791845999999-5m-w16-39.csv`
- `expectancy-audit-trades-SOLUSDT-RETEST-1791845999999-5m-w16-39.csv`
- `expectancy-audit-regimes-SOLUSDT-RETEST-1791845999999-5m-w16-39.csv`

Valida os 24 portfolios, capital inicial, contagem e soma de trades,
PnL LONG+SHORT, fees e timestamps pre-entrada. Para portfolios com
posicao ainda aberta, distingue a taxa de entrada pendente no
balanco total das taxas dos trades ja encerrados. O PnL marcado
continua diferente do realizado se ha posicao aberta.

Calcula, por LONG/SHORT, bloco historico e contexto de entrada:
- taxa de acerto, ganho medio das vencedoras, perda media dos
  resultados nao positivos, expectativa liquida por trade em
  USDT e em R, Profit Factor, quantidade de janelas cobertas
- `RequiredWinRatePctWithCurrentPayoff`, um calculo **algebrico**
  do acerto necessario para equilibrio, mantendo os payoffs do
  historico, nao predicao do mercado
- `RequiredMeanWinUsdtWithCurrentWinRate`, o ganho medio
  necessario para cobrir as perdas observadas, mantendo
  a contagem de wins e losses, nao promessa de alvo executavel
- contribuicao das tres maiores vencedoras, para medir concentracao
- tendencia 15m, tendencia 5m, estado de breakout, volume, setup,
  bins de stop 0.3/0.6/0.9% e custo/risco 30/60/90%
- agrupamento combinado 15m+breakout, reduzindo conclusoes
  precipitadas baseadas em uma unica variavel.
- Motivo da saida e MFE/MAE, **explicitamente POST-ENTRY**.
  MFE/MAE so sao exportados pelos CSVs w16..27; valores
  ausentes no restante NUNCA sao tratados como zero.
  A cobertura consta em `PostEntryMetricTradeCount`.

`FeatureTiming=ENTRY` NAO certifica potencial de lucro futuro:
apenas diz que a caracteristica ja era observavel ao abrir
a posicao. `FeatureTiming=OUTCOME` serve apenas para
diagnosticar o comportamento e nao pode ser usada diretamente
como filtro. Tabelas pequenas sao sinalizadas
`SmallSample` se <9 trades ou <3 janelas.

A soma do PnL de 24 carteiras de 1500 USDT independentes
NAO corresponde ao crescimento composto de uma carteira.
A amostra esta inteiramente **INSPECIONADA**, sem OOS novo.
Nenhuma remocao retrospectiva de trades constitui prova do
resultado de um filtro executavel. Qualquer configuracao de
saida/trailing ou entrada baseada nesses achados exigiria
novo replay completo e validacao em historico ainda nao visto.

Para ganhos reais, nao elevar alavancagem/risco antes de
observar expectativa liquida positiva com amostra robusta.
Reduzir quantidade de trades pode apenas reduzir tamanho da
perda, sem criar edge. Preservar vencedoras excepcionais
pode ser mais importante que elevar marginalmente win rate,
mas requer testes de gestao causal em candles fechados.

## Contexto LONG: duas hipoteses congeladas em seis janelas novas (2026-10-14)

Base empirica exploratoria: a auditoria dos CONTROLS em w16..39
identificou prejuizos historicos nas operacoes LONG quando
`trend15m=RANGE` e `breakout5m=UP_FOLLOW_THROUGH`, e quando
`trend15m=UP` mas `volumeLevel5m=NORMAL`.
Esses grupos foram selecionados APOS examinarmos w16..39.
Nao sao evidencia de causalidade. O novo experimento testa
ambas separadamente, nunca combinadas nesta etapa.

Tres estrategias congeladas antes de inspecionar w40..45:

1. `CONTROL`: RETEST 5m e tendencia 15m; `BLOCK_BOTH` +
   `WAIT_12_BARS` (60 minutos), sem novo filtro LONG.
2. `BLOCK_LONG_RANGE_FOLLOW_THROUGH`: mesmo controle, mas
   rejeita apenas entrada LONG RETEST com tendencia 15m RANGE e
   estado de rompimento **UP_FOLLOW_THROUGH** no ultimo 5m fechado.
3. `BLOCK_LONG_UP_NORMAL_VOLUME`: mesmo controle, rejeitando
   somente LONG RETEST quando 15m UP e classificacao de volume
   **NORMAL** no ultimo 5m fechado.

Nenhum desses filtros altera SHORT, setups diferentes de RETEST,
ordens de saida, regras de stop, trailing ou risco. Bloqueia sinais
SOMENTE apos a precedencia original do BLOCK_BOTH e cooldown,
ANTES de qualquer nova decisao financeira do ledger. Os filtros
nao usam taxa de acerto, lucro futuro, MFE/MAE, outcome ou cohort
de trades removidos, e nao filtram CSV retrospectivamente.

`BacktestService.runLongContext` e
`POST /api/v1/backtest/compare-long-context` executam tres
portfolios isolados desde o primeiro candle de cada janela. Cada
cenario recalcula capital disponivel, posicoes LONG/SHORT, stop
diario, risco 0.5%, slippage/taxas e eventual PnL marcado.
O relatorio possui `longContextBlockedSignals` por politica;
**sinais rejeitados nao sao necessariamente trades eliminados**.
O endpoint nao esta conectado ao motor normal.

OOS reservado, nao inspecionado antes da definicao:
**w40..45**, seis blocos nao sobrepostos de **300 horas**
(3600 candles 5m por bloco), `AsOf=1791845999999`,
1500 USDT independentes por janela. Setup RETEST-only, 15m
contexto sem alinhamento obrigatorio, `BLOCK_BOTH` + 60 min,
stop diario 3%, alavancagem 1, fee por lado 0.05%,
slippage por lado 0.02%, BE 1R e filtro custo/risco maximo 50%.
Nunca pagar Luna ou executar ordens.
A familia numerica 3/6/9 continua como convencao de experimentos
(3 politicas, 6 janelas, 300 horas, cooldown 60 min),
nao uma prova de vantagem estatistica.

Executar com Spring Boot local TESTNET/SIMULATION_ONLY,
Java 25 compilado, WS/bootstrap/ordens/Luna desligados:

`./scripts/validate-long-context-oos.ps1 -PrintOnly`

`./scripts/validate-long-context-oos.ps1 -Symbol SOLUSDT -StartWindow 40 -Windows 6 -AsOf 1791845999999 -ExpectedCapitalUsdt 1500 -SyncHistory`

`-SyncHistory` somente na primeira vez, SOL 5m/15m Testnet,
em lotes historicos. Dados antigos da Testnet podem conter
candles de volume e negociacoes zerados e longos trechos
sem atividade. **Isso limita a extrapolacao dos resultados
para a Binance Futures real.** Lacunas, contexto invalido,
historico insuficiente ou ranges ativos inconsistentes
devem produzir falha, nao trocas de origem nem candles falsos.

No diretorio `results`, tres arquivos com sufixo
`SOLUSDT-RETEST-1791845999999-5m-w40-45.csv`:
- `long-context-oos-aggregate-...`
- `long-context-oos-windows-...`
- `long-context-oos-trades-...`

Os checks reconciliam os dados de cada uma das 18 carteiras:
capital inicial e parametros efetivos, mesmo tape 5m,
PnL fechado de trades versus portfolio, LONG+SHORT=total,
contagem de sinais rejeitados e timestamps anteriores ao fill.
O script nao exporta resultado parcial se uma janela falhar.

As comparacoes primarias sao delta de PnL **marcado** versus
CONTROL, janelas melhoradas, drawdown, fees, expectancy por
trade LONG e SHORT, vencedoras sacrificadas, entradas
substitutas, concentracao dos maiores ganhos e resultados
na presenca/ausencia de negocacoes.
Depois de inspecionar w40..45, esse historico nao sera
novamente OOS para modificacoes decididas com seus resultados.
