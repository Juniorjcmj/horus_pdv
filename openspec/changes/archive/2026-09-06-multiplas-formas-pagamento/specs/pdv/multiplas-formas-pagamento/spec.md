## Purpose

Permite que o operador do PDV finalize uma venda dividindo o valor total entre múltiplos meios de pagamento (como PIX, Dinheiro e Cartão), com conferência exata de caixa e emissão fiscal em conformidade com a SEFAZ.

## ADDED Requirements

### Requirement: Divisão de pagamento no PDV
O sistema DEVE permitir que o operador adicione uma ou mais formas de pagamento para liquidar o total de uma venda no PDV.

#### Scenario: Pagamento dividido com valor exato
- **WHEN** o operador finaliza uma compra de R$ 300,00 e adiciona R$ 200,00 no PIX e R$ 100,00 no Débito
- **THEN** o sistema exibe saldo restante igual a R$ 0,00 e habilita a confirmação da venda com as duas parcelas registradas.

#### Scenario: Adição progressiva de pagamentos
- **WHEN** uma venda de R$ 150,00 recebe um primeiro pagamento de R$ 50,00 no Dinheiro
- **THEN** o sistema atualiza o saldo restante para R$ 100,00 e sugere esse valor restante para a próxima forma de pagamento selecionada.

### Requirement: Validação de valores e cálculo de troco
O sistema DEVE validar que a soma dos pagamentos cobre integralmente o valor da venda, calculando troco exclusivamente sobre o excedente recebido em Dinheiro.

#### Scenario: Pagamento misto com dinheiro gerando troco
- **WHEN** em uma venda de R$ 100,00 o cliente paga R$ 50,00 no PIX e entrega uma cédula de R$ 100,00 em Dinheiro
- **THEN** o sistema aceita a operação, registra R$ 50,00 em PIX, R$ 100,00 em Dinheiro recebido e indica R$ 50,00 de troco em dinheiro ao consumidor.

#### Scenario: Tentativa de finalização com saldo pendente
- **WHEN** o operador tenta confirmar a venda antes que a soma dos pagamentos atinja o valor total
- **THEN** o sistema bloqueia a finalização e exibe mensagem informando o saldo restante a pagar.

### Requirement: Registro e conciliação de caixa
O sistema DEVE registrar cada parcela de pagamento individualmente e refletir cada valor nas apurações e conferências do caixa aberto.

#### Scenario: Venda registrada com conciliação individual de cada meio
- **WHEN** a venda com múltiplos pagamentos é concluída
- **THEN** o sistema persiste as formas e valores vinculados à venda e contabiliza os valores fracionados nos totais de fechamento de caixa por forma de pagamento (Dinheiro na gaveta, PIX e Cartões).

#### Scenario: Retrocompatibilidade com requisição de pagamento único
- **WHEN** a API recebe uma requisição legada contendo apenas o campo único de forma de pagamento
- **THEN** o sistema cria automaticamente o registro de pagamento único com o valor integral da venda, sem quebrar clientes legados.

### Requirement: Emissão fiscal de múltiplos pagamentos na NFC-e
O sistema DEVE transmitir cada forma de pagamento e seu respectivo valor na emissão do documento fiscal eletrônico (NFC-e).

#### Scenario: Transmissão de múltiplos detPag para a SEFAZ
- **WHEN** a venda com múltiplos pagamentos é transmitida para a SEFAZ
- **THEN** o XML da NFC-e é gerado com múltiplos blocos `<detPag>`, associando o código fiscal (`tPag`) e o valor (`vPag`) correspondente de cada meio utilizado.
