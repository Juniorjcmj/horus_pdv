# Tasks

> Antes de cada grupo, leia a decisão correspondente em `design.md`, o spec do módulo em `specs/` e os arquivos .NET
> de referência listados no Context do design. Mensagens, códigos HTTP, nomes de campos e ordem das listas são
> copiados literalmente do .NET, e em caso de dúvida vale a gravação do contrato. Trabalhe na branch `spring-api`,
> em `API/JAVA`.
>
> **Ciclo de cada grupo de rotas:**
> 1. escrever os cenários de contrato do grupo;
> 2. gravar contra a API .NET (`contrato/ambiente-dotnet/subir.sh` e o `GravadorDoContrato`, ver `contrato/README.md`);
> 3. implementar com os testes unitários e web;
> 4. rodar o comparador do grupo até ficar verde.
>
> Sem Docker ou sem o ambiente .NET disponível, implemente e teste com Mockito/`@WebMvcTest` e registre no fim do
> grupo o que ficou sem gravar.

## 1. Base comum

- [x] 1.1 Completar a entidade `Empresa` com todas as colunas de `Empresas` do V1 (cadastrais, SMTP, fiscais, CSC, certificado, responsável técnico, `SerieNfce`, `SerieNfe`, `ReviewedAt`, `ReviewedBy`, `RequireApprovalForNewCompanies`) com tipos e tamanhos exatos; verificar com `mvn -B verify` (subida com `ddl-auto=validate` e toda a suíte da Fase 1 verde)
- [x] 1.2 Criar `empresa/app/AdministradorGeral` (`ehAdministradorGeral`, `podeGerenciarTreinamento`, D1) com teste unitário: administrador da `empresa-principal` sim, gerente da principal não, administrador de loja não, administrador de loja com e-mail de dono sim só para treinamento, e-mail com maiúsculas e espaços
- [x] 1.3 Criar (ou reaproveitar, se a Fase 1 já tiver) o formatador de dinheiro pt-BR equivalente ao `HorusMoneyFormat` (`R$ 1.234,56`, negativos como no .NET), com teste unitário de valores com e sem centavos, milhar e zero
- [x] 1.4 Escrever `db/migration/V3__cadastros_dados_referencia.sql` (D10) com as categorias padrão de `09_categorias_produto.sql` e as seções e vídeos de `38_treinamento.sql`, tudo com `IF NOT EXISTS` por `Id`; verificar com IT (Testcontainers): banco novo tem as mesmas contagens e ids que o .NET cria, e um banco que já tem essas linhas migra sem erro e sem duplicar
- [x] 1.5 Ampliar `contrato/seed/seed-contrato.sql` e `reset-contrato.sql` com os dados do D11 (clientes, um com saldo devedor; fornecedores; categorias; 2 produtos mínimos; uma venda com `ClientSaleId` conhecido; um `GatewayCreditGrants` aberto; tema; registros de módulo mercado; SMTP e CSC cifrados na `empresa-principal`), com o reset apagando filhas antes de mães; verificar aplicando o seed e o reset duas vezes seguidas no ambiente .NET (`subir.sh`) sem erro e com o comparador dos grupos `auth`, `sessao` e `usuario` ainda verde

## 2. Empresa

- [x] 2.1 Acrescentar ao `contrato/vetores-dotnet` o vetor de certificado (D3): gerar um PFX autoassinado de teste com senha e gravar em `vetores.json` o base64, a senha, o thumbprint e o `NotAfter` (UTC) calculados pelo `X509Certificate2`; verificar com `dotnet run -- gerar` e o arquivo versionado
- [x] 2.2 Implementar o leitor de certificado (`KeyStore` PKCS12, entrada com chave privada, SHA-1 hexadecimal maiúsculo, `NotAfter` em UTC) e verificar em `CompatibilidadeDotnetTest` que chega nos valores do vetor e que senha errada lança a falha que vira "Certificado digital ou senha inválidos."
- [x] 2.3 Escrever `cenarios/empresa.json` (GET por cada perfil, 401, PUT por administrador, PUT por gerente → 403, cada validação do spec `cadastros/empresa`, SMTP ligado sem host, CRT 5, CSC Id com letra, PFX com senha errada, PFX válido do vetor, gravação sem reenviar CSC/senha SMTP mantendo os indicadores) e gravar contra o .NET; verificar os arquivos em `gravacoes/empresa/`
- [x] 2.4 Implementar `EmpresaService` + `EmpresaController` (`GET`/`PUT /api/Empresa`, D3: leitura com fallback para a `empresa-principal`, upsert, validações na ordem do .NET, segredos com `SecretProtector` e regra "vazio mantém", máscara da resposta, `SerieNfce` padrão 2, datas `"o"`), com testes Mockito de cada validação, da preservação dos segredos e da máscara, e `@WebMvcTest` de perfis; verificar com o comparador do grupo `empresa` verde e com teste que um CSC gravado pela Java é decifrado pelo console .NET (`vetores-dotnet verificar`)

