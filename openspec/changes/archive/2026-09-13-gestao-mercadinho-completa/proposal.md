# Proposal: Gestão Completa de Mercadinho — Categorias, Validade, Promoções e Fiado

## Why

O Hórus PDV tem a base fiscal e de caixa mais robusta do segmento (NFC-e com contingência offline, split payment, importação de NF-e, outbox pattern). Porém, faltam as funcionalidades que fazem o mercadinho **funcionar no dia a dia**. Sem elas, o sistema é um "emissor de nota" — não um sistema de gestão.

Os 4 gaps mais críticos identificados por análise de operação real de mercadinhos:

1. **Categorias**: Produtos são uma lista plana de 500+ itens. O dono não consegue responder "quanto vendi de bebidas?" nem filtrar estoque por setor. Categorias são pré-requisito para promoções por departamento e relatórios gerenciais.

2. **Controle de Validade**: Mercadinho perde 3-5% do faturamento com produtos vencidos. Vigilância Sanitária multa R$2.000-R$20.000 por item vencido na gôndola. O dono descobre o vencimento quando o cliente reclama.

3. **Promoções**: O motor de vendas do mercadinho. "3 por R$10", "leve 3 pague 2", "20% off na carne sexta-feira" — sem isso, o dono não compete com a concorrência. Hoje não há como cadastrar nenhum tipo de promoção.

4. **Fiado (Conta Corrente)**: 30-50% das vendas de mercadinho de bairro são fiado. Sem essa feature, o caderninho permanece ao lado do computador. Nenhum PDV domina esse segmento sem resolver o fiado.

## What Changes

### Feature 1 — Categorias / Departamentos de Produto
- Nova tabela `Categorias` com hierarquia de 2 níveis (Departamento → Subcategoria) via `CategoriaPaiId` auto-referencial
- Campo `CategoriaId` no `Produtos` com FK para `Categorias`
- Seed com 14 departamentos padrão de mercadinho + subcategorias essenciais
- CRUD completo: `CategoriaController` com árvore hierárquica e contagem de produtos
- Ajuste no cadastro de produto: select cascata Departamento → Subcategoria
- Grid de atalhos por categoria no PDV para busca rápida de produtos
- Filtro `categoriaId` em todos os relatórios existentes
- Novo relatório `margem-por-categoria`

### Feature 2 — Controle de Validade
- 3 novos campos no `Produtos`: `DataValidade`, `ControlaValidade`, `DiasAlertaValidade`
- Endpoints de consulta de vencimentos com KPIs por faixa (vencido/7d/15d/30d)
- Seção de controle de validade no cadastro de produto
- Widget de alertas no Dashboard com contagem por faixa e cores semafóricas
- Alerta no PDV ao vender produto vencido (bloqueio configurável)
- Novo relatório `vencimentos`

### Feature 3 — Promoções e Preços Dinâmicos
- Tabelas `Promocoes` e `PromocaoProdutos` (vínculo N:N)
- 6 tipos de promoção: desconto %, desconto R$, preço fixo, leve X pague Y, combo quantidade, preço atacado
- Vigência por data/hora, ativação/desativação manual
- Motor de promoção no frontend PDV: aplicação automática, badge visual, preço original riscado
- Promoções não acumulam (maior desconto vence)
- NFC-e: desconto no campo `vDesc` do item
- Tela de gestão `PromocoesPage.tsx`
- Relatório de resultado por promoção
- Promoção por categoria inteira (depende de Feature 1)

### Feature 4 — Fiado / Conta Corrente do Cliente
- Campos `LimiteCredito` e `SaldoDevedor` no `Clientes`
- Tabela `FiadoMovimentos` com débitos (compras) e créditos (pagamentos)
- Novo tipo de pagamento `fiado` no split payment do PDV
- Verificação de limite de crédito na venda
- Recebimento parcial/total com registro de forma de pagamento
- Extrato imprimível em 80mm com linha de assinatura
- NFC-e: fiado mapeado para `tPag=05` (crediário loja)
- Tela `FiadoPage.tsx` com KPIs de inadimplência, lista de devedores, modal de recebimento
- Relatório de inadimplência

## Capabilities

### New Capabilities
- `produtos/categorias`: Hierarquia de categorias/departamentos para classificação de produtos com navegação por categoria no PDV
- `produtos/controle-validade`: Controle de data de validade com alertas, KPIs e bloqueio opcional de venda de vencidos
- `vendas/promocoes`: Motor de promoções com 6 tipos, vigência, aplicação automática no PDV e integração fiscal
- `vendas/fiado-conta-corrente`: Conta corrente de clientes com limite de crédito, recebimentos e extrato imprimível

### Modified Capabilities
- Nenhuma especificação anterior é modificada diretamente; as novas capabilities se integram aos fluxos existentes de venda, produto e NFC-e.

## Impact

- **Banco de Dados**: 4 novas migrações SQL (09-12) criando tabela `Categorias`, campos de validade, tabelas `Promocoes`/`PromocaoProdutos`, tabela `FiadoMovimentos` e campos no `Clientes`
- **API Backend**:
  - Novos controllers: `CategoriaController`, `PromocaoController`, `FiadoController`
  - Novos repositories: `CategoriaAB`/`CategoriaAD`, `PromocaoAB`/`PromocaoAD`, `FiadoAB`/`FiadoAD`
  - Alterações: `ProdutoAB`/`ProdutoAD` (CategoriaId, validade), `HistoricoVendasAB` (fiado na transação), `ZeusFiscalProvider` (tPag=05, vDesc), `RelatorioAB` (filtros por categoria, novos relatórios), `HomeAB` (widget validade)
- **Frontend**:
  - Novas páginas: `PromocoesPage.tsx`, `FiadoPage.tsx`
  - Alterações: `ProductRegisterPage.tsx` (categoria + validade), `SalesStartPage.tsx` (grid categorias, motor promoção, pagamento fiado, alerta validade), `HomePage` (widget validade), `ReportsPage` (novos relatórios + filtro categoria)
  - Novos services: `categoriaService.ts`, `promocaoService.ts`, `fiadoService.ts`
  - Routing: 2 novas rotas no router
