# Design

## Context

- Motivação e escopo: ver `proposal.md`. Requisitos: `specs/estoque/{inventario,estoque-desktop,etiquetas-gondola,relatorios-compra}/spec.md`.
- O `JAVA/caixa/` (Java 21, JavaFX 21.0.8 sem FXML e sem module-info, Maven **offline** `mvn -o`, Jackson 2.17.2,
  ZXing core 3.5.3, JUnit 5.10.2, maven-shade 3.6.0, `jpackage` app-image) já resolveu: cliente HTTP com cookie
  `horuspdv.auth` + Bearer (`ApiClient`, envelope `{success,message,data}`), login web-like (`LoginView`), modais em
  cartão centralizados (`Fx.card`, escurece a janela dona), tokens visuais (`quack.css`, `glass.css`), fontes
  embutidas (o JavaFX embaralha o **negrito** das fontes do Windows nesta máquina — nunca usar fonte do sistema),
  `Toasts`, impressão assíncrona ajustada à largura da térmica (`ReceiptPrinter.printAsync` + `fitScale`/
  `pageRanges`/`showPage`), `build-exe.ps1` (recusa rodar com o .exe aberto; `-Dest`). Endereço padrão da API:
  `https://api-pdv.quacksistemas.com.br` (teste local `http://localhost:5260`).
- API .NET 8 (`API/NETCORE`): rotas de estoque já existentes e seus contratos (ler os arquivos antes de usar):
  - Produtos: `ProdutoController` (`GET /api/Produto`, `POST`, `PUT {id}`, `PATCH {id}/preco-venda`,
    `POST {id}/ajuste-estoque` → `AjusteEstoqueRequest{Tipo: entrada|saida|uso_interno|perda, Quantidade, Motivo,
    DataValidade?, NumeroLote?}`, `GET {id}/movimentacoes`, `GET movimentacoes?tipo=&limit=`), `ProdutoRequest`.
    `ProdutoAB.AjustarEstoqueAsync` faz transação própria e recusa saldo negativo.
  - Entrada: `NfeImportController` (`preview`, `buscar-sefaz`, `confirmar`, `notas-entrada`, `/{id}/xml`,
    `DELETE notas-entrada/{id}?justificativa=`, `/{id}/comprovante`), `EntradaManualController`
    (`POST /api/Produto/entrada-manual`, `EntradaManualRequest` com `OperacaoId` idempotente). Modelos em
    `Models/Requests/NfeImportRequest.cs` e `EntradaManualRequest.cs`. A web (`NfeImportModal.tsx`,
    `ManualStockEntryPanel.tsx`) é a referência de fluxo.
  - Lotes/validade: `LoteController` (`/api/Produto/lotes/*`), relatórios `vencimentos` no `RelatorioAB`.
  - Compras: `OrdemCompraController` (gerente/admin): criar/atualizar/listar/receber/cancelar e
    `sugestoes-reposicao` (hoje só "abaixo do mínimo", sem giro). `CriarOrdemCompraRequest{SupplierId, Items...}`.
  - Cadastros: `CategoriaController`, `FornecedorController`; roles em `Services/Security/HorusRoles.cs`
    (`estoquista` já existe: "consulta de produtos, contagem/ajuste de estoque, inventário e lotes").
  - Movimentações: tabela `EstoqueMovimentacoes` (migração 52, `Tipo` NVARCHAR(20) sem CHECK).
  - Auditoria: `AuditLogAB` (Description ≤ 500), padrão append-only com fotografia em JSON já usado em
    `VendaCancelamentoAB` + migração 55. Migrações novas **precisam** ser registradas em
    `Repositories/HorusDatabaseInitializer.cs` (senão não rodam no boot).
  - Etiquetas na web: `GondolaLabelModal.tsx` (Pimaco 6180 63,5x31 mm, 6182 105x33,8 mm, térmica 70x35 mm).
- Ambiente de teste: Docker `pdv-teste-sql` (14333, SA `Teste#Caixa2026`, `sqlcmd` precisa `-C -I`) e
  `pdv-teste-api` (5260→8080) montado de contexto limpo (`tar --exclude=bin --exclude=obj API/NETCORE Shared`).
  Usuários: `caixa@teste.com`/`Teste@12345` (administrador), `operadora@teste.com`/`Caixa@12345` (caixa).

