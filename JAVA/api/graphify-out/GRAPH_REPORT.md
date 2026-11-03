# Graph Report - JAVA  (2026-11-02)

## Corpus Check
- 430 files · ~143,063 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 2078 nodes · 5499 edges · 109 communities (83 shown, 26 thin omitted)
- Extraction: 78% EXTRACTED · 22% INFERRED · 0% AMBIGUOUS · INFERRED: 1195 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `b4e79b2d`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- Sessao
- JpaRepository
- ModuloMercadoService
- CategoriaService
- MotorDeContrato
- FornecedorService
- .gerar
- Empresa
- NegocioException.java
- UsuarioController.java
- Usuario
- AuditLog
- Security
- AuthController
- .mascaraSegredosEConservaValoresAusentes
- SessaoRepository
- ClienteTransacao
- GerenciamentoEmpresasRepository
- .verify
- ClienteTransacaoTest
- AuthControllerWebTest
- UsuarioAutenticado
- JwtService
- AutenticacaoServiceTest
- ApiResponse
- BancoIT
- PasswordResetToken
- PerfisPermitidos
- UsuarioRepository
- .getAuth
- CadastrosIT
- RecuperacaoSenhaIT
- .cadastrar
- RecuperacaoSenhaService
- Email
- QuackProperties
- LimiteRequisicoesFilterTest
- .verificar
- .solicitarAdministrativo
- AutenticacaoFilterWebTest
- SegurancaConfig.java
- FormatoDataDotNet.java
- RecuperacaoSenhaServiceTest
- AdministradorGeral
- GerenciamentoEmpresasService
- ClienteIpResolver
- RespostaJson
- ClienteController
- .enviarRedefinicaoSenha
- EmpresaService
- .tokenDe
- ApiIT
- ProtocoloHttpWebTest
- GlobalExceptionHandler
- .solicitar
- EmailService
- ControladorDeTeste.java
- ValidadorSessao
- SeedDevIT.java
- TelemetriaWebTest
- API Quack PDV (Spring Boot)
- GerenciamentoEmpresasServiceTest
- RoteamentoConfig.java
- ApiDocumentationController.java
- .enviarBoasVindas
- EmpresaController
- LimiteRequisicoesFilter
- WebTestBase
- LoginBloqueioIT
- .update
- ClienteWebTest
- CapturaJdbc
- OncePerRequestFilter
- .consumir
- AuthRequests.java
- ClienteIpResolverTest
- .seed
- AdminEmpresasWebTest
- ApiDocumentationWebTest
- CategoriaWebTest
- FornecedorWebTest
- ModuloMercadoWebTest
- TreinamentoWebTest
- Teste de contrato: API Java x API .NET
- ByteDotnetDeserializer.java
- NegocioException
- CabecalhosSegurancaFilter.java
- .registrar
- RepositoriosIT.java
- .resetarSenhaEmDevComEmailDesligadoDevolveOResultadoCompleto
- PerfisWebTest
- OpenApiConfig
- Relatório da prova de conceito fiscal (NFC-e) — biblioteca Java_NFe
- Recaptcha
- CnpjValidator.java
- EsquemaEquivalenteIT.java
- Test
- gerar-v1.sh
- RelogioConfig.java
- PasswordHasher
- SwaggerProducaoWebTest
- AmbienteFalso
- VetoresDotnet.csproj
- Dinheiro.java
- subir.sh
- descer.sh
- br.com.quack:poc-fiscal
- br.com.quack:quack-pdv-api

## God Nodes (most connected - your core abstractions)
1. `Empresa` - 112 edges
2. `QuackProperties` - 80 edges
3. `ApiResponse` - 77 edges
4. `Usuario` - 62 edges
5. `WebTestBase` - 48 edges
6. `PasswordResetToken` - 43 edges
7. `UsuarioRepository` - 41 edges
8. `UsuarioAutenticado` - 37 edges
9. `SessaoRepository` - 35 edges
10. `EmpresaRepository` - 34 edges

## Surprising Connections (you probably didn't know these)
- `ClienteService` --references--> `AuditoriaService`  [EXTRACTED]
  src/main/java/br/com/quack/pdv/cadastro/cliente/app/ClienteService.java → src/main/java/br/com/quack/pdv/auditoria/app/AuditoriaService.java
- `RepositoriosIT` --references--> `AuditoriaService`  [EXTRACTED]
  src/test/java/br/com/quack/pdv/banco/RepositoriosIT.java → src/main/java/br/com/quack/pdv/auditoria/app/AuditoriaService.java
