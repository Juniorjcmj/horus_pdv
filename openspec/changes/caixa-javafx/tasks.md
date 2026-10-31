# Tasks

Referência obrigatória: `design.md` (D1–D15). Todos os comandos Maven rodam offline (`-o`) a partir da raiz do repositório.

## 1. Esqueleto do módulo

- [x] 1.1 Criar `CAIXA_JAVAFX/pom.xml` conforme D2 (Java 21, javafx-controls 21.0.8, jackson-databind + jsr310 2.17.2, junit-jupiter 5.10.2, compiler 3.13.0, surefire 3.2.5, shade 3.5.3 com Main-Class `br.com.quack.caixa.Launcher`, finalName `quack-caixa`). Verificar: `mvn -o -q -f CAIXA_JAVAFX/pom.xml validate` sem erro.
- [x] 1.2 Criar `Launcher`, `App` (janela "Quack Caixa" maximizada, mínimo 1024×700), `config/AppPaths`, `config/AppConfig` (D11) e `quack.css` (D15). Verificar: `mvn -o -q -f CAIXA_JAVAFX/pom.xml compile`.
- [x] 1.3 Criar `README.md` (requisitos, build, execução, pasta de dados, atalhos, limitações) e `run.bat`. Verificar: arquivos presentes.

## 2. Domínio puro (com testes)

- [x] 2.1 `domain/Money` (D4) + `MoneyTest` (parse "1.234,56", "12.9", "R$ 5,00"; format; productMoney 0,452×12,90 = 5,83; itemNet com desconto maior que bruto lança). Verificar: teste passa.
- [x] 2.2 `domain/Product`, `Customer`, `Promotion`, `Company`, `CartItem`, `PaymentType`, `PaymentPart`, `DomainException`; `Product.fromDto` mapeia campos de D-Context (preço/estoque pt-BR, unidade padrão "UN"). Verificar: compila.
- [x] 2.3 `domain/PromotionEngine` (D5) + `PromotionEngineTest` cobrindo os 6 tipos, vigência, categoria, melhor desconto sem empilhar. Verificar: teste passa.
- [x] 2.4 `domain/BalancaBarcode` (D6) + `BalancaBarcodeTest` (DV inválido → null; PLU 4 dígitos modo preço; PLU 5 dígitos modo peso; fallback sem produtos). Verificar: teste passa.
- [x] 2.5 `domain/Cart` (D7) + `CartTest` (soma quantidade do mesmo produto, estoque insuficiente, fração em "UN" recusada, fração em "KG" aceita, total com promoção). Verificar: teste passa.
- [x] 2.6 `domain/PaymentPlan` (D8) + `PaymentPlanTest` (pagamento rápido com troco, parcela acima do saldo, troco só em dinheiro, fiado sem cliente, fiado acima do limite, faltando valor). Verificar: teste passa.
- [x] 2.7 `api/dto/VendaPayload` (+ itens/pagamentos), `domain/SalePayloadFactory`, `domain/SaleValidator`, `domain/PayloadHash` (D9) + testes (hash determinístico, ordem por productCode, `10.50` serializa `10.5`, validator recusa soma divergente). Verificar: teste passa.

## 3. Persistência local

- [x] 3.1 `persistence/JsonFiles` (escrita atômica) e `persistence/CacheStore` (D11, com `companyId` e `decrementStock`). Verificar: compila.
- [x] 3.2 `persistence/OutboxStore` (enqueue, listPending ordenado, remove, moveToFailed, counts) + `OutboxStoreTest` usando `@TempDir` e `-Dquack.home`/construtor com pasta. Verificar: teste passa.

## 4. Cliente da API

- [x] 4.1 `api/ApiClient` + `ApiException` (D3: CookieManager, timeouts, envelope, `apiReachable`, evento 401). Verificar: compila.
- [x] 4.2 `api/AuthApi` (login, logout), `api/CaixaApi` (status, abrir, fechar, movimento com eventId+hash SHA-256 dos canônicos do servidor), `api/CatalogApi` (produtos, clientes, promoções ativas, empresa), `api/VendaApi` (registrar; 503 com saleNumber = sucesso) e DTOs. Verificar: compila.

## 5. Serviços

- [x] 5.1 `service/Session`, `service/CatalogService` (API → cache, fallback para cache), `service/CaixaService` (online-only para escrita; status cai para cache). Verificar: compila.
- [x] 5.2 `service/SaleService` (D10) e `service/SyncWorker` (D12). Verificar: compila; `SaleServiceTest` com `VendaApi` falso cobre 4xx (não enfileira), rede (enfileira) e sucesso.
- [x] 5.3 `service/ReceiptPrinter` (D14). Verificar: compila.

## 6. Telas

- [x] 6.1 `ui/LoginView` (URL da API, e-mail, senha, Entrar; erro da API; Enter envia). Verificar: compila.
- [x] 6.2 `ui/PdvView` com topo, busca + sugestões, quantidade, carrinho, painel de total e botões (D13). Verificar: compila.
- [x] 6.3 `ui/PaymentDialog`, `ui/CustomerPickerDialog`, `ui/CashPanelDialog`, `ui/CashMovementDialog`, `ui/Toasts`, `ui/Fx` e atalhos de teclado de D13. Verificar: compila.
- [x] 6.4 Ligar tudo em `App` (login → carregar catálogo/caixa → PdvView; 401 → login; iniciar/parar SyncWorker; fechar janela encerra executores). Verificar: `mvn -o -f CAIXA_JAVAFX/pom.xml package` gera `target/quack-caixa.jar` e todos os testes passam.

## 7. Verificação final

- [x] 7.1 Rodar `java -jar CAIXA_JAVAFX/target/quack-caixa.jar` e confirmar que a tela de login abre sem exceção no console (encerrar após alguns segundos). Verificar: processo sobe e janela é criada.
- [x] 7.2 Revisar paridade com o caixa web: atalhos, mensagens de erro e regras de D7/D8 conferidas contra `SalesStartPage.tsx`. Verificar: checklist no README atualizado.

## Workflow follow-up

- Rodar `graphify update .` após a implementação.
- Commit do módulo `CAIXA_JAVAFX/` e da change `openspec/changes/caixa-javafx/`.
- Arquivar a change com `/opsx:archive` depois de testada numa loja.