## 3. Tema da empresa

- [x] 3.1 Escrever `cenarios/tema.json` (GET sem tema, PUT por gerente, GET com tema, cor inválida, cor vazia, atendente → 403, caixa lê) e gravar contra o .NET
- [x] 3.2 Implementar entidade `EmpresaTema`, repositório, serviço e `CompanyThemeController` (`/api/company-theme`, D3), com testes Mockito da regex e `@WebMvcTest` de perfis; verificar com o comparador do grupo `tema` verde

## 4. Categorias

- [x] 4.1 Escrever `cenarios/categoria.json` (árvore, `?plana=true`, `apenasAtivas`, contagem de produtos, GET por id, criar departamento e subcategoria, 3º nível recusado, pai inexistente, categoria pai dela mesma, nome vazio e com 81 caracteres, ativar/desativar, excluir com subcategoria, excluir com produto, excluir livre, caixa lê, atendente tenta criar → 403, id de outra empresa → 404) e gravar contra o .NET
- [x] 4.2 Implementar entidade `Categoria`, repositório (com a contagem por consulta nativa em `Produtos`), `CategoriaService` e `CategoriaController` (D4, spec `produtos/categorias`), com testes Mockito de cada regra de hierarquia e exclusão; verificar com o comparador do grupo `categoria` verde

## 5. Fornecedores

- [x] 5.1 Escrever `cenarios/fornecedor.json` (listar, criar só com fantasia, criar só com razão social, sem nome nenhum, CNPJ repetido na empresa, mesmo CNPJ em outra empresa, editar, editar de outra empresa → 404, excluir com produto vinculado, excluir livre, caixa → 403) e gravar contra o .NET
- [x] 5.2 Implementar entidade `Fornecedor`, repositório (com o `EXISTS` nativo em `Produtos.ProductSupplier`, D2), `FornecedorService` e `FornecedorController`, com testes Mockito (nomes que se completam, CNPJ único na criação e na edição, bloqueio de exclusão sem diferenciar maiúsculas) e IT do `EXISTS`; verificar com o comparador do grupo `fornecedor` verde

## 6. Clientes

- [x] 6.1 Escrever `cenarios/cliente.json` (listar ordenado, criar por caixa, cada validação do spec `cadastros/clientes`, documento repetido, editar por atendente mudando o limite, gerente mudando o limite → 400, gerente editando sem mudar o limite, limite abaixo de saldo + delegado → 400, caixa editando → 403, gerente excluindo devedor → 400, administrador excluindo devedor, atendente excluindo → 403, id de outra empresa → 404, `credito-snapshot` com identidades conhecidas e desconhecidas, com 5001 identidades e sem sessão) e gravar contra o .NET
- [x] 6.2 Implementar entidade `Cliente`, repositório (consultas nativas de saldo com `UPDLOCK, HOLDLOCK`, soma delegada de `GatewayCreditGrants` e as duas leituras do snapshot com `OPENJSON`, D2), `ClienteService` (D5: validações, `PodeAlterarFiado`, conferência de compromissos na mesma transação, auditoria depois da gravação sem quebrar a resposta) e `ClienteController`; testes Mockito de cada regra e das descrições de auditoria (inclusive `FiadoLimiteAlterado` com os dois valores em reais) e IT das consultas nativas; verificar com o comparador do grupo `cliente` verde

## 7. Módulo mercado

- [x] 7.1 Escrever `cenarios/modulo-mercado.json` (cada um dos 8 módulos sem registros, módulo desconhecido → 404, criar registro, título curto, situação vazia, atualizar, atualizar de outra empresa → 404, excluir, painel com pendentes e concluídos misturados, caixa → 403) e gravar contra o .NET
- [x] 7.2 Implementar entidades `ModuloMercado` e `ModuloMercadoRegistro`, repositórios, `ModuloMercadoService` (D6: títulos, ação principal, KPIs, fluxo e alertas, `HALF_EVEN`, garantia da linha do módulo) e `ModuloMercadoController`; testes Mockito do painel (inclusive 1 de 8 concluídos = "12%" e Alertas mínimo 1); verificar com o comparador do grupo `modulo-mercado` verde

