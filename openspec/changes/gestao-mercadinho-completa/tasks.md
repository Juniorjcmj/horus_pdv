# Tasks: Gestão Completa de Mercadinho

## Fase 1 — Categorias / Departamentos de Produto

### 1.1 Banco de Dados
- [x] 1.1.1 Criar migration `09_categorias_produto.sql`: tabela `Categorias` com PK, CompanyId, Nome, CategoriaPaiId (auto-ref FK), Ordem, Ativa, UNIQUE (CompanyId, Nome, CategoriaPaiId), índice IX_Categorias_Company
- [x] 1.1.2 Na mesma migration: `ALTER TABLE Produtos ADD CategoriaId NVARCHAR(40) NULL` com FK para Categorias ON DELETE SET NULL
- [x] 1.1.3 Seed idempotente de 14 departamentos padrão (Mercearia, Bebidas, Laticínios, Padaria, Açougue, Hortifruti, Frios, Limpeza, Higiene, Congelados, Bomboniere, Bazar, Pet, Tabacaria) + subcategorias essenciais (~25 subcategorias)
- [x] 1.1.4 Registrar migration no `HorusDatabaseInitializer` para execução automática no boot

### 1.2 Backend — Repository e Model
- [x] 1.2.1 Criar `CategoriaAD.cs` em `Repositories/DataAccess/` com record: Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa, QuantidadeProdutos (calculado)
- [x] 1.2.2 Criar `CategoriaAB.cs` em `Repositories/DatabaseAccess/` com métodos: ListarArvoreAsync (retorna departamentos com subcategorias e contagem), SalvarAsync (upsert), AtivarDesativarAsync, ExcluirAsync (valida produtos vinculados), ObterPorIdAsync
- [x] 1.2.3 Atualizar `ProdutoAD.cs`: adicionar campo `CategoriaId`, `CategoriaNome` (join)
- [x] 1.2.4 Atualizar `ProdutoAB.cs`: adicionar CategoriaId no Columns, Map, SalvarAsync, e LEFT JOIN com Categorias no SELECT para trazer CategoriaNome

### 1.3 Backend — Controller e Service
- [x] 1.3.1 Criar `ICategoriaService` e `CategoriaService` com métodos CRUD + validações de negócio
- [x] 1.3.2 Criar `CategoriaController` em `Controllers/Categorias/`: GET (árvore), POST, PUT, PATCH /{id}/status, DELETE /{id} — roles: administrador, gerente
- [x] 1.3.3 Registrar service e repository no DI (`Program.cs`)

### 1.4 Backend — Relatórios
- [x] 1.4.1 Adicionar filtro `categoriaId` opcional nos relatórios existentes em `RelatorioAB` (vendas-periodo, historico-vendas, produtos-mais-vendidos, estoque-critico, movimento-estoque) via LEFT JOIN Produtos → Categorias
- [x] 1.4.2 Criar novo relatório `margem-por-categoria` no `RelatorioAB`: agrupa por departamento, calcula Receita (SUM VendaItens.ItemTotal), CMV (SUM Produtos.ProductUnitPrice × qty), Margem R$ e Margem %

### 1.5 Frontend — Service e Tipos
- [x] 1.5.1 Criar `categoriaService.ts` em `services/api/` com métodos: list (árvore), create, update, toggleStatus, remove
- [x] 1.5.2 Criar tipos TypeScript: `Categoria`, `CategoriaArvore` (com subcategorias[] e quantidadeProdutos)

### 1.6 Frontend — Cadastro de Produto
- [x] 1.6.1 Atualizar `ProductRegisterPage.tsx`: adicionar seção "Classificação" com select cascata Departamento → Subcategoria, carregando categorias via `categoriaService.list()`
- [x] 1.6.2 Ao selecionar departamento, filtrar subcategorias automaticamente. Persistir `categoriaId` no payload de salvar produto

### 1.7 Frontend — PDV
- [x] 1.7.1 Atualizar `SalesStartPage.tsx`: adicionar grid de botões de departamento abaixo da barra de busca (modo toggle: clica para filtrar, clica de novo para voltar)
- [x] 1.7.2 Ao clicar em departamento, filtrar lista de produtos local para exibir apenas produtos da categoria (incluindo subcategorias)

