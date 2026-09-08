# Graph Report - horus_pdv  (2026-09-06)

## Corpus Check
- 283 files · ~274,999 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 2642 nodes · 4678 edges · 180 communities (161 shown, 11 thin omitted)
- Extraction: 93% EXTRACTED · 7% INFERRED · 0% AMBIGUOUS · INFERRED: 329 edges (avg confidence: 0.81)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- API Controllers Core
- API Controllers Logging
- Database Schemas XSD
- API Specs Validation
- Frontend Page Components
- Database Connection Utils
- Empresa Request Models
- Empresa Domain Entities
- Advanced Module Specs
- Fiscal Contracts Models
- NF-e Import Controller
- Fiscal Document Processing
- Fiscal Service Config
- KPI Trend Card UI
- Emitente Fiscal Context
- User Management Constants
- Auth Form Fields
- Database Operations Core
- Reports Module
- NF-e Import Modal
- Input Masks Formatters
- Auth Middleware Pipeline
- Produto Model
- Produto Domain Entity
- Produto Request DTO
- Item Fiscal Model
- Emitente Context Config
- TypeScript App Config
- Client IP Middleware
- Security Store Auth
- SQL Connection Async
- Controller Routing
- Home About Controllers
- NF-e Import Data Models
- Cancelamento Request
- API Controllers Registry
- Pedido Domain Entity
- NFC-e Outbox Worker
- Item Fiscal Attributes
- Fiscal Service Runtime
- Cliente Model
- Cliente Domain Entity
- NPM Package Config
- Status Dialog UI
- Node TS Config
- Session Controller
- Cliente Request DTO
- Venda History Entity
- SQL Data Readers
- Request Middleware Chain
- Pedido Model DTO
- SQL Connection Pool
- SQL Reader Helpers
- File Upload Controller
- Fornecedor Domain Entity
- Cliente Repository
- Connection DateTime Ops
- Fiscal Provider Interface
- Home Relatorio Controller
- Fornecedor Model
- Fornecedor Request DTO
- Fiscal Store Provider
- Dev Dependencies Config
- Row Actions Menu UI
- Controller Program Setup
- CRUD API Endpoints
- Documento Fiscal Views
- SQL Command Readers
- ReCAPTCHA Service
- API Client HTTP
- CEP Lookup Service
- Caixa Controller
- NF-e XML Parser
- Build Scripts Config
- Market Module Page
- CRUD Controller A
- CRUD Controller B
- Logging Controller
- CRUD Controller C
- Rate Limit Middleware
- NF-e Import Item Preview
- Venda Request Model
- Password Reset Admin
- DatePicker Component
- TimePicker Component
- Connection DateTime Read
- Security User DTO
- Cliente Service Layer
- Produto Service Layer
- Print Settings Card
- Sales Start Page
- Update Controller
- Auth Log Controller
- Caixa Session Entity
- Caixa Session DTO
- Fornecedor Service Layer
- Resultado Fiscal Model
- User Profile Controller
- Usuario Request DTO
- Database Initializer
- Status Fiscal Enum A
- Status Fiscal Enum B
- Frontend HTML Entry
- Product Register Page
- User Accounts Page
- Auth Register Request
- Caixa Status DTO
- Fiscal Contracts Dest
- React Dependencies
- Receipt Preview Modal
- NF-e Import Service
- NFC-e Request Models
- Pedido Request DTO
- Caixa Service Core
- Searchable Select Field
- Tour Steps Guide
- Fiscal Service DTOs
- Cliente Controller
- Fornecedor Controller
- API NFC-e Config
- SQL Reader Mapper
- ICliente Service
- IFornecedor Service
- IProduto Service
- Auth Storage Utils
- Vite Build Config
- Login Request Model
- Password Reset Request
- DB Connection Builder
- Pedido Item Entity
- SQL Transaction Auth
- Money Format Preview
- Cancelamento Fiscal
- Inutilizacao Fiscal
- Pagamento Fiscal Model
- UI Screen Images A
- UI Screen Images B
- UI Screen Nav Images
- App Navigation Events
- Cash Register Page
- Customer Register Page
- My Company Page
- Novo Pedido Page
- Sales History Page
- Supplier Register Page
- Auth Service Frontend
- Pedido Service Frontend
- Forgot Password Request
- Venda Item Record
- Secret Protector Crypto
- UI Screen Images C
- DANFE Preview Modal
- ReCAPTCHA v3 Hook
- Update Profile Request
- Change Password Request
- Playwright Test Config
- App Entry Point
- Table Pagination UI
- Fiscal Page Frontend
- Cash Register Service
- Customer Service Frontend
- Product Service Frontend
- Sales History Service
- Supplier Service Frontend
- Balanca Barcode Parser
- PDV Preferences Store
- Graphify Skill Config
- Company Service Frontend
- TSConfig References
- Project Documentation A
- Project Documentation B
- Project Documentation C

## God Nodes (most connected - your core abstractions)
1. `ApiResponse` - 62 edges
2. `HorusSecurityStore` - 56 edges
3. `EmpresaAD` - 51 edges
4. `EmpresaRequest` - 48 edges
5. `lucide-react` - 48 edges
6. `react` - 45 edges
7. `ContextoEmitente` - 44 edges
8. `HORUSPDV_API.Models.Requests` - 39 edges
9. `ProdutoAD` - 38 edges
10. `DocumentoFiscalAB` - 36 edges

## Surprising Connections (you probably didn't know these)
- `Graphify Skill - Claude Code knowledge graph generation skill` --semantically_similar_to--> `Graphify Skill (Codex) - Knowledge graph generation skill for Codex`  [INFERRED] [semantically similar]
  .claude/skills/graphify/SKILL.md → .codex/skills/graphify/SKILL.md
- `GitHub Pages Landing Page` --conceptually_related_to--> `Horus PDV Project`  [INFERRED]
  docs/index.html → README.md
- `NFC-e Fiscal Module (Modelo 65)` --conceptually_related_to--> `SEFAZ-RJ / SVRS (Tax Authority)`  [INFERRED]
  MODULO-FISCAL-E-PEDIDOS.md → doc fiscal pdv/README-FISCAL.md
