## Purpose

Permitir a reimpressão fiel dos itens e pagamentos registrados na venda, distinguindo valores iguais a zero de informações ausentes em registros legados.

## ADDED Requirements

### Requirement: Preservação dos valores registrados
O sistema MUST utilizar totais líquidos e descontos registrados ao exibir e reimprimir a venda, preservando zero válido. MUST exibir meios de pagamento, valores líquidos, dinheiro recebido e troco quando disponíveis no registro online ou local, sem substituí-los por total da venda e zero fixos.

#### Scenario: Item integralmente descontado
- **WHEN** os demais itens somam R$ 82,48 e o biscoito de R$ 5,14 tem líquido registrado de R$ 0,00
- **THEN** o cupom exibe esse item gratuito e soma líquida R$ 82,48, sem elevar a soma a R$ 87,62

#### Scenario: Reimpressão de pagamento misto
- **WHEN** a venda de R$ 100,00 registra PIX de R$ 50,00 e parcela dinheiro de R$ 50,00 com recebido R$ 100,00 e troco R$ 50,00
- **THEN** a reimpressão preserva esses valores e meios sem declarar recebido R$ 100,00 como pagamento líquido em dinheiro

### Requirement: Informação legada ausente ou inconsistente
O sistema MUST distinguir campo ausente de zero e usar fallback de item somente quando o líquido estiver ausente e houver dados suficientes para cálculo com descontos. Recebido e troco desconhecidos MUST ser omitidos ou identificados como não informados. Registros históricos divergentes MUST ser apresentados sem correção silenciosa, com indicação de inconsistência.

#### Scenario: Recebido desconhecido
- **WHEN** uma venda legada não tem detalhes de recebido ou troco
- **THEN** o cupom não inventa recebido igual ao total nem troco zero

#### Scenario: Divergência histórica
- **WHEN** os itens registrados somam R$ 87,62 e o total histórico é R$ 82,48
- **THEN** a consulta indica divergência e a reimpressão preserva os valores registrados, sem modificar a venda
