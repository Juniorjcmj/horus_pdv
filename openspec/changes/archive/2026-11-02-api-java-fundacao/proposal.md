# Proposal

## Why

A API do Quack PDV será reescrita em Java (Spring Boot) e rodará numa VPS própria, sem convivência com a API .NET
(plano completo em `docs/migracao-api-springboot.md`). Antes de portar os módulos de negócio, é preciso (1) congelar o
contrato que os clientes usam hoje, para medir a compatibilidade de cada rota portada, (2) provar que a biblioteca
Java de NF-e/NFC-e atende o fiscal, que é o maior risco, e (3) ter a fundação da API Java: projeto, banco, envelope,
segurança e as rotas de login, sessão e usuários — sem as quais nenhum outro módulo funciona.

## What Changes

**Fase 0 — Preparação**
- Exporta a OpenAPI da API .NET atual (227 rotas) para `API/JAVA/contrato/openapi-dotnet.json`.
- Cria o **gravador de contrato**: chama rotas da API .NET (ambiente Docker de teste) e grava as respostas reais em JSON.
- Cria o **comparador de contrato**: repete as mesmas chamadas na API Java e compara com a gravação, ignorando campos voláteis (ids, horários, tokens).
- Grava o contrato das rotas desta change (`api/Auth`, `api/Sessao`, `api/Usuario`). Os outros módulos gravam o contrato na própria fase.
- **Prova de conceito fiscal** (`API/JAVA/poc-fiscal`), fora da API: monta, assina e valida contra os XSD uma NFC-e 4.00 com QR Code e com os grupos IBS/CBS; emite, cancela e inutiliza em homologação da SEFAZ quando houver certificado A1 e CSC de teste. O relatório da PoC decide se a biblioteca segue para a Fase 7.
- `docker-compose` de homologação (API Java + SQL Server) para a nova VPS.

**Fase 1 — Fundação**
- Projeto `API/JAVA` (Java 21, Spring Boot 3, Maven), pacote `br.com.quack.pdv`, organizado por domínio.
- **Banco:** Spring Data JPA sobre SQL Server; **Flyway** com `V1__esquema_inicial.sql` (esquema atual completo) e `V2__dados_referencia.sql`; baseline automático em banco restaurado da produção; `ddl-auto=validate`.
- **Contrato HTTP idêntico ao .NET:** envelope `{success, message, details, data}`, camelCase, nulos presentes, rotas sem diferenciar maiúsculas/minúsculas e com barra final opcional, mesmos códigos HTTP e mensagens, erro 500 genérico, corpo inválido no formato ProblemDetails.
- **Segurança equivalente:**
  - JWT HS256 no cookie `horuspdv.auth` ou no `Authorization: Bearer`, mesmos claims, validade e sessão conferida no banco a cada requisição;
  - situação da empresa;
  - perfis principal e adicionais;
  - senhas PBKDF2-SHA256 (`iter.sal.chave`) e segredos AES-GCM (`enc:v1:`) compatíveis com os dados atuais;
  - bloqueio de login (5 erros em 15 min → 10 min);
  - reCAPTCHA;
  - limite por IP;
  - limite de corpo;
  - cabeçalhos de segurança;
  - CORS;
  - telemetria com `X-Request-Id`;
  - auditoria.
- **Rotas:** `api/Auth` (8), `api/Sessao` (3), `api/Usuario` (6) com o mesmo comportamento da API .NET, inclusive cadastro público de empresa, recuperação de senha por e-mail e redefinição administrativa.
- **Documentação** springdoc (Swagger UI fora de produção) e **Actuator** (health).
- **Testes:** JUnit 5 + Mockito nos serviços, `@WebMvcTest` nas rotas, testes de repositório e de Flyway com SQL Server em Docker (Testcontainers), compatibilidade criptográfica com valores gerados pelo .NET, comparador de contrato.
- **Entrega:** Dockerfile e workflow do GitHub Actions publicando a imagem `quack-pdv-api-java` no GHCR a partir da branch `spring-api`.
- Fora desta change: tokens do Gateway e de integração (MCP) na autenticação, e o vínculo de fiado da loja (`CreditStoreId`) — entram nas Fases 5 e 8. Até lá, esses tokens recebem 401.

## Capabilities

### New Capabilities
- `plataforma/contrato-api`: formato das respostas e das rotas que os clientes usam (envelope, JSON, códigos HTTP, roteamento sem diferenciar maiúsculas, erros), mais a gravação e a comparação do contrato .NET × Java.
- `plataforma/esquema-banco`: criação e evolução do esquema por Flyway, baseline de banco restaurado, validação do esquema na inicialização.
- `seguranca/autenticacao`: login, sessão, cookie/JWT, bloqueio por tentativas, logout, perfil próprio, troca e recuperação de senha, cadastro público de empresa, listagem e encerramento de sessões.
- `seguranca/usuarios`: gestão de usuários da empresa (listar, supervisores, criar, editar, status, redefinição administrativa de senha) e perfis adicionais.
- `seguranca/protecao-requisicoes`: autorização por perfil, situação da empresa, limite por IP, limite de corpo, cabeçalhos, CORS, telemetria, compatibilidade de hashes de senha e de segredos cifrados.
- `fiscal/biblioteca-java-nfe`: critérios de aceite da biblioteca Java de NF-e/NFC-e (prova de conceito).

### Modified Capabilities
- (nenhuma — a API .NET e os specs existentes não mudam; a API Java reproduz o comportamento atual)

## Impact

- **Código novo:** `API/JAVA/` (projeto Spring Boot, `contrato/`, `poc-fiscal/`, `docker-compose.homologacao.yml`, `Dockerfile`) e `.github/workflows/api-java.yml`. A API .NET, os clientes e o Gateway **não mudam**.
- **Banco:** nenhuma alteração no banco de produção. O banco da nova VPS nasce pelo Flyway (V1) ou por restauração com baseline.
- **Dependências:** Spring Boot 3 (web, data-jpa, security, validation, mail, actuator), Flyway (core + sqlserver), mssql-jdbc, springdoc-openapi, Caffeine, Bucket4j, JUnit 5, Mockito, AssertJ, Testcontainers (mssqlserver). PoC: biblioteca Java de NF-e (candidata `br.com.swconsultoria:java-nfe`).
- **Configuração:** variáveis de ambiente equivalentes às atuais (`QUACK_AUTH_JWT_SECRET`, `QUACK_SECURITY_ENCRYPTION_KEY` etc., mapa no anexo 17.3 do plano). Os valores de produção de `JwtSecret` e `EncryptionKey` são reaproveitados no corte.
- **Branch:** `spring-api`.
