# vendas/registro-venda Specification

## Purpose

Registro de vendas na API Java: validação de totais e pagamentos, preço autorizado, baixa de estoque e de lotes,
fiado, idempotência offline, histórico, detalhe e impressão, no mesmo contrato da API .NET.

## Requirements

### Requirement: Rotas de vendas no contrato da API .NET
`POST /api/HistoricoVendas`, `GET /api/HistoricoVendas`, `GET /api/HistoricoVendas/{saleNumber}`,
`POST /api/HistoricoVendas/{saleNumber}/imprimir` e `POST /api/HistoricoVendas/autorizar-preco` SHALL responder com o
mesmo status, campos, formatos e mensagens da API .NET, com os mesmos perfis por rota, medidos pelo comparador.

#### Scenario: Venda registrada
- **WHEN** uma venda válida é enviada sem `eventId`
- **THEN** a resposta é 201 com `saleNumber`, `vendaId`, `rows`, `fiscalQueued`, `emitirFiscal`, `isReplay` false e
  `warnings`, no formato da API .NET

#### Scenario: Venda sem itens
- **WHEN** a venda chega com a lista de itens vazia
- **THEN** a resposta é 400 com "Venda sem itens."

### Requirement: Venda exige caixa liberado
Uma venda online SHALL ser recusada com a mensagem de bloqueio do caixa quando o operador não pode vender, gravando a
tentativa na auditoria. Uma venda sincronizada do modo offline (com `eventId` ou `occurredAt`) SHALL ser aceita mesmo
com o caixa bloqueado.

#### Scenario: Caixa fechado
- **WHEN** um operador sem turno aberto envia uma venda online
- **THEN** a resposta é 400 com o motivo do bloqueio e a auditoria registra a venda bloqueada

#### Scenario: Venda offline com caixa fechado
- **WHEN** a mesma venda chega com `eventId` depois que o turno foi fechado
- **THEN** a venda é registrada

### Requirement: Conferência de totais e pagamentos
O total SHALL ser igual à soma dos líquidos dos itens (quantidade × preço − desconto, arredondado como na API .NET);
cada item SHALL trazer o preço de fechamento; os pagamentos SHALL cobrir o total, com troco só sobre o excedente em
dinheiro. Divergências SHALL dar 400 com a mensagem da API .NET.

#### Scenario: Total divergente
- **WHEN** a soma dos itens é R$ 10,00 e o total enviado é R$ 9,00
- **THEN** a resposta é 400 com "Soma dos itens líquidos difere do total da venda."

#### Scenario: Item sem preço
- **WHEN** um item chega sem `unitPrice`
- **THEN** a resposta é 400 com "Preço do item ausente. Atualize o cliente para enviar o preço de fechamento."

### Requirement: Preço diferente do cadastro
Um item com preço diferente do cadastrado SHALL exigir uma autorização de preço válida (criada com a senha de um
gerente, de uso único, com validade de 2 horas), consumida na mesma transação da venda. Na venda offline sem
autorização e com preço abaixo do cadastro, a venda SHALL ser aceita e registrada no log de atividades.

#### Scenario: Autorização consumida
- **WHEN** o caixa envia o item com a autorização obtida em `autorizar-preco`
- **THEN** a venda é registrada e a autorização fica vinculada a ela e não pode ser usada de novo

#### Scenario: Senha errada ao autorizar
- **WHEN** `autorizar-preco` é chamado com senha de gerente errada
- **THEN** a resposta é 403 com a mensagem da validação de supervisor

### Requirement: Baixa de estoque na venda
A venda SHALL baixar o estoque de cada produto pela porta única de movimentação, na mesma transação da venda, com
origem "venda" vinculada ao número gerado. Sem saldo, a venda online SHALL ser recusada; a venda offline SHALL ser
aceita com saldo negativo e aviso de ruptura na resposta e na auditoria.

