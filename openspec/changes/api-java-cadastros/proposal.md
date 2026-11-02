# Proposal

## Why

A Fase 1 entregou a fundação da API Java (login, sessão, usuários, envelope e segurança). Para que as telas web de
cadastro e o painel do administrador da plataforma funcionem contra a API Java, a Fase 2 do plano
(`docs/migracao-api-springboot.md`, § 11) porta as rotas de **cadastros e administração** com o mesmo contrato da API
.NET. Todas as fases seguintes (produtos, vendas, fiscal) dependem desses cadastros: empresa (dados fiscais, CSC e
certificado), categorias, fornecedores e clientes (limite de fiado).

## What Changes

- **Empresa** (`api/Empresa`): leitura e gravação dos dados da empresa logada. A gravação inclui SMTP, dados fiscais
  (CRT, CSC, séries, responsável técnico) e certificado A1. Os segredos ficam cifrados (`enc:v1:`) e mascarados na
  resposta. O certificado é validado com a senha, com impressão digital e validade calculadas como no .NET.
- **Tema da empresa** (`api/company-theme`): leitura e gravação das cores `#rrggbb` do sistema e do PDV.
- **Categorias** (`api/Categoria`): árvore e lista plana com contagem de produtos, CRUD, ativar/desativar e a regra de
  2 níveis, conforme o spec existente `produtos/categorias`.
- **Fornecedores** (`api/Fornecedor`): CRUD com CNPJ único por empresa. A exclusão é bloqueada se houver produto
  vinculado.
- **Clientes** (`api/Cliente`):
  - CRUD com validações de nome, documento, telefone e e-mail, e documento único;
  - regras de fiado por perfil (gerente só consulta);
  - limite de crédito não abaixo do saldo devedor somado aos orçamentos delegados ao Gateway;
  - auditoria;
  - `credito-snapshot`, usado na reconciliação offline do Caixa.
- **Módulo mercado** (`api/ModuloMercado`): os 8 módulos fixos, com registros, KPIs, fluxo e alertas calculados igual ao .NET.
- **Treinamento** (`api/treinamento`): seções e vídeos do YouTube. Todos os perfis leem; só o administrador geral da
  plataforma edita.
- **Administração de empresas** (`api/Admin/Empresas`):
  - lista paginada com busca e filtro;
  - métricas;
  - aprovar, rejeitar, bloquear e reativar (rejeitar e bloquear derrubam as sessões);
  - aprovação prévia de novos cadastros;
  - troca de credenciais do administrador da empresa;
  - exclusão completa da empresa;
  - download da documentação OpenAPI (`documentacao-api`), agora gerada pelo springdoc.
- **Contrato:** novos grupos de cenários gravados da API .NET e comparados na Java (`empresa`, `tema`, `categoria`,
  `fornecedor`, `cliente`, `modulo-mercado`, `treinamento`, `admin-empresas`).
- **Testes:** JUnit 5 + Mockito nos serviços, `@WebMvcTest` nos controllers, repositórios com Testcontainers (SQL Server).
- **Banco:** nenhuma tabela nova. As entidades JPA mapeiam tabelas que já estão no `V1`. Um
  `V3__cadastros_dados_referencia.sql` idempotente traz os dados iniciais destes módulos que a API .NET cria nas
  migrações 09 e 38: as categorias padrão da `empresa-principal` e o conteúdo inicial do treinamento. A linha do módulo
  em `ModulosMercado` é criada na primeira gravação de registro, como no .NET.
- Fora desta change: rotas de produtos, estoque, vendas, fiado, Gateway, fiscal, backup e MCP (Fases 3 a 8).

## Capabilities

### New Capabilities
- `cadastros/empresa`: dados da empresa logada (cadastrais, SMTP, fiscais, CSC e certificado A1 com segredos
  mascarados) e o tema de cores do sistema e do PDV.
- `cadastros/fornecedores`: cadastro de fornecedores por empresa, unicidade de CNPJ e bloqueio de exclusão com produtos
  vinculados.
- `cadastros/clientes`: cadastro de clientes, regras de limite de fiado por perfil e por compromissos, auditoria e
  retrato de crédito para reconciliação offline.
- `cadastros/modulo-mercado`: módulos operacionais fixos do mercado com registros e indicadores.
- `plataforma/treinamento`: seções e vídeos de treinamento, com edição restrita ao administrador geral.
- `plataforma/administracao-empresas`: gestão das empresas da plataforma pelo administrador geral (situação,
  aprovação prévia, credenciais, exclusão) e download da documentação da API.

### Modified Capabilities
- (nenhuma: `produtos/categorias` já descreve o comportamento das categorias e a API Java o reproduz sem mudar
  requisito. A API .NET e os demais specs não mudam.)

## Impact

- **Código novo** em `API/JAVA`:
  - pacotes `empresa` (empresa, tema, administração, treinamento) e `cadastro` (categorias, fornecedores, clientes,
    módulo mercado), com `web/ app/ domain/ repo/ dto/`;
  - cenários e gravações de contrato em `src/test/resources/contrato/`;
  - seed de contrato ampliado.
- **Clientes:** nenhuma mudança. A web, o Quack Estoque e o Caixa JavaFX usam as mesmas rotas e o mesmo JSON.
- **Banco:**
  - sem alteração de esquema;
  - `V3` só com dados de referência, que roda também no banco restaurado da produção e por isso não muda nada se os dados já existirem;
  - a entidade `Empresa` da Fase 1 ganha as colunas cadastrais e fiscais que faltam.
- **Dependências:** nenhuma nova. O certificado é lido com `java.security.KeyStore` (PKCS12) da própria JDK.
- **Branch:** `spring-api`.
