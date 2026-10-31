# Design

## Context

Mapa do caixa web atual (ver proposal.md — Why):

| Peça web | O que faz | Equivalente JavaFX |
|---|---|---|
| `pages/Admin/SalesStartPage.tsx` | tela, carrinho, pagamento, atalhos, fluxo online/offline | `ui/PdvView` + `service/SaleService` |
| `services/api/apiClient.ts` | `fetch` com cookie, timeout 5 s, envelope `{success,message,data}` | `api/ApiClient` |
| `services/api/cashRegisterService.ts` | `/api/Caixa/status|abrir|fechar|movimento` | `api/CaixaApi` |
| `services/api/salesHistoryService.ts` (`register`) | `POST /api/HistoricoVendas` timeout 30 s | `api/VendaApi` |
| `utils/saleMoney.ts` | centavos exatos, `validateSale`, `itemNet`, `validateCredit` | `domain/Money`, `domain/SaleValidator` |
| `utils/promotionEngine.ts` | promoções (6 tipos, melhor desconto) | `domain/PromotionEngine` |
| `utils/balancaBarcode.ts` | EAN-13 de balança | `domain/BalancaBarcode` |
| `utils/cryptoHash.ts` | `payloadHash` SHA-256 canônico | `domain/PayloadHash` |
| `application/sales/SaleOutboxAdapter.ts` + `SyncEngine` | IndexedDB + outbox + reenvio | `persistence/OutboxStore` + `service/SyncWorker` |
| `application/products/ProductSyncAdapter.ts` | `ProductDto` → registro local | `persistence/CacheStore` + `domain/Product.fromDto` |

Fatos da API que moldam o desenho (verificados no código):

- **Login**: `POST /api/Auth/login` body `{email,password,rememberMe,recaptchaToken:""}`. O JWT volta **só** no cookie `horuspdv.auth` (HttpOnly). A resposta traz `data.user` (`id`, `name`, `email`, `role`, `companyId`). O middleware aceita o cookie ou `Authorization: Bearer`. Recaptcha é ignorado quando `Recaptcha:Enabled=false`.
- **Envelope**: toda resposta JSON é `{success:boolean,message:string,details?:string,data?:T}`. Erro = HTTP não-2xx **ou** `success=false`.
- **Venda** (`HistoricoVendasController.Registrar`): idempotente por `eventId` + `payloadHash` (o hash enviado pelo cliente é usado como está). 201 = nova, 200 = replay, 409 = mesmo `eventId` com hash diferente, 400 = regra de negócio, 503 com `data.saleNumber` = venda gravada mas fila fiscal falhou (tratar como sucesso). `data` traz `saleNumber`, `vendaId`, `emitirFiscal`, `fiadoSaldoAtual`.
- Valores em texto pt-BR (`"1.234,56"`) são aceitos por `HorusMoneyFormat.ParseDecimal`; números JSON (`amount`, `unitPrice`, `quantity`) são `decimal`.
- **Produto** (`GET /api/Produto`): campos usados `id, productCode, productName, gtin, productSalePrice ("12,90"), productQnt ("10" ou "0,452"), unidadeComercial, categoriaId, marca, controlaValidade, dataValidade, diasAlertaValidade`.
- **Cliente** (`GET /api/Cliente`): `id, customerName, document, limiteCredito (number), saldoDevedor (number)`.
- **Promoções** (`GET /api/Promocao/ativas`): `id, nome, tipo, valorDesconto, precoFixo, quantidadeLeva, quantidadePaga, quantidadeMinima, inicioVigencia, fimVigencia (ISO), ativa, categoriaId, produtoIds[]`.
- **Empresa** (`GET /api/Empresa`): `fantasyName, corporateName, cnpj, address, number, neighborhood, city, uf, phone`.
- **Caixa status**: `{state, canSell, blockReason, serverNow, currentSession{id,openedAt,openingAmount,operatorId,operatorName,elapsedMinutes,expectedCashAmount,differenceAmount,movimentos[],paymentBreakdown[]}, lastSession, history[]}`. Abrir `{openingAmount:"100,00",eventId,payloadHash}`; fechar `{closingAmount,note,differenceReason,eventId,payloadHash}`; movimento `{tipo:"Sangria"|"Reforco",valor,motivo,eventId,payloadHash}`. Todas devolvem o status atualizado.

