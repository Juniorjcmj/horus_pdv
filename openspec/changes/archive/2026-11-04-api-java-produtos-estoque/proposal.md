# Proposal

## Why

As Fases 1 e 2 deixaram na API Java o login, os usuários e os cadastros. A Fase 3 do plano
(`docs/migracao-api-springboot.md`, § 11) porta o coração do Quack Estoque: produtos, saldo e movimentação de estoque,
lotes e validade, entradas de mercadoria (manual e por XML de NF-e), inventário, ordens de compra e promoções. Sem
isso, o Quack Estoque não roda contra a API Java, e as fases de vendas (4) e relatórios (6) não têm sobre o que trabalhar.

## What Changes

- **Produtos** (`api/Produto`):
  - lista, criação, edição e exclusão, com código e GTIN únicos e fornecedor obrigatoriamente cadastrado;
  - **estoque relativo**: a edição aplica só a diferença digitada sobre o saldo atual;
  - **preço com versão** (`PATCH preco-venda`): recusa com 409 quando custo ou preço mudaram desde a consulta;
  - vencimentos (lista e resumo) e validade do produto;
  - carga legada de demonstração (`importar-legado`).
- **Registrador único de movimentação:** um só caminho altera o saldo do produto e grava a movimentação na mesma
  transação, com saldo anterior e novo exatos sob concorrência. Rotas que usam o registrador:
  - ajuste manual (entrada, saída, uso interno, perda, com motivo obrigatório);
  - listagem de movimentações por produto e geral.
- **Lotes e validade** (`api/Produto/lotes`):
  - alertas por lote, consulta com filtros, lotes do produto e registro manual de lote;
  - modo da baixa por lote (FEFO): desligado, sombra ou ativo;
  - prazo padrão de validade por categoria;
  - consumo de lotes nas saídas por ajuste.
- **Entrada de mercadorias:**
  - entrada manual (`api/Produto/entrada-manual`);
  - importação de NF-e/NFC-e **por arquivo XML** (`api/NfeImport/preview` e `confirmar`), com conferência de chave,
    emitente e itens e confirmação idempotente;
  - notas de entrada: lista, detalhe, XML, estorno com justificativa, exportação em ZIP, vínculo de XML posterior e
    comprovante.
- **Inventário** (`api/Inventario`):
  - abertura por loja, categorias ou produtos;
  - contagens, anulação, recontagem e trilha;
  - aplicação com justificativa e senha de supervisor (ajusta o estoque pelo registrador);
  - cancelamento.
- **Ordens de compra** (`api/OrdemCompra`):
  - criar (inclusive sem fornecedor, para cotação), editar só quando pendente, listar e consultar;
  - receber total ou parcial, dando entrada no estoque com lote e validade;
  - cancelar;
  - sugestões de reposição.
- **Promoções** (`api/Promocao`): os 6 tipos, vigência, ativar e desativar, ativas, resultado e exclusão, conforme o
  spec existente `vendas/promocoes`.
- **Contrato:** novos grupos de cenários gravados da API .NET e comparados na Java.
- **Aceite:** os testes E2E do Quack Estoque passam contra a API Java, exceto os de relatórios e análise de estoque,
  que são da Fase 6.
- **Fora desta fase** (decidido com o usuário):
  - busca de NF-e direto na SEFAZ pela chave (`NfeImport/buscar-sefaz`), verificação fiscal do produto
    (`Produto/{id}/verificacao-fiscal`) e revisão fiscal com IA (`Produto/fiscal-ia`): vão para a Fase 7, com a
    biblioteca java-nfe. Até lá, `buscar-sefaz` valida a chave como hoje e recusa com mensagem própria;
  - relatórios e análise de estoque (`api/Estoque/*`) e foto diária: Fase 6;
  - baixa de lotes na venda e estorno de venda: Fase 4, que usará o registrador e o consumo de lotes desta fase.

## Capabilities

### New Capabilities
- `produtos/cadastro-produtos`: cadastro de produtos na API Java, com unicidade, estoque relativo na edição, preço com
  controle de versão, validade do produto e carga de demonstração.
- `estoque/movimentacao`: registrador único de saldo e movimentação, ajuste manual de estoque e consulta das
  movimentações.
- `estoque/lotes-fefo`: lotes com validade, alertas e consulta, registro manual, modo da baixa por lote e prazos por
  categoria.
- `estoque/entrada-mercadorias`: entrada manual e por XML de NF-e/NFC-e, notas de entrada, estorno, exportação e
  comprovantes.
- `estoque/inventario`: inventário com escopo, contagens, recontagem, aplicação autorizada por supervisor,
  cancelamento e trilha.
- `compras/ordens-compra`: ordens de compra com ou sem fornecedor, edição, recebimento total ou parcial com entrada no
  estoque, cancelamento e sugestão de reposição.

### Modified Capabilities
- (nenhuma: `vendas/promocoes` e `produtos/controle-validade` já descrevem promoções e vencimentos do produto, e a API
  Java os reproduz sem mudar requisito. A API .NET não muda.)

## Impact

- **Código novo** em `JAVA/api`:
  - pacotes `produto` (produtos, lotes, entrada manual, importação de NF-e), `estoque` (registrador, inventário),
    `compras` e `promocao`;
  - cenários e gravações de contrato e seed de contrato ampliado.
- **Banco:**
  - sem tabela nova nem migração nova, porque as entidades mapeiam tabelas que já estão no `V1`;
  - as migrações .NET destes módulos não criam dados de referência: os produtos de demonstração ficam de fora de
    propósito, e a criação de lotes da migração 34 só vale para produtos que já existem.
- **Recursos:** o script da carga legada (`06_produtos_mercado_completo.sql`) passa a ser recurso da API Java.
- **Clientes:** nenhuma mudança. O Quack Estoque, a web e o Caixa usam as mesmas rotas e o mesmo JSON.
- **Teste de aceite:** um SQL Server só para a API Java (cópia do banco de teste do E2E), para não misturar com o banco
  da API .NET.
- **Branch:** `spring-api`.
