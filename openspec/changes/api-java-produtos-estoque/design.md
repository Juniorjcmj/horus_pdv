# Design

## Context

- **O que já existe em `JAVA/api/` (branch `spring-api`), Fases 1 e 2.** Leia os archives
  `openspec/changes/archive/2026-11-02-api-java-fundacao/` e `…-api-java-cadastros/`.
  - **Envelope e erros:** `ApiResponse`, `NegocioException`, `GlobalExceptionHandler`, `FormatoO`/`FormatoDataDotNet`.
  - **Segurança:** `UsuarioAutenticado`, `@PerfisPermitidos` (403 "Usuário sem permissão para esta ação."),
    `PasswordHasher`, `SecretProtector`, `AuditoriaService`.
  - **Formatação:** `shared/Dinheiro`, equivalente ao `HorusMoneyFormat` (formatar e ler valores pt-BR).
  - **Entidades da Fase 2:** `Categoria`, `Fornecedor`, `Cliente`, `Empresa` (completa).
  - **Banco:** Flyway `V1` (esquema completo, com todas as tabelas desta fase), `V2` e `V3`.
  - **Contrato:** gravador e comparador (`contrato/README.md`, `MotorDeContrato.GRUPOS`), seed e reset de contrato e
    o ambiente .NET `contrato/ambiente-dotnet` (porta 5270).
  - **Padrões da Fase 2 que valem aqui:**
    - SQL do .NET portado literalmente em `@Query(nativeQuery = true)` ou `JdbcTemplate`;
    - lote T-SQL com várias instruções roda **instrução por instrução**, somando as contagens, porque o mssql-jdbc só
      devolve a contagem da última;
    - chaves de cifra do teste de contrato iguais às do `subir.sh`.
- **Referência .NET (fonte da verdade)**, em `API/NETCORE/`:

  | Módulo | Arquivos |
  | --- | --- |
  | Produtos | `Controllers/Produtos/ProdutoController.cs`, `Services/Produtos/ProdutoService.cs`, `Repositories/DatabaseAccess/ProdutoAB.cs` (982 l.), `Models/Produtos/ProdutoModel.cs`, `Models/Requests/ProdutoRequest.cs`, `ProdutoPrecoRequest.cs` |
  | Registrador | `Repositories/DatabaseAccess/EstoqueMovimentacaoAB.cs` (`AplicarMovimentoAsync`, `AplicarMovimentoPorCodigoAsync`, `EstoqueTipos`, `MovimentoOrigem`, listagens) |
  | Lotes/FEFO | `Controllers/Produtos/LoteController.cs`, `Services/Produtos/LoteService.cs`, `Repositories/DatabaseAccess/LoteAB.cs` (699 l.), `LoteEstimador` |
  | Entrada manual | `Controllers/Produtos/EntradaManualController.cs`, `Services/Produtos/EntradaManualService.cs` |
  | Importação NF-e | `Controllers/Produtos/NfeImportController.cs`, `Services/Produtos/NfeImportService.cs`, `NfeXmlParser.cs`, `NfeAccessKey.cs`, `NotaEntradaArquivoService.cs` |
  | Inventário | `Controllers/Estoque/InventarioController.cs` (mapa código → HTTP em `Responder`), `Repositories/DatabaseAccess/InventarioAB.cs` (899 l.), `HorusSecurityStore.ValidateSupervisorCredentials` |
  | Compras | `Controllers/Compras/OrdemCompraController.cs`, `Repositories/DatabaseAccess/OrdemCompraAB.cs` (605 l.), `Repositories/DataAccess/OrdemCompraAD.cs` |
  | Promoções | `Controllers/Promocoes/PromocaoController.cs`, `Services/Promocoes/PromocaoService.cs`, `Repositories/DatabaseAccess/PromocaoAB.cs`; spec `openspec/specs/vendas/promocoes` |
  | Vencimentos | `ProdutoAB.ListarVencimentosAsync`/`ObterResumoVencimentosAsync`; spec `openspec/specs/produtos/controle-validade` |
  | Datas | `Services/Shared/HorusDateTime.cs` (America/Sao_Paulo) |
  | Carga legada | `DataBase/Migrations/06_produtos_mercado_completo.sql` + `ProdutoAB.ImportarCargaLegadoAsync` |

- **Requisitos:** specs desta change. Para promoções e vencimentos, os specs existentes `vendas/promocoes` e
  `produtos/controle-validade`. Em dúvida sobre campo, formato ou mensagem, **vale o código .NET e a gravação do
  contrato**.
