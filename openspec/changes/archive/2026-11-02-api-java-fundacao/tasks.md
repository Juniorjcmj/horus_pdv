# Tasks

> Antes de cada grupo, leia as seções correspondentes de `design.md` e os arquivos .NET de referência listados no
> Context. Mensagens e códigos HTTP são copiados literalmente do .NET. Trabalhe na branch `spring-api`.

## 1. Projeto base

- [x] 1.1 Criar `API/JAVA` (Maven, Java 21, Spring Boot 3.5.x; dependências web, data-jpa, security, validation, mail, actuator, flyway-core, flyway-sqlserver, mssql-jdbc, springdoc-openapi-starter-webmvc-ui; teste: spring-boot-starter-test, mockito, assertj, testcontainers-mssqlserver; failsafe para `*IT`) e verificar com `mvn -B -q -DskipTests package` em `API/JAVA`
- [x] 1.2 Criar `QuackPdvApiApplication`, a estrutura de pacotes por domínio (D1) e `application.yml` com perfis `dev` e padrão (produção); verificar que `mvn -B test` roda um teste de contexto vazio com o perfil de teste
- [x] 1.3 Criar `QuackProperties` (`@ConfigurationProperties("quack")`) com todos os padrões e limites (D4) e o validador de produção em `ApplicationRunner`; testes unitários dos limites (ex.: `session-hours=40` vira 24) e da recusa com segredo curto em produção
- [x] 1.4 Escrever `API/JAVA/README.md` (como compilar, rodar em `dev` com o SQL Server do Docker, variáveis de ambiente do anexo 17.3) e verificar que os comandos documentados funcionam

## 2. Vetores de compatibilidade .NET e criptografia

- [x] 2.1 Criar `API/JAVA/contrato/vetores-dotnet` (console .NET 8) que gera `vetores.json` (PBKDF2 de senhas com e sem acento, `enc:v1:` de textos conhecidos com chave fixa e com fallback no segredo do JWT, JWT com claims e segredo fixos, hash de token de redefinição) e que também verifica um arquivo de vetores gerado pelo Java; verificar com `dotnet run` gerando o arquivo
- [x] 2.2 Implementar `PasswordHasher` (PBKDF2-SHA256, `iter.sal.chave`, 100.000 / 16 / 32, comparação em tempo constante) e verificar com teste que valida os hashes do `vetores.json` e rejeita senha errada
- [x] 2.3 Implementar `SecretProtector` (AES-GCM `enc:v1:`, chave SHA-256 de `encryption-key` ou do segredo do JWT, valor sem prefixo passa direto) e verificar com teste que decifra os vetores do .NET e que o que o Java cifra o console C# decifra
- [x] 2.4 Implementar `JwtService` (emitir e validar como `HorusJwtService`) e verificar com testes: token do vetor .NET aceito, assinatura alterada recusada, emissor/público errados recusados, expirado recusado, `sessionTtl` e `expiresInSeconds` coerentes
- [x] 2.5 Implementar hash e geração do token de redefinição, `CnpjValidator`, máscara de e-mail e rótulo de dispositivo, com testes unitários (vetores .NET para o hash; CNPJ válido/inválido/repetido; casos de máscara e de user-agents)

## 3. Banco e Flyway

