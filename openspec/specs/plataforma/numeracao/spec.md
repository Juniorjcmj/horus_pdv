# plataforma/numeracao Specification

## Purpose

Numeração de documentos da empresa (venda, sequência fiscal e inventário) gerada por um único ponto da API, para que,
no modo loja, só a autoridade da empresa numere e os IDs derivados do número nunca colidam.

## Requirements

### Requirement: Número da venda
O número da venda SHALL ser o maior número numérico já usado na empresa mais um (base 15039 para a empresa
`empresa-principal` sem vendas, 0 para as demais), gerado de forma serializada por empresa na transação da venda. O
ID da venda SHALL ser `sale-{empresa}-{número}`, com itens e pagamentos derivados dele, como na API .NET.

#### Scenario: Primeira venda de uma empresa nova
- **WHEN** uma empresa sem vendas registra a primeira venda
- **THEN** o `saleNumber` é "1" e o `vendaId` é `sale-<empresa>-1`

#### Scenario: Vendas simultâneas
- **WHEN** dois caixas da mesma empresa registram vendas ao mesmo tempo
- **THEN** cada venda recebe um número diferente e consecutivo, sem erro de chave duplicada

### Requirement: Numeração fiscal e de inventário
O próximo número da NFC-e (por empresa, modelo, série e ambiente) e o número do inventário SHALL ser gerados pelo mesmo
ponto único de numeração, com as mesmas regras da API .NET.

#### Scenario: Duas notas seguidas
- **WHEN** duas vendas que emitem NFC-e são registradas na mesma série
- **THEN** as notas pendentes têm números consecutivos

#### Scenario: Inventário numerado
- **WHEN** um inventário é aberto depois do inventário 7
- **THEN** o número do novo inventário é 8, como antes desta fase

### Requirement: Um só caminho para numerar
Nenhuma outra parte da API SHALL calcular número de venda, número fiscal ou número de inventário. Uma verificação
automática do código SHALL falhar quando aparecer outro cálculo.

#### Scenario: Cálculo fora do ponto único
- **WHEN** um código novo calcula `MAX(SaleNumber)` fora do ponto de numeração
- **THEN** o teste de arquitetura falha
