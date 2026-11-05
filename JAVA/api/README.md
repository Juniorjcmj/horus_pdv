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

As exclusões de `ConfigE2ETest`, `CurvaReposicaoE2ETest`, `DashboardE2ETest` e `Etapa5E2ETest` evitam classes que consultam explicitamente análises da Fase 6 fora do escopo; a rodada original comprovou respostas 404 e falha de UI em Etapa5 antes da consulta. Com `POST /api/HistoricoVendas` portado na Fase 4, os E2E de inventário que dependem de venda (`InventarioAppE2ETest` e os métodos de `InventarioE2ETest`) rodam sem exclusão (comando da Fase 4 abaixo). Aceite da Fase 4 em 03/11/2026: 42 testes verdes (13 métodos de `InventarioE2ETest` e `InventarioAppE2ETest` incluídos). Aceite da Fase 3: 36 testes, todos verdes.

As Fases 6 (relatórios e análises de estoque) e 7 (busca SEFAZ e funcionalidades fiscais) permanecem planejadas. Nesta fase, `NfeImport/buscar-sefaz` valida a chave e, para chave válida, responde HTTP 400: "A busca da nota na SEFAZ ainda não está disponível nesta versão. Importe a nota pelo arquivo XML." Essa divergência consciente está registrada no cenário de contrato e será removida na Fase 7.

## Migração de vendas (Fase 4)

Rotas portadas, com o comportamento da API .NET:

- **Caixa** (6): status, sessão por id, movimentos por período, abrir turno, fechar turno e sangria/reforço (abrir,
  fechar e movimentar são idempotentes; o dinheiro esperado do fechamento é fundo + vendas em dinheiro do turno +
  reforços - sangrias).
- **HistoricoVendas** (9): registrar venda (`POST`, idempotente por hash do payload, com venda offline, fiado e
  ruptura), listagens e detalhe, cancelamento (com senha do gerente e trilha) e correção.
- **Pedido** (5): criar, listar abertos, consultar, finalizar (preço congelado) e cancelar.
- **Fiado** (4): receber, extrato, devedores e resumo.
- **Autorização de preço**, usada pela venda com preço autorizado.

Outras peças: numeração centralizada de documentos (`NumeradorDocumentos`), decisão e pendência fiscal da venda e a
fila fiscal mínima (abaixo). Mudança de esquema: `V4__lote_baixa_pendencias.sql`, tabela só da Java.

Contrato: os grupos `caixa`, `vendas`, `cancelamento`, `pedido` e `fiado` entram em `MotorDeContrato.GRUPOS`, que passa
a ter 24 grupos (ver `contrato/README.md`).

### Divergências conscientes

- **`xmlNfceContingencia`** na venda: 400 "A importação da NFC-e de contingência do Gateway ainda não está disponível
  nesta versão.", antes de qualquer gravação. A Fase 7 remove.
- **`creditAuthorization` e canal do Gateway:** sem canal do Gateway a Java recusa com a mesma mensagem e status da
  .NET; o canal em si (tokens do Gateway, `gateway-credit`) vem na Fase 5.