- [x] 3.1 Criar um banco do zero com a API .NET atual (contêiner SQL temporário + imagem .NET) e gerar com SqlPackage o `V1__esquema_inicial.sql` só com esquema (sem `CREATE DATABASE`/`USE`, sem `__MigrationHistory`, lotes com `GO`); verificar que roda num banco vazio sem erro
- [x] 3.2 Gerar `contrato/esquema/esquema-dotnet.json` (colunas e índices do banco do 3.1) e escrever `EsquemaEquivalenteIT` (Testcontainers + Flyway) que compara com o snapshot; verificar que passa
- [x] 3.3 Escrever `V2__dados_referencia.sql` (linha `empresa-principal` e demais dados obrigatórios encontrados nos `INSERT` do `Resumo.sql` e das migrações, com `IF NOT EXISTS`) e `db/dev/R__seed_dev.sql` (usuário de desenvolvimento só no perfil `dev`); verificar com IT que o banco novo tem a empresa principal e que o perfil padrão não tem o usuário de dev
- [x] 3.4 Configurar Flyway (`baseline-on-migrate`, `baseline-version=1`, `validate-on-migrate`) e escrever `BaselineBancoRestauradoIT`: banco criado pelo script .NET (sem histórico Flyway) sobe com baseline, o V1 não roda e os dados ficam; verificar que passa
- [x] 3.5 Configurar JPA (nationalized, `timezone.default_storage=NATIVE`, naming físico padrão, `ddl-auto=validate`, `open-in-view=false`) e criar as entidades `Usuario`, `Sessao`, `Empresa`, `PasswordResetToken`, `AuditLog` com repositórios; verificar com IT de repositório (gravar e ler `OffsetDateTime`, acentos, `IDENTITY` do `AuditLog`) e com a subida validando o esquema
- [x] 3.6 Documentar no README como o banco nasce (V1/V2), como funciona o baseline do banco restaurado e a regra de nunca editar migração aplicada; verificar teste de checksum (migração alterada impede a subida)

## 4. Contrato HTTP comum

- [x] 4.1 Criar `ApiResponse`, `NegocioException` e o `@RestControllerAdvice` (envelope por status, ProblemDetails 400 para corpo inválido, 500 genérico só com `success` e `message`) e verificar com `@WebMvcTest` de um controller de teste cobrindo os três casos
- [x] 4.2 Configurar Jackson (nulos presentes, camelCase, formato de data .NET com 7 casas e `+00:00`, ignorar desconhecidos, entrada sem diferenciar maiúsculas) e verificar com teste de serialização de um usuário, de uma data e de um corpo com nomes PascalCase
- [x] 4.3 Configurar roteamento sem diferenciar maiúsculas e com barra final opcional, 404/405 sem corpo e sem página de erro padrão, e verificar com teste MockMvc (maiúsculas, minúsculas, barra final, rota inexistente, método errado)
- [x] 4.4 Configurar springdoc (Swagger UI só em `dev`) e Actuator (health); verificar que `/v3/api-docs` e `/swagger-ui.html` respondem em `dev` e não em produção

## 5. Filtros de proteção

- [x] 5.1 Implementar `ClienteIpResolver` e `CabecalhosSegurancaFilter` (7 cabeçalhos, sem `Server`) e verificar com teste MockMvc dos cabeçalhos e testes unitários do IP com e sem `trust-forwarded-headers`
- [x] 5.2 Implementar `TelemetriaFilter` (`X-Request-Id` e log `http.request.completed` com os campos do .NET) e verificar com teste que captura o log e confere o id igual ao cabeçalho
- [x] 5.3 Implementar `LimiteCorpoFilter` (413) e `LimiteRequisicoesFilter` (janela fixa por IP, `Retry-After`, ignora `OPTIONS` e swagger, limpeza acima de 500 entradas) com `Clock` injetado; verificar com testes unitários da janela e do `Retry-After` e teste MockMvc do 413
- [x] 5.4 Configurar Spring Security (stateless, sem CSRF, sem cabeçalhos próprios, `permitAll`) e CORS igual ao `Program.cs` (origens configuradas + fixas + subdomínios + localhost, credenciais) na ordem de filtros do D4; verificar com teste de preflight para origem permitida e recusada

## 6. Autenticação por requisição e login