## Goals / Non-Goals

**Goals:**
- Executável desktop independente do navegador, compilando com `mvn -f CAIXA_JAVAFX/pom.xml package` usando só o cache Maven local já presente (JavaFX 21.0.8, Jackson 2.17.2, JUnit 5.10.2).
- Paridade exata de centavos com o caixa web (mesmo total, descontos e parcelas para o mesmo carrinho).
- Toda regra pura (dinheiro, promoções, balança, validação, hash) isolada da UI e coberta por testes JUnit.
- Nenhuma venda perdida: gravação em disco antes de qualquer tentativa que possa falhar no meio.

**Non-Goals:**
- Instalador MSI/jpackage (só fat-jar + script `.bat`); assinatura de código.
- Abrir/fechar caixa offline (a web faz com `clientSessionId`; aqui é só online nesta versão).
- Pedidos, NF-e 55, override de preço, cancelamento de venda/NFC-e, Local Gateway, crédito compartilhado, DANFE.
- Tema escuro (só o tema claro Quack).

## Decisions

### D1. Estrutura do módulo
`CAIXA_JAVAFX/` na raiz, Maven, `groupId=br.com.quack`, `artifactId=quack-caixa`, Java 21, pacote raiz `br.com.quack.caixa`:

```
CAIXA_JAVAFX/
  pom.xml
  README.md
  run.bat                       # java -jar target/quack-caixa.jar
  src/main/java/br/com/quack/caixa/
    Launcher.java               # main() que chama App.main (necessário para fat-jar sem module-path)
    App.java                    # javafx.application.Application; troca LoginView <-> PdvView
    config/AppConfig.java       # lê/grava %APPDATA%/QuackCaixa/config.properties (apiBaseUrl, deviceId)
    config/AppPaths.java        # pasta de dados; respeita -Dquack.home para testes
    api/ApiClient.java          # java.net.http.HttpClient + CookieManager, envelope, ApiException
    api/ApiException.java       # status (0 = rede/timeout), message
    api/AuthApi.java  api/CaixaApi.java  api/CatalogApi.java  api/VendaApi.java
    api/dto/*.java              # records Jackson (@JsonIgnoreProperties(ignoreUnknown = true))
    domain/Money.java           # BigDecimal escala 2 HALF_UP, parse/format pt-BR
    domain/Product.java  domain/Customer.java  domain/Promotion.java  domain/Company.java
    domain/CartItem.java  domain/Cart.java          # carrinho mutável + recálculo de promoções
    domain/PaymentType.java                          # DINHEIRO("dinheiro","Dinheiro") ... FIADO
    domain/PaymentPart.java                          # record type, amount, cashGiven, change
    domain/PaymentPlan.java                          # parcelas, saldo, troco, validações
    domain/PromotionEngine.java  domain/BalancaBarcode.java
    domain/SaleValidator.java  domain/PayloadHash.java  domain/SalePayloadFactory.java
    domain/DomainException.java                      # mensagem para o operador
    persistence/JsonFiles.java   # escrita atômica (tmp + Files.move ATOMIC_MOVE, REPLACE_EXISTING)
    persistence/CacheStore.java  # produtos, clientes, promoções, empresa, status do caixa
    persistence/OutboxStore.java # fila de vendas pendentes + falhas
    service/Session.java         # usuário logado, empresa, status do caixa (observável)
    service/CatalogService.java  # carrega da API, cai para cache, baixa estoque local
    service/CaixaService.java    # status/abrir/fechar/movimento
    service/SaleService.java     # confirma venda: online -> fila
    service/SyncWorker.java      # ScheduledExecutorService, reenvio a cada 15 s
    service/ReceiptPrinter.java  # monta Node do cupom e imprime com javafx.print.PrinterJob
    ui/LoginView.java  ui/PdvView.java  ui/PaymentDialog.java  ui/CashPanelDialog.java
    ui/CashMovementDialog.java  ui/CustomerPickerDialog.java  ui/Toasts.java  ui/Fx.java
  src/main/resources/br/com/quack/caixa/ui/quack.css
  src/test/java/br/com/quack/caixa/domain/*Test.java  (+ persistence/OutboxStoreTest)
```