- `ClienteAuditoriaTest` --references--> `AuditoriaService`  [EXTRACTED]
  src/test/java/br/com/quack/pdv/cadastro/ClienteAuditoriaTest.java → src/main/java/br/com/quack/pdv/auditoria/app/AuditoriaService.java
- `WebTestBase` --references--> `AuditoriaService`  [EXTRACTED]
  src/test/java/br/com/quack/pdv/web/WebTestBase.java → src/main/java/br/com/quack/pdv/auditoria/app/AuditoriaService.java
- `AutenticacaoService` --references--> `UsuarioRepository`  [EXTRACTED]
  src/main/java/br/com/quack/pdv/auth/app/AutenticacaoService.java → src/main/java/br/com/quack/pdv/usuario/repo/UsuarioRepository.java

## Import Cycles
- None detected.

## Communities (109 total, 26 thin omitted)

### Community 0 - "Sessao"
Cohesion: 0.06
Nodes (20): Service, Transactional, SessaoService, Entity, Table, Sessao, Modifying, Query (+12 more)

### Community 1 - "JpaRepository"
Cohesion: 0.06
Nodes (30): JpaRepository, NullAndEmptySource, Pattern, Service, Transactional, Secao, SecaoRequest, TreinamentoService (+22 more)

### Community 2 - "ModuloMercadoService"
Cohesion: 0.07
Nodes (31): Service, Transactional, Kpi, ModuloMercadoService, Painel, Registro, Entity, Table (+23 more)

### Community 3 - "CategoriaService"
Cohesion: 0.08
Nodes (14): CategoriaService, Service, Transactional, Categoria, Entity, Table, CategoriaRepository, Modifying (+6 more)

### Community 4 - "MotorDeContrato"
Cohesion: 0.06
Nodes (29): ApplicationArguments, ApplicationRunner, ConfigurationPropertiesScan, DynamicTest, EnabledIfSystemProperty, HttpClient, JsonNodeType, ObjectNode (+21 more)

### Community 5 - "FornecedorService"
Cohesion: 0.08
Nodes (22): FornecedorService, Service, Transactional, Fornecedor, Entity, Table, FornecedorRepository, Query (+14 more)

### Community 6 - ".gerar"
Cohesion: 0.07
Nodes (22): AmbienteEnum, EstadosEnum, FunctionalInterface, Certificados, Certificado, Certificado, ConfiguracoesNfe, TEnviNFe (+14 more)

### Community 8 - "NegocioException.java"
Cohesion: 0.08
Nodes (17): EmpresaTemaService, Service, Transactional, Tema, EmpresaTema, Entity, Table, EmpresaTemaRepository (+9 more)

### Community 9 - "UsuarioController.java"
Cohesion: 0.11
Nodes (17): AutenticacaoFilter, FilterChain, HttpServletRequest, HttpServletResponse, Override, Perfis, GetMapping, HttpServletRequest (+9 more)

### Community 10 - "Usuario"
Cohesion: 0.14
Nodes (3): Transactional, Usuario, UsuarioRequest

### Community 12 - "AuditLog"
Cohesion: 0.10
Nodes (7): AuditoriaService, Service, Transactional, AuditLog, Entity, Table, AuditLogRepository

### Community 13 - "Security"
Cohesion: 0.13
Nodes (6): ConfigurationProperties, Auth, RateLimit, Security, Test, QuackPropertiesTest

### Community 14 - "AuthController"
Cohesion: 0.22
Nodes (11): AuthController, GetMapping, HttpServletRequest, HttpServletResponse, Logger, PostMapping, PutMapping, RequestMapping (+3 more)

### Community 15 - ".mascaraSegredosEConservaValoresAusentes"
Cohesion: 0.11
Nodes (9): Entity, Table, EmpresaRequest, EmpresaServiceTest, Arguments, BeforeEach, MethodSource, ParameterizedTest (+1 more)

### Community 16 - "SessaoRepository"
Cohesion: 0.16
Nodes (13): AutenticacaoService, Service, TransactionOperations, CadastroPublicoService, Service, EmpresaRepository, SessaoRepository, Service (+5 more)

### Community 17 - "ClienteTransacao"
Cohesion: 0.17
Nodes (9): Alteracao, ClienteTransacao, ObjectMapper, Service, Transactional, Snapshot, Cliente, Entity (+1 more)