- `ResultadoFiscal` --references--> `StatusDocumentoFiscal`  [EXTRACTED]
  doc fiscal pdv/FiscalContracts.cs → API/NETCORE/Services/Fiscal/FiscalContracts.cs
- `DocumentoFiscalPendente` --references--> `TipoEmissaoFiscal`  [EXTRACTED]
  doc fiscal pdv/NfceOutboxWorker.cs → API/NETCORE/Services/Fiscal/FiscalContracts.cs

## Import Cycles
- None detected.

## Communities (180 total, 11 thin omitted)

### Community 0 - "API Controllers Core"
Cohesion: 0.06
Nodes (38): HttpDelete, HttpGet, HttpPost, HttpPut, IActionResult, ProducesResponseType, Task, ModuloMercadoController (+30 more)

### Community 1 - "API Controllers Logging"
Cohesion: 0.06
Nodes (37): HttpGet, HttpPost, HttpPut, IActionResult, ILogger, IWebHostEnvironment, Task, UsuarioController (+29 more)

### Community 2 - "Database Schemas XSD"
Cohesion: 0.05
Nodes (53): XSD Schemas NF-e/NFC-e v4.00, Database Tables Schema, API .NET README, Repository Pattern (AD/AB/Services/Controllers), Resumo.sql (Database Init Script), SMTP Email Configuration, api-pdv.wootchat.com.br (API), EncryptionKey (AES-GCM Secrets) (+45 more)

### Community 3 - "API Specs Validation"
Cohesion: 0.08
Nodes (46): api(), ApiResponse, cleanupSql(), cnpjDigit(), Company, cpfDigit(), createCustomer(), createProduct() (+38 more)

### Community 4 - "Frontend Page Components"
Cohesion: 0.04
Nodes (25): AboutPdvPage, CashRegisterPage, CrmLoyaltyPage, CurrentUser, CustomerRegisterPage, EditProfilePage, FiscalPage, HomePage (+17 more)

### Community 5 - "Database Connection Utils"
Cohesion: 0.16
Nodes (19): Connection, CultureInfo, DateTimeOffset, Dictionary, JsonElement, List, SqlDataReader, Task (+11 more)

### Community 6 - "Empresa Request Models"
Cohesion: 0.05
Nodes (44): EmpresaRequest, Address, AmbienteFiscal, Cep, CertificadoHasValue, CertificadoPfxBase64, CertificadoSenha, CertificadoThumbprint (+36 more)

### Community 7 - "Empresa Domain Entities"
Cohesion: 0.05
Nodes (42): DateTimeOffset, EmpresaAD, Address, AmbienteFiscal, Cep, CertificadoPfxBase64, CertificadoSenha, CertificadoThumbprint (+34 more)

### Community 8 - "Advanced Module Specs"
Cohesion: 0.11
Nodes (39): advancedModulesCrud(), api(), ApiResponse, caption(), cardAction(), cleanupSql(), closeCurrentCashIfNeeded(), cnpjDigit() (+31 more)

### Community 9 - "Fiscal Contracts Models"
Cohesion: 0.06
Nodes (39): DateTimeOffset, IReadOnlyList, DestinatarioFiscal, CpfCnpj, IndIeDest, InscricaoEstadual, Nome, EmissaoNfceRequest (+31 more)

### Community 10 - "NF-e Import Controller"
Cohesion: 0.07
Nodes (34): HttpPost, IActionResult, ProducesResponseType, Task, NfeImportController, List, NfeImportConfirmRequest, Fornecedor (+26 more)

### Community 11 - "Fiscal Document Processing"
Cohesion: 0.14
Nodes (14): CancellationToken, Connection, DateTimeOffset, DestinatarioFiscal, DocumentoFiscalPendente, EmissaoNfceRequest, List, SqlConnection (+6 more)

### Community 12 - "Fiscal Service Config"
Cohesion: 0.11
Nodes (18): CancellationToken, ConfiguracaoServico, CultureInfo, det, ICMSBasico, ILogger, IWebHostEnvironment, NFe (+10 more)

### Community 13 - "KPI Trend Card UI"
Cohesion: 0.06
Nodes (18): buildTrendPath(), KpiTrendCard(), KpiTrendCardProps, toSafeGradientId(), SkeletonProps, AddressContactFieldsProps, AddressContactValue, STATE_OPTIONS (+10 more)

### Community 14 - "Emitente Fiscal Context"
Cohesion: 0.06
Nodes (32): ContextoEmitente, Ambiente, Bairro, Cep, CertificadoPfx, CertificadoSenha, Cnae, Cnpj (+24 more)

### Community 15 - "User Management Constants"
Cohesion: 0.12
Nodes (19): ROLE_LABEL, STATUS_LABEL, DeactivateUserReasonDialogProps, AdminUser, UserRole, UserRoleFilter, UserStatus, UserStatusFilter (+11 more)

### Community 16 - "Auth Form Fields"
Cohesion: 0.13
Nodes (23): CnpjField(), EmailField(), FeedbackMessage(), FieldProps, PasswordField(), AuthLayout(), AuthLayoutProps, ForgotPasswordPage() (+15 more)

### Community 17 - "Database Operations Core"
Cohesion: 0.21
Nodes (5): Dictionary, List, SqlCommand, TimeSpan, HorusSecurityStore

### Community 18 - "Reports Module"
Cohesion: 0.11
Nodes (24): ReportCardsGridProps, createInitialFilterValues(), escapeHtml(), exportToExcel(), exportToPdf(), FilterValue, formatTableCellValue(), getDaysAgoIso() (+16 more)

### Community 19 - "NF-e Import Modal"
Cohesion: 0.07
Nodes (12): EditableItem, PageHeaderProps, UnderDevelopmentPageProps, LoadingButtonProps, clamp(), findTarget(), GuidedTour(), GuidedTourProps (+4 more)

