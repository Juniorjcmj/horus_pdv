## Context

O sistema já possui 20 relatórios no catálogo, todos seguindo o padrão: frontend define card+filtros em `reportsConfig.ts`, backend gera dados em `RelatorioAB.cs` via switch no `GerarAsync`. O endpoint `POST /api/Relatorio/Gerar` já existe e aceita `reportId` + `filters`.

Todos os campos fiscais e contábeis necessários já existem no modelo `ProdutoAD` (94 propriedades). A movimentação de vendas está em `Vendas`+`VendaItens` e as entradas em `NotasEntradaArquivo.EntradaJson`.

Ver proposal.md para motivação.

## Goals / Non-Goals

**Goals:**
- Relatório funcional com dados fiscais, contábeis e movimentação, usando a infraestrutura existente.
- Zero migrations — tudo via dados já persistidos.

**Non-Goals:**
- Exportação para PDF/Excel (pode ser adicionada futuramente).
- Cálculo de impostos devidos (PIS/COFINS/ICMS efetivos) — mostra apenas a classificação fiscal.
- Histórico de alterações dos dados fiscais (mostra o cadastro atual).

## Decisions

### D1: Query em 3 fontes, merge em C#

**Escolha**: Fazer 3 queries separadas (Produtos, VendaItens agregado, NotasEntradaArquivo) e fazer o merge por ProductCode/ProductId em C# com LINQ.

**Alternativa descartada**: JOIN único das 3 tabelas — a `NotasEntradaArquivo` armazena itens em JSON (`EntradaJson`), não em tabela relacional, então não dá para fazer JOIN direto. Parsear JSON no SQL Server (`OPENJSON`) seria possível mas frágil e lento para muitos registros.

**Racional**: Cada query é simples, testável isoladamente e não depende de estrutura JSON no SQL.

### D2: Movimentação de entrada via parse do EntradaJson em C#

**Escolha**: Ler `NotasEntradaArquivo` do período, parsear `EntradaJson` como `JsonElement`, iterar os itens e agregar por `produtoExistenteId`.

**Racional**: O JSON já contém `produtoExistenteId`, `quantidade` e `precoCusto` por item. É o mesmo formato usado pelo `EstornarNotaEntradaAsync`. Notas estornadas (`Estornada=1`) SHALL ser excluídas da contagem.

### D3: Dados fiscais/contábeis do cadastro atual

**Escolha**: Exibir os valores atuais do cadastro (`Produtos`), não os valores históricos no momento da venda/entrada.

**Racional**: O sistema não armazena snapshots fiscais por transação (exceto no XML da NF-e). Para conferência fiscal, o estado atual é o relevante (é o que está sendo praticado agora).

### D4: Restrição de acesso no backend via verificação de role

**Escolha**: Verificar a role do usuário dentro do método `GerarFichaFiscalAsync` e lançar exceção se for atendente/caixa. O controller já tem `[HorusAuthorizeRoles("administrador", "gerente", "atendente")]`, mas queremos bloquear atendente especificamente para este relatório.

**Alternativa descartada**: Criar endpoint separado com autorização diferente — desnecessário, o padrão existente já resolve com verificação interna (mesmo approach do `log-atividades` que restringe atendente).

### D5: Frontend — card no catálogo existente

**Escolha**: Adicionar entrada em `reportsConfig.ts` com filtros `startDate`, `endDate`, `categoriaId` e `productSearch`. O `ReportFiltersView` renderiza automaticamente.

**Sem alteração em componentes**: a tabela padrão renderiza as colunas. Como são muitas colunas (30+), a tabela usará scroll horizontal nativo do `TableScrollArea` já existente.

## Risks / Trade-offs

- **[Muitas colunas]** → 30+ colunas na tabela pode dificultar leitura em telas pequenas. Mitigação: scroll horizontal via `TableScrollArea` já usado no sistema. Futuramente pode-se agrupar em abas.
- **[Performance com muitas notas de entrada]** → Parsear JSON de todas as notas do período pode ser lento se houver milhares. Mitigação: a query filtra por `CriadaEm` e `CompanyId` (índice existente); na prática, lojas pequenas/médias têm dezenas de notas por mês.
- **[Dados fiscais desatualizados]** → Se o cadastro fiscal foi corrigido depois de uma venda, o relatório mostra o dado novo, não o que estava vigente na venda. Trade-off aceito — o sistema não tem versionamento fiscal por transação.