## Goals / Non-Goals

**Goals:**
- Um aplicativo separado (`JAVA/estoque/`, `QuackEstoque.exe`) que reaproveita o máximo do caixa por cópia.
- Inventário confiável com vendas acontecendo durante a contagem e trilha que não se altera.
- Etiquetas legíveis pelo leitor do caixa em qualquer térmica com driver Windows.
- Relatórios de compra calculados no servidor (mesmos números para web e desktop).

**Non-Goals:**
- Modo offline no estoque (operações de estoque exigem conexão; o app avisa e mantém rascunhos locais).
- Linguagem nativa de impressora (ZPL/PPLA) — fica para depois; a impressão é sempre via driver.
- Coletor de dados móvel/Android, transferência entre lojas, produção/receitas, NF-e de saída.
- Mudar contratos das rotas existentes (só acréscimos).

## Decisions

### D1. App separado por cópia do caixa, não biblioteca compartilhada
`JAVA/estoque/` com pacote `br.com.quack.estoque`, copiando de `JAVA/caixa` as classes de infraestrutura
(`ApiClient`, `ApiException`, `AuthApi`, `AppConfig`/`AppPaths` com pasta `%APPDATA%\QuackEstoque`, `Session`, `Fx`,
`Toasts`, `LoginView`, CSS, fontes, ícone, `Launcher`, `ReceiptPrinter` → parte de impressão vira `PrintService`).
**Por quê:** os dois apps evoluem e são distribuídos separadamente; um módulo Maven comum exigiria reorganizar o
caixa já em produção. **Alternativa:** módulo `quack-desktop-common` (melhor a longo prazo; anotar como refatoração
futura quando o terceiro app surgir). Sem banco local criptografado: o estoque é online; guarda localmente só
config, rascunhos (entrada em conferência, contagens não enviadas) e fila/ajustes de etiquetas em JSON.

### D2. Telas e navegação
Janela única com menu lateral (ícones + texto): Início (alertas: crítico, vencendo, preços alterados, inventário
aberto), Produtos, Entradas, Ajustes/Movimentações, Inventário, Etiquetas, Compras (sugestão → pedido, pedidos),
Relatórios, Cadastros (categorias, fornecedores). Atalhos: F2 busca, F3 nova entrada, F4 ajuste, F6 inventário
(contar), F8 etiquetas, Esc fecha modal. Visual = `docs/design-system.md` (tokens Quack), igual ao caixa.

### D3. Inventário: acerto pela diferença no momento da contagem
Na **primeira** contagem de cada produto o servidor grava o saldo do sistema naquele instante
(`SaldoNa1aContagem`, lido com `UPDLOCK`). Na aplicação: `delta = Σ contagens válidas − SaldoNa1aContagem` e
`novo saldo = saldo atual + delta` (piso 0; se o piso for usado, a diferença fica registrada no item). Assim vendas e
entradas feitas **depois** da contagem continuam valendo (cenário "Venda durante a contagem" do spec). Premissa
documentada para o usuário: o que se move entre a 1ª e a última contagem do mesmo produto é tratado como posterior
à contagem — por isso a tela orienta a contar cada produto de uma vez e mostra o horário da 1ª contagem.
Cada ajuste vira uma linha em `EstoqueMovimentacoes` com `Tipo = "inventario"`, `EstoqueAnterior` = saldo atual,
`EstoqueNovo` = novo saldo, motivo "Inventário #N: <justificativa>". Tudo numa transação com `UPDLOCK` nos produtos;
senha do gerente validada por `HorusSecurityStore.ValidateSupervisorCredentials` (tentativa negada vai para a trilha
em conexão própria, como `VendaCancelamentoAB.RegistrarNegadaAsync`). Lotes: delta positivo cria lote sem validade
(ou prazo padrão da categoria); delta negativo consome FEFO pelo mesmo serviço de lotes usado no ajuste.
**Alternativas rejeitadas:** congelar vendas durante o inventário (inviável em loja aberta) e sobrescrever o saldo
com o contado (apaga as vendas feitas durante a contagem).

