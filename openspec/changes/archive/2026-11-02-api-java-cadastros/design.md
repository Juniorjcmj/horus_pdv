# Design

## Context

- **Ponto de partida:** a Fase 1 (`openspec/changes/archive/2026-11-02-api-java-fundacao/`) deixou pronto em `API/JAVA/`
  (branch `spring-api`):
  - **envelope** `shared/ApiResponse` (`ok`, `erro`), `NegocioException(HttpStatus, msg[, details, data])` tratada pelo
    `GlobalExceptionHandler`, e `FormatoO`/`FormatoDataDotNet` para datas no formato do .NET;
  - **segurança:**
    - `AutenticacaoFilter`, que preenche o `UsuarioAutenticado` da requisição;
    - `@PerfisPermitidos`, aplicada pelo `PerfisInterceptor` (403 "Usuário sem permissão para esta ação.");
    - `SecretProtector` (`protect`/`unprotect` no formato `enc:v1:`), `PasswordHasher`, `CnpjValidator`;
  - **auditoria:** `AuditoriaService.registrar(companyId, userId, userName, eventType, description, entityType,
    entityId, ip)`;
  - **entidades:** `Empresa` (só as colunas usadas no login e no cadastro público), `Usuario`, `Sessao`, `AuditLog`;
  - **banco:**
    - Flyway com `V1__esquema_inicial.sql` (todas as tabelas, inclusive as desta fase) e `V2__dados_referencia.sql`
      (só a `empresa-principal`);
    - num banco restaurado, o baseline é a versão 1, então **V2 e as seguintes também rodam na produção restaurada**;
  - **contrato:** gravador e comparador (`contrato/README.md`):
    - cenários em `src/test/resources/contrato/cenarios/<grupo>.json`;
    - gravações da API .NET em `gravacoes/<grupo>/`;
    - grupos listados em `MotorDeContrato.GRUPOS`;
    - seed `contrato/seed/seed-contrato.sql` e reset `reset-contrato.sql`, aplicados nos dois lados.
- **Referência .NET (fonte da verdade do comportamento)**, em `API/NETCORE/`:

  | Módulo | Arquivos |
  | --- | --- |
  | Empresa | `Controllers/Empresa/EmpresaController.cs`, `Repositories/DatabaseAccess/EmpresaAB.cs`, `Models/Requests/EmpresaRequest.cs` |
  | Tema | `Controllers/Tema/CompanyThemeController.cs` (SQL no próprio controller, `MERGE EmpresaTema`) |
  | Categorias | `Controllers/Categorias/CategoriaController.cs`, `Services/Categorias/CategoriaService.cs`, `Repositories/DatabaseAccess/CategoriaAB.cs`; spec `openspec/specs/produtos/categorias` |
  | Fornecedores | `Controllers/Fornecedores/FornecedorController.cs`, `Services/Fornecedores/FornecedorService.cs`, `Repositories/DatabaseAccess/FornecedorAB.cs`, `Models/Requests/FornecedorRequest.cs` |
  | Clientes | `Controllers/Clientes/ClienteController.cs`, `Services/Clientes/ClienteService.cs`, `Repositories/DatabaseAccess/ClienteAB.cs` (inclusive `CreditSnapshotAsync`), `GatewayCreditAB.DelegatedAsync`, `Models/Requests/ClienteRequest.cs` |
  | Módulo mercado | `Controllers/ModuloMercado/ModuloMercadoController.cs` (títulos, painel e KPIs no controller), `Repositories/DatabaseAccess/ModuloMercadoAB.cs` |
  | Treinamento | `Controllers/Admin/TreinamentoController.cs` (regex do YouTube, `PodeGerenciar`), `Repositories/DatabaseAccess/TreinamentoAB.cs` |
  | Admin de empresas | `Controllers/Admin/GerenciamentoEmpresasController.cs`, `Repositories/DatabaseAccess/HorusSecurityStore.cs` (`IsApprovalRequiredForNewCompanies` ~l.1230, `GetCompaniesMetrics` ~1305, `ListCompaniesAdmin` ~1338, `Approve/Reject/Block/ReactivateCompany` ~1446–1560, `UpdateCompanyAdminCredentials` ~1562, `DeleteCompany` ~1658), `Models/Requests/AdminEmpresaContracts.cs` |
  | Documentação | `Controllers/Admin/ApiDocumentationController.cs`, `Services/Admin/ApiDocumentation.cs` (`FileName = "quack-pdv-api-openapi.json"`) |
  | Dados iniciais | `DataBase/Migrations/09_categorias_produto.sql` (categorias padrão), `38_treinamento.sql` (seções e vídeos) |

