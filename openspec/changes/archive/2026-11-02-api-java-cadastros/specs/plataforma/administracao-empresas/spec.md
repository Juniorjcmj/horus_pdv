# Spec Delta

## Purpose

Gestão das empresas da plataforma pelo administrador geral na API Java: listar e medir, aprovar, rejeitar, bloquear e
reativar, ligar a aprovação prévia de novos cadastros, trocar as credenciais do administrador de uma empresa, excluí-la
por completo e baixar a documentação da API, com o mesmo contrato da API .NET.

## ADDED Requirements

### Requirement: Acesso restrito ao administrador geral
As rotas de `api/Admin/Empresas` SHALL exigir o perfil administrador da `empresa-principal`; qualquer outro usuário
autenticado SHALL receber 403 com "Acesso negado: módulo restrito ao Administrador Geral da Plataforma.".

#### Scenario: Administrador de uma loja
- **WHEN** o administrador de uma empresa cliente chama `GET /api/Admin/Empresas`
- **THEN** a resposta é 403 com "Acesso negado: módulo restrito ao Administrador Geral da Plataforma."

### Requirement: Listar empresas
`GET /api/Admin/Empresas` SHALL aceitar `search` (nome fantasia, razão social, CNPJ ou e-mail), `status` (`todas` ou
vazio = sem filtro), `page` (mínimo 1) e `pageSize` (1 a 100, padrão 20), e devolver `items`, `totalCount`, `page` e
`pageSize`, com "Empresas listadas com sucesso.".

#### Scenario: Ordem da lista
- **WHEN** há empresas pendentes e aprovadas
- **THEN** a `empresa-principal` vem primeiro, depois as pendentes, e dentro de cada grupo as mais novas primeiro

#### Scenario: Página grande demais
- **WHEN** a lista é pedida com `pageSize=500`
- **THEN** a resposta usa `pageSize` 100

#### Scenario: Dados de cada empresa
- **WHEN** a lista é carregada
- **THEN** cada item traz a situação (vazia vale "aprovada"), os dados do primeiro usuário da empresa como
  administrador inicial e o total de usuários, produtos e vendas

### Requirement: Métricas das empresas
`GET /api/Admin/Empresas/metricas` SHALL devolver o total de empresas e quantas estão pendentes, aprovadas, rejeitadas
e bloqueadas (situação vazia conta como aprovada), mais `requireApprovalForNewCompanies`, com "Métricas obtidas com
sucesso.".

#### Scenario: Contagem por situação
- **WHEN** há 1 empresa pendente, 2 aprovadas e 1 bloqueada
- **THEN** `total` é 4, `pendentes` 1, `aprovadas` 2, `rejeitadas` 0 e `bloqueadas` 1

### Requirement: Mudar a situação de uma empresa
Aprovar, rejeitar, bloquear e reativar (`POST /api/Admin/Empresas/{id}/aprovar|rejeitar|bloquear|reativar`) SHALL
gravar a nova situação, quem revisou e quando. Rejeitar e bloquear SHALL exigir o motivo e encerrar todas as sessões
dos usuários da empresa. A `empresa-principal` MUST NOT ser alterada.

#### Scenario: Bloquear empresa
- **WHEN** o administrador geral bloqueia uma empresa informando o motivo
- **THEN** a resposta é "Empresa suspensa/bloqueada com sucesso. Todas as sessões ativas foram revogadas." e os
  usuários dessa empresa perdem a sessão na próxima requisição

#### Scenario: Rejeitar sem motivo
- **WHEN** a rejeição é enviada com motivo vazio
- **THEN** a resposta é 400 com "Informe o motivo da recusa/rejeição."

#### Scenario: Empresa principal
- **WHEN** o administrador geral tenta aprovar a `empresa-principal`
- **THEN** a resposta é 400 com "Empresa inválida para aprovação."

#### Scenario: Aprovar empresa inexistente
- **WHEN** o id não existe
- **THEN** a resposta é 404 com "Empresa não encontrada ou já aprovada."

### Requirement: Aprovação prévia de novos cadastros
`GET` e `PUT /api/Admin/Empresas/configuracao` SHALL ler e gravar `requireApprovalForNewCompanies`, guardado na
`empresa-principal` (vazio vale verdadeiro). A gravação SHALL responder com a mensagem que diz se a aprovação passou
a ser prévia ou automática.

#### Scenario: Ligar a aprovação automática
- **WHEN** o administrador geral grava `requireApprovalForNewCompanies` falso
- **THEN** a resposta é "Aprovação automática ativada: novos cadastros terão acesso imediato." e um cadastro público
  feito depois nasce aprovado

### Requirement: Trocar credenciais do administrador da empresa
`PUT /api/Admin/Empresas/{id}/credenciais` SHALL trocar o e-mail e/ou a senha do administrador ativo mais antigo da
empresa. O e-mail SHALL ser único no sistema. Trocar a senha SHALL desligar a troca obrigatória e encerrar as sessões
desse usuário. As mensagens SHALL ser as da API .NET.

#### Scenario: Nada informado
- **WHEN** o corpo vem sem e-mail e sem senha
- **THEN** a resposta é 400 com "Informe o novo e-mail ou a nova senha."

#### Scenario: Troca de senha
- **WHEN** o administrador geral troca a senha do administrador "Maria" de uma empresa
- **THEN** a resposta é "Credenciais atualizadas (senha) para o administrador Maria.", a senha nova funciona no login e
  as sessões antigas dela deixam de valer

#### Scenario: E-mail em uso
- **WHEN** o e-mail novo já pertence a outro usuário
- **THEN** a resposta é 400 com "Este e-mail já está em uso por outro usuário."

### Requirement: Excluir empresa
`DELETE /api/Admin/Empresas/{id}` SHALL apagar a empresa e os dados dela nas mesmas tabelas que a API .NET apaga, numa
única transação, e responder "Empresa e todos os seus dados foram excluídos permanentemente.". A `empresa-principal`
MUST NOT ser excluída.

#### Scenario: Excluir empresa com vendas
- **WHEN** o administrador geral exclui uma empresa com usuários, produtos e vendas
- **THEN** a empresa e esses dados deixam de existir, e os dados das outras empresas continuam intactos

#### Scenario: Falha no meio
- **WHEN** a exclusão de alguma tabela falha
- **THEN** nada é apagado e a resposta é 500 com "Erro interno ao excluir empresa."

### Requirement: Download da documentação da API
`GET /api/Admin/Empresas/documentacao-api` SHALL baixar, também em produção, o documento OpenAPI 3 da API Java como
arquivo `quack-pdv-api-openapi.json` (`application/json`), com o servidor da própria requisição. Quem não for o
administrador geral SHALL receber 403 com "Documentação da API restrita ao Administrador Geral da Plataforma.".

#### Scenario: Administrador geral baixa a documentação
- **WHEN** o administrador geral chama a rota
- **THEN** a resposta é 200 com o anexo `quack-pdv-api-openapi.json`, que descreve as rotas da API Java

#### Scenario: Gerente da plataforma
- **WHEN** um gerente da `empresa-principal` chama a rota
- **THEN** a resposta é 403 com "Documentação da API restrita ao Administrador Geral da Plataforma."
