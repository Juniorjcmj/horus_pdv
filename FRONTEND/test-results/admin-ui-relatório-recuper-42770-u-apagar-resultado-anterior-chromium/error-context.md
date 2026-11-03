# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: admin-ui.spec.ts >> relatório recupera erro sem travar o botão ou apagar resultado anterior
- Location: tests\architecture\admin-ui.spec.ts:178:1

# Error details

```
Error: expect(locator).toContainText(expected) failed

Locator: getByRole('alert')
Expected substring: "Não foi possível gerar o relatório"
Received string:    "Indisponível"
Timeout: 10000ms

Call log:
  - Expect "toContainText" with timeout 10000ms
  - waiting for getByRole('alert')
    13 × locator resolved to <p role="alert" class="mt-3 text-sm text-danger">Indisponível</p>
       - unexpected value "Indisponível"

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
          - heading "Administração Master" [level=2] [ref=e34]
          - button "Gerenciamento Geral" [ref=e35] [cursor=pointer]:
            - img [ref=e37]
            - generic [ref=e40]: Gerenciamento Geral
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
    - generic [ref=e264]:
      - generic [ref=e266]:
        - generic [ref=e267]:
          - button "Voltar para relatórios" [ref=e268] [cursor=pointer]:
            - img [ref=e269]
            - text: Voltar para relatórios
          - heading "Vendas por Período" [level=2] [ref=e271]
          - paragraph [ref=e272]: Consolida faturamento, ticket médio e quantidade de vendas no período.
        - generic [ref=e273]: Filtros do relatório
      - generic [ref=e274]:
        - generic [ref=e275]:
          - generic [ref=e276]:
            - text: Data inicial
            - button "Data inicial" [ref=e278] [cursor=pointer]:
              - generic [ref=e279]: 20/09/2026
              - img [ref=e280]
          - generic [ref=e282]:
            - text: Data final
            - button "Data final" [ref=e284] [cursor=pointer]:
              - generic [ref=e285]: 20/10/2026
              - img [ref=e286]
          - generic [ref=e288]:
            - generic [ref=e289]: Departamento
            - generic [ref=e290]:
              - img
              - combobox "Departamento" [ref=e291]: Todos os departamentos
          - generic [ref=e292]:
            - generic [ref=e293]: Agrupar por
            - generic [ref=e294]:
              - img
              - combobox "Agrupar por" [ref=e295]: Diário
          - generic [ref=e296]:
            - generic [ref=e297]: Forma de pagamento
            - generic [ref=e298]:
              - img
              - combobox "Forma de pagamento" [ref=e299]: Todos
        - generic [ref=e300]:
          - button "Gerar relatório" [ref=e301] [cursor=pointer]
          - button "Limpar filtros" [ref=e302] [cursor=pointer]
        - alert [ref=e303]: Indisponível
```

# Test source