UI montada em código Java (sem FXML) para reduzir dependências e manter o build simples. Alternativa FXML rejeitada: mais arquivos e reflexão para pouco ganho numa tela só.

### D2. Build e execução
- Dependências: `org.openjfx:javafx-controls:21.0.8` (traz base/graphics; classificador `win` resolvido pelo próprio pom do OpenJFX), `com.fasterxml.jackson.core:jackson-databind:2.17.2`, `com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2`, `org.junit.jupiter:junit-jupiter:5.10.2` (test).
- Plugins: `maven-compiler-plugin 3.13.0` (`<release>21</release>`), `maven-surefire-plugin 3.2.5`, `maven-shade-plugin 3.5.3` com `ManifestResourceTransformer` `Main-Class=br.com.quack.caixa.Launcher`, `finalName=quack-caixa`.
- Sem `module-info.java` (classpath). O `Launcher` evita o erro "JavaFX runtime components are missing".
- Rodar: `java -jar CAIXA_JAVAFX/target/quack-caixa.jar` (ou `run.bat`). Testes: `mvn -o -f CAIXA_JAVAFX/pom.xml test`.

### D3. HTTP e sessão
- Um único `HttpClient` (`connectTimeout` 5 s, `HttpClient.Redirect.NORMAL`) com `CookieManager(null, CookiePolicy.ACCEPT_ALL)`; o cookie `horuspdv.auth` segue automaticamente nas chamadas seguintes ao mesmo host.
- Timeout por requisição: 5 s leitura padrão; 20 s para `/api/Caixa/abrir|fechar|movimento`; 30 s para `POST /api/HistoricoVendas`.
- `ApiClient.send(method, path, body, Class/TypeReference, timeout)` → devolve `data`. Falha de rede/timeout → `ApiException(status=0)`. Não-2xx ou `success=false` → `ApiException(status, message da API)`, exceto 503 de venda com `data.saleNumber` (tratado em `VendaApi`).
- 401 depois do login → `Session` dispara evento `sessionExpired`; `App` volta para o login (a fila em disco permanece).
- Chamadas sempre fora da FX thread (`CompletableFuture.supplyAsync` num executor próprio de 4 threads daemon); atualizações de tela com `Platform.runLater`.
- Online/offline: `ApiClient` mantém `volatile boolean apiReachable`; vira `false` em `status=0` e `true` em qualquer resposta HTTP < 500. Um ping leve (`GET /api/Caixa/status`) roda a cada 15 s no `SyncWorker` para reavaliar.

### D4. Dinheiro e quantidades
- `Money` usa `BigDecimal` com escala 2 e `RoundingMode.HALF_UP`, espelhando `moneyCents` (arredonda meio para cima longe de zero; para positivos é igual). Quantidades: escala até 4 (`HALF_UP`).
- `Money.parseBr("1.234,56")`: remove `R$`, espaços e pontos de milhar, troca vírgula por ponto. Se não houver vírgula, aceita ponto decimal (`"12.9"`). Inválido → `DomainException`.
- `Money.formatBr(BigDecimal)` → `"1.234,56"` (Locale `pt-BR`, sempre 2 casas). `Money.formatQty` → até 4 casas sem zeros à direita, vírgula decimal.
- `productMoney(q, p) = round2(q × p)` exato (BigDecimal multiplica sem perda e arredonda uma vez — igual ao `productMoney` web).
- `itemNet(q,p,d)`: q>0, p≥0, d≥0, d ≤ bruto, senão erro; `bruto − d`.

