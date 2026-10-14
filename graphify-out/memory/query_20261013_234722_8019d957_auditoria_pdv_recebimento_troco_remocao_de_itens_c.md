---
type: "query"
date: "2026-10-13T23:47:22.879494+00:00"
question: "Auditoria PDV recebimento troco remocao de itens cupom 1245"
contributor: "graphify"
outcome: "useful"
source_nodes: ["SalesStartPage.tsx", "SalesHistoryPage.tsx", "HistoricoVendasAB", "FiscalPaymentRules"]
---

# Q: Auditoria PDV recebimento troco remocao de itens cupom 1245

## Answer

Inspecao de fontes e reproducao isolada: remocao pelo rawCart recalcula subtotal; SalesHistoryPage.toReceipt usa fallback || que converte ItemTotal zero em preco cheio, reproduzindo itens 87.62 e total 82.48 se biscoito for gratuito; reimpressao zera troco. HistoricoVendasAB nao confere soma dos itens com cabecalho nem recebido/troco na persistencia e contabiliza somente primeira parcela fiado. FiscalPaymentRules corrige pagamento bruto desde commit 62b1a94 em 10/10. Troco JS sem round2 pode ser rejeitado pela validacao decimal estrita do LocalFiscalSigner. Causas das vendas reais dependem dos registros e XML originais.

## Outcome

- Signal: useful

## Source Nodes

- SalesStartPage.tsx
- SalesHistoryPage.tsx
- HistoricoVendasAB
- FiscalPaymentRules