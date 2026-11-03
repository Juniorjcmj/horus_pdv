# Spec Delta

## Purpose

Inventário de estoque na API Java: abertura por escopo, contagens cegas, anulação e recontagem, aplicação das
diferenças com justificativa e senha de supervisor, cancelamento e trilha, com o mesmo contrato da API .NET.

## ADDED Requirements

### Requirement: Acesso ao inventário
Abrir, listar, consultar, contar e anular SHALL aceitar administrador, gerente e estoquista. Recontar, aplicar,
cancelar e ver a trilha SHALL exigir administrador ou gerente. Toda operação SHALL ficar restrita à empresa do usuário
logado.

#### Scenario: Estoquista tenta aplicar
- **WHEN** um estoquista chama `POST /api/Inventario/{id}/aplicar`
- **THEN** a resposta é 403

#### Scenario: Atendente sem acesso
- **WHEN** um atendente chama `GET /api/Inventario`
- **THEN** a resposta é 403

### Requirement: Contagem cega
A consulta do inventário e dos itens SHALL trazer saldos e valores do sistema só para administrador e gerente; para o
estoquista, esses campos MUST NOT ser revelados.

#### Scenario: Estoquista consulta os itens
- **WHEN** um estoquista chama `GET /api/Inventario/{id}/itens`
- **THEN** os itens vêm sem o saldo do sistema e sem valores, no mesmo formato da API .NET

### Requirement: Abrir inventário
`POST /api/Inventario` SHALL abrir um inventário com escopo `loja`, `categorias` ou `produtos`. O escopo MUST NOT se
sobrepor ao de outro inventário aberto. A abertura SHALL ser serializada por empresa.

#### Scenario: Escopo inválido
- **WHEN** o escopo enviado é `deposito`
- **THEN** a resposta é 400 com "Escopo deve ser 'loja', 'categorias' ou 'produtos'." e `data.codigo`
  `ESCOPO_INVALIDO`

#### Scenario: Escopo sobreposto
- **WHEN** já existe inventário aberto da loja inteira e outro é aberto para uma categoria
- **THEN** a resposta é 409 com `data.codigo` `ESCOPO_SOBREPOSTO`

#### Scenario: Abertura simultânea
- **WHEN** duas aberturas chegam ao mesmo tempo e uma não consegue a vez
- **THEN** essa responde 409 com "Outro inventário está sendo aberto. Tente novamente."

### Requirement: Registrar contagens
`POST /api/Inventario/{id}/contagens` SHALL registrar a quantidade contada de um produto (pelo id ou pelo código).
SHALL recusar quantidade negativa, fração em produto de unidade `UN`, produto fora do escopo e inventário encerrado.
As recusas usam o código e o status HTTP da API .NET.

#### Scenario: Fração em UN
- **WHEN** a contagem de um produto em `UN` é 1,5
- **THEN** a resposta é 400 com "Produto em UN não aceita quantidade fracionada."

#### Scenario: Fora do escopo
- **WHEN** o produto contado não pertence ao escopo
- **THEN** a resposta é 422 com "O produto está fora do escopo deste inventário."

#### Scenario: Código desconhecido
- **WHEN** a contagem informa um código que não existe na empresa
- **THEN** a resposta é 404 com `data.codigo` `CODIGO_DESCONHECIDO`

### Requirement: Anular contagem e recontar
`POST /api/Inventario/{id}/contagens/{contagemId}/anular` SHALL exigir motivo. O estoquista SHALL anular só os próprios
lançamentos. Uma contagem anulada MUST NOT ser anulada de novo. `POST /api/Inventario/{id}/recontar` SHALL reabrir a
contagem dos produtos informados.

#### Scenario: Anular contagem de outro usuário
- **WHEN** um estoquista tenta anular a contagem de outra pessoa
- **THEN** a resposta é 403 com "Você só pode anular os seus próprios lançamentos."

#### Scenario: Anulação repetida
- **WHEN** a contagem já foi anulada
- **THEN** a resposta é 409 com "Esta contagem já foi anulada."

### Requirement: Aplicar inventário
`POST /api/Inventario/{id}/aplicar` SHALL exigir justificativa com 5 ou mais caracteres e a senha de um supervisor
válido da empresa. SHALL aplicar ao saldo atual a diferença entre a quantidade contada e o saldo da primeira
contagem, pelo registrador único (tipo `inventario`), preservando movimentos posteriores à contagem e o piso zero
da API .NET. Se pedido, SHALL zerar os não contados conforme a mesma regra da API .NET. Cada recusa SHALL ser
registrada na trilha.

#### Scenario: Justificativa curta
- **WHEN** a justificativa é "ok"
- **THEN** a resposta é 400 com "Informe uma justificativa de no mínimo 5 caracteres." e `data.codigo`
  `JUSTIFICATIVA_CURTA`, e a tentativa negada aparece na trilha

#### Scenario: Senha do supervisor errada
- **WHEN** a senha do supervisor está errada
- **THEN** a resposta é 403 com `data.codigo` `SENHA_INVALIDA`, a mensagem da validação de supervisor e nenhum saldo
  muda

#### Scenario: Aplicação com diferença
- **WHEN** o sistema tem 10, foram contados 7 e o inventário é aplicado
- **THEN** o saldo vira 7 e há uma movimentação `inventario` de 3 com o inventário como origem

#### Scenario: Aplicar duas vezes
- **WHEN** o inventário já foi aplicado
- **THEN** a resposta é 409 com `data.codigo` `JA_APLICADO` e o estoque não muda

#### Scenario: Movimento após a primeira contagem
- **WHEN** a primeira contagem registrou saldo do sistema 10, foram contados 7, e uma saída posterior reduziu o
  saldo atual para 8 antes da aplicação
- **THEN** a aplicação registra diferença de -3 e o saldo final vira 5, preservando a saída posterior

### Requirement: Cancelar inventário e trilha
`POST /api/Inventario/{id}/cancelar` SHALL exigir motivo e encerrar o inventário sem mexer no estoque. `GET
/api/Inventario/{id}/trilha` SHALL listar, em ordem, os eventos do inventário (abertura, contagens, anulações,
recontagens, tentativas negadas, aplicação ou cancelamento).

#### Scenario: Cancelar sem motivo
- **WHEN** o cancelamento vem sem motivo
- **THEN** a resposta é 400 com "Informe o motivo do cancelamento."
