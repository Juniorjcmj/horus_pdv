# seguranca/autenticacao Specification

## Purpose

Login, sessão e conta do próprio usuário na API Java, com o mesmo comportamento e as mesmas credenciais da API .NET,
para que usuários e sessões continuem válidos depois da troca de API.

## Requirements

### Requirement: Login com e-mail e senha
`POST /api/Auth/login` SHALL autenticar por e-mail (sem diferenciar maiúsculas, com espaços removidos) e senha, criar
uma sessão no banco e devolver o token tanto no cookie quanto no corpo da resposta.

#### Scenario: Login válido
- **WHEN** um usuário ativo de empresa aprovada envia e-mail e senha corretos
- **THEN** a resposta é 200 com `message` "Login realizado com sucesso." e `data` com `tokenType` "Bearer", `expiresInSeconds` (horas de sessão × 3600), `sessionId` e `user`
- **AND** a resposta grava o cookie `horuspdv.auth` com o token
- **AND** uma linha nova existe em `Sessoes` para o usuário

#### Scenario: Campos vazios
- **WHEN** e-mail ou senha vêm vazios
- **THEN** a resposta é 400 com "Informe e-mail e senha."

#### Scenario: Senha errada
- **WHEN** a senha não confere ou o usuário está inativo ou não existe
- **THEN** a resposta é 401 com "E-mail ou senha inválidos."

### Requirement: Bloqueio por tentativas
Após 5 senhas erradas dentro de 15 minutos, o login do usuário SHALL ficar bloqueado por 10 minutos; a janela de
tentativas recomeça quando passar de 15 minutos da primeira falha, e um login certo zera o contador.

#### Scenario: Quinta tentativa errada
- **WHEN** um usuário erra a senha pela 5ª vez em 15 minutos
- **THEN** a resposta é 429 com `data.lockedUntil` (data ISO) e as tentativas seguintes recebem 429 com "Muitas tentativas inválidas. Aguarde alguns minutos para tentar novamente." até o fim do bloqueio

### Requirement: Login bloqueado pela situação da empresa
O login SHALL ser recusado com 401 quando a empresa do usuário (exceto `empresa-principal`) estiver pendente, rejeitada
ou bloqueada, com a mensagem própria de cada situação e o motivo quando houver.

#### Scenario: Empresa pendente
- **WHEN** o usuário de uma empresa com situação "pendente" faz login com senha certa
- **THEN** a resposta é 401 com "O cadastro da sua empresa está em análise e aguarda aprovação pelo administrador do sistema."

#### Scenario: Empresa bloqueada com motivo
- **WHEN** a empresa está "bloqueada" com motivo "Inadimplência"
- **THEN** a mensagem é "O acesso da sua empresa está suspenso/bloqueado. Motivo: Inadimplência"

### Requirement: Token e cookie compatíveis
O token SHALL ser um JWT HS256 assinado com o segredo configurado, com os claims `iss, aud, sub, id, companyId, name,
email, role, tokenType, sessionId, sessionTtl, ip, iat, exp`, válido pelas horas de sessão configuradas (1 a 24,
padrão 8). Tokens emitidos pela API .NET com o mesmo segredo SHALL ser aceitos.

#### Scenario: Token emitido pelo .NET
- **WHEN** um cliente envia um token gerado pela API .NET com o mesmo segredo, emissor e público, dentro da validade e com sessão existente
- **THEN** a API Java aceita a requisição

#### Scenario: Cookie lembrar-me
- **WHEN** o login vem com `rememberMe` verdadeiro
- **THEN** o cookie `horuspdv.auth` é HttpOnly, Path "/", Secure e SameSite=Strict fora de desenvolvimento, e expira junto com a sessão; sem `rememberMe` o cookie é de sessão do navegador

### Requirement: Validação da sessão em cada requisição
Toda rota protegida SHALL ler o token do cookie `horuspdv.auth` ou, sem cookie, do cabeçalho `Authorization: Bearer`,
e SHALL exigir assinatura, emissor, público e validade corretos, sessão existente no banco e usuário ativo.

#### Scenario: Sessão encerrada
- **WHEN** um token válido chega mas a sessão dele foi apagada
- **THEN** a resposta é 401 com "Sessao expirada ou token invalido."

#### Scenario: Rotas públicas
- **WHEN** chegam requisições para `api/Auth/login`, `forgot-password`, `reset-password`, `register` ou `OPTIONS`
- **THEN** elas não exigem token

### Requirement: Logout
`POST /api/Auth/logout` SHALL apagar a sessão atual (quando houver) e remover o cookie.

#### Scenario: Sair
- **WHEN** um usuário logado chama logout
- **THEN** a sessão dele some do banco, o cookie é apagado e a resposta é 200 com "Logout realizado com sucesso."