### Community 20 - "Input Masks Formatters"
Cohesion: 0.13
Nodes (24): BRL_CURRENCY_FORMATTER, hasCurrencyInput(), maskCellphoneBr(), maskCep(), maskCnpj(), maskCpf(), maskCpfOrCnpj(), maskCurrencyBr() (+16 more)

### Community 21 - "Auth Middleware Pipeline"
Cohesion: 0.10
Nodes (18): HttpContext, IWebHostEnvironment, RequestDelegate, Task, HorusAuthMiddleware, HorusAuthorizeRolesAttribute, Roles, AuthenticatedUser (+10 more)

### Community 22 - "Produto Model"
Cohesion: 0.07
Nodes (27): ProdutoModel, AliquotaIcms, CClassTrib, Cest, Cfop, CsosnIcms, CstCofins, CstIbsCbs (+19 more)

### Community 23 - "Produto Domain Entity"
Cohesion: 0.07
Nodes (27): ProdutoAD, AliquotaIcms, CClassTrib, Cest, Cfop, CsosnIcms, CstCofins, CstIbsCbs (+19 more)

### Community 24 - "Produto Request DTO"
Cohesion: 0.08
Nodes (26): ProdutoRequest, AliquotaIcms, CClassTrib, Cest, Cfop, CsosnIcms, CstCofins, CstIbsCbs (+18 more)

### Community 25 - "Item Fiscal Model"
Cohesion: 0.09
Nodes (23): ItemFiscal, AliquotaIcms, CClassTrib, Cest, Cfop, CodigoProduto, Csosn, CstCofins (+15 more)

### Community 26 - "Emitente Context Config"
Cohesion: 0.08
Nodes (26): ContextoEmitente, Ambiente, Bairro, Cep, CertificadoPfx, CertificadoSenha, Cnae, Cnpj (+18 more)

### Community 27 - "TypeScript App Config"
Cohesion: 0.08
Nodes (25): compilerOptions, allowImportingTsExtensions, baseUrl, erasableSyntaxOnly, ignoreDeprecations, jsx, lib, module (+17 more)

### Community 28 - "Client IP Middleware"
Cohesion: 0.09
Nodes (20): HttpContext, Task, HttpContext, HorusClientIpResolver, IConfiguration, IWebHostEnvironment, HorusSecurityOptions, EncryptionKey (+12 more)

### Community 29 - "Security Store Auth"
Cohesion: 0.12
Nodes (18): DateTimeOffset, LoginAttemptBucket, Count, FirstAttemptAt, LastAttemptAt, LockedUntil, LoginResult, PasswordHasher (+10 more)

### Community 30 - "SQL Connection Async"
Cohesion: 0.19
Nodes (12): CancellationToken, SqlConnection, Task, Connection, IEnumerable, List, SqlCommand, Task (+4 more)

### Community 31 - "Controller Routing"
Cohesion: 0.12
Nodes (7): HORUSPDV_API.Repositories.DatabaseAccess, HORUSPDV_API.Controllers.ModuloMercado, HORUSPDV_API.Services.Email, HORUSPDV_API.Repositories.DataAccess, HORUSPDV_API.Controllers.Usuario, HORUSPDV_API.Controllers.Auth, HORUSPDV_API.Controllers.Empresa

### Community 32 - "Home About Controllers"
Cohesion: 0.13
Nodes (15): HttpGet, IActionResult, Task, Connection, CultureInfo, DateTimeOffset, List, SqlDataReader (+7 more)

### Community 33 - "NF-e Import Data Models"
Cohesion: 0.09
Nodes (22): List, NfeImportFornecedorPreview, Address, Cep, City, Cnpj, CompanyName, FantasyName (+14 more)

### Community 34 - "Cancelamento Request"
Cohesion: 0.12
Nodes (19): CancelamentoRequest, ChaveAcesso, Emitente, Justificativa, Protocolo, SequenciaEvento, ResultadoFiscal, ChaveAcesso (+11 more)

### Community 35 - "API Controllers Registry"
Cohesion: 0.16
Nodes (8): HORUSPDV_API.Models.Requests, HORUSPDV_API.Controllers.Fiscal, HORUSPDV_API.Models.Produtos, HORUSPDV_API.Controllers.Produtos, HORUSPDV_API.Services.Produtos, HORUSPDV_API.Controllers.Pedidos, HORUSPDV_API.Services.Shared, HORUSPDV_API.Models.Response

### Community 36 - "Pedido Domain Entity"
Cohesion: 0.10
Nodes (20): DateTimeOffset, List, PedidoAD, CompanyId, CreatedAt, CustomerCpf, CustomerName, FinalizedAt (+12 more)

### Community 37 - "NFC-e Outbox Worker"
Cohesion: 0.13
Nodes (17): CancellationToken, DateTimeOffset, ILogger, IServiceScopeFactory, Task, TimeSpan, DocumentoFiscalPendente, CompanyId (+9 more)

### Community 38 - "Item Fiscal Attributes"
Cohesion: 0.10
Nodes (21): ItemFiscal, AliquotaIcms, CClassTrib, Cest, Cfop, CodigoProduto, Csosn, CstCofins (+13 more)

### Community 39 - "Fiscal Service Runtime"
Cohesion: 0.19
Nodes (11): CancellationToken, ConfiguracaoServico, CultureInfo, ILogger, IWebHostEnvironment, NFe, ResultadoFiscal, Task (+3 more)

### Community 40 - "Cliente Model"
Cohesion: 0.10
Nodes (20): ClienteModel, Address, Age, BirthDate, Cellphone, Cep, City, CodigoMunicipioIbge (+12 more)

### Community 41 - "Cliente Domain Entity"
Cohesion: 0.10
Nodes (20): ClienteAD, Address, Age, BirthDate, Cellphone, Cep, City, CodigoMunicipioIbge (+12 more)

### Community 42 - "NPM Package Config"
Cohesion: 0.12
Nodes (18): name, private, type, version, date-fns, eslint, @eslint/js, eslint-plugin-react-hooks (+10 more)