```ts
  91  |       ? route.fulfill({ status: 503, json: { success: false, message: "Indisponível" } })
  92  |       : route.fulfill({ json: { success: true, data: [] } }));
  93  |     await page.goto("/");
  94  |     await expect(page.getByRole("alert")).toContainText("Não foi possível carregar");
  95  |     fail = false;
  96  |     await page.getByRole("button", { name: "Tentar novamente", exact: true }).click();
  97  |     const empty = page.getByText(`Nenhum ${noun} cadastrado`, { exact: true });
  98  |     await expect(empty).toBeVisible();
  99  |     const rect = await empty.boundingBox();
  100 |     expect(rect!.x).toBeGreaterThanOrEqual(0);
  101 |     expect(rect!.x + rect!.width).toBeLessThanOrEqual(375);
  102 |   });
  103 | }
  104 | 
  105 | test("seleciona e limpa fornecedor usando teclado no cadastro de produto", async ({ page, baseURL }) => {
  106 |   await setup(page, baseURL!, "cadastro-produto");
  107 |   await page.route("**/api/Fornecedor", route => route.fulfill({ json: { success: true, data: [
  108 |     { id: "f-1", fantasyName: "Atacado Norte", companyName: "Atacado Norte", cnpj: "", city: "", cellphone: "", telephone: "" },
  109 |     { id: "f-2", fantasyName: "Atacado Sul", companyName: "Atacado Sul", cnpj: "", city: "", cellphone: "", telephone: "" },
  110 |   ] } }));
  111 |   await page.goto("/");
  112 |   await page.getByRole("button", { name: "Novo produto", exact: true }).click();
  113 |   const field = page.getByRole("combobox", { name: "Fornecedor (opcional)", exact: true });
  114 |   await field.fill("Sul");
  115 |   await field.press("ArrowDown");
  116 |   await expect(field).toHaveAttribute("aria-activedescendant", /options-0$/);
  117 |   await field.press("Enter");
  118 |   await expect(field).toHaveValue("Atacado Sul");
  119 |   await field.fill("");
  120 |   await field.press("Escape");
  121 |   await expect(field).toHaveValue("");
  122 |   await expect(field).toHaveAttribute("aria-expanded", "false");
  123 | });
  124 | 
  125 | test("cadastra fornecedor pela opção de criação usando Enter", async ({ page, baseURL }) => {
  126 |   await setup(page, baseURL!, "cadastro-produto");
  127 |   await page.goto("/");
  128 |   await page.getByRole("button", { name: "Novo produto", exact: true }).click();
  129 |   const field = page.getByRole("combobox", { name: "Fornecedor (opcional)", exact: true });
  130 |   await field.fill("Fornecedor novo");
  131 |   await field.press("Enter");
  132 |   await expect(page.getByRole("heading", { name: "Cadastrar fornecedor", exact: true })).toBeVisible();
  133 |   await expect(page.getByRole("textbox", { name: "Razão social *", exact: true })).toHaveValue("Fornecedor novo");
  134 | });
  135 | 
  136 | test("menu da conta recebe foco e devolve ao gatilho com Escape", async ({ page, baseURL }) => {
  137 |   await setup(page, baseURL!);
  138 |   await page.goto("/");
  139 |   const trigger = page.getByRole("button", { name: "Menu de Administrador", exact: true });
  140 |   await trigger.click();
  141 |   await expect(page.getByRole("menuitem", { name: "Meu Perfil" })).toBeFocused();
  142 |   await page.keyboard.press("ArrowDown");
  143 |   await expect(page.getByRole("menuitem", { name: "Configurações" })).toBeFocused();
  144 |   await page.keyboard.press("Escape");
  145 |   await expect(trigger).toBeFocused();
  146 |   await expect(trigger).toHaveAttribute("aria-expanded", "false");
  147 | });
  148 | 
  149 | test("menu de ações da tabela funciona com setas e Escape", async ({ page, baseURL }) => {
  150 |   await setup(page, baseURL!, "cadastro-cliente");
  151 |   await page.route("**/api/Cliente", route => route.fulfill({ json: { success: true, data: [
  152 |     { id: "c-1", customerName: "Cliente de teste", document: "", city: "", cellphone: "", email: "", saldoDevedor: 0 },
  153 |   ] } }));
  154 |   await page.goto("/");
  155 |   const trigger = page.getByRole("button", { name: "Abrir ações", exact: true });
  156 |   await trigger.click();
  157 |   await expect(page.getByRole("menuitem", { name: "Editar", exact: true })).toBeFocused();
  158 |   await page.keyboard.press("ArrowDown");
  159 |   await expect(page.getByRole("menuitem", { name: "Excluir", exact: true })).toBeFocused();
  160 |   await page.keyboard.press("Escape");
  161 |   await expect(trigger).toBeFocused();
  162 | });
  163 | 
  164 | test("calendário anuncia mês e ano e devolve foco ao fechar", async ({ page, baseURL }) => {
  165 |   await setup(page, baseURL!, "cadastro-cliente");
  166 |   await page.goto("/");
  167 |   await page.getByRole("button", { name: "Novo cliente", exact: true }).click();
  168 |   const trigger = page.locator('button[aria-haspopup="dialog"]').first();
  169 |   await trigger.click();
  170 |   const dialog = page.getByRole("dialog", { name: "Escolher data", exact: true });
  171 |   await expect(dialog).toBeVisible();
  172 |   await expect(dialog.getByRole("combobox", { name: "Mês", exact: true })).toBeVisible();
  173 |   await expect(dialog.getByRole("combobox", { name: "Ano", exact: true })).toBeVisible();
  174 |   await page.keyboard.press("Escape");
  175 |   await expect(trigger).toBeFocused();
  176 | });
  177 | 
  178 | test("relatório recupera erro sem travar o botão ou apagar resultado anterior", async ({ page, baseURL }) => {
  179 |   await setup(page, baseURL!, "relatorios");
  180 |   let fail = false;
  181 |   await page.route("**/api/Relatorio/Gerar", route => fail
  182 |     ? route.fulfill({ status: 503, json: { success: false, message: "Indisponível" } })
  183 |     : route.fulfill({ json: { success: true, data: { columns: [{ key: "teste", label: "Descrição" }], rows: [{ teste: "Resultado preservado" }] } } }));
  184 |   await page.goto("/");
  185 |   await page.getByRole("button", { name: /^Vendas por Período/ }).click();
  186 |   const generate = page.getByRole("button", { name: "Gerar relatório", exact: true });
  187 |   await generate.click();
  188 |   await expect(page.getByText("Resultado preservado", { exact: true })).toBeVisible();
  189 |   fail = true;
  190 |   await generate.click();
> 191 |   await expect(page.getByRole("alert")).toContainText("Não foi possível gerar o relatório");
      |                                         ^ Error: expect(locator).toContainText(expected) failed
  192 |   await expect(generate).toBeEnabled();
  193 |   await expect(page.getByText("Resultado preservado", { exact: true })).toBeVisible();
  194 |   fail = false;
  195 |   await generate.click();
  196 |   await expect(page.getByRole("alert")).toHaveCount(0);
  197 | });
  198 | 
  199 | test("paginação usa cores de ação e indica a página no tema escuro", async ({ page, baseURL }) => {
  200 |   await setup(page, baseURL!, "cadastro-cliente", "administrador", "dark");
  201 |   await page.goto("/");
  202 |   const current = page.getByRole("button", { name: "Página 1", exact: true });
  203 |   await expect(current).toHaveAttribute("aria-current", "page");
  204 |   const style = await current.evaluate(element => ({ background: getComputedStyle(element).backgroundColor, color: getComputedStyle(element).color }));
  205 |   expect(style.background).toBe("rgb(30, 58, 95)");
  206 |   expect(style.color).toBe("rgb(255, 255, 255)");
  207 |   expect((await current.boundingBox())!.height).toBeGreaterThanOrEqual(44);
  208 | });
  209 | 
  210 | test("título longo cabe em 320 px e a tabela permite rolagem por teclado", async ({ page, baseURL }) => {
  211 |   await page.setViewportSize({ width: 320, height: 812 });
  212 |   await setup(page, baseURL!, "cadastro-fornecedor");
  213 |   await page.goto("/");
  214 |   const heading = page.locator("main h1");
  215 |   await expect(heading).toBeVisible();
  216 |   const rect = await heading.boundingBox();
  217 |   expect(rect!.x + rect!.width).toBeLessThanOrEqual(320);
  218 |   const table = page.getByRole("region", { name: "Fornecedores", exact: true });
  219 |   await table.focus();
  220 |   await page.keyboard.press("ArrowRight");
  221 |   await expect.poll(() => table.evaluate(element => element.scrollLeft)).toBeGreaterThan(0);
  222 | });
  223 | 
  224 | test("preserva restrição fiscal para gerente sem perfil financeiro", async ({ page, baseURL }) => {
  225 |   await setup(page, baseURL!, "home", "gerente");
  226 |   await page.goto("/");
  227 |   await expect(page.getByRole("heading", { name: "Visão geral", exact: true })).toBeVisible();
  228 |   await expect(page.getByRole("button", { name: /Fiscal NFC-e/ })).toHaveCount(0);
  229 |   await expect(page.locator("#admin-sidebar").getByRole("button", { name: "Produto", exact: true })).toBeVisible();
  230 | });
  231 | 
```