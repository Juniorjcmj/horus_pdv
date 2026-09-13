## Purpose

Permite vender no fiado (crediário de loja), controlando saldo devedor por cliente, limite de crédito, recebimentos parciais/totais e extrato imprimível, eliminando o caderninho de papel e integrando o fiado ao fluxo fiscal via `tPag=05`.

## ADDED Requirements

### Requirement: Campos de crédito no cadastro de cliente
O sistema DEVE permitir configurar limite de crédito por cliente e manter saldo devedor atualizado automaticamente.

#### Scenario: Configuração de limite de crédito
- **WHEN** o gerente edita o cliente "Maria Silva" e define LimiteCredito = R$500,00
- **THEN** o sistema persiste o limite e o utiliza para validação nas vendas fiado.

#### Scenario: Limite zero significa ilimitado
- **WHEN** o cliente tem `LimiteCredito = 0` (padrão)
- **THEN** o sistema permite vendas fiado sem limite de valor.

### Requirement: Venda no fiado como forma de pagamento
O sistema DEVE permitir "fiado" como forma de pagamento no split payment do PDV, com obrigatoriedade de cliente e verificação de limite.

#### Scenario: Venda fiado com cliente dentro do limite
- **WHEN** o operador finaliza venda de R$80,00 selecionando "fiado" como pagamento e cliente "Maria Silva" (SaldoDevedor=R$100, LimiteCredito=R$500)
- **THEN** o sistema registra a venda, insere FiadoMovimento tipo débito (R$80), atualiza SaldoDevedor para R$180 e emite NFC-e com `tPag=05`.

#### Scenario: Venda fiado bloqueada por limite excedido
- **WHEN** o operador tenta venda fiado de R$150,00 para cliente com SaldoDevedor=R$400 e LimiteCredito=R$500
- **THEN** o sistema bloqueia e exibe "Cliente atingiu limite de crédito (R$500,00). Saldo atual: R$400,00. Disponível: R$100,00."

#### Scenario: Venda fiado sem cliente selecionado
- **WHEN** o operador seleciona "fiado" como forma de pagamento sem informar cliente
- **THEN** o sistema exige seleção de cliente cadastrado antes de prosseguir.

#### Scenario: Pagamento misto com fiado
- **WHEN** o operador finaliza venda de R$100 com R$50 em dinheiro e R$50 no fiado
- **THEN** o sistema registra o split payment normalmente, com FiadoMovimento apenas para os R$50 do fiado e NFC-e com dois `<detPag>` (tPag=01 R$50 + tPag=05 R$50).

### Requirement: Recebimento de fiado (parcial ou total)
O sistema DEVE permitir registrar recebimentos do fiado com atualização atômica do saldo devedor.

#### Scenario: Recebimento parcial
- **WHEN** o operador registra recebimento de R$50 do cliente "Maria Silva" (SaldoDevedor=R$180) via PIX com observação "Pagou metade"
- **THEN** o sistema insere FiadoMovimento tipo crédito (R$50), atualiza SaldoDevedor para R$130 e registra AuditLog.

#### Scenario: Recebimento total (quitação)
- **WHEN** o operador registra recebimento de R$130 do cliente "Maria Silva" (SaldoDevedor=R$130) via dinheiro
- **THEN** o sistema zera o SaldoDevedor, insere FiadoMovimento tipo crédito e o cliente sai da lista de devedores.

#### Scenario: Recebimento maior que saldo
- **WHEN** o operador tenta registrar recebimento de R$200 para cliente com SaldoDevedor=R$130
- **THEN** o sistema bloqueia e exibe "Valor informado (R$200,00) é maior que o saldo devedor (R$130,00)."

### Requirement: Extrato do cliente imprimível
O sistema DEVE gerar extrato de movimentações do fiado em formato imprimível em impressora térmica 80mm.

#### Scenario: Extrato com movimentações
- **WHEN** o operador solicita extrato do cliente "Maria Silva" com filtro de data
- **THEN** o sistema exibe lista cronológica de débitos (compras) e créditos (pagamentos) com saldo anterior/atual, e oferece impressão em layout 80mm com linha de assinatura do cliente.

#### Scenario: Extrato sem movimentações
- **WHEN** o operador solicita extrato de cliente sem fiado no período
- **THEN** o sistema exibe "Nenhuma movimentação no período" com saldo devedor atual.

### Requirement: Lista de devedores e KPIs
O sistema DEVE oferecer visão consolidada de todos os clientes devedores com indicadores de inadimplência.

#### Scenario: Lista de devedores
- **WHEN** o gerente acessa `GET /api/Fiado/devedores`
- **THEN** o sistema retorna lista de clientes com SaldoDevedor > 0, ordenados por saldo (maior primeiro), incluindo data da última compra fiado.

#### Scenario: Resumo de inadimplência
- **WHEN** o gerente acessa `GET /api/Fiado/resumo`
- **THEN** o sistema retorna KPIs: total a receber (soma de todos SaldoDevedor), quantidade de clientes devedores, total com mais de 30 dias sem pagamento, total com mais de 60 dias.

### Requirement: Integração fiscal (NFC-e)
O fiado DEVE ser mapeado para o código fiscal `tPag=05` (crediário loja) na NFC-e.

#### Scenario: NFC-e com pagamento fiado
- **WHEN** uma venda com fiado é transmitida para a SEFAZ
- **THEN** o XML contém `<detPag>` com `<tPag>05</tPag>` e `<vPag>` com o valor do fiado.

### Requirement: Auditoria de movimentações
Toda movimentação de fiado (débito e crédito) DEVE gerar registro no AuditLog.

#### Scenario: Audit trail de recebimento
- **WHEN** o operador registra recebimento de fiado
- **THEN** o sistema insere entrada no AuditLog com EventType="FiadoRecebimento", EntityType="FiadoMovimentos", EntityId, IP e UserId.

#### Scenario: Audit trail de venda fiado
- **WHEN** uma venda com fiado é registrada
- **THEN** o sistema insere entrada no AuditLog com EventType="FiadoDebito", EntityType="FiadoMovimentos", EntityId, IP e UserId.
