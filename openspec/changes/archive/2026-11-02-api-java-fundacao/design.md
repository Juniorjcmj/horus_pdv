# Design

## Context

Motivação e escopo: ver `proposal.md`. Plano geral: `docs/migracao-api-springboot.md`.

**A API .NET é a especificação viva.** Onde este design ou os specs não detalharem um comportamento, vale o código
.NET. Arquivos de referência (ler antes de implementar cada parte):

| Assunto | Arquivo .NET |
| --- | --- |
| Pipeline e CORS | `API/NETCORE/Program.cs` |
| Autenticação por requisição | `API/NETCORE/Middlewares/HorusAuthMiddleware.cs` |
| Limite por IP / corpo / cabeçalhos / telemetria | `API/NETCORE/Middlewares/HorusRateLimitMiddleware.cs`, `HorusRequestBodyLimitMiddleware.cs`, `HorusSecurityHeadersMiddleware.cs`, `HorusRequestTelemetryMiddleware.cs` |
| JWT | `API/NETCORE/Services/Security/HorusJwtService.cs` |
| Opções e validação de produção | `API/NETCORE/Services/Security/HorusSecurityOptions.cs` |
| Cifra de segredos | `API/NETCORE/Services/Security/HorusSecretProtector.cs` |
| IP do cliente | `API/NETCORE/Services/Security/HorusClientIpResolver.cs` |
| Perfis | `API/NETCORE/Services/Security/HorusRoles.cs`, `HorusAuthorizeRolesAttribute.cs` |
| reCAPTCHA | `API/NETCORE/Services/Security/HorusRecaptchaService.cs` |
| E-mail e modelos | `API/NETCORE/Services/Email/HorusEmailService.cs`, `HorusEmailTemplate.cs` |
| Login, sessões, usuários, senha, cadastro | `API/NETCORE/Repositories/DatabaseAccess/HorusSecurityStore.cs` (inclui `PasswordHasher`) |
| Rotas | `API/NETCORE/Controllers/Auth/AuthController.cs`, `Controllers/Sessao/SessaoController.cs`, `Controllers/Usuario/UsuarioController.cs` |
| Auditoria | `API/NETCORE/Repositories/DatabaseAccess/AuditLogAB.cs` |
| Envelope | `API/NETCORE/Models/Response/ApiResponse.cs` |
| Esquema | `API/NETCORE/DataBase/Resumo.sql` + `DataBase/Migrations/*.sql`, na ordem de `Repositories/HorusDatabaseInitializer.cs` |

**Ambiente local disponível** (já usado nos testes dos apps JavaFX):

- **Docker:** rede `pdv-teste`, contêiner `pdv-teste-sql` (SQL Server 2022, porta 14333, usuário `sa`, senha `Teste#Caixa2026`) e contêiner `pdv-teste-api` (API .NET em `http://localhost:5260`).
- **Build da imagem .NET:** montar um contexto limpo com `tar --exclude=bin --exclude=obj -cf - API/NETCORE Shared` numa pasta temporária e rodar `docker build -f API/NETCORE/Dockerfile` a partir dela.
- **Ferramentas:** .NET SDK 8 instalado (gera os vetores de compatibilidade), Java 21 e Maven.
- **Cuidados:**
  - o disco C: tem pouco espaço: arquivos temporários e builds grandes vão para o E:;
  - no Git Bash, `docker exec` com caminho Linux precisa de `MSYS_NO_PATHCONV=1`, e o `sqlcmd` precisa de `-C -I`.

## Goals / Non-Goals

**Goals:**
- API Java que, para as rotas desta change, é indistinguível da API .NET para os clientes (comprovado pelo comparador).
- Fundação reutilizável pelas próximas fases: envelope, erros, segurança, Flyway, padrões de entidade/repositório/serviço/teste.
- Decisão sobre a biblioteca fiscal com evidência.

