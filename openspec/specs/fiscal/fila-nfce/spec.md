# fiscal/fila-nfce Specification

## Purpose

Fronteira fiscal da venda na API Java: decisão de emitir NFC-e por venda, nota pendente com número reservado e uma
fila fiscal mínima, sem comunicação com a SEFAZ, até a emissão chegar na Fase 7.

## Requirements

### Requirement: Decisão de emissão por venda
Cada venda registrada SHALL decidir uma única vez se emite NFC-e, pelas regras de emissão da empresa (controle
desligado emite todas; CPF na nota; formas de pagamento "sempre emitir"; uma nota a cada N vendas com contador único),
e guardar a decisão na venda. Um replay MUST NOT avançar o contador de novo.

#### Scenario: Intervalo de 3 vendas
- **WHEN** a empresa emite uma nota a cada 3 vendas em dinheiro e registra 3 vendas em dinheiro
- **THEN** só a terceira tem `emitirFiscal` true e o contador volta a zero

#### Scenario: Replay não conta
- **WHEN** uma venda já registrada é reenviada com o mesmo `eventId`
- **THEN** `emitirFiscal` repete a decisão original e o contador não muda

### Requirement: Nota pendente com número reservado
Quando a venda emite, a API SHALL gravar uma NFC-e pendente para a venda com série, ambiente e o próximo número da
sequência fiscal da empresa, sem duplicar a nota num replay. Venda cancelada MUST NOT gerar nova NFC-e. Falha ao gravar
a pendência SHALL responder 503 com a venda já guardada.

#### Scenario: Venda que emite
- **WHEN** uma venda com `emitirFiscal` true é registrada
- **THEN** existe uma NFC-e pendente da venda com o próximo número da série e `fiscalQueued` é true

#### Scenario: Falha ao enfileirar
- **WHEN** a gravação da pendência falha depois de a venda ser gravada
- **THEN** a resposta é 503 com "Venda guardada; a preparação da nota fiscal será retomada automaticamente." e
  `saleNumber` e `vendaId` em `data`

### Requirement: Fila fiscal mínima sem SEFAZ
Até a Fase 7, uma rotina periódica SHALL: gravar a pendência das vendas que decidiram emitir e ficaram sem nota; tirar
da fila, como não emitida, a nota não assinada de venda cancelada, com aviso para inutilizar o número; e marcar erro na
nota de empresa sem emitente configurado. Ela MUST NOT transmitir, assinar nem inutilizar na SEFAZ.

#### Scenario: Venda cancelada com nota na fila
- **WHEN** uma venda com NFC-e pendente é cancelada
- **THEN** em poucos segundos a nota fica com status 4 e motivo contendo "não emitida" e o número a inutilizar

#### Scenario: Empresa sem emitente
- **WHEN** há nota pendente de empresa sem emitente fiscal
- **THEN** a nota fica com o erro "Empresa sem certificado ou CSC configurado." e sem nova tentativa marcada

#### Scenario: Nota de empresa com emitente
- **WHEN** há nota pendente de empresa com emitente configurado
- **THEN** a nota continua pendente, sem transmissão, até a Fase 7

### Requirement: Rotina fiscal desligável
A fila fiscal mínima SHALL poder ser desligada por configuração, para que a API Java e a API .NET não processem a
mesma fila sobre o mesmo banco.

#### Scenario: Rotina desligada
- **WHEN** a configuração da fila fiscal está desligada
- **THEN** nenhuma nota pendente muda de status pela API Java
