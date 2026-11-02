# API Quack PDV (Spring Boot)

Migração da API .NET (`API/NETCORE`) para Java 21 + Spring Boot 3.5. Esta pasta contém a **fundação**: configuração,
banco (Flyway + JPA), envelope e erros, segurança (login, sessão, usuários, recuperação de senha, cadastro público),
o teste de contrato contra a API .NET e a entrega (Docker + CI). Plano geral: `docs/migracao-api-springboot.md`.
Os módulos de negócio (produtos, vendas, fiscal…) entram nas fases seguintes.

A API .NET continua atendendo a produção. **Onde algo não estiver documentado, vale o comportamento da API .NET.**

## Como compilar e testar

Requisitos: JDK 21 e Maven 3.9. Os testes de integração precisam de Docker (SQL Server 2022 via Testcontainers).

```bash
cd API/JAVA
mvn -B -q -DskipTests package   # compila e empacota target/quack-pdv-api-*.jar
mvn -B test                     # unidade + web (MockMvc), sem Docker
mvn -B verify                   # tudo: unidade, web, integração (*IT) e o comparador de contrato
```

| Fase do Maven | O que roda | Precisa de Docker |
| --- | --- | --- |
| `test` (surefire) | testes de unidade e web (`*Test`) | não |
| `verify` (failsafe) | `*IT` (Flyway, repositórios, fluxos completos) e `ApiJavaContratoTest` (comparador) | sim |

## Como rodar em desenvolvimento (perfil `dev`)

O perfil `dev` equivale ao `Development` da API .NET: Swagger ligado (`/swagger-ui.html`, `/v3/api-docs`), cookie sem
`Secure` e `SameSite=Lax`, `forgot-password`/`resetar-senha` devolvem o token quando o e-mail está desligado e o Flyway
também aplica `db/dev` (usuário de desenvolvimento). Segredos de desenvolvimento já vêm no `application-dev.yml`.

```bash
# 1) SQL Server local (Docker). Porta 14333 e senha como nos testes dos apps JavaFX:
docker run -d --name quack-sql -p 14333:1433 -e ACCEPT_EULA=Y -e "MSSQL_SA_PASSWORD=Teste#Caixa2026" \
  mcr.microsoft.com/mssql/server:2022-latest
docker exec quack-sql /opt/mssql-tools18/bin/sqlcmd -C -S localhost -U sa -P "Teste#Caixa2026" \
  -Q "CREATE DATABASE HorusPdv"      # no Git Bash do Windows: prefixe com MSYS_NO_PATHCONV=1

# 2) API em dev (o Flyway cria o esquema na primeira subida)
cd API/JAVA
SPRING_DATASOURCE_URL="jdbc:sqlserver://localhost:14333;databaseName=HorusPdv;encrypt=true;trustServerCertificate=true" \
SPRING_DATASOURCE_USERNAME=sa SPRING_DATASOURCE_PASSWORD="Teste#Caixa2026" \
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

Login de desenvolvimento: `flavio@hpdv.com.br` / `Senha#Quack2026` (administrador da empresa-principal).
A API sobe em `http://localhost:8080` (variável `PORT`).

## Variáveis de ambiente (anexo 17.3 do plano)

| Variável | Para quê | Padrão / limites |
| --- | --- | --- |
| `SPRING_DATASOURCE_URL`, `_USERNAME`, `_PASSWORD` | conexão com o SQL Server | `localhost:1433`, banco `HorusPDV` |
| `QUACK_AUTH_JWT_SECRET` | segredo do JWT (**mesmo valor da API .NET**) | obrigatório em produção, 32+ caracteres |
| `QUACK_AUTH_ISSUER`, `QUACK_AUTH_AUDIENCE` | emissor e público do JWT | `horus-pdv-api`, `horus-pdv-web` |
| `QUACK_AUTH_SESSION_HOURS` | validade da sessão | 8 (1 a 24) |
| `QUACK_AUTH_PASSWORD_RESET_TOKEN_MINUTES` | validade do token de redefinição | 30 (5 a 180) |
| `QUACK_SECURITY_ENCRYPTION_KEY` | chave que cifra segredos no banco (`enc:v1:`) (**mesmo valor da produção**) | obrigatório em produção, 32+ caracteres |
| `QUACK_SECURITY_CORS_ORIGINS` | origens CORS extras, separadas por vírgula | as de produção/localhost já vêm fixas |
| `QUACK_SECURITY_TRUST_FORWARDED_HEADERS` | confiar em `X-Forwarded-For` (atrás de proxy) | `false` |
| `QUACK_SECURITY_MAX_REQUEST_BODY_BYTES` | limite do corpo | 2.000.000 (32.768 a 25.000.000) |
| `QUACK_SECURITY_RATE_LIMIT_WINDOW_SECONDS`, `_MAX_REQUESTS` | limite por IP | 60 s (10 a 3600) / 240 (10 a 5000) |
| `QUACK_RECAPTCHA_ENABLED`, `_SECRET_KEY`, `_MIN_SCORE` | reCAPTCHA | desligado; nota mínima 0,5 (0,1 a 1) |
| `QUACK_EMAIL_ENABLED`, `_HOST`, `_PORT`, `_ENABLE_SSL`, `_USER`, `_PASSWORD`, `_FROM_EMAIL`, `_FROM_NAME`, `_REPLY_TO`, `_FRONTEND_BASE_URL` | SMTP global (a SMTP da empresa tem prioridade quando informada) | desligado |
| `TZ` | fuso do contêiner | `America/Sao_Paulo` |