### Community 18 - "GerenciamentoEmpresasRepository"
Cohesion: 0.11
Nodes (13): NamedParameterJdbcTemplate, Repository, ResultSet, RowMapper, Credenciais, GerenciamentoEmpresasRepository, JdbcTemplate, Transactional (+5 more)

### Community 20 - "ClienteTransacaoTest"
Cohesion: 0.16
Nodes (8): ClienteRepository, Query, ClienteTransacaoTest, Arguments, BeforeEach, MethodSource, ParameterizedTest, Test

### Community 21 - "AuthControllerWebTest"
Cohesion: 0.19
Nodes (4): ResultActions, LoginSucesso, AuthControllerWebTest, Test

### Community 22 - "UsuarioAutenticado"
Cohesion: 0.15
Nodes (8): ClienteService, Logger, Service, ClienteDto, ClienteRequest, UsuarioAutenticado, ClienteAuditoriaTest, Test

### Community 23 - "JwtService"
Cohesion: 0.17
Nodes (7): Component, JsonNode, ObjectMapper, JwtService, BeforeEach, Test, JwtServiceTest

### Community 25 - "ApiResponse"
Cohesion: 0.20
Nodes (12): Configuracao, Credenciais, GerenciamentoEmpresasController, DeleteMapping, GetMapping, HttpServletRequest, PostMapping, PutMapping (+4 more)

### Community 26 - "BancoIT"
Cohesion: 0.16
Nodes (12): Flyway, MSSQLServerContainer, PreparedStatementCallback, BaselineBancoRestauradoIT, Connection, Test, DynamicPropertyRegistry, DynamicPropertySource (+4 more)

### Community 27 - "PasswordResetToken"
Cohesion: 0.11
Nodes (3): Entity, Table, PasswordResetToken

### Community 28 - "PerfisPermitidos"
Cohesion: 0.16
Nodes (15): Documented, Retention, CategoriaController, DeleteMapping, GetMapping, HttpServletRequest, PatchMapping, PostMapping (+7 more)

### Community 29 - "UsuarioRepository"
Cohesion: 0.15
Nodes (4): FormatoO, Modifying, Query, UsuarioRepository

### Community 30 - ".getAuth"
Cohesion: 0.19
Nodes (4): CompatibilidadeDotnetTest, BeforeAll, JsonNode, Test

### Community 31 - "CadastrosIT"
Cohesion: 0.19
Nodes (6): Autowired, CadastrosIT, ActiveProfiles, JdbcTemplate, SpringBootTest, Test

### Community 32 - "RecuperacaoSenhaIT"
Cohesion: 0.19
Nodes (9): Part, BeforeEach, DynamicPropertyRegistry, DynamicPropertySource, GreenMailExtension, MvcResult, Pattern, Test (+1 more)

### Community 33 - ".cadastrar"
Cohesion: 0.23
Nodes (6): Transactional, Register, Modifying, Query, CadastroPublicoServiceTest, Test

### Community 34 - "RecuperacaoSenhaService"
Cohesion: 0.15
Nodes (8): Service, TransactionOperations, RecuperacaoSenhaService, PasswordResetTokenRepository, RotuloDispositivo, SecureRandom, TokenRedefinicao, BeforeEach

### Community 36 - "QuackProperties"
Cohesion: 0.19
Nodes (10): Builder, MockRestServiceServer, RestClient, ObjectMapper, Service, RecaptchaService, QuackProperties, BeforeEach (+2 more)

### Community 37 - "LimiteRequisicoesFilterTest"
Cohesion: 0.22
Nodes (7): MockHttpServletRequest, MockHttpServletResponse, BeforeEach, Override, Test, LimiteRequisicoesFilterTest, RelogioMutavel

### Community 38 - ".verificar"
Cohesion: 0.26
Nodes (4): Resultado, Test, RecaptchaServiceTest, BeforeEach

### Community 39 - ".solicitarAdministrativo"
Cohesion: 0.28
Nodes (4): RedefinicaoAdministrativa, Supervisor, Test, UsuarioControllerWebTest

### Community 40 - "AutenticacaoFilterWebTest"
Cohesion: 0.22
Nodes (3): Validacao, AutenticacaoFilterWebTest, Test

### Community 41 - "SegurancaConfig.java"
Cohesion: 0.18
Nodes (8): CorsConfigurationSource, HttpSecurity, InterceptorRegistry, SecurityFilterChain, Bean, Configuration, Override, SegurancaConfig

