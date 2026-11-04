# Spec Delta

## Purpose

Turno de caixa na API Java: abertura, fechamento, sangria, reforço, status e conferência por operador, com
idempotência para operações sincronizadas do modo offline, no mesmo contrato da API .NET.

## ADDED Requirements

### Requirement: Rotas do caixa no contrato da API .NET
As rotas `GET /api/Caixa/status`, `GET /api/Caixa/sessao/{id}`, `GET /api/Caixa/movimentos`, `POST /api/Caixa/abrir`,
`POST /api/Caixa/fechar` e `POST /api/Caixa/movimento` SHALL responder com o mesmo status, campos, formatos e mensagens
da API .NET, medidos pelo comparador de contrato. Toda operação SHALL ficar restrita à empresa do usuário logado.

#### Scenario: Sem sessão
- **WHEN** a rota `GET /api/Caixa/status` é chamada sem autenticação
- **THEN** a resposta é 401 com "Sessão não encontrada."

#### Scenario: Status igual ao gravado
- **WHEN** um cenário gravado de `GET /api/Caixa/status` é repetido na API Java
- **THEN** status, nomes de campos, tipos e valores coincidem com a gravação, salvo os campos voláteis declarados

### Requirement: Um turno aberto por operador
Cada operador SHALL ter no máximo um turno aberto por empresa; operadores diferentes SHALL poder ter turnos abertos ao
mesmo tempo (multi-caixa). O status SHALL informar se o operador pode vender e, quando não pode, o motivo.

#### Scenario: Dois operadores abrem caixa
- **WHEN** o operador A e o operador B abrem caixa na mesma empresa
- **THEN** os dois turnos ficam abertos e cada venda é vinculada ao turno do próprio operador

#### Scenario: Abrir de novo com turno aberto
- **WHEN** o operador com turno aberto chama `POST /api/Caixa/abrir` sem `eventId`
- **THEN** a resposta é a mesma da API .NET para caixa já aberto

### Requirement: Fechamento com conferência
O fechamento SHALL calcular o dinheiro esperado na gaveta (fundo de troco + vendas em dinheiro + reforços − sangrias
do turno, como a API .NET), registrar o valor contado, a diferença e a justificativa, e encerrar o turno.

#### Scenario: Fechamento com diferença
- **WHEN** o operador fecha informando valor contado diferente do esperado e uma justificativa
- **THEN** o turno fica fechado com esperado, contado, diferença e justificativa iguais ao que a API .NET grava

### Requirement: Sangria e reforço
`POST /api/Caixa/movimento` SHALL registrar sangria ou reforço no turno aberto do operador, com valor e motivo, e
gravar a trilha de auditoria correspondente.

#### Scenario: Sangria no turno aberto
- **WHEN** o operador registra uma sangria de R$ 50,00 com motivo
- **THEN** o movimento entra no turno e o dinheiro esperado do fechamento diminui R$ 50,00

### Requirement: Idempotência das operações de caixa offline
Abrir, fechar e movimentar com `eventId` SHALL ser idempotentes por empresa e evento: o mesmo `eventId` com o mesmo
conteúdo SHALL devolver a resposta original sem novo efeito; o mesmo `eventId` com conteúdo diferente SHALL ser
recusado com 409. O hash do conteúdo SHALL ser igual ao calculado pela API .NET e pelo Caixa JavaFX.

#### Scenario: Reenvio da abertura
- **WHEN** o Caixa reenvia a abertura com o mesmo `eventId` e o mesmo `payloadHash`
- **THEN** a resposta repete a original e não é criado outro turno

#### Scenario: Mesmo evento com outro valor
- **WHEN** chega um movimento com `eventId` já processado e valor diferente
- **THEN** a resposta é 409 e nada é gravado

#### Scenario: Evento já processado pela API .NET
- **WHEN** a API Java recebe o reenvio de um evento que a API .NET processou antes do corte, com o mesmo conteúdo
- **THEN** o hash coincide e a resposta é a original guardada
