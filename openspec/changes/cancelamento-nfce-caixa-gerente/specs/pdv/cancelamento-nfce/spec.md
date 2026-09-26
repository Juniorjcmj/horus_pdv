## Purpose

Permite a busca, conferência visual, autorização presencial por senha de gerente e cancelamento formal de NFC-e perante a SEFAZ diretamente na frente de caixa, com estorno automático de estoque e emissão de comprovante.

## ADDED Requirements

### Requirement: Busca e Identificação de NFC-e no Caixa

The system SHALL allow the POS cashier to enter or scan the sale identifier, NFC-e number, or 44-digit access key, and upon pressing Enter, fetch and display the authorized fiscal document details.

#### Scenario: Busca com sucesso de NFC-e autorizada
- **WHEN** o operador informa um código de venda, número da nota ou chave de acesso e pressiona Enter
- **THEN** o sistema localiza a nota, exibe na tela o número, série, valor total, cliente, protocolo e data/hora de emissão, confirmando que a nota está apta a cancelamento

#### Scenario: Busca de nota inexistente
- **WHEN** o operador informa um código inexistente e pressiona Enter
- **THEN** o sistema exibe alerta informando que nenhum documento fiscal foi encontrado com os dados informados

#### Scenario: Busca de nota já cancelada ou denegada
- **WHEN** o operador busca uma NFC-e cujo status já seja Cancelado, Inutilizado ou Denegado
- **THEN** o sistema apresenta o estado atual do documento e bloqueia o acionamento do fluxo de cancelamento

### Requirement: Seleção e Validação de Senha de Gerente

The system SHALL provide a dropdown list of active managers and administrators for the company and MUST require the selected manager's password to authorize the cancellation.

#### Scenario: Listagem de gerentes para o caixa
- **WHEN** o operador de caixa acessa a etapa de autorização
- **THEN** o sistema carrega no campo `select` exclusivamente os usuários ativos da empresa que possuem perfil de gerente ou administrador

#### Scenario: Autorização com senha de gerente válida
- **WHEN** o operador seleciona um gerente válido na lista, digita a respectiva senha correta e confirma
- **THEN** o backend valida com sucesso as credenciais do gerente e autoriza a execução do cancelamento fiscal

#### Scenario: Tentativa com senha incorreta
- **WHEN** a senha digitada não confere com as credenciais do gerente selecionado
- **THEN** o sistema rejeita a operação com erro de autorização, não transmite nenhum evento à SEFAZ e notifica o operador

### Requirement: Justificativa Legal SEFAZ e Homologação Fiscal

The system SHALL require a cancellation justification of at least 15 characters, provide 1-click suggested reasons, and MUST transmit the formal cancellation event to SEFAZ and record manager authorization audit.

#### Scenario: Seleção rápida de motivo pré-definido
- **WHEN** o operador ou gerente clica em um botão de motivo sugerido (ex: "Desistência da compra pelo cliente")
- **THEN** o campo de justificativa é preenchido com o texto selecionado, satisfazendo a regra de 15 caracteres mínimos

#### Scenario: Cancelamento homologado com sucesso pela SEFAZ
- **WHEN** a autorização do gerente é confirmada e uma justificativa válida com no mínimo 15 caracteres é enviada
- **THEN** o sistema transmite o evento à SEFAZ, registra a resposta homologada, atualiza o status da nota para Cancelado, salva o XML de cancelamento e vincula o gerente autorizador à auditoria do evento

#### Scenario: Rejeição da SEFAZ por decurso de prazo
- **WHEN** a solicitação é transmitida após o encerramento do prazo regulamentar estadual de cancelamento da NFC-e
- **THEN** o sistema exibe o motivo de rejeição retornado pelo fisco, mantém o documento fiscal no status atual e orienta o operador

### Requirement: Estorno Automático de Estoque e Venda Cancelada

The system SHALL automatically revert the stock quantities of the cancelled sale items and MUST mark the corresponding sale record as canceled.

#### Scenario: Reversão automática de estoque após homologação fiscal
- **WHEN** a NFC-e é cancelada com sucesso na SEFAZ
- **THEN** o sistema repõe a quantidade de cada produto vendido de volta ao saldo de estoque e atualiza o status da venda para cancelada

### Requirement: Comprovante de Cancelamento na Impressora Térmica

The system SHALL prompt the cashier after successful cancellation offering the option to print a cancellation voucher.

#### Scenario: Impressão sob demanda do comprovante de cancelamento
- **WHEN** o cancelamento é homologado na SEFAZ
- **THEN** o sistema exibe a opção de imprimir o comprovante de cancelamento contendo dados da nota, protocolo de homologação SEFAZ, valor estornado e gerente autorizador
