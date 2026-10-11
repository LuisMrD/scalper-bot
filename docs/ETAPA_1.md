# Etapa 1 — Checklist

| Entrega | Estado |
| --- | --- |
| Projeto novo Java 25/Maven/Spring Boot 4.1.1 | Arquivos criados |
| Perfis local/backtest/paper/testnet | Arquivos criados |
| MySQL com Compose local | Arquivo criado; nao iniciado aqui |
| Flyway V1 e entidades JPA | Arquivos criados; integracao nao verificada aqui |
| Configuracao de multiplos pares | Criada; validacao com Spring pendente |
| Risco financeiro sem defaults | Criado |
| Guard contra envio de ordens | Implementado e com testes unitarios escritos |
| Endpoint de status seguro | Implementado e teste unitario escrito |
| Configuracao segura Testnet (hosts) | Criada |
| Build e testes Maven | **PENDENTES: Maven e JDK 25 indisponiveis no ambiente de geracao** |
| Teste com MySQL real | **PENDENTE: MySQL/Docker indisponivel no ambiente de geracao** |

## Validacao no ambiente do usuario

1. Instalar JDK 25 e Maven 3.9.x, configurar `JAVA_HOME`.
2. `docker compose up -d mysql`.
3. `mvn clean test`.
4. `mvn spring-boot:run` e verificar `/actuator/health` + `/api/v1/system/status`.
5. `mvn clean package`.
6. Opcionalmente testar perfil `testnet` (continua sem ordens).

## Etapa 2 proposta

Implementar coleta de candles e regras de `exchangeInfo` via REST, streaming publico WS, normalizacao, idempotencia e persistencia, antes de estrategias.
