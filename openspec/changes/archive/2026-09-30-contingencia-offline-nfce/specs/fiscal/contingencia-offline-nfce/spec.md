## Purpose

Permite a emissão, assinatura digital com certificado A1, impressão térmica e sincronização posterior de NFC-e (Modelo 65) em contingência offline (tpEmis = 9) durante indisponibilidades da SEFAZ ou falta de internet na loja.

## ADDED Requirements

### Requirement: Emissão de NFC-e em contingência offline (tpEmis = 9)
O sistema DEVE permitir a geração e assinatura de NFC-e Modelo 65 com tipo de emissão 9 (Contingência Off-line da NFC-e), calculando a chave de acesso de 44 dígitos com `tpEmis = 9`, incluindo obrigatoriamente a data/hora de contingência (`dhCont`) e justificativa (`xJust`) com no mínimo 15 caracteres, assinando o XML com o Certificado Digital A1 e gerando o QR Code da SEFAZ correspondente à contingência offline.

#### Scenario: Geração válida de NFC-e em contingência
- **WHEN** uma venda for finalizada em modo de contingência offline informando uma justificativa válida
- **THEN** o sistema gera o XML com `tpEmis = 9`, `dhCont` preenchido com a data/hora local, `xJust` com a justificativa, assina o XML via certificado A1 e gera a chave de 44 dígitos com o tipo 9 na posição 35.

#### Scenario: Rejeição de justificativa insuficiente
- **WHEN** uma emissão em contingência for solicitada com justificativa menor que 15 caracteres
- **THEN** o sistema rejeita a solicitação com erro descritivo exigindo o cumprimento do tamanho mínimo regulamentado pela SEFAZ.

### Requirement: Emissão e assinatura na rede local via Local Gateway
O sistema DEVE permitir que o terminal de PDV, ao detectar ausência de conexão com a internet, solicite a emissão da NFC-e em contingência offline diretamente ao `HorusGateway` na rede local (LAN), o qual assina o XML localmente com o certificado digital A1 provisionado, armazena no SQLite local e devolve o XML assinado e os dados do DANFE com QR Code para impressão térmica imediata.

#### Scenario: Emissão de NFC-e sem internet via Gateway local
- **WHEN** o caixa estiver sem conexão com a internet e solicitar a emissão da NFC-e
- **THEN** o PDV despacha a nota para o Local Gateway na LAN, que assina o XML com o certificado A1 local, persiste a nota como `ContingenciaPendente` no SQLite e devolve os dados de impressão térmica ao PDV.

#### Scenario: Queda simultânea de internet e Gateway local
- **WHEN** a loja estiver sem internet e o Local Gateway estiver indisponível
- **THEN** o PDV registra a venda offline na Outbox do IndexedDB para emissão posterior e emite comprovante de venda não fiscal com aviso de contingência ao consumidor.

### Requirement: Impressão do DANFE NFC-e térmico de contingência
O gerador de impressão térmica em bobina de 80mm DEVE renderizar com destaque oficial a tarja "EMITIDA EM CONTINGÊNCIA - Pendente de autorização", a data/hora da contingência, a justificativa legal, a chave de acesso de 44 dígitos e o QR Code gerado nos moldes de contingência offline.

#### Scenario: Impressão do cupom em contingência na bobina térmica
- **WHEN** um documento fiscal emitido com `tpEmis = 9` for enviado para impressão
- **THEN** o DANFE exibe em destaque a mensagem "EMITIDA EM CONTINGÊNCIA - Pendente de autorização", o motivo legal e o QR Code oficial offline.

### Requirement: Transmissão posterior em lote à SEFAZ e controle de prazo de 24h
O sistema DEVE manter uma rotina em background que monitora documentos fiscais com status `ContingenciaPendente` e, assim que o serviço da SEFAZ estiver restabelecido, transmite o lote para autorização, gravando o protocolo de homologação SEFAZ sem alterar a chave nem a numeração original, respeitando o prazo legal de 24 horas.

#### Scenario: Transmissão e autorização de notas em contingência
- **WHEN** a conexão com a SEFAZ for restabelecida e houver notas com status `ContingenciaPendente`
- **THEN** o sistema envia o XML assinado original para a SEFAZ, obtém o protocolo de autorização (`cStat 100`) e atualiza o status para `Autorizado`.

#### Scenario: Tratamento de duplicidade de nota em contingência (cStat 539)
- **WHEN** a SEFAZ responder com rejeição 539 informando que o documento já foi processado
- **THEN** o sistema consulta a chave na SEFAZ, recupera o protocolo correspondente e marca o documento como autorizado sem duplicar vendas ou numeração.