- **Cliente que define o aceite:** `JAVA/estoque` (Quack Estoque).
  - Os E2E rodam com `-Dquack.e2e.url=<API>` e usuário `caixa@teste.com` / `Teste@12345`;
  - alguns preparam dados por `docker exec <container> sqlcmd … -d HorusPdv` (`E2eSupport.sql`), com o container
    escolhido por `-Dquack.e2e.sqlContainer` (padrão `pdv-teste-sql`).

## Goals / Non-Goals

**Goals:**
- As rotas desta fase com o mesmo contrato da API .NET, medido pelo comparador.
- Um único caminho de mudança de saldo (o registrador), pronto para as vendas da Fase 4.
- Os E2E do Quack Estoque passando contra a API Java, menos os de relatórios e análise.

**Non-Goals:**
- `NfeImport/buscar-sefaz` de verdade, `Produto/{id}/verificacao-fiscal` e `Produto/fiscal-ia/*`. Vão para a Fase 7. A
  verificação e a IA **não** são mapeadas nesta fase e respondem 404 como qualquer rota inexistente.
- `api/Estoque/relatorios/*`, `api/Estoque/analise/*`, foto diária (`EstoqueSnapshotWorker`) e o cache da análise
  (Fase 6). Onde o .NET invalida o cache (aplicar inventário), a Java deixa um ponto de extensão vazio.
- Baixa de lotes na venda, estorno de venda, `VincularOrigemAsync` e `DevolverItensDaVendaAsync` (Fase 4).
- Corrigir esquisitices do .NET (D11).

## Decisions

### D1. Pacotes

Seguem o § 3.2 do plano, com `web/ app/ domain/ repo/` em cada módulo:

```
br.com.quack.pdv.produto     produto/ (cadastro, preço, validade, carga legada), lote/, entrada/ (manual, nfe, notas)
br.com.quack.pdv.estoque     registrador/ (RegistradorEstoque, tipos, origem), movimentacao/ (consultas), inventario/
br.com.quack.pdv.compras     ordem/
br.com.quack.pdv.promocao    promocao/
```

As regras de erro, usuário logado e perfis são as mesmas da Fase 2:
- `InvalidOperationException` do .NET vira `NegocioException(400)`;
- 401 é "Sessão não encontrada.";
- `@PerfisPermitidos` usa os perfis do `HorusAuthorizeRoles` de cada rota. Controller sem atributo na classe significa
  qualquer autenticado. O perfil `estoquista` já existe em `security/Perfis` (`Perfis.ESTOQUISTA`).

### D2. Registrador único de estoque

- `estoque/registrador/RegistradorEstoque`, com dois métodos:
  - `aplicar(companyId, produtoId, delta, tipo, custoUnitario, origem[, deposito="loja"])` → `Optional<Saldos(anterior, novo)>`;
  - `aplicarPorCodigo(...)`.
- Executa **o mesmo SQL** do `AplicarMovimentoAsync` por `JdbcTemplate`:
  - `DECLARE @r TABLE…; UPDATE Produtos SET ProductQnt = ProductQnt + ?, TotalPriceOnProduct = … OUTPUT deleted…, inserted… INTO @r WHERE…; SELECT … FROM @r`;
  - num só `query`, porque o resultado vem do `SELECT` final;
  - depois, o `INSERT INTO EstoqueMovimentacoes` com id `mov-<uuid sem hífens>`, quantidade absoluta, truncamentos
    (motivo 500, usuário 36/200, origem 20/80) e custo = informado ou o do produto.
- Delta zero devolve os saldos sem gravar movimentação. Produto inexistente devolve vazio.
- `@Transactional(propagation = MANDATORY)`: o registrador **só roda dentro da transação de quem chama**, que é o que
  garante saldo e movimentação juntos.
- **Guarda de arquitetura:**
  - a entidade `Produto` mapeia `ProductQnt` e `TotalPriceOnProduct` com `insertable = true, updatable = false`, então
    o JPA nunca muda o saldo;
  - um teste (`RegistradorUnicoTest`) varre `src/main/java` e falha se aparecer `UPDATE Produtos` com `ProductQnt`
    fora do `RegistradorEstoque`.
- Alternativa descartada: `@Version`/`PESSIMISTIC_WRITE` com leitura, soma em Java e `save`. Daria mais idas ao banco
  e não reproduz o anterior/novo exatos do `OUTPUT`.

