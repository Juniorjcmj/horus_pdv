# cadastros/fornecedores Specification

## Purpose

Cadastro de fornecedores de cada empresa na API Java (listar, criar, editar e excluir), com as mesmas regras de
identificação, unicidade de CNPJ e proteção contra exclusão de fornecedores em uso da API .NET.

## Requirements

### Requirement: Acesso ao cadastro de fornecedores
As rotas de `api/Fornecedor` SHALL aceitar os perfis administrador, gerente e atendente e SHALL operar só sobre os
fornecedores da empresa do usuário logado.

#### Scenario: Caixa tenta listar
- **WHEN** um caixa chama `GET /api/Fornecedor`
- **THEN** a resposta é 403

#### Scenario: Fornecedor de outra empresa
- **WHEN** um gerente tenta editar o id de um fornecedor de outra empresa
- **THEN** a resposta é 404 com "Fornecedor não encontrado."

### Requirement: Listar fornecedores
`GET /api/Fornecedor` SHALL devolver os fornecedores da empresa, na mesma ordem e com os mesmos campos da API .NET, com
"Fornecedores obtidos com sucesso.".

#### Scenario: Lista da empresa
- **WHEN** um atendente lista os fornecedores
- **THEN** a resposta é 200 e traz só os fornecedores da empresa dele

### Requirement: Criar e editar fornecedor
`POST /api/Fornecedor` SHALL criar o fornecedor com id `fr-<milissegundos>` e responder 201 com "Fornecedor criado com
sucesso."; `PUT /api/Fornecedor/{id}` SHALL responder "Fornecedor atualizado com sucesso.". Razão social e nome
fantasia SHALL completar um ao outro quando só um vier preenchido.

#### Scenario: Só nome fantasia
- **WHEN** o fornecedor é criado só com nome fantasia "Distribuidora Sol"
- **THEN** a resposta é 201 e a razão social gravada também é "Distribuidora Sol"

#### Scenario: Sem nome nenhum
- **WHEN** razão social e nome fantasia vêm vazios
- **THEN** a resposta é 400 com "Informe a Razão Social ou Nome Fantasia do fornecedor."

### Requirement: CNPJ único por empresa
O CNPJ do fornecedor, quando informado, SHALL ser único entre os fornecedores da mesma empresa, na criação e na edição.

#### Scenario: CNPJ repetido
- **WHEN** já existe fornecedor na empresa com o mesmo CNPJ
- **THEN** a resposta é 400 com "Já existe fornecedor com este CNPJ."

#### Scenario: Mesmo CNPJ em outra empresa
- **WHEN** outra empresa já tem fornecedor com esse CNPJ
- **THEN** a criação é aceita

### Requirement: Exclusão de fornecedor em uso
`DELETE /api/Fornecedor/{id}` SHALL recusar a exclusão quando algum produto da empresa tiver como fornecedor o nome
fantasia ou a razão social desse fornecedor; sem produtos vinculados, SHALL excluir e responder "Fornecedor removido
com sucesso.".

#### Scenario: Fornecedor com produtos
- **WHEN** um produto da empresa tem o fornecedor "Distribuidora Sol"
- **THEN** a exclusão desse fornecedor responde 400 com "Fornecedor possui produtos vinculados e não pode ser excluído."

#### Scenario: Fornecedor sem produtos
- **WHEN** nenhum produto usa o fornecedor
- **THEN** a resposta é 200 com "Fornecedor removido com sucesso." e ele some da lista
