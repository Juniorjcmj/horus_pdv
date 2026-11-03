# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: customer-registration.spec.ts >> Cliente com nome e telefone obrigatórios >> bloqueia quando falta Telefone *
- Location: tests\architecture\customer-registration.spec.ts:65:5

# Error details

```
Error: expect(received).toHaveLength(expected)

Expected length: 0
Received length: 1
Received array:  [[]]
```

# Page snapshot

```yaml
- generic [ref=e3]:
  - link "Ir para o conteúdo" [ref=e4] [cursor=pointer]:
    - /url: "#admin-content"
  - generic [ref=e6]:
    - img [ref=e8]
    - generic [ref=e10]: Online
  - complementary "Menu principal" [ref=e11]:
    - generic [ref=e12]:
      - generic [ref=e13]:
        - generic [ref=e14]:
          - img "Quack Sistemas" [ref=e15]
          - generic [ref=e16]:
            - heading "Quack Sistemas" [level=1] [ref=e17]
            - paragraph [ref=e18]: Soluções para seu negócio
        - button "Recolher menu lateral" [ref=e20] [cursor=pointer]:
          - img [ref=e21]
      - navigation "Navegação principal" [ref=e22]:
        - generic [ref=e23]:
          - heading "Principal" [level=2] [ref=e24]
          - button "Visão geral" [ref=e25] [cursor=pointer]:
            - img [ref=e27]
            - generic [ref=e30]: Visão geral
            - img [ref=e31]
        - generic [ref=e33]:
          - heading "Cadastros" [level=2] [ref=e34]
          - button "Cliente" [ref=e35] [cursor=pointer]:
            - img [ref=e37]
            - generic [ref=e40]: Cliente
            - img [ref=e41]
          - button "Fornecedor" [ref=e43] [cursor=pointer]:
            - img [ref=e45]
            - generic [ref=e50]: Fornecedor
            - img [ref=e51]
          - button "Produto" [ref=e53] [cursor=pointer]:
            - img [ref=e55]
            - generic [ref=e59]: Produto
            - img [ref=e60]
          - button "Tributação dos produtos" [ref=e62] [cursor=pointer]:
            - img [ref=e64]
            - generic [ref=e67]: Tributação dos produtos
            - img [ref=e68]
          - button "Contas de Usuários" [ref=e70] [cursor=pointer]:
            - img [ref=e72]
            - generic [ref=e84]: Contas de Usuários
            - img [ref=e85]
        - generic [ref=e87]:
          - heading "Operação" [level=2] [ref=e88]
          - button "Histórico de Vendas" [ref=e89] [cursor=pointer]:
            - img [ref=e91]
            - generic [ref=e95]: Histórico de Vendas
            - img [ref=e96]
          - button "Promoções" [ref=e98] [cursor=pointer]:
            - img [ref=e100]
            - generic [ref=e103]: Promoções
            - img [ref=e104]
          - button "Controle de Validade" [ref=e106] [cursor=pointer]:
            - img [ref=e108]
            - generic [ref=e112]: Controle de Validade
            - img [ref=e113]
          - button "Fiado / Conta Corrente" [ref=e115] [cursor=pointer]:
            - img [ref=e117]
            - generic [ref=e119]: Fiado / Conta Corrente
            - img [ref=e120]
          - button "Relatórios" [ref=e122] [cursor=pointer]:
            - img [ref=e124]
            - generic [ref=e127]: Relatórios
            - img [ref=e128]
          - button "Iniciar Vendas" [ref=e130] [cursor=pointer]:
            - img [ref=e132]
            - generic [ref=e136]: Iniciar Vendas
            - img [ref=e137]
          - button "Novo Pedido" [ref=e139] [cursor=pointer]:
            - img [ref=e141]
            - generic [ref=e144]: Novo Pedido
            - img [ref=e145]
        - generic [ref=e147]:
          - heading "Gestão Avançada" [level=2] [ref=e148]
          - button "Fiscal NFC-e / NF-e" [ref=e149] [cursor=pointer]:
            - img [ref=e151]
            - generic [ref=e154]: Fiscal NFC-e / NF-e
            - img [ref=e155]
          - button "NF-e Modelo 55" [ref=e157] [cursor=pointer]:
            - img [ref=e159]
            - generic [ref=e162]: NF-e Modelo 55
            - img [ref=e163]
          - button "Pagamentos Integrados" [ref=e165] [cursor=pointer]:
            - img [ref=e167]
            - generic [ref=e169]: Pagamentos Integrados
            - img [ref=e170]
          - button "Estoque e Inventário" [ref=e172] [cursor=pointer]:
            - img [ref=e174]
            - generic [ref=e179]: Estoque e Inventário
            - img [ref=e180]
          - button "Abertura e Fechamento" [ref=e182] [cursor=pointer]:
            - img [ref=e184]
            - generic [ref=e186]: Abertura e Fechamento
            - img [ref=e187]
          - button "Compras e Reposição" [ref=e189] [cursor=pointer]:
            - img [ref=e191]
            - generic [ref=e194]: Compras e Reposição
            - img [ref=e195]
          - button "Trocas e Devoluções" [ref=e197] [cursor=pointer]:
            - img [ref=e199]
            - generic [ref=e204]: Trocas e Devoluções
            - img [ref=e205]
        - generic [ref=e207]:
          - heading "Sistema" [level=2] [ref=e208]
          - button "Minha Empresa" [ref=e209] [cursor=pointer]:
            - img [ref=e211]
            - generic [ref=e215]: Minha Empresa
            - img [ref=e216]
          - button "Detalhes da Licença" [ref=e218] [cursor=pointer]:
            - img [ref=e220]
            - generic [ref=e224]: Detalhes da Licença
            - img [ref=e225]
          - button "Sobre PDV" [ref=e227] [cursor=pointer]:
            - img [ref=e229]
            - generic [ref=e231]: Sobre PDV
            - img [ref=e232]
          - button "Aprendizado" [ref=e234] [cursor=pointer]:
            - img [ref=e236]
            - generic [ref=e239]: Aprendizado
            - img [ref=e240]
    - button "Menu de Administrador" [ref=e244] [cursor=pointer]:
      - generic [ref=e245]: A
      - generic [ref=e246]:
        - generic [ref=e247]: Administrador
        - generic [ref=e249]: Administrador
      - img [ref=e250]
  - main [ref=e252]:
    - generic [ref=e253]:
      - generic [ref=e254]:
        - generic [ref=e255]:
          - generic [ref=e256]:
            - heading "Cadastro de Cliente" [level=1] [ref=e257]
            - button "Abrir tour da tela" [ref=e258] [cursor=pointer]:
              - img [ref=e259]
              - generic [ref=e262]: Tour da tela
          - paragraph [ref=e263]: Cadastre clientes e mantenha documentos, contatos e endereços atualizados.
        - button "Novo cliente" [ref=e265] [cursor=pointer]:
          - img [ref=e266]
          - text: Novo cliente
      - generic [ref=e268]:
        - img
        - textbox "Buscar clientes por nome ou documento" [ref=e269]:
          - /placeholder: Pesquise por nome ou CPF
      - generic [ref=e270]:
        - region "Clientes" [ref=e271]:
          - paragraph [ref=e272]: Role para os lados para ver todas as colunas.
          - table [ref=e273]:
            - rowgroup [ref=e274]:
              - row "Selecionar clientes desta página Nome Documento Cidade Telefone / celular E-mail Saldo Devedor Ações" [ref=e275]:
                - columnheader "Selecionar clientes desta página" [ref=e276]:
                  - checkbox "Selecionar clientes desta página" [ref=e277]
                - columnheader "Nome" [ref=e278]
                - columnheader "Documento" [ref=e279]
                - columnheader "Cidade" [ref=e280]
                - columnheader "Telefone / celular" [ref=e281]
                - columnheader "E-mail" [ref=e282]
                - columnheader "Saldo Devedor" [ref=e283]
                - columnheader "Ações" [ref=e284]
            - rowgroup [ref=e285]:
              - row "Selecionar undefined - R$ 0,00 Abrir ações" [ref=e286]:
                - cell "Selecionar undefined" [ref=e287]:
                  - checkbox "Selecionar undefined" [ref=e288]
                - cell [ref=e289]
                - cell [ref=e290]
                - cell [ref=e291]
                - cell [ref=e292]
                - cell "-" [ref=e293]
                - cell "R$ 0,00" [ref=e294]
                - cell "Abrir ações" [ref=e295]:
                  - button "Abrir ações" [ref=e297] [cursor=pointer]:
                    - img [ref=e298]
        - generic [ref=e303]:
          - paragraph [ref=e304]: Mostrando 1-1 de 1 registros
          - generic [ref=e305]:
            - generic [ref=e306]:
              - text: Linhas por página
              - combobox "Linhas por página" [ref=e307]:
                - option "10" [selected]
                - option "20"
                - option "50"
                - option "100"
            - navigation "Paginação da lista" [ref=e308]:
              - button "Anterior" [disabled] [ref=e309]
              - button "Página 1" [ref=e310] [cursor=pointer]: "1"
              - button "Próxima" [disabled] [ref=e311]
      - complementary [ref=e313]:
        - generic [ref=e314]:
          - generic [ref=e315]:
            - heading "Novo cliente" [level=3] [ref=e316]
            - paragraph [ref=e317]: Informe nome e telefone ou celular. Os demais dados são opcionais.
          - button "Fechar formulário" [ref=e318] [cursor=pointer]:
            - img [ref=e319]
        - generic [ref=e322]:
          - generic [ref=e323]:
            - heading "Dados pessoais" [level=4] [ref=e324]
            - generic [ref=e325]:
              - generic [ref=e326]:
                - generic [ref=e327]: Documento (CPF/CNPJ)
                - textbox "Documento (CPF/CNPJ)" [ref=e328]:
                  - /placeholder: 000.000.000-00 ou 00.000.000/0000-00
              - generic [ref=e329]:
                - generic [ref=e330]: Nome *
                - textbox "Nome *" [ref=e331]:
                  - /placeholder: Nome
                  - text: Maria Silva
              - generic [ref=e332]:
                - generic [ref=e333]: Data de nascimento
                - button "Data de nascimento" [ref=e335] [cursor=pointer]:
                  - generic [ref=e336]: dd/mm/aaaa
                  - img [ref=e337]
              - generic [ref=e339]:
                - generic [ref=e340]: Idade
                - textbox "Idade" [disabled] [ref=e341]
          - generic [ref=e342]:
            - heading "Endereço" [level=4] [ref=e343]
            - generic [ref=e344]:
              - generic [ref=e345]:
                - generic [ref=e346]: CEP
                - textbox "CEP" [ref=e347]:
                  - /placeholder: 00000-000
              - generic [ref=e348]:
                - generic [ref=e349]: Cidade
                - textbox "Cidade" [ref=e350]
              - generic [ref=e351]:
                - generic [ref=e352]: UF
                - generic [ref=e353]:
                  - img
                  - combobox "UF" [ref=e354]
              - generic [ref=e355]:
                - generic [ref=e356]: Endereço
                - textbox "Endereço" [ref=e357]
              - generic [ref=e358]:
                - generic [ref=e359]: Bairro
                - textbox "Bairro" [ref=e360]
              - generic [ref=e361]:
                - generic [ref=e362]: Complemento
                - textbox "Complemento" [ref=e363]
              - generic [ref=e364]:
                - generic [ref=e365]: Número
                - textbox "Número" [ref=e366]
              - generic [ref=e367]:
                - generic [ref=e368]: Referência
                - textbox "Referência" [ref=e369]
          - generic [ref=e370]:
            - heading "Contato" [level=4] [ref=e371]
            - paragraph [ref=e372]: Informe um telefone ou celular com DDD. Basta preencher um deles.
            - generic [ref=e373]:
              - generic [ref=e374]:
                - generic [ref=e375]: Telefone *
                - textbox "Telefone *" [ref=e376]:
                  - /placeholder: (00) 0000-0000
              - generic [ref=e377]:
                - generic [ref=e378]: Celular
                - textbox "Celular" [ref=e379]:
                  - /placeholder: (00) 00000-0000
              - generic [ref=e380]:
                - generic [ref=e381]: E-mail
                - textbox "E-mail" [ref=e382]:
                  - /placeholder: email@dominio.com
          - generic [ref=e383]:
            - heading "Conta Corrente / Fiado" [level=4] [ref=e384]
            - generic [ref=e385]:
              - generic [ref=e386]:
                - generic [ref=e387]: Limite de Crédito (R$)
                - spinbutton "Limite de Crédito (R$) 0 significa sem limite pré-estabelecido." [ref=e388]: "0"
                - generic [ref=e389]: 0 significa sem limite pré-estabelecido.
              - generic [ref=e390]:
                - generic [ref=e391]: Saldo Devedor Atual
                - generic [ref=e392]: R$ 0,00
                - generic [ref=e393]: Atualizado automaticamente através das vendas a prazo e quitações.
        - generic [ref=e395]:
          - button "Cancelar" [ref=e396] [cursor=pointer]
          - button "Criar cliente" [active] [ref=e397] [cursor=pointer]
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
> 70  |       expect(writes).toHaveLength(0);
      |                      ^ Error: expect(received).toHaveLength(expected)
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