- [x] 6.1 Implementar `AutenticacaoFilter` (rotas públicas, cookie ou Bearer, validação JWT, consulta única de sessão/usuário/perfis/empresa, 401 e 403 com as mensagens do middleware, tokens `qgw_`/`qmcp_` e `/mcp` → 401) e `UsuarioAutenticado`; verificar com testes de filtro cobrindo cada saída
- [x] 6.2 Implementar `@PerfisPermitidos` e o interceptor (perfil principal ou adicional, método sobrescreve classe) e verificar com `@WebMvcTest` de rotas de teste (permitido, negado com 403 e mensagem, gerente com financeiro)
- [x] 6.3 Implementar `RecaptchaService` igual ao `HorusRecaptchaService` (desligado passa; sem chave 500; sem token 400; falha do Google 503; `timeout-or-duplicate`/`invalid-input-response` 409 `RECAPTCHA_TOKEN_EXPIRED`; demais 400 `RECAPTCHA_VALIDATION_FAILED`; ação e nota mínima) e verificar com testes unitários usando `MockRestServiceServer`
- [x] 6.4 Implementar `AutenticacaoService.login` (normalização do e-mail, bloqueio 5/15/10 com SQL atômico, situação da empresa com motivos, sessão com dispositivo/plataforma, `LastLoginAt` no formato .NET, transação) e verificar com testes Mockito (cada mensagem, 401 x 429 com `lockedUntil`, janela expirada zera) e `LoginBloqueioIT`
- [x] 6.5 Implementar `AuthController` `login` e `logout` (cookie `horuspdv.auth` com as opções por ambiente e `rememberMe`) e verificar com `@WebMvcTest` (corpo, cookie e atributos em `dev` e produção, logout apaga cookie e sessão)
- [x] 6.6 Implementar `me`, `PUT me` e `change-password` (8+ caracteres, desmarca `mustChangePassword`, encerra todas as sessões) e verificar com testes Mockito e `@WebMvcTest` das mensagens e status

## 7. Sessões

- [x] 7.1 Implementar `SessaoService` e `SessaoController` (listar marcando a atual com "Agora mesmo"; encerrar outra; recusar a atual com 400; encerrar outras; devolver lista atualizada; 404 se não achar) e verificar com testes Mockito, `@WebMvcTest` e um IT que encerra uma sessão e confere 401 no token dela

## 8. Usuários

- [x] 8.1 Implementar `UsuarioService` (listar por nome, supervisores ativos admin/gerente, criar com validações e ids, duplicidade de e-mail global e CPF por empresa, perfis adicionais só por administrador, editar mantendo senha vazia, status com encerramento de sessões, isolamento por empresa) e verificar com testes Mockito de cada regra e mensagem
- [x] 8.2 Implementar `UsuarioController` (perfis por rota, 201 ao criar, 404 de outra empresa) e verificar com `@WebMvcTest` incluindo caixa recebendo 403 em `GET /api/Usuario` e 200 em `supervisores`

## 9. Recuperação de senha, cadastro público e e-mail

- [x] 9.1 Implementar `EmailService` (resolução de SMTP da empresa com senha decifrada ou global, `JavaMailSender` por envio, modelos HTML/texto portados do `HorusEmailTemplate`, URL de redefinição) e verificar com teste usando GreenMail ou servidor SMTP falso que recebe o e-mail com o link
- [x] 9.2 Implementar `RecuperacaoSenhaService` (pedido com limpeza de tokens antigos, invalidação dos não usados, token e hash, `expiresAt`, consumo em falha de envio; redefinição com validade, confirmação, troca, consumo e encerramento de sessões; redefinição administrativa com `mustChangePassword`) e verificar com testes Mockito e `RecuperacaoSenhaIT` ponta a ponta
- [x] 9.3 Implementar as rotas `forgot-password`, `reset-password` e `POST /api/Usuario/{id}/resetar-senha` com as respostas por ambiente (503, 502, dados completos em `dev` com e-mail desligado) e verificar com `@WebMvcTest`
- [x] 9.4 Implementar `CadastroPublicoService` e `POST /api/Auth/register` (CNPJ válido, senhas iguais, e-mail e CNPJ inéditos, empresa pendente ou aprovada conforme a `empresa-principal`, administrador, e-mail de boas-vindas que não derruba o cadastro) e verificar com testes Mockito e `CadastroPublicoIT`

