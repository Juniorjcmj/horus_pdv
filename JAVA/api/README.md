# API Quack PDV (Spring Boot)

Migração da API .NET (`API/NETCORE`) para Java 21 + Spring Boot 3.5. Esta pasta contém a fundação e os módulos já
implementados da migração. Plano geral: `docs/migracao-api-springboot.md`.

A API .NET continua atendendo a produção. **Onde algo não estiver documentado, vale o comportamento da API .NET.**

## Como compilar e testar

Requisitos: JDK 21 e Maven 3.9. Os testes de integração precisam de Docker (SQL Server 2022 via Testcontainers).

```bash
cd JAVA/api
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
cd JAVA/api
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
- `V3__cadastros_dados_referencia.sql`: 55 categorias padrão, 2 seções e 9 vídeos de treinamento; cada INSERT é
  protegido por `IF NOT EXISTS` pelo `Id`.
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

## Migração de cadastros (Fase 2)

Endpoints implementados na Fase 2:

- Empresa (`GET`/`PUT`) e tema da empresa (`GET`/`PUT`);
- Categoria (consulta, CRUD e alteração de status), Fornecedor (CRUD) e Cliente (CRUD e `credito-snapshot`);
- `ModuloMercado` (consulta do módulo e CRUD de registros);
- treinamento (consulta e CRUD de seções/vídeos);
- Admin/Empresas (lista, métricas, configuração, aprovar, rejeitar, bloquear, reativar, credenciais e exclusão);
- documentação da API (`documentacao-api`).

`mvn -B verify` passa (2026-11-02): 351 testes de unidade/web e 279 de integração/contrato, sem falhas, erros ou
skips. O comparador cobre os 11 grupos (`MotorDeContrato.GRUPOS`), com 233 cenários iguais às gravações da API .NET.

Falta só a validação manual pelas telas web (FRONTEND apontado para a API Java em `dev`), registrada aqui quando for
feita. A Fase 2 fica aberta até lá.

Notas da comparação:
- O `ApiJavaContratoTest` usa as mesmas chaves de JWT e de cifra do ambiente .NET de contrato
  (`contrato/ambiente-dotnet/subir.sh`), porque o seed traz segredos `enc:v1:` cifrados com elas.
- Rejeitar e bloquear empresa rodam o `UPDATE` e o `DELETE` das sessões como instruções separadas, na mesma
  transação, e somam as linhas: o mssql-jdbc devolveria só a contagem da última instrução do lote.

## Migração de produtos e estoque (Fase 3)

Implementados: produtos, movimentações, lotes, entrada manual, importação de NF-e, ordens de compra, promoções e inventário (abrir, contar, anular, recontar, aplicar, cancelar e trilha, com o mesmo comportamento da API .NET, inclusive a contagem "cega" que só oculta custos e valores do estoquista). O aceite 8.1 foi validado: a API respondeu ao health check e ao login, aplicou baseline 1 e V2/V3, e o script de retomada terminou com código 0. A tarefa 8.2, de execução E2E, permanece pendente.

O contrato possui grupos para `produto`, `movimentacao`, `lote`, `entrada-manual`, `nfe-import`, `ordem-compra`, `promocao` e `inventario` (134 cenários). Fixtures de contrato ficam em `src/test/resources/contrato/fixtures`. O seed de integração foi estendido para lotes, notas de entrada, replay manual equivalente ao .NET, compras, promoções, `VendaItens` e inventário.

### Aceite isolado

Na raiz do repositório, execute `bash JAVA/api/contrato/aceite-estoque/subir.sh` no Git Bash. Requer Docker, Maven, JDK 21, curl, Python 3 e Git Bash. O script cria backup SQL `COPY_ONLY` de `HorusPdv` em `pdv-teste-sql` e o restaura no SQL isolado `pdv-java-sql`; publica a API Java em `http://localhost:5261`. A senha padrão SQL é `Teste#Caixa2026`, configurável por `QUACK_E2E_SQL_SENHA`; a origem é configurável por `QUACK_E2E_SQL_ORIGEM`. Após falha, para retomar somente a API sobre o SQL já restaurado: `QUACK_ACEITE_APENAS_API=1 bash JAVA/api/contrato/aceite-estoque/subir.sh`. `descer.sh` remove apenas os containers e a rede rotulados pelo aceite; preserva o backup em `.estado/HorusPdv.bak`.

Comando E2E da D10 (aceite do Quack Estoque):

```bash
mvn -o -f JAVA/estoque/pom.xml test -Dquack.e2e.url=http://localhost:5261 -Dquack.e2e.sqlContainer=pdv-java-sql -Dtest='*E2ETest,!RelatoriosE2ETest,!ConfigE2ETest,!CurvaReposicaoE2ETest,!DashboardE2ETest,!Etapa5E2ETest,!ComprasE2ETest#geraUmPedidoPorFornecedor' -Dsurefire.failIfNoSpecifiedTests=false
```

As exclusões de `ConfigE2ETest`, `CurvaReposicaoE2ETest`, `DashboardE2ETest` e `Etapa5E2ETest` evitam classes que consultam explicitamente análises da Fase 6 fora do escopo; a rodada original comprovou respostas 404 e falha de UI em Etapa5 antes da consulta. Com `POST /api/HistoricoVendas` portado na Fase 4, os E2E de inventário que dependem de venda (`InventarioAppE2ETest` e os métodos de `InventarioE2ETest`) rodam sem exclusão. Aceite da Fase 4 em 03/11/2026: 42 testes verdes (13 métodos de `InventarioE2ETest` e `InventarioAppE2ETest` incluídos). Aceite da Fase 3: 36 testes, todos verdes.

As Fases 4 (vendas), 6 (relatórios e análises de estoque) e 7 (busca SEFAZ e funcionalidades fiscais) permanecem planejadas. Nesta fase, `NfeImport/buscar-sefaz` valida a chave e, para chave válida, responde HTTP 400: "A busca da nota na SEFAZ ainda não está disponível nesta versão. Importe a nota pelo arquivo XML." Essa divergência consciente está registrada no cenário de contrato e será removida na Fase 7.

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