#### Scenario: Estoque insuficiente online
- **WHEN** a venda online pede 5 unidades de um produto com saldo 3
- **THEN** a resposta é 400 com "Estoque insuficiente para <produto>. Disponível: <saldo formatado como na API
  .NET>." e nada é gravado

#### Scenario: Ruptura offline
- **WHEN** a mesma venda chega com `eventId`
- **THEN** a venda é registrada, o saldo fica −2 e `warnings` traz a ruptura

### Requirement: Baixa por lote depois da venda
Depois de gravada a venda, a API SHALL consumir os lotes dos produtos vendidos pela regra FEFO conforme o modo da
empresa, sem nunca desfazer a venda quando o consumo de lotes falhar.

#### Scenario: Modo ativo
- **WHEN** a empresa está com a baixa por lote ativa e vende 4 unidades de um produto com dois lotes
- **THEN** o lote de validade mais próxima é consumido primeiro e o consumo fica vinculado à venda

### Requirement: Fiado na venda
Uma venda com pagamento fiado SHALL exigir cliente cadastrado pelo CPF/CNPJ, respeitar o limite de crédito (saldo
devedor mais crédito delegado ao Gateway) e lançar o débito na mesma transação, devolvendo `fiadoSaldoAtual`. Com
crédito controlado pelo Gateway na empresa, a venda fiado comum SHALL ser recusada como na API .NET.

#### Scenario: Fiado sem cliente
- **WHEN** a venda tem pagamento fiado e CPF "-"
- **THEN** a resposta é 400 com "Para compras a prazo / fiado, é obrigatório vincular um cliente cadastrado."

#### Scenario: Limite estourado
- **WHEN** o fiado ultrapassa o limite do cliente
- **THEN** a resposta é 400 com a mensagem de limite da API .NET e nada é gravado

### Requirement: Idempotência da venda offline
Com `eventId`, a venda SHALL ser idempotente por empresa e evento: o mesmo conteúdo SHALL devolver a resposta original
com `isReplay` true e status 200, sem nova baixa de estoque; conteúdo diferente SHALL dar 409. O hash SHALL ser o
enviado em `payloadHash` ou, sem ele, o calculado como na API .NET.

#### Scenario: Replay
- **WHEN** a mesma venda é reenviada com o mesmo `eventId`
- **THEN** a resposta é 200 com o mesmo `saleNumber` e `isReplay` true, e o estoque não muda

#### Scenario: Conflito
- **WHEN** o `eventId` já processado chega com outro total
- **THEN** a resposta é 409 com "Conflito de idempotência: o EventId '<id>' já foi processado anteriormente com um
  payload diferente."

### Requirement: Histórico, detalhe e impressão
A lista SHALL filtrar por data e por turno de caixa (outro operador só vê o próprio turno, salvo gerente e
administrador); o detalhe SHALL trazer itens, pagamentos e o documento fiscal da venda; a impressão SHALL devolver as
linhas e os pagamentos da venda.

#### Scenario: Turno de outro operador
- **WHEN** um caixa lista as vendas de um turno de outro operador
- **THEN** a resposta é 403 com "Turno de caixa não encontrado ou pertencente a outro operador."

#### Scenario: Venda inexistente
- **WHEN** o detalhe é pedido para um número que não existe
- **THEN** a resposta é 404 com "Venda não encontrada."

### Requirement: Recusas conscientes até as Fases 5 e 7
Até as fases correspondentes, a venda com `xmlNfceContingencia` SHALL ser recusada com 400 e mensagem informando que a
importação da nota do Gateway ainda não está disponível nesta versão; a venda com `creditAuthorization` SHALL ser
recusada como a API .NET recusa quem não usa o token do Gateway.

#### Scenario: Nota de contingência do Gateway
- **WHEN** a venda chega com `xmlNfceContingencia`
- **THEN** a resposta é 400, nada é gravado, e o cenário de contrato registra a divergência
