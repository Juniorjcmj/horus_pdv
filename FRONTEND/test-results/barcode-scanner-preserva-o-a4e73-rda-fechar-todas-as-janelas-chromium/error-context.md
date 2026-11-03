# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: barcode-scanner.spec.ts >> preserva o campo de cliente e aguarda fechar todas as janelas
- Location: tests\architecture\barcode-scanner.spec.ts:158:1

# Error details

```
Error: expect(locator).toHaveValue(expected) failed

Locator:  getByPlaceholder('Pesquise por nome, CPF ou celular...')
Expected: "Maria"
Received: "Maria7890000000002"
Timeout:  10000ms

Call log:
  - Expect "toHaveValue" with timeout 10000ms
  - waiting for getByPlaceholder('Pesquise por nome, CPF ou celular...')
    13 × locator resolved to <input value="Maria7890000000002" class="input-field w-full pl-9 text-xs" placeholder="Pesquise por nome, CPF ou celular..."/>
       - unexpected value "Maria7890000000002"

```

# Page snapshot

```yaml
- generic [ref=e4]:
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
            - paragraph [ref=e44]: 23:20:51
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
          - generic [ref=e66]: "0001"
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
          - heading "Leite scanner" [level=2] [ref=e92]
        - generic [ref=e93]:
          - paragraph [ref=e94]: "Lista de itens:"
          - table [ref=e96]:
            - rowgroup [ref=e97]:
              - row "# Código Produto Qtd Vl. Unit Vl. Total" [ref=e98]:
                - columnheader "#" [ref=e99]
                - columnheader "Código" [ref=e100]
                - columnheader "Produto" [ref=e101]
                - columnheader "Qtd" [ref=e102]
                - columnheader "Vl. Unit" [ref=e103]
                - columnheader "Vl. Total" [ref=e104]
                - columnheader [ref=e105]
            - rowgroup [ref=e106]:
              - row "1 7890000000001 Leite scanner 1 Alterar preço de Leite scanner 10,00 10,00" [ref=e107]:
                - cell "1" [ref=e108]
                - cell "7890000000001" [ref=e109]
                - cell "Leite scanner" [ref=e110]
                - cell "1" [ref=e111]
                - cell "Alterar preço de Leite scanner 10,00" [ref=e112]:
                  - generic [ref=e113]:
                    - button "Alterar preço de Leite scanner" [ref=e114] [cursor=pointer]:
                      - img [ref=e115]
                    - generic [ref=e118]: 10,00
                - cell "10,00" [ref=e119]
                - cell [ref=e120]:
                  - button [ref=e121] [cursor=pointer]:
                    - img [ref=e122]
        - generic [ref=e125]:
          - generic [ref=e126]: 00 - Ajuda
          - generic [ref=e127]:
            - generic [ref=e128]: SUB TOTAL
            - generic [ref=e129]: R$ 10,00
          - generic [ref=e130]:
            - button "✖ CANCELAR (F8)" [ref=e131] [cursor=pointer]
            - button "Imprimir última venda" [ref=e132] [cursor=pointer]:
              - img [ref=e133]
              - text: Imprimir última venda
            - button "PAGAMENTO (F12)" [ref=e137] [cursor=pointer]
          - generic [ref=e138]:
            - paragraph [ref=e139]: "Usuário: Gerente"
            - paragraph [ref=e140]: "Estabelecimento: Quack PDV"
            - paragraph [ref=e141]: "Prévia impressão: Sim • Caixa: PDV01 aberto"
  - generic [ref=e143]:
    - generic [ref=e144]:
      - heading "Pagamento" [level=2] [ref=e145]
      - button [ref=e146] [cursor=pointer]:
        - img [ref=e147]
    - generic [ref=e150]:
      - button "Nota fiscal (NF-e)" [ref=e151] [cursor=pointer]:
        - img [ref=e152]
        - text: Nota fiscal (NF-e)
      - generic [ref=e155]:
        - generic [ref=e157]:
          - img [ref=e158]
          - generic [ref=e163]: Identificação do Cliente
        - generic [ref=e164]:
          - textbox "CPF na nota (opcional, ou busque cliente ao lado)" [ref=e165]
          - button "Buscar" [ref=e166] [cursor=pointer]:
            - img [ref=e167]
            - text: Buscar
          - button "Cadastrar" [ref=e170] [cursor=pointer]:
            - img [ref=e171]
            - text: Cadastrar
          - button "Sincronizar" [ref=e174] [cursor=pointer]:
            - img [ref=e175]
            - text: Sincronizar
      - generic [ref=e180]:
        - generic [ref=e181]:
          - img [ref=e182]
          - generic [ref=e184]: Venda a prazo (Fiado) exige seleção de cliente cadastrado.
        - generic [ref=e185]:
          - button "Selecionar" [ref=e186] [cursor=pointer]
          - button "+ Cadastrar" [ref=e187] [cursor=pointer]
      - generic [ref=e188]:
        - generic [ref=e189]:
          - generic [ref=e190]: Total da Venda
          - generic [ref=e191]: R$ 10,00
        - generic [ref=e192]:
          - generic [ref=e193]: Total Informado
          - generic [ref=e194]: R$ 10,00
        - generic [ref=e195]:
          - generic [ref=e196]: Saldo Restante
          - generic [ref=e197]: R$ 0,00
        - generic [ref=e198]:
          - generic [ref=e199]: Troco Total
          - generic [ref=e200]: R$ 0,00
      - generic [ref=e201]:
        - generic [ref=e202]:
          - generic [ref=e203]: Forma de pagamento
          - generic [ref=e204]: ✓ Saldo quitado
        - generic [ref=e205]:
          - generic [ref=e206]: Forma
          - radiogroup "Forma de pagamento" [ref=e207]:
            - radio "Dinheiro Alt+1" [ref=e208] [cursor=pointer]:
              - generic [ref=e209]: Dinheiro
              - generic [ref=e210]: Alt+1
            - radio "PIX Alt+2" [ref=e211] [cursor=pointer]:
              - generic [ref=e212]: PIX
              - generic [ref=e213]: Alt+2
            - radio "Débito Alt+3" [ref=e214] [cursor=pointer]:
              - generic [ref=e215]: Débito
              - generic [ref=e216]: Alt+3
            - radio "Crédito Alt+4" [ref=e217] [cursor=pointer]:
              - generic [ref=e218]: Crédito
              - generic [ref=e219]: Alt+4
            - radio "Fiado Alt+5" [checked] [ref=e220] [cursor=pointer]:
              - generic [ref=e221]: Fiado
              - generic [ref=e222]: Alt+5
        - generic [ref=e224]:
          - generic [ref=e225]: Valor a pagar (R$)
          - textbox "Valor a pagar (R$)" [ref=e226]:
            - /placeholder: 0,00
            - text: 10,00
        - button "Adicionar à lista de parcelas" [ref=e227] [cursor=pointer]:
          - img [ref=e228]
          - text: Adicionar à lista de parcelas
    - generic [ref=e229]:
      - button "Voltar" [ref=e230] [cursor=pointer]
      - button "Confirmar Venda" [disabled] [ref=e231]
  - complementary [ref=e233]:
    - generic [ref=e234]:
      - generic [ref=e235]:
        - img [ref=e236]
        - heading "Selecionar Cliente para Fiado" [level=3] [ref=e241]
      - generic [ref=e242]:
        - button "Sincronizar Nuvem" [ref=e243] [cursor=pointer]:
          - img [ref=e244]
          - text: Sincronizar Nuvem
        - button "+ Novo Cliente" [ref=e249] [cursor=pointer]:
          - img [ref=e250]
          - text: + Novo Cliente
        - button "Fechar seleção de cliente" [ref=e253] [cursor=pointer]:
          - img [ref=e254]
    - generic [ref=e258]:
      - img
      - textbox "Pesquise por nome, CPF ou celular..." [active] [ref=e259]: Maria7890000000002
    - generic [ref=e261]:
      - img [ref=e262]
      - generic [ref=e267]:
        - paragraph [ref=e268]: Nenhum cliente encontrado
        - paragraph [ref=e269]: Nenhum cliente com "Maria7890000000002" foi localizado.
      - button "Cadastrar \"Maria7890000000002\"" [ref=e270] [cursor=pointer]:
        - img [ref=e271]
        - text: Cadastrar "Maria7890000000002"
```

# Test source

```ts
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
  93  |   await expect(page.getByText("Produto não encontrado.", { exact: true })).toBeVisible();
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
> 167 |   await expect(customer).toHaveValue("Maria");
      |                          ^ Error: expect(locator).toHaveValue(expected) failed
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
  194 | });
  195 | 
```