# Spec Delta

## Purpose

Garantir que o saldo de estoque só mude por movimentações registradas (com tipo, quantidade, custo, origem, autor e
horário) e manter uma fotografia diária do estoque, base confiável para histórico e indicadores.

## ADDED Requirements

### Requirement: Saldo só muda com movimentação
A API SHALL registrar uma movimentação na mesma transação de toda operação que altera o saldo de um produto, com
saldo anterior e novo; se a movimentação não puder ser gravada, a operação inteira é desfeita.

#### Scenario: Venda registra saída
- **WHEN** uma venda de 3 unidades de um produto com saldo 10 é gravada
- **THEN** existe uma movimentação `saida_venda` de 3 unidades com saldo anterior 10, novo 7 e o número da venda como origem

#### Scenario: Falha ao gravar a movimentação
- **WHEN** a gravação da movimentação falha durante uma venda
- **THEN** a venda não é gravada e o saldo do produto continua o mesmo

### Requirement: Tipos e origens de movimentação
Cada movimentação SHALL ter um tipo entre `entrada`, `saida`, `saida_venda`, `devolucao`, `uso_interno`, `perda`,
`inventario` e `transferencia`, e a origem (venda, NF-e, nota de entrada, pedido de compra, inventário, ajuste ou
cadastro) com o identificador do documento.

#### Scenario: Cancelamento de venda
- **WHEN** uma venda é cancelada (sem nota ou pela NFC-e)
- **THEN** cada item gera uma movimentação `devolucao` com a venda como origem e o saldo volta

#### Scenario: Entrada por NF-e
- **WHEN** uma NF-e de compra é confirmada
- **THEN** cada item gera uma movimentação `entrada` com a quantidade convertida, o custo unitário da nota e a chave/número da nota como origem

#### Scenario: Estorno de nota de entrada
- **WHEN** uma nota de entrada é estornada
- **THEN** cada item gera uma movimentação `saida` com a nota como origem e o saldo diminui

#### Scenario: Correção de venda
- **WHEN** uma venda é corrigida (itens removidos e nova venda gerada)
- **THEN** a devolução da venda original e a saída da venda nova ficam registradas como movimentações com as duas vendas como origem

#### Scenario: Edição de produto com quantidade diferente
- **WHEN** o cadastro de um produto é salvo com quantidade diferente da atual
- **THEN** a diferença é registrada como movimentação `entrada` ou `saida` com origem "cadastro" e o usuário que salvou

### Requirement: Custo na movimentação
Cada movimentação SHALL guardar o custo unitário vigente no momento (custo da nota nas entradas, custo do produto
nas demais), para que o valor das saídas e perdas seja calculado sem depender do custo atual.

#### Scenario: Custo muda depois
- **WHEN** um produto com custo R$ 4,00 tem uma perda registrada e depois o custo passa a R$ 5,00
- **THEN** o valor daquela perda continua calculado a R$ 4,00

### Requirement: Fotografia diária do estoque
A API SHALL gravar uma vez por dia (após a meia-noite no horário de Brasília) e sob demanda a quantidade e o valor
pelo custo de cada produto ativo, sem duplicar o mesmo dia; dias sem execução não são inventados.

#### Scenario: Execução diária
- **WHEN** o job roda em 01/11
- **THEN** existe uma linha por produto ativo da empresa com data 31/10, quantidade e valor ao fim daquele dia

#### Scenario: Execução repetida
- **WHEN** o job é executado duas vezes para o mesmo dia
- **THEN** a fotografia daquele dia é atualizada, não duplicada

#### Scenario: Reconstrução do dia
- **WHEN** a fotografia de um dia passado é pedida e há movimentações registradas desde então
- **THEN** a quantidade daquele dia é reconstruída pelo saldo atual menos as movimentações posteriores

### Requirement: Depósito implícito
Movimentações e fotografias SHALL referir-se ao depósito único "Loja" da empresa enquanto não houver cadastro de
depósitos, e o identificador de depósito SHALL existir nos dados para uso futuro.

#### Scenario: Consulta por depósito
- **WHEN** um indicador é pedido com o depósito "Loja"
- **THEN** o resultado é igual ao da empresa inteira