### Community 43 - "Status Dialog UI"
Cohesion: 0.16
Nodes (17): ConfirmIntent, DialogConfig, DialogType, useStatusDialog(), addToast(), backgroundByType, listeners, notify() (+9 more)

### Community 44 - "Node TS Config"
Cohesion: 0.10
Nodes (19): compilerOptions, allowImportingTsExtensions, erasableSyntaxOnly, lib, module, moduleDetection, moduleResolution, noEmit (+11 more)

### Community 45 - "Session Controller"
Cohesion: 0.16
Nodes (12): HttpDelete, HttpGet, IActionResult, SessaoController, SecuritySessionDto, Current, Device, Id (+4 more)

### Community 46 - "Cliente Request DTO"
Cohesion: 0.11
Nodes (19): ClienteRequest, Address, Age, BirthDate, Cellphone, Cep, City, CodigoMunicipioIbge (+11 more)

### Community 47 - "Venda History Entity"
Cohesion: 0.11
Nodes (18): List, VendaHistoricoAD, CustomerCpf, CustomerName, ItemTotal, OperatorName, PaymentType, ProductCode (+10 more)

### Community 48 - "SQL Data Readers"
Cohesion: 0.25
Nodes (8): Connection, IEnumerable, List, SqlConnection, SqlDataReader, SqlTransaction, Task, PedidoAB

### Community 49 - "Request Middleware Chain"
Cohesion: 0.11
Nodes (12): HttpContext, RequestDelegate, Task, HorusRequestBodyLimitMiddleware, ILogger, RequestDelegate, HorusRequestTelemetryMiddleware, HttpContext (+4 more)

### Community 50 - "Pedido Model DTO"
Cohesion: 0.12
Nodes (17): List, PedidoItemModel, ItemTotal, ProductCode, ProductName, Quantity, UnitPrice, PedidoModel (+9 more)

### Community 51 - "SQL Connection Pool"
Cohesion: 0.29
Nodes (9): Connection, IEnumerable, List, SqlConnection, SqlDataReader, SqlTransaction, Task, HistoricoVendasAB (+1 more)

### Community 52 - "SQL Reader Helpers"
Cohesion: 0.13
Nodes (14): SqlDataReader, SecurityUserRecord, CompanyId, Cpf, CreatedAt, Email, Id, LastLoginAt (+6 more)

### Community 53 - "File Upload Controller"
Cohesion: 0.28
Nodes (7): HttpPost, IActionResult, ILogger, IWebHostEnvironment, Task, AuthController, CookieOptions

### Community 54 - "Fornecedor Domain Entity"
Cohesion: 0.12
Nodes (16): FornecedorAD, Address, Cellphone, Cep, City, Cnpj, CompanyName, Email (+8 more)

### Community 55 - "Cliente Repository"
Cohesion: 0.24
Nodes (6): Connection, List, SqlCommand, SqlDataReader, Task, ClienteAB

### Community 56 - "Connection DateTime Ops"
Cohesion: 0.26
Nodes (6): Connection, DateTimeOffset, SqlCommand, SqlDataReader, Task, EmpresaAB

### Community 57 - "Fiscal Provider Interface"
Cohesion: 0.22
Nodes (10): CancellationToken, Task, IFiscalProvider, BackgroundService, CancellationToken, ILogger, IServiceScopeFactory, Task (+2 more)

### Community 58 - "Home Relatorio Controller"
Cohesion: 0.13
Nodes (13): HomeController, Dictionary, HttpPost, IActionResult, JsonElement, Task, RelatorioController, RelatorioGerarRequest (+5 more)

### Community 59 - "Fornecedor Model"
Cohesion: 0.12
Nodes (16): FornecedorModel, Address, Cellphone, Cep, City, Cnpj, CompanyName, Email (+8 more)

### Community 60 - "Fornecedor Request DTO"
Cohesion: 0.12
Nodes (15): FornecedorRequest, Address, Cellphone, Cep, City, Cnpj, CompanyName, Email (+7 more)

### Community 61 - "Fiscal Store Provider"
Cohesion: 0.12
Nodes (12): EmitenteFiscalStore, HORUSPDV_API.Services.Fiscal, DateTimeOffset, DocumentoFiscalPendente, CompanyId, DhContingencia, Id, NumeroNf (+4 more)

### Community 62 - "Dev Dependencies Config"
Cohesion: 0.12
Nodes (16): devDependencies, eslint, @eslint/js, eslint-plugin-react-hooks, eslint-plugin-react-refresh, globals, @playwright/test, tailwindcss (+8 more)

### Community 63 - "Row Actions Menu UI"
Cohesion: 0.13
Nodes (9): RowActionItem, RowActionsMenuProps, AppSidebarProps, PageKey, SidebarItemProps, SidebarSectionTitleProps, UserMenu(), UserMenuProps (+1 more)

### Community 64 - "Controller Program Setup"
Cohesion: 0.16
Nodes (6): HORUSPDV_API.Controllers.Caixa, HORUSPDV_API.Services.Caixa, HORUSPDV_API.Repositories, HORUSPDV_API.Services.Security, HORUSPDV_API.Controllers.Sessao, HORUSPDV_API.Controllers.HistoricoVendas

### Community 65 - "CRUD API Endpoints"
Cohesion: 0.30
Nodes (8): HttpGet, HttpPost, IActionResult, Task, NfceController, CancellationToken, ContextoEmitente, Task

### Community 66 - "Documento Fiscal Views"
Cohesion: 0.14
Nodes (14): DocumentoFiscalDetalhe, QrCodeUrl, DocumentoFiscalResumo, ChaveAcesso, CriadoEm, DhAutorizacao, Id, MotivoStatus (+6 more)

### Community 67 - "SQL Command Readers"
Cohesion: 0.26
Nodes (6): Connection, List, SqlCommand, SqlDataReader, Task, FornecedorAB

### Community 68 - "ReCAPTCHA Service"
Cohesion: 0.23
Nodes (7): CancellationToken, JsonElement, Task, HorusRecaptchaService, RecaptchaValidationResult, HashSet, HttpClient

