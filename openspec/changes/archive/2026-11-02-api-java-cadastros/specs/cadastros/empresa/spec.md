# Spec Delta

## Purpose

Dados da empresa logada (cadastrais, envio de e-mail por SMTP, dados fiscais, CSC e certificado digital A1) e o tema
de cores do sistema e do PDV, servidos pela API Java com o mesmo contrato da API .NET e com os segredos sempre cifrados
no banco e mascarados nas respostas.

## ADDED Requirements

### Requirement: Consultar dados da empresa
`GET /api/Empresa` SHALL devolver os dados da empresa do usuário logado, para qualquer perfil autenticado, com a
mensagem "Dados da empresa obtidos com sucesso."; quando a empresa não existir, SHALL responder 404 com "Empresa não
encontrada.".

#### Scenario: Caixa consulta a empresa
- **WHEN** um usuário com perfil caixa chama `GET /api/Empresa`
- **THEN** a resposta é 200 com os dados da própria empresa e nenhum dado de outra empresa

#### Scenario: Sem sessão
- **WHEN** a rota é chamada sem token válido
- **THEN** a resposta é 401 no mesmo formato da API .NET

### Requirement: Segredos mascarados na resposta
A resposta da empresa MUST NOT conter a senha SMTP, o CSC, o certificado nem a senha do certificado: esses campos SHALL
vir vazios, acompanhados dos indicadores `emailSmtpHasPassword`, `cscHasValue` e `certificadoHasValue`, além da
impressão digital e da validade do certificado.

#### Scenario: Empresa com certificado e CSC cadastrados
- **WHEN** a empresa tem CSC e certificado gravados e alguém consulta `GET /api/Empresa`
- **THEN** `csc` e o certificado vêm vazios, `cscHasValue` e `certificadoHasValue` são verdadeiros, e vêm
  `certificadoThumbprint` e `certificadoValidoAte` (formato ISO com fuso)

### Requirement: Atualizar dados da empresa
`PUT /api/Empresa` SHALL ser permitido só ao perfil administrador e SHALL gravar os dados enviados, respondendo
"Dados da empresa atualizados com sucesso." com os dados já mascarados. Os textos de erro de validação SHALL ser os
mesmos da API .NET.

#### Scenario: Gerente tenta alterar
- **WHEN** um gerente chama `PUT /api/Empresa`
- **THEN** a resposta é 403 com "Usuário sem permissão para esta ação."

#### Scenario: Nome fantasia vazio
- **WHEN** o administrador envia a empresa sem nome fantasia
- **THEN** a resposta é 400 com "Nome fantasia e obrigatorio."

#### Scenario: CNPJ inválido
- **WHEN** o CNPJ enviado não passa na validação de dígitos
- **THEN** a resposta é 400 com "CNPJ invalido."

### Requirement: Validação de SMTP e dados fiscais
Com o envio por SMTP ligado, a gravação SHALL exigir host, porta válida, usuário, e-mail remetente e senha de app
(salvo se já houver senha gravada), e e-mail de resposta válido quando informado. O CRT SHALL estar entre 1 e 4 e o
CSC Id SHALL ter só dígitos (até 6). A série da NFC-e vazia SHALL virar 2.

#### Scenario: SMTP ligado sem host
- **WHEN** o administrador liga o SMTP e não informa o host
- **THEN** a resposta é 400 com "Informe o host SMTP."

#### Scenario: CRT fora da faixa
- **WHEN** o CRT enviado é 5
- **THEN** a resposta é 400 com "Regime tributario (CRT) invalido."

### Requirement: Segredos preservados e cifrados
A senha SMTP, o CSC, o certificado e a senha do certificado SHALL ser gravados cifrados no formato `enc:v1:`, legível
pela API .NET. Quando o campo vier vazio na gravação, o valor já gravado SHALL ser mantido.

#### Scenario: Gravar sem reenviar o CSC
- **WHEN** o administrador altera o telefone e envia `csc` vazio
- **THEN** o CSC gravado antes continua o mesmo e `cscHasValue` segue verdadeiro

#### Scenario: Compatibilidade com o .NET
- **WHEN** a API Java grava um CSC novo
- **THEN** o valor no banco começa com `enc:v1:` e a API .NET consegue decifrá-lo com a mesma chave

### Requirement: Certificado digital A1
Ao receber um certificado novo (PFX em base64 e senha), a API SHALL abri-lo com a senha e calcular a impressão digital
(SHA-1 em hexadecimal maiúsculo) e a data de validade, iguais às da API .NET. Certificado ou senha inválidos SHALL
gerar 400 com "Certificado digital ou senha inválidos.".

#### Scenario: Senha errada
- **WHEN** o administrador envia um PFX com a senha errada
- **THEN** a resposta é 400 com "Certificado digital ou senha inválidos." e nada é gravado

#### Scenario: Certificado válido
- **WHEN** o administrador envia um PFX válido com a senha certa
- **THEN** a resposta traz `certificadoHasValue` verdadeiro, a impressão digital igual à calculada pelo .NET para o
  mesmo arquivo e a validade do certificado

### Requirement: Tema de cores da empresa
`GET /api/company-theme` SHALL aceitar administrador, gerente, atendente e caixa e devolver `systemAccent`,
`pdvAccent` e `updatedAt` com "Tema da empresa.", ou dados nulos com "Sem tema personalizado." quando não houver tema.

#### Scenario: Empresa sem tema
- **WHEN** um caixa consulta o tema de uma empresa que nunca salvou cores
- **THEN** a resposta é 200 com `data` nulo e "Sem tema personalizado."

### Requirement: Salvar tema
`PUT /api/company-theme` SHALL ser permitido a administrador e gerente, SHALL aceitar cada cor no formato `#rrggbb` ou
vazia (volta ao padrão) e SHALL criar ou atualizar o tema da empresa, respondendo "Tema salvo.".

#### Scenario: Cor inválida
- **WHEN** um gerente envia `systemAccent` igual a `azul`
- **THEN** a resposta é 400 com "Cor inválida. Use formato hexadecimal #rrggbb (ex.: #0369a1) ou vazio para o padrão."

#### Scenario: Atendente tenta salvar
- **WHEN** um atendente chama `PUT /api/company-theme`
- **THEN** a resposta é 403
