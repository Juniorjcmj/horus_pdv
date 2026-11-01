# Spec Delta

## Purpose

Telas de análise de estoque no aplicativo de estoque JavaFX — dashboard, curva ABC, reposição, detalhe do produto,
acuracidade e contagem cíclica, perdas e parados, fornecedores e configurações — com gráficos, filtros e exportação.

## ADDED Requirements

### Requirement: Navegação e filtros
O app SHALL ter o item de menu "Análise" com as 8 telas, todas com barra de filtros de período (com atalhos 7/30/90/
365 dias e comparação com o período anterior), categoria, fornecedor e depósito (o depósito só aparece se houver mais
de um), e SHALL lembrar no terminal os últimos filtros usados.

#### Scenario: Mudar filtro
- **WHEN** o gerente troca o período de 30 para 90 dias no dashboard
- **THEN** todos os cards e gráficos da tela são recarregados com o novo período, sem travar a janela

### Requirement: Cards do dashboard
O dashboard SHALL mostrar cards de valor em estoque, giro, cobertura média, acuracidade, taxa de ruptura, itens em
ruptura agora, itens em risco (cobertura menor que o lead time), valor parado e valor em excesso, cada um com variação
contra o período anterior e cor verde/amarela/vermelha pela meta configurada.

#### Scenario: Semáforo
- **WHEN** a meta de acuracidade é verde ≥ 98% e amarela ≥ 95% e a acuracidade do período é 96%
- **THEN** o card de acuracidade fica amarelo e mostra a variação contra o período anterior

### Requirement: Gráficos do dashboard
O dashboard SHALL mostrar valor e quantidade em estoque no tempo (linha), ruptura no tempo (linha), cobertura por
categoria (barras horizontais com a faixa da meta), top 10 maior giro e top 10 parados (barras) e valor em estoque por
classe ABC (rosca).

#### Scenario: Gráfico de cobertura por categoria
- **WHEN** a meta de cobertura é de 15 a 45 dias
- **THEN** cada categoria aparece como uma barra e a faixa de 15 a 45 dias fica sombreada atrás das barras

#### Scenario: Estado vazio
- **WHEN** a empresa ainda não tem fotografias diárias
- **THEN** os gráficos de histórico mostram "Ainda não há histórico: ele começa a ser registrado hoje" em vez de um gráfico vazio

### Requirement: Curva ABC
A tela ABC SHALL mostrar o gráfico de Pareto (barras de faturamento por produto e linha do % acumulado com marcações
em 80% e 95%), a tabela (código, produto, faturamento, % individual, % acumulado, classe, giro, cobertura) e a matriz
ABC × FSN clicável que filtra a tabela.

#### Scenario: Clicar na matriz
- **WHEN** o gerente clica na célula "A × Slow"
- **THEN** a tabela passa a mostrar só os produtos classe A com saída lenta e o filtro aparece com opção de limpar

### Requirement: Reposição e mapa de risco
A tela de reposição SHALL mostrar cards de itens em ruptura, abaixo do ponto de pedido e em excesso, e a tabela de
ação (produto, saldo, consumo médio, cobertura, lead time, segurança, ponto de pedido, sugestão em quantidade e R$,
status) ordenada por urgência com as rupturas destacadas, permitindo selecionar itens e gerar rascunhos de pedido de
compra por fornecedor e exportar CSV ou Excel.

#### Scenario: Gerar rascunhos
- **WHEN** o gerente seleciona 12 itens de 3 fornecedores e gera os pedidos
- **THEN** são criados 3 pedidos de compra em aberto com as quantidades sugeridas (editáveis antes de confirmar)

#### Scenario: Exportar Excel
- **WHEN** o gerente exporta a tabela em Excel
- **THEN** o app grava um arquivo .xlsx com as mesmas colunas e números em formato brasileiro

### Requirement: Detalhe do produto
O detalhe do produto SHALL mostrar os KPIs do item (saldo, cobertura, giro, classes ABC/FSN, ponto de pedido), o
saldo diário em linha com as linhas de mínimo, ponto de pedido e máximo, entradas × saídas por semana em barras, o
histograma da demanda diária e o extrato de movimentações paginado; abre por duplo clique em qualquer tabela da análise.

#### Scenario: Abrir pelo ranking
- **WHEN** o gerente dá duplo clique num produto do top 10 parados
- **THEN** abre o detalhe do produto com o período do filtro atual

### Requirement: Acuracidade e contagem cíclica
A tela de acuracidade SHALL gerar a lista de contagem do dia priorizando os itens cuja última contagem venceu pela
periodicidade da classe (A antes de B antes de C), permitir digitar ou bipar a quantidade contada em campos grandes,
aplicar o acerto como inventário (com senha do gerente e motivo "Contagem cíclica"), e mostrar a acuracidade por
contagem no tempo (linha com meta de 98%) e a tabela de divergências (sistema, contado, diferença em quantidade e R$).

#### Scenario: Lista do dia
- **WHEN** a contagem cíclica é gerada para hoje com capacidade de 40 itens
- **THEN** a lista traz primeiro os itens A sem contagem há mais de 30 dias, depois B (90) e C (180), até 40 itens

#### Scenario: Aplicar contagem
- **WHEN** a lista é concluída e o gerente confirma com a senha
- **THEN** um inventário é aplicado com os itens contados e a acuracidade daquela contagem entra no gráfico

### Requirement: Perdas e parados
A tela SHALL mostrar perdas por motivo e por mês em barras empilhadas (quantidade ou R$) e a tabela de itens parados
com dias sem venda, última entrada e valor imobilizado.

#### Scenario: Alternar quantidade e valor
- **WHEN** o gerente alterna o gráfico de perdas para R$
- **THEN** as barras passam a mostrar o valor pelo custo de cada perda

### Requirement: Fornecedores
A tela SHALL mostrar o lead time médio por fornecedor em barras (prometido × real) e a tabela de fornecedores com nº
de pedidos, lead time médio, desvio e % de entregas no prazo.

#### Scenario: Fornecedor sem pedidos recebidos
- **WHEN** um fornecedor não tem pedido recebido no período
- **THEN** ele aparece na tabela com "sem pedidos recebidos" e fica fora do gráfico

### Requirement: Configurações de análise no app
A tela de configurações SHALL permitir ao gerente/administrador editar todos os parâmetros de análise e as metas do
semáforo, com os valores padrão visíveis e botão para restaurar o padrão; o estoquista vê os valores sem editar.

#### Scenario: Salvar configuração
- **WHEN** o gerente muda os dias de "parado" de 90 para 60 e salva
- **THEN** a tela de perdas e parados e o dashboard passam a usar 60 dias na próxima consulta

### Requirement: Formatação, tooltips e acesso
Todos os gráficos SHALL ter dica ao passar o mouse com valores em pt-BR (R$ 1.234,56, milhar com ponto, decimal com
vírgula, datas dd/mm/aaaa), cards e gráficos SHALL se reorganizar em colunas conforme a largura da janela, e valores
em R$ SHALL ficar ocultos para o estoquista.

#### Scenario: Dica no gráfico
- **WHEN** o mouse passa sobre um ponto do gráfico de valor em estoque
- **THEN** aparece "15/10/2026 · R$ 48.230,10 · 3.412 un"

#### Scenario: Estoquista
- **WHEN** um estoquista abre o dashboard
- **THEN** os cards de valor aparecem sem R$ (só quantidades e dias) e a tela de configurações fica só para leitura