### Community 69 - "API Client HTTP"
Cohesion: 0.18
Nodes (9): apiRequest(), ApiRequestOptions, ApiResponse, HomeKpiDto, homeService, marketModuleService, reportService, sessionService (+1 more)

### Community 70 - "CEP Lookup Service"
Cohesion: 0.14
Nodes (11): brasilApiProvider, CepAddressData, CepLookupFailure, CepLookupOptions, CepLookupResult, CepLookupSuccess, lookupAddressByCep(), openCepProvider (+3 more)

### Community 71 - "Caixa Controller"
Cohesion: 0.20
Nodes (9): HttpGet, HttpPost, IActionResult, CaixaController, AbrirCaixaRequest, OpeningAmount, FecharCaixaRequest, ClosingAmount (+1 more)

### Community 72 - "NF-e XML Parser"
Cohesion: 0.26
Nodes (7): List, NfeParsedDocument, NfeParsedEmitente, NfeParsedItem, NfeXmlParser, XElement, XNamespace

### Community 73 - "Build Scripts Config"
Cohesion: 0.14
Nodes (14): scripts, build, build:dev, build:prod, demo:video, dev, dev:dev, dev:prod (+6 more)

### Community 74 - "Market Module Page"
Cohesion: 0.20
Nodes (10): emptyForm, getStatusClass(), MarketModuleConfig, MarketModuleKpi, MarketModulePage(), MarketModulePageProps, MarketModuleRecord, MarketModuleRecordPayload (+2 more)

### Community 75 - "CRUD Controller A"
Cohesion: 0.32
Nodes (8): HttpDelete, HttpGet, HttpPost, HttpPut, IActionResult, ProducesResponseType, Task, ClienteController

### Community 76 - "CRUD Controller B"
Cohesion: 0.32
Nodes (8): HttpDelete, HttpGet, HttpPost, HttpPut, IActionResult, ProducesResponseType, Task, FornecedorController

### Community 77 - "Logging Controller"
Cohesion: 0.38
Nodes (6): HttpGet, HttpPost, IActionResult, ILogger, Task, PedidoController

### Community 78 - "CRUD Controller C"
Cohesion: 0.32
Nodes (8): HttpDelete, HttpGet, HttpPost, HttpPut, IActionResult, ProducesResponseType, Task, ProdutoController

### Community 79 - "Rate Limit Middleware"
Cohesion: 0.22
Nodes (9): DateTimeOffset, HttpContext, RequestDelegate, Task, HorusRateLimitMiddleware, RequestBucket, Count, ExpiresAt (+1 more)

### Community 80 - "NF-e Import Item Preview"
Cohesion: 0.15
Nodes (13): NfeImportItemPreview, Cest, Gtin, Ncm, NumeroItem, PrecoCusto, PrecoVendaSugerido, ProductCode (+5 more)

### Community 81 - "Venda Request Model"
Cohesion: 0.17
Nodes (12): List, VendaItemRequest, ProductCode, ProductName, Quantity, VendaRequest, CustomerCpf, CustomerName (+4 more)

### Community 83 - "DatePicker Component"
Cohesion: 0.21
Nodes (9): DateOutputFormat, DatePickerField(), DatePickerFieldProps, parseBrDate(), parseIsoDate(), PopoverPosition, toBrDate(), toIsoDate() (+1 more)

### Community 84 - "TimePicker Component"
Cohesion: 0.27
Nodes (11): ActiveColumn, formatTimeValue(), getDefaultSelection(), getNeighborValues(), nearestMinute(), pad2(), ParsedTime, parseTimeValue() (+3 more)

### Community 85 - "Connection DateTime Read"
Cohesion: 0.27
Nodes (6): Connection, DateTimeOffset, List, SqlDataReader, Task, CaixaAB

### Community 86 - "Security User DTO"
Cohesion: 0.17
Nodes (12): SecurityUserDto, CompanyId, Cpf, CreatedAt, Email, Id, LastLoginAt, MustChangePassword (+4 more)

### Community 87 - "Cliente Service Layer"
Cohesion: 0.38
Nodes (3): List, Task, ClienteService

### Community 88 - "Produto Service Layer"
Cohesion: 0.36
Nodes (3): List, Task, ProdutoService

### Community 89 - "Print Settings Card"
Cohesion: 0.18
Nodes (5): PrintSettingsCardProps, ActiveSession, SecuritySessionsCardProps, ThemeMode, ThemeSettingsCardProps

### Community 90 - "Sales Start Page"
Cohesion: 0.24
Nodes (10): CartItem, formatCashElapsed(), formatDateTime(), formatQuantityDisplay(), getPaymentLabel(), isFractionableUnit(), PAYMENT_OPTIONS, Product (+2 more)

### Community 91 - "Update Controller"
Cohesion: 0.33
Nodes (5): HttpGet, HttpPut, IActionResult, Task, EmpresaController

### Community 92 - "Auth Log Controller"
Cohesion: 0.35
Nodes (6): HttpGet, HttpPost, IActionResult, ILogger, Task, HistoricoVendasController

### Community 93 - "Caixa Session Entity"
Cohesion: 0.18
Nodes (10): DateTimeOffset, CaixaSessionAD, ClosedAt, ClosedByName, ClosingAmount, Id, Note, OpenedAt (+2 more)

### Community 94 - "Caixa Session DTO"
Cohesion: 0.18
Nodes (11): CaixaSessionDto, ClosedAt, ClosedByName, ClosingAmount, ElapsedMinutes, Id, Note, OpenedAt (+3 more)

### Community 95 - "Fornecedor Service Layer"
Cohesion: 0.42
Nodes (3): List, Task, FornecedorService

### Community 96 - "Resultado Fiscal Model"
Cohesion: 0.18
Nodes (11): DateTimeOffset, ResultadoFiscal, ChaveAcesso, CodigoStatus, DhAutorizacao, MotivoStatus, Protocolo, Retentavel (+3 more)

### Community 97 - "User Profile Controller"
Cohesion: 0.20
Nodes (8): HttpGet, HttpPut, ApiResponse, Data, Details, Message, Success, UpdateProfileRequest