### 1.8 Frontend — Relatórios
- [x] 1.8.1 Atualizar `reportsConfig.ts`: adicionar filtro `categoriaId` (select de departamentos) nos relatórios existentes
- [x] 1.8.2 Adicionar card do relatório `margem-por-categoria` na grade de relatórios

---

## Fase 2 — Controle de Validade

### 2.1 Banco de Dados
- [x] 2.1.1 Criar migration `10_controle_validade.sql`: ALTER TABLE Produtos ADD DataValidade DATE NULL, ControlaValidade BIT NOT NULL DEFAULT 0, DiasAlertaValidade INT NOT NULL DEFAULT 15
- [x] 2.1.2 Criar índice filtrado IX_Produtos_Validade (CompanyId, ControlaValidade, DataValidade) WHERE ControlaValidade = 1 AND DataValidade IS NOT NULL

### 2.2 Backend — Repository e Model
- [x] 2.2.1 Atualizar `ProdutoAD.cs`: adicionar campos DataValidade (DateTime?), ControlaValidade (bool), DiasAlertaValidade (int)
- [x] 2.2.2 Atualizar `ProdutoAB.cs`: adicionar campos no Columns, Map (ReadNullableDateTime), SalvarAsync
- [x] 2.2.3 Adicionar métodos em `ProdutoAB`: ListarVencimentosAsync(companyId, dias), ObterResumoVencimentosAsync(companyId), AtualizarValidadeAsync(companyId, produtoId, dataValidade)

### 2.3 Backend — Controller
- [x] 2.3.1 Adicionar endpoints no `ProdutoController`: GET /vencimentos?dias=N, GET /vencimentos/resumo, PUT /{id}/validade
- [x] 2.3.2 Retornar no resumo: contagem de vencidos, venceEm7Dias, venceEm15Dias, venceEm30Dias, produtosControlados, produtosSemDataInformada

### 2.4 Backend — Dashboard e Relatório
- [x] 2.4.1 Atualizar `HomeAB` (ou criar método em ProdutoAB): incluir resumo de vencimentos nos KPIs do dashboard
- [x] 2.4.2 Criar relatório `vencimentos` no `RelatorioAB`: código, produto, categoria, validade, diasRestantes, qtdEstoque, custoTotal, com filtro por faixa (vencido/7d/15d/30d/todos)

### 2.5 Frontend — Cadastro de Produto
- [x] 2.5.1 Atualizar `ProductRegisterPage.tsx`: adicionar seção "Controle de Validade" com checkbox "Controlar validade", date picker para DataValidade, campo numérico DiasAlertaValidade
- [x] 2.5.2 Exibir indicador visual do status: verde (OK), amarelo (próximo), vermelho (vencido) baseado na data atual vs DataValidade

### 2.6 Frontend — Dashboard
- [x] 2.6.1 Criar componente `ValidadeAlertWidget.tsx`: exibe contagem por faixa com ícones semafóricos (vermelho/amarelo/verde) e link para relatório de vencimentos
- [x] 2.6.2 Integrar widget na `HomePage` chamando endpoint GET /api/Produto/vencimentos/resumo

### 2.7 Frontend — PDV
- [x] 2.7.1 Atualizar `SalesStartPage.tsx`: ao adicionar produto ao carrinho, verificar DataValidade. Se vencido, exibir modal de alerta (bloqueio ou confirmação conforme config). Se próximo, badge amarelo discreto no item

### 2.8 Frontend — Relatório
- [x] 2.8.1 Adicionar relatório `vencimentos` em `reportsConfig.ts` com filtro de faixa temporal
- [x] 2.8.2 Adicionar card na grade de relatórios

---

## Fase 3 — Promoções e Preços Dinâmicos

