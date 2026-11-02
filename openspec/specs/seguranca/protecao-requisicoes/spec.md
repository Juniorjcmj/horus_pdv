# seguranca/protecao-requisicoes Specification

## Purpose

Proteções aplicadas a todas as requisições da API Java (perfis, situação da empresa, limites, cabeçalhos, CORS e
rastreio) e a compatibilidade com senhas e segredos já gravados pela API .NET.

## Requirements

### Requirement: Autorização por perfil
Uma rota marcada com perfis SHALL aceitar o usuário cujo perfil principal ou algum perfil adicional esteja na lista, sem
diferenciar maiúsculas, e SHALL responder 403 com "Usuário sem permissão para esta ação." caso contrário.

#### Scenario: Gerente com financeiro
- **WHEN** uma rota exige "financeiro" e o usuário é gerente com o perfil adicional financeiro
- **THEN** a requisição é aceita

### Requirement: Situação da empresa
Requisições autenticadas de empresas diferentes de `empresa-principal` SHALL ser recusadas com 403 quando a empresa não
estiver "aprovada", com mensagem conforme a situação (pendente, rejeitada, bloqueada ou outra).

#### Scenario: Empresa bloqueada depois do login
- **WHEN** a empresa é bloqueada enquanto um usuário dela está logado
- **THEN** a próxima requisição dele recebe 403 com "O acesso da sua empresa está bloqueado/suspenso."

### Requirement: Limite de requisições por IP
A API SHALL contar requisições por IP numa janela configurável (10 a 3600 s, padrão 60) e responder 429 com
`Retry-After` e "Limite de requisições excedido. Tente novamente em instantes." acima do máximo (10 a 5000, padrão 240).

#### Scenario: Excesso
- **WHEN** um IP faz a 241ª requisição na mesma janela de 60 s com a configuração padrão
- **THEN** a resposta é 429 com o cabeçalho `Retry-After` em segundos

#### Scenario: Preflight não conta
- **WHEN** chegam requisições `OPTIONS`
- **THEN** elas não entram na contagem

### Requirement: IP do cliente atrás de proxy
O IP do cliente SHALL ser o primeiro de `X-Forwarded-For` somente quando a configuração de confiar em cabeçalhos
encaminhados estiver ligada; caso contrário, o IP da conexão.

#### Scenario: Proxy confiável
- **WHEN** `TrustForwardedHeaders` está ligado e chega `X-Forwarded-For: 200.1.1.1, 10.0.0.2`
- **THEN** o IP usado em limite, sessão e auditoria é `200.1.1.1`

### Requirement: Limite de tamanho do corpo
Requisições com `Content-Length` acima do limite configurado (32 KB a 25 MB, padrão 2 MB) SHALL ser recusadas com 413
e "Payload maior que o permitido." antes de chegar à rota.

#### Scenario: Corpo grande
- **WHEN** chega uma requisição de 3 MB com o limite padrão
- **THEN** a resposta é 413 com a mensagem de limite

### Requirement: Cabeçalhos de segurança
Toda resposta SHALL trazer `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`,
`Permissions-Policy: camera=(), microphone=(), geolocation=()`, `X-Permitted-Cross-Domain-Policies: none`,
`Cross-Origin-Resource-Policy: cross-origin` e `Cache-Control: no-store`, sem cabeçalhos que revelem o servidor.

#### Scenario: Resposta qualquer
- **WHEN** um cliente chama qualquer rota
- **THEN** os sete cabeçalhos estão presentes e `Server`/`X-Powered-By` não aparecem

### Requirement: CORS
A API SHALL aceitar, com credenciais, qualquer método e cabeçalho, as origens configuradas mais
`pdv.quacksistemas.com.br`, `pdv.wootchat.com.br` (http e https), subdomínios de `quacksistemas.com.br` e
`wootchat.com.br`, `localhost` e `127.0.0.1`, e recusar as demais.

#### Scenario: Origem desconhecida
- **WHEN** um navegador em `https://exemplo.com` faz requisição com credenciais
- **THEN** a resposta não traz `Access-Control-Allow-Origin` para essa origem

### Requirement: Rastreio de requisições
Toda resposta SHALL ter `X-Request-Id` único, e cada requisição SHALL gerar um registro de log com id, método, caminho,
status, duração, usuário, sessão e IP.

#### Scenario: Requisição concluída
- **WHEN** uma requisição termina
- **THEN** existe um log `http.request.completed` com o mesmo id do cabeçalho `X-Request-Id`

### Requirement: Senhas compatíveis
A verificação de senha SHALL aceitar os hashes atuais no formato `iteracoes.sal.chave` (PBKDF2-SHA256, sal e chave em
Base64) e novas senhas SHALL ser gravadas no mesmo formato com 100.000 iterações, sal de 16 bytes e chave de 32 bytes.

#### Scenario: Hash gerado pelo .NET
- **WHEN** um usuário cujo hash foi gravado pela API .NET faz login na API Java com a senha certa
- **THEN** o login é aceito

### Requirement: Segredos cifrados compatíveis
Segredos gravados no banco no formato `enc:v1:<nonce>.<tag>.<cifra>` (AES-GCM, chave = SHA-256 da chave de cifragem ou,
sem ela, do segredo do JWT) SHALL ser lidos pela API Java, e novos segredos SHALL ser gravados no mesmo formato.

#### Scenario: Senha SMTP da empresa
- **WHEN** a API Java lê a senha SMTP cifrada pela API .NET com a mesma chave
- **THEN** obtém o texto original e consegue enviar e-mail

### Requirement: Configuração obrigatória em produção
Em produção, a API SHALL recusar a inicialização se o segredo do JWT ou a chave de cifragem tiverem menos de 32
caracteres, ou se o reCAPTCHA estiver ligado sem chave secreta.

#### Scenario: Segredo curto
- **WHEN** a API sobe em produção com um segredo de JWT de 20 caracteres
- **THEN** a inicialização falha com mensagem explicando o requisito

### Requirement: Auditoria
A API SHALL oferecer o registro de eventos de auditoria por empresa (usuário, tipo de evento, entidade, descrição de até
500 caracteres e IP), no mesmo formato e tabela da API .NET, para uso dos módulos.

#### Scenario: Descrição longa
- **WHEN** um módulo registra um evento com descrição de 700 caracteres
- **THEN** a descrição gravada é cortada em 500 caracteres