### D3. Produtos

- **JSON:** o `ProdutoModel` do .NET usa **texto pt-BR** para quantidade e valores (`productQnt "10"`,
  `productUnitPrice "2,0000"`), formatado e lido pelo `HorusMoneyFormat`. A Java usa `Dinheiro` com as mesmas casas e
  copia campo a campo os nomes, a ordem e os nulos do modelo e do request. A gravação do contrato confere.
- **Ler produto:** o `ObterAsync` com o join da categoria, depois de toda gravação, como o .NET.
- **Unicidade:** código e GTIN únicos na empresa, sem diferenciar maiúsculas. Fornecedor validado pelo nome
  cadastrado, como no `ValidateBusinessRulesAsync`. Código vazio num produto novo vira `P-<uuid sem hífens>`.
- **Estoque relativo** (`SalvarAsync` do `ProdutoAB`), numa só transação:
  1. lê o saldo com `WITH (UPDLOCK, ROWLOCK)`;
  2. se veio `estoqueConsultado`, usa saldo + (quantidade − consultado);
  3. grava o cadastro (sem a quantidade);
  4. aplica o delta pelo registrador (`entrada`/`saida`, origem `cadastro`).
- **Preço com versão:** `UPDATE … SET ProductSalePrice=? … WHERE Id=? AND CompanyId=? AND ProductUnitPrice=? AND
  ProductSalePrice=?`. Com 0 linhas, relê o produto: se não existe, 404; se existe, `ConflitoException` → 409. A versão
  `custo|preço` é lida com ponto decimal (`InvariantCulture`), e o markup acima do limite dá a mensagem do .NET.
- **Carga legada:**
  - o script `06_produtos_mercado_completo.sql` é copiado para `src/main/resources/db/legado/` (não é migração
    Flyway);
  - na execução, tira as linhas `GO` e troca `VALUES (N'empresa-principal');` pelo parâmetro da empresa, como o .NET;
  - roda num `JdbcTemplate`;
  - erro dá 400 "Erro ao importar carga: <causa>".
- **Auditoria e efeitos colaterais da gravação de produto:** copiar o que `ProdutoService`/`ProdutoAB` fazem (conferir
  auditoria, mapeamento produto↔fornecedor, sincronização de validade).

### D4. Lotes e FEFO

- **Saldo exibido:** porta do `LoteEstimador` (`Distribuir` e `Saldos`), conferida por testes de mesa.
  - `Distribuir`: ordena por validade decrescente e aloca `min(QtdInicial, restante)`;
  - `Saldos`: no modo `ativo`, o saldo real; nos demais, o estimado.
- **Modo:** lido da coluna do .NET (`ObterModoAsync`); valor inválido ou nulo vale `sombra`.
- **"Hoje":** `LocalDate.now(clock.withZone(ZoneId.of("America/Sao_Paulo")))`, igual ao `HorusDateTime`. O `Clock`
  injetado da Fase 1 permite testar datas.
- **Operações "seguras"** (`ConsumirAjusteSeguroAsync`, `RegistrarEntradaAsync` depois do ajuste):
  - rodam **depois** do commit da operação principal, numa transação própria (`TransactionTemplate` com
    `REQUIRES_NEW`);
  - nunca lançam exceção, só registram no log;
  - o consumo usa id de origem `aj-<uuid>` e chama a sincronização da validade do produto.
- **Fase 4:** o consumo de venda (`ConsumirVendaSeguroAsync`) e o estorno (`EstornarVendaSeguroAsync`) ficam para lá,
  mas o `ConsumirNosLotes` comum já sai pronto e testado nesta fase.

### D5. Entradas de mercadoria

- **Leitura do XML:**
  - porta do `NfeXmlParser` com DOM do JDK (`DocumentBuilderFactory`) ciente de namespace;
  - **XXE desligado**: `disallow-doctype-decl`, sem entidades externas, `FEATURE_SECURE_PROCESSING`;
  - limite de 10 MB antes de ler;
  - base64 inválido gera a mensagem do .NET.
- **Chave de acesso:** porta do `NfeAccessKey` (44 dígitos, DV módulo 11, modelo nas posições 21–22, CNPJ do
  emitente), com testes de mesa usando chaves reais de exemplo (a do `JAVA/estoque/src/test/resources/…/nfe-exemplo.xml`).
