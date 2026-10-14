## ADDED Requirements

### Requirement: Precisão e identidade dos valores de pagamento
O sistema MUST representar parcelas líquidas, recebido, saldo e troco em centavos. Para dinheiro, recebido MUST ser igual à parcela líquida mais troco; troco MUST ser não negativo e exclusivo de dinheiro. Outros meios MUST ter troco zero. O total líquido MUST ser igual à soma das parcelas, sem tolerar diferença real de um centavo.

#### Scenario: Troco decimal exato
- **WHEN** uma venda de R$ 8,80 recebe R$ 10,00 em dinheiro
- **THEN** a interface, a persistência e o payload fiscal informam troco R$ 1,20, sem resíduo como 1.1999999999999993

#### Scenario: Dados de dinheiro incoerentes
- **WHEN** uma parcela líquida R$ 8,80 informa recebido R$ 10,00 e troco R$ 1,19
- **THEN** a finalização é bloqueada antes de efeitos persistentes

#### Scenario: Troco em meio não monetário
- **WHEN** uma parcela PIX, cartão ou fiado informa troco positivo
- **THEN** a operação é rejeitada

### Requirement: Preservação do valor fiscal declarado
O sistema MUST declarar o recebido bruto em dinheiro e o valor líquido nos demais meios, deduzindo o troco uma única vez para conciliar o total fiscal, tanto na emissão cloud quanto na local.

#### Scenario: XML de pagamento misto
- **WHEN** a venda R$ 100,00 contém PIX R$ 50,00 e dinheiro líquido R$ 50,00, recebido R$ 100,00 e troco R$ 50,00
- **THEN** os valores declarados são PIX R$ 50,00 e dinheiro R$ 100,00, com troco R$ 50,00 e resultado líquido R$ 100,00
