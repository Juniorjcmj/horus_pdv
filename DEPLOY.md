# Deploy do Hórus PDV — GitHub Actions + ghcr.io + Portainer/Traefik

Runbook de configuração inicial. Depois de feito uma vez, o fluxo do dia a dia é só
`git push` na `main` — o resto é automático.

## Visão geral

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