**Non-Goals:**
- Tokens do Gateway (`qgw_`) e de integração (`qmcp_`), vínculo de fiado (`CreditStoreId`) e o servidor MCP: Fases 5 e 8. Nesta change, requisição com esses tokens ou em `/mcp` recebe 401 "Sessao expirada ou token invalido.".
- Administração de empresas (`api/Admin/Empresas`) e demais módulos: fases seguintes.
- Deploy em produção e corte.

## Decisions

### D1. Estrutura e versões
- `API/JAVA/` é o projeto Spring Boot (Maven, `groupId br.com.quack`, `artifactId quack-pdv-api`, pacote raiz `br.com.quack.pdv`), Java 21, **Spring Boot 3.5.x** (última estável da linha 3; precisa ser ≥ 3.4 para o `UrlHandlerFilter`).
- `API/JAVA/poc-fiscal/` é um projeto Maven **separado** (não é módulo da API), para as dependências fiscais não entrarem na API antes da Fase 7.
- `API/JAVA/contrato/` guarda a OpenAPI exportada, o ambiente da API .NET para gravar o contrato, o projeto C# de vetores e o snapshot do esquema.
- Pacotes por domínio, cada um com `web/` (controllers e DTOs de entrada/saída), `app/` (serviços), `domain/` (entidades JPA) e `repo/` (repositórios Spring Data). Infraestrutura transversal em `shared/` (envelope, erros, Jackson, formatação) e `security/`.
- Sem Lombok. DTOs como `record`; entidades JPA como classes com getters e setters.
- Alternativa descartada: multi-módulo Maven (API + PoC + contrato). Aumenta o atrito agora sem ganho. A PoC some ou vira módulo `fiscal` na Fase 7.

### D2. Banco, Spring Data e Flyway
- **Driver e dialeto:** `mssql-jdbc` + `SQLServerDialect`.
- **Propriedades do Hibernate:**
  - `hibernate.use_nationalized_character_data=true`: strings como NVARCHAR, para acentos e para o índice funcionar com os parâmetros;
  - `hibernate.timezone.default_storage=NATIVE`: `OffsetDateTime` ↔ `DATETIMEOFFSET`;
  - naming `PhysicalNamingStrategyStandardImpl` com `@Table`/`@Column` explícitos e os nomes PascalCase atuais.
- `spring.jpa.open-in-view=false`, `ddl-auto=validate`.
- **Entidades desta change:** `Usuario` (`Usuarios`), `Sessao` (`Sessoes`), `Empresa` (`Empresas`, só as colunas usadas aqui: status, aprovação, motivo, CNPJ, nome, e-mail e SMTP), `PasswordResetToken` (`PasswordResetTokens`) e `AuditLog` (`AuditLog`, id `IDENTITY`). Os ids de texto continuam gerados pela aplicação com os mesmos formatos.
- **Colunas de data em texto:** `Usuarios.CreatedAt` (`yyyy-MM-dd`) e `LastLoginAt` (ISO "o" do .NET em UTC, `2026-11-01T22:30:00.1234567Z`, ou `-`) continuam `String`, porque o JSON as devolve como texto.
- **Operações atômicas do .NET viram `@Modifying @Query` nativas com o mesmo SQL:** incremento de tentativas com `CASE` de bloqueio, exclusão de sessões e invalidação de tokens. O cadastro público de empresa insere com o mesmo `INSERT` nativo de `EnsureCompanyForPublicRegistration`.
- **Flyway** (`flyway-core` + `flyway-sqlserver`), em `classpath:db/migration`, com `baseline-on-migrate=true`, `baseline-version=1` e `validate-on-migrate=true`:
  - `V1__esquema_inicial.sql`: **somente o esquema** (tabelas, colunas, defaults, PK, FK, UNIQUE, índices) de um banco criado do zero pelo inicializador .NET. É gerado com SqlPackage (`dotnet tool install -g microsoft.sqlpackage`; `sqlpackage /Action:Script` de um `.dacpac` extraído desse banco contra um banco vazio), sem `CREATE DATABASE`/`USE`, sem a tabela `__MigrationHistory` e com lotes separados por `GO` (o Flyway SQL Server aceita).
  - `V2__dados_referencia.sql`: a linha `empresa-principal` em `Empresas`, como em `Resumo.sql`, e qualquer outra linha que o sistema exige para funcionar, levantada nos `INSERT` do `Resumo.sql` e das migrações. Seeds de demonstração (produtos de mercado/balança, usuário `usr-001`) **não** entram.
  - `db/dev/R__seed_dev.sql`: usuário `usr-001` de desenvolvimento, só no perfil `dev` (`spring.flyway.locations` acrescenta `classpath:db/dev`).
