# Módulo Fiscal (NFC-e) + Módulo de Pedidos — resumo das mudanças

> Documento de acompanhamento para revisão/aprovação deste fork. Descreve o que foi
> adicionado e modificado, por quê, e o que precisa ser conferido antes de mergear.

## Resumo executivo

Duas frentes de trabalho, sobre o Hórus PDV existente:

1. **Emissão fiscal de NFC-e** (modelo 65, SEFAZ-RJ/SVRS) — a partir do desenho já
   existente na pasta `doc fiscal pdv/` (contratos, provider, worker de fila, migrações
   SQL), ligado ao código real do projeto.
2. **Módulo de Pedidos** — fluxo em que um vendedor monta o pedido do cliente no sistema
   e o caixa localiza pelo número para cobrar, comum em lojas de material de construção.

Nenhuma dessas duas frentes foi testada contra o compilador .NET real nem contra a SEFAZ —
ver [Antes de aprovar](#antes-de-aprovar-o-que-ainda-falta-verificar) no final deste
documento.

---

## 1. Módulo Fiscal (NFC-e)

### 1.1 Motivação

O PDV registrava vendas apenas como recibo interno, sem nenhum documento fiscal. Os
arquivos em `doc fiscal pdv/` (não versionados no fluxo da aplicação) já continham o
desenho de como emitir NFC-e usando a biblioteca `Hercules.NET.NFe.NFCe` — este trabalho
liga esse desenho ao schema e ao código reais do projeto, que divergiam da premissa dos
arquivos originais em dois pontos: dinheiro/quantidade eram armazenados como texto
(`NVARCHAR`), e não existia nenhuma tabela de fila/controle de documentos fiscais.

### 1.2 Migração de banco: dinheiro e quantidade viram `DECIMAL`

O PDV vende produtos por peso (ex.: material vendido por kg/m³), o que exige quantidade
fracionada — algo que a coluna `VendaItens.Quantity` (`INT`) não suportava.

- **Novo:** `API/NETCORE/DataBase/Migrations/01_migracao_valores.sql` — converte
  `Vendas.TotalAmount`, `VendaItens.Quantity/UnitPrice/ItemTotal`,
  `Produtos.ProductQnt/ProductUnitPrice/ProductSalePrice/TotalPriceOnProduct` e
  `CaixaSessoes.OpeningAmount/ClosingAmount` de `NVARCHAR` para `DECIMAL`. Valida antes de
  converter (aborta com erro se algum valor existente não for parseável).
- **Novo:** `API/NETCORE/Services/Shared/HorusMoneyFormat.cs` — helper único para
  converter entre `decimal` (banco) e string pt-BR (`"1.234,56"`, contrato HTTP com o
  frontend), substituindo quatro implementações duplicadas da mesma lógica que existiam
  espalhadas pelo código.
- **Modificado:** `HorusDatabaseInitializer.cs` — passou a rodar, além do
  `DataBase/Resumo.sql` de sempre, os dois scripts de `DataBase/Migrations/`. A migração de
  valores só roda quando detecta que `Vendas.TotalAmount` ainda é `NVARCHAR` (checagem feita
  em C#, não no SQL) — ou seja, é automática no boot da aplicação, mas só executa uma vez.
  **Por isso é indispensável fazer backup do banco antes do primeiro deploy com essa
  mudança.**
- **Modificados** (para ler/gravar `decimal` nativo em vez de string, mantendo o mesmo
  contrato JSON pt-BR para o frontend): `ProdutoAB.cs`, `ProdutoAD.cs`, `ProdutoService.cs`,
  `HistoricoVendasAB.cs`, `VendaHistoricoAD.cs`, `VendaRequest.cs` (campo `Quantity` virou
  `decimal`), `CaixaAB.cs`, `CaixaSessionAD.cs`, `HorusCaixaService.cs`, `RelatorioAB.cs`,
  `HomeAB.cs`.

### 1.3 Estrutura fiscal (produtos, empresa, clientes, fila de documentos)

- **Novo:** `API/NETCORE/DataBase/Migrations/02_estrutura_fiscal.sql` (idempotente, roda
  em todo boot) — adiciona:
  - Em `Produtos`: `Ncm`, `Cest`, `Cfop`, `OrigemMercadoria`, `UnidadeComercial`,
    `UnidadeTributavel`, `Gtin`, `CsosnIcms`, `CstIcms`, `AliquotaIcms`, `CstPis`,
    `CstCofins`, `CstIbsCbs`, `CClassTrib` (os dois últimos para a reforma tributária).
  - Em `Empresas`: `Crt`, `CnaeFiscal`, `CodigoMunicipioIbge`, `CodigoUfIbge`,
    `AmbienteFiscal`, `CscId`/`CscCifrado`, `CertificadoPfxCifrado` (certificado A1 como
    base64 cifrado — **alterado do desenho original**, que guardava bytes crus em
    `VARBINARY`), `CertificadoSenhaCifrada`, `CertificadoThumbprint`,
    `CertificadoValidoAte`, dados do responsável técnico.
  - Em `Clientes`: `IndIeDest`, `InscricaoEstadual`, `CodigoMunicipioIbge`.
  - Tabelas novas `FiscalSequencias` (numeração de NFC-e por empresa/série/ambiente) e
    `DocumentosFiscais` (a fila de emissão — status, XML assinado/protocolado/cancelamento,
    tentativas, contingência).

### 1.4 Módulo fiscal (backend)

Todos novos, em `API/NETCORE/Services/Fiscal/`:

- `FiscalContracts.cs` — contratos (`IFiscalProvider`, `EmissaoNfceRequest`, `ItemFiscal`,
  `ContextoEmitente` etc.) isolando o resto da aplicação da biblioteca emissora.
- `ZeusFiscalProvider.cs` — implementação usando `Hercules.NET.NFe.NFCe` (fork mantido do
  extinto DFe.NET/Zeus). **Diferença em relação ao rascunho original:** trocado
  `X509CertificateLoader.LoadPkcs12` (API do .NET 9+) pelo construtor
  `new X509Certificate2(bytes, senha, flags)`, compatível com o `net8.0` deste projeto.
  Validação local de schema XSD (`IsValidaSchemas`) fica automaticamente desligada se a
  pasta de schemas estiver vazia, sem quebrar a emissão (a SEFAZ ainda valida do lado dela).
- `NfceOutboxWorker.cs` — `BackgroundService` que processa a fila de `DocumentosFiscais`
  fora da thread de requisição HTTP (a API do Zeus é síncrona). Reforço em relação ao
  desenho original: uma exceção inesperada ao montar um documento não trava mais o lote
  inteiro a cada ciclo — cada documento tem seu próprio `try/catch` com backoff.

Novos, em `API/NETCORE/Repositories/DatabaseAccess/`:

- `DocumentoFiscalAB.cs` — enfileira venda para emissão (aloca `nNF` via
  `FiscalSequencias`), monta a `EmissaoNfceRequest` a partir da venda já registrada, e
  registra o resultado (autorizado/rejeitado/denegado/cancelado/contingência).
- `EmitenteFiscalStore.cs` — monta o `ContextoEmitente` (dados já descriptografados) a
  partir dos dados fiscais da empresa.

Novo: `API/NETCORE/Controllers/Fiscal/NfceController.cs` — endpoints
`GET /api/Nfce`, `GET /api/Nfce/{saleNumber}`, `POST /api/Nfce/{id}/reemitir`,
`POST /api/Nfce/{id}/cancelar`, `POST /api/Nfce/inutilizar`.

**Modificado:** `HistoricoVendasController.cs` — depois de registrar a venda, enfileira a
NFC-e automaticamente (`DocumentoFiscalAB.EnfileirarAsync`). Falha ao enfileirar não
derruba a venda (só marca `fiscalQueued: false` na resposta) — a venda já está paga e
confirmada, e o vendedor não deve ficar bloqueado por um problema no módulo fiscal.

**Modificados** (campos fiscais + upload/validação de certificado):
`EmpresaAD.cs`/`EmpresaRequest.cs`/`EmpresaAB.cs`/`EmpresaController.cs`,
`ClienteAD.cs`/`ClienteRequest.cs`/`ClienteAB.cs`/`ClienteService.cs`. O upload do
certificado `.pfx` (recebido como base64) é validado no momento do salvamento
(`EmpresaAB.SalvarAsync` tenta carregar o certificado com a senha informada e extrai
thumbprint/validade; se falhar, rejeita com mensagem clara).

**Registrado em `Program.cs`:** `IFiscalProvider → ZeusFiscalProvider`,
`DocumentoFiscalAB`, `EmitenteFiscalStore`, `AddHostedService<NfceOutboxWorker>`.

**Dependências novas** (`API/NETCORE/HORUSPDV-API.csproj`):
`Hercules.NET.NFe.NFCe` (`2026.8.31.*`) e `System.Security.Cryptography.Xml` (`8.0.*`).

### 1.5 Schemas XSD

`API/NETCORE/DataBase/Schemas/` — 257 arquivos `.xsd` da NF-e/NFC-e v4.00 (incluindo o
grupo IBS/CBS da reforma tributária), copiados da pasta `NFe.AppTeste/Schemas` do
repositório [Hercules-NET/ZeusFiscal](https://github.com/Hercules-NET/ZeusFiscal) — mesmo
fork do pacote NuGet usado, para garantir que a versão dos schemas bate com a versão da
lib. Ver `API/NETCORE/DataBase/Schemas/README.md` para origem e instruções de atualização
futura.

### 1.6 Frontend fiscal

- **Novo:** `FRONTEND/src/services/api/fiscalService.ts` — cliente HTTP para o
  `NfceController`.
- **Novo:** `FRONTEND/src/components/Admin/DanfePreviewModal.tsx` — DANFE em tela (chave de
  acesso, protocolo, status, QR Code renderizado com `qrcode.react`) em vez de impressão
  térmica ESC/POS (fora de escopo desta etapa — sem impressora física para testar).
- **Reescrito:** `FRONTEND/src/pages/Admin/FiscalPage.tsx` — de placeholder
  "Em desenvolvimento" para lista real de documentos fiscais, com reemitir, cancelar e
  inutilizar faixa de numeração.
- **Modificado:** `FRONTEND/src/pages/Admin/MyCompanyPage.tsx` — nova seção "Dados
  fiscais": CRT, ambiente (produção/homologação), CNAE, código do município, CSC/CSCid,
  upload do certificado `.pfx` + senha, dados do responsável técnico.
- **Modificado:** `FRONTEND/src/pages/Admin/ProductRegisterPage.tsx` — nova seção "Dados
  fiscais" (NCM, CFOP, CEST, origem, unidade comercial/tributável, GTIN, CSOSN/CST ICMS,
  alíquota, CST PIS/COFINS) e quantidade de estoque fracionada para produtos vendidos por
  peso/volume.
- **Modificado:** `FRONTEND/src/pages/Admin/SalesHistoryPage.tsx` — badge de status fiscal
  por venda + ações "Ver DANFE"/"Reemitir".
- **Nova dependência:** `qrcode.react` (`^4.2.0`) — renderização de QR Code 100%
  client-side, sem chamada a serviço externo.

### 1.7 Quantidade fracionada no PDV + leitor de balança

- **Modificado:** `FRONTEND/src/pages/Admin/SalesStartPage.tsx` — campo de quantidade no
  carrinho aceita decimal quando a unidade do produto não é `UN` (ex.: `KG`, `LT`).
- **Novo:** `FRONTEND/src/utils/balancaBarcode.ts` — decodifica código de barras EAN-13 de
  "peso variável" impresso por etiquetas de balança (prefixo `2` + 5 dígitos de código do
  produto + 5 dígitos de peso em gramas + dígito verificador EAN-13 — formato mais comum
  entre balanças Toledo/Filizola/Urano configuradas de fábrica). **Não testado contra uma
  balança física** — se o peso lido não bater com a etiqueta real, ajustar
  `PRODUCT_CODE_LENGTH`/`WEIGHT_LENGTH` nesse arquivo.

---

## 2. Módulo de Pedidos

### 2.1 Motivação

Loja de material de construção: o vendedor atende o cliente no balcão e monta o pedido; o
cliente leva um número até o caixa para pagar. O PDV original só suportava "montar carrinho
e pagar na mesma tela, pelo mesmo operador".

### 2.2 Decisões de escopo (confirmadas com o solicitante)

- O caixa localiza o pedido **pelo número** (não por uma lista).
- O estoque **não é reservado** na criação do pedido — só é checado e baixado quando o
  caixa efetivamente finaliza o pagamento. Dois pedidos podem, em tese, disputar a mesma
  unidade; quem finalizar primeiro leva.
- O preço de cada item é **congelado** no momento em que o vendedor monta o pedido — o
  caixa cobra esse valor, não o preço atual do produto.
- O caixa **só confirma e cobra**, não edita itens/quantidades do pedido.

### 2.3 Backend

- **Novo (schema, em `API/NETCORE/DataBase/Resumo.sql`** — feature geral do PDV, não
  fiscal, por isso não entrou em `DataBase/Migrations/`): tabelas `Pedidos` (número,
  cliente, vendedor, status aberto/finalizado/cancelado, venda vinculada) e `PedidoItens`
  (produto, quantidade, preço unitário e total já congelados).
- **Novo:** `Repositories/DataAccess/PedidoAD.cs`,
  `Repositories/DatabaseAccess/PedidoAB.cs` — cria pedido (resolve preço atual do produto e
  congela), busca por número, lista abertos, marca finalizado/cancelado. Numeração
  sequencial **por empresa** (ao contrário da numeração de venda existente, que é global —
  ver [Limitações conhecidas](#limitações-conhecidas--fora-de-escopo)).
- **Novo:** `Controllers/Pedidos/PedidoController.cs` —
  `POST /api/Pedido` (criar), `GET /api/Pedido` (listar abertos),
  `GET /api/Pedido/{orderNumber}`, `POST /api/Pedido/{orderNumber}/finalizar`,
  `POST /api/Pedido/{orderNumber}/cancelar`. A finalização exige caixa aberto (mesma regra
  de uma venda direta) e enfileira a NFC-e da mesma forma.
- **Refatorado:** `HistoricoVendasAB.cs` — extraído um método privado compartilhado
  (`InserirVendaAsync`) entre o registro de venda normal e o novo
  `RegistrarComPrecosFixosAsync` (usado na finalização de pedido, que não reconsulta o
  preço do produto — usa o valor congelado).
- **Registrado em `Program.cs`:** `PedidoAB`.

### 2.4 Frontend

- **Novo:** `FRONTEND/src/services/api/pedidoService.ts`.
- **Novo:** `FRONTEND/src/pages/Admin/NovoPedidoPage.tsx` — tela do vendedor: busca
  produtos, monta a lista, gera o pedido e mostra o número em destaque para o cliente levar
  ao caixa.
- **Modificado:** `FRONTEND/src/pages/Admin/SalesStartPage.tsx` (Frente de Caixa) — campo
  "Nº do pedido": carrega os itens do pedido com o carrinho **travado** (sem adicionar/
  remover item manualmente) e, ao confirmar o pagamento, finaliza via
  `pedidoService.finalize` em vez do registro de venda direto.
- **Modificados** (nova entrada de menu/rota "Novo Pedido"): `App.tsx`,
  `components/AppSidebar/AppSidebar.tsx`.

---

## 3. Infraestrutura de deploy (GitHub Actions + ghcr.io + Portainer/Traefik)

Adicionado depois, para publicar esta stack em produção atrás do Traefik já existente
(rede `OrionNet`, mesmo padrão de labels informado por você). Dois hostnames separados:
`pdv.wootchat.com.br` (frontend) e `api-pdv.wootchat.com.br` (API) — decisão confirmada,
já que só um bloco de labels tinha sido informado originalmente.

- **Novo:** `API/NETCORE/Dockerfile` + `.dockerignore` — build multi-stage .NET 8,
  container final roda como usuário não-root, escuta em `:8080` (Kestrel sem TLS — quem
  termina HTTPS é o Traefik).
- **Novo:** `FRONTEND/Dockerfile` + `.dockerignore` + `nginx.conf` — build multi-stage
  Vite, servido por nginx em `:8080`. As `VITE_*_API_URL` são resolvidas em build time; o
  Dockerfile recebe o hostname da API como build-arg e aplica em cima do `.env.prod` já
  versionado (só troca o host, mantém os nomes de variável).
- **Corrigido:** `FRONTEND/.env.prod`, `.env.example`, `.env.development` — estavam sem
  `VITE_NFCE_API_URL` e `VITE_PEDIDO_API_URL` (esquecidos quando os módulos fiscal e de
  pedidos foram criados nesta mesma sessão). Sem essa correção, um build de produção feito
  a partir desses arquivos cairia silenciosamente no fallback `localhost:5260` para essas
  duas APIs.
- **Novo:** `docker-compose.yml` (raiz) — stack para Portainer com os três serviços
  (`pdv-sqlserver`, `pdv-api`, `pdv-frontend`), labels do Traefik para os dois hostnames
  (exatamente no padrão que você mandou, replicado para a API com router/service
  próprios), segredos via variável de ambiente (nunca hardcoded).
- **Novo:** `.env.stack.example` — documenta cada variável que o `docker-compose.yml`
  espera (senha do SQL Server, `JWT_SECRET`, `ENCRYPTION_KEY`, e-mail/reCAPTCHA opcionais).
- **Novo:** `.github/workflows/docker-publish.yml` — builda e publica as duas imagens em
  `ghcr.io/<seu-usuario>/horus-pdv-{api,frontend}` a cada push na `main`, com cache do
  GitHub Actions; dispara webhook(s) de redeploy do Portainer no final, se configurados
  (opcional — sem webhook, o redeploy é manual no Portainer).
- **Novo:** `DEPLOY.md` — runbook passo a passo (DNS, permissões do GitHub, variáveis do
  stack, primeira subida, avisos esperados nos logs).
- **Modificado:** `.gitignore` — protege contra versionar um `.env`/`.env.stack` real
  (com senhas) na raiz do projeto.

**Decisões tomadas:**
- SQL Server roda **dentro da stack** (container próprio, volume `horus-pdv-mssql-data`),
  em vez de apontar para um banco externo já existente — confirmado que ainda não havia um.
- Imagens publicadas no **GitHub Container Registry (ghcr.io)** — mais simples de
  autenticar a partir do próprio Actions, sem cadastrar credencial de registry separada.
- `MSSQL_PID=Express` por padrão (grátis, licenciado para produção, até 10 GB por banco) —
  trocar para uma edição paga se o volume de dados crescer além disso.

**Não testado** (sem Docker disponível nesta máquina para validar): build real das duas
imagens, `docker compose up` contra um Traefik de verdade, e o healthcheck do SQL Server.
Validei a sintaxe YAML de `docker-compose.yml` e do workflow com um parser (`python -c
"import yaml..."`), e revisei cada Dockerfile/config linha por linha, mas isso não
substitui rodar o build de verdade.

## 4. Verificação feita

- **Frontend:** `npx tsc -b`, `npx eslint .` e `npm run build` rodados após cada etapa —
  todos concluídos sem erro.
- **Backend:** revisão manual, arquivo por arquivo, de nomes de coluna, tipos e
  assinaturas de método entre todos os arquivos C# tocados. **Não foi compilado** — ver
  próxima seção.

## Antes de aprovar: o que ainda falta verificar

Esta sessão de trabalho não teve acesso a um SDK .NET nem a um ambiente de homologação da
SEFAZ. Antes de mergear/publicar:

1. **`dotnet restore && dotnet build`** em `API/NETCORE/HORUSPDV-API.csproj` — nunca foi
   executado de verdade contra esse código. É o primeiro passo.
2. **Backup do banco de produção** antes do primeiro deploy — a migração de valores
   (`01_migracao_valores.sql`) roda automaticamente no boot assim que detecta a coluna
   ainda em texto.
3. Confirmar que o pacote `Hercules.NET.NFe.NFCe` resolve normalmente (existe no NuGet.org,
   verificado manualmente, mas o `dotnet restore` é o teste real).
4. Testar o roteiro de homologação (status do serviço, emissão com um item, emissão com
   CPF, quantidade fracionada, cancelamento, inutilização, contingência) contra a
   SEFAZ-RJ/SVRS de verdade, com um certificado A1 de homologação.
5. Testar o leitor de código de barras de balança (`balancaBarcode.ts`) contra uma
   etiqueta real — o formato assumido é o mais comum, mas não foi validado contra hardware.
6. Rodar `docker build` das duas imagens (`API/NETCORE` e `FRONTEND`) e um
   `docker compose up` local antes de apontar o Portainer pra produção — ver `DEPLOY.md`.
   Só validei a sintaxe dos arquivos, não um build real.

## Limitações conhecidas / fora de escopo

- **Impressão térmica ESC/POS** — ficou só o DANFE em tela (HTML + QR Code); impressão
  direta na térmica é uma etapa futura.
- **`HistoricoVendasAB.NextSaleNumberAsync`** — a numeração de venda (`SaleNumber`) é
  global entre empresas, não por empresa (bug pré-existente ao início deste trabalho, não
  corrigido — sem relação com a numeração fiscal, que já nasce isolada por empresa/série
  em `FiscalSequencias`, nem com a numeração de pedido, que já é por empresa).
- **`RelatorioAB.cs`** — segue agregando em memória via LINQ em vez de `SUM()` no SQL;
  ficou mais simples de corrigir agora que os valores são `DECIMAL` nativo, mas não foi
  feito nesta etapa.
- **Balança ligada direto no caixa (serial/USB)** — não implementado; só o formato de
  etiqueta impressa (mais comum em mercado pequeno).
