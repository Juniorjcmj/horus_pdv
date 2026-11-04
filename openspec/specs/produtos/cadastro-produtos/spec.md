# produtos/cadastro-produtos Specification

## Purpose

Cadastro de produtos de cada empresa na API Java (listar, criar, editar, excluir, preço de venda e validade), com as
mesmas regras de unicidade, de estoque relativo na edição e de controle de versão do preço da API .NET.

## Requirements

### Requirement: Acesso ao cadastro de produtos
Listar produtos e consultar vencimentos SHALL ser permitido a qualquer usuário autenticado; criar, editar, excluir e
mudar o preço SHALL exigir administrador, gerente ou atendente; mudar a validade SHALL aceitar também o estoquista.
Toda operação SHALL ficar restrita aos produtos da empresa do usuário logado.

#### Scenario: Caixa lista produtos
- **WHEN** um caixa chama `GET /api/Produto`
- **THEN** a resposta é 200 com "Produtos obtidos com sucesso." e só os produtos da empresa dele

#### Scenario: Estoquista tenta criar produto
- **WHEN** um estoquista chama `POST /api/Produto`
- **THEN** a resposta é 403

#### Scenario: Produto de outra empresa
- **WHEN** um gerente edita o id de um produto de outra empresa
- **THEN** a resposta é 404 com "Produto não encontrado."

### Requirement: Criar e editar produto
`POST /api/Produto` SHALL criar o produto com id `pr-<milissegundos>` e responder 201 com "Produto criado com
sucesso."; `PUT /api/Produto/{id}` SHALL responder "Produto atualizado com sucesso.". A resposta SHALL trazer o
produto relido do banco, com o nome da categoria e o saldo real. Os textos de validação SHALL ser os do .NET.

#### Scenario: Sem descrição
- **WHEN** o produto é enviado sem descrição
- **THEN** a resposta é 400 com "Descrição do produto é obrigatória."

#### Scenario: Custo zero
- **WHEN** o custo enviado é zero
- **THEN** a resposta é 400 com "Preco de custo deve ser maior que zero."

#### Scenario: Código vazio
- **WHEN** um produto novo é criado sem código
- **THEN** o produto recebe um código gerado `P-<identificador>`

### Requirement: Unicidade e fornecedor do produto
Na mesma empresa, o código e o GTIN do produto SHALL ser únicos (sem diferenciar maiúsculas), e o fornecedor
informado SHALL estar cadastrado.

#### Scenario: Código repetido
- **WHEN** outro produto da empresa já tem o mesmo código
- **THEN** a resposta é 400 com "Já existe produto com este código."

#### Scenario: GTIN repetido
- **WHEN** outro produto da empresa já tem o mesmo GTIN
- **THEN** a resposta é 400 com "Já existe produto com este GTIN (código de barras)."

#### Scenario: Fornecedor desconhecido
- **WHEN** o produto informa um fornecedor que não está cadastrado na empresa
- **THEN** a resposta é 400 com "Fornecedor informado não está cadastrado."

### Requirement: Estoque relativo na edição
Quando a edição traz o estoque consultado (`estoqueConsultado`), o novo saldo SHALL ser o saldo atual do banco somado à
diferença digitada (quantidade enviada − estoque consultado), para não desfazer vendas feitas depois da consulta.
Qualquer mudança de saldo SHALL passar pelo registrador único de movimentação.

#### Scenario: Venda entre a consulta e a gravação
- **WHEN** a tela consultou o produto com saldo 10, uma venda baixou o saldo para 8 e a tela grava quantidade 15 com
  `estoqueConsultado` 10
- **THEN** o saldo final é 13 e é gravada uma movimentação de entrada de 5

#### Scenario: Edição sem mexer na quantidade
- **WHEN** a edição envia a mesma quantidade que foi consultada
- **THEN** o saldo não muda e nenhuma movimentação é gravada

### Requirement: Preço de venda com controle de versão
`PATCH /api/Produto/{id}/preco-venda` SHALL exigir preço maior que zero, com até 2 casas decimais e até R$ 1 bilhão,
e a versão consultada no formato `custo|preço`. A gravação SHALL acontecer só se custo e preço no banco ainda forem
os da versão; senão, SHALL responder 409.

#### Scenario: Preço alterado por outra pessoa
- **WHEN** a versão enviada é `2.0000|5.00`, mas o preço no banco já virou 5,50
- **THEN** a resposta é 409 com "O custo ou preço de venda mudou desde esta consulta. Atualize os preços e confira o
  ajuste antes de salvar novamente." e o preço não muda

#### Scenario: Sem versão
- **WHEN** a versão consultada vem vazia
- **THEN** a resposta é 400 com "Atualize a consulta do produto antes de salvar o preço."

#### Scenario: Preço gravado
- **WHEN** a versão confere e o preço é 6,90
- **THEN** a resposta é 200 com "Preço de venda atualizado." e o produto atualizado

### Requirement: Excluir produto
`DELETE /api/Produto/{id}` SHALL remover o produto da empresa e responder "Produto removido com sucesso.".

#### Scenario: Produto inexistente
- **WHEN** o id não existe na empresa
- **THEN** a resposta é 404 com "Produto não encontrado."

### Requirement: Vencimentos e validade do produto
`GET /api/Produto/vencimentos` e `GET /api/Produto/vencimentos/resumo` SHALL listar os produtos com controle de
validade por faixa e o resumo por faixa, como no spec `produtos/controle-validade`. `PUT /api/Produto/{id}/validade`
SHALL gravar a data e responder "Validade atualizada com sucesso.".

#### Scenario: Data inválida
- **WHEN** a validade enviada não é uma data
- **THEN** a resposta é 400 com "Data de validade inválida."

### Requirement: Carga legada de demonstração
`POST /api/Produto/importar-legado` SHALL ser permitido a administrador e gerente e SHALL rodar a mesma carga de
produtos de demonstração da API .NET na empresa do usuário, respondendo "Carga de 4.714 produtos importada com
sucesso!" com `rowsAffected`; qualquer falha SHALL responder 400 com "Erro ao importar carga: " seguido da causa.

#### Scenario: Atendente tenta importar
- **WHEN** um atendente chama a rota
- **THEN** a resposta é 403
