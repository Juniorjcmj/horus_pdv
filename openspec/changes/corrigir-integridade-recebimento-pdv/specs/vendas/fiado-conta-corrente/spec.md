## ADDED Requirements

### Requirement: Agregação integral das parcelas fiado
O sistema MUST somar todas as parcelas fiado da venda para exigir cliente, conferir limite e lançar um único débito integral, atualizar saldo e registrar auditoria atomicamente. Essa regra MUST valer para venda direta, finalização de pedido e sincronização, bloqueando qualquer parcela fiado quando o limite for zero e preservando idempotência.

#### Scenario: Duas parcelas fiado dentro do limite
- **WHEN** cliente com saldo R$ 100,00 e limite R$ 500,00 compra com parcelas fiado R$ 30,00 e R$ 50,00 e PIX R$ 20,00
- **THEN** há um débito R$ 80,00, saldo R$ 180,00 e auditoria correspondente, sem incluir PIX no débito

#### Scenario: Agregado excede limite
- **WHEN** cliente com saldo R$ 440,00 e limite R$ 500,00 tenta parcelas fiado R$ 30,00 e R$ 50,00
- **THEN** a venda inteira é rejeitada pelo agregado R$ 80,00 sem gravações parciais, mesmo que cada parcela isolada caiba no limite

#### Scenario: Retry e concorrência
- **WHEN** uma venda fiado é repetida ou duas vendas concorrentes disputam o limite restante
- **THEN** não há débito duplicado e o saldo não ultrapassa o limite pela aplicação concorrente das operações

### Requirement: Conferência obrigatória do crédito disponível
O sistema MUST conferir em toda compra com fiado que a soma de todas as parcelas fiado da operação cabe no crédito disponível: `max(0, limiteCredito - saldoDevedor)`, sem tolerância de um centavo. Na compra integralmente fiado, essa soma corresponde ao valor total da compra. MUST descontar do disponível as compras fiado locais pendentes ainda não refletidas no saldo, sem contá-las duas vezes. MUST reconferir após alterações no carrinho ou pagamentos e antes de confirmar, persistir ou enfileirar, com validação independente e atômica no servidor. Saldo ou limite indisponível não pode ser interpretado como crédito liberado. O bloqueio MUST informar crédito disponível, valor solicitado em fiado e insuficiência, sem efeitos parciais.

#### Scenario: Crédito restante de quarenta não cobre compra de cem
- **WHEN** o cliente tem limite R$ 100,00, saldo devedor R$ 60,00 e tenta compra de R$ 100,00 integralmente fiado
- **THEN** o sistema bloqueia a venda, informa disponível R$ 40,00 e insuficiência R$ 60,00 e não grava nem enfileira a nova compra.

#### Scenario: Valor exatamente igual ao disponível
- **WHEN** restam R$ 40,00 de crédito e a compra solicita R$ 40,00 em fiado
- **THEN** a validação de crédito permite o valor e o disponível passa a zero após o débito, respeitadas as demais regras.

#### Scenario: Um centavo acima do disponível
- **WHEN** restam R$ 40,00 e a soma das parcelas fiado é R$ 40,01
- **THEN** o sistema bloqueia por crédito insuficiente.

#### Scenario: Nova compra considera dívida offline pendente
- **WHEN** o saldo local já reflete uma compra fiado pendente de sincronização
- **THEN** a próxima compra considera esse consumo de crédito uma única vez e não reutiliza o limite disponível anterior.

### Requirement: Cadastro de crédito com bloqueio por limite zero
O sistema MUST permitir configurar limite de crédito por cliente e manter saldo devedor atualizado automaticamente. Limite zero MUST bloquear qualquer nova compra com parcela fiado, independentemente do saldo devedor, inclusive em pagamento misto, venda direta, finalização de pedido e operação offline.

#### Scenario: Configuração de limite de crédito
- **WHEN** o gerente edita o cliente "Maria Silva" e define LimiteCredito = R$500,00
- **THEN** o sistema persiste o limite e o utiliza para validação nas vendas fiado.

#### Scenario: Limite zero bloqueia compra fiado
- **WHEN** o cliente tem `LimiteCredito = 0` e a compra contém qualquer valor positivo em fiado
- **THEN** o sistema bloqueia a finalização e informa que o cliente não possui limite para comprar fiado, sem gravar ou enfileirar a nova venda nem lançar débito.

#### Scenario: Limite zero em pagamento misto ou offline
- **WHEN** o cliente com limite zero tenta compra parcialmente em fiado, inclusive por pedido ou sem conexão
- **THEN** o sistema bloqueia a compra enquanto houver parcela fiado, mesmo que as outras parcelas sejam em dinheiro, PIX ou cartão.

#### Scenario: Compra sem fiado com limite zero
- **WHEN** o cliente com limite zero paga integralmente em outros meios e atende às demais regras da venda
- **THEN** o limite de fiado zero não impede a compra.

## REMOVED Requirements

### Requirement: Campos de crédito no cadastro de cliente
**Reason**: O usuário definiu que limite zero bloqueia compras fiado, substituindo expressamente o cenário anterior de limite zero ilimitado.
**Migration**: Substituir por "Cadastro de crédito com bloqueio por limite zero" desta change, que preserva configuração de limite e atualização automática de saldo e define os cenários de bloqueio. Não alterar limites ou dívidas históricos.
