## Purpose

Permite organizar produtos em categorias hierárquicas de 2 níveis (Departamento → Subcategoria), habilitando navegação rápida no PDV, filtros por setor em relatórios e base para promoções por departamento.

## ADDED Requirements

### Requirement: CRUD de categorias hierárquicas
O sistema DEVE permitir criar, editar, ativar/desativar e excluir categorias organizadas em até 2 níveis de hierarquia.

#### Scenario: Criação de departamento raiz
- **WHEN** o administrador cria uma categoria "Bebidas" sem categoria pai
- **THEN** o sistema persiste a categoria como departamento raiz com ordem configurável e a lista em `GET /api/Categoria` como nó de primeiro nível.

#### Scenario: Criação de subcategoria
- **WHEN** o administrador cria "Refrigerantes" vinculada ao departamento "Bebidas"
- **THEN** o sistema persiste a subcategoria com `CategoriaPaiId` apontando para "Bebidas" e a retorna como filho na árvore hierárquica.

#### Scenario: Exclusão bloqueada por produtos vinculados
- **WHEN** o administrador tenta excluir a categoria "Bebidas" que possui 45 produtos vinculados
- **THEN** o sistema bloqueia a exclusão e retorna mensagem informando a quantidade de produtos vinculados.

#### Scenario: Exclusão de categoria sem produtos
- **WHEN** o administrador exclui a categoria "Tabacaria" que não possui produtos vinculados
- **THEN** o sistema remove a categoria e suas subcategorias órfãs sem produtos.

### Requirement: Vinculação de produto a categoria
O sistema DEVE permitir vincular cada produto a uma categoria (departamento ou subcategoria).

#### Scenario: Atribuição de categoria no cadastro de produto
- **WHEN** o operador cadastra ou edita um produto e seleciona departamento "Bebidas" e subcategoria "Refrigerantes"
- **THEN** o sistema persiste `CategoriaId` do produto apontando para "Refrigerantes" e o produto aparece nos filtros por categoria.

#### Scenario: Produto sem categoria
- **WHEN** um produto não tem categoria atribuída
- **THEN** o sistema o classifica como "Sem categoria" nos relatórios e filtros, sem bloquear a venda.

### Requirement: Navegação por categoria no PDV
O sistema DEVE oferecer grid de atalhos por departamento no PDV para busca rápida de produtos.

#### Scenario: Busca por categoria no caixa
- **WHEN** o operador clica no botão "Bebidas" no grid de categorias do PDV
- **THEN** o sistema exibe lista filtrada de produtos da categoria "Bebidas" (incluindo subcategorias) com nome, preço e botão de adicionar ao carrinho.

#### Scenario: Retorno à busca geral
- **WHEN** o operador clica em "Todos" no grid de categorias
- **THEN** o sistema retorna à busca geral por nome/código de barras.

### Requirement: Filtro por categoria nos relatórios
O sistema DEVE permitir filtrar relatórios existentes por categoria e oferecer relatório de margem por departamento.

#### Scenario: Relatório de vendas filtrado por categoria
- **WHEN** o gerente gera o relatório `vendas-periodo` com filtro `categoriaId = "Bebidas"`
- **THEN** o sistema retorna apenas vendas de produtos vinculados à categoria "Bebidas" e suas subcategorias.

#### Scenario: Relatório de margem por categoria
- **WHEN** o gerente gera o relatório `margem-por-categoria`
- **THEN** o sistema exibe para cada departamento: receita total, CMV (custo da mercadoria vendida), margem em R$ e margem percentual, ordenado por receita.

### Requirement: Seed de categorias padrão
O sistema DEVE pré-cadastrar categorias padrão de mercadinho na primeira execução.

#### Scenario: Inicialização com categorias padrão
- **WHEN** a migration é executada em banco sem categorias
- **THEN** o sistema insere 14 departamentos (Mercearia, Bebidas, Laticínios, Padaria, Açougue, Hortifruti, Frios, Limpeza, Higiene, Congelados, Bomboniere, Bazar, Pet, Tabacaria) e subcategorias essenciais para cada um.

### Requirement: Árvore com contagem de produtos
O endpoint `GET /api/Categoria` DEVE retornar a árvore hierárquica com a contagem de produtos por categoria.

#### Scenario: Contagem de produtos na árvore
- **WHEN** o frontend solicita a lista de categorias
- **THEN** cada nó da árvore inclui `quantidadeProdutos` contando os produtos diretos daquela categoria.