- **Prova de equivalência do esquema:** `contrato/esquema/esquema-dotnet.json` é um snapshot do `INFORMATION_SCHEMA` (tabela, coluna, tipo, tamanho, precisão, escala, nulidade, default) e de `sys.indexes`, tirado do banco criado pelo .NET. Um teste com Testcontainers cria o banco pelo Flyway e compara com o snapshot.
- **Banco restaurado da produção:** tem esquema e não tem `flyway_schema_history`, então o `baselineOnMigrate` marca a versão 1 e roda só V2+. Como a `empresa-principal` já existe, o V2 usa `IF NOT EXISTS`. A `__MigrationHistory` antiga fica sem uso.
- Alternativa descartada para o V1: concatenar `Resumo.sql` + 57 migrações. Isso carrega condicionais históricas (migração 01, renomeação 12→13) e seeds; o script gerado do esquema final é mais limpo e verificável.

### D3. Contrato HTTP
- **`ApiResponse<T>`** (`record`: `success`, `message`, `details`, `data`) com fábricas `ok(msg, data)` e `erro(msg)`. As rotas devolvem `ResponseEntity<ApiResponse<?>>` com o status exato.
- **Jackson:**
  - `Include.ALWAYS` (nulos presentes) e nomes camelCase;
  - `JavaTimeModule` com `OffsetDateTime` no formato do .NET: `yyyy-MM-dd'T'HH:mm:ss.SSSSSSSXXX`, `XXX` como `+00:00`;
  - `FAIL_ON_UNKNOWN_PROPERTIES=false`, porque o .NET ignora campos extras;
  - aceitar nomes de propriedade sem diferenciar maiúsculas na entrada (`ACCEPT_CASE_INSENSITIVE_PROPERTIES`), como o `System.Text.Json` do ASP.NET.
- **Roteamento:**
  - `PathPatternParser` com `setCaseSensitive(false)`;
  - `UrlHandlerFilter.trailingSlashHandler("/**").wrapRequest()` para aceitar barra final;
  - rota inexistente → **404 sem corpo**, e método não permitido → **405 sem corpo**, como o ASP.NET; desligar a página de erro padrão do Spring (`server.error.whitelabel.enabled=false` e tratamento próprio).
- **`@RestControllerAdvice`:**
  - `NegocioException(status, mensagem, details, data)` → envelope com o status;
  - `HttpMessageNotReadableException` / `MethodArgumentTypeMismatchException` → 400 ProblemDetails com `type` `https://tools.ietf.org/html/rfc9110#section-15.5.1`, `title` `One or more validation errors occurred.`, `status` 400, `errors` e `traceId`;
  - qualquer outra exceção → 500 `{"success":false,"message":"Erro interno no servidor."}`, só esses dois campos, como o .NET; em `dev`, com a pilha no log.
- **Mensagens:** são copiadas literalmente do .NET, inclusive as sem acento ("Usuario criado com sucesso.", "Senha deve ter no minimo 8 caracteres.", "Sessao expirada ou token invalido."). O comparador pega qualquer diferença.

### D4. Segurança
- **Spring Security** só como infraestrutura:
  - cadeia `stateless`, CSRF desligado (o .NET não tem; o cookie é `SameSite=Strict`), CORS pelo `CorsConfigurationSource`, `permitAll` na cadeia;
  - desligar os cabeçalhos padrão do Spring Security, que são escritos pelo filtro próprio para ficarem idênticos.