- **Confirmação** (`NfeImportService.ConfirmarAsync`) e entrada manual (`EntradaManualService`), numa só transação:
  1. trava a operação com `sp_getapplock` (`@LockOwner='Transaction'`, 15 s), por SQL nativo na transação;
  2. procura uma entrada anterior pela operação (`operacaoId`) ou pela identidade da nota;
  3. se for a mesma operação com a mesma **impressão dos dados** (`fingerprint`, calculada igual ao .NET), devolve o
     resultado guardado; se os dados forem outros, dá erro; se for outra operação da mesma nota, dá "Esta nota já deu
     entrada…";
  4. cria ou vincula os produtos, aplica as entradas pelo registrador e grava a nota de entrada com o JSON da entrada
     (`Entrada`) e o XML;
  5. os lotes entram depois, pelas operações seguras de D4.

  A forma do JSON guardado em `NotasEntrada` deve ser igual à do .NET, para notas gravadas por uma API serem lidas pela
  outra.
- **Notas de entrada** (`NotaEntradaArquivoService`):
  - lista, detalhe, XML (`application/xml`, nome do .NET) e comprovante (JPG/PNG/PDF/XML até 10 MB);
  - estorno pelo registrador, com justificativa de 5+ caracteres e marcação de estornada;
  - **ZIP do mês** com `java.util.zip`: um XML por nota e o `resumo.csv` com as colunas e o separador do .NET;
  - vínculo de XML posterior.
- **`buscar-sefaz`:**
  - valida a chave como o `NfeAccessKey`/serviço .NET, com as mesmas mensagens, inclusive a de modelo 65;
  - com chave válida, responde 400 "A busca da nota na SEFAZ ainda não está disponível nesta versão. Importe a nota
    pelo arquivo XML.";
  - o cenário de contrato com chave válida fica como **divergência consciente** (campo `divergencia` do cenário, como
    o bloqueio de login da Fase 1), até a Fase 7.

### D6. Inventário

- Porta do `InventarioAB`. Os resultados são `InventarioResultado(sucesso, codigo, mensagem, dados)`, e o controller
  converte com o **mesmo mapa** do `Responder`:

  | Código | HTTP |
  | --- | --- |
  | `NAO_ENCONTRADO`, `CODIGO_DESCONHECIDO` | 404 |
  | `ESCOPO_SOBREPOSTO`, `INVENTARIO_ENCERRADO`, `JA_APLICADO`, `JA_ANULADA`, `OCUPADO` | 409 |
  | `SEM_PERMISSAO` | 403 |
  | `FORA_DO_ESCOPO` | 422 |
  | demais | 400 |

  O corpo de erro é `data: {codigo}` ou `{codigo, detalhe}`.
- **Contagem cega:** `comValores = perfil principal administrador ou gerente` (`HorusRoles.IsGerenteOuAdmin`). Os DTOs
  sem valores omitem ou zeram os campos exatamente como o .NET (conferir pela gravação do estoquista).
- **Supervisor:**
  - portar `ValidateSupervisorCredentials` para `usuario/app/ValidadorSupervisor`: usuário ativo da empresa, perfil
    administrador ou gerente, senha pelo `PasswordHasher`, mesmas mensagens;
  - vai ser usado de novo pelo caixa na Fase 4;
  - as tentativas negadas vão para a trilha (`RegistrarNegadaAsync`).
- **Aplicação:**
  - numa transação, aplica pelo registrador (tipo `inventario`, origem o inventário) a diferença entre o contado e
    o saldo registrado na primeira contagem, somada ao saldo atual. Isso preserva vendas e entradas posteriores à
    contagem, com o mesmo piso zero do .NET; `zerarNaoContados` segue a regra literal de `InventarioAB`;
  - os ajustes de lote correspondentes rodam depois, como operações seguras (o .NET junta `lotes` e aplica depois do
    commit; conferir);
  - IP do ator pelo `ClienteIpResolver` da Fase 1 e terminal opcional.
- **Abertura serializada:** a mesma trava do .NET (applock por empresa ou equivalente). Falhar em obter a trava dá
  `OCUPADO`.

### D7. Ordens de compra

