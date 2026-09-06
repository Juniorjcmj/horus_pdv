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
   - https://pdv.wootchat.com.br       → container pdv-frontend (nginx, build estático)
   - https://api.pdv.wootchat.com.br   → container pdv-api (.NET, porta 8080)
                                          → container pdv-sqlserver (SQL Server, sem acesso externo)
```

Dois hostnames separados (frontend e API), conforme confirmado — não é um proxy único.

## 1. DNS

Aponte os dois hostnames para o IP do seu servidor Docker (mesmo IP que já resolve o
resto do que está atrás do Traefik):

- `pdv.wootchat.com.br` → A/AAAA para o servidor
- `api.pdv.wootchat.com.br` → A/AAAA para o servidor

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
| Variable | `VITE_API_ORIGIN`              | Só se a API não for `https://api.pdv.wootchat.com.br` (esse já é o default no workflow). |
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
3. Acesse `https://pdv.wootchat.com.br` — login inicial padrão do seed (`Resumo.sql`) usa
   CPF `06.332.765/0001-05`; troque a senha e os dados da empresa assim que entrar.
4. Configure os dados fiscais em **Minha Empresa** (CRT, ambiente, CSC, certificado) antes
   da primeira venda — ver `MODULO-FISCAL-E-PEDIDOS.md`.

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
- O volume `horus-pdv-mssql-data` é o único dado que precisa de backup regular — é onde
  fica o banco inteiro (vendas, produtos, documentos fiscais).
- Este runbook cobre o deploy em si; ele **não substitui** rodar `dotnet build` localmente
  pelo menos uma vez antes do primeiro push — ver `MODULO-FISCAL-E-PEDIDOS.md` para o que
  ainda falta verificar no código.
