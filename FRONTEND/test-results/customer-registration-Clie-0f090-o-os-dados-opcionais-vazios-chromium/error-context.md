# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: customer-registration.spec.ts >> Cliente com nome e telefone obrigatórios >> cadastra dois clientes sem documento e permite editar mantendo os dados opcionais vazios
- Location: tests\architecture\customer-registration.spec.ts:85:3

# Error details

```
Error: expect(received).toHaveLength(expected)

Expected length: 2
Received length: 3
Received array:  [{"id": "cliente-1"}, {"address": "", "age": "", "birthDate": "", "cellphone": "", "cep": "", "city": "", "customerName": "Maria Silva", "document": "", "email": "", "id": "cliente-2", "limiteCredito": 0, "neighborhood": "", "number": "", "referencePoint": "", "saldoDevedor": 0, "state": "", "streetComplement": "", "telephone": "(11) 5555-6666"}, {"address": "", "age": "", "birthDate": "", "cellphone": "", "cep": "", "city": "", "customerName": "João Silva", "document": "", "email": "", "id": "cliente-3", "limiteCredito": 0, "neighborhood": "", "number": "", "referencePoint": "", "saldoDevedor": 0, "state": "", "streetComplement": "", "telephone": "(11) 4444-5555"}]
```

# Page snapshot

