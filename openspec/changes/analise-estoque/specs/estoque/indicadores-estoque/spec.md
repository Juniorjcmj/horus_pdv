# Spec Delta

## Purpose

Indicadores de controle de estoque calculados no servidor a partir das movimentações e das fotografias diárias —
consumo, giro, cobertura, segurança, ponto de pedido, sugestão de compra, ruptura, acuracidade, parados, excesso,
perdas, ABC/FSN e lead time — com parâmetros configuráveis por empresa.

## ADDED Requirements

### Requirement: Filtros comuns
Todo indicador SHALL aceitar período (padrão últimos 30 dias), categoria (com subcategorias), fornecedor e depósito,
e, quando pedido, devolver o mesmo indicador do período anterior de mesma duração para comparação.

#### Scenario: Comparação com período anterior
- **WHEN** o valor em estoque de 01/10 a 31/10 é pedido com comparação
- **THEN** a resposta traz o valor do período e o de 01/09 a 30/09 com a variação percentual

### Requirement: Consumo e variabilidade da demanda
O consumo médio diário SHALL ser a soma das saídas por venda (vendas menos devoluções) nos últimos N dias (N da
configuração, padrão 30) dividida por N, e o desvio-padrão SHALL ser calculado sobre as N demandas diárias, contando
como zero os dias sem venda.

#### Scenario: Consumo com dias sem venda
- **WHEN** um produto vendeu 30 unidades em 10 dos últimos 30 dias
- **THEN** o consumo médio é 1,0 por dia e o desvio considera os 20 dias com demanda zero

#### Scenario: Produto sem histórico
- **WHEN** um produto não tem nenhuma venda na janela
- **THEN** o consumo é 0, o desvio é 0 e os indicadores que dividem pelo consumo mostram "sem giro"

### Requirement: Estoque médio, giro, cobertura e DIO
O estoque médio SHALL ser a média do valor das fotografias diárias do período; o giro SHALL ser o CMV do período
(saídas por venda × custo da movimentação) dividido pelo estoque médio em valor; a cobertura SHALL ser saldo ÷
consumo médio diário; o DIO SHALL ser 365 ÷ giro anualizado.

#### Scenario: Giro anual
- **WHEN** o CMV de 30 dias é R$ 3.000,00 e o estoque médio em valor é R$ 6.000,00
- **THEN** o giro do período é 0,5, o giro anualizado ≈ 6,08 e o DIO ≈ 60 dias

#### Scenario: Cobertura sem consumo
- **WHEN** um produto tem saldo 20 e consumo 0
- **THEN** a cobertura é "sem giro" (não é número nem infinito) e o produto não entra na média de cobertura

#### Scenario: Sem fotografias no período
- **WHEN** não há nenhuma fotografia diária no período
- **THEN** o estoque médio e o giro vêm vazios com o motivo "sem histórico"

#### Scenario: Saldo negativo
- **WHEN** o saldo de um produto está negativo
- **THEN** o valor em estoque considera 0 para esse item e o item aparece em ruptura

### Requirement: Estoque de segurança, ponto de pedido e sugestão de compra
O estoque de segurança SHALL ser Z × desvio-padrão × √lead time (Z da configuração, padrão 1,65); o ponto de pedido
SHALL ser consumo × lead time + segurança; a sugestão SHALL ser max(0, alvo − saldo − pedidos em trânsito), com alvo =
consumo × (lead time + cobertura alvo) + segurança. Lead time: do produto, senão o real do fornecedor, senão o
prometido do fornecedor, senão 7 dias.

#### Scenario: Cálculo completo
- **WHEN** consumo 10/dia, desvio 4, lead time 9 dias, Z 1,65, cobertura alvo 15, saldo 80 e 50 em trânsito
- **THEN** segurança = 19,8, ponto de pedido = 109,8, alvo = 259,8 e sugestão = 129,8 (arredondada para cima na unidade do produto)

#### Scenario: Pedido em trânsito cobre a necessidade
- **WHEN** saldo mais pedidos abertos já superam o alvo
- **THEN** a sugestão é 0

