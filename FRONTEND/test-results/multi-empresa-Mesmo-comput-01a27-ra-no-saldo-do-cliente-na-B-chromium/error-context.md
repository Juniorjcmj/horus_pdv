# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: multi-empresa.spec.ts >> Mesmo computador, duas empresas >> fiado offline da A não entra no saldo do cliente na B
- Location: tests\architecture\multi-empresa.spec.ts:102:3

# Error details

```
Error: page.evaluate: Error: Dados do cliente indisponíveis para comprar fiado offline.
    at Transaction.<anonymous> (http://localhost:5173/src/application/sales/SaleOutboxAdapter.ts:118:31)
```