- **A decisão de autenticar fica num filtro próprio**, que reproduz o `HorusAuthMiddleware` (mensagens e ordem exatas). Alternativa descartada: `@PreAuthorize` + `AuthenticationEntryPoint`, porque espalha a lógica e dificulta mensagens e ordem idênticas.
- **Ordem dos filtros** (como o pipeline .NET):

  | # | Filtro | Faz |
  | --- | --- | --- |
  | 1 | `UrlHandlerFilter` | barra final |
  | 2 | CORS | — |
  | 3 | `CabecalhosSegurancaFilter` | os 7 cabeçalhos; remove `Server` (`server.server-header=` vazio) |
  | 4 | `TelemetriaFilter` | `X-Request-Id` = UUID sem hífens; log `http.request.completed requestId=… method=… path=… statusCode=… durationMs=… userId=… sessionId=… ip=…` |
  | 5 | `LimiteCorpoFilter` | `Content-Length` > limite → 413 |
  | 6 | `LimiteRequisicoesFilter` | janela fixa por IP, `ConcurrentHashMap<ip, contador+expiraEm>` com limpeza quando passar de 500 entradas; ignora `OPTIONS` e `/swagger`, `/v3/api-docs` |
  | 7 | `AutenticacaoFilter` | ver abaixo |

  - No filtro 6, a janela fixa própria reproduz exatamente o algoritmo e o `Retry-After` do .NET. Alternativa descartada: Bucket4j, cujo token bucket tem semântica diferente.
- **`AutenticacaoFilter`:**
  - **Pula:** `OPTIONS` e caminhos que começam (sem diferenciar maiúsculas) com `/api/Auth/login`, `/api/Auth/forgot-password`, `/api/Auth/reset-password`, `/api/Auth/register`, mais swagger e actuator health em `dev`.
  - **Lê o token:** cookie `horuspdv.auth`, senão `Authorization: Bearer`.
  - **Valida:** JWT, e depois uma consulta única equivalente a `ValidateRequestAsync` (sessão existe, status do usuário, perfis adicionais, status da empresa).
  - **Falha** → 401 "Sessao expirada ou token invalido.". Empresa não aprovada (exceto `empresa-principal`) → 403 com as mensagens do middleware.
  - Guarda `UsuarioAutenticado` (`record`: id, companyId, sessionId, name, email, role, perfisAdicionais) num atributo da requisição e no `SecurityContext`.
- **Autorização por perfil:** anotação `@PerfisPermitidos({"administrador","gerente"})` em classe ou método, verificada por um `HandlerInterceptor` depois da autenticação. Vale o perfil principal ou algum adicional, sem diferenciar maiúsculas. Negado → 403 "Usuário sem permissão para esta ação.". O método sobrescreve a classe, como no `.NET` (`GetMetadata` pega a mais específica).
- **JWT** escrito à mão (`HmacSHA256` do JDK), bit a bit como o `HorusJwtService`:
  - Base64URL sem padding;
  - assinatura sobre `header.payload` com o segredo em UTF-8 e comparação em tempo constante;
  - header `{"alg":"HS256","typ":"JWT"}`, mesmos claims e `sessionTtl` "8h".
  - Alternativa descartada: jjwt/nimbus, que validam claims de forma própria e arriscam divergência.
- **PBKDF2:** `SecretKeyFactory("PBKDF2WithHmacSHA256")`. O SunJCE codifica a senha em UTF-8, como o `Rfc2898DeriveBytes.Pbkdf2(string)`. O hash fica `iter.base64(sal).base64(chave)` e a verificação em tempo constante (`MessageDigest.isEqual`).
- **AES-GCM:** `Cipher("AES/GCM/NoPadding")` com `GCMParameterSpec(128, nonce12)`. A saída do Java é `cifra||tag`: separar os últimos 16 bytes para o formato `enc:v1:base64(nonce).base64(tag).base64(cifra)`. A chave é `SHA-256(UTF-8(encryptionKey ou, se vazia, jwtSecret))`.
- **Token de redefinição:**
  - 32 bytes aleatórios em Base64URL sem padding;
  - hash = SHA-256 hex minúsculo de `"<token>:<jwtSecret>"`.
