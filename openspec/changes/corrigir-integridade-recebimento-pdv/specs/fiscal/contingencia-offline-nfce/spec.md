## ADDED Requirements

### Requirement: Integridade monetária da contingência
O sistema MUST entregar ao emissor local valores monetários normalizados em centavos e coerentes entre itens, total, pagamentos declarados e troco. MUST rejeitar divergências antes de assinatura e preservar os mesmos valores no registro local e na sincronização posterior, sem relaxar validações fiscais.

#### Scenario: Troco aceito pelo emissor local
- **WHEN** uma venda R$ 8,80 paga com R$ 10,00 em dinheiro é emitida localmente
- **THEN** o emissor recebe e grava troco 1.20 e valor declarado 10.00, conciliados com 8.80, sem rejeição causada por ponto flutuante

#### Scenario: Divergência real rejeitada
- **WHEN** o emissor recebe pagamentos menos troco diferentes do total em R$ 0,01
- **THEN** rejeita antes de assinar ou gravar documento fiscal

#### Scenario: Sincronização do documento original
- **WHEN** uma contingência válida é sincronizada após restabelecimento da conexão
- **THEN** o documento e a venda mantêm seus valores originais, identidade, chave e numeração, sem nova emissão para ajustar valores
