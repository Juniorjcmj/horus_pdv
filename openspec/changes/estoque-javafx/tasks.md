# Tasks

Referência obrigatória: `design.md` (D1–D10) e os specs em `specs/estoque/*`. Maven sempre offline (`mvn -o`).
API: `cd API/NETCORE && dotnet build -c Release`. Ambiente de teste Docker conforme `design.md` (Context): para
testar mudanças da API, reconstruir a imagem `horus-pdv-api:teste` de contexto limpo e recriar `pdv-teste-api`.

## 1. API — inventário (dados)

- [x] 1.1 Criar `API/NETCORE/DataBase/Migrations/56_inventario.sql` (idempotente) com `Inventarios`, `InventarioItens`, `InventarioContagens`, `InventarioEventos` e índices conforme D4; registrar em `Repositories/HorusDatabaseInitializer.cs`. Verificar: subir a API no Docker e `sqlcmd -C -I` mostra as 4 tabelas.
- [x] 1.2 Acrescentar em `AuditEventTypes` (`AuditLogAB.cs`) os eventos `InventarioAberto`, `InventarioAplicado`, `InventarioAplicacaoNegada`, `InventarioCancelado` e no filtro de `FRONTEND/src/components/Admin/ReportsPage/reportsConfig.ts`. Verificar: `dotnet build` e `npx tsc --noEmit -p FRONTEND` sem erros novos.

## 2. API — inventário (regras e rotas)

- [x] 2.1 `Repositories/DatabaseAccess/InventarioAB.cs`: abrir (escopo loja/categorias com subcategorias/produtos, `sp_getapplock` por empresa, recusa sobreposição com 409 e número do aberto, cria `InventarioItens` com custo/código/nome do momento) e listar/obter. Verificar: E2E 2.6 cenário "Escopo sobreposto".
- [x] 2.2 `InventarioAB`: registrar contagem (produto no escopo, inventário em contagem com `UPDLOCK`, grava `SaldoNa1aContagem`/horários na 1ª contagem, soma por produto, terminal/IP/usuário), anular contagem (marca, nunca apaga) e pedir recontagem. Verificar: E2E 2.6 cenários "Somar locais", "Produto fora do escopo", "Anular contagem errada".
- [x] 2.3 `InventarioAB`: divergências (sistema na contagem, contado, diferença, valor pelo custo, não contados, recontar, totais sobra/falta/líquido). Verificar: E2E 2.6 cenário "Produto com falta" (-4 un, -R$ 16,00).
- [x] 2.4 `InventarioAB`: aplicar (D3) numa transação — `delta = contado − SaldoNa1aContagem`, novo = atual + delta com piso 0, `EstoqueMovimentacoes` tipo `inventario`, lotes (delta + cria lote, delta − consome FEFO pelo serviço de lotes existente), opção zerar não contados, totais, evento + AuditLog; tentativas negadas em conexão própria. Cancelar com motivo. Verificar: E2E 2.6 cenários "Venda durante a contagem" (saldo 25), "Senha errada" (403, estoque intacto, evento negado), "Aplicação dupla" (409).
- [x] 2.5 `Controllers/Estoque/InventarioController.cs` com as rotas e roles de D5, IP via `HorusClientIpResolver`, recusas com `data.codigo`; registrar `InventarioAB` no `Program.cs`. Verificar: `dotnet build` sem erros e `curl` com perfil caixa → 403.
- [x] 2.6 Teste E2E da API do inventário (Java, gated por `-Dquack.e2e.url`, dentro de `ESTOQUE_JAVAFX` depois do grupo 4, ou script `curl` em `ESTOQUE_JAVAFX/e2e/`) cobrindo todos os cenários de `specs/estoque/inventario/spec.md` que envolvem a API, incluindo venda feita durante a contagem. Verificar: execução contra o Docker passa e imprime a trilha.

## 3. API — relatórios de compra

- [x] 3.1 `Repositories/DatabaseAccess/EstoqueRelatorioAB.cs`: `reposicao` (venda média diária no período, cobertura em dias, sugerido = max(0, min(max, media×cobertura) − saldo) ou completar mínimo; motivo; agrupado por fornecedor; "Sem fornecedor"), `curva-abc` (A≤80%, B≤95%, C; filtros período/categoria; período máx. 365 dias), `critico`, `parado` (última venda/entrada, nunca vendido, valor pelo custo), `validade` (lotes a vencer/vencidos), `perdas` (movimentações `perda` por produto/motivo/usuário, valor). Vendas consideradas: status diferente de cancelada/corrigida. Verificar: E2E 3.3.
- [x] 3.2 `Controllers/Estoque/EstoqueRelatorioController.cs` (`api/Estoque/relatorios/*`, D6) — estoquista só `reposicao` sem campos de valor e `critico`/`validade` sem valor; demais 403 — e os mesmos geradores no `RelatorioAB` com ids `reposicao-giro`, `curva-abc`, `estoque-parado`, `perdas` + entradas em `reportsConfig.ts`. Verificar: `dotnet build`, `tsc`, e na web os relatórios aparecem na lista.
- [x] 3.3 E2E dos relatórios contra o Docker com dados inseridos via SQL (venda de 60 un em 30 dias com saldo 6/min 10/max 40 → sugestão 24; produto parado 75 dias R$ 100,00; perdas do mês; curva com 3 produtos de faturamento conhecido). Verificar: testes passam.