### Community 98 - "Usuario Request DTO"
Cohesion: 0.20
Nodes (9): UsuarioRequest, CompanyId, Cpf, Email, Name, Password, Phone, Role (+1 more)

### Community 99 - "Database Initializer"
Cohesion: 0.31
Nodes (7): ILogger, SqlConnection, Task, HorusDatabaseInitializer, ILoggerFactory, IServiceProvider, Regex

### Community 100 - "Status Fiscal Enum A"
Cohesion: 0.20
Nodes (10): StatusDocumentoFiscal, Assinado, Autorizado, Cancelado, ContingenciaPendente, Denegado, Inutilizado, Rascunho (+2 more)

### Community 101 - "Status Fiscal Enum B"
Cohesion: 0.20
Nodes (10): StatusDocumentoFiscal, Assinado, Autorizado, Cancelado, ContingenciaPendente, Denegado, Inutilizado, Rascunho (+2 more)

### Community 102 - "Frontend HTML Entry"
Cohesion: 0.24
Nodes (10): GitHub Pages Landing Page, Frontend SPA Entry Point (index.html), ASP.NET Core 8 Backend API, Horus PDV Project, JWT Authentication (HttpOnly Cookie), React Frontend (Vite + TypeScript + Tailwind), reCAPTCHA v3 Integration, Playwright Smoke Test E2E (+2 more)

### Community 103 - "Product Register Page"
Cohesion: 0.22
Nodes (7): EMPTY_FORM, EMPTY_SUPPLIER_DRAFT, isFractionableUnit(), Product, ProductFormData, ProductFormDrawer(), QuickSupplierDraft

### Community 104 - "User Accounts Page"
Cohesion: 0.24
Nodes (6): defaultForm(), toInputForm(), UserAccountsPage(), UserFormDrawer, UsersFilters, UsersTable

### Community 105 - "Auth Register Request"
Cohesion: 0.22
Nodes (8): AuthRegisterRequest, Cnpj, ConfirmPassword, Email, Name, Password, Phone, RecaptchaToken

### Community 106 - "Caixa Status DTO"
Cohesion: 0.22
Nodes (9): List, CaixaStatusDto, BlockReason, CanSell, CurrentSession, History, LastSession, ServerNow (+1 more)

### Community 107 - "Fiscal Contracts Dest"
Cohesion: 0.22
Nodes (8): DestinatarioFiscal, CpfCnpj, IndIeDest, InscricaoEstadual, Nome, TipoEmissaoFiscal, ContingenciaOffline, Normal

### Community 108 - "React Dependencies"
Cohesion: 0.22
Nodes (9): dependencies, date-fns, lucide-react, qrcode.react, react, react-day-picker, react-dom, react-router-dom (+1 more)

### Community 109 - "Receipt Preview Modal"
Cohesion: 0.33
Nodes (8): buildReceiptPrintHtml(), escapeHtml(), formatReceiptDate(), PaymentType, ReceiptCompany, ReceiptPreviewModal(), SaleReceipt, SaleReceiptItem

### Community 110 - "NF-e Import Service"
Cohesion: 0.22
Nodes (7): NfeImportConfirmPayload, NfeImportFornecedorPreview, NfeImportItemInput, NfeImportItemPreview, NfeImportPreview, NfeImportResult, nfeImportService

### Community 111 - "NFC-e Request Models"
Cohesion: 0.25
Nodes (7): CancelamentoNfceRequest, Justificativa, InutilizacaoNfceRequest, Justificativa, NumeroFinal, NumeroInicial, Serie

### Community 112 - "Pedido Request DTO"
Cohesion: 0.25
Nodes (7): List, CriarPedidoRequest, CustomerCpf, CustomerName, Items, FinalizarPedidoRequest, PaymentType

### Community 113 - "Caixa Service Core"
Cohesion: 0.46
Nodes (3): DateTimeOffset, TimeSpan, HorusCaixaService

### Community 114 - "Searchable Select Field"
Cohesion: 0.29
Nodes (4): normalizeSearchValue(), SearchableSelectField(), SearchableSelectFieldProps, YesNoSegmentedControlProps

### Community 115 - "Tour Steps Guide"
Cohesion: 0.29
Nodes (7): marketPageKeys, pageSelector(), registerPageKeys, TOUR_STEPS_BY_PAGE, TourPageKey, TourStep, withCommonPageSteps()

### Community 116 - "Fiscal Service DTOs"
Cohesion: 0.25
Nodes (5): FISCAL_STATUS, FiscalDocumentDetailDto, FiscalDocumentDto, fiscalService, FiscalStatus

### Community 117 - "Cliente Controller"
Cohesion: 0.38
Nodes (3): HORUSPDV_API.Controllers.Clientes, HORUSPDV_API.Services.Clientes, HORUSPDV_API.Models.Clientes

### Community 118 - "Fornecedor Controller"
Cohesion: 0.38
Nodes (3): HORUSPDV_API.Controllers.Fornecedores, HORUSPDV_API.Services.Fornecedores, HORUSPDV_API.Models.Fornecedores

### Community 119 - "API NFC-e Config"
Cohesion: 0.29
Nodes (7): HORUSPDV-API, net8.0, Hercules.NET.NFe.NFCe (2026.8.31.*), Microsoft.Data.SqlClient (5.2.2), Swashbuckle.AspNetCore (10.1.5), System.Security.Cryptography.Xml (8.0.*), Microsoft.NET.Sdk.Web

### Community 121 - "ICliente Service"
Cohesion: 0.43
Nodes (3): List, Task, IClienteService

### Community 122 - "IFornecedor Service"
Cohesion: 0.43
Nodes (3): List, Task, IFornecedorService

### Community 123 - "IProduto Service"
Cohesion: 0.43
Nodes (3): List, Task, IProdutoService

### Community 124 - "Auth Storage Utils"
Cohesion: 0.33
Nodes (5): AUTH_REMEMBER_STORAGE_KEY, AUTH_USER_STORAGE_KEY, AuthenticatedUser, clearAuthSession(), getStoredAuthUser()

