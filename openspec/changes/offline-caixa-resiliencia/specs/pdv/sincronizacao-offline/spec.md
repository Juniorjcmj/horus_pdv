## Purpose

Garante que todo evento gravado offline no PDV ou no Local Gateway (vendas, abertura, movimentação e
fechamento de caixa) chegue à nuvem quando a conexão voltar, sem intervenção manual, e que falhas reais
fiquem visíveis.

## ADDED Requirements

### Requirement: Falha transitória nunca abandona o evento
O Gateway e o PDV SHALL tratar como transitória toda falha de rede, timeout, HTTP 408, 429, 5xx, 401 e
403, e SHALL continuar tentando enviar o evento indefinidamente, com intervalo crescente limitado a no
máximo 5 minutos entre tentativas. Uma falha transitória MUST NOT levar o evento ao estado `FAILED`.

#### Scenario: Queda prolongada de internet
- **WHEN** a internet fica indisponível por 15 horas e cada tentativa de envio falha por rede
- **THEN** os eventos permanecem pendentes de envio, com tentativas espaçadas em no máximo 5 minutos
- **AND** ao voltar a internet todos são enviados sem ação do operador

#### Scenario: Token recusado temporariamente
- **WHEN** a nuvem responde 401 ou 403 a um envio
- **THEN** o evento continua pendente e é reenviado nas tentativas seguintes

### Requirement: Somente recusa de negócio marca o evento como falho
O Gateway e o PDV SHALL marcar um evento como `FAILED` apenas quando a nuvem responder 409 ou outro 4xx
que não seja 401, 403, 408 ou 429, guardando a mensagem retornada como motivo.

#### Scenario: Fechamento recusado por diferença sem justificativa
- **WHEN** a nuvem responde HTTP 400 "Informe uma justificativa antes de fechar o caixa"
- **THEN** o evento fica `FAILED` com essa mensagem como motivo
- **AND** não é reenviado automaticamente

### Requirement: Eventos abandonados por versão anterior são recuperados
Ao iniciar, o Gateway SHALL devolver à fila de envio os eventos `FAILED` cujo motivo seja falha
transitória (rede, timeout, 408, 429, 5xx, 401, 403), preservando o EventId.

#### Scenario: Atualização do Gateway com eventos presos
- **WHEN** o Gateway atualizado inicia com eventos `FAILED` de motivo "rede indisponível"
- **THEN** esses eventos voltam a pendentes e são enviados na próxima conexão
- **AND** eventos `FAILED` por recusa de negócio permanecem `FAILED`

### Requirement: Painel não indica sincronização completa com falhas
O painel do Gateway e a checagem de atualização segura SHALL considerar não sincronizado qualquer
estado com eventos `FAILED`, exibindo a quantidade e o motivo de cada falha.

#### Scenario: Falhas sem pendentes
- **WHEN** há 0 eventos pendentes e 6 eventos `FAILED`
- **THEN** o painel não exibe "tudo sincronizado" nem "seguro para atualizar"
- **AND** exibe "6 eventos com falha" e seus motivos

### Requirement: Histórico mostra vendas ainda não sincronizadas
O histórico de vendas do PDV SHALL exibir, junto das vendas da nuvem, as vendas guardadas no terminal
que ainda não foram aceitas pela nuvem, identificadas como pendentes de envio, sem duplicar uma venda
já presente na nuvem (mesmo `clientSaleId`).

#### Scenario: Internet voltou com vendas ainda na fila
- **WHEN** a internet volta e 10 vendas locais ainda não foram aceitas pela nuvem
- **THEN** o histórico lista as vendas da nuvem mais as 10 locais marcadas como "pendente de envio"

#### Scenario: Venda já sincronizada
- **WHEN** uma venda local já foi aceita pela nuvem
- **THEN** ela aparece uma única vez no histórico