## 4. App — esqueleto e infraestrutura (cópia do caixa, D1)

- [x] 4.1 Criar `ESTOQUE_JAVAFX/` (pom igual ao do caixa sem sqlite/jna; artifact `quack-estoque`, Main-Class `br.com.quack.estoque.Launcher`), copiar e renomear pacote das classes de infraestrutura listadas em D1 (ApiClient, ApiException, AuthApi, AppConfig/AppPaths → `%APPDATA%\QuackEstoque`, Session, Fx, Toasts, LoginView, CSS, fontes, ícone, extensão de testes se usada) e `PrintService` (impressão assíncrona com `fitScale`/`pageRanges`/`showPage` de `ReceiptPrinter`). Verificar: `mvn -o -q -f ESTOQUE_JAVAFX/pom.xml package` gera `target/quack-estoque.jar` e os testes copiados passam.
- [x] 4.2 `App` com login → janela principal com menu lateral e Início (D2); acesso por perfil (D9) — perfil caixa/atendente recusado com mensagem; 401 volta ao login. Verificar: `PerfilAcessoTest` (regras de D9 puras) e execução manual/E2E de login com `caixa@teste.com` (entra) e `operadora@teste.com` (recusado).
- [x] 4.3 `build-exe.ps1` (cópia do caixa: nome QuackEstoque, ícone, guarda de .exe aberto, `-Dest`), `run.bat`, `.gitignore`, `README.md` (build, execução, perfis, pasta de dados, atalhos, classes copiadas do caixa). Verificar: `powershell -File ESTOQUE_JAVAFX/build-exe.ps1 -Dest <scratch>` gera `QuackEstoque.exe`.

## 5. App — produtos e cadastros

- [x] 5.1 `api/ProdutoApi`, `CategoriaApi`, `FornecedorApi` + catálogo em memória com busca por nome/código/GTIN e `BalancaBarcode` copiado (D8); recarga F5/5 min. Verificar: `CatalogoBuscaTest` (código interno, GTIN, balança, nome parcial sem acento).
- [x] 5.2 Tela Produtos: lista com busca e indicador de estoque (sem estoque/baixo/normal/acima do máx.), ficha com criação/edição (campos do spec), alteração rápida de preço que oferece etiqueta, custo/margem ocultos para estoquista. Verificar: E2E cria produto, altera preço e confere na API; GTIN duplicado mostra a mensagem da API.
- [x] 5.3 Telas de Categorias (2 níveis) e Fornecedores (gerente/admin). Verificar: E2E cria categoria filha e fornecedor.

## 6. App — entradas de mercadoria

- [x] 6.1 `api/EntradaApi` (preview XML, buscar-sefaz, confirmar, entrada manual com `OperacaoId`, listar/obter notas, xml, estornar). Verificar: compila e E2E de entrada manual.
- [x] 6.2 Tela "Nova entrada por NF-e": abrir XML ou digitar chave, conferência item a item (vínculo obrigatório, cadastrar produto novo a partir do item, fator de conversão, custo unitário calculado, lote/validade obrigatórios quando o produto controla validade, atualizar preço com preço atual × sugerido), fornecedor novo a partir do emitente; nota repetida mostra a mensagem da API; rascunho local da conferência. Verificar: `ConversaoUnidadeTest` (5 CX × 12 = 60, custo/12) e E2E importando um XML de exemplo (`src/test/resources`) contra o Docker.
- [x] 6.3 Tela "Entrada manual" (documento, fornecedor opcional, itens, anexo). Verificar: E2E — estoque dos itens sobe e entrada aparece como "cupom".
- [x] 6.4 Lista de entradas com busca, itens, baixar XML e estorno (gerente). Verificar: E2E estorna a entrada manual e o estoque volta.
- [x] 6.5 Ao confirmar entrada: oferecer etiquetas (1 por produto ou quantidade recebida) — integra com o grupo 8. Verificar: depois do grupo 8, E2E/manual mostra os itens na fila.

## 7. App — ajustes, movimentações e inventário

