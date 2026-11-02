# Spec Delta

## Purpose

Cadastro de clientes de cada empresa na API Java, com as regras de limite de fiado por perfil e por compromissos já
assumidos, a auditoria das alterações e o retrato de crédito usado pelo Caixa para reconciliar vendas feitas offline,
tudo com o mesmo contrato da API .NET.

## ADDED Requirements

### Requirement: Acesso ao cadastro de clientes
As rotas de `api/Cliente` SHALL aceitar administrador, gerente, atendente e caixa para listar, criar e consultar o
retrato de crédito; editar SHALL exigir administrador, gerente ou atendente; excluir SHALL exigir administrador ou
gerente. Toda operação SHALL ficar restrita à empresa do usuário logado.

#### Scenario: Caixa cadastra cliente no PDV
- **WHEN** um caixa envia `POST /api/Cliente` com dados válidos
- **THEN** a resposta é 201 com "Cliente criado com sucesso."

#### Scenario: Caixa tenta editar
- **WHEN** um caixa chama `PUT /api/Cliente/{id}`
- **THEN** a resposta é 403

#### Scenario: Atendente tenta excluir
- **WHEN** um atendente chama `DELETE /api/Cliente/{id}`
- **THEN** a resposta é 403

### Requirement: Listar clientes
`GET /api/Cliente` SHALL devolver os clientes da empresa ordenados por nome, com os mesmos campos da API .NET
(inclusive limite de crédito e saldo devedor), com "Clientes obtidos com sucesso.".

#### Scenario: Lista ordenada
- **WHEN** a empresa tem os clientes "Zélia" e "Ana"
- **THEN** a lista traz "Ana" antes de "Zélia"

### Requirement: Validação do cliente
Criar e editar SHALL exigir nome com 3+ caracteres, documento com 11 ou 14 dígitos quando informado, telefone ou
celular com DDD (10 ou 11 dígitos), e-mail válido quando informado e documento único na empresa; o id de cliente novo
SHALL ser `cl-<milissegundos>`.

#### Scenario: Nome curto
- **WHEN** o nome enviado é "Jo"
- **THEN** a resposta é 400 com "Nome do cliente deve ter no minimo 3 caracteres."

#### Scenario: Sem telefone
- **WHEN** telefone e celular vêm vazios
- **THEN** a resposta é 400 com "Informe um telefone ou celular com DDD."

#### Scenario: Documento repetido
- **WHEN** outro cliente da empresa já tem o mesmo documento
- **THEN** a resposta é 400 com "Já existe cliente com este documento."

### Requirement: Gerente só consulta o fiado
O perfil gerente MUST NOT alterar o limite de crédito de um cliente nem excluir cliente com saldo devedor; os demais
perfis com acesso à rota podem.

#### Scenario: Gerente muda o limite
- **WHEN** um gerente edita um cliente mudando o limite de crédito
- **THEN** a resposta é 400 com "Seu perfil só consulta fiado/crediário: o limite de crédito do cliente não pode ser
  alterado." e nada é gravado

#### Scenario: Gerente exclui cliente devedor
- **WHEN** um gerente exclui um cliente com saldo devedor maior que zero
- **THEN** a resposta é 400 com "Seu perfil só consulta fiado/crediário: cliente com saldo devedor não pode ser
  excluído."

### Requirement: Limite não abaixo dos compromissos
Ao gravar um cliente existente, o limite de crédito MUST NOT ficar abaixo do saldo devedor somado aos orçamentos de
fiado ainda abertos delegados ao Gateway para esse cliente; a conferência e a gravação SHALL ocorrer na mesma
transação, com bloqueio da linha do cliente.

#### Scenario: Limite abaixo do orçamento delegado
- **WHEN** o cliente deve R$ 50,00, tem R$ 30,00 delegados a um Gateway e o administrador grava limite de R$ 60,00
- **THEN** a resposta é 400 com "Recolha e reconcilie os orçamentos antes de reduzir o limite abaixo dos
  compromissos."

### Requirement: Auditoria do cadastro de clientes
Criar, editar e excluir cliente SHALL registrar na auditoria os eventos `ClienteCriado`, `ClienteAtualizado` e
`ClienteExcluido`; a mudança do limite de crédito SHALL registrar também `FiadoLimiteAlterado`, com os valores antigo e
novo em reais, com as mesmas descrições da API .NET.

#### Scenario: Limite alterado
- **WHEN** o administrador muda o limite de R$ 100,00 para R$ 200,00
- **THEN** a auditoria recebe `FiadoLimiteAlterado` com os dois valores e `ClienteAtualizado`

### Requirement: Retrato de crédito para reconciliação offline
`POST /api/Cliente/credito-snapshot` SHALL receber até 5000 identidades de venda do cliente e devolver todos os
clientes da empresa (lidos com bloqueio) e quais dessas identidades já existem como vendas gravadas.

#### Scenario: Vendas offline já recebidas
- **WHEN** o Caixa envia 3 identidades e 2 delas já foram gravadas como vendas da empresa
- **THEN** a resposta é 200 com `customers` (todos os clientes) e `acceptedClientSaleIds` com as 2 identidades

#### Scenario: Identidades demais
- **WHEN** o corpo tem 5001 identidades
- **THEN** a resposta é 400 com "Muitas identidades para conferir crédito."

### Requirement: Excluir cliente
`DELETE /api/Cliente/{id}` SHALL remover o cliente da empresa e responder "Cliente removido com sucesso."; quando o
cliente não existir na empresa, editar ou excluir SHALL responder 404 com "Cliente não encontrado.".

#### Scenario: Cliente de outra empresa
- **WHEN** o administrador tenta excluir o id de um cliente de outra empresa
- **THEN** a resposta é 404 com "Cliente não encontrado." e o cliente continua existindo
