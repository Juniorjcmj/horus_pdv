# estoque/lotes-fefo Specification

## Purpose

Lotes com data de validade na API Java: alertas e consulta por lote, lotes de um produto, registro manual de lote, o
modo da baixa por lote (FEFO) e os prazos padrão de validade por categoria, com o mesmo contrato da API .NET.

## Requirements

### Requirement: Saldo dos lotes por modo
O saldo exibido de cada lote SHALL seguir o modo da baixa por lote da empresa: no modo `ativo`, o saldo real dos
lotes; nos modos `sombra` e `desligado`, o saldo estimado, distribuindo o estoque do produto pelos lotes que vencem
por último. Empresa sem modo gravado SHALL valer `sombra`.

#### Scenario: Modo sombra
- **WHEN** um produto tem estoque 8 e dois lotes de 6, vencendo em 2026-11-10 e 2026-11-20, e a empresa está em `sombra`
- **THEN** a consulta mostra 2 no lote de 2026-11-10 e 6 no de 2026-11-20

#### Scenario: Modo ativo
- **WHEN** a empresa está em `ativo`
- **THEN** a consulta mostra o saldo real gravado em cada lote

### Requirement: Faixas de validade dos lotes
Cada lote SHALL ser classificado como `esgotado` (sem saldo), `vencido`, `critico`, `atencao` (dentro da janela de
alerta) ou `ok`, contando os dias a partir da data de hoje no fuso de Brasília. A janela SHALL ser a da categoria; se
não houver, a do produto; e se não houver nenhuma, a padrão do .NET.

#### Scenario: Lote vencido
- **WHEN** um lote com saldo venceu ontem
- **THEN** ele aparece com faixa `vencido` e dias para vencer negativo

### Requirement: Alertas e consulta de lotes
`GET /api/Produto/lotes/alertas`, `GET /api/Produto/lotes/consulta` e `GET /api/Produto/lotes/produto/{produtoId}`
SHALL devolver os lotes da empresa, com o resumo por faixa nos alertas e os filtros por período, faixa e produto na
consulta, com os mesmos campos, ordem e mensagens da API .NET.

#### Scenario: Período invertido
- **WHEN** a consulta pede validade final anterior à inicial
- **THEN** a resposta é 400 com "Período inválido: a validade final é anterior à inicial."

#### Scenario: Janela fora da faixa
- **WHEN** a janela de alerta pedida é 4000 dias
- **THEN** a resposta é 400 com "A janela de alerta deve ficar entre 0 e 3650 dias."

### Requirement: Registrar lote manualmente
`POST /api/Produto/lotes/registrar` SHALL aceitar administrador, gerente, atendente e estoquista, exigir produto,
quantidade maior que zero e validade válida, e responder "Lote registrado com sucesso.". O registro de lote MUST NOT
alterar o saldo do produto.

#### Scenario: Quantidade zero
- **WHEN** o lote é enviado com quantidade 0
- **THEN** a resposta é 400 com "A quantidade do lote deve ser maior que zero."

#### Scenario: Saldo intacto
- **WHEN** um lote de 10 é registrado para um produto com saldo 4
- **THEN** o saldo do produto continua 4

### Requirement: Modo da baixa por lote
`GET /api/Produto/lotes/fefo` SHALL devolver o modo e a situação da baixa por lote: lotes com saldo, divergências entre
o saldo real e o estimado, e produtos cujo estoque os lotes não cobrem. `PUT /api/Produto/lotes/fefo/modo` SHALL ser
permitido a administrador e gerente e aceitar só `desligado`, `sombra` ou `ativo`.

#### Scenario: Modo inválido
- **WHEN** um gerente envia o modo `ligado`
- **THEN** a resposta é 400 com "Modo inválido. Use 'desligado', 'sombra' ou 'ativo'."

#### Scenario: Atendente tenta mudar o modo
- **WHEN** um atendente chama `PUT /api/Produto/lotes/fefo/modo`
- **THEN** a resposta é 403

### Requirement: Consumo de lotes nas saídas
Com o modo diferente de `desligado`, as saídas que consomem lotes SHALL tirar a quantidade dos lotes que vencem
primeiro, registrar quanto saiu de cada lote e atualizar a validade do produto. Isso vale nesta fase para o ajuste
manual e, nas próximas, para as vendas. O consumo SHALL rodar depois da operação principal, e uma falha nele MUST NOT
desfazê-la.

#### Scenario: Perda consome o lote mais antigo
- **WHEN** a empresa está em `ativo` e registra perda de 3 de um produto com lotes de 2 (vence antes) e de 5
- **THEN** o primeiro lote zera, o segundo fica com 4 e a validade do produto passa a ser a do segundo

#### Scenario: Modo desligado
- **WHEN** a empresa está em `desligado` e registra uma perda
- **THEN** nenhum lote muda

### Requirement: Prazo de validade por categoria
`GET /api/Produto/lotes/categorias` SHALL listar as categorias com o prazo padrão de validade. `PUT
/api/Produto/lotes/categorias/{categoriaId}` SHALL ser permitido a administrador e gerente, aceitar prazo de 1 a 3650
dias e responder "Prazo de validade da categoria atualizado.".

#### Scenario: Prazo fora da faixa
- **WHEN** o prazo enviado é 0
- **THEN** a resposta é 400 com "O prazo padrão deve ficar entre 1 e 3650 dias."

#### Scenario: Categoria de outra empresa
- **WHEN** o id é de uma categoria de outra empresa
- **THEN** a resposta é 404 com "Categoria não encontrada."
