# Tasks: Múltiplas Formas de Pagamento no PDV

## 1. Banco de Dados e Migrações

- [x] 1.1 Criar script SQL de migração adicionando a tabela `VendaPagamentos` com índices `(CompanyId, VendaId)` e `(CompanyId, PaymentType)`
- [x] 1.2 Adicionar rotina de migração no script para popular `VendaPagamentos` a partir das vendas históricas existentes

## 2. Backend .NET 8 (Modelos, Repositórios e Serviços)

- [x] 2.1 Criar classe `VendaPagamentoRequest` e estender `VendaRequest` para aceitar `List<VendaPagamentoRequest> Payments`
- [x] 2.2 Implementar entidade `VendaPagamentoAD` e atualizar `HistoricoVendasAB.InserirVendaAsync` para persistir as parcelas em transação atômica
- [x] 2.3 Atualizar `CaixaAB.ObterTotaisPorFormaPagamentoAsync` para consolidar fechamento de caixa por forma a partir de `VendaPagamentos`
- [x] 2.4 Atualizar `DocumentoFiscalAB.MontarRequisicaoEmissaoAsync` para alimentar a lista de `Pagamentos` da NFC-e com as parcelas da venda

## 3. Frontend React (Checkout e Interface do PDV)

- [x] 3.1 Atualizar contratos e tipos em `salesHistoryService.ts` adicionando a lista `payments` no payload de registro de venda
- [x] 3.2 Reformular o modal de pagamento em `SalesStartPage.tsx` permitindo adicionar múltiplos pagamentos e remover parcelas
- [x] 3.3 Implementar feedback em tempo real no checkout: saldo restante, sugestão automática do valor pendente e cálculo de troco em dinheiro
- [x] 3.4 Atualizar o componente de recibo/impressão e histórico de vendas para detalhar a discriminação dos meios e valores pagos

## 4. Testes e Validação

- [x] 4.1 Testar fluxo completo de venda mista no PDV (ex.: R$ 200,00 no PIX e R$ 100,00 no Débito em compra de R$ 300,00)
- [x] 4.2 Testar venda mista com dinheiro e troco (ex.: R$ 50,00 no PIX + R$ 100,00 em dinheiro para compra de R$ 120,00 gerando R$ 30,00 de troco)
- [x] 4.3 Conferir fechamento de caixa e conferir XML gerado para a SEFAZ com múltiplos nós `<detPag>`
- [x] 4.4 Validar retrocompatibilidade com chamadas que enviam apenas a forma única
