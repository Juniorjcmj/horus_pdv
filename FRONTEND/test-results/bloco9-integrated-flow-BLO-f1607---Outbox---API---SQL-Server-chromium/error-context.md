# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: bloco9-integrated-flow.spec.ts >> BLOCO 9 — Integracao do Fluxo Completo Offline-First >> Teste 31 — Fluxo integrado real: Abertura -> Venda -> Movimento -> Fechamento via IndexedDB -> Outbox -> API -> SQL Server
- Location: tests\architecture\bloco9-integrated-flow.spec.ts:38:3

# Error details

```
Error: apiRequestContext.post: connect ECONNREFUSED ::1:5260
Call log:
  - → POST http://localhost:5260/api/Auth/register
    - user-agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.7727.15 Safari/537.36
    - accept: */*
    - accept-encoding: gzip,deflate,br
    - Content-Type: application/json
    - content-length: 227

```