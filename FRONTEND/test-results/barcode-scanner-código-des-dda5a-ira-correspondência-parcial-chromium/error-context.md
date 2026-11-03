# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: barcode-scanner.spec.ts >> código desconhecido mantém a busca e não adiciona a primeira correspondência parcial
- Location: tests\architecture\barcode-scanner.spec.ts:89:1

# Error details

```
Error: expect(locator).toBeVisible() failed

Locator: getByText('Produto não encontrado.', { exact: true })
Expected: visible
Timeout: 10000ms
Error: element(s) not found

Call log:
  - Expect "toBeVisible" with timeout 10000ms
  - waiting for getByText('Produto não encontrado.', { exact: true })

```

# Page snapshot

```yaml
- generic [ref=e5]:
  - banner [ref=e6]:
    - generic [ref=e7]:
      - generic [ref=e8]:
        - img "Quack Sistemas" [ref=e9]
        - generic [ref=e10]:
          - heading "Quack Sistemas" [level=1] [ref=e11]
          - paragraph [ref=e12]: Frente de Caixa • Soluções para seu negócio
      - generic [ref=e13]:
        - button "Sincronizar (F10)" [ref=e14] [cursor=pointer]:
          - img [ref=e15]
          - generic [ref=e20]: Sincronizar (F10)
        - button "Caixa (F6)" [ref=e21] [cursor=pointer]:
          - img [ref=e22]
          - generic [ref=e25]: Caixa (F6)
        - button "Vendas (F9)" [ref=e26] [cursor=pointer]:
          - img [ref=e27]
          - generic [ref=e30]: Vendas (F9)
        - button "Cancelar NFC-e (F7)" [ref=e31] [cursor=pointer]:
          - img [ref=e32]
          - generic [ref=e34]: Cancelar NFC-e (F7)
        - button "Sair da tela cheia" [ref=e35] [cursor=pointer]:
          - img [ref=e36]
          - generic [ref=e41]: Sair da tela cheia
        - generic [ref=e42]:
          - paragraph [ref=e43]: 20 de outubro de 2026
          - paragraph [ref=e44]: 23:17:19
  - main [ref=e45]:
    - complementary [ref=e46]:
      - generic [ref=e47]:
        - textbox "Nº do pedido" [ref=e48]
        - button "Buscar" [ref=e49] [cursor=pointer]
      - generic [ref=e50]:
        - generic [ref=e51]: "Produto:"
        - generic [ref=e52]:
          - img
          - textbox "Produto:" [ref=e53]
      - generic [ref=e54]:
        - generic [ref=e55]: "Quantidade (volume):"
        - textbox "Quantidade (volume):" [ref=e56]: "1"
      - generic [ref=e57]:
        - generic [ref=e58]: "Preço unitário:"
        - textbox "Preço unitário:" [disabled] [ref=e59]: 0,00
      - generic [ref=e60]:
        - generic [ref=e61]: "Preço total:"
        - textbox "Preço total:" [disabled] [ref=e62]: 0,00
      - button "ADICIONAR ITEM (ENTER)" [ref=e63] [cursor=pointer]
      - generic [ref=e64]:
        - generic [ref=e65]: Total volumes
        - generic [ref=e66]: "0000"
      - generic [ref=e69]:
        - img [ref=e70]
        - generic [ref=e74]: Sem imagem
    - generic [ref=e75]:
      - generic [ref=e76]:
        - paragraph [ref=e77]: "Empresa: Quack PDV"
        - paragraph [ref=e78]: "CNPJ: -"
      - generic [ref=e79]:
        - generic [ref=e81]: Caixa aberto por 1 min
        - button "Reforço (F1)" [ref=e82] [cursor=pointer]:
          - img [ref=e83]
          - text: Reforço (F1)
        - button "Sangria (F3)" [ref=e86] [cursor=pointer]:
          - img [ref=e87]
          - text: Sangria (F3)
      - generic [ref=e90]:
        - paragraph [ref=e91]: Nome produto
        - heading "AGUARDANDO PRODUTO" [level=2] [ref=e92]
      - generic [ref=e93]:
        - paragraph [ref=e94]: "Lista de itens:"
        - generic [ref=e96]:
          - img [ref=e97]
          - paragraph [ref=e100]: Nenhum item no cupom
          - paragraph [ref=e101]: Busque um produto ou leia o código de barras para começar.
      - generic [ref=e102]:
        - generic [ref=e103]: 00 - Ajuda
        - generic [ref=e104]:
          - generic [ref=e105]: SUB TOTAL
          - generic [ref=e106]: R$ 0,00
        - generic [ref=e107]:
          - button "✖ CANCELAR (F8)" [ref=e108] [cursor=pointer]
          - button "Imprimir última venda" [ref=e109] [cursor=pointer]:
            - img [ref=e110]
            - text: Imprimir última venda
          - button "PAGAMENTO (F12)" [ref=e114] [cursor=pointer]
        - generic [ref=e115]:
          - paragraph [ref=e116]: "Usuário: Gerente"
          - paragraph [ref=e117]: "Estabelecimento: Quack PDV"
          - paragraph [ref=e118]: "Prévia impressão: Sim • Caixa: PDV01 aberto"
```

