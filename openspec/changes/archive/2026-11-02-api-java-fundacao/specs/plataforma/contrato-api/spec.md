# Spec Delta

## Purpose

Garante que a API Java responda aos clientes do Quack PDV (web, desktop, Caixa, Quack Estoque, Gateway) exatamente como
a API .NET responde hoje, para que a troca de API não exija mudança nos clientes.

## ADDED Requirements

### Requirement: Envelope de resposta
Toda resposta de rota da API SHALL ser um objeto JSON com `success` (booleano), `message` (texto), `details` (texto ou
nulo) e `data` (qualquer valor ou nulo), inclusive nas respostas de erro de negócio, de autenticação e de autorização.

#### Scenario: Sucesso com dados
- **WHEN** um cliente autenticado chama `GET /api/Sessao`
- **THEN** a resposta é 200 com `{"success": true, "message": "Sessoes obtidas com sucesso.", "details": null, "data": [...]}`

#### Scenario: Erro de negócio
- **WHEN** `POST /api/Auth/login` chega sem e-mail
- **THEN** a resposta é 400 com `success` falso, `message` "Informe e-mail e senha.", `details` nulo e `data` nulo

### Requirement: Nomes e nulos no JSON
Os campos do JSON SHALL usar camelCase com os mesmos nomes da API .NET, e campos sem valor SHALL ser enviados como
`null` (não omitidos).

#### Scenario: Usuário serializado
- **WHEN** a API devolve um usuário
- **THEN** o objeto tem exatamente `id, companyId, cpf, name, email, phone, role, status, createdAt, lastLoginAt, mustChangePassword, perfisAdicionais`

### Requirement: Rotas sem diferenciar maiúsculas
A API SHALL atender as rotas sem diferenciar maiúsculas de minúsculas e com ou sem barra final, como a API .NET.

#### Scenario: Caminho em minúsculas
- **WHEN** um cliente chama `POST /api/auth/login`
- **THEN** a rota de login responde como em `POST /api/Auth/login`

#### Scenario: Barra final
- **WHEN** um cliente chama `GET /api/Sessao/`
- **THEN** a resposta é a mesma de `GET /api/Sessao`

### Requirement: Erro inesperado
Um erro não tratado SHALL responder 500 com `{"success": false, "message": "Erro interno no servidor."}` em produção,
sem detalhes internos.

#### Scenario: Falha interna
- **WHEN** uma rota lança um erro não previsto em produção
- **THEN** a resposta é 500 com a mensagem genérica e sem pilha de erro

### Requirement: Corpo inválido
Um corpo JSON malformado ou com tipo incompatível SHALL responder 400 no formato ProblemDetails (`type`, `title`,
`status`, `errors`), como a API .NET faz para falhas de leitura do corpo.

#### Scenario: JSON quebrado
- **WHEN** `POST /api/Usuario` recebe `{"name": ` (JSON incompleto)
- **THEN** a resposta é 400 com `status` 400 e `title` descrevendo erro de validação

### Requirement: Gravação do contrato .NET
O projeto SHALL ter uma ferramenta que chama rotas da API .NET num ambiente de teste e grava requisição e resposta
(status, cabeçalhos relevantes e JSON) por rota.

#### Scenario: Gravar rotas de autenticação
- **WHEN** o gravador roda contra a API .NET de teste para o grupo `auth`
- **THEN** um arquivo JSON por cenário é salvo com a requisição, o status e o corpo da resposta

### Requirement: Comparação de contrato
O projeto SHALL ter um teste que repete os cenários gravados na API Java e falha quando status, nomes de campos, tipos
ou valores diferem, ignorando campos voláteis declarados por cenário (ids, datas, tokens).

#### Scenario: Resposta compatível
- **WHEN** o comparador roda o cenário "login válido" na API Java
- **THEN** o teste passa se o status e o JSON batem com a gravação, exceto `data.sessionId` e `data.user.lastLoginAt`

#### Scenario: Campo divergente
- **WHEN** a API Java devolve `mustChangePassword` como texto em vez de booleano
- **THEN** o comparador falha apontando o campo e os dois valores
