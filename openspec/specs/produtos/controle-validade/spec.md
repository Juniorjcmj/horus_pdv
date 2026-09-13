## Purpose

Permite controlar a data de validade dos produtos, alertando o operador sobre itens vencidos ou próximos do vencimento no PDV, no dashboard e em relatórios dedicados, reduzindo perdas e evitando multas da Vigilância Sanitária.

## Requirements

### Requirement: Campos de controle de validade no produto
O sistema DEVE permitir configurar controle de validade por produto com data, flag de controle e dias de antecedência para alerta.

#### Scenario: Ativação do controle de validade
- **WHEN** o operador edita um produto perecível e marca "Controlar validade", informa data 15/10/2026 e 15 dias para alerta
- **THEN** o sistema persiste `ControlaValidade = 1`, `DataValidade = 2026-10-15` e `DiasAlertaValidade = 15`.

#### Scenario: Produto sem controle de validade
- **WHEN** o operador cadastra um produto de limpeza sem marcar "Controlar validade"
- **THEN** o sistema persiste `ControlaValidade = 0` e ignora o produto em consultas de vencimento.

#### Scenario: Atualização de validade na entrada de mercadoria
- **WHEN** o operador importa NF-e de compra e informa a data de validade dos itens
- **THEN** o sistema atualiza `DataValidade` com a menor data informada para cada produto.

### Requirement: Consulta de vencimentos com KPIs
O sistema DEVE oferecer endpoints para listar produtos por faixa de vencimento e retornar resumo quantitativo.

#### Scenario: Resumo de vencimentos
- **WHEN** o gerente consulta `GET /api/Produto/vencimentos/resumo`
- **THEN** o sistema retorna contagem de produtos por faixa: vencidos, vence em 7 dias, vence em 15 dias, vence em 30 dias, total controlados e sem data informada.

#### Scenario: Lista de produtos por faixa
- **WHEN** o gerente consulta `GET /api/Produto/vencimentos?dias=7`
- **THEN** o sistema retorna lista de produtos com `ControlaValidade = 1` e `DataValidade` nos próximos 7 dias ou já vencidos, ordenados por data (mais urgentes primeiro).

### Requirement: Widget de alertas no Dashboard
O sistema DEVE exibir widget de alertas de validade na página inicial com contagem por faixa e indicadores semafóricos.

#### Scenario: Dashboard com produtos vencidos
- **WHEN** o gerente acessa a página inicial e existem 3 produtos vencidos, 8 vencem em 7 dias e 14 vencem em 15 dias
- **THEN** o dashboard exibe widget com indicadores vermelho (3 vencidos), amarelo (8 em 7d) e verde (14 em 15d) com link para detalhes.

#### Scenario: Dashboard sem alertas
- **WHEN** nenhum produto está próximo do vencimento
- **THEN** o widget exibe "Nenhum alerta de validade" com indicador verde.

### Requirement: Alerta no PDV ao vender produto vencido
O sistema DEVE alertar o operador ao adicionar produto vencido ao carrinho, com opção configurável de bloqueio.

#### Scenario: Bloqueio de venda de produto vencido (modo bloqueio ativo)
- **WHEN** o operador adiciona ao carrinho um produto com `DataValidade` anterior à data atual e o modo bloqueio está ativo
- **THEN** o sistema impede a adição e exibe alerta vermelho "Produto VENCIDO em DD/MM/AAAA. Venda bloqueada."

#### Scenario: Alerta sem bloqueio de produto vencido
- **WHEN** o operador adiciona ao carrinho um produto vencido e o modo bloqueio está desativo
- **THEN** o sistema exibe alerta visual amarelo "Produto VENCIDO em DD/MM/AAAA" mas permite a adição ao carrinho.

#### Scenario: Alerta discreto de produto próximo do vencimento
- **WHEN** o operador adiciona ao carrinho um produto com `DataValidade` dentro do período de `DiasAlertaValidade`
- **THEN** o sistema exibe badge amarelo discreto no item do carrinho indicando "Vence em X dias", sem bloquear.

### Requirement: Relatório de vencimentos
O sistema DEVE oferecer relatório dedicado de vencimentos com filtros por faixa temporal.

#### Scenario: Relatório completo de vencimentos
- **WHEN** o gerente gera o relatório `vencimentos` com filtro "vence em 15 dias"
- **THEN** o sistema exibe tabela com: código, produto, categoria, data de validade, dias restantes, quantidade em estoque e custo total em estoque, ordenado por dias restantes (urgentes primeiro).

#### Scenario: Relatório de vencidos
- **WHEN** o gerente gera o relatório `vencimentos` com filtro "vencidos"
- **THEN** o sistema lista apenas produtos com `DataValidade` anterior à data atual, evidenciando o prejuízo potencial (custo total em estoque).