# Test source

```ts
  1   | import { expect, test, type Page } from "@playwright/test";
  2   | 
  3   | const MILK_CODE = "7890000000001";
  4   | const COFFEE_CODE = "7890000000002";
  5   | const RICE_GTIN = "7890000000003";
  6   | const PACKAGED_CODE = "2999990001006";
  7   | const milkRow = (page: Page) => page.getByRole("row").filter({ hasText: "Leite scanner" });
  8   | const coffeeRow = (page: Page) => page.getByRole("row").filter({ hasText: "Café scanner" });
  9   | 
  10  | async function scan(page: Page, code: string, ending = "Enter") {
  11  |   // Real keyboard events, including browser input defaults and React controlled fields.
  12  |   await page.keyboard.type(code, { delay: 1 });
  13  |   await page.keyboard.press(ending);
  14  | }
  15  | 
  16  | async function openPos(page: Page, baseURL: string, installed = true) {
  17  |   const writes: string[] = [];
  18  |   const user = { id: "gerente-scanner", companyId: "loja-scanner", name: "Gerente", email: "teste@teste.invalid",
  19  |     role: "gerente", status: "ativo", createdAt: "2026-10-09T00:00:00Z", lastLoginAt: "2026-10-09T00:00:00Z", mustChangePassword: false };
  20  |   const products = [
  21  |     { id: "leite", productName: "Leite scanner", productCode: MILK_CODE, unidadeComercial: "UN", productSalePrice: "10,00" },
  22  |     { id: "cafe", productName: "Café scanner", productCode: COFFEE_CODE, unidadeComercial: "UN", productSalePrice: "20,00" },
  23  |     { id: "arroz", productName: "Arroz GTIN", productCode: "SKU-ARROZ", gtin: RICE_GTIN, unidadeComercial: "UN", productSalePrice: "15,00" },
  24  |     { id: "balanca", productName: "Queijo balança", productCode: "2691", unidadeComercial: "KG", productSalePrice: "10,00" },
  25  |     { id: "embalado", productName: "Produto embalado", productCode: "SKU-EMBALADO", gtin: PACKAGED_CODE,
  26  |       unidadeComercial: "UN", productSalePrice: "5,00" },
  27  |   ].map(product => ({ productUnitPrice: "5,00", productQnt: "9999", productSupplier: "", ...product }));
  28  |   await page.route("**/*", route => {
  29  |     const request = route.request();
  30  |     const url = new URL(request.url());
  31  |     if (url.origin === new URL(baseURL).origin) return route.continue();
  32  |     if (request.method() !== "GET") writes.push(`${request.method()} ${url.pathname}`);
  33  |     const data = url.pathname.endsWith("/Auth/me") ? user
  34  |       : url.pathname.endsWith("/Produto") ? products
  35  |       : url.pathname.endsWith("/Caixa/status") ? { state: "aberto", canSell: true, blockReason: "", serverNow: new Date().toISOString(),
  36  |         currentSession: { id: "sessao-scanner", status: "Aberto", openedAt: new Date().toISOString(), openingAmount: "100,00",
  37  |           closingAmount: "0,00", operatorId: user.id, operatorName: user.name, closedById: "", closedByName: "", note: "",
  38  |           elapsedMinutes: 1, expectedCashAmount: "100,00", movimentos: [], paymentBreakdown: [] }, lastSession: null, history: [] }
  39  |       : url.pathname.endsWith("/Empresa") ? null : [];
  40  |     return route.fulfill({ json: { success: true, data } });
  41  |   });
  42  |   await page.addInitScript(({ user, installed }) => {
  43  |     localStorage.setItem("horuspdv.auth.user", JSON.stringify(user));
  44  |     localStorage.setItem("horuspdv.activePage", "vendas");
  45  |     if (installed) window.quackDesktop = { savePendingBackup: async () => "" };
  46  |   }, { user, installed });
  47  |   await page.goto("/");
  48  |   const product = page.getByRole("textbox", { name: /^Produto:/ });
  49  |   await product.fill(MILK_CODE);
  50  |   await expect(page.getByText("Leite scanner", { exact: true }).first()).toBeVisible();
  51  |   await product.fill("");
  52  |   await page.getByText("Lista de itens:", { exact: true }).click();
  53  |   return { product, quantity: page.getByRole("textbox", { name: "Quantidade (volume):", exact: true }), writes };
  54  | }
  55  | 
  56  | test("lê após clicar fora da busca e adiciona uma única vez", async ({ page, baseURL }) => {
  57  |   const { product } = await openPos(page, baseURL!);
  58  |   await scan(page, MILK_CODE);
  59  |   await expect(milkRow(page)).toHaveCount(1);
  60  |   await expect(milkRow(page).getByRole("cell").nth(3)).toHaveText("1");
  61  |   await expect(product).toBeFocused();
  62  |   await expect(product).toHaveValue("");
  63  | });
  64  | 
  65  | test("restaura a quantidade anterior ao leitor e impede usar o código como quantidade", async ({ page, baseURL }) => {
  66  |   const { quantity } = await openPos(page, baseURL!);
  67  |   await quantity.fill("3");
  68  |   await scan(page, MILK_CODE);
  69  |   await expect(milkRow(page).getByRole("cell").nth(3)).toHaveText("3");
  70  |   await expect(quantity).toHaveValue("1");
  71  | });
  72  | 
  73  | test("substitui a pesquisa anterior pelo código exato", async ({ page, baseURL }) => {
  74  |   const { product } = await openPos(page, baseURL!);
  75  |   await product.fill("Café");
  76  |   await scan(page, MILK_CODE);
  77  |   await expect(milkRow(page)).toHaveCount(1);
  78  |   await expect(coffeeRow(page)).toHaveCount(0);
  79  | });
  80  | 
  81  | test("leituras repetidas somam unidades sem duplicar o Enter", async ({ page, baseURL }) => {
  82  |   await openPos(page, baseURL!);
  83  |   await scan(page, MILK_CODE);
  84  |   await scan(page, MILK_CODE);
  85  |   await expect(milkRow(page)).toHaveCount(1);
  86  |   await expect(milkRow(page).getByRole("cell").nth(3)).toHaveText("2");
  87  | });
  88  | 
  89  | test("código desconhecido mantém a busca e não adiciona a primeira correspondência parcial", async ({ page, baseURL }) => {
  90  |   const { product } = await openPos(page, baseURL!);
  91  |   const partialCode = MILK_CODE.slice(0, -1);
  92  |   await scan(page, partialCode);
> 93  |   await expect(page.getByText("Produto não encontrado.", { exact: true })).toBeVisible();
      |                                                                            ^ Error: expect(locator).toBeVisible() failed
  94  |   await expect(product).toHaveValue(partialCode);
  95  |   await expect(milkRow(page)).toHaveCount(0);
  96  | });
  97  | 
  98  | test("encontra GTIN mesmo quando o código interno do produto é diferente", async ({ page, baseURL }) => {
  99  |   await openPos(page, baseURL!);
  100 |   await scan(page, RICE_GTIN);
  101 |   await expect(page.getByRole("row").filter({ hasText: "Arroz GTIN" })).toHaveCount(1);
  102 | });
  103 | 
  104 | test("mantém a interpretação da etiqueta de balança", async ({ page, baseURL }) => {
  105 |   await openPos(page, baseURL!);
  106 |   const base = "226910000685";
  107 |   const sum = [...base].reduce((sum, digit, index) => sum + Number(digit) * (index % 2 ? 3 : 1), 0);
  108 |   await scan(page, base + (10 - sum % 10) % 10);
  109 |   const row = page.getByRole("row").filter({ hasText: "Queijo balança" });
  110 |   await expect(row.getByRole("cell").nth(3)).toHaveText("0.685");
  111 | });
  112 | 
  113 | test("prioriza o produto com código completo cadastrado antes de interpretar uma etiqueta", async ({ page, baseURL }) => {
  114 |   await openPos(page, baseURL!);
  115 |   await scan(page, PACKAGED_CODE);
  116 |   const row = page.getByRole("row").filter({ hasText: "Produto embalado" });
  117 |   await expect(row.getByRole("cell").nth(3)).toHaveText("1");
  118 | });
  119 | 
  120 | test("digitação manual e atalho de quantidade continuam funcionando", async ({ page, baseURL }) => {
  121 |   const { product, quantity } = await openPos(page, baseURL!);
  122 |   await product.fill("Leite scanner");
  123 |   await page.keyboard.press("F4");
  124 |   await expect(quantity).toBeFocused();
  125 |   await page.keyboard.type("1234", { delay: 120 });
  126 |   await expect(quantity).toHaveValue("1234");
  127 |   await page.keyboard.press("Enter");
  128 |   await expect(milkRow(page).getByRole("cell").nth(3)).toHaveText("1234");
  129 |   await expect(quantity).toHaveValue("1");
  130 |   await expect(product).toHaveValue("");
  131 | });
  132 | 
  133 | test("leitura no pagamento preserva o valor, não confirma venda e aguarda fechar a janela", async ({ page, baseURL }) => {
  134 |   const { writes } = await openPos(page, baseURL!);
  135 |   await scan(page, MILK_CODE);
  136 |   await page.keyboard.press("F12");
  137 |   await expect(page.getByRole("heading", { name: "Pagamento", exact: true })).toBeVisible();
  138 |   const amount = page.getByRole("textbox", { name: "Valor entregue em dinheiro", exact: true });
  139 |   await amount.fill("2000");
  140 |   await expect(amount).toHaveValue("20,00");
  141 |   await scan(page, COFFEE_CODE);
  142 |   await expect(amount).toHaveValue("20,00");
  143 |   await expect(page.getByRole("heading", { name: "Pagamento", exact: true })).toBeVisible();
  144 |   await expect(coffeeRow(page)).toHaveCount(0);
  145 |   expect(writes.filter(write => /\/Venda\b/i.test(write))).toHaveLength(0);
  146 |   await page.getByRole("button", { name: "Voltar", exact: true }).click();
  147 |   await expect(coffeeRow(page)).toHaveCount(1);
  148 |   expect(writes.filter(write => /\/Venda\b/i.test(write))).toHaveLength(0);
  149 | });
  150 | 
  151 | test("lê com terminador Tab sem depender do foco", async ({ page, baseURL }) => {
  152 |   const { product } = await openPos(page, baseURL!);
  153 |   await scan(page, MILK_CODE, "Tab");
  154 |   await expect(milkRow(page)).toHaveCount(1);
  155 |   await expect(product).toBeFocused();
  156 | });
  157 | 
  158 | test("preserva o campo de cliente e aguarda fechar todas as janelas", async ({ page, baseURL }) => {
  159 |   await openPos(page, baseURL!);
  160 |   await scan(page, MILK_CODE);
  161 |   await page.keyboard.press("F12");
  162 |   await page.getByRole("radio", { name: /Fiado/ }).click();
  163 |   await expect(page.getByRole("heading", { name: "Selecionar Cliente para Fiado" })).toBeVisible();
  164 |   const customer = page.getByPlaceholder("Pesquise por nome, CPF ou celular...");
  165 |   await customer.fill("Maria");
  166 |   await scan(page, COFFEE_CODE);
  167 |   await expect(customer).toHaveValue("Maria");
  168 |   await page.getByRole("button", { name: "Fechar seleção de cliente" }).click();
  169 |   await expect(coffeeRow(page)).toHaveCount(0);
  170 |   await page.getByRole("button", { name: "Voltar", exact: true }).click();
  171 |   await expect(coffeeRow(page)).toHaveCount(1);
  172 | });
  173 | 
  174 | test("o Enter do leitor não aciona o botão que recebeu foco", async ({ page, baseURL }) => {
  175 |   await openPos(page, baseURL!);
  176 |   await scan(page, MILK_CODE);
  177 |   await page.getByRole("button", { name: /PAGAMENTO \(F12\)/ }).focus();
  178 |   await scan(page, COFFEE_CODE);
  179 |   await expect(coffeeRow(page)).toHaveCount(1);
  180 |   await expect(page.getByRole("heading", { name: "Pagamento", exact: true })).toHaveCount(0);
  181 | });
  182 | 
  183 | test("usa o catálogo local quando cai a internet", async ({ page, baseURL, context }) => {
  184 |   await openPos(page, baseURL!);
  185 |   await context.setOffline(true);
  186 |   await scan(page, MILK_CODE);
  187 |   await expect(milkRow(page)).toHaveCount(1);
  188 | });
  189 | 
  190 | test("a captura fora da busca fica restrita ao aplicativo instalado", async ({ page, baseURL }) => {
  191 |   await openPos(page, baseURL!, false);
  192 |   await scan(page, MILK_CODE);
  193 |   await expect(milkRow(page)).toHaveCount(0);
```