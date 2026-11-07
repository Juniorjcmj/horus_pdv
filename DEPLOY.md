# Deploy do Hórus PDV — GitHub Actions + ghcr.io + Portainer/Traefik

Runbook de configuração inicial. Depois de feito uma vez, o fluxo do dia a dia é só
`git push` na `main` — o resto é automático.

## Visão geral

### Página de documentação (GitHub Pages)

O workflow `Deploy Docs` publica a pasta `docs` e é independente da publicação
da API e do frontend pelo Docker. Para ativá-lo, abra **Settings → Pages → Build
and deployment → Source** no repositório e selecione **GitHub Actions**. Depois,
execute **Actions → Deploy Docs → Run workflow**.

Quando Pages não está configurado ou não está acessível ao token do workflow,
a publicação da documentação é ignorada com um aviso e instruções no resumo.
Falhas de autenticação, permissões e serviço diferentes de HTTP 404 continuam
interrompendo a execução. Uma execução ignorada não significa site publicado.

Não basta adicionar `enablement: true` em `actions/configure-pages`: a habilitação
automática exige um token diferente de `GITHUB_TOKEN`, com as permissões indicadas
na [documentação oficial da ação](https://github.com/actions/configure-pages/blob/v5/action.yml).

```
push na main
   │
   ▼
GitHub Actions (.github/workflows/docker-publish.yml)
   │  builda API e frontend, publica em ghcr.io/<seu-usuario>/horus-pdv-{api,frontend}
   ▼
(opcional) webhook do Portainer
   │
   ▼
Portainer puxa as imagens novas e recria os containers
   │
   ▼
Traefik (já existente na rede OrionNet) expõe:
   - https://pdv.quacksistemas.com.br       → container pdv-frontend (nginx, build estático)
   - https://api-pdv.quacksistemas.com.br   → container pdv-api (.NET, porta 8080)
                                           → container pdv-sqlserver (SQL Server, sem acesso externo)
```

Dois hostnames separados (frontend e API), conforme confirmado — não é um proxy único.

## 1. DNS

Aponte os dois hostnames para o IP do seu servidor Docker (mesmo IP que já resolve o
resto do que está atrás do Traefik):

- `pdv.quacksistemas.com.br` → A/AAAA para o servidor
- `api-pdv.quacksistemas.com.br` → A/AAAA para o servidor

## 2. GitHub — permitir o workflow publicar em ghcr.io

Settings do repositório → **Actions → General → Workflow permissions** → marque
"Read and write permissions". Sem isso o `docker/login-action` falha ao dar push no
registry (o `GITHUB_TOKEN` do workflow já cobre a autenticação, só precisa da permissão
habilitada uma vez).

Se o pacote (`horus-pdv-api`/`horus-pdv-frontend`) nascer privado no ghcr.io e o Portainer
precisar puxá-lo, gere um Personal Access Token (classic) com escopo `read:packages` e
faça `docker login ghcr.io` com ele na própria VPS antes do primeiro `docker compose up`
— ou torne os pacotes públicos em Package settings no GitHub (mais simples, se não tiver
problema com o código das imagens serem visíveis).

## 3. GitHub — variáveis e secrets do workflow (opcionais)

Settings → **Secrets and variables → Actions**:

| Tipo     | Nome                          | Para quê |
|----------|-------------------------------|----------|
| Variable | `VITE_API_ORIGIN`              | Só se a API não for `https://api-pdv.quacksistemas.com.br` (esse já é o default no workflow). |
| Secret   | `RECAPTCHA_SITE_KEY`           | Site key pública do reCAPTCHA v3, se for usar. Vazio = reCAPTCHA desabilitado no frontend. |
| Secret   | `PORTAINER_WEBHOOK_URL`        | Webhook de redeploy do Portainer (stack ou serviço da API) — ver passo 6. |
| Secret   | `PORTAINER_WEBHOOK_URL_FRONTEND` | Um segundo webhook, só se sua versão do Portainer expõe um por serviço em vez de um por stack. |

Nenhum é obrigatório para o build funcionar — sem os webhooks, as imagens só ficam
publicadas e você atualiza o stack manualmente no Portainer (botão "Update the stack" /
"Pull and redeploy").

## 4. Rede externa no Docker

Se `OrionNet` já existe (é a rede que o Traefik e os outros serviços já usam, pela sua
mensagem), não precisa criar de novo. Se for a primeira stack a usar esse nome, crie uma
vez no host:

```bash
docker network create OrionNet
```

## 5. Subir o stack no Portainer

**Stacks → Add stack**, método "Repository" apontando para este repositório/branch, com
"Compose path" = `docker-compose.yml` (assim toda atualização do arquivo no git já reflete
no próximo redeploy) — ou "Web editor" colando o conteúdo do `docker-compose.yml` se
preferir não linkar o repositório.

Em **Environment variables**, preencha (ver `.env.stack.example` para a lista completa
com descrição de cada uma):

- `GHCR_NAMESPACE` — seu usuário/organização do GitHub, **em minúsculo**.
- `MSSQL_SA_PASSWORD` — gere com `openssl rand -base64 24`.
- `JWT_SECRET` e `ENCRYPTION_KEY` — gere cada um com `openssl rand -base64 48`.
  **`ENCRYPTION_KEY` guarde num cofre de senhas** — é o que cifra CSC, senha do
  certificado digital e senha de SMTP no banco; trocar depois invalida tudo isso.
- Deixe `EMAIL_ENABLED=false` e `RECAPTCHA_ENABLED=false` se ainda não for configurar
  essas duas coisas agora — o PDV funciona sem elas.

Clique em **Deploy the stack**.

## 6. (Opcional) Webhook de redeploy automático

No Portainer, abra o stack (ou o serviço, dependendo da versão/edição) → aba
**Webhooks** → habilite e copie a URL. Cole em `PORTAINER_WEBHOOK_URL` nos secrets do
GitHub (passo 3). A partir daí, todo push na `main` já termina com o Portainer puxando a
imagem nova sozinho — sem o webhook, o build e o push continuam acontecendo normalmente,
só falta você clicar em "Pull and redeploy" no Portainer depois.

## 7. Primeira subida

1. Confirme que `docker-publish.yml` rodou com sucesso na aba **Actions** do GitHub (os
   dois jobs `build-api` e `build-frontend` verdes).
2. No Portainer, confira os logs do `pdv-sqlserver` até aparecer pronto para aceitar
   conexões, e do `pdv-api` até aparecer "Script SQL DataBase/Resumo.sql executado com
   sucesso" — é o `HorusDatabaseInitializer` criando o schema na primeira vez.
3. Acesse `https://pdv.quacksistemas.com.br` — login inicial padrão do seed (`Resumo.sql`) usa
   CPF `06.332.765/0001-05`; troque a senha e os dados da empresa assim que entrar.
4. Configure os dados fiscais em **Minha Empresa** (CRT, ambiente, CSC, certificado) antes
   da primeira venda — ver `MODULO-FISCAL-E-PEDIDOS.md`.

## Falha `429 Too Many Requests` ao baixar imagens no build

Esse erro durante o download de `node:20-alpine` ou `nginx:1.27-alpine` vem do limite
de requisições do Docker Hub. O frontend usa as mesmas versões das imagens oficiais
disponíveis em `public.ecr.aws/docker/library/`, sem exigir conta ou secrets da AWS.
O Dockerfile também usa o interpretador integrado ao BuildKit para evitar o download
adicional de `docker/dockerfile:1` do Docker Hub.

Envie a correção para `main` para iniciar uma publicação com o Dockerfile atualizado.
Reexecutar o job do commit antigo continua usando o arquivo antigo. Também é possível
iniciar o workflow manualmente em **Actions**, selecionando a branch que contém a correção.
O aviso de depreciação de `punycode` não é a causa dessa falha.

## Aviso esperado nos logs da API

O container só escuta HTTP na porta 8080 (quem termina TLS é o Traefik) — o
`app.UseHttpsRedirection()` do `Program.cs` não encontra uma porta HTTPS configurada e
loga um aviso único do tipo "Failed to determine the https port for redirect" no boot.
É esperado e inofensivo neste desenho (o middleware simplesmente não redireciona nada,
já que o tráfego externo já chega como HTTPS via Traefik).

## Notas

- O SQL Server sobe com `MSSQL_PID=Express` (grátis, licenciado para produção, até 10 GB
  por banco e uso limitado de CPU/memória) — se o PDV crescer além disso, troque para uma
  edição paga ajustando essa variável.
- O volume `horus-pdv-mssql-data` contém o banco inteiro. Use o backup nativo descrito abaixo
  para obter uma cópia consistente; preserve também os segredos de configuração da API.
- Este runbook cobre o deploy em si; ele **não substitui** rodar `dotnet build` localmente
  pelo menos uma vez antes do primeiro push — ver `MODULO-FISCAL-E-PEDIDOS.md` para o que
  ainda falta verificar no código.

## Backup completo pelo Administrador Geral

No **Gerenciamento Geral de Empresas**, use **Fazer backup completo**. Quando a geração e a
verificação terminarem, clique em **Baixar backup**. A cópia `.bak` inclui todas as empresas,
tabelas, relacionamentos, índices e dados do banco configurado em `ConnectionStrings:HorusPdv`,
inclusive documentos fiscais e certificados armazenados nele. O arquivo é transmitido diretamente
ao aplicativo/navegador, sem montar um JSON ou carregar o banco inteiro na memória da frente de caixa.

Publique **API e frontend** e atualize o **stack inteiro** com o `docker-compose.yml` deste
repositório. Atualizar somente as imagens não adiciona os volumes. O serviço `pdv-backup-init`
prepara o volume `horus-pdv-backups` com dono `10001:0` e modo `2770`, sem alterar o volume de dados.
O bit de grupo herdado faz o arquivo nativo do SQL Server pertencer ao grupo `0`, permitindo
que a API leia a cópia sem liberar acesso a outros usuários do servidor.
SQL Server e API acessam a mesma pasta pelas configurações:

- `DatabaseBackup__SqlDirectory=/var/opt/mssql/backups`: pasta vista pelo SQL Server, com escrita.
- `DatabaseBackup__StorageDirectory=/var/opt/horus/backups`: mesma pasta montada na API, com leitura
  e limpeza; a API permanece no usuário `app`, com grupo suplementar `0`.

Em Swarm com vários nós, mantenha SQL Server, API e inicialização no mesmo nó que já contém o
volume SQL, ou utilize armazenamento compartilhado apropriado. Volumes locais com nomes iguais
em nós diferentes não representam a mesma pasta. O serviço de inicialização deve ser executado
uma vez para provisionar as permissões antes do primeiro backup.

As três rotas `/api/Admin/Empresas/backup`, `/{id}` e `/{id}/arquivo` exigem sessão ativa com
perfil principal `administrador` e empresa `empresa-principal`. O download é restrito ao usuário
que solicitou a cópia, com `Cache-Control: no-store`; solicitação, conclusão e download ficam
na auditoria. Existe uma geração por vez, que continua no servidor após sair da tela.
O painel acompanha novamente o pedido ao voltar ou recarregar a mesma aba. Os arquivos ficam
disponíveis por seis horas e a limpeza roda a cada 30 minutos. Após reiniciar a API, pedidos em
memória deixam de estar disponíveis: gere outro backup; arquivos antigos são removidos pela limpeza.

A geração usa [`BACKUP DATABASE ... WITH COPY_ONLY, CHECKSUM`](https://learn.microsoft.com/en-us/sql/t-sql/statements/backup-transact-sql)
e `RESTORE VERIFYONLY ... WITH CHECKSUM`, sem compressão para funcionar no SQL Server Express.
`COPY_ONLY` preserva a sequência dos backups existentes. Verificação não substitui testar uma
restauração: faça o teste em uma instância isolada, nunca sobre a base em uso.

Para restaurar em outro servidor, mantenha a mesma `Security__EncryptionKey` em um cofre seguro:
ela é necessária para ler CSC, senhas e certificados cifrados presentes no banco. Segredos do
stack, arquivos externos, bancos do Gateway e vendas offline ainda não sincronizadas não fazem
parte do banco central; preserve suas configurações e backups locais separadamente.


### Arquivo de notas fiscais de entrada (desktop 1.3.6)

Publique a API junto com o frontend/instalador. Na inicialização, a API aplica a migração
`42_notas_entrada_arquivo.sql` e cria `NotasEntradaArquivo`. Nenhum diretório ou serviço de
arquivos adicional é necessário: o XML original fica como binário no SQL Server, incluído
no backup completo do banco. Confira o espaço disponível no banco conforme o volume de XMLs.

A confirmação grava fornecedor, produtos, estoque, lotes e nota em uma única transação SQL
local. Chaves repetidas na mesma empresa são recusadas antes de alterar estoque, inclusive
em confirmações concorrentes. Arquivos sem chave usam o hash do XML para detectar repetição.
Pré-visualizar ou cancelar a importação não arquiva a nota. Cada entrada conserva os itens
revisados, a data e o operador; o valor fiscal do XML é exibido separadamente do custo dos
itens recebidos. Download do XML exige sessão e pertence somente à empresa da nota.

Cupons digitados registram chave e itens como `digitada`, sem fabricar XML. Clientes antigos
que não enviam documento conservam o movimento como `sem-documento`; atualizar o instalador
é necessário para enviar o arquivo original. Notas anteriores à implantação não podem ser
recuperadas automaticamente. Não reimporte notas antigas somente para guardar XML, pois
se ainda não houver registro da chave isso dará uma nova entrada no estoque.


### Conferência fiscal e XML completo (1.3.7)

Publique a API e o frontend juntos. A API deve incluir `DataBase/FiscalTables/ncm.json` e `cclass.json` (já configurados no projeto para saída/publicação), além dos schemas fiscais existentes. A nova rota de produto é somente leitura e exige perfil administrador, gerente ou atendente. Os códigos fiscais não se tornam campos obrigatórios para salvar produto.

A consulta de referências tenta as fontes públicas Siscomex e SVRS, com limite de tempo e cache; sem internet, retorna a referência datada e indica a limitação. Nenhum dado da loja é enviado. O cálculo do emissor usa uma referência versionada e cobre os casos comuns documentados de 2026; códigos especiais não suportados exigem revisão, sem substituição silenciosa. Alterações de lei, opções tributárias para 2027, FECP/ST/benefícios e classificações por composição precisam de revisão contábil e técnica específica.

Não é necessária migração para recuperar as notas antigas: os downloads compõem `nfeProc` usando `XmlAssinado` e o protocolo anteriormente salvo em `XmlProtocolado`, validando chave/digest e preservando a assinatura. O banco original não é regravado. Se um dos registros necessários não existir, a exportação informa a pendência. Reexportar o mês após publicar para fornecer XMLs completos à contabilidade; o ZIP antigo de protocolos não contém NCM ou tributos.

## Servidor da loja: `pdv-api-sync` (sync-pdv)

Receptor da sincronização das lojas com servidor local (change `api-java-modo-loja`). É a **API Java** em perfil
`nuvem`: responde só `/api/loja-sync/**` e `/actuator/health` (o resto devolve 404). A API .NET (`pdv-api`) e o
frontend **não mudam**. O serviço está no `docker-compose.yml` com `profiles: ["nuvem"]`: **sem o perfil ligado, nada
muda no stack**.

### Subida

1. DNS: `sync-pdv.quacksistemas.com.br` → A/AAAA para o servidor Docker (igual ao `api-pdv`).
2. Imagem: o workflow `api-java-publish.yml` publica `ghcr.io/<dono>/horus-pdv-api-java` (`latest` e o SHA) a cada push
   em `main` que mude `JAVA/api/**`, ou por *Run workflow*. Opcional: `IMAGE_TAG_JAVA` fixa a tag no stack.
3. Ligue o perfil: `docker compose --profile nuvem up -d` (ou `COMPOSE_PROFILES=nuvem` nas variáveis do stack).
   O serviço reutiliza `MSSQL_SA_PASSWORD`, `JWT_SECRET` e `ENCRYPTION_KEY` do stack: **precisam ser os mesmos da
   `pdv-api`** (o receptor valida o administrador pelo JWT da .NET e decifra segredos com a mesma chave).
   > Portainer em modo Swarm (`docker stack deploy`) não entende `profiles`. Use um stack em modo Standalone/Compose
   > ou copie o serviço para um stack à parte.
4. A **primeira subida** aplica `db/nuvem/V1` (histórico próprio `flyway_quack_sync`): cria as tabelas `Sync*` e liga o
   **Change Tracking** (retenção de 3 dias, `AUTO_CLEANUP`) e o `ALLOW_SNAPSHOT_ISOLATION` no banco `HorusPdv`, em 38
   tabelas. Nenhuma tabela da .NET é alterada além de ligar o rastreamento.
5. Confira: `https://sync-pdv.quacksistemas.com.br/actuator/health` → `{"status":"UP"}`; e no banco
   `SELECT COUNT(*) FROM sys.change_tracking_tables` (38) e `SELECT snapshot_isolation_state_desc FROM sys.databases
   WHERE name = 'HorusPdv'` (`ON`).

### Primeira implantação real (06-07/11/2026): o que aconteceu

Produção roda Portainer em **Swarm** com o compose antigo; o `pdv-api-sync` entrou como bloco à parte (sem `profiles`).

| Sintoma | Causa | O que fazer |
|---|---|---|
| Portainer: "Request failed with status code 500" ao salvar o stack | Compose novo do repositório (com `profiles` e `pdv-backup-init` em `network_mode: none`) não vale no Swarm | Manter o compose de produção e acrescentar só o serviço `pdv-api-sync` |
| "task … not found"; `docker service ps` mostra `failed to resolve reference … not found` | `horus-pdv-api-java:latest` **nunca foi publicada**: `api-java-publish.yml` só existe na `spring-api` e o GitHub só oferece *Run workflow* para workflow que está na `main` | Até a Java ir para a `main`, usar `ghcr.io/juniorjcmj/quack-pdv-api-java:spring-api` (gerada pelo `api-java.yml` a cada push na `spring-api`) |
| Mesmo com o registry cadastrado no Portainer, o Swarm não baixa a imagem privada | O login do Portainer não chegou ao serviço | `docker login ghcr.io` no servidor e `docker service update --with-registry-auth --force pdv_pdv-api-sync`; ou deixar o pacote **Public** |
| Imagem nova publicada, serviço continua na antiga | Swarm guarda o digest da tag | Portainer → Services → `pdv_pdv-api-sync` → *Update the service* com **Pull latest image version** |

### Cuidado: chave primária e recriação de tabela

Com o Change Tracking ligado, o SQL Server **não permite** alterar a chave primária de uma tabela rastreada, nem
recriar a tabela (`DROP`/`SELECT INTO`/troca de nome) sem antes desligar o rastreamento nela. Se uma versão futura da
API .NET (ou um script manual) precisar disso: desligue o tracking da tabela
(`ALTER TABLE dbo.[Tabela] DISABLE CHANGE_TRACKING`), faça a mudança e religue com
`ALTER TABLE dbo.[Tabela] ENABLE CHANGE_TRACKING WITH (TRACK_COLUMNS_UPDATED = OFF)` (ou reaplique o
`db/nuvem/V1__sync_nuvem.sql` com `sqlcmd`: ele é idempotente e religa o que faltar). Religar zera o histórico da
tabela: as lojas fazem a ressincronização completa. **Ainda não existe verificador automático** que religue tabela
esquecida na subida do serviço (previsto no design, não implementado na 4b: pendência de operação, fica para uma fase seguinte): confira com
`SELECT COUNT(*) FROM sys.change_tracking_tables` (38). Avise quem mantém a .NET.

### Reverter (rollback da nuvem)

1. Pare o serviço: `docker compose --profile nuvem stop pdv-api-sync` (sem tracking ele só acumularia erros).
2. Rode `JAVA/api/db/nuvem/desligar.sql` no banco `HorusPdv` (`sqlcmd -S <servidor> -d HorusPdv -i desligar.sql`):
   desliga o tracking das tabelas e do banco e o snapshot, e **mantém** as tabelas `Sync*`. É idempotente.
3. As lojas continuam vendendo. Para voltar: reaplique o `db/nuvem/V1__sync_nuvem.sql` com `sqlcmd` (idempotente: religa
   o snapshot, o banco e as tabelas) e suba o `pdv-api-sync`; cada loja faz a ressincronização completa.

### Receptor: propriedades que importam

O receptor lê as mesmas propriedades da API (variável de ambiente = nome em maiúsculas com `_`): `QUACK_SYNC_DETECTOR_ATIVO`
(`true`, liga o detector das edições do painel e do MCP) e `QUACK_SYNC_COMANDOS_ESPERA_MS` (`25000`, long-poll dos
comandos; atrás do Traefik mantenha o *timeout* de resposta acima de 30 s). A lista completa de `quack.sync.*`, as rotas
`/api/loja-sync/**` (`conferencia`, `situacao`, `recuperar`, `carga`, `ativar`, `comandos`, `quarentena`) e os horários
estão em `JAVA/api/README.md` (seção "Modo loja e sincronização").

### Servidor da loja: instalação, alertas e horários

A máquina da loja usa `JAVA/api/instalador-loja/` (ver `CHECKLIST.md`). Pontos que o operador precisa saber:

- **SMTP da loja obrigatório para os alertas por e-mail.** Sem ele os alertas ficam só no log e na tabela `SyncAlertas`
  (ninguém é avisado). Use o SMTP da empresa (Minha Empresa, no painel; desce para a loja) ou `QUACK_EMAIL_*` no
  `config\loja.env`.
- **Backup às 02:00** (tarefa `QuackLoja-Backup`, retenção de 7) e **conferência loja/nuvem às 03:00**
  (`quack.sync.conferencia-cron`), horários confirmados pelo dono.
- Depois de restaurar um backup o servidor se recupera sozinho da nuvem (`restaurar.ps1` deixa o marcador; vendas e envio
  ficam parados até terminar). Acompanhe por `GET /api/loja-sync/situacao` (`recuperando`).
- Nenhuma mudança na `pdv-api` (.NET) nem no frontend é necessária para a loja: os caixas só trocam o endereço da API.

### Custo medido (Change Tracking + snapshot)

Método: `JAVA/api/contrato/medicao-tracking/medir.sh` (SQL Server 2022 em container descartável, nunca no banco real;
dois bancos com o esquema da API: sem e com o `db/nuvem/V1`). Carga: 10 000 vendas por rodada, 3 rodadas alternadas, cada
venda uma transação com 11 linhas (1 `Vendas`, 3 `VendaItens`, 1 `VendaPagamentos`, 3 `EstoqueMovimentacoes`, 3 `UPDATE`
em `Produtos`). Máquina de desenvolvimento (Docker Desktop), então vale a proporção, não o valor absoluto.

| Medida | Sem tracking | Com tracking + snapshot |
|--------|-------------|-------------------------|
| Tempo de 10 000 vendas (mediana de 3) | 107,8 s (93 vendas/s) | 112,2 s (89 vendas/s), **+4,0%** |
| Espaço usado após 30 000 vendas | 121,7 MB | 169,0 MB (+47,3 MB; tabelas internas do tracking: 21,8 MB, 0,73 KB por venda) |
| Version store (tempdb) após a carga, sem leitor | n/d (snapshot desligado) | 14,7 MB, voltou a 0 MB na limpeza |
| Version store com um leitor `SNAPSHOT` aberto durante 10 000 vendas | n/d | pico 23 MB; 0 MB depois que o leitor fechou |

Leitura: o custo de escrita é pequeno (~4%) e o `tempdb` quase não cresce, porque as transações de leitura do
receptor são curtas (uma página). O histórico do tracking é limitado pela retenção de 3 dias: uma loja com 1 000 vendas
por dia guarda ~2 MB. Vigie `sys.dm_db_file_space_usage.version_store_reserved_page_count` se algum dia houver leitor
longo. Suíte de contrato da .NET (`contrato/medicao-tracking/suite-dotnet.sh`, 1 152 gravações contra a API .NET real): 1 896 s sem o `db/nuvem/V1` e 1 784 s com ele aplicado; mesmos status em todas as respostas (a .NET sobe e grava normalmente com o tracking ligado). Método e detalhes no `design.md` (Notas do grupo 7).

## Monitoramento (Prometheus + Grafana)

Change `api-java-monitoramento`. A API Java expõe métricas em `/actuator/prometheus` (protegidas por token), as lojas
relatam seu estado à nuvem a cada minuto (`POST /api/loja-sync/estado`) e dois serviços novos da stack `pdv` coletam e
mostram tudo: `pdv-monitor-prometheus` (30 dias de histórico, **sem rota pública**) e `pdv-monitor-grafana` (login
obrigatório, atrás do Traefik). Painéis: **Sincronização das lojas** (contato, última sincronização, atraso do envio,
idade e sucesso do backup, comandos pendentes/parados, conflitos 24 h, quarentena, versão do programa) e **API Java**
(requisições/s, P95/P99 por rota sem o long-poll de comandos, Hikari, threads, heap, GC, detector, lotes).

Configuração e painéis moram em `monitoramento/` e vão **dentro de imagens** publicadas pelo workflow
`.github/workflows/monitoramento.yml` (`ghcr.io/<dono>/quack-monitor-prometheus` e `quack-monitor-grafana`, tags
`spring-api`/`latest` e o SHA do commit). Imagem, e não bind mount, porque a stack do Portainer é colada à mão e o nó não
tem o repositório. Versões fixadas: `prom/prometheus:v3.5.1` e `grafana/grafana-oss:12.1.1`.

### Ordem de implantação

1. **Receptor com token.** Gere o token (`openssl rand -hex 32`) e acrescente ao `pdv-api-sync`
   `QUACK_METRICS_TOKEN: "<token>"` (no repositório: `"${METRICS_TOKEN}"`). Atualize a imagem do serviço
   (*Update the service* com **Pull latest image version**; imagem privada: `docker login ghcr.io` no nó e
   `docker service update --with-registry-auth --force pdv_pdv-api-sync`). Sem o token a rota de métricas não existe (404),
   mas o receptor novo já aceita o relato das lojas. A migração `db/nuvem/V3__estado_loja.sql` roda sozinha na subida.
2. **DNS:** `monitor-pdv.quacksistemas.com.br` → IP do nó (igual ao `sync-pdv`). Trocar o domínio muda só a label do Traefik
   e `GF_SERVER_ROOT_URL`/`GF_SERVER_DOMAIN`.
3. **Variáveis da stack no Portainer:** `METRICS_TOKEN` (o mesmo token do passo 1) e `GRAFANA_ADMIN_PASSWORD` (senha forte;
   o Grafana só a lê na primeira subida com o volume vazio, depois troque pela tela de perfil).
4. **Imagens:** depois do push em `spring-api` (ou *Run workflow*), confira em ghcr.io que `quack-monitor-prometheus` e
   `quack-monitor-grafana` existem. Se forem privadas, o mesmo `docker login ghcr.io` do nó vale.
5. **Serviços e volumes:** cole o bloco abaixo na stack `pdv` (Swarm; sem `profiles`) e declare os volumes
   `horus-pdv-prometheus-data` e `horus-pdv-grafana-data` na seção `volumes:`.
6. **Servidor da loja:** na próxima atualização (`instalar.ps1 -EmailAdmin … -PularCarga`) o jar novo começa a relatar
   sozinho. `QUACK_METRICS_TOKEN` na loja é opcional (só para ler as métricas locais na rede da loja).
7. **Conferir:** alvo `UP` no Prometheus (sem rota pública, via `docker exec`, abaixo), painéis carregados e a linha da
   loja com backup e atraso.

### Bloco para colar na stack (Swarm)

```yaml
  pdv-monitor-prometheus:
    image: "ghcr.io/juniorjcmj/quack-monitor-prometheus:spring-api"
    environment:
      METRICS_TOKEN: "${METRICS_TOKEN}"
      TZ: "America/Sao_Paulo"
    volumes:
      - "horus-pdv-prometheus-data:/prometheus"
    networks:
      - OrionNet
    deploy:
      resources:
        limits:
          memory: 512M
    # SEM labels do Traefik: o Prometheus nao tem rota publica.

  pdv-monitor-grafana:
    image: "ghcr.io/juniorjcmj/quack-monitor-grafana:spring-api"
    environment:
      GF_SECURITY_ADMIN_PASSWORD: "${GRAFANA_ADMIN_PASSWORD}"
      GF_SERVER_ROOT_URL: "https://monitor-pdv.quacksistemas.com.br"
      GF_SERVER_DOMAIN: "monitor-pdv.quacksistemas.com.br"
      TZ: "America/Sao_Paulo"
    volumes:
      - "horus-pdv-grafana-data:/var/lib/grafana"
    networks:
      - OrionNet
    deploy:
      resources:
        limits:
          memory: 512M
      labels:
        - "traefik.enable=true"
        - "traefik.http.routers.monitor-pdv.rule=Host(`monitor-pdv.quacksistemas.com.br`)"
        - "traefik.http.routers.monitor-pdv.entrypoints=websecure"
        - "traefik.http.routers.monitor-pdv.tls.certresolver=letsencrypt"
        - "traefik.http.services.monitor-pdv.loadbalancer.server.port=3000"
```

> As labels do Traefik acima estão em `deploy.labels` (provider Swarm do Traefik). Se na sua stack as labels dos outros
> serviços (ex.: `pdv-api-sync`) ficam no nível do serviço, como no `docker-compose.yml`, repita esse padrão. O Prometheus acessa o
> receptor por `pdv-api-sync:8080` (alias de rede do serviço na `OrionNet`); se o seu serviço tiver outro nome, ajuste
> `monitoramento/prometheus/prometheus.yml` e publique a imagem de novo.

### Conferir sem rota pública

```bash
# alvo do receptor (deve mostrar "health":"up")
docker exec $(docker ps -qf name=pdv-monitor-prometheus) wget -qO- 'http://localhost:9090/api/v1/targets?state=active'
# a loja relatou? (uma serie por loja)
docker exec $(docker ps -qf name=pdv-monitor-prometheus) wget -qO- 'http://localhost:9090/api/v1/query?query=quack_loja_backup_sucesso'
```

O Grafana abre em `https://monitor-pdv.quacksistemas.com.br` (usuário `admin`); sem login ele redireciona para
`/login` e não mostra painel algum. Teste automatizado de ponta a ponta (SQL Server + .NET + receptor + Prometheus + Grafana
em Docker): `monitoramento/testes/Testar-MonitoramentoDocker.ps1`.

### Operação

- **Trocar o token:** mude `METRICS_TOKEN` na stack e reimplante `pdv-api-sync` e `pdv-monitor-prometheus`. O token só dá
  leitura de contagens e nomes de lojas.
- **Loja sem série `quack_loja_*`:** o servidor da loja ainda está com o jar antigo (não relata); as séries
  `quack_sync_*` continuam.
- **Reverter:** remova os dois serviços da stack (os dados ficam nos volumes) e/ou volte a tag do `pdv-api-sync`. A tabela
  `SyncEstadoLoja` fica sem uso e não interfere em nada.
- Alertas continuam sendo os e-mails de `EnviadorAlertas`; regras de alerta no Grafana ficam para uma change futura.