- **Bloqueio de login** (5 erros em 15 min → bloqueio de 10 min) com o mesmo SQL atômico de `RegisterFailedAttemptDb`. O lockout persiste no banco e vale entre instâncias.
- **IP do cliente:** `ClienteIpResolver`, com `X-Forwarded-For` só se `trust-forwarded-headers`; senão `request.getRemoteAddr()`.
- **Ambiente:**
  - perfil `dev` = "Development" do .NET: cookie sem `Secure` e `SameSite=Lax`, Swagger ligado, `forgot-password` e `resetar-senha` devolvem o token quando o e-mail está desligado;
  - o padrão é produção.
  - Validação de produção (segredos com 32+ caracteres; reCAPTCHA ligado exige chave) num `ApplicationRunner` que derruba a subida.
- **Configuração** em `@ConfigurationProperties(prefix = "quack")`, com os mesmos padrões e limites de `HorusSecurityOptions`:

  | Propriedade | Padrão e limites |
  | --- | --- |
  | `auth.jwt-secret` | — |
  | `auth.issuer` | `horus-pdv-api` |
  | `auth.audience` | `horus-pdv-web` |
  | `auth.session-hours` | 8 (1–24) |
  | `auth.password-reset-token-minutes` | 30 (5–180) |
  | `security.encryption-key` | — |
  | `security.cors-origins` | — |
  | `security.trust-forwarded-headers` | — |
  | `security.max-request-body-bytes` | 2.000.000 (32.768–25.000.000) |
  | `security.rate-limit.window-seconds` | 60 (10–3600) |
  | `security.rate-limit.max-requests` | 240 (10–5000) |
  | `recaptcha.enabled`, `recaptcha.secret-key` | — |
  | `recaptcha.min-score` | 0,5 (0,1–1) |
  | `email.*` | host, port, enable-ssl, user, password, from-email, from-name, reply-to, frontend-base-url |

  As variáveis de ambiente seguem o anexo 17.3 do plano (`QUACK_AUTH_JWT_SECRET`…).

### D5. Serviços e regras (onde fica cada coisa)
- **Regras e mensagens são copiadas do `HorusSecurityStore`:**

  | Serviço | Faz |
  | --- | --- |
  | `AutenticacaoService` | login, logout, me, atualizar me, trocar senha |
  | `RecuperacaoSenhaService` | pedido, redefinição, consumo de token, redefinição administrativa |
  | `CadastroPublicoService` | cadastro de empresa e administrador |
  | `SessaoService` | listar, encerrar, encerrar outras |
  | `UsuarioService` | listar, supervisores, criar, editar, status, validação de duplicidade e de dados |
  | `RecaptchaService` | `RestClient` + `x-www-form-urlencoded`, lógica inteira de `HorusRecaptchaService`, com ação e nota mínima |
  | `EmailService` | `JavaMailSender` montado por envio a partir das configurações resolvidas como em `ResolveSmtpSettingsAsync` (SMTP da empresa com senha decifrada, senão o global); modelos HTML e texto portados do `HorusEmailTemplate` |
  | `AuditoriaService` | grava em `AuditLog` cortando a descrição em 500 caracteres |

