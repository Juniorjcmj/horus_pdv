# estoque/movimentacao Specification

## Purpose

Saldo de estoque dos produtos na API Java: um registrador único que altera o saldo e grava a movimentação juntos, o
ajuste manual de estoque e a consulta das movimentações, com o mesmo contrato da API .NET.

## Requirements

### Requirement: Registrador único de saldo
Toda alteração do saldo de um produto (cadastro, ajuste, entradas, inventário, recebimento de compra e, nas próximas
fases, vendas e devoluções) SHALL passar por um único registrador, que soma a variação ao saldo e grava a movimentação
na mesma transação. Saldo anterior e novo SHALL ser os do próprio comando de atualização.

#### Scenario: Duas saídas ao mesmo tempo
- **WHEN** duas operações tiram 1 unidade de um produto com saldo 10 ao mesmo tempo
- **THEN** o saldo final é 8 e as movimentações registram 10 → 9 e 9 → 8, sem repetir o saldo anterior

#### Scenario: Variação zero
- **WHEN** uma operação aplica variação zero
- **THEN** o saldo não muda e nenhuma movimentação é gravada

#### Scenario: Falha depois do saldo
- **WHEN** a operação que mudou o saldo falha antes de terminar
- **THEN** nem o saldo nem a movimentação ficam gravados

### Requirement: Dados da movimentação
Cada movimentação SHALL guardar:
- o tipo (`entrada`, `saida`, `saida_venda`, `devolucao`, `uso_interno`, `perda`, `inventario` ou `transferencia`);
- a quantidade sem sinal e os saldos anterior e novo;
- o motivo, o usuário (`sistema` quando não houver), o custo unitário (o do produto quando não informado);
- a origem (tipo e id do documento) e o depósito `loja`.

#### Scenario: Ajuste com motivo
- **WHEN** um usuário registra uma perda de 2 unidades com motivo "Embalagem rasgada"
- **THEN** a movimentação tem tipo `perda`, quantidade 2, o motivo, o nome do usuário e a origem `ajuste`

### Requirement: Ajuste manual de estoque
`POST /api/Produto/{id}/ajuste-estoque` SHALL aceitar administrador, gerente, atendente e estoquista e os tipos
`entrada`, `saida`, `uso_interno` e `perda`. SHALL exigir quantidade maior que zero e motivo, e recusar saída maior
que o saldo. Em sucesso, SHALL responder "<Tipo> de <quantidade> registrada com sucesso.".

#### Scenario: Entrada registrada
- **WHEN** um estoquista registra entrada de 5 com motivo
- **THEN** a resposta é 200 com "Entrada de 5 registrada com sucesso." e o saldo sobe 5

#### Scenario: Sem motivo
- **WHEN** o ajuste vem sem motivo
- **THEN** a resposta é 400 com "O motivo é obrigatório para toda movimentação de estoque."

#### Scenario: Tipo inválido
- **WHEN** o tipo enviado é `roubo`
- **THEN** a resposta é 400 com "Tipo deve ser 'entrada', 'saida', 'uso_interno' ou 'perda'."

#### Scenario: Saída maior que o saldo
- **WHEN** o saldo é 3 e o ajuste pede saída de 5
- **THEN** a resposta é 400 com "Estoque insuficiente. Disponível: …, solicitado: …." no mesmo formato numérico do .NET
  e o saldo não muda

### Requirement: Lote no ajuste
Uma entrada por ajuste com validade SHALL registrar o lote (validade e número informados). Uma saída por ajuste SHALL
consumir lotes conforme o modo da baixa por lote da empresa. Falha no lote MUST NOT desfazer o ajuste do saldo, como
na API .NET.

#### Scenario: Entrada com validade
- **WHEN** a entrada de 6 vem com validade 2026-12-10 e lote "L-77"
- **THEN** o produto ganha um lote de 6 unidades com essa validade e esse número

### Requirement: Consultar movimentações
`GET /api/Produto/{id}/movimentacoes` SHALL listar as movimentações do produto, das mais novas para as mais antigas.
`GET /api/Produto/movimentacoes` SHALL listar as da empresa, com filtro opcional por `tipo` e no máximo `limit` (padrão
200). As duas respondem "Movimentações obtidas com sucesso." e aceitam os perfis do ajuste.

#### Scenario: Filtro por tipo
- **WHEN** a consulta é `GET /api/Produto/movimentacoes?tipo=perda&limit=10`
- **THEN** vêm no máximo 10 movimentações, todas do tipo `perda`, com nome e código do produto
