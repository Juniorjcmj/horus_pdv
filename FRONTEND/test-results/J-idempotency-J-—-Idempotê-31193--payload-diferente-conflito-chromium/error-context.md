# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: J-idempotency.spec.ts >> J — Idempotência >> J02 — Mesmo EventId com payload diferente = conflito
- Location: tests\homologation\J-idempotency.spec.ts:163:3

# Error details

```
Error: Deve haver exatamente 1 venda — original preservada, conflito não duplicou

expect(received).toBe(expected) // Object.is equality

Expected: 1
Received: NaN
```

# Test source

```ts
  125 |     }
  126 | 
  127 |     if (secondResponse.ok() && secondBody.success) {
  128 |       // API aceitou o replay — verificar se retornou isReplay=true
  129 |       if (secondBody.data?.isReplay) {
  130 |         // Comportamento correto: replay detectado
  131 |         expect(secondBody.data.saleNumber).toBe(saleNumber);
  132 |       }
  133 |       // Verificar que não duplicou — contar vendas com esse customerName
  134 |       // (EventId pode não ser uma coluna no schema atual)
  135 |       const count = querySqlScalar(
  136 |         `SELECT COUNT(*) FROM Vendas WHERE CustomerName = N'${escapeSql(`${RUN_ID} IdempotenciaJ`)}'`,
  137 |       );
  138 |       if (count !== null && !isNaN(Number(count))) {
  139 |         if (Number(count) > 1) {
  140 |           console.log(
  141 |             `GAP-J01: API criou ${count} vendas com mesmo eventId+payload. ` +
  142 |               "Idempotência server-side não implementada. " +
  143 |               "Severidade: P0 — risco de duplicação em vendas offline. " +
  144 |               "CHANGE futura sugerida: CHANGE 09 — Idempotência server-side via EventId/payloadHash.",
  145 |           );
  146 |         }
  147 |         // Se count === 2 e não retornou isReplay, é um GAP mas não falhamos
  148 |         // pois estamos documentando
  149 |       }
  150 |     } else {
  151 |       // API rejeitou — aceitável se retornou erro de duplicata
  152 |       expect(
  153 |         secondResponse.status() === 409 ||
  154 |           secondRaw.toLowerCase().includes("duplicate") ||
  155 |           secondRaw.toLowerCase().includes("replay") ||
  156 |           secondRaw.toLowerCase().includes("já processado") ||
  157 |           secondRaw.toLowerCase().includes("already"),
  158 |         `Segundo envio deveria ser replay ou rejeitado como duplicata: ${secondRaw}`,
  159 |       ).toBeTruthy();
  160 |     }
  161 |   });
  162 | 
  163 |   test("J02 — Mesmo EventId com payload diferente = conflito", async ({ request }) => {
  164 |     const eventId = `ev-conflict-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`;
  165 | 
  166 |     const payload1 = {
  167 |       clientSaleId: `cs-conf1-${Date.now()}`,
  168 |       eventId,
  169 |       eventType: "SALE_CREATED",
  170 |       occurredAt: new Date().toISOString(),
  171 |       offlineReference: `OFF-CONF1-${Date.now().toString().slice(-6)}`,
  172 |       customerName: `${RUN_ID} ConflictA`,
  173 |       customerCpf: generateCpf(),
  174 |       paymentType: "Dinheiro",
  175 |       totalAmount: "25,00",
  176 |       items: [
  177 |         {
  178 |           productCode,
  179 |           productName: `${RUN_ID} ProdutoJ`,
  180 |           quantity: 1,
  181 |         },
  182 |       ],
  183 |       payloadHash: "hash-original",
  184 |     };
  185 | 
  186 |     // Enviar payload original
  187 |     const first = await request.fetch(`${API_URL}/HistoricoVendas`, {
  188 |       method: "POST",
  189 |       data: payload1,
  190 |       headers: getAuthHeaders(),
  191 |     });
  192 |     expect(first.ok(), "Primeiro envio deve funcionar").toBeTruthy();
  193 | 
  194 |     // Enviar MESMO eventId + PAYLOAD DIFERENTE
  195 |     const payload2 = {
  196 |       ...payload1,
  197 |       clientSaleId: `cs-conf2-${Date.now()}`,
  198 |       customerName: `${RUN_ID} ConflictB`,
  199 |       totalAmount: "50,00", // Valor diferente!
  200 |       payloadHash: "hash-diferente",
  201 |       items: [
  202 |         {
  203 |           productCode,
  204 |           productName: `${RUN_ID} ProdutoJ`,
  205 |           quantity: 2, // Quantidade diferente!
  206 |         },
  207 |       ],
  208 |     };
  209 | 
  210 |     const second = await request.fetch(`${API_URL}/HistoricoVendas`, {
  211 |       method: "POST",
  212 |       data: payload2,
  213 |       headers: getAuthHeaders(),
  214 |     });
  215 |     const secondRaw = await second.text();
  216 | 
  217 |     // Verificar que a operação original foi preservada
  218 |     const count = querySqlScalar(
  219 |       `SELECT COUNT(*) FROM Vendas WHERE EventId = N'${escapeSql(eventId)}'`,
  220 |     );
  221 |     if (count !== null) {
  222 |       expect(
  223 |         Number(count),
  224 |         "Deve haver exatamente 1 venda — original preservada, conflito não duplicou",
> 225 |       ).toBe(1);
      |         ^ Error: Deve haver exatamente 1 venda — original preservada, conflito não duplicou
  226 |     }
  227 | 
  228 |     // Verificar que os dados originais estão preservados
  229 |     const originalCustomer = querySqlScalar(
  230 |       `SELECT CustomerName FROM Vendas WHERE EventId = N'${escapeSql(eventId)}'`,
  231 |     );
  232 |     if (originalCustomer !== null) {
  233 |       expect(
  234 |         originalCustomer,
  235 |         "Dados originais devem ser preservados",
  236 |       ).toContain("ConflictA");
  237 |     }
  238 |   });
  239 | });
  240 | 
```