### Community 42 - "FormatoDataDotNet.java"
Cohesion: 0.17
Nodes (10): Jackson2ObjectMapperBuilderCustomizer, JsonGenerator, SerializerProvider, JacksonConfig, Bean, Configuration, FormatoDataDotNet, Override (+2 more)

### Community 44 - "AdministradorGeral"
Cohesion: 0.32
Nodes (4): AdministradorGeral, Component, BaseCadastrosTest, Test

### Community 45 - "GerenciamentoEmpresasService"
Cohesion: 0.24
Nodes (5): Acao, GerenciamentoEmpresasService, HttpStatus, Logger, Service

### Community 46 - "ClienteIpResolver"
Cohesion: 0.18
Nodes (9): ClienteIpResolver, Component, HttpServletRequest, FilterChain, HttpServletRequest, HttpServletResponse, Logger, Override (+1 more)

### Community 47 - "RespostaJson"
Cohesion: 0.20
Nodes (9): HandlerInterceptor, HttpServletRequest, HttpServletResponse, Override, PerfisInterceptor, Component, HttpServletResponse, ObjectMapper (+1 more)

### Community 48 - "ClienteController"
Cohesion: 0.24
Nodes (9): ClienteController, DeleteMapping, GetMapping, HttpServletRequest, PostMapping, PutMapping, RequestMapping, ResponseEntity (+1 more)

### Community 49 - ".enviarRedefinicaoSenha"
Cohesion: 0.24
Nodes (4): EmailServiceTest, BeforeEach, GreenMailExtension, Test

### Community 50 - "EmpresaService"
Cohesion: 0.17
Nodes (6): EmpresaService, Service, Transactional, Dados, Component, LeitorCertificado

### Community 51 - ".tokenDe"
Cohesion: 0.30
Nodes (4): EmpresaCadastrosWebTest, ParameterizedTest, Test, ValueSource

### Community 52 - "ApiIT"
Cohesion: 0.22
Nodes (10): ApiIT, AutoConfigureMockMvc, BeforeEach, HttpHeaders, JdbcTemplate, JsonNode, MockMvc, MvcResult (+2 more)

### Community 54 - "GlobalExceptionHandler"
Cohesion: 0.28
Nodes (7): ExceptionHandler, MediaType, RestControllerAdvice, GlobalExceptionHandler, HttpServletRequest, Logger, ResponseEntity

### Community 56 - "EmailService"
Cohesion: 0.23
Nodes (7): EmailService, Logger, Service, SmtpSettings, Component, SecureRandom, SecretProtector

### Community 57 - "ControladorDeTeste.java"
Cohesion: 0.29
Nodes (7): ControladorDeTeste, Corpo, GetMapping, PostMapping, RequestMapping, ResponseEntity, RestController

### Community 58 - "ValidadorSessao"
Cohesion: 0.22
Nodes (4): Service, Transactional, ValidadorSessao, ValidacaoRequisicaoView

### Community 59 - "SeedDevIT.java"
Cohesion: 0.22
Nodes (9): DynamicUpdate, Entity, Table, ActiveProfiles, DynamicPropertyRegistry, DynamicPropertySource, SpringBootTest, Test (+1 more)

### Community 60 - "TelemetriaWebTest"
Cohesion: 0.29
Nodes (7): AfterEach, ILoggingEvent, ListAppender, BeforeEach, Logger, Test, TelemetriaWebTest

### Community 61 - "API Quack PDV (Spring Boot)"
Cohesion: 0.17
Nodes (11): API Quack PDV (Spring Boot), Banco restaurado da produção (baseline), Como compilar e testar, Como o banco nasce (Flyway), Como rodar em desenvolvimento (perfil `dev`), Compatibilidade com a API .NET, Estrutura, Homologação (VPS nova) (+3 more)

### Community 63 - "RoteamentoConfig.java"
Cohesion: 0.29
Nodes (8): FilterRegistrationBean, PathMatchConfigurer, Bean, Configuration, Override, RoteamentoConfig, UrlHandlerFilter, WebMvcConfigurer

### Community 64 - "ApiDocumentationController.java"
Cohesion: 0.27
Nodes (8): OpenApiWebMvcResource, ApiDocumentationController, GetMapping, HttpServletRequest, ObjectMapper, RequestMapping, ResponseEntity, RestController

