# Proposal: Múltiplas Formas de Pagamento no PDV

## Why

Atualmente, o Hórus PDV permite selecionar apenas uma forma de pagamento por venda (ex.: 100% Dinheiro, 100% PIX ou 100% Cartão). No comércio varejista diário, é muito comum clientes dividirem o valor da compra entre diferentes meios de pagamento (por exemplo: em uma compra de R$ 300,00, pagar R$ 200,00 no PIX e R$ 100,00 no Débito). A ausência dessa funcionalidade obriga o operador a recusar a divisão ou criar vendas artificiais, prejudicando o atendimento, a gestão de caixa e a emissão correta da NFC-e.

## What Changes

- **Interface de Checkout do PDV**:
  - Adição de interface de divisão de pagamentos no modal de finalização (`SalesStartPage`), permitindo incluir múltiplas parcelas com formas de pagamento distintas (Dinheiro, PIX, Cartão de Débito, Cartão de Crédito).
  - Validação em tempo real do saldo restante a pagar e cálculo automático de troco quando houver pagamento em dinheiro.
  - Atalhos rápidos e preenchimento ágil para manter a agilidade no caixa.

- **Contrato da API e Backend**:
  - Extensão do `VendaRequest` para suportar `Payments: List<VendaPagamentoRequest>`, mantendo retrocompatibilidade com o campo `PaymentType` legado para clientes que enviarem pagamento único.
  - Criação da tabela `VendaPagamentos` no SQL Server (`Id, CompanyId, VendaId, PaymentType, Amount, CreatedAt`) vinculada a `Vendas`.
  - Atualização do `HistoricoVendasAB` para gravar as frações de pagamento em transação atômica.

- **Fechamento e Conferência de Caixa**:
  - Atualização de `CaixaAB.ObterTotaisPorFormaPagamentoAsync` para consolidar os valores reais recebidos em cada forma a partir de `VendaPagamentos`, garantindo que o dinheiro na gaveta, os totais em PIX e cartões reflitam exatamente o que foi rateado.

- **Emissão de NFC-e (Fiscal)**:
  - Atualização de `DocumentoFiscalAB.MontarRequisicaoEmissaoAsync` para enviar todas as linhas de pagamento para `EmissaoNfceRequest.Pagamentos`.
  - Geração correta dos múltiplos blocos `<detPag>` na NFC-e (ex.: `tPag: 17` para R$ 200,00 e `tPag: 04` para R$ 100,00), já suportados pelo `ZeusFiscalProvider`.

- **Comprovantes e Histórico**:
  - Atualização do recibo de impressão e do histórico de vendas para detalhar as formas e os respectivos valores pagos.

## Capabilities

### New Capabilities
- `pdv/multiplas-formas-pagamento`: Permite dividir o pagamento de uma venda entre múltiplos meios (PIX, Dinheiro, Cartão), com conciliação no caixa, detalhamento no comprovante e transmissão multi-pagamento para a NFC-e.

### Modified Capabilities
<!-- Nenhuma especificação anterior existia formalizada no openspec/specs. -->

## Impact

- **Banco de Dados**: Nova migração SQL criando a tabela `VendaPagamentos` com índices por `(CompanyId, VendaId)` e `(CompanyId, PaymentType)`.
- **API Backend**:
  - `HORUSPDV_API.Models.Requests.VendaRequest`
  - `HORUSPDV_API.Repositories.DatabaseAccess.HistoricoVendasAB`
  - `HORUSPDV_API.Repositories.DatabaseAccess.CaixaAB`
  - `HORUSPDV_API.Repositories.DatabaseAccess.DocumentoFiscalAB`
- **Frontend**:
  - `FRONTEND/src/pages/Admin/SalesStartPage.tsx` (modal de checkout)
  - `FRONTEND/src/services/api/salesHistoryService.ts` (tipagem do payload)
  - `FRONTEND/src/components/Admin/SalesHistoryPage.tsx` e componentes de recibo/impressão
