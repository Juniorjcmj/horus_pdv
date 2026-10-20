## Purpose

Permite abrir, movimentar e fechar o caixa sem internet e reconciliar depois na nuvem a mesma sessão,
com os valores e horários reais do fato, sem afetar caixas de outros dias ou operadores.

## ADDED Requirements

### Requirement: Abertura offline preserva a identidade e o horário da sessão
Quando uma abertura de caixa chegar à nuvem com identificador de sessão do cliente e horário de abertura,
a nuvem SHALL criar a sessão com esse identificador e esse horário. Reenviar a mesma abertura MUST ser
idempotente.

#### Scenario: Abertura feita offline sincronizada depois
- **WHEN** o caixa foi aberto offline às 06:12 com sessão `cx-offline-123` e o evento chega às 11:00
- **THEN** a nuvem registra a sessão `cx-offline-123` aberta às 06:12
- **AND** as vendas com `caixaSessaoId = cx-offline-123` contam para essa sessão

### Requirement: Fechamento sincronizado fecha somente a sessão indicada
Quando um fechamento chegar com identificador de sessão do cliente, a nuvem SHALL fechar exatamente essa
sessão e MUST NOT fechar outra sessão aberta do operador. O dinheiro esperado SHALL ser calculado com
vendas e movimentos dessa sessão até o horário do fechamento informado.

#### Scenario: Fechamento antigo com outro caixa aberto hoje
- **WHEN** chega o fechamento da sessão de 19/10 e o operador tem outra sessão aberta em 20/10
- **THEN** somente a sessão de 19/10 é fechada, com o esperado calculado até 19:03 de 19/10
- **AND** a sessão de 20/10 continua aberta e inalterada

#### Scenario: Sessão indicada não existe na nuvem
- **WHEN** o fechamento indica uma sessão que a nuvem não conhece
- **THEN** a nuvem recusa com mensagem que identifica a sessão ausente
- **AND** nenhuma outra sessão é fechada

#### Scenario: Fechamento já aplicado
- **WHEN** o mesmo fechamento (mesmo EventId) é reenviado
- **THEN** a nuvem responde com o resultado já registrado, sem alterar a sessão

#### Scenario: Cliente antigo sem identificador de sessão
- **WHEN** um fechamento chega sem identificador de sessão
- **THEN** a nuvem mantém o comportamento atual (sessão aberta do operador, esperado até agora)

### Requirement: Cadeia de caixa respeita a ordem por terminal
Enquanto um evento de caixa (abertura, movimentação ou fechamento) de um terminal estiver `FAILED`, o
Gateway e o PDV SHALL reter os eventos de caixa posteriores do mesmo terminal, sem reter vendas.

#### Scenario: Fechamento recusado seguido de nova abertura
- **WHEN** o fechamento de 19/10 está `FAILED` e existe a abertura de 20/10 do mesmo terminal na fila
- **THEN** a abertura de 20/10 não é enviada até o fechamento ser resolvido
- **AND** as vendas de 20/10 continuam sendo enviadas

### Requirement: Recusa de fechamento pode ser resolvida no PDV
O PDV SHALL listar os fechamentos recusados com o motivo e o valor esperado informado pela nuvem, e
SHALL permitir a usuário com perfil gerente ou administrador informar a justificativa e reenviar o mesmo
evento, que volta à fila e libera a cadeia do terminal quando aceito.

#### Scenario: Gerente justifica a diferença
- **WHEN** um fechamento está recusado por diferença sem justificativa e o gerente informa a justificativa
- **THEN** o evento é reenviado com a justificativa e o mesmo EventId
- **AND** ao ser aceito, os eventos de caixa retidos do terminal voltam a ser enviados

#### Scenario: Operador comum
- **WHEN** um operador sem perfil gerente abre a lista de recusas
- **THEN** ele vê o motivo, mas não consegue informar justificativa nem reenviar

### Requirement: Operador é informado do modo offline
Enquanto o PDV estiver sem conexão com a nuvem, SHALL exibir aviso permanente de que as vendas
continuam sendo registradas e guardadas no terminal. Ao fechar o caixa com eventos não enviados, o PDV
SHALL avisar quantos eventos estão pendentes antes de confirmar.

#### Scenario: Internet cai durante o expediente
- **WHEN** a conexão com a nuvem cai
- **THEN** o PDV mostra "Sem internet — pode continuar vendendo. As vendas estão guardadas neste caixa."

#### Scenario: Fechar caixa com pendências
- **WHEN** o operador confirma o fechamento com 66 eventos não enviados
- **THEN** o PDV avisa que há 66 pendências que serão enviadas quando a internet voltar e pede confirmação