### Requirement: Ruptura
A taxa de ruptura SHALL ser dias com saldo ≤ 0 nas fotografias ÷ dias do período, por produto e consolidada (média
dos produtos ativos), e o indicador "em ruptura agora" SHALL ser o percentual de produtos ativos com saldo ≤ 0.

#### Scenario: Ruptura do produto
- **WHEN** num período de 30 dias o produto ficou 6 dias com saldo zero
- **THEN** a taxa de ruptura dele é 20%

### Requirement: Acuracidade de inventário
A acuracidade SHALL ser Σ min(contado, sistema) ÷ Σ sistema × 100 sobre as contagens aplicadas no período, junto com
o percentual de itens com contagem exata.

#### Scenario: Acuracidade de uma contagem
- **WHEN** foram contados 3 itens: sistema 10/contado 10, sistema 20/contado 18, sistema 5/contado 7
- **THEN** a acuracidade é (10 + 18 + 5) ÷ 35 = 94,29% e 33,3% dos itens bateram

### Requirement: Valor, parado, excesso e perdas
A API SHALL calcular valor em estoque (Σ saldo × custo), itens parados (sem saída de venda há X dias, padrão 90, com
valor), excesso (cobertura acima de Y dias, padrão 120, com valor do excedente acima de Y dias de consumo) e perdas
(movimentações `perda` e ajustes negativos, em quantidade, valor e % do CMV), por motivo e por mês.

#### Scenario: Valor do excesso
- **WHEN** um produto tem saldo 500, consumo 2/dia, custo R$ 3,00 e o limite de excesso é 120 dias
- **THEN** a cobertura é 250 dias e o valor em excesso é (500 − 240) × 3 = R$ 780,00

### Requirement: Curva ABC e classificação FSN
A API SHALL classificar os produtos em A/B/C pelo faturamento (ou CMV, configurável) acumulado com limites da
configuração (padrão 80% e 95%), e em F/S/N pela frequência de saída: N sem saída no período, F com saída em pelo
menos 50% das semanas do período, S nos demais (limite configurável), recalculando no job diário.

#### Scenario: Matriz ABC × FSN
- **WHEN** a matriz é pedida para os últimos 90 dias
- **THEN** a resposta traz as 9 combinações com quantidade de produtos e valor em estoque de cada uma

### Requirement: Lead time real por fornecedor
O lead time real SHALL ser a média, em dias, entre a criação e o recebimento dos pedidos de compra recebidos de cada
fornecedor, com desvio-padrão, número de pedidos e % recebidos até a previsão de entrega.

#### Scenario: Pedido sem previsão
- **WHEN** um pedido recebido não tem previsão de entrega
- **THEN** ele entra na média do lead time, mas não no cálculo de "no prazo"

### Requirement: Configurações de análise
A API SHALL guardar por empresa a janela de consumo, Z, limites ABC, limite FSN, dias de parado, dias de excesso,
cobertura alvo, metas verde/amarelo de cada KPI e periodicidade de contagem por classe (padrão A 30, B 90, C 180 dias),
editáveis só por gerente/administrador, com valores padrão quando não configurado.

#### Scenario: Sem configuração
- **WHEN** a empresa nunca salvou configurações
- **THEN** os indicadores usam os padrões (30 dias, Z 1,65, ABC 80/95, parado 90, excesso 120, cobertura alvo 15)

#### Scenario: Estoquista
- **WHEN** um estoquista tenta salvar as configurações
- **THEN** a API responde 403

### Requirement: Entrega por bloco e permissões
Cada bloco de tela SHALL ter seu endpoint, com cache de até 5 minutos por empresa e filtros; números com valor (R$,
CMV, faturamento) SHALL ser restritos a gerente/administrador, e o estoquista recebe só quantidades e dias.

#### Scenario: Cache
- **WHEN** o mesmo bloco com os mesmos filtros é pedido duas vezes em 1 minuto
- **THEN** a segunda resposta vem do cache e informa o horário do cálculo