```yaml
- generic [ref=e2]:
  - generic [ref=e4]:
    - img [ref=e5]
    - generic [ref=e8]: Cliente atualizado com sucesso.
    - button "Fechar toast" [ref=e9] [cursor=pointer]:
      - img [ref=e10]
  - generic [ref=e13]:
    - link "Ir para o conteúdo" [ref=e14] [cursor=pointer]:
      - /url: "#admin-content"
    - generic [ref=e16]:
      - img [ref=e18]
      - generic [ref=e20]: Online
    - complementary "Menu principal" [ref=e21]:
      - generic [ref=e22]:
        - generic [ref=e23]:
          - generic [ref=e24]:
            - img "Quack Sistemas" [ref=e25]
            - generic [ref=e26]:
              - heading "Quack Sistemas" [level=1] [ref=e27]
              - paragraph [ref=e28]: Soluções para seu negócio
          - button "Recolher menu lateral" [ref=e30] [cursor=pointer]:
            - img [ref=e31]
        - navigation "Navegação principal" [ref=e32]:
          - generic [ref=e33]:
            - heading "Principal" [level=2] [ref=e34]
            - button "Visão geral" [ref=e35] [cursor=pointer]:
              - img [ref=e37]
              - generic [ref=e40]: Visão geral
              - img [ref=e41]
          - generic [ref=e43]:
            - heading "Cadastros" [level=2] [ref=e44]
            - button "Cliente" [ref=e45] [cursor=pointer]:
              - img [ref=e47]
              - generic [ref=e50]: Cliente
              - img [ref=e51]
            - button "Fornecedor" [ref=e53] [cursor=pointer]:
              - img [ref=e55]
              - generic [ref=e60]: Fornecedor
              - img [ref=e61]
            - button "Produto" [ref=e63] [cursor=pointer]:
              - img [ref=e65]
              - generic [ref=e69]: Produto
              - img [ref=e70]
            - button "Tributação dos produtos" [ref=e72] [cursor=pointer]:
              - img [ref=e74]
              - generic [ref=e77]: Tributação dos produtos
              - img [ref=e78]
            - button "Contas de Usuários" [ref=e80] [cursor=pointer]:
              - img [ref=e82]
              - generic [ref=e94]: Contas de Usuários
              - img [ref=e95]
          - generic [ref=e97]:
            - heading "Operação" [level=2] [ref=e98]
            - button "Histórico de Vendas" [ref=e99] [cursor=pointer]:
              - img [ref=e101]
              - generic [ref=e105]: Histórico de Vendas
              - img [ref=e106]
            - button "Promoções" [ref=e108] [cursor=pointer]:
              - img [ref=e110]
              - generic [ref=e113]: Promoções
              - img [ref=e114]
            - button "Controle de Validade" [ref=e116] [cursor=pointer]:
              - img [ref=e118]
              - generic [ref=e122]: Controle de Validade
              - img [ref=e123]
            - button "Fiado / Conta Corrente" [ref=e125] [cursor=pointer]:
              - img [ref=e127]
              - generic [ref=e129]: Fiado / Conta Corrente
              - img [ref=e130]
            - button "Relatórios" [ref=e132] [cursor=pointer]:
              - img [ref=e134]
              - generic [ref=e137]: Relatórios
              - img [ref=e138]
            - button "Iniciar Vendas" [ref=e140] [cursor=pointer]:
              - img [ref=e142]
              - generic [ref=e146]: Iniciar Vendas
              - img [ref=e147]
            - button "Novo Pedido" [ref=e149] [cursor=pointer]:
              - img [ref=e151]
              - generic [ref=e154]: Novo Pedido
              - img [ref=e155]
          - generic [ref=e157]:
            - heading "Gestão Avançada" [level=2] [ref=e158]
            - button "Fiscal NFC-e / NF-e" [ref=e159] [cursor=pointer]:
              - img [ref=e161]
              - generic [ref=e164]: Fiscal NFC-e / NF-e
              - img [ref=e165]
            - button "NF-e Modelo 55" [ref=e167] [cursor=pointer]:
              - img [ref=e169]
              - generic [ref=e172]: NF-e Modelo 55
              - img [ref=e173]
            - button "Pagamentos Integrados" [ref=e175] [cursor=pointer]:
              - img [ref=e177]
              - generic [ref=e179]: Pagamentos Integrados
              - img [ref=e180]
            - button "Estoque e Inventário" [ref=e182] [cursor=pointer]:
              - img [ref=e184]
              - generic [ref=e189]: Estoque e Inventário
              - img [ref=e190]
            - button "Abertura e Fechamento" [ref=e192] [cursor=pointer]:
              - img [ref=e194]
              - generic [ref=e196]: Abertura e Fechamento
              - img [ref=e197]
            - button "Compras e Reposição" [ref=e199] [cursor=pointer]:
              - img [ref=e201]
              - generic [ref=e204]: Compras e Reposição
              - img [ref=e205]
            - button "Trocas e Devoluções" [ref=e207] [cursor=pointer]:
              - img [ref=e209]
              - generic [ref=e214]: Trocas e Devoluções
              - img [ref=e215]
          - generic [ref=e217]:
            - heading "Sistema" [level=2] [ref=e218]
            - button "Minha Empresa" [ref=e219] [cursor=pointer]:
              - img [ref=e221]
              - generic [ref=e225]: Minha Empresa
              - img [ref=e226]
            - button "Detalhes da Licença" [ref=e228] [cursor=pointer]:
              - img [ref=e230]
              - generic [ref=e234]: Detalhes da Licença
              - img [ref=e235]
            - button "Sobre PDV" [ref=e237] [cursor=pointer]:
              - img [ref=e239]
              - generic [ref=e241]: Sobre PDV
              - img [ref=e242]
            - button "Aprendizado" [ref=e244] [cursor=pointer]:
              - img [ref=e246]
              - generic [ref=e249]: Aprendizado
              - img [ref=e250]
      - button "Menu de Administrador" [ref=e254] [cursor=pointer]:
        - generic [ref=e255]: A
        - generic [ref=e256]:
          - generic [ref=e257]: Administrador
          - generic [ref=e259]: Administrador
        - img [ref=e260]
    - main [ref=e262]:
      - generic [ref=e263]:
        - generic [ref=e264]:
          - generic [ref=e265]:
            - generic [ref=e266]:
              - heading "Cadastro de Cliente" [level=1] [ref=e267]
              - button "Abrir tour da tela" [ref=e268] [cursor=pointer]:
                - img [ref=e269]
                - generic [ref=e272]: Tour da tela
            - paragraph [ref=e273]: Cadastre clientes e mantenha documentos, contatos e endereços atualizados.
          - button "Novo cliente" [ref=e275] [cursor=pointer]:
            - img [ref=e276]
            - text: Novo cliente
        - generic [ref=e278]:
          - img
          - textbox "Buscar clientes por nome ou documento" [ref=e279]:
            - /placeholder: Pesquise por nome ou CPF
        - generic [ref=e280]:
          - region "Clientes" [ref=e281]:
            - paragraph [ref=e282]: Role para os lados para ver todas as colunas.
            - table [ref=e283]:
              - rowgroup [ref=e284]:
                - row "Selecionar clientes desta página Nome Documento Cidade Telefone / celular E-mail Saldo Devedor Ações" [ref=e285]:
                  - columnheader "Selecionar clientes desta página" [ref=e286]:
                    - checkbox "Selecionar clientes desta página" [ref=e287]
                  - columnheader "Nome" [ref=e288]
                  - columnheader "Documento" [ref=e289]
                  - columnheader "Cidade" [ref=e290]
                  - columnheader "Telefone / celular" [ref=e291]
                  - columnheader "E-mail" [ref=e292]
                  - columnheader "Saldo Devedor" [ref=e293]
                  - columnheader "Ações" [ref=e294]
              - rowgroup [ref=e295]:
                - row "Selecionar João Silva João Silva (11) 4444-5555 - R$ 0,00 Abrir ações" [ref=e296]:
                  - cell "Selecionar João Silva" [ref=e297]:
                    - checkbox "Selecionar João Silva" [ref=e298]
                  - cell "João Silva" [ref=e299]
                  - cell [ref=e300]
                  - cell [ref=e301]
                  - cell "(11) 4444-5555" [ref=e302]
                  - cell "-" [ref=e303]
                  - cell "R$ 0,00" [ref=e304]
                  - cell "Abrir ações" [ref=e305]:
                    - button "Abrir ações" [ref=e307] [cursor=pointer]:
                      - img [ref=e308]
                - row "Selecionar Maria Silva Maria Silva (11) 5555-6666 - R$ 0,00 Abrir ações" [ref=e312]:
                  - cell "Selecionar Maria Silva" [ref=e313]:
                    - checkbox "Selecionar Maria Silva" [ref=e314]
                  - cell "Maria Silva" [ref=e315]
                  - cell [ref=e316]
                  - cell [ref=e317]
                  - cell "(11) 5555-6666" [ref=e318]
                  - cell "-" [ref=e319]
                  - cell "R$ 0,00" [ref=e320]
                  - cell "Abrir ações" [ref=e321]:
                    - button "Abrir ações" [ref=e323] [cursor=pointer]:
                      - img [ref=e324]
                - row "Selecionar undefined - R$ 0,00 Abrir ações" [ref=e328]:
                  - cell "Selecionar undefined" [ref=e329]:
                    - checkbox "Selecionar undefined" [ref=e330]
                  - cell [ref=e331]
                  - cell [ref=e332]
                  - cell [ref=e333]
                  - cell [ref=e334]
                  - cell "-" [ref=e335]
                  - cell "R$ 0,00" [ref=e336]
                  - cell "Abrir ações" [ref=e337]:
                    - button "Abrir ações" [ref=e339] [cursor=pointer]:
                      - img [ref=e340]
          - generic [ref=e345]:
            - paragraph [ref=e346]: Mostrando 1-3 de 3 registros
            - generic [ref=e347]:
              - generic [ref=e348]:
                - text: Linhas por página
                - combobox "Linhas por página" [ref=e349]:
                  - option "10" [selected]
                  - option "20"
                  - option "50"
                  - option "100"
              - navigation "Paginação da lista" [ref=e350]:
                - button "Anterior" [disabled] [ref=e351]
                - button "Página 1" [ref=e352] [cursor=pointer]: "1"
                - button "Próxima" [disabled] [ref=e353]
```