### 3.1 Banco de Dados
- [x] 3.1.1 Criar migration `11_promocoes.sql`: tabela Promocoes com PK, CompanyId, Nome, Tipo, ValorDesconto, PrecoFixo, QuantidadeLeva, QuantidadePaga, QuantidadeMinima, InicioVigencia, FimVigencia, Ativa, CategoriaId (FK Categorias), CriadoPor, CriadoEm, índice IX_Promocoes_Vigencia
- [x] 3.1.2 Tabela PromocaoProdutos com PK, PromocaoId (FK CASCADE), ProdutoId (FK CASCADE), UNIQUE (PromocaoId, ProdutoId)
- [x] 3.1.3 ALTER TABLE VendaItens ADD Desconto DECIMAL(18,2) NOT NULL DEFAULT 0
- [x] 3.1.4 ALTER TABLE VendaItens ADD PromocaoId NVARCHAR(40) NULL (referência informativa, sem FK para não bloquear exclusão de promo)

### 3.2 Backend — Repository e Model
- [x] 3.2.1 Criar `PromocaoAD.cs` em `Repositories/DataAccess/`: record com todos os campos + lista de ProdutoIds
- [x] 3.2.2 Criar `PromocaoAB.cs` em `Repositories/DatabaseAccess/`: ListarAsync, ListarAtivasAsync (filtra Ativa=1 AND vigência atual, inclui ProdutoIds e CategoriaId), SalvarAsync (upsert promo + sync PromocaoProdutos), AtivarDesativarAsync, ExcluirAsync, ObterResultadoAsync
- [x] 3.2.3 Atualizar `HistoricoVendasAB`: incluir campos Desconto e PromocaoId no INSERT de VendaItens

### 3.3 Backend — Controller e Service
- [x] 3.3.1 Criar `IPromocaoService` e `PromocaoService` com validações: vigência coerente (inicio < fim), tipo com parâmetros corretos, produtos existentes
- [x] 3.3.2 Criar `PromocaoController` em `Controllers/Promocoes/`: GET (todas), GET /ativas (para PDV), POST, PUT /{id}, PATCH /{id}/status, DELETE /{id}, GET /{id}/resultado — roles: administrador, gerente para CRUD; todos para GET /ativas
- [x] 3.3.3 Registrar no DI

### 3.4 Backend — Integração Fiscal
- [x] 3.4.1 Atualizar `DocumentoFiscalAB.MontarRequisicaoEmissaoAsync`: ler campo Desconto de VendaItens e preencher `vDesc` no item da NFC-e
- [x] 3.4.2 Atualizar `ZeusFiscalProvider`: mapear `vDesc` para o nó XML `det[i].prod.vDesc` quando > 0

### 3.5 Frontend — Service e Tipos
- [x] 3.5.1 Criar `promocaoService.ts` em `services/api/`: list, listAtivas, create, update, toggleStatus, remove, getResultado
- [x] 3.5.2 Criar tipos TypeScript: `Promocao`, `PromocaoAtiva` (com produtoIds[], categoriaId), `TipoPromocao` (union type dos 6 tipos)

### 3.6 Frontend — Motor de Promoção
- [x] 3.6.1 Criar `promotionEngine.ts` em `utils/`: função pura `applyPromotions(cartItems, activePromotions)` que retorna carrinho com descontos calculados
- [x] 3.6.2 Implementar lógica para cada tipo: desconto_percentual, desconto_valor, preco_fixo, leve_x_pague_y, combo_quantidade, preco_atacado
- [x] 3.6.3 Implementar resolução de conflitos: quando múltiplas promoções aplicam ao mesmo item, calcular desconto efetivo de cada e aplicar a maior
- [x] 3.6.4 Implementar resolução de promoção por categoria: expandir CategoriaId para lista de ProdutoIds usando dados carregados

### 3.7 Frontend — PDV
- [x] 3.7.1 Atualizar `SalesStartPage.tsx`: carregar promoções ativas no boot e recarregar a cada 5 minutos
- [x] 3.7.2 Integrar motor de promoção: chamar `applyPromotions()` a cada adição/remoção de item no carrinho
- [x] 3.7.3 Exibir no carrinho: badge "PROMO" + nome da promoção, preço original riscado, preço promocional, desconto em R$
- [x] 3.7.4 Enviar ao backend: unitPrice (preço cheio), discount (desconto), itemTotal (líquido), promocaoId

