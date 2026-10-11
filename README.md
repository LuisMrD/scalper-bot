# Scalper Bot

Robô de pesquisa e simulação de scalping para **Binance USDⓈ-M Futures**, desenvolvido com **Java 25, Spring Boot, Maven e MySQL**.

## Escopo aprovado
- Suporte a múltiplos pares, iniciando por `BTCUSDT`.
- Dois motores de decisão independentes: regras Java e GPT-6 Luna.
- Análise de Price Action (Al Brooks), volume (Anna Coulling), Fibonacci, suportes e resistências.
- Setups de pullback em tendência e rompimento com reteste.
- Backtesting, paper trading e preparação para Binance Futures Testnet.
- Banca, risco, alavancagem e stop diário configuráveis, sem pressupor valores.
- Reversão confirmada, break-even e trailing stop estrutural.
- Análises pela IA acionadas por eventos, não obrigatoriamente a cada candle.

## Segurança
O projeto deve ser testado em simulação antes de qualquer execução real. As proteções e os limites de risco pertencem ao Java, independentemente do motor de decisão.

## Desenvolvimento
Este é o primeiro commit de inicialização do repositório. A importação do código-fonte das Etapas 1 e 2 será feita separadamente e validada antes de considerar essas etapas publicadas.

Fluxo planejado: `main` (estável), `develop` (integração) e `feat/*` (funcionalidades).