### Requirement: Dados do usuário logado
`GET /api/Auth/me` SHALL devolver o usuário da sessão; `PUT /api/Auth/me` SHALL atualizar nome, e-mail e telefone do
próprio usuário sem mudar perfil, situação, senha nem perfis adicionais.

#### Scenario: Atualizar o próprio perfil
- **WHEN** o usuário envia nome, e-mail e telefone válidos
- **THEN** a resposta é 200 com "Perfil atualizado com sucesso." e o usuário atualizado

#### Scenario: E-mail já usado
- **WHEN** o e-mail novo pertence a outro usuário de qualquer empresa
- **THEN** a resposta é 400 com "Este e-mail já está cadastrado no sistema."

### Requirement: Troca de senha
`POST /api/Auth/change-password` SHALL exigir a senha atual, aceitar nova senha com 8 caracteres ou mais, desmarcar
`mustChangePassword` e encerrar todas as sessões do usuário.

#### Scenario: Senha atual errada
- **WHEN** a senha atual não confere
- **THEN** a resposta é 400 com "Senha atual inválida."

#### Scenario: Troca feita
- **WHEN** a senha atual confere e a nova tem 8 caracteres
- **THEN** a resposta é 200 com "Senha atualizada com sucesso. Faça login novamente." e todas as sessões do usuário são apagadas

### Requirement: Recuperação de senha por e-mail
`POST /api/Auth/forgot-password` SHALL validar CNPJ e e-mail, gerar um token de uso único com validade configurável (5 a
180 min, padrão 30), guardar só o hash e enviar o link por e-mail, sem revelar se o e-mail existe.

#### Scenario: Pedido aceito
- **WHEN** CNPJ válido e e-mail de usuário ativo da empresa são enviados e o e-mail está configurado
- **THEN** a resposta é 200 com "Se o e-mail estiver cadastrado, enviaremos as instruções de recuperação." e `data` com `accepted`, `maskedEmail` e `expiresAt`, e tokens anteriores não usados do usuário são invalidados

#### Scenario: E-mail não configurado em produção
- **WHEN** o envio de e-mail está desligado fora de desenvolvimento
- **THEN** a resposta é 503 com "Recuperação de senha indisponível. Configure o envio de e-mail."

#### Scenario: CNPJ inválido
- **WHEN** o CNPJ não passa nos dígitos verificadores
- **THEN** a resposta é 400 com "Informe um CNPJ válido."

### Requirement: Redefinição com token
`POST /api/Auth/reset-password` SHALL aceitar o token ainda válido e não usado, exigir confirmação igual e 8+
caracteres, trocar a senha, consumir o token e encerrar as sessões do usuário.

#### Scenario: Token expirado
- **WHEN** o token passou da validade ou já foi usado
- **THEN** a resposta é 400 com "Token de redefinição inválido ou expirado."

### Requirement: Cadastro público de empresa
`POST /api/Auth/register` SHALL criar uma empresa nova (situação "pendente" se a aprovação estiver ligada na
`empresa-principal`, senão "aprovada") e o usuário administrador dela, recusando CNPJ ou e-mail já cadastrados.

#### Scenario: Cadastro criado
- **WHEN** CNPJ válido, senhas iguais e e-mail inédito são enviados
- **THEN** a resposta é 201 com "Cadastro criado com sucesso." e o usuário administrador criado

#### Scenario: CNPJ já cadastrado
- **WHEN** já existe empresa (fora a principal) com o mesmo CNPJ
- **THEN** a resposta é 400 com "Este CNPJ já possui uma empresa cadastrada no sistema. Faça login com sua conta."

### Requirement: Verificação reCAPTCHA
Login, cadastro, pedido e redefinição de senha SHALL validar o token reCAPTCHA (com a ação esperada e nota mínima
configurada) quando o reCAPTCHA estiver ligado, com as mesmas respostas de erro da API .NET.

#### Scenario: Token ausente
- **WHEN** o reCAPTCHA está ligado e o login chega sem `recaptchaToken`
- **THEN** a resposta é 400 com "Validação de segurança obrigatória. Atualize e tente novamente."

#### Scenario: Token expirado no Google
- **WHEN** o Google responde `timeout-or-duplicate`
- **THEN** a resposta é 409 com "Validação de segurança expirou. Tente novamente." e `details` "RECAPTCHA_TOKEN_EXPIRED"

### Requirement: Sessões do usuário
`GET /api/Sessao` SHALL listar as sessões do usuário marcando a atual; `DELETE /api/Sessao/{id}` SHALL encerrar outra
sessão; `DELETE /api/Sessao/outras` SHALL encerrar todas menos a atual. As duas exclusões devolvem a lista atualizada.

#### Scenario: Tentar encerrar a sessão atual
- **WHEN** o usuário pede para encerrar a própria sessão atual por `DELETE /api/Sessao/{id}`
- **THEN** a resposta é 400 com "A sessão atual não pode ser encerrada por esta ação."