- **Comportamento:** os requisitos estão nos specs desta change (`specs/cadastros/*`, `specs/plataforma/*`) e, para as
  categorias, em `openspec/specs/produtos/categorias`. Em caso de dúvida sobre um campo ou mensagem, **vale o código
  .NET e a gravação do contrato**, não a memória.

## Goals / Non-Goals

**Goals:**
- As rotas desta fase respondem byte a byte como a API .NET (status, mensagens, campos, tipos, ordem das listas),
  medidas pelo comparador de contrato.
- As telas web de cadastro e o painel do administrador geral funcionam apontando para a API Java.
- Banco novo criado só pelo Flyway tem os mesmos dados iniciais destes módulos que um banco criado pela API .NET.

**Non-Goals:**
- Entidade e rotas de **Produto** (Fase 3). Esta fase só **lê** `Produtos` por consulta nativa (contagem por
  categoria, fornecedor em uso, totais do admin).
- Fiado, Gateway e vendas (Fases 4 e 5). O `credito-snapshot` e a soma delegada do Gateway são só leituras.
- Corrigir comportamentos estranhos do .NET (ver D9), salvo os já tratados como divergência consciente na Fase 1.

## Decisions

### D1. Pacotes e camadas

Segue o § 3.2 do plano. Cada módulo tem `web/` (controller fino + records de request/response), `app/` (serviço com as
regras, onde ficam os testes com Mockito), `domain/` (entidades JPA) e `repo/` (Spring Data).

```
br.com.quack.pdv.empresa      Empresa (dados), tema, administração de empresas, documentação da API, treinamento
br.com.quack.pdv.cadastro     categoria/, fornecedor/, cliente/, mercado/   (subpacotes com web/app/domain/repo)
```

- **Erros:**
  - toda `InvalidOperationException` do .NET vira `NegocioException(BAD_REQUEST, msg)`, com o mesmo texto;
  - os 404 viram `NegocioException(NOT_FOUND, msg)`;
  - os `catch` genéricos do admin, que no .NET dão 500 com mensagem própria (ex.: "Erro interno ao excluir empresa."),
    são reproduzidos com um `try/catch` no serviço, que devolve `NegocioException(INTERNAL_SERVER_ERROR, msg)` e
    registra o erro no log.
- **Usuário logado:**
  - os controllers recebem o `UsuarioAutenticado`, como os da Fase 1;
  - sem usuário, devolvem 401 `ApiResponse.erro("Sessão não encontrada.")`, igual ao `UsuarioController`;
  - exceção: `credito-snapshot` devolve **401 sem corpo** (`Unauthorized()` no .NET). Confirmar pela gravação.
- **Perfis:**
  - `@PerfisPermitidos` na classe e nos métodos, com os mesmos perfis do `HorusAuthorizeRoles` de cada controller;
  - `CategoriaController` não tem perfil na classe (qualquer autenticado lê), e as escritas pedem administrador ou gerente;
  - `EmpresaController`: GET para qualquer autenticado, PUT só para administrador.
- **Administrador geral:**
  - componente `empresa/app/AdministradorGeral` com dois métodos:
    - `ehAdministradorGeral(u)`: administrador **e** `empresa-principal`, usado no admin e na documentação;
    - `podeGerenciarTreinamento(u)`: o anterior **ou** administrador com e-mail na lista `jotacfs2010@hotmail.com`,
      `jotanaval2009@gmail.com`, `flavio@hpdv.com.br`;
  - a comparação ignora maiúsculas e espaços;
  - as respostas 403 têm o texto de cada controller.

### D2. Entidades, repositórios e consultas

- **Entidades novas** (`@Table`/`@Column` com os nomes exatos do V1, `ddl-auto=validate`):
  - `EmpresaTema`, `Categoria`, `Fornecedor`, `Cliente`, `ModuloMercado`, `ModuloMercadoRegistro`,
    `TreinamentoSecao`, `TreinamentoVideo`;
  - os ids são `String`, gerados pela aplicação com os prefixos do .NET (`fr-`, `cl-`, `mm-`; categorias, seções e
    vídeos: conferir o formato em `CategoriaAB`/`TreinamentoAB`), sempre com `System.currentTimeMillis()` do `Clock`
    injetado.