- Porta do `OrdemCompraAB`:
  - **número:** `ISNULL(MAX(TRY_CONVERT(INT, OrderNumber)),0)+1` por empresa, na transação da criação, com o mesmo
    bloqueio do .NET;
  - **situações:** 0 pendente, 1 recebido, 2 cancelado, 3 recebido parcial;
  - "Fornecedor não informado" quando não há fornecedor;
  - editar só pendente;
  - cancelar só quando `Status = 0`;
  - **receber:** soma o recebido por item, aplica a entrada pelo registrador (custo do item), registra o lote quando
    vier validade e calcula a nova situação. Qualquer exceção vira 400 com a mensagem, como o `catch (Exception)` do
    .NET.
- **Sugestões de reposição:** SQL literal, inclusive a comparação
  `CAST(REPLACE(REPLACE(p.ProductQnt,'.',''),',','.') AS DECIMAL(15,4)) < p.EstoqueMinimo` (ver D11).

### D8. Promoções

Porta do `PromocaoService` + `PromocaoAB`:
- os 6 tipos e a validação de cada um, com as mensagens do .NET;
- vigência, `ativas` (usada pelo Caixa e pela web), ativar e desativar, exclusão;
- `resultado`, que lê `VendaItens`/`Vendas` por consulta nativa (as vendas são da Fase 4, mas o banco já tem a
  tabela);
- datas de vigência no formato do .NET (`FormatoO`), e "agora" no fuso de Brasília para decidir se está ativa.

Requisitos no spec existente `vendas/promocoes`; esta change não o modifica.

### D9. Contrato gravado e comparado

- Os vetores temporais desta fase usam a referência **02/11/2026**, data da gravação .NET, com um `Clock` deslocado
  para essa data, que avança normalmente, somente na configuração de `ApiJavaContratoTest`. Isso mantém a comparação
  literal de dias restantes, faixas e contadores após a virada do dia, preservando IDs e ordenação; produção e demais
  ITs continuam com seu relógio normal.
  Ao regravar vetores dependentes de hoje em outra data, atualize conjuntamente essa referência e os grupos afetados.

- **Grupos novos:** `produto`, `movimentacao`, `lote`, `entrada-manual`, `nfe-import`, `inventario`, `ordem-compra`,
  `promocao`.
- **Cobertura de cada grupo:** sucesso, validações dos specs, 401, 403 por perfil (inclusive estoquista) e isolamento
  entre empresas.
- **Seed** (`seed-contrato.sql`, nos dois lados), ampliado com:
  - na `emp-contrato-aprovada`, um usuário **estoquista**;
  - produtos com e sem controle de validade, com lotes;
  - um produto com estoque mínimo;
  - categorias com prazo;
  - uma ordem de compra pendente e uma recebida;
  - uma promoção de cada tipo;
  - um inventário aberto;
  - a configuração de modo FEFO.

  O reset limpa e recria essas tabelas, filhas antes das mães.
- **XML de teste:** fixtures em `src/test/resources/contrato/fixtures/`:
  - o `nfe-exemplo.xml` do Quack Estoque;
  - um NFC-e de exemplo;
  - um XML com chave adulterada;
  - um XML sem `<det>`.

  Os cenários enviam o base64 deles.
- **Campos voláteis:**
  - `id` para `pr-`, `mov-`, lote, inventário, nota de entrada, contagem e promoção;
  - `iso`/`data` para datas;
  - o número da ordem é estável, porque o seed define o maior.
- **Formato numérico das mensagens:** mensagens com número interpolado ("Estoque insuficiente. Disponível: …") seguem
  o formato que a gravação mostrar. O .NET roda sem cultura definida, provavelmente invariante (`10.000`). A Java
  formata `BigDecimal` com a mesma escala do banco e ponto decimal.

### D10. Aceite pelo E2E do Quack Estoque

O ambiente fica **separado** do banco da API .NET:
1. Gerar backup do `HorusPdv` do container `pdv-teste-sql` (o banco de teste dos E2E). Restaurar num container novo
   `pdv-java-sql` (SQL Server 2022, mesmo nome de banco `HorusPdv`, mesma senha de `sa`), para o `E2eSupport.sql` e o
   `-d HorusPdv` funcionarem.
2. Subir a API Java (perfil padrão, com variáveis de ambiente) na porta **5261** sobre o `pdv-java-sql`:
   - o Flyway faz baseline (versão 1) e roda `V2`/`V3`, que são idempotentes;
   - limite por IP alto (`quack.security.rate-limit.max-requests=5000`), porque a suíte faz muitos logins;
   - e-mail desligado.