### 3.8 Frontend — Tela de Gestão
- [x] 3.8.1 Criar `PromocoesPage.tsx` em `pages/Admin/`: listagem com filtro (ativas/encerradas/todas), cards ou tabela com nome, tipo, vigência, status, contagem de produtos
- [x] 3.8.2 Modal de criação/edição: nome, select de tipo (com campos dinâmicos conforme tipo), date pickers de vigência, busca e multi-select de produtos, select opcional de categoria
- [x] 3.8.3 Ações: ativar/desativar (toggle), editar, excluir com confirmação
- [x] 3.8.4 Adicionar rota `promocoes` no router dentro do layout Admin

### 3.9 Frontend — Relatório
- [x] 3.9.1 Exibir resultado da promoção no modal de detalhes da PromocoesPage: vendas afetadas, receita bruta, receita líquida, desconto total

---

## Fase 4 — Fiado / Conta Corrente do Cliente

### 4.1 Banco de Dados
- [x] 4.1.1 Criar migration `12_fiado_conta_corrente.sql`: ALTER TABLE Clientes ADD LimiteCredito DECIMAL(18,2) NOT NULL DEFAULT 0, SaldoDevedor DECIMAL(18,2) NOT NULL DEFAULT 0
- [x] 4.1.2 Criar tabela FiadoMovimentos: Id, CompanyId, ClienteId (FK Clientes), Tipo TINYINT (1=Débito, 2=Crédito), Valor, SaldoAnterior, SaldoAtual, VendaId (FK Vendas NULL), Observacao, OperadorNome, CriadoEm DATETIMEOFFSET, índice IX_FiadoMovimentos_Cliente (CompanyId, ClienteId, CriadoEm DESC)

### 4.2 Backend — Repository e Model
- [x] 4.2.1 Criar `FiadoMovimentoAD.cs` em `Repositories/DataAccess/`: record com todos os campos + ClienteNome (join)
- [x] 4.2.2 Criar `FiadoAB.cs` em `Repositories/DatabaseAccess/`:
  - `RegistrarDebitoAsync(companyId, clienteId, valor, vendaId, operador)`: em transaction com UPDLOCK no Clientes, INSERT FiadoMovimentos tipo=1, UPDATE SaldoDevedor
  - `RegistrarCreditoAsync(companyId, clienteId, valor, formaPagamento, observacao, operador)`: em transaction, valida valor ≤ SaldoDevedor, INSERT tipo=2, UPDATE SaldoDevedor
  - `ListarDevedoresAsync(companyId)`: clientes com SaldoDevedor > 0, com data da última compra fiado
  - `ObterExtratoAsync(companyId, clienteId, startDate, endDate)`: movimentos ordenados por CriadoEm DESC
  - `ObterResumoAsync(companyId)`: KPIs de inadimplência
- [x] 4.2.3 Atualizar `ClienteAD.cs`: adicionar campos LimiteCredito, SaldoDevedor
- [x] 4.2.4 Atualizar `ClienteAB.cs`: incluir LimiteCredito e SaldoDevedor no Columns, Map, SalvarAsync

### 4.3 Backend — Integração com Venda
- [x] 4.3.1 Atualizar `HistoricoVendasAB.RegistrarAsync`: detectar pagamento "fiado" em payments; dentro da mesma transaction, chamar lógica de débito fiado (SELECT Clientes WITH UPDLOCK, validar limite, INSERT FiadoMovimentos, UPDATE SaldoDevedor)
- [x] 4.3.2 Validar: se LimiteCredito > 0 AND SaldoDevedor + valorFiado > LimiteCredito → throw InvalidOperationException com mensagem clara
- [x] 4.3.3 Validar: se pagamento "fiado" sem clienteId/customerCpf vinculado → throw InvalidOperationException

### 4.4 Backend — Controller e Service
- [x] 4.4.1 Criar `IFiadoService` e `FiadoService` com validações de negócio
- [x] 4.4.2 Criar `FiadoController` em `Controllers/Fiado/`: POST /receber, GET /extrato/{clienteId}, GET /devedores, GET /resumo — roles: administrador, gerente, caixa
- [x] 4.4.3 Registrar no DI
- [x] 4.4.4 Inserir AuditLog para cada movimentação (EventType: FiadoDebito, FiadoRecebimento)

### 4.5 Backend — Integração Fiscal
- [x] 4.5.1 Atualizar mapeamento de formas de pagamento no `ZeusFiscalProvider` (ou `DocumentoFiscalAB`): `"fiado"` → `tPag=05` (crediário loja)

