# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: desktop-gateway.spec.ts >> Gateway embutido no programa desktop >> descoberta pela nuvem não troca o Gateway embutido por outro endereço
- Location: tests\architecture\desktop-gateway.spec.ts:172:3

# Error details

```
Error: expect(received).toBe(expected) // Object.is equality

Expected: "http://192.168.0.50:5080"
Received: "http://127.0.0.1:5080"
```

# Page snapshot

```yaml
- generic [ref=e3]:
  - link "Ir para o conteúdo" [ref=e4] [cursor=pointer]:
    - /url: "#admin-content"
  - generic [ref=e6]:
    - img [ref=e8]
    - generic [ref=e10]: Sincronizando...
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
          - button "Pagamentos Integrados" [ref=e149] [cursor=pointer]:
            - img [ref=e151]
            - generic [ref=e153]: Pagamentos Integrados
            - img [ref=e154]
          - button "Estoque e Inventário" [ref=e156] [cursor=pointer]:
            - img [ref=e158]
            - generic [ref=e163]: Estoque e Inventário
            - img [ref=e164]
          - button "Abertura e Fechamento" [ref=e166] [cursor=pointer]:
            - img [ref=e168]
            - generic [ref=e170]: Abertura e Fechamento
            - img [ref=e171]
          - button "Compras e Reposição" [ref=e173] [cursor=pointer]:
            - img [ref=e175]
            - generic [ref=e178]: Compras e Reposição
            - img [ref=e179]
          - button "Trocas e Devoluções" [ref=e181] [cursor=pointer]:
            - img [ref=e183]
            - generic [ref=e188]: Trocas e Devoluções
            - img [ref=e189]
        - generic [ref=e191]:
          - heading "Sistema" [level=2] [ref=e192]
          - button "Minha Empresa" [ref=e193] [cursor=pointer]:
            - img [ref=e195]
            - generic [ref=e199]: Minha Empresa
            - img [ref=e200]
          - button "Detalhes da Licença" [ref=e202] [cursor=pointer]:
            - img [ref=e204]
            - generic [ref=e208]: Detalhes da Licença
            - img [ref=e209]
          - button "Sobre PDV" [ref=e211] [cursor=pointer]:
            - img [ref=e213]
            - generic [ref=e215]: Sobre PDV
            - img [ref=e216]
          - button "Aprendizado" [ref=e218] [cursor=pointer]:
            - img [ref=e220]
            - generic [ref=e223]: Aprendizado
            - img [ref=e224]
    - button "Menu de Gerente" [ref=e228] [cursor=pointer]:
      - generic [ref=e229]: G
      - generic [ref=e230]:
        - generic [ref=e231]: Gerente
        - generic [ref=e233]: Gerente
      - img [ref=e234]
  - main [ref=e236]:
    - generic [ref=e237]:
      - generic [ref=e238]:
        - generic [ref=e239]:
          - generic [ref=e240]:
            - heading "Visão geral" [level=1] [ref=e241]
            - button "Abrir tour da tela" [ref=e242] [cursor=pointer]:
              - img [ref=e243]
              - generic [ref=e246]: Tour da tela
          - paragraph [ref=e247]: Acompanhe os resultados e acesse as rotinas da loja.
        - generic [ref=e248]:
          - button "Atualizar indicadores" [disabled] [ref=e249]:
            - img [ref=e250]
            - text: Atualizar indicadores
          - button "Iniciar vendas" [ref=e255] [cursor=pointer]:
            - img [ref=e256]
            - text: Iniciar vendas
      - region "Indicadores da loja" [ref=e260]:
        - status [ref=e261]:
          - paragraph [ref=e266]: Carregando indicadores…
      - region "Validade dos produtos" [ref=e267]:
        - alert [ref=e268]:
          - img [ref=e269]
          - paragraph [ref=e271]: Não foi possível consultar a validade dos produtos.
          - paragraph [ref=e272]: Tente novamente para conferir se há produtos vencidos.
          - button "Tentar novamente" [ref=e273] [cursor=pointer]:
            - img [ref=e274]
            - text: Tentar novamente
      - region "Ações rápidas" [ref=e279]:
        - heading "Ações rápidas" [level=2] [ref=e280]
        - list [ref=e282]:
          - listitem [ref=e283]:
            - button "Produtos Cadastrar produtos e ajustar preços" [ref=e284] [cursor=pointer]:
              - img [ref=e285]
              - generic [ref=e289]:
                - generic [ref=e290]: Produtos
                - generic [ref=e291]: Cadastrar produtos e ajustar preços
              - img [ref=e292]
          - listitem [ref=e294]:
            - button "Clientes Cadastrar e consultar clientes" [ref=e295] [cursor=pointer]:
              - img [ref=e296]
              - generic [ref=e299]:
                - generic [ref=e300]: Clientes
                - generic [ref=e301]: Cadastrar e consultar clientes
              - img [ref=e302]
          - listitem [ref=e304]:
            - button "Histórico de vendas Consultar vendas finalizadas" [ref=e305] [cursor=pointer]:
              - img [ref=e306]
              - generic [ref=e310]:
                - generic [ref=e311]: Histórico de vendas
                - generic [ref=e312]: Consultar vendas finalizadas
              - img [ref=e313]
          - listitem [ref=e315]:
            - button "Relatórios Analisar os resultados da loja" [ref=e316] [cursor=pointer]:
              - img [ref=e317]
              - generic [ref=e320]:
                - generic [ref=e321]: Relatórios
                - generic [ref=e322]: Analisar os resultados da loja
              - img [ref=e323]
      - region "Gestão da loja" [ref=e325]:
        - heading "Gestão da loja" [level=2] [ref=e326]
        - list [ref=e328]:
          - listitem [ref=e329]:
            - button "Caixa Abertura, movimentações e fechamento" [ref=e330] [cursor=pointer]:
              - img [ref=e331]
              - generic [ref=e333]:
                - generic [ref=e334]: Caixa
                - generic [ref=e335]: Abertura, movimentações e fechamento
              - img [ref=e336]
          - listitem [ref=e338]:
            - button "Estoque e inventário Conferir quantidades e movimentações" [ref=e339] [cursor=pointer]:
              - img [ref=e340]
              - generic [ref=e345]:
                - generic [ref=e346]: Estoque e inventário
                - generic [ref=e347]: Conferir quantidades e movimentações
              - img [ref=e348]
          - listitem [ref=e350]:
            - button "Compras e reposição Pedidos, recebimento e custos" [ref=e351] [cursor=pointer]:
              - img [ref=e352]
              - generic [ref=e355]:
                - generic [ref=e356]: Compras e reposição
                - generic [ref=e357]: Pedidos, recebimento e custos
              - img [ref=e358]
          - listitem [ref=e360]:
            - button "Trocas e devoluções Consultar e registrar devoluções" [ref=e361] [cursor=pointer]:
              - img [ref=e362]
              - generic [ref=e367]:
                - generic [ref=e368]: Trocas e devoluções
                - generic [ref=e369]: Consultar e registrar devoluções
              - img [ref=e370]
          - listitem [ref=e372]:
            - button "Pagamentos integrados Pagamentos e conciliação" [ref=e373] [cursor=pointer]:
              - img [ref=e374]
              - generic [ref=e376]:
                - generic [ref=e377]: Pagamentos integrados
                - generic [ref=e378]: Pagamentos e conciliação
              - img [ref=e379]
```