- **Detalhes a manter:**
  - **Ids:** `usr-<epochMillis>`, `emp-<epochMillis>`, `sess-<uuid32>`, `prt-<uuid32>`.
  - **Sessão:** dispositivo pelo user-agent (Firefox, Edg, Chrome, Safari, ou "Dispositivo web"); plataforma `mobile` se o user-agent contém "Mobile"; local "Localização indisponível"; `LastActive` "Agora mesmo"; na listagem, a sessão atual mostra "Agora mesmo".
  - **Mascarar e-mail:** `a***b@dominio`.
  - **CNPJ:** validação por dígitos verificadores.
  - **E-mail:** normalizado com trim e minúsculas, e único no sistema todo. O CPF é único por empresa.
  - **Perfil padrão:** "atendente". Status diferente de "inativo" vira "ativo".
  - **Senha nova** marca `mustChangePassword`. Inativar o usuário encerra as sessões.
  - **Respostas de recuperação:**
    - `forgot-password` em `dev` com e-mail desligado devolve o objeto completo, com o token; senão, `accepted`, `maskedEmail` e `expiresAt`;
    - `resetar-senha` responde do mesmo jeito.
  - **Transações:** `@Transactional` nos serviços, onde o .NET usa transação (login, troca e redefinição de senha, redefinição administrativa, pedido de recuperação).

### D6. Contrato gravado e comparado
- **Ambiente .NET de gravação**, `contrato/ambiente-dotnet/subir.sh`:
  - sobe `contrato-sql` (SQL Server 2022) e `contrato-api-dotnet` (imagem da API .NET atual, perfil Production, `Recaptcha__Enabled=false`, `Email__Enabled=false`, segredos de teste fixos);
  - espera o inicializador criar o banco e aplica `contrato/seed/seed-contrato.sql`: empresas `empresa-principal`, uma aprovada, uma pendente e uma bloqueada; usuários administrador, gerente, atendente, caixa e um inativo, com hashes PBKDF2 fixos de senhas conhecidas.
  - Não reaproveita o `pdv-teste-*`, para não sujar os testes dos apps.
- **Gravador:** testes JUnit com a tag `gravar-contrato`, ativos com `-Dcontrato.gravar=true -Dcontrato.url=http://localhost:5270`. Executam os **cenários** declarados em `src/test/resources/contrato/cenarios/<grupo>.json`: método, caminho, corpo, usuário para logar antes e campos voláteis. Gravam `src/test/resources/contrato/gravacoes/<grupo>/<cenario>.json` com status, cabeçalhos de interesse (`Set-Cookie` sem valor, `Retry-After`, `Content-Type`) e o corpo.
- **Comparador:** testes com a tag `contrato`, com `@SpringBootTest(webEnvironment = RANDOM_PORT)` contra SQL Server do Testcontainers criado pelo Flyway + o mesmo `seed-contrato.sql`. Repetem cada cenário e comparam por caminho JSON: status, conjunto de campos, tipo e valor. Campos voláteis só têm o tipo e o formato conferidos (ISO com offset, prefixo do id). As mensagens de falha apontam caminho e valores.
- **OpenAPI:** `contrato/openapi-dotnet.json` exportado do Swagger da API .NET em Development (`/swagger/v1/swagger.json`), para referência das próximas fases.

### D7. Testes
- **Unidade** (JUnit 5 + Mockito + AssertJ): todos os serviços desta change e os componentes de segurança (`JwtService`, `PasswordHasher`, `SecretProtector`, `LimiteRequisicoes`, `ClienteIpResolver`, validador de CNPJ, máscara de e-mail, rótulo de dispositivo). Repositórios e relógio entram como mocks (`Clock` injetado, para testar bloqueio e expiração).
- **Web:** `@WebMvcTest` por controller, com serviços mockados: status, envelope, mensagens e perfis, com o filtro de autenticação no contexto e um usuário falso.
- **Integração:** `@SpringBootTest` + Testcontainers (`mcr.microsoft.com/mssql/server:2022-latest`, a imagem já está no Docker local) para Flyway, repositórios com consultas nativas, fluxos completos de login e bloqueio e equivalência de esquema. Os testes de integração ficam na fase `verify` (failsafe, sufixo `IT`), para `mvn test` continuar rápido.
- **Compatibilidade** criptográfica e do JWT:
  - `contrato/vetores-dotnet/` é um console C# (.NET 8) que gera `vetores.json`;
  - inclui hashes PBKDF2 de senhas com e sem acento, segredos `enc:v1:` com textos conhecidos, um JWT com claims e segredo fixos e hashes de token de redefinição;
  - testes Java verificam e decifram esses vetores, e o inverso: o que o Java gera, o C# verifica no próprio console.

