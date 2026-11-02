# Spec Delta

## Purpose

Critérios que a biblioteca Java de NF-e/NFC-e precisa cumprir, comprovados por uma prova de conceito, antes de a
emissão fiscal ser portada para a API Java.

## ADDED Requirements

### Requirement: NFC-e 4.00 válida
A prova de conceito SHALL montar e assinar uma NFC-e modelo 65 versão 4.00 com QR Code (CSC), a partir de uma venda de
exemplo equivalente às que a API .NET emite, e o XML SHALL validar nos XSD oficiais.

#### Scenario: XML válido
- **WHEN** a PoC gera a NFC-e de uma venda com dois itens, desconto e pagamento em dinheiro e PIX
- **THEN** o XML assinado passa na validação dos XSD 4.00 e traz `infNFeSupl` com QR Code e URL de consulta

### Requirement: Reforma tributária
A NFC-e gerada SHALL conter os grupos de IBS/CBS (CST IBS/CBS e cClassTrib do cadastro do produto) exigidos pelo
leiaute vigente usado pela API .NET.

#### Scenario: Item com IBS/CBS
- **WHEN** um item tem `CstIbsCbs` e `cClassTrib` preenchidos
- **THEN** o XML do item contém o grupo de IBS/CBS com esses valores

### Requirement: Certificado A1 em memória
A biblioteca SHALL assinar e transmitir usando o certificado A1 (PFX e senha) carregado em memória, sem gravar o
certificado em disco.

#### Scenario: Certificado vindo do banco
- **WHEN** a PoC recebe o PFX em bytes e a senha
- **THEN** a assinatura e a conexão com a SEFAZ funcionam sem arquivo de certificado no disco

### Requirement: Operações na SEFAZ de homologação
Com certificado e CSC de homologação, a biblioteca SHALL autorizar, consultar, cancelar e inutilizar NFC-e, e gerar
NFC-e em contingência offline (tpEmis 9) para transmissão posterior.

#### Scenario: Ciclo completo em homologação
- **WHEN** a PoC roda com certificado e CSC de homologação
- **THEN** uma NFC-e é autorizada, consultada e cancelada, uma faixa de numeração é inutilizada e uma nota em contingência é gerada e depois autorizada

### Requirement: Relatório de decisão
A prova de conceito SHALL produzir um relatório com o resultado de cada critério e a recomendação (seguir com a
biblioteca, ou usar a alternativa de montar o XML pelos XSD e usar a biblioteca só para assinar e transmitir).

#### Scenario: Critério não atendido
- **WHEN** um critério falha
- **THEN** o relatório registra a falha, a evidência e a alternativa proposta
