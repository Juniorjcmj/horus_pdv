# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: customer-registration.spec.ts >> Cliente com nome e telefone obrigatórios >> rejeita telefone incompleto e documento inválido se preenchido
- Location: tests\architecture\customer-registration.spec.ts:74:3

# Error details

```
Error: expect(received).toHaveLength(expected)

Expected length: 0
Received length: 1
Received array:  [[]]
```

# Page snapshot

```yaml
- generic [ref=e2]:
  - generic [ref=e4]:
    - img [ref=e5]
    - generic [ref=e9]: Documento inválido.
    - button "Fechar toast" [ref=e10] [cursor=pointer]:
      - img [ref=e11]
  - generic [ref=e14]:
    - link "Ir para o conteúdo" [ref=e15] [cursor=pointer]:
      - /url: "#admin-content"
    - generic [ref=e17]:
      - img [ref=e19]
      - generic [ref=e21]: Online
    - complementary "Menu principal" [ref=e22]:
      - generic [ref=e23]:
        - generic [ref=e24]:
          - generic [ref=e25]:
            - img "Quack Sistemas" [ref=e26]
            - generic [ref=e27]:
              - heading "Quack Sistemas" [level=1] [ref=e28]
              - paragraph [ref=e29]: Soluções para seu negócio
          - button "Recolher menu lateral" [ref=e31] [cursor=pointer]:
            - img [ref=e32]
        - navigation "Navegação principal" [ref=e33]:
          - generic [ref=e34]:
            - heading "Principal" [level=2] [ref=e35]
            - button "Visão geral" [ref=e36] [cursor=pointer]:
              - img [ref=e38]
              - generic [ref=e41]: Visão geral
              - img [ref=e42]
          - generic [ref=e44]:
            - heading "Cadastros" [level=2] [ref=e45]
            - button "Cliente" [ref=e46] [cursor=pointer]:
              - img [ref=e48]
              - generic [ref=e51]: Cliente
              - img [ref=e52]
            - button "Fornecedor" [ref=e54] [cursor=pointer]:
              - img [ref=e56]
              - generic [ref=e61]: Fornecedor
              - img [ref=e62]
            - button "Produto" [ref=e64] [cursor=pointer]:
              - img [ref=e66]
              - generic [ref=e70]: Produto
              - img [ref=e71]
            - button "Tributação dos produtos" [ref=e73] [cursor=pointer]:
              - img [ref=e75]
              - generic [ref=e78]: Tributação dos produtos
              - img [ref=e79]
            - button "Contas de Usuários" [ref=e81] [cursor=pointer]:
              - img [ref=e83]
              - generic [ref=e95]: Contas de Usuários
              - img [ref=e96]
          - generic [ref=e98]:
            - heading "Operação" [level=2] [ref=e99]
            - button "Histórico de Vendas" [ref=e100] [cursor=pointer]:
              - img [ref=e102]
              - generic [ref=e106]: Histórico de Vendas
              - img [ref=e107]
            - button "Promoções" [ref=e109] [cursor=pointer]:
              - img [ref=e111]
              - generic [ref=e114]: Promoções
              - img [ref=e115]
            - button "Controle de Validade" [ref=e117] [cursor=pointer]:
              - img [ref=e119]
              - generic [ref=e123]: Controle de Validade
              - img [ref=e124]
            - button "Fiado / Conta Corrente" [ref=e126] [cursor=pointer]:
              - img [ref=e128]
              - generic [ref=e130]: Fiado / Conta Corrente
              - img [ref=e131]
            - button "Relatórios" [ref=e133] [cursor=pointer]:
              - img [ref=e135]
              - generic [ref=e138]: Relatórios
              - img [ref=e139]
            - button "Iniciar Vendas" [ref=e141] [cursor=pointer]:
              - img [ref=e143]
              - generic [ref=e147]: Iniciar Vendas
              - img [ref=e148]
            - button "Novo Pedido" [ref=e150] [cursor=pointer]:
              - img [ref=e152]
              - generic [ref=e155]: Novo Pedido
              - img [ref=e156]
          - generic [ref=e158]:
            - heading "Gestão Avançada" [level=2] [ref=e159]
            - button "Fiscal NFC-e / NF-e" [ref=e160] [cursor=pointer]:
              - img [ref=e162]
              - generic [ref=e165]: Fiscal NFC-e / NF-e
              - img [ref=e166]
            - button "NF-e Modelo 55" [ref=e168] [cursor=pointer]:
              - img [ref=e170]
              - generic [ref=e173]: NF-e Modelo 55
              - img [ref=e174]
            - button "Pagamentos Integrados" [ref=e176] [cursor=pointer]:
              - img [ref=e178]
              - generic [ref=e180]: Pagamentos Integrados
              - img [ref=e181]
            - button "Estoque e Inventário" [ref=e183] [cursor=pointer]:
              - img [ref=e185]
              - generic [ref=e190]: Estoque e Inventário
              - img [ref=e191]
            - button "Abertura e Fechamento" [ref=e193] [cursor=pointer]:
              - img [ref=e195]
              - generic [ref=e197]: Abertura e Fechamento
              - img [ref=e198]
            - button "Compras e Reposição" [ref=e200] [cursor=pointer]:
              - img [ref=e202]
              - generic [ref=e205]: Compras e Reposição
              - img [ref=e206]
            - button "Trocas e Devoluções" [ref=e208] [cursor=pointer]:
              - img [ref=e210]
              - generic [ref=e215]: Trocas e Devoluções
              - img [ref=e216]
          - generic [ref=e218]:
            - heading "Sistema" [level=2] [ref=e219]
            - button "Minha Empresa" [ref=e220] [cursor=pointer]:
              - img [ref=e222]
              - generic [ref=e226]: Minha Empresa
              - img [ref=e227]
            - button "Detalhes da Licença" [ref=e229] [cursor=pointer]:
              - img [ref=e231]
              - generic [ref=e235]: Detalhes da Licença
              - img [ref=e236]
            - button "Sobre PDV" [ref=e238] [cursor=pointer]:
              - img [ref=e240]
              - generic [ref=e242]: Sobre PDV
              - img [ref=e243]
            - button "Aprendizado" [ref=e245] [cursor=pointer]:
              - img [ref=e247]
              - generic [ref=e250]: Aprendizado
              - img [ref=e251]
      - button "Menu de Administrador" [ref=e255] [cursor=pointer]:
        - generic [ref=e256]: A
        - generic [ref=e257]:
          - generic [ref=e258]: Administrador
          - generic [ref=e260]: Administrador
        - img [ref=e261]
    - main [ref=e263]:
      - generic [ref=e264]:
        - generic [ref=e265]:
          - generic [ref=e266]:
            - generic [ref=e267]:
              - heading "Cadastro de Cliente" [level=1] [ref=e268]
              - button "Abrir tour da tela" [ref=e269] [cursor=pointer]:
                - img [ref=e270]
                - generic [ref=e273]: Tour da tela
            - paragraph [ref=e274]: Cadastre clientes e mantenha documentos, contatos e endereços atualizados.
          - button "Novo cliente" [ref=e276] [cursor=pointer]:
            - img [ref=e277]
            - text: Novo cliente
        - generic [ref=e279]:
          - img
          - textbox "Buscar clientes por nome ou documento" [ref=e280]:
            - /placeholder: Pesquise por nome ou CPF
        - generic [ref=e281]:
          - region "Clientes" [ref=e282]:
            - paragraph [ref=e283]: Role para os lados para ver todas as colunas.
            - table [ref=e284]:
              - rowgroup [ref=e285]:
                - row "Selecionar clientes desta página Nome Documento Cidade Telefone / celular E-mail Saldo Devedor Ações" [ref=e286]:
                  - columnheader "Selecionar clientes desta página" [ref=e287]:
                    - checkbox "Selecionar clientes desta página" [ref=e288]
                  - columnheader "Nome" [ref=e289]
                  - columnheader "Documento" [ref=e290]
                  - columnheader "Cidade" [ref=e291]
                  - columnheader "Telefone / celular" [ref=e292]
                  - columnheader "E-mail" [ref=e293]
                  - columnheader "Saldo Devedor" [ref=e294]
                  - columnheader "Ações" [ref=e295]
              - rowgroup [ref=e296]:
                - row "Selecionar undefined - R$ 0,00 Abrir ações" [ref=e297]:
                  - cell "Selecionar undefined" [ref=e298]:
                    - checkbox "Selecionar undefined" [ref=e299]
                  - cell [ref=e300]
                  - cell [ref=e301]
                  - cell [ref=e302]
                  - cell [ref=e303]
                  - cell "-" [ref=e304]
                  - cell "R$ 0,00" [ref=e305]
                  - cell "Abrir ações" [ref=e306]:
                    - button "Abrir ações" [ref=e308] [cursor=pointer]:
                      - img [ref=e309]
          - generic [ref=e314]:
            - paragraph [ref=e315]: Mostrando 1-1 de 1 registros
            - generic [ref=e316]:
              - generic [ref=e317]:
                - text: Linhas por página
                - combobox "Linhas por página" [ref=e318]:
                  - option "10" [selected]
                  - option "20"
                  - option "50"
                  - option "100"
              - navigation "Paginação da lista" [ref=e319]:
                - button "Anterior" [disabled] [ref=e320]
                - button "Página 1" [ref=e321] [cursor=pointer]: "1"
                - button "Próxima" [disabled] [ref=e322]
        - complementary [ref=e324]:
          - generic [ref=e325]:
            - generic [ref=e326]:
              - heading "Novo cliente" [level=3] [ref=e327]
              - paragraph [ref=e328]: Informe nome e telefone ou celular. Os demais dados são opcionais.
            - button "Fechar formulário" [ref=e329] [cursor=pointer]:
              - img [ref=e330]
          - generic [ref=e333]:
            - generic [ref=e334]:
              - heading "Dados pessoais" [level=4] [ref=e335]
              - generic [ref=e336]:
                - generic [ref=e337]:
                  - generic [ref=e338]: Documento (CPF/CNPJ)
                  - textbox "Documento (CPF/CNPJ)" [ref=e339]:
                    - /placeholder: 000.000.000-00 ou 00.000.000/0000-00
                    - text: 111.111.111-11
                - generic [ref=e340]:
                  - generic [ref=e341]: Nome *
                  - textbox "Nome *" [ref=e342]:
                    - /placeholder: Nome
                    - text: Maria Silva
                - generic [ref=e343]:
                  - generic [ref=e344]: Data de nascimento
                  - button "Data de nascimento" [ref=e346] [cursor=pointer]:
                    - generic [ref=e347]: dd/mm/aaaa
                    - img [ref=e348]
                - generic [ref=e350]:
                  - generic [ref=e351]: Idade
                  - textbox "Idade" [disabled] [ref=e352]
            - generic [ref=e353]:
              - heading "Endereço" [level=4] [ref=e354]
              - generic [ref=e355]:
                - generic [ref=e356]:
                  - generic [ref=e357]: CEP
                  - textbox "CEP" [ref=e358]:
                    - /placeholder: 00000-000
                - generic [ref=e359]:
                  - generic [ref=e360]: Cidade
                  - textbox "Cidade" [ref=e361]
                - generic [ref=e362]:
                  - generic [ref=e363]: UF
                  - generic [ref=e364]:
                    - img
                    - combobox "UF" [ref=e365]
                - generic [ref=e366]:
                  - generic [ref=e367]: Endereço
                  - textbox "Endereço" [ref=e368]
                - generic [ref=e369]:
                  - generic [ref=e370]: Bairro
                  - textbox "Bairro" [ref=e371]
                - generic [ref=e372]:
                  - generic [ref=e373]: Complemento
                  - textbox "Complemento" [ref=e374]
                - generic [ref=e375]:
                  - generic [ref=e376]: Número
                  - textbox "Número" [ref=e377]
                - generic [ref=e378]:
                  - generic [ref=e379]: Referência
                  - textbox "Referência" [ref=e380]
            - generic [ref=e381]:
              - heading "Contato" [level=4] [ref=e382]
              - paragraph [ref=e383]: Informe um telefone ou celular com DDD. Basta preencher um deles.
              - generic [ref=e384]:
                - generic [ref=e385]:
                  - generic [ref=e386]: Telefone *
                  - textbox "Telefone *" [ref=e387]:
                    - /placeholder: (00) 0000-0000
                    - text: (11) 3333-4444
                - generic [ref=e388]:
                  - generic [ref=e389]: Celular
                  - textbox "Celular" [ref=e390]:
                    - /placeholder: (00) 00000-0000
                - generic [ref=e391]:
                  - generic [ref=e392]: E-mail
                  - textbox "E-mail" [ref=e393]:
                    - /placeholder: email@dominio.com
            - generic [ref=e394]:
              - heading "Conta Corrente / Fiado" [level=4] [ref=e395]
              - generic [ref=e396]:
                - generic [ref=e397]:
                  - generic [ref=e398]: Limite de Crédito (R$)
                  - spinbutton "Limite de Crédito (R$) 0 significa sem limite pré-estabelecido." [ref=e399]: "0"
                  - generic [ref=e400]: 0 significa sem limite pré-estabelecido.
                - generic [ref=e401]:
                  - generic [ref=e402]: Saldo Devedor Atual
                  - generic [ref=e403]: R$ 0,00
                  - generic [ref=e404]: Atualizado automaticamente através das vendas a prazo e quitações.
          - generic [ref=e406]:
            - button "Cancelar" [ref=e407] [cursor=pointer]
            - button "Criar cliente" [active] [ref=e408] [cursor=pointer]
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
> 82  |     expect(writes).toHaveLength(0);
      |                    ^ Error: expect(received).toHaveLength(expected)
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
  99  |     expect(customers).toHaveLength(2);
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