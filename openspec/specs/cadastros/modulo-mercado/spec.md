# cadastros/modulo-mercado Specification

## Purpose

Módulos operacionais fixos do mercado (fiscal, pagamentos, estoque, caixa, compras, devoluções, CRM e omnichannel),
cada um com registros por empresa e um painel de indicadores, fluxo e alertas calculado a partir desses registros,
servidos pela API Java com o mesmo contrato da API .NET.

## Requirements

### Requirement: Módulos conhecidos
`api/ModuloMercado` SHALL aceitar administrador, gerente e atendente e SHALL reconhecer só os módulos `fiscal`,
`pagamentos`, `estoque`, `caixa`, `compras`, `devolucoes`, `crm-fidelidade` e `omnichannel`, com os títulos e a ação
principal da API .NET; qualquer outro id SHALL responder 404 com "Módulo não encontrado.".

#### Scenario: Módulo desconhecido
- **WHEN** um gerente chama `GET /api/ModuloMercado/financeiro`
- **THEN** a resposta é 404 com "Módulo não encontrado."

#### Scenario: Caixa sem acesso
- **WHEN** um caixa chama `GET /api/ModuloMercado/estoque`
- **THEN** a resposta é 403

### Requirement: Painel do módulo
`GET /api/ModuloMercado/{id}` SHALL devolver, com "Modulo obtido com sucesso.", o painel do módulo com título,
descrição, ação principal, os registros da empresa e os indicadores Volume, Pendências, Concluídos (%) e Alertas, mais
o fluxo e os alertas, com os mesmos textos e regras de contagem da API .NET.

#### Scenario: Pendências e concluídos
- **WHEN** o módulo `compras` da empresa tem 4 registros, com situação "Pendente", "Aguardando", "Concluído" e "Em
  andamento"
- **THEN** Volume é "4", Pendências é "2", Concluídos é "25%", Alertas é "2" e o primeiro alerta é "Existem 2
  pendência(s) aguardando conferência."

#### Scenario: Módulo sem registros
- **WHEN** o módulo não tem registros na empresa
- **THEN** Concluídos é "0%", Alertas é "1" e o primeiro alerta é "Nenhuma pendência crítica no momento."

### Requirement: Registros do módulo
`POST /api/ModuloMercado/{id}/registros` SHALL criar o registro com id `mm-<milissegundos>` e responder 201 com
"Registro criado com sucesso."; `PUT` e `DELETE` em `/{id}/registros/{recordId}` SHALL responder "Registro atualizado
com sucesso." e "Registro removido com sucesso."; todas devolvem o painel atualizado.

#### Scenario: Título curto
- **WHEN** o registro é enviado com título "ab"
- **THEN** a resposta é 400 com "Titulo deve ter no minimo 3 caracteres."

#### Scenario: Sem situação
- **WHEN** o registro é enviado com situação vazia
- **THEN** a resposta é 400 com "Status e obrigatorio."

#### Scenario: Registro de outra empresa
- **WHEN** um gerente tenta alterar um registro que pertence a outra empresa
- **THEN** a resposta é 404 com "Registro não encontrado."
