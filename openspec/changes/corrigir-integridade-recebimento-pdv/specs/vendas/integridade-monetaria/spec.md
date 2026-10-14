## Purpose

Garantir que itens, total e pagamentos de cada venda permaneçam conciliados em centavos em todos os caminhos de gravação e sincronização.

## ADDED Requirements

### Requirement: Fechamento monetário consistente
O sistema MUST exigir que a soma dos totais líquidos dos itens, arredondados individualmente para centavos, seja igual ao total da venda e à soma das parcelas líquidas. Quantidades devem ser positivas, valores monetários finitos e não negativos, descontos não superiores ao bruto e parcelas de uma venda positiva estritamente positivas; item líquido zero é válido. A normalização MUST usar arredondamento de meio centavo para longe de zero e rejeitar divergências reais de um centavo ou mais.

#### Scenario: Total divergente dos itens
- **WHEN** os itens líquidos somam R$ 87,62 e o cabeçalho informa R$ 82,48
- **THEN** a venda é rejeitada com indicação da divergência antes de qualquer gravação de venda, estoque, caixa, fiado ou emissão

#### Scenario: Quantidade fracionada e item gratuito
- **WHEN** uma venda inclui 0,125 unidade a R$ 10,00 e um item de R$ 5,14 com desconto integral
- **THEN** os itens líquidos são R$ 1,25 e R$ 0,00 e o total conciliado é R$ 1,25

#### Scenario: Valores inválidos
- **WHEN** a requisição contém quantidade zero ou negativa, valor não finito, parcela negativa, desconto superior ao bruto ou total negativo
- **THEN** a operação é rejeitada sem efeitos persistentes

### Requirement: Mesma validação online offline e sincronização
O sistema MUST aplicar o contrato monetário antes de confirmar a venda online, finalizar pedido ou enfileirar venda offline e MUST validá-lo novamente no servidor durante sincronização. Retries MUST preservar identidade e não duplicar efeitos. Falhas MUST preservar atomicidade; uma venda inválida já enfileirada deve permanecer identificável com erro, sem ser apagada ou alterada silenciosamente.

#### Scenario: Rejeição de outbox antiga inconsistente
- **WHEN** uma venda antiga da fila tem itens diferentes do total durante sincronização
- **THEN** o servidor rejeita sem efeitos parciais e a fila mantém o registro com erro acionável

#### Scenario: Retry após resposta perdida
- **WHEN** uma venda válida já aceita é enviada novamente com sua mesma identidade
- **THEN** o sistema retorna o resultado compatível com o registro existente sem duplicar venda, estoque, caixa ou débito fiado

#### Scenario: Pagamento único legado
- **WHEN** um cliente legado informa somente a forma única de pagamento e itens coerentes com o total
- **THEN** o sistema deriva uma parcela líquida pelo total e aplica as mesmas validações, sem inferir dinheiro recebido ou troco históricos desconhecidos