# Test source

```ts
  93  |   test("ativar: gera token, entrega ao programa, revoga o anterior e liga o caixa ao Gateway local", async ({ page }) => {
  94  |     const calls = await setup(page, { configured: true, tokenId: "tok-antigo" });
  95  | 
  96  |     const status = await page.evaluate(async (path) => {
  97  |       const m = await import(path as string);
  98  |       return m.activateEmbeddedGateway();
  99  |     }, mod(page));
  100 | 
  101 |     expect(status.running).toBe(true);
  102 |     const bridgeCalls = await page.evaluate(() => (window as any).__bridgeCalls);
  103 |     expect(bridgeCalls[0].configure).toMatchObject({
  104 |       token: "qgw_TOKENNOVO1234567890abcdefghijklmnopqrstu",
  105 |       tokenId: "tok-novo",
  106 |       companyId: "emp-1",
  107 |       storeId: "loja-01",
  108 |       apiUrl: "http://localhost:5260",
  109 |     });
  110 |     expect(calls.tokenPosts).toHaveLength(1);
  111 |     expect(calls.revoked).toEqual(["tok-antigo"]);
  112 | 
  113 |     const config = await gatewayConfig(page);
  114 |     expect(config).toMatchObject({ enabled: true, url: GW, companyId: "emp-1", storeId: "loja-01", terminalType: "CASH", apiKey: "chave-1" });
  115 |     expect(config.terminalId).toMatch(/^CAIXA-/);
  116 |     expect(calls.registers[0]).toMatchObject({ companyId: "emp-1", terminalId: config.terminalId, terminalType: "CASH" });
  117 |   });
  118 | 
  119 |   test("abrir o programa já ativado liga sozinho; de outra empresa não liga", async ({ page }) => {
  120 |     const calls = await setup(page, { configured: true, companyId: "outra-empresa" });
  121 |     const r1 = await page.evaluate(async (path) => {
  122 |       const m = await import(path as string);
  123 |       await m.linkEmbeddedGateway();
  124 |       return localStorage.getItem("horus-gateway-config");
  125 |     }, mod(page));
  126 |     expect(r1).toBeNull();
  127 |     expect(calls.registers).toHaveLength(0);
  128 |   });
  129 | 
  130 |   test("ligado ao Gateway local, a fila sem internet vai a ele; credencial recusada (401) é renovada", async ({ page }) => {
  131 |     const calls = await setup(page, { configured: true, publishStatus: 401 });
  132 |     const result = await page.evaluate(async (path) => {
  133 |       const m = await import(path as string);
  134 |       await m.linkEmbeddedGateway();
  135 |       const { SaleOutboxAdapter } = (window as any).__horus_test__;
  136 |       await SaleOutboxAdapter.queueSaleToOutbox({
  137 |         eventId: "ev-1", clientSaleId: "cs-1", operatorId: "usr-1", customerName: "", customerCpf: "", paymentType: "dinheiro",
  138 |         totalAmount: "10,00", operatorName: "Gerente", payloadHash: "h-1",
  139 |         items: [{ productCode: "P1", productName: "Arroz", quantity: 1, unitPrice: 10, desconto: 0, itemTotal: 10 }],
  140 |         payments: [{ paymentType: "dinheiro", amount: 10, cashGiven: null, changeAmount: null }],
  141 |       });
  142 |       const fwd = await import("/src/infrastructure/gateway/outboxGatewayForwarder.ts" as string);
  143 |       const first = await fwd.forwardPendingToGateway();
  144 |       const afterFirst = JSON.parse(localStorage.getItem("horus-gateway-config") || "{}").apiKey;
  145 |       await fwd.forwardPendingToGateway();
  146 |       return { first, afterFirst };
  147 |     }, mod(page));
  148 | 
  149 |     expect(calls.published[0].key).toBe("chave-1");
  150 |     expect(result.first.forwarded).toBe(0);
  151 |     expect(result.afterFirst).toBe("");
  152 |     // Segundo ciclo: registrou de novo (registro aberto) e tentou com a chave nova.
  153 |     expect(calls.registers).toHaveLength(2);
  154 |     expect(calls.published[1].key).toBe("chave-2");
  155 |   });
  156 | 
  157 |   test("desativar para o Gateway, revoga o token e desliga o uso no PDV", async ({ page }) => {
  158 |     const calls = await setup(page, { configured: true, tokenId: "tok-atual" });
  159 |     const config = await page.evaluate(async (path) => {
  160 |       const m = await import(path as string);
  161 |       await m.linkEmbeddedGateway();
  162 |       await m.deactivateEmbeddedGateway();
  163 |       return JSON.parse(localStorage.getItem("horus-gateway-config") || "{}");
  164 |     }, mod(page));
  165 | 
  166 |     expect(calls.revoked).toEqual(["tok-atual"]);
  167 |     expect(config).toMatchObject({ enabled: false, apiKey: "" });
  168 |     const bridgeCalls = await page.evaluate(() => (window as any).__bridgeCalls);
  169 |     expect(bridgeCalls).toEqual([{ disable: true }]);
  170 |   });
  171 | 
  172 |   test("descoberta pela nuvem não troca o Gateway embutido por outro endereço", async ({ page }) => {
  173 |     await setup(page, { configured: true });
  174 |     let cloudAsked = 0;
  175 |     await page.route("http://localhost:5260/api/gateway-config**", (route) => {
  176 |       cloudAsked++;
  177 |       return route.fulfill({
  178 |         json: { success: true, data: { gatewayUrl: "http://192.168.0.50:5080", enabled: true, storeId: "loja-01", updatedAt: "2026-10-06T00:00:00Z" } },
  179 |       });
  180 |     });
  181 |     const urls = await page.evaluate(async (path) => {
  182 |       const read = () => JSON.parse(localStorage.getItem("horus-gateway-config") || "{}").url;
  183 |       const p = await import("/src/infrastructure/gateway/cloudGatewayProvisioning.ts" as string);
  184 |       // Sem o Gateway embutido ligado, a nuvem define o endereço (controle do teste)...
  185 |       await p.learnGatewayFromCloud();
  186 |       const semEmbutido = read();
  187 |       // ...com ele ligado, a nuvem não sobrescreve.
  188 |       const m = await import(path as string);
  189 |       await m.linkEmbeddedGateway();
  190 |       await p.learnGatewayFromCloud();
  191 |       return { semEmbutido, comEmbutido: read() };
  192 |     }, mod(page));
> 193 |     expect(urls.semEmbutido).toBe("http://192.168.0.50:5080");
      |                              ^ Error: expect(received).toBe(expected) // Object.is equality
  194 |     expect(urls.comEmbutido).toBe(GW);
  195 |     expect(cloudAsked).toBe(1);
  196 |   });
  197 | });
  198 | 
```