### D8. Entrega
- **`API/JAVA/Dockerfile`:** multi-stage `maven:3.9-eclipse-temurin-21` → `eclipse-temurin:21-jre`, usuário não-root, `TZ=America/Sao_Paulo`, `HEALTHCHECK` em `/actuator/health`.
- **`API/JAVA/docker-compose.homologacao.yml`:** SQL Server 2022 com volume + API Java com as variáveis de ambiente. O README explica como subir na VPS nova.
- **`.github/workflows/api-java.yml`:** dispara em push na `spring-api` com mudanças em `API/JAVA/**`; roda `mvn -B verify` e publica `ghcr.io/<dono>/quack-pdv-api-java:spring-api` e `:<sha>`, no mesmo padrão do `docker-publish.yml`. Não mexe no workflow atual.

### D9. Prova de conceito fiscal
- Projeto `API/JAVA/poc-fiscal` com a biblioteca candidata `br.com.swconsultoria:java-nfe`, versão 4.00.x mais recente.
- Monta uma venda de exemplo espelhando o que o `ZeusFiscalProvider.cs` envia:
  - emitente;
  - itens com NCM, CFOP, CST/CSOSN, PIS/COFINS e `CstIbsCbs`/`cClassTrib`;
  - desconto;
  - pagamentos em dinheiro e PIX;
  - consumidor sem CPF.
- **Sempre:** monta, assina com certificado autoassinado de teste e valida nos XSD.
- **Com variáveis de ambiente de homologação** (`POC_CERT_PFX_BASE64`, `POC_CERT_SENHA`, `POC_CSC_ID`, `POC_CSC_TOKEN`, `POC_UF`, `POC_CNPJ`): autoriza, consulta, cancela, inutiliza e faz contingência offline.
- **Saída:** `poc-fiscal/RELATORIO.md` com cada critério do spec `fiscal/biblioteca-java-nfe` como atendido, não atendido ou pendente de certificado, com a evidência.

## Risks / Trade-offs

- **[Hibernate e `DATETIMEOFFSET`/NVARCHAR mal mapeados]** → testes de integração com SQL Server real desde o grupo de banco; `ddl-auto=validate` derruba a subida se o tipo não bater.
- **[Diferença sutil de formato (data com 7 casas, nulos, mensagens)]** → comparador de contrato com gravação real; o formatador de data é centralizado.
- **[V1 gerado não ser idêntico ao esquema de produção]** → snapshot de esquema comparado em teste; antes do corte, comparar também com o banco de produção restaurado (Fase 9).
- **[Roteamento sem diferenciar maiúsculas conflitar com outras configurações do Spring]** → teste específico com caminhos em maiúsculas, minúsculas e com barra final em todas as rotas desta change.
- **[Limite por IP em memória vale só por instância]** → igual ao .NET; a homologação roda uma instância.
- **[Sem certificado A1 de homologação, a PoC fica parcial]** → o relatório marca os critérios pendentes; a decisão da Fase 7 depende de completá-los.
- **[Disco C: com pouco espaço na máquina de desenvolvimento]** → repositório Maven e imagens já existem; builds temporários no E:.

## Migration Plan

Nada vai para produção nesta change. Passos de entrega:
1. Merge dos artefatos na branch `spring-api`.
2. A CI publica a imagem `quack-pdv-api-java` no GHCR.
3. Opcional: subir na VPS de homologação com `docker-compose.homologacao.yml` e um banco restaurado de backup, conferindo o baseline do Flyway e o login com usuários reais.

Rollback: não se aplica (a API .NET segue atendendo a produção).

## Open Questions

- Certificado A1 e CSC de **homologação** para completar a PoC fiscal: se não houver agora, a PoC entrega a parte offline e o relatório fica com os critérios da SEFAZ pendentes.