**Produção recusa subir** com `QUACK_AUTH_JWT_SECRET` ou `QUACK_SECURITY_ENCRYPTION_KEY` com menos de 32 caracteres, ou
com o reCAPTCHA ligado sem chave secreta (`ValidadorProducao`). Valores fora das faixas acima são ajustados, não
recusados (igual à API .NET).

## Como o banco nasce (Flyway)

- `V1__esquema_inicial.sql`: **só o esquema** (tabelas, colunas, defaults, PK, FK, UNIQUE, índices e a função
  `fn_HorusParseDecimal`) de um banco criado do zero pela API .NET. É **gerado**, não escrito à mão:
  `SQLPACKAGE=<caminho> contrato/esquema/gerar-v1.sh` (ver o cabeçalho do script).
- `V2__dados_referencia.sql`: a empresa `empresa-principal` (e só ela por enquanto). É idempotente (`IF NOT EXISTS`).
  Categorias padrão, conteúdo de Treinamento e sequências fiscais entram em `V3+` junto com cada módulo.
- `db/dev/R__seed_dev.sql`: o usuário de desenvolvimento, **só no perfil `dev`**.
- A equivalência com o esquema da API .NET é provada pelo `EsquemaEquivalenteIT`, que compara colunas, índices e chaves
  com o snapshot `contrato/esquema/esquema-dotnet.json`.

### Banco restaurado da produção (baseline)

Um backup da produção tem o esquema mas **não tem `flyway_schema_history`**. A configuração
`baseline-on-migrate=true` + `baseline-version=1` marca o esquema existente como versão 1 e roda **somente V2 em diante**;
o V1 nunca é executado e nenhum dado é tocado (o V2 usa `IF NOT EXISTS`). A tabela `__MigrationHistory` da API .NET
fica sem uso. Coberto pelo `BaselineBancoRestauradoIT`.

### Regra: nunca editar uma migração já aplicada

O Flyway guarda o checksum de cada migração (`validate-on-migrate=true`). Alterar um `V*` já aplicado **impede a subida**
com `FlywayValidateException` (teste: `BaselineBancoRestauradoIT.migracaoAlteradaDepoisDeAplicadaImpedeASubida`).
Mudança de esquema = nova migração `V3__...`. O JPA roda com `ddl-auto=validate`: entidade que não bate com o banco
derruba a subida.

## Estrutura

```
src/main/java/br/com/quack/pdv/
  config/    propriedades (QuackProperties), Jackson, roteamento, Spring Security, validação de produção
  shared/    ApiResponse (envelope), NegocioException, GlobalExceptionHandler, formatos de data do .NET
  security/  JWT, PBKDF2, AES-GCM, filtros (cabeçalhos, telemetria, limites, autenticação), @PerfisPermitidos
  auth/      login, logout, me, troca/recuperação de senha, cadastro público, reCAPTCHA
  usuario/ sessao/ empresa/ auditoria/ email/   domain (entidades), repo, app (serviços), web (controllers e DTOs)
src/main/resources/db/{migration,dev}
contrato/    teste de contrato (ver contrato/README.md)
poc-fiscal/  prova de conceito da biblioteca fiscal (projeto Maven separado)
```

## Compatibilidade com a API .NET

Senhas (PBKDF2 `100000.sal.chave`), segredos cifrados (`enc:v1:` AES-GCM), JWT HS256 e hash do token de redefinição
são **idênticos**: um usuário/segredo/token gravado por uma API vale na outra com os mesmos segredos. Os vetores são
gerados pelo próprio código da API .NET (`contrato/vetores-dotnet`) e conferidos nos dois sentidos.

## Homologação (VPS nova)

1. A CI (`.github/workflows/api-java.yml`) publica `ghcr.io/<dono>/quack-pdv-api-java:spring-api` (e `:<sha>`) a cada push
   na branch `spring-api`.
2. Na VPS: `cp .env.homologacao.example .env.homologacao`, preencha senhas e segredos (para usar um banco restaurado,
   **os mesmos** `JWT_SECRET`/`ENCRYPTION_KEY` da API .NET) e suba:
   `docker compose --env-file .env.homologacao -f docker-compose.homologacao.yml up -d`.
3. Banco novo: o Flyway cria tudo (`V1`+`V2`) — o serviço `db-init` do compose cria o banco vazio `HorusPdv` se ele não existir.
   Banco restaurado da produção: restaure o `.bak` no SQL Server do compose (`RESTORE DATABASE HorusPdv FROM DISK =
   '/var/opt/mssql/backups/arquivo.bak' WITH REPLACE`) e suba a API: ela faz o baseline na versão 1 e aplica só o V2.
4. Confira: `GET /actuator/health` = `UP` e login com um usuário real.