- [x] 7.1 Tela de ajuste avulso (entrada/saída/uso interno/perda, motivo obrigatório, lote/validade na entrada, saldo antes/depois, recusa de saldo negativo mostrada). Verificar: E2E registra perda de 2 e confere a movimentação.
- [x] 7.2 Histórico de movimentações por produto e geral com filtro tipo/período e exportação CSV (`;`, formato BR). Verificar: `CsvExportTest` e E2E do filtro "perda".
- [x] 7.3 `api/InventarioApi` + telas: lista de inventários, abrir (escopo loja/categorias/produtos), modo contagem (leitura contínua: bipa → quantidade padrão 1/fração só fora de UN → local → Enter; total do produto; últimos lançamentos do usuário; aviso sonoro/visual para código desconhecido ou fora do escopo; rascunho local se a API cair), anular lançamento próprio. Verificar: E2E bipando código, GTIN e etiqueta de balança.
- [x] 7.4 Divergências (filtros divergentes/não contados/recontar, totais), pedir recontagem, aplicar com gerente+senha+justificativa+zerar não contados, cancelar, ver trilha (gerente/admin); estoquista não vê aplicar. Verificar: E2E de ponta a ponta do inventário pelo app (abrir, contar, venda via API durante a contagem, aplicar, conferir saldos e trilha).

## 8. App — etiquetas

- [x] 8.1 `labels/LabelModel` (tamanhos padrão 60x40, 50x30, 40x25, 70x35 rolo e Pimaco 6180 + personalizado) e `labels/LabelRenderer` (D7: mm→pt, preço em destaque, nome em até 2 linhas com reticências, unidade, código interno, promoção com preço riscado e período, EAN-13 válido senão Code128 com zona de silêncio e número legível). Verificar: `LabelRendererTest` (GTIN válido → EAN-13; inválido/ausente → Code128 + aviso; nome longo não invade o preço; snapshot offscreen salvo opcionalmente em PNG) e o código de barras gerado é decodificado de volta pelo ZXing (`MultiFormatReader`, adicionar `com.google.zxing:javase` só em teste se necessário e disponível offline; senão decodificar com o `core`).
- [x] 8.2 Fila de etiquetas persistida em `%APPDATA%\QuackEstoque\etiquetas.json` (busca, cópias, remover, "preços alterados" pelo último preço impresso, entrada da nota) e pré-visualização em tamanho real. Verificar: `FilaEtiquetasTest` (persistência, preços alterados).
- [x] 8.3 `PrintService` de etiquetas: escolha da impressora do Windows, papel do driver mais próximo do modelo, margem mínima, deslocamento de calibração salvo, uma etiqueta por página no rolo, grade na Pimaco, impressão em segundo plano com aviso de fim/erro, registro do preço impresso. Verificar: `LabelPrintLayoutTest` (geometria por página dentro do papel com e sem deslocamento) e impressão manual em PDF/impressora disponível documentada no README.

## 9. App — compras e relatórios

- [x] 9.1 `api/EstoqueRelatorioApi` + `api/OrdemCompraApi`; tela "Sugestão de reposição" (parâmetros período/cobertura/categoria, agrupado por fornecedor, editar quantidade/custo, excluir, escolher fornecedor para "Sem fornecedor") → gerar um pedido por fornecedor e imprimir pedido; estoquista só vê quantidades. Verificar: E2E gera 2 pedidos de fornecedores diferentes e confere em `GET /api/OrdemCompra`.
- [x] 9.2 Telas de relatórios: curva ABC, crítico, parado, validade, perdas — filtros, totais, CSV e impressão (A4 via driver, usando `PrintService`). Verificar: E2E confere números do grupo 3.3 nas telas (via camada de serviço) e `CsvExportTest` cobre os relatórios.

## 10. Integração e entrega

- [x] 10.1 Rodar toda a suíte `mvn -o -f ESTOQUE_JAVAFX/pom.xml test` + E2E contra o Docker (`-Dquack.e2e.url=http://localhost:5260`) e o build da API; corrigir falhas. Verificar: tudo verde, saída anexada ao resumo.
- [x] 10.2 Gerar `QuackEstoque.exe` e o zip com `build-exe.ps1`; abrir offscreen (snapshot das telas principais como o `UiSnapshotTool` do caixa) para conferir layout. Verificar: zip gerado e PNGs das telas revisados.
- [x] 10.3 Atualizar `graphify update .` e a memória do projeto (`caixa-javafx.md` ou nova `estoque-javafx.md`). Verificar: comando executado (se recusar por contagem de nós, registrar).

## Workflow follow-up

- Publicar a API (migração 56) antes de distribuir o app.
- Arquivar a change (`/opsx:archive estoque-javafx`) depois da revisão.