- **`Empresa`:** a entidade da Fase 1 ganha todas as colunas que faltam (cadastrais, SMTP, fiscais, CSC, certificado,
  responsável técnico, `SerieNfce`, `SerieNfe`, `ReviewedAt`, `ReviewedBy`, `RequireApprovalForNewCompanies`), com
  tipos e tamanhos do V1. Sem quebrar o login, o cadastro público nem os testes da Fase 1.
- **Isolamento por empresa:** todo método de repositório recebe `companyId` (`findByCompanyIdAndId…`), exceto o
  treinamento (global) e o admin, que é da plataforma.
- **Leituras de tabelas de outras fases**, por `@Query(nativeQuery = true)` em repositórios do próprio módulo:
  - contagem de produtos por categoria (`Produtos.CategoriaId`), igual ao SQL do `CategoriaAB`;
  - fornecedor em uso: `Produtos.ProductSupplier` igual ao nome fantasia ou à razão social, sem diferenciar
    maiúsculas. O .NET carrega todos os produtos e compara em memória; a Java faz um `EXISTS` com a collation
    padrão do banco (CI), que dá o mesmo resultado;
  - soma delegada do Gateway:
    `SELECT COALESCE(SUM(AmountCents-ConsumedCents),0) FROM GatewayCreditGrants WHERE CompanyId=? AND CustomerId=? AND Closed=0`,
    dividida por 100 em `BigDecimal`. Conferir colunas e condição em `GatewayCreditAB.DelegatedAsync`;
  - `credito-snapshot`:
    - `SELECT … FROM Clientes WITH (UPDLOCK, HOLDLOCK) WHERE CompanyId=? ORDER BY Id`;
    - `SELECT DISTINCT ClientSaleId FROM Vendas WHERE CompanyId=? AND ClientSaleId IN (SELECT value FROM OPENJSON(?))`,
      com o parâmetro sendo a lista serializada em JSON pelo Jackson;
    - as duas na mesma transação.
- **Admin de empresas:** o SQL de `ListCompaniesAdmin`, `GetCompaniesMetrics` e das mudanças de situação é portado
  **literalmente** para `@Query(nativeQuery = true)` com projeções (records/interfaces) ou para `JdbcTemplate`, com os
  mesmos `ISNULL`, a mesma ordem e a mesma paginação `OFFSET/FETCH`. Rejeitar e bloquear apagam as sessões na mesma
  transação (`@Modifying` + `@Transactional`).
- **`DeleteCompany`:** o lote T-SQL do .NET (com `BEGIN TRANSACTION/TRY/CATCH` e os `IF OBJECT_ID … COL_LENGTH`) é
  executado **igual**, num só `JdbcTemplate.update(sql, companyId)`. O resultado é "afetou linhas > 0" (o .NET usa o
  total de linhas do lote). Alternativa descartada: reescrever como chamadas JPA tabela a tabela, que daria mais
  código, mais idas ao banco e risco de mudar a ordem das exclusões.

### D3. Empresa: leitura, gravação, segredos e certificado

- **Leitura com fallback:** `ObterAsync(companyId)` do .NET devolve a linha da própria empresa ou, se ela não existir,
  a da `empresa-principal` (`WHERE Id=@Id OR Id='empresa-principal' ORDER BY CASE…`). A Java reproduz isso no GET e
  na leitura do "atual" do PUT. A gravação é sempre na empresa do usuário, por upsert (atualiza se existir, senão
  insere), como o `SalvarAsync`.
- **Validação:** ordem e textos de `EmpresaController.Validate`/`EmpresaAB.SalvarAsync`. Ver spec
  `cadastros/empresa`. Inclui "Informe a senha do certificado digital." quando vem PFX sem senha.
- **Segredos:**
  - cada um (`EmailSmtpPassword`, `CscCifrado`, `CertificadoPfxCifrado`, `CertificadoSenhaCifrada`) é gravado com
    `SecretProtector.protect`;
  - quando o campo chega vazio, o valor do banco fica, com a mesma regra `CASE WHEN @x = N'' THEN coluna ELSE @x END`.
    Os quatro campos do certificado só mudam juntos, quando chega PFX novo;
  - a resposta mascara como `EmpresaController.ToModel`.
