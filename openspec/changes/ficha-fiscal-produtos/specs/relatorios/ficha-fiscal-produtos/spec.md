## Purpose

Relatório que consolida dados fiscais, contábeis e de movimentação (vendas e entradas) dos produtos que tiveram giro em um período, para conferência fiscal e análise de custos.

## ADDED Requirements

### Requirement: Geração do relatório ficha-fiscal-produtos

O sistema SHALL aceitar `reportId: "ficha-fiscal-produtos"` no endpoint `POST /api/Relatorio/Gerar` e retornar uma tabela com uma linha por produto que teve movimentação (venda ou entrada) no período informado.

Cada linha SHALL conter:
- **Identificação**: código do produto, nome, GTIN, departamento/categoria.
- **Classificação fiscal**: NCM, CEST, CFOP, Origem da Mercadoria, Unidade Comercial, Unidade Tributável.
- **Tributação**: CSOSN/CST ICMS, Alíquota ICMS (%), CST PIS, CST COFINS, CST IBS/CBS, Classificação Tributária (CClassTrib).
- **Dados contábeis**: Custo unitário atual, Preço de venda atual, Custo médio, Custo c/ imposto, Custo s/ imposto, Margem desejada (%), Markup cadastrado (%), Markup praticado (%), Lucro unitário (R$), Estoque atual, Valor total em estoque (R$).
- **Movimentação no período**: Quantidade vendida, Faturamento de vendas (R$), Quantidade entrada, Custo total das entradas (R$).

#### Scenario: Produto com vendas e entradas no período
- **WHEN** o operador gera o relatório com período de 01/10 a 31/10 e existem vendas e entradas para o produto "Arroz 5kg"
- **THEN** o sistema retorna uma linha para "Arroz 5kg" com todos os campos fiscais/contábeis do cadastro e a movimentação agregada do período (soma de quantidades vendidas, soma de faturamento, soma de quantidades entradas, soma de custos de entrada)

#### Scenario: Produto com apenas vendas no período
- **WHEN** o produto "Café 500g" teve vendas mas nenhuma entrada no período
- **THEN** o sistema retorna a linha com quantidade entrada = 0 e custo de entradas = R$ 0,00

#### Scenario: Produto com apenas entradas no período
- **WHEN** o produto "Detergente" teve entradas mas nenhuma venda no período
- **THEN** o sistema retorna a linha com quantidade vendida = 0 e faturamento = R$ 0,00

#### Scenario: Nenhum produto movimentado no período
- **WHEN** nenhum produto teve venda ou entrada no período informado
- **THEN** o sistema retorna uma tabela vazia (zero linhas)

### Requirement: Filtros obrigatórios e opcionais

O relatório SHALL aceitar os seguintes filtros:
- **startDate** e **endDate** (obrigatórios): delimitam o período de movimentação. Se omitidos, usar os últimos 30 dias como padrão.
- **categoriaId** (opcional): filtra produtos por departamento/categoria.
- **productSearch** (opcional): busca textual por código ou nome do produto.

#### Scenario: Filtro por período
- **WHEN** o operador informa startDate=2026-10-01 e endDate=2026-10-15
- **THEN** somente produtos com vendas ou entradas entre 01/10 e 15/10 (inclusive) aparecem no resultado

#### Scenario: Filtro por departamento
- **WHEN** o operador seleciona categoriaId="cat-bebidas"
- **THEN** somente produtos da categoria Bebidas (ou subcategorias) com movimentação no período aparecem

#### Scenario: Filtro por nome/código do produto
- **WHEN** o operador digita "arroz" no campo productSearch
- **THEN** somente produtos cujo código ou nome contenham "arroz" (case-insensitive) e que tenham movimentação no período aparecem

### Requirement: Restrição de acesso por role

O relatório SHALL ser acessível apenas para usuários com role `administrador` ou `gerente`. Usuários com role `atendente` ou `caixa` NÃO SHALL ter acesso a este relatório.

#### Scenario: Administrador gera o relatório
- **WHEN** um usuário com role administrador solicita o relatório ficha-fiscal-produtos
- **THEN** o sistema gera e retorna o relatório normalmente

#### Scenario: Atendente tenta gerar o relatório
- **WHEN** um usuário com role atendente solicita o relatório ficha-fiscal-produtos
- **THEN** o sistema retorna erro de permissão (403 ou mensagem de acesso negado)

### Requirement: Formato de saída compatível com o catálogo

O relatório SHALL retornar dados no formato padrão `{ columns, rows }` usado pelos demais relatórios do sistema, permitindo exibição pelo componente `ReportFiltersView` existente sem alterações.

Valores monetários SHALL ser formatados em pt-BR (R$ 1.234,56). Percentuais SHALL incluir o sufixo "%" quando aplicável.

#### Scenario: Formato de resposta
- **WHEN** o relatório é gerado com sucesso
- **THEN** a resposta contém `columns` (array de {key, label}) e `rows` (array de dicionários com os mesmos keys), compatível com a renderização padrão de tabelas do frontend
