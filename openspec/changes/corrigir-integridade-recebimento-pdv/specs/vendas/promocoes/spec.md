## ADDED Requirements

### Requirement: Reconciliação do checkout após alteração do carrinho
O sistema MUST recalcular itens líquidos e total ao adicionar/remover produto ou alterar quantidade. Se itens, descontos ou total mudarem após o início do pagamento, inclusive por atualização de promoções, MUST invalidar a confirmação da composição anterior e exigir nova conferência antes de finalizar, preservando referência dos valores já informados. MUST NOT apagar silenciosamente pagamentos efetivados, estornar ou cobrar novamente automaticamente. Atualização sem alteração financeira MUST preservar o checkout.

#### Scenario: Remoção antes do pagamento
- **WHEN** um carrinho R$ 87,62 perde um item líquido de R$ 5,14
- **THEN** o novo total é R$ 82,48 e o payload não contém o item removido

#### Scenario: Voltar e alterar promoção ou quantidade
- **WHEN** o operador volta do pagamento e altera quantidade ou remove item que sustentava um combo
- **THEN** a promoção é recalculada e o novo pagamento usa os itens e total atuais, sem parcelas anteriores

#### Scenario: Atualização assíncrona durante pagamento
- **WHEN** consulta periódica ou foco da janela altera a promoção efetiva de um item durante o pagamento
- **THEN** a finalização antiga é bloqueada, o operador é informado da atualização e reconfirma o carrinho e os pagamentos