### 4.6 Frontend — Service e Tipos
- [x] 4.6.1 Criar `fiadoService.ts` em `services/api/`: receber, extrato, listarDevedores, resumo
- [x] 4.6.2 Criar tipos TypeScript: `FiadoMovimento`, `FiadoResumo`, `FiadoDevedor`, `RecebimentoRequest`

### 4.7 Frontend — Cadastro de Cliente
- [x] 4.7.1 Atualizar formulário de cliente: adicionar campo "Limite de crédito" (R$) na seção financeira. Exibir saldo devedor atual (read-only)

### 4.8 Frontend — PDV
- [x] 4.8.1 Atualizar `SalesStartPage.tsx`: adicionar "Fiado" na lista de formas de pagamento do split payment
- [x] 4.8.2 Ao selecionar "Fiado": exigir busca/seleção de cliente cadastrado (modal de busca por nome/CPF)
- [x] 4.8.3 Após selecionar cliente: exibir saldo devedor e limite disponível. Bloquear se limite excedido
- [x] 4.8.4 No recibo: adicionar indicação "FIADO" com saldo devedor atualizado

### 4.9 Frontend — Tela de Gestão do Fiado
- [x] 4.9.1 Criar `FiadoPage.tsx` em `pages/Admin/`: KPIs (total a receber, inadimplência >30d, >60d, qtd clientes), busca de cliente, tabela de devedores (nome, saldo, última compra, ações)
- [x] 4.9.2 Modal de recebimento: campo valor (sugestão: saldo total), select forma de pagamento (dinheiro/pix/debito/credito), campo observação, botões confirmar/cancelar
- [x] 4.9.3 Modal de extrato: lista cronológica de movimentos (data, tipo, valor, saldo), botão de impressão 80mm com layout térmico (cabeçalho empresa, dados cliente, tabela movimentos, saldo devedor, linha de assinatura)
- [x] 4.9.4 Adicionar rota `fiado` no router dentro do layout Admin

### 4.10 Frontend — Relatório
- [x] 4.10.1 Adicionar relatório `inadimplencia` em `reportsConfig.ts`: cliente, CPF, saldo devedor, última compra fiado, dias desde último pagamento, com filtro por faixa de atraso

---

## Fase 5 — Testes e Validação

### 5.1 Categorias
- [x] 5.1.1 Testar CRUD de categorias (criar departamento, subcategoria, editar, desativar, excluir vazia, bloquear exclusão com produtos)
- [x] 5.1.2 Testar filtro por categoria no PDV (grid de botões, filtro de produtos)
- [x] 5.1.3 Testar relatório margem-por-categoria e filtro categoriaId nos relatórios existentes

### 5.2 Validade
- [x] 5.2.1 Testar cadastro de produto com controle de validade (ativar/desativar, definir data, alterar dias alerta)
- [x] 5.2.2 Testar widget de alertas no dashboard (vencido, 7d, 15d, sem alertas)
- [x] 5.2.3 Testar alerta no PDV ao vender produto vencido (bloqueio e sem bloqueio)

### 5.3 Promoções
- [x] 5.3.1 Testar cada tipo de promoção no PDV: desconto %, desconto R$, preço fixo, leve 3 pague 2, 3 por R$10, preço atacado
- [x] 5.3.2 Testar conflito de promoções (2 promos no mesmo produto → maior desconto vence)
- [x] 5.3.3 Testar vigência (promoção dentro do período, expirada, desativada manualmente)
- [x] 5.3.4 Verificar XML da NFC-e: campo vDesc preenchido corretamente

### 5.4 Fiado
- [x] 5.4.1 Testar venda fiado completa: selecionar cliente, verificar limite, finalizar, conferir saldo
- [x] 5.4.2 Testar bloqueio por limite excedido
- [x] 5.4.3 Testar pagamento misto (R$50 dinheiro + R$50 fiado) com split payment
- [x] 5.4.4 Testar recebimento parcial e total, verificar atualização do saldo
- [x] 5.4.5 Testar extrato com impressão 80mm
- [x] 5.4.6 Verificar XML da NFC-e: tPag=05 para fiado