### D5. Promoções (porta literal de `promotionEngine.ts`)
`PromotionEngine.apply(List<CartItem>, List<Promotion>, Instant now)`: filtra `ativa && inicio ≤ now ≤ fim`; para cada item, entre as promoções que se aplicam (`produtoIds` contém `item.productId` ou `item.code`, **ou** `categoriaId` igual à categoria do item), calcula o desconto e fica com o **maior estritamente** (empate mantém a primeira). `desconto = min(bruto, melhor)`, `total = round2(max(0, bruto − desconto))`, `promocaoId/nome` só se desconto > 0.

Fórmulas (q = quantidade, p = preço unitário, bruto = productMoney(q,p)); retorna 0 se q ≤ 0 ou p ≤ 0:
- `desconto_percentual`: pct = valorDesconto; ≤0 → 0; `productMoney(bruto, min(pct,100)/100)`.
- `desconto_valor`: v ≤ 0 → 0; `min(bruto, productMoney(q, v))`.
- `preco_fixo`: pf ≤ 0 ou pf ≥ p → 0; `round2(q × (p − pf))`.
- `leve_x_pague_y`: leva, paga > 0, leva > paga, q ≥ leva; grupos = floor(q/leva); `round2(grupos × (leva − paga) × p)`.
- `combo_quantidade`: min > 1 e q ≥ min; grupos = floor(q/min); se precoFixo > 0 → `round2(max(0, grupos×min×p − grupos×precoFixo))`; senão se valorDesconto > 0 → `round2(grupos × valorDesconto)`; senão 0.
- `preco_atacado`: min > 1 e q ≥ min; se precoFixo > 0 → (precoFixo < p ? `round2(q × (p − precoFixo))` : 0); senão se valorDesconto > 0 → `round2(min(bruto, q × valorDesconto))`; senão 0.
- Tipo desconhecido → 0.

### D6. Etiqueta de balança (porta literal de `balancaBarcode.ts`)
Válido só se 13 dígitos, começa com `2` e DV EAN-13 correto (pesos 1/3 alternados sobre os 12 primeiros). Com a lista de produtos, casa o PLU (comparação por texto igual **ou** valor numérico igual) nesta ordem:
1. 4 dígitos `[1,5)`: preço total = `[5,12)` centavos; peso = `round3(total / preço)` (preço 0 → 1).
2. 5 dígitos `[1,6)`: centavos = `[6,12)`, gramas = `[6,11)`; se preço > 0 e centavos > 0 e `0 < round3(centavos/100/preço) ≤ 50` → modo preço; senão se gramas > 0 → peso = gramas/1000.
3. 6 dígitos `[1,7)`: total = `[7,12)` centavos; peso = `round3(total/preço)` (preço 0 → 1).
Sem produto casado: se `[5,8) == "000"` → código 4 dígitos, peso 1; senão código `[1,6)`, peso `[6,11)/1000` (0 → 1). Depois o caixa busca o produto pelo código retornado (texto igual ou número igual); não achou → "Produto com código de balança X não encontrado".

### D7. Carrinho e regras do item
- `Cart` guarda itens crus (productId, code, name, unit, categoriaId, quantity, unitPrice) e expõe `pricedItems()` = `PromotionEngine.apply(...)`. Mesmo produto adicionado de novo soma quantidade.
- Validações ao adicionar: q > 0; `unit != "UN"` (case-insensitive, trim) para aceitar fração, senão q deve ser inteira; quantidade final ≤ estoque (`"Estoque insuficiente. Disponível: X."`). Validade: `controlaValidade && dataValidade` vencida → aviso (não bloqueia); a vencer em ≤ `diasAlertaValidade` (padrão 15) → aviso informativo.
- Total = soma dos `itemTotal`.
- Busca: filtro por `name`, `code`, `gtin` ou `marca` contendo o texto (case-insensitive), lista de até 50 sugestões. Enter na busca: 1º match exato por `code` ou `gtin` → adiciona; senão tenta balança; senão, se a lista de sugestões tem item destacado, adiciona esse; senão "Produto não encontrado".

