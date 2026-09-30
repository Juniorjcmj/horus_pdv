# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: bloco13-resilience-concurrency.spec.ts >> BLOCO 13 — Homologação: Segurança, Concorrência e Resiliência Offline-First (Change 07.3) >> Teste 61 — Abertura concorrente de caixa: requisições simultâneas com mesmo EventId resultam em 1 abertura e 1 replay sem duplicidade
- Location: tests\architecture\bloco13-resilience-concurrency.spec.ts:20:3

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