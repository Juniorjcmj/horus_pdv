# vendas/cancelamento-correcao Specification

## Purpose

Cancelamento de venda autorizado pela senha de um gerente, com estorno de estoque, lotes e fiado e trilha completa,
e correção de venda que remove itens e gera uma venda nova, no mesmo contrato da API .NET.

## Requirements

### Requirement: Cancelamento com senha do gerente
`POST /api/HistoricoVendas/{saleNumber}/cancelar` SHALL exigir justificativa de no mínimo 5 caracteres e credenciais
válidas de um supervisor da empresa. Cancelada, a venda SHALL guardar quem pediu, quem autorizou, o motivo e a data,
e o estoque, os lotes e o fiado SHALL voltar.

#### Scenario: Cancelamento autorizado
- **WHEN** o operador cancela uma venda finalizada com a senha certa do gerente e justificativa "Cliente desistiu."
- **THEN** a resposta é 200 com `canceladoEm`, `supervisorNome`, `itensEstornados` e `fiadoEstornado`, e o estoque
  volta pela porta única de movimentação com o tipo de devolução

#### Scenario: Justificativa curta
- **WHEN** a justificativa tem 3 caracteres
- **THEN** a resposta é 400 com "Informe uma justificativa de no mínimo 5 caracteres."

#### Scenario: Senha errada
- **WHEN** a senha do gerente está errada
- **THEN** a resposta é 403 e nada muda na venda

### Requirement: Recusas do cancelamento com código
O cancelamento SHALL recusar com `data.codigo`, mapeado em HTTP como na API .NET: `NAO_ENCONTRADA` (404),
`JA_CANCELADA`, `STATUS_INVALIDO`, `NOTA_AUTORIZADA` e `NOTA_PENDENTE` (409), demais (400). Venda com nota fiscal
autorizada MUST NOT ser cancelada por esta rota.

#### Scenario: Cancelar duas vezes
- **WHEN** a mesma venda é cancelada de novo
- **THEN** a resposta é 409 com `data.codigo` `JA_CANCELADA`

#### Scenario: Nota autorizada
- **WHEN** a venda tem NFC-e autorizada
- **THEN** a resposta é 409 com `data.codigo` `NOTA_AUTORIZADA` e o id e o modelo do documento

#### Scenario: Nota ainda na fila
- **WHEN** a venda tem NFC-e pendente que ainda não foi assinada
- **THEN** a venda é cancelada e a mensagem avisa que a nota não será emitida

### Requirement: Trilha de cancelamentos
Todo pedido de cancelamento, autorizado ou negado, SHALL entrar na trilha com operador, supervisor, justificativa,
terminal, IP e a fotografia da venda. A trilha completa SHALL ser só para administrador e gerente; a lista de
cancelamentos SHALL aceitar também atendente e caixa.

#### Scenario: Tentativa negada na trilha
- **WHEN** um cancelamento é negado por senha errada
- **THEN** `GET /api/HistoricoVendas/cancelamentos/auditoria` mostra a tentativa com resultado "negada"

#### Scenario: Caixa pede a auditoria
- **WHEN** um usuário caixa chama `GET /api/HistoricoVendas/cancelamentos/auditoria`
- **THEN** a resposta é 403

### Requirement: Estorno do fiado no cancelamento
O cancelamento SHALL estornar o débito de fiado da venda. Quando o cliente já pagou parte do fiado (saldo devedor menor
que o débito), o cancelamento SHALL ser recusado com a mensagem de estorno pelo financeiro.

#### Scenario: Fiado já pago em parte
- **WHEN** a venda fiado de R$ 100,00 é cancelada e o cliente deve só R$ 40,00
- **THEN** a resposta é 400 com "O cliente já pagou parte deste fiado: o saldo devedor é menor que o valor da venda.
  Faça o estorno pelo financeiro."

### Requirement: Correção de venda
`POST /api/HistoricoVendas/{saleNumber}/corrigir` SHALL remover os produtos escolhidos da venda, devolver o estoque
deles e gerar uma venda nova com os itens restantes, marcando a original como corrigida. Exige justificativa de no
mínimo 5 caracteres e ao menos um produto; o responsável é o gerente da senha ou, sem gerente, um usuário com
perfil financeiro.

#### Scenario: Correção autorizada pelo gerente
- **WHEN** o caixa corrige a venda 100 removendo um produto, com a senha do gerente
- **THEN** a resposta traz a nova venda, o estoque do produto removido volta e a venda 100 fica "corrigida"

#### Scenario: Sem gerente e sem perfil financeiro
- **WHEN** um caixa sem perfil financeiro corrige sem informar gerente
- **THEN** a resposta é 403 com "Apenas usuários com perfil financeiro podem corrigir vendas."

#### Scenario: Cancelar venda corrigida
- **WHEN** se tenta cancelar a venda original que foi corrigida
- **THEN** a resposta é 409 com `data.codigo` `STATUS_INVALIDO` e a orientação de cancelar a venda nova