## 10. Contrato gravado e comparado

- [x] 10.1 Criar `contrato/ambiente-dotnet/subir.sh` e `descer.sh` (contêineres `contrato-sql` e `contrato-api-dotnet` na porta 5270, Production, reCAPTCHA e e-mail desligados) e `contrato/seed/seed-contrato.sql` (empresas e usuários do D6); verificar que o login de cada usuário do seed funciona na API .NET
- [x] 10.2 Escrever os cenários de `auth`, `sessao` e `usuario` em `src/test/resources/contrato/cenarios/` (sucessos, erros de validação, 401, 403, 429 de bloqueio, perfis) e o gravador (tag `gravar-contrato`); verificar gerando as gravações contra a API .NET e versionando os arquivos
- [x] 10.3 Implementar o comparador (tag `contrato`: `@SpringBootTest` + Testcontainers + Flyway + seed; comparação por caminho JSON com campos voláteis) e verificar que todos os cenários gravados passam contra a API Java
- [x] 10.4 Exportar `contrato/openapi-dotnet.json` do Swagger da API .NET em Development e documentar em `contrato/README.md` como subir o ambiente, gravar um grupo novo e rodar o comparador; verificar seguindo o README do zero

## 11. Entrega

- [x] 11.1 Criar `API/JAVA/Dockerfile` (multi-stage, não-root, `TZ`, healthcheck) e verificar com `docker build` e `docker run` respondendo `/actuator/health`
- [x] 11.2 Criar `API/JAVA/docker-compose.homologacao.yml` e a seção de homologação no README (variáveis, banco restaurado com baseline); verificar subindo localmente e fazendo login com o usuário de dev
- [x] 11.3 Criar `.github/workflows/api-java.yml` (push na `spring-api` com mudanças em `API/JAVA/**`: `mvn -B verify` e publicação `quack-pdv-api-java:spring-api` e `:<sha>` no GHCR) e verificar a sintaxe com `actionlint` ou rodando o workflow no push

## 12. Prova de conceito fiscal

- [x] 12.1 Criar `API/JAVA/poc-fiscal` com a biblioteca candidata e a venda de exemplo espelhando o `ZeusFiscalProvider` (itens, tributos, IBS/CBS, desconto, dinheiro + PIX); verificar montando e assinando com certificado autoassinado e validando nos XSD 4.00
- [x] 12.2 Implementar as operações com SEFAZ de homologação (autorizar, consultar, cancelar, inutilizar, contingência offline), ativadas pelas variáveis `POC_*`; verificar rodando com certificado de homologação quando disponível
- [x] 12.3 Escrever `poc-fiscal/RELATORIO.md` com cada critério do spec `fiscal/biblioteca-java-nfe` (atendido, não atendido ou pendente de certificado, evidência e recomendação) e verificar que cobre todos os requisitos do spec

## 13. Verificação integrada

- [x] 13.1 Rodar `mvn -B verify` completo (unidade, web, IT e comparador) e a suíte de compatibilidade do console C# lendo os vetores do Java; verificar tudo verde
- [x] 13.2 Fazer login na API Java (Docker local) com um usuário restaurado de um banco criado pela API .NET e usar o token emitido pela API .NET na API Java (mesmos segredos); verificar `GET /api/Auth/me` 200 nos dois sentidos

## Workflow follow-up

- Revisar o `RELATORIO.md` da PoC fiscal e decidir a biblioteca da Fase 7.
- Arquivar a change depois da revisão (`/opsx:archive`).
- Abrir a proposta da Fase 2 (cadastros e administração) reaproveitando o gravador e o comparador.