# Test source

```ts
  1   | import { test, expect, type Page } from "@playwright/test";
  2   | import type { CustomerDto, CustomerPayload } from "../../src/services/api/customerService";
  3   | 
  4   | async function fillMinimum(page: Page, phoneField = "Telefone *", number = "1133334444", name = "Maria Silva") {
  5   |   await page.getByRole("textbox", { name: "Nome *", exact: true }).fill(name);
  6   |   await page.getByRole("textbox", { name: phoneField, exact: true }).fill(number);
  7   | }
  8   | 
  9   | test.describe("Cliente com nome e telefone obrigatórios", () => {
  10  |   let writes: CustomerPayload[];
  11  |   let customers: CustomerDto[];
  12  |   test.beforeEach(async ({ page, baseURL }) => {
  13  |     writes = [];
  14  |     customers = [];
  15  |     const user = { id: "admin-teste", companyId: "loja-teste", name: "Administrador", email: "teste@teste.invalid",
  16  |       role: "administrador", status: "ativo", createdAt: "2026-10-09T00:00:00Z", lastLoginAt: "2026-10-09T00:00:00Z", mustChangePassword: false };
  17  |     await page.route("**/*", route => {
  18  |       if (new URL(route.request().url()).origin === new URL(baseURL!).origin) return route.continue();
  19  |       return route.fulfill({ json: { success: true, data: [] } });
  20  |     });
  21  |     await page.route("**/api/Auth/me", route => route.fulfill({ json: { success: true, data: user } }));
  22  |     await page.route("**/api/Cliente**", route => {
  23  |       if (route.request().method() === "GET") return route.fulfill({ json: { success: true, data: customers } });
  24  |       const payload = route.request().postDataJSON() as CustomerPayload;
  25  |       writes.push(payload);
  26  |       const id = route.request().method() === "PUT" ? new URL(route.request().url()).pathname.split("/").pop()! : `cliente-${writes.length}`;
  27  |       const saved = { ...payload, id };
  28  |       const index = customers.findIndex(customer => customer.id === id);
  29  |       if (index === -1) customers.push(saved); else customers[index] = saved;
  30  |       return route.fulfill({ json: { success: true, data: saved } });
  31  |     });
  32  |     await page.addInitScript(operator => {
  33  |       localStorage.setItem("horuspdv.auth.user", JSON.stringify(operator));
  34  |       localStorage.setItem("horuspdv.activePage", "cadastro-cliente");
  35  |     }, user);
  36  |     await page.goto("/");
  37  |     await page.getByRole("button", { name: "Novo cliente", exact: true }).click();
  38  |   });
  39  | 
  40  |   test("salva só nome e telefone fixo, deixando os demais dados vazios", async ({ page }) => {
  41  |     await fillMinimum(page);
  42  |     await page.getByRole("button", { name: "Criar cliente", exact: true }).click();
  43  |     await expect(page.getByText("Cliente cadastrado com sucesso.", { exact: true })).toBeVisible();
  44  |     expect(writes).toHaveLength(1);
  45  |     expect(writes[0]).toMatchObject({ customerName: "Maria Silva", telephone: "(11) 3333-4444", cellphone: "", document: "",
  46  |       birthDate: "", age: "", cep: "", city: "", state: "", address: "", neighborhood: "", number: "", email: "" });
  47  |     await expect(page.getByRole("cell", { name: "(11) 3333-4444", exact: true })).toBeVisible();
  48  |   });
  49  | 
  50  |   test("aceita celular no lugar de telefone fixo", async ({ page }) => {
  51  |     await fillMinimum(page, "Celular", "11999998888");
  52  |     await page.getByRole("button", { name: "Criar cliente", exact: true }).click();
  53  |     await expect(page.getByText("Cliente cadastrado com sucesso.", { exact: true })).toBeVisible();
  54  |     expect(writes[0]).toMatchObject({ telephone: "", cellphone: "(11) 99999-8888", document: "" });
  55  |   });
  56  | 
  57  |   test("aceita número de celular também no campo Telefone", async ({ page }) => {
  58  |     await fillMinimum(page, "Telefone *", "11999998888");
  59  |     await page.getByRole("button", { name: "Criar cliente", exact: true }).click();
  60  |     await expect(page.getByText("Cliente cadastrado com sucesso.", { exact: true })).toBeVisible();
  61  |     expect(writes[0].telephone).toBe("(11) 99999-8888");
  62  |   });
  63  | 
  64  |   for (const [field, message] of [["Nome *", "Informe o nome do cliente."], ["Telefone *", "Informe um telefone ou celular com DDD."]]) {
  65  |     test(`bloqueia quando falta ${field}`, async ({ page }) => {
  66  |       await fillMinimum(page);
  67  |       await page.getByRole("textbox", { name: field, exact: true }).fill("");
  68  |       await page.getByRole("button", { name: "Criar cliente", exact: true }).click();
  69  |       await expect(page.getByText(message, { exact: true })).toBeVisible();
  70  |       expect(writes).toHaveLength(0);
  71  |     });
  72  |   }
  73  | 
  74  |   test("rejeita telefone incompleto e documento inválido se preenchido", async ({ page }) => {
  75  |     await fillMinimum(page, "Telefone *", "123");
  76  |     await page.getByRole("button", { name: "Criar cliente", exact: true }).click();
  77  |     await expect(page.getByText("Informe um telefone ou celular válido com DDD (10 ou 11 dígitos).", { exact: true })).toBeVisible();
  78  |     await page.getByRole("textbox", { name: "Telefone *", exact: true }).fill("1133334444");
  79  |     await page.getByRole("textbox", { name: "Documento (CPF/CNPJ)", exact: true }).fill("11111111111");
  80  |     await page.getByRole("button", { name: "Criar cliente", exact: true }).click();
  81  |     await expect(page.getByText("Documento inválido.", { exact: true })).toBeVisible();
  82  |     expect(writes).toHaveLength(0);
  83  |   });
  84  | 
  85  |   test("cadastra dois clientes sem documento e permite editar mantendo os dados opcionais vazios", async ({ page }) => {
  86  |     await fillMinimum(page);
  87  |     await page.getByRole("button", { name: "Criar cliente", exact: true }).click();
  88  |     await expect(page.getByText("Cliente cadastrado com sucesso.", { exact: true })).toBeVisible();
  89  |     await page.getByRole("button", { name: "Novo cliente", exact: true }).click();
  90  |     await fillMinimum(page, "Telefone *", "1144445555", "João Silva");
  91  |     await page.getByRole("button", { name: "Criar cliente", exact: true }).click();
  92  |     await expect(page.getByRole("cell", { name: "João Silva", exact: true })).toBeVisible();
  93  |     const row = page.getByRole("row").filter({ hasText: "Maria Silva" });
  94  |     await row.getByRole("button", { name: "Abrir ações", exact: true }).click();
  95  |     await page.getByRole("menuitem", { name: "Editar", exact: true }).click();
  96  |     await page.getByRole("textbox", { name: "Telefone *", exact: true }).fill("1155556666");
  97  |     await page.getByRole("button", { name: "Salvar cliente", exact: true }).click();
  98  |     await expect(page.getByText("Cliente atualizado com sucesso.", { exact: true })).toBeVisible();
> 99  |     expect(customers).toHaveLength(2);
      |                       ^ Error: expect(received).toHaveLength(expected)
  100 |     expect(writes[2]).toMatchObject({ telephone: "(11) 5555-6666", document: "", birthDate: "", address: "" });
  101 |   });
  102 | 
  103 |   for (const width of [1366, 375]) {
  104 |     test(`explica os dois dados obrigatórios e mantém os demais opcionais em ${width}px`, async ({ page }, testInfo) => {
  105 |       await page.setViewportSize({ width, height: 900 });
  106 |       await expect(page.getByText("Informe nome e telefone ou celular. Os demais dados são opcionais.", { exact: true })).toBeVisible();
  107 |       for (const field of ["Documento (CPF/CNPJ)", "CEP", "Cidade", "Endereço", "Bairro", "Número", "Celular", "E-mail"]) {
  108 |         await expect(page.getByRole("textbox", { name: field, exact: true })).not.toHaveAttribute("required");
  109 |       }
  110 |       await page.screenshot({ path: testInfo.outputPath(`cliente-${width}.png`) });
  111 |     });
  112 |   }
  113 | 
  114 |   test("mantém as exigências do fornecedor ao compartilhar os campos de contato", async ({ page }) => {
  115 |     await page.getByRole("button", { name: "Fechar formulário", exact: true }).click();
  116 |     await page.locator("#admin-sidebar").getByRole("button", { name: "Fornecedor", exact: true }).click();
  117 |     await page.getByRole("button", { name: "Novo fornecedor", exact: true }).click();
  118 |     await expect(page.getByRole("textbox", { name: "CEP *", exact: true })).toBeVisible();
  119 |     await expect(page.getByRole("textbox", { name: "Celular *", exact: true })).toBeVisible();
  120 |     await expect(page.getByRole("textbox", { name: "Telefone", exact: true })).toBeVisible();
  121 |     await page.getByRole("button", { name: "Criar fornecedor", exact: true }).click();
  122 |     await expect(page.getByText("Informe a Razão Social ou Nome Fantasia do fornecedor.", { exact: true })).toBeVisible();
  123 |     expect(writes).toHaveLength(0);
  124 |   });
  125 | 
  126 |   test("cadastro rápido salva sem documento e só exige nome e telefone", async ({ page }) => {
  127 |     await page.getByRole("button", { name: "Fechar formulário", exact: true }).click();
  128 |     // Exercita o mesmo componente usado no caixa, sem iniciar uma venda ou acessar serviços da loja.
  129 |     await page.evaluate(async () => {
  130 |       const { mountQuickCustomer } = await import("/tests/fixtures/customer-quick.fixture.tsx" as string);
  131 |       mountQuickCustomer();
  132 |     });
  133 |     const dialog = page.getByRole("dialog", { name: "Cadastro Rápido de Cliente", exact: true });
  134 |     await expect(dialog.locator("input[required]")).toHaveCount(2);
  135 |     await dialog.getByRole("textbox", { name: "Nome Completo *", exact: true }).fill("Cliente Rápido");
  136 |     await dialog.getByRole("textbox", { name: "Telefone / celular *", exact: true }).fill("1133334444");
  137 |     await dialog.getByRole("button", { name: "Salvar e Selecionar Cliente", exact: true }).click();
  138 |     await expect(page.locator("#customer-quick-test")).toHaveAttribute("data-saved", "cliente-1");
  139 |     expect(writes[0]).toMatchObject({ customerName: "Cliente Rápido", document: "", cellphone: "(11) 3333-4444", address: "" });
  140 |   });
  141 | });
  142 | 
```