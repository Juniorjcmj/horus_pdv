# Spec Delta

## Purpose

Relatórios que orientam a compra de mercadorias — o que repor, o que mais vende, o que está parado e o que está
vencendo ou se perdendo — e a geração do pedido de compra a partir da sugestão.

## ADDED Requirements

### Requirement: Sugestão de reposição por giro
A API SHALL calcular, por produto ativo, a venda média diária num período (padrão 30 dias, configurável), a
cobertura do estoque atual em dias e a quantidade sugerida para cobrir N dias de venda (padrão 15) respeitando o
estoque mínimo e máximo do cadastro, agrupando por fornecedor com custo estimado. Produtos sem venda e acima do
mínimo não entram.

#### Scenario: Produto com giro
- **WHEN** um produto vendeu 60 unidades nos últimos 30 dias (2/dia), tem 6 em estoque, mínimo 10 e máximo 40, e a cobertura desejada é 15 dias
- **THEN** a sugestão é 24 unidades (2 × 15 = 30, menos 6), limitada ao máximo de 40, com cobertura atual de 3 dias

#### Scenario: Abaixo do mínimo sem venda
- **WHEN** um produto não vendeu no período e está com 2 unidades e mínimo 10
- **THEN** a sugestão é completar até o mínimo (8 unidades) e o motivo mostrado é "abaixo do mínimo"

#### Scenario: Sem fornecedor
- **WHEN** um produto sugerido não tem fornecedor no cadastro
- **THEN** ele aparece no grupo "Sem fornecedor" e só pode ir para pedido depois de escolher um fornecedor

### Requirement: Pedido de compra a partir da sugestão
O aplicativo SHALL permitir ao gerente revisar a sugestão (alterar quantidade, custo e excluir itens) e gerar um
pedido de compra por fornecedor usando as rotas de pedido de compra existentes, com impressão do pedido.

#### Scenario: Gerar pedidos
- **WHEN** o gerente confirma a sugestão com itens de 3 fornecedores
- **THEN** são criados 3 pedidos de compra em aberto, um por fornecedor, com os itens e quantidades revisados

#### Scenario: Estoquista
- **WHEN** um estoquista abre a sugestão de reposição
- **THEN** ele vê produtos e quantidades sugeridas, mas não vê custos nem pode gerar pedido

### Requirement: Curva ABC
A API SHALL classificar os produtos vendidos num período em A, B e C pelo faturamento acumulado (padrão: A até 80%,
B até 95%, C o restante), informando quantidade vendida, faturamento, participação, participação acumulada,
estoque atual e cobertura em dias.

#### Scenario: Classificação
- **WHEN** a curva ABC dos últimos 90 dias é pedida
- **THEN** os produtos vêm ordenados do maior para o menor faturamento, com a classe de cada um e os totais por classe

#### Scenario: Filtro por categoria
- **WHEN** a curva é pedida só para o departamento "Mercearia"
- **THEN** os percentuais são calculados sobre o faturamento desse departamento

### Requirement: Estoque crítico e parado
A API SHALL listar produtos zerados ou abaixo do mínimo (crítico) e produtos com estoque e sem venda há X dias
(parado, padrão 60), com última venda, última entrada, quantidade e valor do estoque parado pelo custo.

#### Scenario: Produto parado
- **WHEN** um produto tem 20 unidades, custo R$ 5,00 e a última venda foi há 75 dias
- **THEN** ele aparece em "parado" com 75 dias sem venda e R$ 100,00 de estoque parado

#### Scenario: Produto nunca vendido
- **WHEN** um produto com estoque nunca foi vendido
- **THEN** ele aparece em "parado" com "nunca vendido" e a data da última entrada

### Requirement: Validade e perdas
A API SHALL listar lotes vencidos e a vencer em X dias (padrão 30) com quantidade e valor pelo custo, e as perdas
registradas num período por produto e motivo, com quantidade e valor, e total geral.

#### Scenario: Perdas do mês
- **WHEN** o relatório de perdas do mês é pedido
- **THEN** a API devolve as movimentações "perda" do período agrupadas por produto, com motivo, usuário, quantidade e valor pelo custo

#### Scenario: Lotes a vencer
- **WHEN** existem lotes com validade nos próximos 30 dias
- **THEN** eles aparecem ordenados pela data de validade com dias restantes, quantidade e valor

### Requirement: Restrição de valores e exportação
Relatórios com custo, faturamento ou valor SHALL ser restritos a gerente e administrador; todos os relatórios do
aplicativo SHALL poder ser exportados em CSV e impressos.

#### Scenario: Exportar CSV
- **WHEN** o gerente exporta a curva ABC
- **THEN** o aplicativo grava um CSV (separador ponto e vírgula, valores em formato brasileiro) com as colunas da tela

#### Scenario: Estoquista e valores
- **WHEN** um estoquista chama a rota da curva ABC
- **THEN** a API responde 403