### Community 125 - "Vite Build Config"
Cohesion: 0.33
Nodes (6): chunkGroups, getPackageName(), manualChunks(), @tailwindcss/vite, vite, @vitejs/plugin-react

### Community 126 - "Login Request Model"
Cohesion: 0.33
Nodes (5): LoginRequest, Email, Password, RecaptchaToken, RememberMe

### Community 127 - "Password Reset Request"
Cohesion: 0.33
Nodes (5): ResetPasswordWithTokenRequest, ConfirmPassword, NextPassword, RecaptchaToken, Token

### Community 128 - "DB Connection Builder"
Cohesion: 0.47
Nodes (3): IConfiguration, Connection, ConnectionString

### Community 129 - "Pedido Item Entity"
Cohesion: 0.33
Nodes (6): PedidoItemAD, ItemTotal, ProductCode, ProductName, Quantity, UnitPrice

### Community 131 - "Money Format Preview"
Cohesion: 0.40
Nodes (3): Task, CultureInfo, HorusMoneyFormat

### Community 132 - "Cancelamento Fiscal"
Cohesion: 0.33
Nodes (6): CancelamentoRequest, ChaveAcesso, Emitente, Justificativa, Protocolo, SequenciaEvento

### Community 133 - "Inutilizacao Fiscal"
Cohesion: 0.33
Nodes (6): InutilizacaoRequest, Emitente, Justificativa, NumeroFinal, NumeroInicial, Serie

### Community 134 - "Pagamento Fiscal Model"
Cohesion: 0.33
Nodes (6): PagamentoFiscal, AutorizacaoTef, BandeiraCartao, CnpjCredenciadora, Tipo, Valor

### Community 135 - "UI Screen Images A"
Cohesion: 0.53
Nodes (6): Abertura e Fechamento de Caixa - Cash register open/close screen with daily cash control, business rules (sales blocked without open register, 24h expiry), and history table, Cadastro de Cliente - Customer registry CRUD with search by name/CPF, table listing name, document, city, phone, email, Compras e Reposicao - Purchasing and replenishment module with KPI cards (volume, pending, completed, alerts), operational records list, and 4-step workflow panel, Configuracoes - Settings screen with theme toggle (light/dark), receipt auto-print toggle, and session security management, CRM e Fidelidade - CRM and loyalty module with KPI cards, operational records pattern, workflow panel, Dashboard - Home dashboard with real-time KPIs (daily sales: 42, avg ticket: R$186.30, customers served: 31, open orders: 6), quick action shortcuts, and advanced management module links

### Community 136 - "UI Screen Images B"
Cohesion: 0.53
Nodes (6): Detalhes da Licenca - License details screen showing open source model, credits (author Flavio Oliveira), project evolution since 2020, and active free license status, Perfil do Usuario - User profile editing screen with name, email, permission level (Atendente), photo upload, and password change form, Estoque e Inventario - Stock and inventory management screen with volume/pending/completed KPIs, operational records list, workflow steps, and alerts panel, Fiscal NFC-e / NF-e - Fiscal module screen showing in-development status, blocked for operational use pending SEFAZ homologation, digital certificates, contingency, and NFC-e/NF-e transmission, Cadastro de Fornecedor - Supplier registration screen with search by name/CNPJ, data table showing Razao Social, Nome Fantasia, CNPJ, Cidade, Contato columns, and pagination, Frente de Caixa - POS cashier interface with product search, quantity/price inputs, item list, subtotal display (R$0.00), Cancel(F8)/Print/Payment(F12) action buttons, and company CNPJ header

### Community 137 - "UI Screen Nav Images"
Cohesion: 0.60
Nodes (6): Historico de Vendas screen - sales history listing with search, columns for Venda/Cliente/CPF/Produto/QNT/Valor/Data, pagination, sidebar navigation, Horus PDV sidebar navigation structure - Principal, Cadastros, Operacao, Gestao Avancada sections, Login screen - email/password authentication with branding tagline, keep session checkbox, forgot password and sign-up links, Minha Empresa screen - company registration form with Nome Fantasia, Razao Social, CNPJ, Inscricao Estadual, address fields, contact info, SMTP email configuration, Omnichannel e Integracoes screen - operational module dashboard with volume/pendencias/concluidos/alertas KPIs, operational records list with status badges, Pagamentos Integrados screen - module under development with TEF integration, reconciliation, chargebacks and transaction receipts planned

### Community 138 - "App Navigation Events"
Cohesion: 0.33
Nodes (3): APP_NAVIGATE_EVENT, APP_OPEN_TOUR_EVENT, AppNavigateDetail

### Community 139 - "Cash Register Page"
Cohesion: 0.53
Nodes (4): CashRegisterPage(), formatDateTime(), formatElapsed(), SessionRow()

### Community 140 - "Customer Register Page"
Cohesion: 0.33
Nodes (3): Customer, CustomerFormData, EMPTY_FORM

### Community 141 - "My Company Page"
Cohesion: 0.40
Nodes (5): CRT_OPTIONS, fileToBase64(), MyCompanyPage(), UF_OPTIONS, UF_SELECT_OPTIONS

### Community 142 - "Novo Pedido Page"
Cohesion: 0.47
Nodes (5): CartItem, formatQuantityDisplay(), isFractionableUnit(), NovoPedidoPage(), Product

### Community 143 - "Sales History Page"
Cohesion: 0.47
Nodes (5): PAYMENT_LABEL, SaleHistoryRow, SalesHistoryPage(), splitSaleDate(), toCompanyReceipt()

### Community 144 - "Supplier Register Page"
Cohesion: 0.33
Nodes (3): EMPTY_FORM, Supplier, SupplierFormData

### Community 145 - "Auth Service Frontend"
Cohesion: 0.33
Nodes (5): authService, ForgotPasswordResponse, LoginPayload, LoginResponse, RegisterPayload

### Community 146 - "Pedido Service Frontend"
Cohesion: 0.33
Nodes (5): CriarPedidoPayload, PedidoDto, PedidoItemDto, pedidoService, PedidoStatus

