## Context

O backend do Horus PDV já possui suporte completo à emissão de NFC-e com o `ZeusFiscalProvider`, persistindo o XML protocolado e extraindo `NumeroNf`, `Serie`, `ChaveAcesso`, `Protocolo`, `DhAutorizacao` e `QrCodeUrl` via rota `GET /api/Nfce/{saleNumber}`.

No frontend, a impressão atual para bobina de 80mm é realizada via `window.print()` em janela/iframe isolado no componente `ReceiptPreviewModal.tsx`, porém limitada a um "CUPOM NAO FISCAL". O componente `DanfePreviewModal.tsx` exibe os dados fiscais na tela, mas não gera o layout de impressão em bobina térmica de 80mm.

## Goals / Non-Goals

**Goals:**
- Implementar gerador de HTML padronizado para impressão térmica de 80mm do DANFE NFC-e, em estrita conformidade com o Manual de Padrões Técnicos da SEFAZ.
- Renderizar o QR Code bidimensional oficial dentro do HTML de impressão térmica de 80mm.
- Orquestrar o fluxo de finalização no PDV (`SalesStartPage`) para aguardar a autorização da NFC-e por até 3 segundos e já disparar o DANFE oficial.
- Disponibilizar botão de impressão térmica do DANFE NFC-e em `DanfePreviewModal`, `SalesHistoryPage` e `FiscalPage`.

**Non-Goals:**
- Alterações no schema do banco ou nas classes C# da API (os dados já são fornecidos integralmente pelo backend).
- Comunicação direta via ESC/POS ou portas seriais (a impressão utiliza o subsistema padrão de spool do navegador/driver térmico `size: 80mm auto`).

## Decisions

1. **Geração do QR Code no HTML de Impressão**:
   - *Decisão*: Gerar o QR Code em Data URL (imagem base64 ou SVG inline) a partir do `qrCodeUrl` da SEFAZ para inserção direta no template HTML da bobina de 80mm.
   - *Alternativas consideradas*: Depender de fontes de código de barras ou requisição externa de imagem. *Rejeitado* porque não funciona offline e pode falhar durante o carregamento do diálogo de impressão do navegador.

2. **Gerador Centralizado de Impressão (`danfePrint.ts`)**:
   - *Decisão*: Criar um utilitário centralizado que receba os dados da venda (`SaleReceipt`) e os dados fiscais autorizados (`FiscalDocumentDetailDto`) e monte o documento térmico oficial completo.
   - *Vantagem*: Garante que a impressão gerada pelo PDV, pelo Histórico de Vendas e pelo Módulo Fiscal utilize exatamente o mesmo layout auditável.

3. **Orquestração Pós-Venda Não-Bloqueante no PDV**:
   - *Decisão*: Ao concluir `salesHistoryService.register`, disparar verificação rápida com tentativas curtas (polling a cada 500ms, teto de 3s) em `fiscalService.getBySaleNumber`.
   - *Se Autorizado*: Abre diretamente o preview/impressão do DANFE NFC-e oficial.
   - *Se Não Autorizado dentro do teto*: Abre o recibo gerencial informando que a nota está em processamento, permitindo que a fila do caixa continue fluindo sem travamento.

## Risks / Trade-offs

- [SEFAZ instável atrasando autorização no caixa] → O teto de 3 segundos impede que o caixa fique travado; o operador pode imprimir o recibo da venda e, posteriormente, imprimir o DANFE pelo Histórico de Vendas assim que o worker concluir a transmissão.
- [Qualidade de leitura do QR Code em impressoras térmicas de baixa resolução (203 dpi)] → Definir dimensionamento otimizado (tamanho ~130px a 150px centralizado, alto contraste preto e branco e margem limpa) para garantir leitura instantânea por câmeras de celular.
