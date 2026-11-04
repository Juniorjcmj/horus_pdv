# compras/ordens-compra Specification

## Purpose

Ordens de compra de cada empresa na API Java: criar (com ou sem fornecedor, para cotação), editar enquanto pendente,
listar e consultar, receber total ou parcialmente com entrada no estoque, cancelar e sugerir reposição, com o mesmo
contrato da API .NET.

## Requirements

### Requirement: Acesso às ordens de compra
As rotas de `api/OrdemCompra` SHALL exigir administrador ou gerente e SHALL operar só sobre as ordens da empresa do
usuário logado, identificadas pelo número da ordem.

#### Scenario: Atendente sem acesso
- **WHEN** um atendente chama `GET /api/OrdemCompra`
- **THEN** a resposta é 403

#### Scenario: Ordem de outra empresa
- **WHEN** um gerente consulta o número de uma ordem que só existe em outra empresa
- **THEN** a resposta é 404 com "Ordem de compra não encontrada."

### Requirement: Criar ordem de compra
`POST /api/OrdemCompra` SHALL criar a ordem com o próximo número sequencial da empresa (maior número + 1), situação
pendente (0) e os itens, e responder "Ordem de compra criada com sucesso.". O fornecedor SHALL ser opcional: sem ele, a
ordem fica como "Fornecedor não informado", para cotação.

#### Scenario: Ordem sem itens
- **WHEN** a ordem é enviada sem itens
- **THEN** a resposta é 400 com "Ordem de compra sem itens."

#### Scenario: Quantidade zero
- **WHEN** um item tem quantidade 0
- **THEN** a resposta é 400 com "Quantidade do item <código> deve ser maior que zero."

#### Scenario: Ordem para cotação
- **WHEN** a ordem é criada sem fornecedor
- **THEN** a ordem é gravada com o fornecedor "Fornecedor não informado"

#### Scenario: Numeração por empresa
- **WHEN** a última ordem da empresa é a 41 e outra empresa já está na 900
- **THEN** a nova ordem da empresa recebe o número 42

### Requirement: Editar ordem pendente
`PUT /api/OrdemCompra/{orderNumber}` SHALL atualizar fornecedor, itens e dados da ordem e responder "Ordem de compra
atualizada com sucesso.". Ordem que não está pendente MUST NOT ser editada.

#### Scenario: Ordem já recebida
- **WHEN** a ordem já foi recebida
- **THEN** a resposta é 400 com "Apenas ordens com status Pendente podem ser editadas."

### Requirement: Listar e consultar ordens
`GET /api/OrdemCompra` SHALL listar as ordens da empresa com "Ordens de compra listadas."; `GET
/api/OrdemCompra/{orderNumber}` SHALL devolver a ordem com os itens e "Ordem de compra encontrada.".

#### Scenario: Consulta da ordem
- **WHEN** o gerente consulta uma ordem com 3 itens
- **THEN** a resposta traz a ordem, a situação numérica e os 3 itens com as quantidades pedidas e recebidas

### Requirement: Receber ordem
`POST /api/OrdemCompra/{orderNumber}/receber` SHALL dar entrada no estoque das quantidades recebidas por item, pelo
registrador único, registrando o lote quando vier validade, e mudar a situação para recebida (1) ou recebida
parcialmente (3). A resposta SHALL trazer a mensagem da situação e `data.status`.

#### Scenario: Recebimento parcial
- **WHEN** a ordem pede 10 de um item e o recebimento traz 4
- **THEN** a resposta é "Recebimento parcial registrado. Estoque atualizado para os itens recebidos.", `status` é 3 e
  o saldo do produto sobe 4

#### Scenario: Recebimento total
- **WHEN** o recebimento completa todos os itens
- **THEN** a resposta é "Ordem de compra recebida integralmente. Estoque atualizado." e `status` é 1

#### Scenario: Nada para receber
- **WHEN** o recebimento vem sem itens
- **THEN** a resposta é 400 com "Nenhum item para receber."

### Requirement: Cancelar ordem
`POST /api/OrdemCompra/{orderNumber}/cancelar` SHALL cancelar só ordem pendente, com motivo opcional, e responder
"Ordem de compra cancelada com sucesso.".

#### Scenario: Cancelar ordem recebida
- **WHEN** a ordem não está pendente
- **THEN** a resposta é 404 com "Ordem de compra não encontrada ou não está pendente."

### Requirement: Sugestões de reposição
`GET /api/OrdemCompra/sugestoes-reposicao` SHALL listar os produtos com estoque mínimo definido e saldo abaixo dele,
ordenados por fornecedor e nome, com "Sugestões de reposição.", usando a mesma comparação de saldo da API .NET.

#### Scenario: Produto abaixo do mínimo
- **WHEN** um produto tem estoque mínimo 10 e o comparador da API .NET o considera abaixo do mínimo
- **THEN** ele aparece nas sugestões, com o mesmo resultado nas duas APIs
