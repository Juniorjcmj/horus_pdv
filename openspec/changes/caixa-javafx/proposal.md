# Proposal

## Why

A frente de caixa hoje é uma página React de ~3.700 linhas ([SalesStartPage.tsx](../../../FRONTEND/src/pages/Admin/SalesStartPage.tsx)) que roda no navegador ou dentro do Electron. Lojas com máquinas fracas ou que querem um caixa nativo (sem Chromium, sem IndexedDB do navegador que pode ser apagado) precisam de uma alternativa leve, com teclado como entrada principal, que fale com a mesma API .NET e não mude nenhuma regra de negócio do servidor.

## What Changes

- Novo módulo `JAVA/caixa/` (Java 21 + JavaFX 21, Maven) com um executável desktop "Quack Caixa".
- Login com e-mail e senha na mesma rota `POST /api/Auth/login` (sessão pelo cookie `horuspdv.auth`).
- Abertura, fechamento, sangria e reforço de caixa pelas rotas existentes `/api/Caixa/*`, com bloqueio de venda quando o caixa não está aberto.
- Venda completa: busca por nome/código/código de barras, quantidade fracionada para itens por peso, etiqueta de balança EAN-13 (prefixo `2`), promoções ativas, remoção de item, cancelamento da venda.
- Pagamento com múltiplas formas (dinheiro, PIX, débito, crédito, fiado), troco em dinheiro, fiado só com cliente cadastrado e dentro do limite, CPF na nota.
- Registro da venda em `POST /api/HistoricoVendas` com o mesmo payload da web (`clientSaleId`, `eventId`, `payloadHash`, itens, pagamentos), garantindo idempotência.
- Modo offline: sem API, a venda é gravada numa fila local em disco e reenviada automaticamente com o mesmo `eventId`; catálogo, clientes, promoções e último status do caixa ficam em cache local.
- Cupom não fiscal impresso pela impressora padrão do Windows (a NFC-e continua sendo emitida pelo servidor, como hoje).
- Atalhos de teclado iguais aos do caixa web (F2, F4, F6, F1/F3, F8, F10, F12, Esc, Alt+1..5).
- Nenhuma mudança na API, no frontend web ou no Gateway.

## Capabilities

### New Capabilities
- `pdv/caixa-desktop`: frente de caixa desktop nativa que opera vendas, pagamentos e caixa contra a API existente, com fila offline local e paridade de regras de valores com o caixa web.

### Modified Capabilities
<!-- Nenhuma: as regras de pagamento múltiplo (pdv/multiplas-formas-pagamento) são reutilizadas sem mudança. -->

## Impact

- Código novo: `JAVA/caixa/` (pom.xml, código Java, CSS, testes, README, script de build).
- APIs consumidas (sem alteração): `/api/Auth/login|logout|me`, `/api/Caixa/status|abrir|fechar|movimento`, `/api/Produto`, `/api/Cliente`, `/api/Promocao/ativas`, `/api/Empresa`, `/api/HistoricoVendas`.
- Dependências novas (só no módulo Java): OpenJFX 21.0.8, Jackson 2.17.2, JUnit 5.10.2.
- Dados locais: pasta `%APPDATA%\QuackCaixa\` (config, cache, fila offline).
- Fora do escopo desta versão: pedidos do vendedor, NF-e modelo 55, alteração de preço com senha de gerente, cancelamento de NFC-e/venda, Local Gateway, crédito compartilhado do Gateway, impressão do DANFE NFC-e.