### Community 147 - "Forgot Password Request"
Cohesion: 0.40
Nodes (4): ForgotPasswordRequest, Cnpj, Email, RecaptchaToken

### Community 148 - "Venda Item Record"
Cohesion: 0.40
Nodes (5): VendaItemRecord, ProductCode, ProductName, Quantity, UnitPrice

### Community 150 - "UI Screen Images C"
Cohesion: 0.80
Nodes (5): Cadastro de Produto screen - paginated product list with search, image, code, supplier, quantity, sale price columns and new product button, Relatorios screen - catalog of 8 report cards: Vendas por Periodo, Historico de Vendas, Produtos Mais Vendidos, Clientes Mais Frequentes, Estoque Critico, Compras por Fornecedor, Movimento de Estoque, Desempenho de Caixa, Sobre PDV screen - open source project info, created 2020, author Flavio Oliveira, free use with credits, Trocas e Devolucoes screen - operational dashboard with volume/pending/completed/alerts KPIs, operational records list with status and values, Usuarios screen - user list with name/CNPJ/email/phone, profile (Atendente/Administrador), status, last login, security; filters; new user button

### Community 151 - "DANFE Preview Modal"
Cohesion: 0.60
Nodes (4): DanfePreviewModal(), formatChave(), formatDate(), qrcode.react

### Community 152 - "ReCAPTCHA v3 Hook"
Cohesion: 0.60
Nodes (4): loadRecaptchaScript(), useRecaptchaV3(), waitForRecaptchaReady(), Window

### Community 153 - "Update Profile Request"
Cohesion: 0.50
Nodes (4): UpdateProfileRequest, Email, Name, Phone

### Community 154 - "Change Password Request"
Cohesion: 0.50
Nodes (3): ChangePasswordRequest, CurrentPassword, NextPassword

### Community 156 - "App Entry Point"
Cohesion: 0.67
Nodes (3): App(), formatRole(), toCurrentUser()

### Community 157 - "Table Pagination UI"
Cohesion: 0.67
Nodes (3): createPageItems(), TablePagination(), TablePaginationProps

### Community 158 - "Fiscal Page Frontend"
Cohesion: 0.83
Nodes (3): askJustificativa(), FiscalPage(), formatDate()

### Community 159 - "Cash Register Service"
Cohesion: 0.50
Nodes (3): cashRegisterService, CashRegisterSessionDto, CashRegisterStatusDto

### Community 160 - "Customer Service Frontend"
Cohesion: 0.50
Nodes (3): CustomerDto, CustomerPayload, customerService

### Community 161 - "Product Service Frontend"
Cohesion: 0.50
Nodes (3): ProductDto, ProductPayload, productService

### Community 162 - "Sales History Service"
Cohesion: 0.50
Nodes (3): RegisterSalePayload, SaleHistoryDto, salesHistoryService

### Community 163 - "Supplier Service Frontend"
Cohesion: 0.50
Nodes (3): SupplierDto, SupplierPayload, supplierService

### Community 164 - "Balanca Barcode Parser"
Cohesion: 0.67
Nodes (3): BalancaBarcode, isValidEan13(), parseBalancaBarcode()

### Community 166 - "Graphify Skill Config"
Cohesion: 0.67
Nodes (3): Graphify Skill - Claude Code knowledge graph generation skill, Claude Project Config - Horus PDV Claude Code project instructions, Graphify Skill (Codex) - Knowledge graph generation skill for Codex

## Knowledge Gaps
- **1124 isolated node(s):** `HORUSPDV_API.Controllers.Auth`, `Name`, `Email`, `Phone`, `HORUSPDV_API.Controllers.Caixa` (+1119 more)
  These have ≤1 connection - possible missing edges or undocumented components. (Counts symbols only; 1404 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **11 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `ApiResponse` connect `User Profile Controller` to `API Controllers Core`, `API Controllers Logging`, `NF-e Import Controller`, `Auth Middleware Pipeline`, `Home About Controllers`, `API Controllers Registry`, `Session Controller`, `Request Middleware Chain`, `File Upload Controller`, `Home Relatorio Controller`, `CRUD API Endpoints`, `Caixa Controller`, `CRUD Controller A`, `CRUD Controller B`, `Logging Controller`, `CRUD Controller C`, `Rate Limit Middleware`, `Update Controller`, `Auth Log Controller`?**
  _High betweenness centrality (0.114) - this node is a cross-community bridge._
- **Why does `HORUSPDV_API.Repositories.DatabaseAccess` connect `Controller Routing` to `Controller Program Setup`, `Home About Controllers`, `Documento Fiscal Views`, `API Controllers Registry`, `NFC-e Outbox Worker`, `Request Middleware Chain`, `Security Store Auth`, `Cliente Controller`, `Fornecedor Controller`, `Cliente Repository`, `Auth Middleware Pipeline`, `Home Relatorio Controller`, `Fiscal Store Provider`?**
  _High betweenness centrality (0.039) - this node is a cross-community bridge._
- **Why does `HORUSPDV_API.Services.Fiscal` connect `Fiscal Store Provider` to `Controller Program Setup`, `Documento Fiscal Views`, `API Controllers Registry`, `NFC-e Outbox Worker`, `Fiscal Contracts Models`, `Fiscal Contracts Dest`?**
  _High betweenness centrality (0.037) - this node is a cross-community bridge._
- **Are the 57 inferred relationships involving `ApiResponse` (e.g. with `.ChangePassword()` and `.ForgotPassword()`) actually correct?**
  _`ApiResponse` has 57 INFERRED edges - model-reasoned connections that need verification._
- **What connects `HORUSPDV_API.Controllers.Auth`, `Name`, `Email` to the rest of the system?**
  _1124 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `API Controllers Core` be split into smaller, more focused modules?**
  _Cohesion score 0.06240084611316764 - nodes in this community are weakly interconnected._
- **Should `API Controllers Logging` be split into smaller, more focused modules?**
  _Cohesion score 0.06019871420222092 - nodes in this community are weakly interconnected._