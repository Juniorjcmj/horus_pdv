## 1. Utilitário de Impressão Térmica do DANFE NFC-e

- [x] 1.1 Criar utilitário `src/utils/danfePrint.ts` com gerador HTML da bobina térmica de 80mm contendo cabeçalho oficial, itens, totais, tributos, identificação fiscal (número, série, protocolo), chave de acesso de 44 dígitos e renderização do QR Code oficial da SEFAZ.
- [x] 1.2 Atualizar `ReceiptPreviewModal.tsx` para aceitar os dados fiscais e renderizar o DANFE NFC-e oficial quando a nota estiver autorizada.

## 2. Orquestração Pós-Venda no PDV

- [x] 2.1 Em `SalesStartPage.tsx`, implementar verificação ágil da emissão (polling de até 3s) após a confirmação da venda para capturar a NFC-e autorizada.
- [x] 2.2 Exibir e imprimir o DANFE NFC-e com QR Code imediatamente se autorizado, mantendo fallback de cupom gerencial se a SEFAZ demorar.

## 3. Reimpressão Térmica no Histórico de Vendas e Módulo Fiscal

- [x] 3.1 Em `DanfePreviewModal.tsx`, adicionar botão de ação "Imprimir DANFE 80mm" para enviar o cupom oficial diretamente ao spool de impressão do navegador.
- [x] 3.2 Em `SalesHistoryPage.tsx`, incluir ação "Imprimir DANFE" no menu de ações para vendas autorizadas.
- [x] 3.3 Em `FiscalPage.tsx`, disponibilizar atalho de impressão térmica de 80mm para documentos fiscais com status "Autorizado".

## 4. Validação e Verificação

- [x] 4.1 Testar a renderização do HTML de 80mm e legibilidade do QR Code no diálogo de impressão.
- [x] 4.2 Executar build e validação estática de tipos (`npm run build` ou `tsc -b`).