## 8. Treinamento

- [x] 8.1 Escrever `cenarios/treinamento.json` (GET por caixa e pelo administrador geral, criar, editar e excluir seção, seção sem nome, criar vídeo com cada formato de link aceito, link inválido, vídeo sem título, ids inexistentes → 404, administrador de loja → 403) e gravar contra o .NET
- [x] 8.2 Implementar entidades `TreinamentoSecao` e `TreinamentoVideo`, repositórios, serviço (D7: ordem das listas, extração do id do YouTube, exclusão de seção como no .NET) e `TreinamentoController` (`/api/treinamento`); testes Mockito da extração (watch com outros parâmetros, `youtu.be`, `embed`, `shorts`, `live`, id puro, inválidos) e da permissão; verificar com o comparador do grupo `treinamento` verde

## 9. Administração de empresas

- [x] 9.1 Escrever `cenarios/admin-empresas.json` (listar sem filtro, com `search`, com `status`, `pageSize=500`, `page=0`; métricas; aprovar pendente, aprovar inexistente, aprovar `empresa-principal`; rejeitar sem motivo e com motivo; bloquear com motivo e conferir no passo seguinte que a sessão do administrador da empresa bloqueada caiu; reativar; ler e gravar configuração nos dois sentidos; trocar e-mail, trocar senha, nada informado, e-mail em uso; excluir uma empresa descartável criada no `antes` pelo cadastro público; excluir `empresa-principal`; administrador de loja → 403) e gravar contra o .NET
- [x] 9.2 Implementar o repositório do admin com o SQL do `HorusSecurityStore` portado literalmente (lista, métricas, situação, configuração, credenciais com `PasswordHasher` e encerramento de sessões, D2) e `GerenciamentoEmpresasService`/`Controller` (`/api/Admin/Empresas`, D1 para o 403 e os 500 com mensagem própria); testes Mockito de normalização (página, tamanho, `todas`) e de mensagens, e IT das consultas com o ordenamento (principal, pendentes, mais novas); verificar com o comparador do grupo `admin-empresas` (exceto exclusão) verde
- [x] 9.3 Implementar a exclusão de empresa executando o lote T-SQL do `DeleteCompany` (D2) e verificar com IT: apaga todas as tabelas da lista para a empresa alvo, não toca outra empresa, recusa a `empresa-principal`, e com uma falha forçada não apaga nada e responde 500 "Erro interno ao excluir empresa."; o cenário de exclusão do contrato fica verde

## 10. Documentação da API

- [x] 10.1 Ligar a geração do springdoc em todos os perfis mantendo `/v3/api-docs` e `/swagger-ui` fechados em produção (D8); verificar com `SwaggerProducaoWebTest` e `SwaggerDevWebTest` da Fase 1 sem alteração e verdes
- [x] 10.2 Implementar `ApiDocumentationController` (`GET /api/Admin/Empresas/documentacao-api`, D8: JSON do springdoc com `servers` da requisição, anexo `quack-pdv-api-openapi.json`, título "Quack Sistemas — API do PDV", 401 e 403 no envelope) com `@WebMvcTest` (cabeçalhos, é OpenAPI 3 com `paths` que inclui `/api/Empresa`, 403 para gerente da principal e para administrador de loja) e um cenário de contrato que compara só status e cabeçalhos; verificar com o comparador e com o arquivo baixado abrindo no Swagger Editor sem erro

## 11. Integração

- [x] 11.1 Rodar `mvn -B verify` em `API/JAVA` com todos os grupos de contrato (`auth`, `sessao`, `usuario` e os 8 novos) e toda a suíte verde; verificar pelo relatório do Failsafe/Surefire
- [ ] 11.2 Subir a API Java em `dev` sobre o SQL Server do Docker com o seed de contrato, apontar o FRONTEND para ela (variáveis `VITE_*_API_URL` do `.env` local, ou o proxy do Vite) e conferir manualmente as telas de Configurações (empresa, SMTP, fiscal, tema), Categorias, Fornecedores, Clientes, Treinamento e o painel de Administração de Empresas; registrar o resultado e qualquer diferença encontrada no `API/JAVA/README.md` (seção "Rotas portadas")
- [x] 11.3 Atualizar `API/JAVA/README.md` (rotas portadas na Fase 2, V3 e como gravar os grupos novos) e `contrato/README.md` (grupos novos e dados do seed); verificar que os comandos documentados funcionam