3. Rodar:
   ```
   mvn -o -f JAVA/estoque/pom.xml test -Dquack.e2e.url=http://localhost:5261 -Dquack.e2e.sqlContainer=pdv-java-sql \
     -Dtest='*E2ETest,!RelatoriosE2ETest,!ComprasE2ETest#geraUmPedidoPorFornecedor,!ConfigE2ETest,!CurvaReposicaoE2ETest,!DashboardE2ETest,!Etapa5E2ETest,!InventarioE2ETest#vendaDuranteAContagemEPreservada+senhaErradaNaoAlteraEstoqueERegistraTentativa+aplicarPelaDiferencaPreservandoAVenda+aplicacaoDuplaEContagemEmEncerradoRecusadas+trilhaDoInventarioAplicado,!InventarioAppE2ETest' -Dsurefire.failIfNoSpecifiedTests=false
   ```
   `geraUmPedidoPorFornecedor` usa `api/Estoque/relatorios` (Fase 6).
   As quatro classes de `analise` nomeadas consultam análise da Fase 6, que é um não objetivo desta change.
   A rodada inicial de aceite confirmou 404 nessas rotas; `Etapa5E2ETest` falha na própria UI antes da consulta.
   Também ficam fora, até a Fase 4, os 6 E2E de inventário que dependem de venda pelo `POST /api/HistoricoVendas`
   (404 na API Java): `InventarioAppE2ETest` e 5 métodos de `InventarioE2ETest` (`vendaDuranteAContagemEPreservada`,
   os 3 que dependem do estoque baixado por ela e `trilhaDoInventarioAplicado`, que lê o inventário aplicado por um
   deles). Os outros 8 métodos de `InventarioE2ETest` continuam obrigatórios.
   Decisão de 03/11/2026; a Fase 4 traz esses testes de volta ao comando.
4. Um script `JAVA/api/contrato/aceite-estoque/subir.sh`/`descer.sh` automatiza os passos 1 e 2, como o
   `ambiente-dotnet`.

A falha de um E2E se investiga pelo comparador: se a rota está igual à gravação e o E2E falha, falta cenário. Grave o
cenário e corrija.

### D11. Fidelidade a esquisitices do .NET

Reproduzidas de propósito (corrigir só depois do corte, em change própria):
- **Comparação de saldo nas sugestões de reposição:** o `REPLACE` em cima de `ProductQnt` (que hoje é `DECIMAL`)
  apaga o ponto decimal antes do `CAST`, e `5.000` vira `5000`.
- **Lotes fora da transação:** ajuste de lote depois do ajuste de saldo, sem desfazer o saldo em caso de falha
  (operações "seguras").
- **Recebimento de ordem:** `catch (Exception)` devolve 400 com a mensagem crua de qualquer erro.

## Risks / Trade-offs

- [Volume: cerca de 70 rotas e 4.000 linhas de SQL .NET] → tarefas por módulo, cada uma com o próprio grupo de
  contrato verde antes da próxima. O registrador vem primeiro, porque todos dependem dele.
- [Formato pt-BR dos valores no JSON de produto, que é fácil errar uma casa] → `Dinheiro` com testes de mesa e o
  comparador campo a campo.
- [`OUTPUT INTO` + `SELECT` num lote pelo mssql-jdbc] → executar por `query` (o resultado vem do `SELECT` final) e testar
  em IT com concorrência (duas threads, saldo final e anterior/novo encadeados).
- [JSON gravado em `NotasEntrada.Entrada` diferente do .NET quebra o replay entre APIs] → teste que lê uma entrada
  gravada pelo .NET (do seed) e faz o replay na Java.
- [XXE e XML grande na importação] → parser endurecido e limite de 10 MB antes de ler, com teste de XML com DOCTYPE.
- [Banco do E2E compartilhado com a API .NET] → container separado `pdv-java-sql` (D10).
- [Divergência consciente no `buscar-sefaz`] → registrada no cenário e no README, e removida na Fase 7.

## Migration Plan

- **Banco:** sem mudança de esquema e sem migração nova.
- **Recurso novo:** só o script legado em `db/legado/`, fora do Flyway.
- **Entrega:** branch `spring-api`, com a imagem pelo CI. Na homologação, o Quack Estoque é apontado para a VPS nova.
- **Volta atrás:** voltar a imagem anterior. Os dados gravados pela Java são os mesmos que a .NET lê (contrato e
  formato do JSON das notas).

## Open Questions

- Corrigir as esquisitices de D11 depois do corte. Isso não muda esta fase.