### Community 66 - "EmpresaController"
Cohesion: 0.27
Nodes (6): EmpresaController, GetMapping, HttpServletRequest, PutMapping, RequestMapping, RestController

### Community 67 - "LimiteRequisicoesFilter"
Cohesion: 0.31
Nodes (6): Balde, FilterChain, HttpServletRequest, HttpServletResponse, Override, LimiteRequisicoesFilter

### Community 68 - "WebTestBase"
Cohesion: 0.29
Nodes (8): ActiveProfiles, Test, SwaggerDevWebTest, ActiveProfiles, AutoConfigureMockMvc, MockMvc, SpringBootTest, WebTestBase

### Community 71 - "ClienteWebTest"
Cohesion: 0.33
Nodes (4): ClienteWebTest, ParameterizedTest, Test, ValueSource

### Community 72 - "CapturaJdbc"
Cohesion: 0.39
Nodes (5): JdbcTemplate, PreparedStatement, CapturaJdbc, GerenciamentoEmpresasSqlTest, Test

### Community 73 - "OncePerRequestFilter"
Cohesion: 0.33
Nodes (6): OncePerRequestFilter, FilterChain, HttpServletRequest, HttpServletResponse, Override, LimiteCorpoFilter

### Community 75 - "AuthRequests.java"
Cohesion: 0.22
Nodes (6): AuthRequests, ChangePassword, ForgotPassword, Login, ResetPassword, UpdateProfile

### Community 77 - ".seed"
Cohesion: 0.36
Nodes (3): BeforeEach, EstadoDoBanco, Connection

### Community 78 - "AdminEmpresasWebTest"
Cohesion: 0.36
Nodes (4): AdminEmpresasWebTest, ParameterizedTest, Test, ValueSource

### Community 79 - "ApiDocumentationWebTest"
Cohesion: 0.36
Nodes (4): ApiDocumentationWebTest, ParameterizedTest, Test, ValueSource

### Community 80 - "CategoriaWebTest"
Cohesion: 0.42
Nodes (4): CategoriaWebTest, ParameterizedTest, Test, ValueSource

### Community 81 - "FornecedorWebTest"
Cohesion: 0.36
Nodes (4): FornecedorWebTest, ParameterizedTest, Test, ValueSource

### Community 82 - "ModuloMercadoWebTest"
Cohesion: 0.36
Nodes (4): ParameterizedTest, Test, ValueSource, ModuloMercadoWebTest

### Community 83 - "TreinamentoWebTest"
Cohesion: 0.36
Nodes (4): ParameterizedTest, Test, ValueSource, TreinamentoWebTest

### Community 84 - "Teste de contrato: API Java x API .NET"
Cohesion: 0.25
Nodes (7): Divergência conhecida: bloqueio de login com a senha certa, Fase 2: cadastros, Formato do cenário, Gravar um grupo novo (ou regravar), Rodar o comparador (só precisa de Docker), Teste de contrato: API Java x API .NET, Vetores de compatibilidade criptográfica

### Community 85 - "ByteDotnetDeserializer.java"
Cohesion: 0.43
Nodes (5): DeserializationContext, JsonDeserializer, JsonParser, ByteDotnetDeserializer, Override

### Community 87 - "CabecalhosSegurancaFilter.java"
Cohesion: 0.43
Nodes (5): CabecalhosSegurancaFilter, FilterChain, HttpServletRequest, HttpServletResponse, Override

### Community 88 - ".registrar"
Cohesion: 0.50
Nodes (3): CadastroPublicoIT, MvcResult, Test

### Community 89 - "RepositoriosIT.java"
Cohesion: 0.39
Nodes (5): JdbcTemplate, SpringBootTest, Test, Transactional, RepositoriosIT

### Community 90 - ".resetarSenhaEmDevComEmailDesligadoDevolveOResultadoCompleto"
Cohesion: 0.46
Nodes (3): AuthDevWebTest, ActiveProfiles, Test

### Community 92 - "OpenApiConfig"
Cohesion: 0.40
Nodes (4): OpenAPI, Bean, Configuration, OpenApiConfig

### Community 93 - "Relatório da prova de conceito fiscal (NFC-e) — biblioteca Java_NFe"
Cohesion: 0.33
Nodes (5): Como rodar, Pontos de atenção da biblioteca, Recomendação, Relatório da prova de conceito fiscal (NFC-e) — biblioteca Java_NFe, Resultado por critério

