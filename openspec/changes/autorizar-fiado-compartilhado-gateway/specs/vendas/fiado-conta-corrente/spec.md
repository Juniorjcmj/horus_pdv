## MODIFIED Requirements

### Requirement: Campos de crédito no cadastro de cliente
O sistema SHALL permitir configurar limite de crédito por cliente e manter saldo devedor atualizado automaticamente. Limite zero SHALL impedir novas compras fiado e novas concessões de orçamento. Alterações de limite SHALL respeitar dívida e compromissos delegados existentes.

#### Scenario: Configuração de limite de crédito
- **WHEN** o gerente edita o cliente "Maria Silva" e define LimiteCredito = R$500,00
- **THEN** o sistema persiste o limite e o utiliza para validação nas vendas fiado, observando compromissos delegados.

#### Scenario: Limite zero significa ilimitado
- **WHEN** o cliente tem LimiteCredito = 0 e não existe autorização anterior válida a reconciliar
- **THEN** o sistema impede nova compra fiado e não concede orçamento offline; a interpretação histórica de zero como ilimitado deixa de valer (título preservado para rastreabilidade da scenario existente).

### Requirement: Venda no fiado como forma de pagamento
O sistema SHALL permitir fiado como pagamento misto do PDV, exigir cliente e verificar crédito. Na cloud, crédito disponível SHALL excluir verbas delegadas ainda não consumidas ou devolvidas. Em loja habilitada para fiado compartilhado, vendas novas SHALL exigir autorização verificável do Gateway; reconciliação de autorização válida SHALL consumir orçamento reservado sem segunda aprovação pelo saldo corrente nem segundo débito.

#### Scenario: Venda fiado com cliente dentro do limite
- **WHEN** operador finaliza venda fiado de R$80 para Maria Silva com saldo R$100, limite R$500 e crédito aplicável disponível
- **THEN** o sistema registra venda, débito de R$80, saldo R$180 e NFC-e com tPag=05, consumindo a autorização quando aplicável.

#### Scenario: Venda fiado bloqueada por limite excedido
- **WHEN** operador tenta fiado R$150 com saldo R$400, limite R$500 e nenhum orçamento reservado que garanta essa compra
- **THEN** o sistema bloqueia e informa limite R$500, saldo R$400, disponível R$100 e insuficiência para a compra.

#### Scenario: Venda fiado sem cliente selecionado
- **WHEN** operador seleciona fiado sem informar cliente
- **THEN** o sistema exige cliente cadastrado antes de prosseguir.

#### Scenario: Pagamento misto com fiado
- **WHEN** operador finaliza venda R$100 com R$50 em dinheiro e R$50 fiado
- **THEN** o sistema mantém pagamento misto, movimenta e autoriza somente R$50 fiado e emite os dois detPag com tPag=01 e tPag=05.

#### Scenario: Compra cloud não usa verba delegada
- **WHEN** cliente tem disponível bruto R$100 e R$80 delegados a Gateway e tenta compra cloud de R$30 fora desse orçamento
- **THEN** a operação é bloqueada por disponível central R$20, embora o saldo bruto seja maior.