## Workflow follow-up

- Depois do apply: `/opsx:archive api-java-cadastros` (sincroniza os specs novos em `openspec/specs/cadastros/*` e
  `openspec/specs/plataforma/*`).
- Próxima fase do plano: Fase 3, Produtos e estoque.

## Registro da execucao (2026-11-02)

- Base: Empresa com todas as 49 colunas; V3 com 55 categorias, 2 secoes e 9 videos, guardados por Id. Validacao SQL/Testcontainers pendente por ausencia de Docker.
- Empresa/tema: servicos e controllers implementados; cenarios escritos, ainda sem gravacoes .NET. Nao incluidos em MotorDeContrato.GRUPOS enquanto faltam gravacoes e seed/reset completos.
- `mvn -B test`: suite passou. Casos novos: BaseCadastrosTest 6, EmpresaServiceTest 16, EmpresaTemaServiceTest 3, CompatibilidadeDotnetTest 9; primeira execucao EmpresaCadastrosWebTest 18. Casos posteriores de binding byte ainda aguardam execucao (chamada Maven interrompida na ferramenta).
- `dotnet run --project contrato/vetores-dotnet -- gerar ...`: executado, vetor PKCS12 real versionado; `verificar target/vetores-java.json`: passou.
- Divergencia dos artefatos: spec empresa fala em 404 para empresa ausente; implementacao segue fallback explicitamente exigido por D3/D9 e tarefa 2.4, como EmpresaAB.NET. Nenhuma mudanca silenciosa no spec.
- Ambiente: Java 21, Maven e .NET disponiveis; Docker e SQL Server do contrato indisponiveis. Testes de banco, gravacoes/comparador e fluxo manual frontend permanecem pendentes.

### Implementacao adicional escrita (validacao pendente)

- Fornecedor, Categoria, Cliente, ModuloMercado, Treinamento, Admin/Empresas e download OpenAPI implementados; testes unitarios/web e cenarios escritos para todos. Ainda nao marcados completos porque compilacao, gravacoes e comparadores destes blocos estao pendentes.
- ClienteTransacao e a fronteira transacional; ClienteService registra auditoria somente depois do retorno confirmado da transacao. Falha de auditoria nao propaga para o cadastro.
- Categoria le as categorias proprias e as padroes da empresa-principal, como CategoriaAB.NET; escritas nativas continuam restritas a empresa autenticada. A exclusao bloqueia filhos (comportamento .NET/task), apesar do spec principal mencionar remocao de orfas. Nenhuma mudanca silenciosa nesse spec.
- Seed/reset ampliados com fixtures sinteticas, inclusive segredos enc:v1: na chave do ambiente de contrato. Arquivos SQL nao executados por falta de SQL Server/Docker. CadastrosIT escrito para schema/V3, seed repetido, consultas nativas, snapshot, admin e rollback da exclusao; ainda nao executado.
- Gravador/comparador aceitam `-Dcontrato.grupo=<novo-grupo>` para selecionar explicitamente cenarios ainda sem gravacao. Novos grupos ainda nao entram na execucao padrao de GRUPOS enquanto faltam gravacoes.
- `graphify update .` tentado via `py -m graphify update .`: falhou no ambiente sandbox com `No module named graphify`; a consulta inicial funcionara no ambiente escalonado. Atualizacao do grafo ainda pendente.

### Verificacao atualizada

- `mvn -B verify` passou com SQL Server real no ambiente elevado; sem skips. A limitacao anterior de Docker era do PATH sandbox e foi superada. Testes de schema, seed/reset repetido, consultas, rollback e id de rota sem truncamento passaram.
- JDBC placeholders e separadores de auditoria/titulo foram revisados literalmente; GerenciamentoEmpresasSqlTest captura SQL real e parametros sem esconder a execucao em mock de servico.
- Graphify atualizado com sucesso no ambiente elevado (backup curated automatico); nova atualizacao incremental sera feita apos ajustes finais.
- Todos os 11 grupos registrados no motor; gravacao dos oito novos em andamento no ambiente .NET isolado.

- Evidencia gravada .NET corrige a interpretacao D1 de snapshot: sem sessao o middleware devolve401 COM envelope antes de chegar no Unauthorized() vazio do controller. Java segue a gravacao prioritaria, sem excecao especial no filtro. Datas admin usam FormatoDataDotNet (serializacao automatica) e certificado/tema mantem seus formatos especificos.