### Community 96 - "EsquemaEquivalenteIT.java"
Cohesion: 0.53
Nodes (4): EsquemaEquivalenteIT, JdbcTemplate, SpringBootTest, Test

### Community 98 - "gerar-v1.sh"
Cohesion: 0.40
Nodes (3): DOTNET_ROLL_FORWARD, MSYS_NO_PATHCONV, gerar-v1.sh script

### Community 99 - "RelogioConfig.java"
Cohesion: 0.60
Nodes (3): Bean, Configuration, RelogioConfig

### Community 102 - "AmbienteFalso"
Cohesion: 0.50
Nodes (3): AmbienteFalso, IFileProvider, IWebHostEnvironment

## Knowledge Gaps
- **29 isolated node(s):** `descer.sh script`, `subir.sh script`, `MSYS_NO_PATHCONV`, `gerar-v1.sh script`, `MSYS_NO_PATHCONV` (+24 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **26 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `WebTestBase` connect `WebTestBase` to `Sessao`, `JpaRepository`, `ModuloMercadoService`, `CategoriaService`, `FornecedorService`, `NegocioException.java`, `AuditLog`, `SessaoRepository`, `ClienteTransacao`, `GerenciamentoEmpresasRepository`, `AuthControllerWebTest`, `UsuarioAutenticado`, `JwtService`, `RecuperacaoSenhaService`, `QuackProperties`, `.verificar`, `.solicitarAdministrativo`, `AutenticacaoFilterWebTest`, `GerenciamentoEmpresasService`, `EmpresaService`, `.tokenDe`, `ProtocoloHttpWebTest`, `EmailService`, `ValidadorSessao`, `TelemetriaWebTest`, `ClienteWebTest`, `AdminEmpresasWebTest`, `ApiDocumentationWebTest`, `CategoriaWebTest`, `FornecedorWebTest`, `ModuloMercadoWebTest`, `TreinamentoWebTest`, `.resetarSenhaEmDevComEmailDesligadoDevolveOResultadoCompleto`, `PerfisWebTest`, `SwaggerProducaoWebTest`?**
  _High betweenness centrality (0.213) - this node is a cross-community bridge._
- **Why does `QuackProperties` connect `QuackProperties` to `MotorDeContrato`, `UsuarioController.java`, `Security`, `AuthController`, `JwtService`, `.getAuth`, `RecuperacaoSenhaService`, `Email`, `LimiteRequisicoesFilterTest`, `.verificar`, `SegurancaConfig.java`, `RecuperacaoSenhaServiceTest`, `ClienteIpResolver`, `.enviarRedefinicaoSenha`, `GlobalExceptionHandler`, `EmailService`, `ValidadorSessao`, `LimiteRequisicoesFilter`, `OncePerRequestFilter`, `ClienteIpResolverTest`, `Recaptcha`?**
  _High betweenness centrality (0.128) - this node is a cross-community bridge._
- **Why does `ApiResponse` connect `ApiResponse` to `Sessao`, `JpaRepository`, `ModuloMercadoService`, `EmpresaController`, `FornecedorService`, `NegocioException.java`, `UsuarioController.java`, `AuthController`, `RespostaJson`, `ClienteController`, `NegocioException`, `ControladorDeTeste.java`, `PerfisPermitidos`?**
  _High betweenness centrality (0.099) - this node is a cross-community bridge._
- **Are the 4 inferred relationships involving `Empresa` (e.g. with `.empresaSemSmtpHabilitadoCaiNaConfiguracaoGlobal()` and `.senhaSmtpDaEmpresaCifradaPeloDotNetEDecifradaParaEnviar()`) actually correct?**
  _`Empresa` has 4 INFERRED edges - model-reasoned connections that need verification._
- **Are the 9 inferred relationships involving `QuackProperties` (e.g. with `.devNaoValida()` and `.limitesAjustamOsValores()`) actually correct?**
  _`QuackProperties` has 9 INFERRED edges - model-reasoned connections that need verification._
- **Are the 4 inferred relationships involving `Usuario` (e.g. with `.sessaoEValidacaoDeRequisicao()` and `.tentativasFalhasAtomicasBloqueiamNaQuinta()`) actually correct?**
  _`Usuario` has 4 INFERRED edges - model-reasoned connections that need verification._
- **What connects `descer.sh script`, `subir.sh script`, `MSYS_NO_PATHCONV` to the rest of the system?**
  _29 weakly-connected nodes found - possible documentation gaps or missing edges._