### D8. Pagamento
- `PaymentPlan(total)` com parcelas. `remaining = total − Σamount`. Adicionar parcela: amount > 0; amount ≤ remaining (tolerância 0 em centavos); dinheiro: cashGiven ≥ amount, `change = cashGiven − amount`; não-dinheiro: cashGiven = amount, change = 0.
- Fiado: exige cliente; `disponível = max(0, limite − saldo − fiadoJáNoPlano)`; `limite ≤ 0` ou amount > disponível → recusa com `"Limite de crédito insuficiente para NOME! Limite: R$ X | Saldo devedor: R$ Y | Disponível: R$ Z"`.
- Confirmação rápida: sem parcelas e valor corrente cobre o total → cria uma parcela única com o total (dinheiro: recebido ≥ total). Com parcelas: se o valor corrente == remaining, adiciona como última parcela. Depois exige Σ == total em centavos, senão `"Faltam R$ X para liquidar a venda."`.
- `paymentType` do cabeçalho: tipo da parcela única, ou `"Múltiplo"` com 2+ parcelas.

### D9. Payload da venda e hash
`SalePayloadFactory` gera uma vez por venda (antes do primeiro envio): `clientSaleId = UUID`, `eventId = UUID`, `eventType = "SALE_CREATED"`, `occurredAt = Instant.now()` ISO-8601, `offlineReference = "OFF-" + deviceId[0..4].upper + "-" + últimos 6 dígitos de epochMillis`, `customerName` (cliente ou `"Consumidor"`), `customerCpf` (documento do cliente, CPF digitado ou `"-"`), `paymentType`, `totalAmount = Money.formatBr(total)`, `operatorName`, `operatorId`, `items[{productCode, productName, quantity, unitPrice, desconto, itemTotal, promocaoId}]`, `payments[{paymentType, amount, cashGiven, changeAmount}]`, `reenvioOffline` (false online / true quando sai da fila).

`SaleValidator.validate(payload)` porta `validateSale`: total > 0 e itens; cada `itemTotal == itemNet`; Σ itens == total; cada parcela amount > 0, troco só em dinheiro, dinheiro com `cashGiven == amount + change`; Σ parcelas == total. Qualquer violação → `DomainException` e a venda **não** é enviada.

`PayloadHash.compute(payload)`: SHA-256 hex minúsculo do JSON canônico (mesma ordem de campos do `cryptoHash.ts`): `{customerName, customerCpf, paymentType, totalAmount(number), operatorName, items[ordenado por productCode]{productCode, quantity(4 casas), unitPrice(2), desconto(2), promocaoId|""}, payments[ordenado por paymentType]{paymentType, amount, cashGiven, changeAmount}}`. Números serializados como `BigDecimal.stripTrailingZeros().toPlainString()` (inteiro `10` → `10`, `10.50` → `10.5`), igual ao `JSON.stringify` do JS. Como a API usa o hash recebido, a exigência real é ser **determinístico** para a mesma venda; o hash é calculado uma vez e gravado com a venda.

### D10. Fluxo de confirmação (SaleService)
1. Revalida caixa: se online, `GET /api/Caixa/status`; `canSell=false` → erro com `blockReason`. Se offline, usa o último status em cache; sem cache ou `canSell=false` → erro "Abra o caixa antes de vender".
2. Monta payload + `SaleValidator` + hash.
3. **Online**: `POST /api/HistoricoVendas`.
   - Sucesso (200/201, ou 503 com `data.saleNumber`) → número da venda = `saleNumber`.
   - 4xx ≠ 408/429 → mostra mensagem, **mantém o carrinho**, nada vai para a fila.
   - status 0 / 5xx / 408 / 429 → passo 4 com o mesmo payload.
