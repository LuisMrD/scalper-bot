# Etapa 8 — LunaDecisionEngine (OpenAI Responses API)

## Configuração (por padrão OFF)

O único interruptor de chamadas e análise do Luna é `LUNA_ENABLED`:

- `LUNA_ENABLED=false` (padrão): nenhuma análise Luna, nenhuma consulta OpenAI e nem leitura de MarketContext pelo serviço Luna.
- `LUNA_ENABLED=true`: habilita **somente** o endpoint POST, mediante `OPENAI_API_KEY` configurada. Não há scheduler nem chamadas a cada candle. A chave nunca é retornada pelos endpoints nem registrada em logs.

O arquivo `.env.example` é apenas documentação, o Spring Boot **não** carrega `.env` automaticamente. Exemplo PowerShell:

```powershell
$env:LUNA_ENABLED = 'true'
$env:OPENAI_API_KEY = '<a-chave-configurada-no-provedor>'
mvn spring-boot:run
```

Para desligar, altere `LUNA_ENABLED=false` **e reinicie o processo**. O flag é lido na inicialização, não é hot-reload. Remova a variável API key do terminal após o uso; nunca a comite.

O acesso à API é cobrado separadamente da assinatura ChatGPT, e **cada POST válido** pode gerar custo. A configuração está desligada por padrão.

## Endpoints

`GET /api/v1/decision/luna/status` retorna enabled, ready, model, ordersEnabled=false e automaticAnalysis=false. Não retorna segredo.

`POST /api/v1/decision/luna/evaluate?symbol=BTCUSDT&intervals=1m,5m,15m&limit=200&positionSide=FLAT`

- Aceita opcionalmente `asOf=<UTC epoch ms>` para avaliação histórica sem dados futuros.
- Reutiliza `MarketContextService` da Etapa 6, com o mesmo snapshot para Price Action, volume e níveis. O primeiro timeframe é o de entrada.
- Sem histórico suficiente, contextos inconsistentes ou com lacunas: HTTP 422, sem chamada ao modelo.
- Candle principal antigo além de `LUNA_MAX_STALENESS_BARS`: `WAIT`, sem chamada paga.
- Se desabilitado ou sem credencial: HTTP 503, sem chamada à Binance nem à OpenAI.
- Se o provedor falhar, recusar, interromper, devolver resposta malformada ou inconsistente: HTTP 503, sem sugestão executável. Nenhuma ordem é executada neste projeto.

## Integração técnica

- Endpoint fixo: `https://api.openai.com/v1/responses`.
- Modelo padrão: `gpt-6-luna` (`LUNA_MODEL` permite alteração futura, mas o motor foi projetado para Luna).
- `reasoning.effort=none`, `store=false`, timeout e `max_output_tokens` configuráveis.
- O JSON Schema estrito aceita somente `entry`, `positionAction`, `setup` e `reason`.
- A validação local exige coerência para posição aberta (nunca nova entrada), setups e motivo da decisão, e não interpreta texto livre como instrução.
- Resultado no mesmo contrato `DecisionResult` usado pelo motor Java, com `engine=LUNA`.
- A API key é enviada apenas no header HTTPS para a OpenAI.
- Nenhuma ordem, stop-loss, tamanho de posição, alavancagem ou risco é definido pela IA.

## Limitações do teste

- Não existe comparação de carteira ou PnL nesta etapa; isso exige backtesting e simuladores independentes.
- A interface POST é acionada manualmente. Automatização por eventos (sem repetição desnecessária) será implementada em etapa própria.
- A verificação do modelo e de rede real exige habilitação explícita e credencial do usuário; testes unitários devem utilizar stubs, sem chamadas pagas.
- `LUNA_ENABLED=false` não desativa o motor Java nem a coleta de dados.

Documentação: https://developers.openai.com/api/docs/models/gpt-6-luna e https://platform.openai.com/docs/api-reference/responses/create
