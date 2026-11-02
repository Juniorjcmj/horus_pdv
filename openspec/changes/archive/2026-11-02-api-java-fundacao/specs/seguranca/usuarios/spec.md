# Spec Delta

## Purpose

Gestão dos usuários de cada empresa na API Java (cadastro, edição, situação, supervisores e redefinição de senha pelo
gestor), com as mesmas regras de perfis e de isolamento por empresa da API .NET.

## ADDED Requirements

### Requirement: Acesso à gestão de usuários
As rotas de `api/Usuario` SHALL exigir perfil administrador ou gerente, exceto `GET /api/Usuario/supervisores`, que
também aceita atendente e caixa; toda operação SHALL ficar restrita à empresa do usuário logado.

#### Scenario: Caixa tenta listar usuários
- **WHEN** um usuário com perfil caixa chama `GET /api/Usuario`
- **THEN** a resposta é 403 com "Usuário sem permissão para esta ação."

#### Scenario: Outra empresa
- **WHEN** um gerente pede para editar um usuário de outra empresa
- **THEN** a resposta é 404 com "Usuário não encontrado."

### Requirement: Listar usuários e supervisores
`GET /api/Usuario` SHALL listar os usuários da empresa por nome; `GET /api/Usuario/supervisores` SHALL listar só
administradores e gerentes ativos (id, nome e perfil), usados para autorizações no caixa.

#### Scenario: Supervisores
- **WHEN** um atendente chama `GET /api/Usuario/supervisores`
- **THEN** a resposta traz apenas usuários ativos com perfil administrador ou gerente, com `id`, `name` e `role`

### Requirement: Criar usuário
`POST /api/Usuario` SHALL criar o usuário com CPF/CNPJ (11 ou 14 dígitos), nome, e-mail válido e senha de 8+
caracteres, perfil padrão atendente, `mustChangePassword` verdadeiro, e-mail único no sistema e CPF único na empresa.

#### Scenario: Usuário criado
- **WHEN** um gerente envia dados válidos
- **THEN** a resposta é 201 com "Usuario criado com sucesso." e o usuário, com id no formato `usr-<milissegundos>`

#### Scenario: CPF repetido na empresa
- **WHEN** já existe usuário com o mesmo CPF na empresa
- **THEN** a resposta é 400 com "Já existe um usuário com este CPF nesta empresa."

### Requirement: Perfis adicionais só pelo administrador
Os perfis adicionais (hoje só "financeiro") SHALL ser definidos apenas por administrador; quando quem cria ou edita não
é administrador, os perfis adicionais enviados são ignorados e os atuais mantidos.

#### Scenario: Gerente tenta dar financeiro
- **WHEN** um gerente cria um usuário com `perfisAdicionais: ["financeiro"]`
- **THEN** o usuário é criado sem perfis adicionais

### Requirement: Editar usuário
`PUT /api/Usuario/{id}` SHALL atualizar os dados mantendo a senha quando ela vier vazia; senha nova marca
`mustChangePassword`; usuário que passa a inativo SHALL ter todas as sessões encerradas.

#### Scenario: Inativar pela edição
- **WHEN** a edição muda a situação para "inativo"
- **THEN** o usuário é salvo e todas as sessões dele são apagadas

### Requirement: Alterar situação
`PATCH /api/Usuario/{id}/status` SHALL aceitar "ativo" ou "inativo" (qualquer outro valor vira "ativo") e encerrar as
sessões ao inativar.

#### Scenario: Inativar usuário
- **WHEN** o gestor envia `{"status": "inativo"}`
- **THEN** a resposta é 200 com "Status atualizado com sucesso." e o usuário não consegue mais usar o token atual

### Requirement: Redefinição de senha pelo gestor
`POST /api/Usuario/{id}/resetar-senha` SHALL gerar um token de redefinição, marcar `mustChangePassword`, encerrar as
sessões do usuário e enviar o link por e-mail.

#### Scenario: E-mail desligado em produção
- **WHEN** o envio de e-mail está desligado fora de desenvolvimento
- **THEN** a resposta é 503 com "Reset de senha indisponível. Configure o envio de e-mail."

#### Scenario: Falha no envio
- **WHEN** o servidor de e-mail recusa o envio
- **THEN** o token é invalidado e a resposta é 502 com "Não foi possível enviar o e-mail de redefinição de senha."
