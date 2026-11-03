## Why

O Gateway existente persiste e encaminha eventos, mas não decide crédito: cada PDV valida seu cache e a API verifica o saldo central apenas ao registrar a venda. Dois caixas desconectados podem aprovar fiado sobre o mesmo disponível; instalar o Gateway no caixa principal só resolve esse problema se ele passar a arbitrar crédito com recursos previamente reservados pela cloud.

## What Changes

- Adicionar autorização durável e atômica de fiado no Gateway, compartilhada pelos terminais da loja, com reserva, confirmação vinculada à venda, cancelamento e consulta de resultado.
- Reservar na cloud um orçamento por empresa/cliente/Gateway/loja, reduzindo o disponível das demais vendas e lojas; não tratar cache de saldo como autorização.
- Integrar a decisão ao PDV e à reconciliação existente, mantendo EventId, PayloadHash, SQLite, outbox, registro de terminais, dispatcher cloud, dashboard e SignalR.
- **BREAKING (apenas lojas habilitadas):** fiado compartilhado exige Gateway acessível e orçamento válido, inclusive com internet no terminal; ausência de autoridade bloqueia fiado, preservando outras formas de pagamento.
- Operar o serviço Windows existente no caixa principal independentemente da UI, com provisionamento, recuperação e observabilidade de crédito.
- Alinhar o requisito de limite zero ao bloqueio já implementado em SaleMoney.Credit; não introduzir crédito ilimitado.

## Capabilities

### New Capabilities

- `vendas/autorizacao-fiado-gateway`: orçamento delegado, arbitragem local, protocolo de autorização e venda, reconciliação, isolamento e operação do serviço.

### Modified Capabilities

- `vendas/fiado-conta-corrente`: validação respeita orçamentos delegados e autorizações, sem dupla cobrança; limite zero bloqueia conforme código atual.

## Impact

API/NETCORE: crédito, cadastro/alteração de limite, recebimentos, registro/cancelamento de vendas e pedidos, migrações e autenticação do Gateway. GATEWAY: SQLite, serviços de crédito, ingestão, sincronização, contratos, dashboard e instalação/documentação. FRONTEND: fechamento de venda, cache de clientes, outbox, sincronização, estados de resultado incerto e mensagens operacionais. Implantação opt-in por loja com versões compatíveis e drenagem do legado antes da ativação.

Fora do escopo: CRDT, malha entre caixas, HA automático, mudanças de estoque, TEF, nova regra fiscal, execução da implementação nesta proposta. O Gateway continua sendo o serviço existente; o PDV do caixa principal é somente mais um cliente.
