# Spec Delta

## Purpose

Pedidos montados pelo vendedor com preço congelado e finalizados depois no caixa, que cobra exatamente os valores
combinados, no mesmo contrato da API .NET.

## ADDED Requirements

### Requirement: Rotas de pedidos no contrato da API .NET
`POST /api/Pedido`, `GET /api/Pedido`, `GET /api/Pedido/{orderNumber}`, `POST /api/Pedido/{orderNumber}/finalizar` e
`POST /api/Pedido/{orderNumber}/cancelar` SHALL responder com o mesmo status, campos, mensagens e perfis por rota da
API .NET, medidos pelo comparador, restritos à empresa do usuário logado.

#### Scenario: Criar pedido
- **WHEN** um atendente cria um pedido com dois itens
- **THEN** a resposta traz o número do pedido, os itens com o preço congelado e o status aberto, como na API .NET

#### Scenario: Pedido inexistente
- **WHEN** `GET /api/Pedido/{orderNumber}` é chamado com um número que não existe
- **THEN** a resposta é 404

### Requirement: Pedido não reserva estoque
Criar um pedido MUST NOT alterar o estoque; o estoque SHALL ser conferido e baixado só na finalização.

#### Scenario: Estoque após criar
- **WHEN** um pedido de 3 unidades é criado
- **THEN** o saldo do produto não muda

### Requirement: Finalização com preço congelado
A finalização SHALL exigir caixa liberado para o operador, cobrar os preços congelados no pedido (não o preço atual do
produto), baixar o estoque, vincular a venda ao pedido e ao turno do operador, e decidir a NFC-e como uma venda comum.
Finalizar de novo um pedido já pago SHALL devolver a venda já gerada.

#### Scenario: Preço mudou depois do pedido
- **WHEN** o preço do produto sobe depois do pedido e o pedido é finalizado
- **THEN** a venda cobra o preço do pedido

#### Scenario: Pedido cancelado
- **WHEN** se tenta finalizar um pedido cancelado
- **THEN** a resposta é 400 com "Este pedido foi cancelado."

#### Scenario: Finalizar duas vezes
- **WHEN** um pedido já finalizado é finalizado de novo
- **THEN** a resposta traz a mesma venda e o estoque não muda outra vez

### Requirement: Venda de pedido pelo registro comum
Uma venda enviada a `POST /api/HistoricoVendas` com `orderNumber` SHALL só ser aceita se o pedido estiver aberto, o
cliente for o mesmo e os itens forem idênticos ao pedido (código, quantidade e preço, sem desconto).

#### Scenario: Itens divergentes
- **WHEN** a venda com `orderNumber` muda a quantidade de um item
- **THEN** a resposta é 400 com "Itens divergem dos preços congelados no pedido."
