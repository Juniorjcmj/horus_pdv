# Teste de contrato: API Java x API .NET

Prova, rota a rota, que a API Java responde **como a API .NET**: o mesmo status, os mesmos cabeçalhos relevantes e o
mesmo JSON (campos, tipos, valores e mensagens). As respostas da API .NET são **gravadas** uma vez; a suíte repete os
mesmos cenários contra a API Java e compara por caminho JSON.

```
contrato/
  ambiente-dotnet/subir.sh, descer.sh   sobe/derruba a API .NET + SQL Server usados para gravar
  seed/seed-contrato.sql                empresas e usuários dos cenários (aplicado nos DOIS lados)
  seed/reset-contrato.sql               volta o banco ao seed antes de CADA cenário (nos dois lados)
  esquema/                              V1 gerado (gerar-v1.sh), snapshot do esquema .NET e o script que o gera
  vetores-dotnet/                       console C# que gera/verifica vetores de PBKDF2, enc:v1, JWT e token de redefinição
  openapi-dotnet.json                   Swagger da API .NET (Development), referência para as próximas fases
src/test/resources/contrato/
  cenarios/<grupo>.json                 os cenários (auth, sessao, usuario)
  gravacoes/<grupo>/<cenario>.json      as respostas gravadas da API .NET (versionadas)
```

## Fase 2: cadastros

Os endpoints Java implementados nesta fase cobrem Empresa (`GET`/`PUT`), tema da empresa (`GET`/`PUT`), Categoria
(consulta, CRUD e status), Fornecedor (CRUD), Cliente (CRUD e `credito-snapshot`), `ModuloMercado` (consulta e CRUD de
registros), treinamento (consulta e CRUD de seções/vídeos), Admin/Empresas (lista, métricas, configuração, aprovar,
rejeitar, bloquear, reativar, credenciais e `DELETE`) e `documentacao-api`.

A migração Flyway V3 inclui 55 categorias padrão, 2 seções e 9 vídeos. Cada INSERT é idempotente por `Id`, usando
`IF NOT EXISTS`.

Os 11 grupos estão habilitados em `MotorDeContrato.GRUPOS`. As gravações da API .NET e os comparadores dos 8 grupos
novos estão em andamento. O fluxo manual pelo frontend também está em andamento, usando banco separado
`HorusPdvUiFase2` e API na porta 18080; ainda não foi concluído. `mvn -B verify` passou com 352 testes de unidade/web e
131 de integração/contrato, sem falhas, erros ou skips. A Fase 2 continua aberta até a conclusão dos comparadores e da
validação de UI.

## Rodar o comparador (só precisa de Docker)

```bash
cd API/JAVA
mvn -B verify -Dtest=NaoExiste -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=ApiJavaContratoTest
```

Sobe a aplicação inteira em porta real sobre um SQL Server 2022 do Testcontainers (Flyway + seed) e executa cada
cenário. Uma diferença aparece com o caminho e os dois valores, por exemplo
`corpo.data.user.role: esperado "gerente" mas veio "caixa"`.
Para limitar a execução, acrescente `-Dcontrato.grupo=empresa,categoria` (nomes separados por vírgula) ao comando.

## Gravar um grupo novo (ou regravar)

1. Suba o ambiente .NET: `contrato/ambiente-dotnet/subir.sh` (constrói a imagem `quack-api-dotnet:contrato` na primeira
   vez — usa um contexto limpo, sem `bin/obj` — e aplica o seed). API em `http://localhost:5270`, SQL Server em
   `localhost:14334` (`sa` / `Contrato#Quack2026`). Production, reCAPTCHA e e-mail desligados, limite por IP em 5000.
2. Escreva o cenário em `src/test/resources/contrato/cenarios/<grupo>.json` (e, para um grupo novo, acrescente o nome em
   `MotorDeContrato.GRUPOS`).
3. Grave: `mvn -B test -Dtest=GravadorDoContrato -Dgroups=gravar-contrato -Dexcluir.grupos=nenhum -Dcontrato.gravar=true -Dcontrato.url=http://localhost:5270`
   (opcional `-Dcontrato.grupo=auth,sessao` para selecionar grupos separados por vírgula).
4. Confira os arquivos novos em `gravacoes/`, versione-os e rode o comparador.
5. `contrato/ambiente-dotnet/descer.sh` derruba tudo.

No Git Bash do Windows, os scripts já usam `MSYS_NO_PATHCONV=1` nos `docker exec/cp`.

## Formato do cenário

```json
{
  "nome": "login-quinta-tentativa-errada-bloqueia",
  "usuario": "admin@contrato.quack",          // opcional: faz login antes e usa o token no pedido
  "outraSessao": true,                         // opcional: faz um 2º login (caminho aceita {outraSessao} e {sessaoAtual})
  "antes": [ {"metodo":"POST","caminho":"/api/Auth/login","corpo":{...},"vezes":4} ],   // passos sem gravação
  "requisicao": {"metodo":"POST","caminho":"/api/Auth/login","corpo":{...},
                 "cabecalhos":{"Origin":"..."}, "auth":"bearer|cookie|nenhum|invalido"},
  "volateis": {"data.lockedUntil":"iso", "data.sessionId":"id"},   // campos que mudam a cada execução
  "divergencia": {...}                          // só para diferença CONSCIENTE (ver abaixo)
}
```

Regras de campo volátil: `tipo` (só o tipo JSON), `id` (mesmo prefixo e tamanho: `sess-…`, `usr-…`), `iso` (data ISO com
fuso), `data` (`yyyy-MM-dd`), `texto` (texto não vazio). Do `Set-Cookie` compara-se a forma (`horuspdv.auth=<valor>;
expires=<data>; path=/; secure; samesite=strict; httponly`), nunca o valor.

## Divergência conhecida: bloqueio de login com a senha certa

A API .NET lê o usuário **sem** as colunas `LockoutEnd`, `FirstFailedLoginAt` e `FailedLoginAttempts`
(`HorusSecurityStore.ReadUser`), então o bloqueio gravado no banco nunca é consultado: depois de 5 senhas erradas, o login
com a senha **certa** ainda devolve 200. O spec `seguranca/autenticacao` exige 429 ("Muitas tentativas inválidas…") até o
fim do bloqueio, e a API Java segue o spec. O cenário `auth/login-bloqueado-com-senha-certa` registra isso no campo
`divergencia` (o comparador confere o 429 da Java em vez de comparar com a gravação). Quando a API .NET for corrigida,
regrave o cenário e remova a `divergencia`.

## Vetores de compatibilidade criptográfica

`vetores-dotnet` referencia o projeto da API .NET e usa o código real (`HorusSecretProtector`, `HorusJwtService`,
`PasswordHasher`). Precisa do SDK .NET 8+ (`RollForward=Major` permite rodar só com o runtime 10):

```bash
cd API/JAVA/contrato/vetores-dotnet
dotnet run -- gerar ../../src/test/resources/contrato/vetores.json   # .NET -> Java (CompatibilidadeDotnetTest lê)
mvn -B test -Dtest=CompatibilidadeDotnetTest                          # Java confere e escreve target/vetores-java.json
dotnet run -- verificar ../../target/vetores-java.json                # .NET confere o que o Java gerou
```