- **Certificado:**
  - abre o PFX com `KeyStore.getInstance("PKCS12")` e a senha, e usa o certificado da primeira entrada com chave
    privada (o mesmo que o `X509Certificate2` escolhe);
  - impressão digital: `SHA-1(cert.getEncoded())` em hexadecimal maiúsculo;
  - validade: `cert.getNotAfter()` em UTC (`OffsetDateTime` com `ZoneOffset.UTC`);
  - qualquer exceção ao abrir dá 400 "Certificado digital ou senha inválidos.";
  - **teste de compatibilidade:** o console `contrato/vetores-dotnet` ganha um vetor de certificado. Ele gera um PFX
    autoassinado de teste com senha, grava o base64 e o thumbprint e o `NotAfter` calculados pelo .NET em
    `vetores.json`, e `CompatibilidadeDotnetTest` confere que a Java chega nos mesmos valores. Se não houver .NET na
    máquina, o vetor já gravado é usado.
- **Tema:** `EmpresaTema` (PK `CompanyId`). Regex `^#[0-9a-fA-F]{6}$` ou vazio. Vazio grava `NULL` ou vazio conforme o
  .NET. `updatedAt` no formato `"o"` (`FormatoO`).

### D4. Categorias

Porta do `CategoriaService` + `CategoriaAB`, com a árvore de 2 níveis, a lista plana (`?plana=true`), `apenasAtivas`,
a contagem de produtos e os bloqueios de exclusão ("…possui subcategorias vinculadas.", "…possui produtos vinculados.
Remova ou altere a categoria dos produtos primeiro."). O formato do id e a ordem (`Ordem`, `Nome`) seguem o `CategoriaAB`.
Requisitos: spec existente `produtos/categorias`. Esta change não o modifica.

### D5. Clientes

- `PodeAlterarFiado(u)` = perfil principal diferente de `gerente` (copiar a regra exata de `ClienteController`).
- A gravação é `@Transactional` no serviço:
  1. lê `SaldoDevedor` com `WITH (UPDLOCK, HOLDLOCK)` por consulta nativa (o `PESSIMISTIC_WRITE` do Hibernate gera
     outra dica de bloqueio, por isso não foi usado);
  2. confere `limite < saldo + delegado`;
  3. grava.
- A conferência vale em toda gravação de cliente existente, não só quando o limite muda, como no .NET.
- **Auditoria:** no .NET é "dispara e esquece" (`_ = RegistrarAsync`). A Java registra depois da gravação, num
  `try/catch` que só faz log, para que uma falha na auditoria nunca desfaça nem quebre a resposta.
  - descrições copiadas de `ClienteService`;
  - valores em reais no formato do `HorusMoneyFormat` (`R$ 1.234,56`), por um formatador pt-BR em `shared/` se a
    Fase 1 ainda não tiver um.
- `credito-snapshot`:
  - `customers` usa o mesmo formato serializado pelo .NET. Ele devolve `ClienteAD`, não `ClienteModel`, então os
    campos podem diferir da listagem. A gravação do contrato é a referência;
  - mensagem `null`.

### D6. Módulo mercado

- Títulos, ação principal, KPIs, `workflow` e `alerts` são **constantes e cálculos da aplicação**, copiados do
  controller .NET para o serviço `mercado/app/ModuloMercadoService`. Regras de contagem:
  - pendente contém `pend` ou `aguard`;
  - concluído contém `conclu`, `audit`, `fech` e os demais termos do `IsCompleted`;
  - sem diferenciar maiúsculas;
  - `completionRate = round(concluídos*100/total)`. O `Math.Round` decimal do .NET arredonda o meio para o par
    (*ToEven*), então a Java usa `BigDecimal.setScale(0, RoundingMode.HALF_EVEN)`.
- `GarantirModuloAsync`: antes de criar o primeiro registro, insere a linha em `ModulosMercado` se faltar. Por isso não
  há dado de referência para módulos.
- Os textos sem acento ("Modulo obtido com sucesso.", "Titulo deve ter no minimo 3 caracteres.", "Status e
  obrigatorio.") ficam como estão.

### D7. Treinamento

- Global (sem `CompanyId`).
- O GET devolve `{podeGerenciar, secoes:[{…, videos:[…]}]}`, na ordem dos dois `SELECT` do `TreinamentoAB`.
- Regex do YouTube copiadas do controller (`^[A-Za-z0-9_-]{11}$` e
  `(?:youtu\.be/|youtube\.com/(?:watch\?(?:.*&)?v=|embed/|shorts/|live/))([A-Za-z0-9_-]{11})`).
- A exclusão de seção segue o `TreinamentoAB`, inclusive o que acontece com os vídeos dela.

### D8. Documentação da API (springdoc também em produção)

- Hoje `springdoc.api-docs.enabled=false` em produção, e `SwaggerProducaoWebTest` espera 401 sem login e 404 com login
  em `/v3/api-docs` e `/swagger-ui.html`.
- **Decisão:**
  - ligar a **geração** do documento em todos os perfis (`springdoc.api-docs.enabled=true`, `swagger-ui.enabled`
    continua só no dev);
  - um filtro ou regra no `AutenticacaoFilter` mantém `/v3/api-docs` e `/swagger-ui` **fechados em produção**, com o
    mesmo 401/404 de hoje, para o `SwaggerProducaoWebTest` continuar passando;
  - o `ApiDocumentationController` obtém o JSON pelo bean do springdoc (`OpenApiWebMvcResource.openapiJson(request,
    apiDocsUrl, locale)` ou `OpenAPIService`), troca `servers` pela URL da requisição
    (`scheme://host[:port]contextPath`) e devolve `ResponseEntity<byte[]>`:
    - `Content-Type: application/json; charset=utf-8`;
    - `Content-Disposition: attachment; filename=quack-pdv-api-openapi.json` (formato do `File()` do ASP.NET, que
      inclui `filename*=UTF-8''…`; conferir na gravação);
  - os 401 e 403 seguem o envelope. O 403 usa `success:false` e "Documentação da API restrita ao Administrador Geral da
    Plataforma.";
  - o título do documento é "Quack Sistemas — API do PDV", igual ao .NET.
- O **conteúdo** do documento não é comparado com o do .NET (são geradores diferentes). O comparador confere só
  status, cabeçalhos e que é um OpenAPI 3 com `paths`.
- Alternativa descartada: servir o `contrato/openapi-dotnet.json` estático, que ficaria desatualizado a cada fase.

### D9. Fidelidade a comportamentos estranhos do .NET

Reproduzidos de propósito (o contrato é a meta; as correções ficam para depois do corte, registradas em Open
Questions):
- GET da empresa cai na `empresa-principal` quando a empresa do usuário não tem linha.
- `DeleteCompany` apaga só as tabelas da lista do .NET. Tabelas criadas depois (lotes, movimentações de estoque,
  inventário, ordens de compra, tokens, fila fiscal etc.) não estão nela.
- Alertas do módulo mercado com mínimo 1 ("Alertas" nunca é 0).

### D10. Flyway V3

`db/migration/V3__cadastros_dados_referencia.sql`, idempotente (`IF NOT EXISTS` por `Id`), com:
- as categorias padrão da `empresa-principal` exatamente como em `09_categorias_produto.sql` (ids `cat-…`,
  hierarquia, ordem, ativa);
- as seções e os vídeos iniciais de `38_treinamento.sql`.

Na produção restaurada, essas linhas já existem e nada muda. O `EsquemaEquivalenteIT`/`SeedDevIT` da Fase 1 continuam
passando, e um teste novo confere as contagens num banco novo.

### D11. Contrato gravado e comparado

- **Grupos novos** em `MotorDeContrato.GRUPOS`: `empresa`, `tema`, `categoria`, `fornecedor`, `cliente`,
  `modulo-mercado`, `treinamento`, `admin-empresas`.
- **Cobertura:** cada grupo cobre, para cada rota:
  - sucesso;
  - cada validação do spec;
  - 401;
  - 403 por perfil;
  - isolamento entre empresas (id de outra empresa → 404).
- **Seed** (`seed-contrato.sql`, nos dois lados), ampliado com:
  - na `emp-contrato-aprovada`, clientes (um com saldo devedor) e fornecedores;
  - um `GatewayCreditGrants` aberto para um cliente;
  - categorias (departamento + subcategoria);
  - 2 produtos mínimos (`Produtos` com as colunas obrigatórias do V1), um ligado a categoria e fornecedor;
  - uma venda com `ClientSaleId` conhecido;
  - um tema;
  - registros de módulo mercado;
  - na `empresa-principal`, o SMTP já com senha cifrada (`enc:v1:` gerado com a chave do ambiente de contrato) e um CSC.
- **Reset** (`reset-contrato.sql`): passa a limpar e recriar essas tabelas, filhas antes das mães, e a voltar a
  `empresa-principal` ao seed.
- **Campos voláteis:**
  - `id` para `fr-`, `cl-`, `mm-`, categorias, seções e vídeos;
  - `iso` para `updatedAt`, `createdAt`, `reviewedAt` e `certificadoValidoAte` quando gerado.
- **Certificado:** o cenário usa o PFX de teste dos vetores, então a impressão digital é estável e comparada.
- **Exclusão de empresa:** cria uma empresa descartável no `antes` (pelo cadastro público) e a exclui. O reset devolve
  o resto.
- **Gravação:** com o ambiente .NET do `contrato/ambiente-dotnet` (porta 5270), como na Fase 1. As gravações ficam
  versionadas.

### D12. Testes

- **Serviços** (JUnit 5 + Mockito, sem Spring), um teste por regra do spec:
  - validações e mensagens;
  - regras de perfil (gerente e fiado, administrador geral);
  - limite × compromissos;
  - cálculo do painel do módulo mercado (inclusive arredondamento);
  - extração do id do YouTube;
  - mascaramento e preservação de segredos;
  - certificado inválido;
  - paginação e normalização do admin.
- **Controllers** (`@WebMvcTest`, base `WebTestBase` da Fase 1): perfis permitidos e negados, 401, formato do envelope,
  status 201 nas criações, download da documentação (cabeçalhos).
- **Repositórios e SQL nativo** (Testcontainers SQL Server, perfil `it`):
  - consultas do admin;
  - `DeleteCompany` (apaga tudo da empresa e nada de outra; rollback quando falha);
  - snapshot com `OPENJSON`;
  - bloqueio `UPDLOCK`;
  - V3 num banco novo e reaplicado num banco que já tem os dados.
- **Contrato:** `ApiJavaContratoTest` com todos os grupos novos passando.
- **Regressão:** a suíte inteira da Fase 1 continua verde (`mvn -B verify`).

## Risks / Trade-offs

- [SQL nativo específico do SQL Server (`OPENJSON`, `WITH (UPDLOCK)`, lote T-SQL) prende ao SQL Server] → é o banco
  decidido no plano (§ 5.1). Os testes rodam no mesmo SQL Server 2022 do Testcontainers.
- [Campos do `credito-snapshot` e da empresa diferentes do que se imagina] → a gravação do contrato é a referência, e o
  comparador aponta campo a campo.
- [Thumbprint ou validade do certificado diferentes entre .NET e Java (cadeia no PFX, fuso)] → vetor gerado pelo
  próprio .NET com PFX de teste. Se o PFX tiver mais de um certificado, usar o da entrada com chave privada.
- [Arredondamento de `completionRate` (ToEven no .NET × HALF_UP no Java)] → `HALF_EVEN` e teste com 1/8 = 12,5% → 12%.
- [`DeleteCompany` deixa linhas órfãs em tabelas que o .NET não apaga, e pode falhar por FK] → reproduzido igual (500
  "Erro interno ao excluir empresa." com rollback). A correção entra em change própria, depois do corte, nas duas APIs
  ou só na Java.
- [Ligar a geração do springdoc em produção expõe a documentação] → as rotas públicas do springdoc continuam fechadas
  em produção (401/404, teste da Fase 1). Só o download autenticado do administrador geral entrega o JSON.
- [Auditoria síncrona deixa a gravação mais lenta] → é uma inserção. Falha de auditoria não quebra a resposta (log).

## Migration Plan

- Sem mudança de esquema. O `V3` só insere dados de referência que já existem em bancos criados pelo .NET.
- **Entrega:** branch `spring-api`. O CI (`.github/workflows/api-java.yml`) publica a imagem. Na homologação, a VPS
  nova sobe com a imagem e a cópia da base, e as telas de cadastro são testadas apontando para ela.
- **Volta atrás:** nada a desfazer no banco (o `V3` é idempotente e só insere). Basta voltar a imagem anterior.

## Open Questions

- Corrigir, depois do corte, os comportamentos de D9: fallback da empresa, lista de tabelas do `DeleteCompany` e
  "Alertas" mínimo 1. Não muda esta fase, que reproduz o .NET.
