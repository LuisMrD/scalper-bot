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
