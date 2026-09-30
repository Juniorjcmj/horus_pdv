# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: bloco2-3-idempotency.spec.ts >> BLOCOS 2 e 3 — Idempotência de Vendas e Concorrência de CASH_MOVEMENT >> Teste 8 — Nova venda: envio inicial com EventId e PayloadHash processa com sucesso
- Location: tests\architecture\bloco2-3-idempotency.spec.ts:23:3

# Error details

```
Error: apiRequestContext.post: connect ECONNREFUSED ::1:5260
Call log:
  - → POST http://localhost:5260/api/Auth/register
    - user-agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.7727.15 Safari/537.36
    - accept: */*
    - accept-encoding: gzip,deflate,br
    - Content-Type: application/json
    - content-length: 224

```