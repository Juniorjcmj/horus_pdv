# Spec Delta

## Purpose

Material de treinamento da plataforma (seções e vídeos do YouTube), igual para todas as empresas: qualquer perfil
lê, e só o administrador geral da plataforma cria, edita e exclui, como na API .NET.

## ADDED Requirements

### Requirement: Consultar treinamentos
`GET /api/treinamento` SHALL aceitar administrador, gerente, atendente e caixa e devolver, com "Treinamentos obtidos
com sucesso.", `podeGerenciar` e as seções em ordem (ordem e nome), cada uma com seus vídeos em ordem (ordem e data de
criação). O material SHALL ser o mesmo para todas as empresas.

#### Scenario: Caixa vê os treinamentos
- **WHEN** um caixa de qualquer empresa consulta `GET /api/treinamento`
- **THEN** recebe as mesmas seções e vídeos que as outras empresas e `podeGerenciar` falso

### Requirement: Quem gerencia os treinamentos
Só o administrador geral SHALL criar, editar ou excluir seções e vídeos: perfil administrador da `empresa-principal`
ou administrador com e-mail na lista de donos da plataforma. Os demais SHALL receber 403 com "Somente o administrador
geral da plataforma pode editar os treinamentos.".

#### Scenario: Administrador de uma loja
- **WHEN** o administrador de uma empresa cliente chama `POST /api/treinamento/secoes`
- **THEN** a resposta é 403 com "Somente o administrador geral da plataforma pode editar os treinamentos."

#### Scenario: Administrador geral
- **WHEN** o administrador da `empresa-principal` consulta `GET /api/treinamento`
- **THEN** `podeGerenciar` é verdadeiro

### Requirement: Seções de treinamento
`POST /api/treinamento/secoes` e `PUT /api/treinamento/secoes/{id}` SHALL exigir o nome e responder "Seção salva com
sucesso." com o id; `DELETE /api/treinamento/secoes/{id}` SHALL responder "Seção excluída com sucesso."; seção
inexistente SHALL dar 404 com "Seção não encontrada.".

#### Scenario: Seção sem nome
- **WHEN** o administrador geral cria uma seção com nome vazio
- **THEN** a resposta é 400 com "Informe o nome da seção."

### Requirement: Vídeos de treinamento
Criar e editar vídeo (`/api/treinamento/videos`) SHALL exigir seção e título e um link do YouTube válido (`watch?v=`,
`youtu.be/`, `embed/`, `shorts/`, `live/` ou o id de 11 caracteres), gravando só o id do vídeo; a resposta SHALL ser
"Vídeo salvo com sucesso." com o id, e vídeo inexistente SHALL dar 404 com "Vídeo não encontrado.".

#### Scenario: Link curto do YouTube
- **WHEN** o administrador geral envia o link `https://youtu.be/dQw4w9WgXcQ`
- **THEN** o vídeo é salvo com o id `dQw4w9WgXcQ`

#### Scenario: Link inválido
- **WHEN** o link enviado é `https://vimeo.com/123`
- **THEN** a resposta é 400 com "Link do YouTube inválido."

#### Scenario: Sem título
- **WHEN** o vídeo vem sem título
- **THEN** a resposta é 400 com "Informe a seção e o título do vídeo."