### D4. Modelo de dados do inventário (migração 56)
- `Inventarios` (Id `inv-…`, CompanyId, Numero sequencial por empresa, Descricao, Escopo `loja|categorias|produtos`,
  EscopoJson, Status `em_contagem|aplicado|cancelado`, AbertoPor/Em, AplicadoPor/Em + SupervisorId/Nome +
  Justificativa, CanceladoPor/Em + Motivo, ZerarNaoContados bit, Totais (sobra, falta, líquido)).
- `InventarioItens` (InventarioId, ProdutoId, código/nome/unidade/custo no momento da abertura, SaldoNa1aContagem,
  PrimeiraContagemEm, UltimaContagemEm, Recontar bit, Contado DECIMAL, e — após aplicar — SaldoAntes, Delta,
  SaldoDepois, ValorDelta). Linha criada na abertura para cada produto do escopo (lista de "não contados").
- `InventarioContagens` (append-only: Id, InventarioId, ProdutoId, Quantidade, Local, UsuarioId/Nome, Terminal, Ip,
  SaldoSistema, CriadoEm, AnuladaPor/Em/Motivo — anulação é a única atualização, nunca DELETE).
- `InventarioEventos` (append-only: abertura, recontagem pedida, tentativa de aplicação autorizada/negada, aplicado,
  cancelado, com usuário, gerente informado, IP, terminal, descrição). Resumo também no `AuditLog`
  (`InventarioAplicado`, `InventarioAplicacaoNegada`, `InventarioCancelado`).
- Sobreposição de escopo: checagem na abertura dentro de transação serializável por empresa (`sp_getapplock`
  `inventario:{companyId}`).

### D5. Rotas novas (`InventarioController`, `[Route("api/Inventario")]`)
`POST /` abrir · `GET /?status=` listar · `GET /{id}` resumo · `GET /{id}/itens?filtro=divergentes|nao_contados|
recontar` · `POST /{id}/contagens` (`{produtoId | codigo, quantidade, local, terminal}`; `codigo` = código interno ou GTIN) · `POST /{id}/contagens/{contagemId}/anular` · `POST /{id}/recontar`
· `POST /{id}/aplicar` (`{supervisorId, supervisorPassword, justificativa, zerarNaoContados, terminal}`) ·
`POST /{id}/cancelar` · `GET /{id}/trilha`. Roles: abrir/contar/consultar = administrador, gerente, estoquista;
aplicar/cancelar/recontar/trilha = administrador, gerente (aplicar também exige senha do gerente, como na correção
de venda, para o gerente logado confirmar). Respostas no envelope padrão; recusas com `data.codigo`.

### D6. Relatórios de compra no servidor (`EstoqueRelatorioController`, `[Route("api/Estoque/relatorios")]`)
`GET reposicao?dias=30&cobertura=15&categoriaId=` · `GET curva-abc?de=&ate=&categoriaId=&a=80&b=95` ·
`GET critico` · `GET parado?dias=60` · `GET validade?dias=30` · `GET perdas?de=&ate=`. Cálculo em SQL sobre
`VendaItens`⋈`Vendas` (status ≠ cancelada/corrigida), `Produtos`, `EstoqueMovimentacoes`, lotes. Rotas com
valores (curva, perdas, parado com valor, reposição com custo) só gerente/admin; estoquista recebe `reposicao` sem
custo (o controller zera os campos de valor para esse perfil). Os mesmos geradores entram no `RelatorioAB` com ids
`reposicao-giro`, `curva-abc`, `estoque-parado`, `perdas` para aparecerem na web (`reportsConfig.ts`).
**Alternativa:** calcular no cliente com a lista de produtos/vendas — rejeitada (volume e divergência entre web e
desktop). A `sugestoes-reposicao` existente fica como está (compatibilidade).
Pedido a partir da sugestão: o app chama `POST /api/OrdemCompra` uma vez por fornecedor.