- **Rotas `api/Nfce/*` e `api/Nfe/*`:** não mapeadas (404), Fase 7.
- **Finalização de pedido e venda com `orderNumber` de pedido existente:** a API .NET responde 500 ("Erro interno no
  servidor.") porque lê `Pedidos.Status` (TINYINT) com `GetInt32`. Por decisão do dono do produto (03/11/2026) a Java
  **funciona** (finalização e conferência reais); não há propriedade para reproduzir o erro. Os cenários gravados com
  500 estão marcados com `divergencia` (ver `contrato/README.md`).

Esquisitices da .NET copiadas de propósito: `saleNumber` em texto, base 15039 só para `empresa-principal`, `printedAt`
local `dd/MM/yyyy HH:mm:ss`, `ate` + 1 dia nos filtros de cancelamento e auditoria de ruptura fora da transação.

### Fila fiscal mínima

`FilaFiscalMinima` (`@Scheduled`, 1 s) faz só o que a venda precisa antes da Fase 7: retoma vendas sem documento
fiscal, encerra sem SEFAZ a nota de venda cancelada antes da emissão (status Rejeitado, motivo pedindo a inutilização),
marca erro ("Empresa sem certificado ou CSC configurado.") na nota de empresa sem emitente e deixa as demais paradas,
sem transmitir nada. Propriedades:

| Propriedade | Padrão | Para quê |
| --- | --- | --- |
| `quack.fiscal.fila-minima.ativa` | `true` | **Desligar (`false`) quando a API .NET usar o mesmo banco**: as duas não devem processar a mesma fila. Pelo ambiente: `QUACK_FISCAL_FILA_MINIMA_ATIVA`. |
| `quack.fiscal.fila-minima.intervalo-ms` | `1000` | intervalo entre execuções |

### Retentativa da baixa de lotes

Se `ConsumirVendaSeguro` ou `EstornarVendaSeguro` falha depois do commit da venda, a .NET só loga. A Java grava a
falha em `LoteBaixaPendencias` (migração V4) e uma rotina periódica tenta de novo com backoff até um limite; depois
dele a pendência fica marcada para revisão e é logada como erro. A rotina é idempotente (não consome a mesma venda
duas vezes). Os produtos da venda, do pedido, do cancelamento e da correção são travados em ordem de código, para
evitar deadlock entre vendas simultâneas (sem efeito no contrato).

### Aceite do Caixa JavaFX

Suba o ambiente (`bash JAVA/api/contrato/aceite-estoque/subir.sh`, ver acima), rode o comando e depois `descer.sh`:

```bash
mvn -o -f JAVA/caixa/pom.xml test -Dquack.e2e.url=http://localhost:5261 -Dquack.e2e.docker=pdv-java-sql -Dtest='*E2ETest,!NfceCancelE2ETest' -Dsurefire.failIfNoSpecifiedTests=false
```

Notas: `SaleCancelE2ETest` e `SaleCorrectionE2ETest` só rodam com `-Dquack.e2e.operador=<e-mail>` (e
`-Dquack.e2e.operadorSenha=<senha>`) de um operador com perfil de caixa; sem isso ficam ignorados. O banco E2E não pode
ter turno aberto de outro dia (o status devolve "expirado", comportamento correto): feche-o antes, na cópia descartável
`pdv-java-sql`. `NfceCancelE2ETest` fica de fora (emissão real, Fase 7). Resultado em 03/11/2026: Caixa 6 testes, 0
falhas, 2 ignorados (os dois acima, verdes quando rodados à parte com um operador `caixa`), `OfflineE2ETest` verde.

### Aceite do Quack Estoque (com inventário)

É o comando da Fase 3 **sem** as exclusões de inventário (`InventarioAppE2ETest` e `InventarioE2ETest` rodam):

```bash
mvn -o -f JAVA/estoque/pom.xml test -Dquack.e2e.url=http://localhost:5261 -Dquack.e2e.sqlContainer=pdv-java-sql -Dtest='*E2ETest,!RelatoriosE2ETest,!ConfigE2ETest,!CurvaReposicaoE2ETest,!DashboardE2ETest,!Etapa5E2ETest,!ComprasE2ETest#geraUmPedidoPorFornecedor' -Dsurefire.failIfNoSpecifiedTests=false
```

Resultado em 03/11/2026: 42 testes, 0 falhas.

### Verificação

`mvn -B -o verify` passa (03/11/2026): 592 testes de unidade/web (Surefire) e 1303 de integração/contrato (Failsafe, dos quais 1152 são os cenários do `ApiJavaContratoTest`, nos 24 grupos), sem falhas, erros ou skips.

## Modo loja e sincronização (Fase 4b)

**Estado (05/11/2026):** implementada e testada; falta só o checklist manual numa máquina Windows com 2 caixas (tarefa 8.4, com o dono). `mvn -B -o verify` em três partes, tudo verde: 646 unitários; `ApiJavaContratoTest` 1 152 cenários; 891 ITs de integração (com `ModoLojaDotnetIT`; `ComandosIT` repetido isolado, ver `design.md`, Notas do grupo 8). Aceite com o Caixa (6 testes) e o Quack Estoque (42) contra o servidor da loja e conferência `ok`.

A mesma API roda em três modos, escolhidos por `quack.modo` (variável `QUACK_MODO`, ou `--spring.profiles.active`):

| Modo | Para quê |
| --- | --- |
| *(vazio)* | como antes: API completa, sem sincronização |
| `loja` | servidor da loja: API completa, autoridade dos dados da empresa; envia as mudanças à nuvem e aplica os comandos que vêm de lá |
| `nuvem` | **receptor**, um container ao lado da API .NET, no mesmo SQL Server (serviço `pdv-api-sync`, ver `DEPLOY.md`). Só expõe `/api/loja-sync/**` e `/actuator/health`; o resto responde 404 |

Desenho completo em `openspec/changes/api-java-modo-loja/` (`design.md`, D1 a D13 e as notas de cada grupo).

### Rotas `/api/loja-sync`

Na **loja** (administrador local):

| Rota | O que faz |
| --- | --- |
| `GET situacao` | atraso, pausa, comandos pendentes/parados, quarentena, notas paradas, conferência, recuperação |
| `POST conferencia` | roda a conferência na hora e devolve o resultado (`ok`, `divergente`, `erro`) |
| `POST retomar` | religa o envio pausado por erro definitivo, depois da correção |
| `POST recuperar` | pede a recuperação depois de restaurar um backup (ou repete uma pendente) |

Na **nuvem** (receptor):

| Rota | Quem chama | O que faz |
| --- | --- | --- |
| `POST registrar` | instalador (e-mail e senha de administrador) | cria o servidor da loja e devolve o token dele |
| `POST servidores/{id}/revogar` | administrador | revoga o token |
| `GET carga`, `GET carga/delta`, `POST ativar` | loja (token) | carga inicial em páginas, mudanças até a ativação e ativação do modo loja |
| `POST lotes` | loja (token) | recebe as mudanças (Change Tracking), idempotente e na ordem das chaves estrangeiras |
| `GET comandos`, `POST comandos/resultados` | loja (token) | long-poll dos comandos nuvem → loja e o resultado de cada um |
| `POST quarentena/{id}/resolver` | administrador | marca a entrada da quarentena como resolvida |
| `POST conferencia/resumo`, `faixas`, `linhas`, `excluir`, `resultado` | loja (token) | passos da conferência diária |
| `GET situacao` | administrador ou loja (token) | mesma situação, vista da nuvem |

### Propriedades `quack.sync.*`

| Propriedade (variável) | Padrão | Para quê |
| --- | --- | --- |
| `quack.sync.nuvem-url` | vazio | endereço do receptor (`https://sync-pdv...`); a carga inicial grava o endereço no banco |
| `quack.sync.intervalo-ms` | 2000 | intervalo do envio e do executor de comandos |
| `quack.sync.envio-ativo` | `true` | liga o envio loja → nuvem |
| `quack.sync.max-lote-bytes` | 16 MB | tamanho máximo de um lote |
| `quack.sync.conferencia-ativa` | `true` | liga a conferência agendada |
| `quack.sync.conferencia-cron` | `0 0 3 * * *` | horário da conferência (**03:00**, confirmado pelo dono) |
| `quack.sync.alertas-ativo` | `true` | liga a verificação de alertas e o envio de e-mail (a cada minuto) |
| `quack.sync.backup-estado` | vazio | caminho do `backup-estado.json` escrito pelo `backup.ps1` (o instalador preenche); vazio = backup não monitorado |
| `quack.sync.recuperacao-ativa` | `true` | liga a rotina de recuperação depois da restauração |
| `quack.sync.recuperacao-marcador` | `config/recuperar.pendente` | arquivo que o `restaurar.ps1` deixa para pedir a recuperação |
| `quack.sync.recuperacao-intervalo-ms` | 15000 | intervalo entre tentativas da recuperação (sem internet fica pendente) |
| `quack.sync.recuperacao-verificacao-ms` | 300000 | de quanto em quanto tempo a loja pergunta se a nuvem está à frente (restauração sem o script) |
| `quack.sync.detector-ativo` | `true` | **nuvem:** detector das edições feitas pelo painel e pelo MCP (D6) |
| `quack.sync.comandos-espera-ms` | 25000 | **nuvem:** tempo do long-poll de `GET comandos` (0 a 60000) |
| `quack.sync.carga.executar` / `.nuvem-url` / `.email` / `.nome-servidor` e `QUACK_SYNC_CARGA_SENHA` | | só da linha de comando do instalador: registro e carga inicial |
| `quack.sync.recuperacao.executar` | `false` | só da linha de comando: executa a recuperação e sai (código 0 = concluída) |

### E-mail dos alertas (obrigatório na loja)

Os alertas (sincronização pausada, atraso acima de 15 min, NFC-e parada acima de 30 min, falha de backup, conferência
divergente, quarentena, comando parado ou em conflito, recuperação pendente) sempre vão ao log e à tabela `SyncAlertas`.
Para chegarem **por e-mail** aos administradores da empresa a loja precisa de um SMTP:
o da empresa (cadastrado em "Minha Empresa" na nuvem, que desce com a carga inicial e com as edições seguintes) ou o
global `QUACK_EMAIL_ENABLED`, `_HOST`, `_PORT`, `_USER`, `_PASSWORD`, `_FROM_EMAIL` no `config\loja.env`. Sem SMTP o alerta
fica guardado e é tentado de novo a cada minuto; nada se perde, mas ninguém é avisado. **Confirme o SMTP antes da entrega.**
Regra de envio: no máximo um e-mail por hora por situação.

### Horários (confirmados pelo dono em 04/11/2026)

- **Backup do banco da loja:** todos os dias às **02:00** (`-HoraBackup` do `instalar.ps1`), retenção de 7 backups.
- **Conferência loja/nuvem:** todos os dias às **03:00** (`quack.sync.conferencia-cron`).

### Instalação e operação

`JAVA/api/instalador-loja/` (Windows): `instalar.ps1` (SQL Server Express, serviço, firewall, backup agendado, registro e
carga inicial), `backup.ps1`, `restaurar.ps1`, `desinstalar.ps1` e o `CHECKLIST.md` de aceite na máquina da loja. O pacote
sai de `build-instalador.sh` (zip de ~112 MB, não versionado). Depois de restaurar um backup a loja se recupera sozinha da
nuvem (`POST recuperar` repete); vendas e envio ficam parados até terminar. Os caixas e o Quack Estoque só trocam o
`apiBaseUrl` para o endereço do servidor.

### Aceite da Fase 4b

- `ModoLojaDotnetIT` (opcional): a API .NET real (`quack-api-dotnet:contrato`, construída por
  `contrato/ambiente-dotnet/subir.sh`) sobre o banco da nuvem do teste; um preço mudado e um cliente cadastrado pelas
  rotas dela chegam à loja. Rodar: `mvn -B -o verify -Dit.test=ModoLojaDotnetIT -Dmodoloja.dotnet=true -Dtest=NoSuch
  -Dsurefire.failIfNoSpecifiedTests=false`.
- Clientes JavaFX contra a loja: `bash JAVA/api/contrato/aceite-loja/subir.sh` (precisa do `HorusPdv.bak` do
  `aceite-estoque`) sobe o receptor (`http://localhost:5262`) e o servidor da loja (`http://localhost:5261`, SQL
  `pdv-loja-sql`), com registro e carga inicial reais. Pare o receptor (`docker stop pdv-loja-nuvem-api`), rode os comandos
  dos aceites do Caixa e do Quack Estoque com `-Dquack.e2e.url=http://localhost:5261 -Dquack.e2e.docker=pdv-loja-sql
  -Dquack.e2e.sqlContainer=pdv-loja-sql -Dquack.e2e.saPassword=Aceite#Loja2026 -Dquack.e2e.sqlSenha=Aceite#Loja2026` (e
  `-Dquack.e2e.modoLoja=true` no Caixa), religue o receptor (`docker start pdv-loja-nuvem-api`) e chame
  `POST /api/loja-sync/conferencia`. `descer.sh` remove tudo. Os E2E esperam o turno de caixa de 31/10 fechado (feche-o por
  SQL na cópia) e, para cancelamento e correção, um operador (`-Dquack.e2e.operador`, `-Dquack.e2e.operadorSenha`).

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