4. **Offline**: `OutboxStore.enqueue(payload)` com `reenvioOffline=true`; número exibido = `offlineReference`.
5. Pós-venda (ambos): baixa estoque no cache local (`CacheStore.decrementStock(code, qty)`), atualiza `saldoDevedor` do cliente no cache (online: `fiadoSaldoAtual` da resposta; offline: saldo + fiado), guarda último cupom, imprime, limpa carrinho, foco na busca, atualiza status do caixa (online).

### D11. Persistência local
Pasta `%APPDATA%\QuackCaixa\` (fallback `~/.quack-caixa`; `-Dquack.home=...` sobrescreve, usado nos testes):
- `config.properties`: `apiBaseUrl`, `deviceId` (UUID gerado na 1ª execução), `lastEmail`.
- `cache/products.json`, `cache/customers.json`, `cache/promotions.json`, `cache/company.json`, `cache/caixa-status.json`, `cache/last-receipt.json`.
- `outbox/pending/<epochMillis>-<eventId>.json` (um arquivo por venda: o payload exato a reenviar) e `outbox/failed/<…>.json` (payload + `error` + `failedAt`).
- Escrita atômica: grava `*.tmp` e `Files.move(tmp, final, ATOMIC_MOVE, REPLACE_EXISTING)`; um arquivo por venda evita corromper a fila inteira.
- Cache é por empresa: os arquivos guardam `companyId`; ao logar em outra empresa o cache antigo é ignorado (a fila nunca é ignorada — reenvio usa a sessão atual; se a API recusar com 4xx vai para `failed`).

Alternativa SQLite (via JDBC) rejeitada: dependência nativa a mais e o volume (centenas de vendas pendentes no pior caso) não exige banco.

### D12. SyncWorker
`ScheduledExecutorService` único (daemon) a cada 15 s, e também disparado após login e por F10:
1. Se não logado → nada. Ping se `apiReachable=false`.
2. Lista `pending` ordenado por nome (ordem de criação); para cada um, `POST /api/HistoricoVendas`. Sucesso/503-com-saleNumber → apaga o arquivo. 4xx ≠ 408/429 (inclui 409) → move para `failed` com a mensagem. Rede/5xx → para o ciclo (tenta no próximo).
3. Publica contadores `pending`/`failed` para a barra de status (via `Platform.runLater`).
Um `ReentrantLock` impede dois ciclos simultâneos (e a confirmação online não usa o worker, então não há corrida de reenvio do mesmo arquivo).

### D13. Tela e atalhos
Layout `PdvView` (BorderPane):
- **Topo**: "Quack Caixa" · empresa (fantasia + CNPJ) · operador · status do caixa (pílula verde "Caixa aberto há 2h 05min" / vermelha com o motivo) · indicador de rede ("Online"/"Modo offline") · "Pendentes: N / Falhas: M" · relógio.
- **Centro-esquerda**: campo de busca grande (F2) com `ListView` de sugestões (nome, código, preço, estoque), campo quantidade (F4), botão Adicionar; abaixo `TableView` do carrinho: #, código, descrição, qtd, unit., desconto (com nome da promoção em tooltip), total, botão remover (Delete remove o item selecionado).
- **Direita**: card com TOTAL gigante (amarelo Quack sobre marinho), nº de itens, cliente selecionado (F5 escolhe / limpa), CPF na nota, botões grandes: Pagamento (F12), Cancelar venda (F8), Caixa (F6), Sangria (F3), Reforço (F1), Sincronizar (F10), Última venda (F11 reimprime), Sair.
- Atalhos (cena inteira, `Scene.addEventFilter(KeyEvent.KEY_PRESSED)`): F1 reforço, F2 busca, F3 sangria, F4 quantidade, F5 cliente, F6 painel do caixa, F8 cancelar venda, F10 sincronizar, F11 reimprimir, F12 pagamento, Esc fecha diálogo, Delete remove item, ↑/↓ navegam sugestões. No `PaymentDialog`: Alt+1..5 forma, Enter adiciona/confirma, Esc fecha.
- `PaymentDialog` (modal): total, pago, restante, troco; botões de forma (com rótulo Alt+n); campo valor; campo "Recebido" só para dinheiro com atalhos de cédulas 10/20/50/100/200; lista de parcelas com remover; botões "Adicionar parcela" e "Confirmar venda (Enter)". Desabilita tudo enquanto confirma (evita duplo envio).
- `CashPanelDialog`: mostra status; caixa fechado → campo valor inicial + Abrir; aberto → valor inicial, esperado em dinheiro (`expectedCashAmount` quando vier), movimentos, campo valor contado, motivo da diferença, observação + Fechar; após fechar mostra esperado/contado/diferença retornados.
- Toasts: `Popup` no canto inferior direito, 3,5 s, cores sucesso/erro/info.

### D14. Cupom (ReceiptPrinter)
`VBox` de largura 280 px (≈ 80 mm), fonte monoespaçada 10 pt: fantasia, razão, CNPJ, endereço; "CUPOM NÃO FISCAL"; venda nº, data/hora, operador; itens (descrição / `qtd x unit = total`, linha de desconto com nome da promoção); total; parcelas com recebido/troco; cliente/CPF; se offline "Venda registrada offline — será sincronizada"; se `emitirFiscal` "NFC-e emitida pelo servidor". Impressão: `PrinterJob.createPrinterJob()` na impressora padrão, `job.printPage(node)`, `job.endJob()`, sempre na FX thread. Sem impressora → `DomainException("Nenhuma impressora disponível")` capturada e avisada; a venda já está salva.

### D15. Estilo
`quack.css` define as cores do design system como *looked-up colors* em `.root` (`-brand: #152238; -brand-pressed: #0c1726; -highlight: #ffc233; -highlight-strong: #f97316; -surface: #ffffff; -surface-muted: #f5f6f8; -border: #e5e7eb; -ink: #152238; -ink-muted: #5a6472; -success: #2e7d4f; -danger: #c0392b`) e só usa essas variáveis no resto do arquivo. Fonte `"Nunito", "Segoe UI", sans-serif`; raios 8/12 px; espaçamentos 4/8/16/24. Classes com prefixo `rg-` (`rg-total-card`, `rg-btn-primary`, `rg-pill-ok`, `rg-pill-danger`, `rg-toast-*`).

## Risks / Trade-offs

- [Recaptcha ligado em produção bloqueia o login do Java] → README explica; o login mostra a mensagem da API. Solução definitiva (token de dispositivo) fica para outra mudança.
- [Hash diferente do web para a mesma venda] → irrelevante: a venda nasce e é reenviada só pelo Java, com o hash gravado junto.
- [Estoque do cache diverge do servidor offline] → o servidor é a fonte da verdade; F10 e cada login baixam de novo.
- [Dois caixas (web e Java) com o mesmo operador] → a API já vincula a venda à sessão aberta do operador; sem mudança.
- [Fila em disco apagada pelo usuário] → arquivos ficam em `%APPDATA%`, fora da pasta do programa; README avisa.
- [Impressora térmica com largura diferente] → largura do cupom em constante única (`RECEIPT_WIDTH_PX`).
- [Sem caixa offline] → vendas offline só funcionam se o caixa foi aberto online antes; mensagem clara.

## Migration Plan

Módulo novo e independente: nada para migrar. Deploy = copiar `quack-caixa.jar` + `run.bat` para a máquina com Java 21. Rollback = voltar ao caixa web/Electron (mesma API, mesmos dados).