### D7. Etiquetas: renderização JavaFX + driver Windows
`LabelRenderer` monta cada etiqueta como nós JavaFX em milímetros convertidos para pontos (1 mm = 72/25,4 pt),
`Text` com suavização GRAY e fontes embutidas (Nunito para nome, Roboto Mono/Nunito ExtraBold para preço), código
de barras desenhado em `Canvas` a partir do `BitMatrix` do ZXing (EAN-13 se GTIN válido, senão Code128 do código
interno) com largura de módulo inteira em pontos de impressora (≥ 2 dots a 203 dpi) e zona de silêncio de 10
módulos. `PrintService` cria `PageLayout` com o `Paper` do tamanho da etiqueta (`Printer.createPageLayout` com o
papel mais próximo da lista do driver; se o driver não tiver o tamanho, usa o papel padrão e escala), margem
`HARDWARE_MINIMUM`, aplica o deslocamento de calibração e imprime uma página por etiqueta num único `PrinterJob`
em thread de impressão (como `ReceiptPrinter.printAsync`). Folha A4 Pimaco: várias etiquetas por página em grade.
Modelos e calibração em `%APPDATA%\QuackEstoque\etiquetas.json`; "último preço impresso" por produto no mesmo
arquivo (por terminal — ver riscos). **Alternativa:** ZPL/PPLA (mais nítido) — fora deste escopo (Non-Goals).

### D8. Leitura de código e balança
O app resolve o código lido contra o catálogo em memória (código interno, GTIN e etiqueta de balança com a classe
`BalancaBarcode` copiada do caixa: prefixo 2, PLU, peso/valor, DV EAN-13) e envia `produtoId` + quantidade. A rota de
contagem também aceita `codigo` (código interno ou GTIN, sem balança) para clientes simples. Catálogo carregado ao
abrir as telas de entrada/contagem/etiquetas e recarregado a cada 5 min ou com F5.

### D9. Perfis no app
`administrador`/`gerente`: tudo. `estoquista`: sem custo/margem (campos ocultos e não enviados), sem relatórios de
valor, sem aplicar/cancelar inventário, sem cadastro de fornecedor/categoria, sem estorno de entrada, sem gerar
pedido. Demais perfis: login recusado com mensagem. O servidor continua sendo a autoridade (roles nas rotas).

### D10. Testes
- API: testes de integração no Docker (scripts `curl`/Java E2E como no caixa) cobrindo inventário (venda durante a
  contagem, senha errada, aplicação dupla, anulação, sobreposição) e relatórios (números conhecidos inseridos via SQL).
- App: JUnit para regras puras (cálculo de etiqueta/código de barras, conversão mm→pt, filtros, formatação BR, CSV),
  `LabelPrintLayoutTest` (snapshot offscreen como `ReceiptPrintLayoutTest`) e E2E gated por `-Dquack.e2e.url`.

## Risks / Trade-offs

- [Drivers de térmica sem o tamanho de etiqueta configurado] → app mostra o papel escolhido pelo driver na
  pré-visualização, permite escolher papel da lista do driver e calibrar deslocamento; README com passo a passo de
  criar o tamanho no driver (Elgin/Argox/Zebra).
- [Inventário longo com muitas vendas] → delta por produto calculado pela janela 1ª–última contagem; recomenda-se
  contar cada produto de uma vez; o relatório mostra o saldo do sistema usado em cada item.
- [Dois inventários concorrentes ou contagem em inventário encerrado] → `sp_getapplock` na abertura, checagem de
  status com `UPDLOCK` em toda contagem/aplicação.
- ["Preços alterados" guardado por terminal] → em lojas com dois terminais de etiqueta a lista pode divergir;
  aceitável nesta versão (servidor poderia guardar `UltimaEtiquetaPreco` depois).
- [Cópia de código entre caixa e estoque] → correções de infraestrutura precisam ser repetidas; registrar no README
  quais classes vieram do caixa.
- [Relatórios pesados (curva ABC de 90 dias)] → índices existentes em `Vendas(CompanyId, SaleDate)`; limitar
  período máximo a 365 dias e paginar no app.

## Migration Plan

1. Publicar a API com a migração 56 (roda no boot; tabelas novas, nada existente muda).
2. Distribuir `QuackEstoque-1.0.0-windows.zip`; criar usuários com perfil `estoquista` onde necessário.
3. Rollback: o app novo pode ser simplesmente não usado; a API antiga ignora as tabelas novas (migrações idempotentes
   e só aditivas). Inventário aplicado não se desfaz automaticamente — correção por ajuste manual registrado.

## Open Questions

- Nome final do executável/ícone (sugestão "Quack Estoque" com o patinho e uma caixa) — não altera specs.
- Tamanhos de etiqueta mais usados nas lojas atuais (os 4 modelos padrão cobrem os comuns; ajustar depois).
