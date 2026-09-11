## Why

Atualmente, ao concluir uma venda no PDV ou solicitar a impressão pelo histórico de vendas, o sistema emite apenas um "CUPOM NAO FISCAL" gerencial. Embora o backend já processe a NFC-e (modelo 65) autorizada com chave de acesso, protocolo e URL do QR Code oficial pela SEFAZ, o operador e o consumidor não recebem o DANFE NFC-e em bobina térmica (80mm) em conformidade com as normas técnicas da SEFAZ e a Lei Federal nº 12.741/2012.

## What Changes

- **Layout e Impressão Térmica do DANFE NFC-e (80mm)**: Criação de gerador de impressão em conformidade com o Manual de Padrões Técnicos do DANFE NFC-e:
  - Cabeçalho emitente: Razão Social, Nome Fantasia, CNPJ, Inscrição Estadual e endereço completo.
  - Título oficial: "DANFE NFC-e - Documento Auxiliar da Nota Fiscal de Consumidor Eletrônica" e aviso "Não permite aproveitamento de crédito de ICMS".
  - Itens vendidos com código, descrição, quantidade, unidade, valor unitário e valor total.
  - Totais, formas de pagamento (incluindo pagamentos múltiplos e troco) e valor estimado dos tributos.
  - Identificação fiscal: Número da NFC-e, Série, Data/Hora de Emissão e Protocolo de Autorização SEFAZ.
  - Chave de Acesso formatada em blocos de 4 dígitos e endereço URL de consulta da SEFAZ.
  - Identificação do Consumidor (CPF/CNPJ ou "CONSUMIDOR NÃO IDENTIFICADO").
  - QR Code gráfico oficial impresso na bobina térmica.
- **Fluxo Integrado no PDV (`SalesStartPage`)**:
  - Ao confirmar a venda, o frontend aguarda brevemente (polling ágil de até 3 segundos) a autorização da NFC-e pelo outbox worker.
  - Se autorizada: abre automaticamente a impressão do DANFE oficial com QR Code.
  - Se houver contingência ou atraso da SEFAZ: permite imprimir o comprovante correspondente sem travar o operador de caixa.
- **Reimpressão do DANFE 80mm**:
  - No `SalesHistoryPage` e `FiscalPage`, inclusão de opção direta para imprimir o DANFE térmico de 80mm em qualquer venda que já possua NFC-e autorizada.

## Capabilities

### New Capabilities
- `fiscal/danfe-nfce-impressao`: Renderização e impressão térmica de 80mm do DANFE NFC-e oficial com QR Code, Chave de Acesso, Protocolo SEFAZ e Numeração Fiscal.

### Modified Capabilities

## Impact

- **Frontend**:
  - Atualização/criação de utilitário de impressão para gerar o HTML do DANFE com QR Code embutido em Base64/SVG.
  - Atualização de `SalesStartPage.tsx` para sincronizar o fechamento da venda com a prévia do DANFE.
  - Adição de botão de impressão no `DanfePreviewModal.tsx` e chamada direta no menu do `SalesHistoryPage.tsx`.
- **Backend / APIs**:
  - O endpoint `GET /api/Nfce/{saleNumber}` já fornece todos os dados necessários (`NumeroNf`, `Serie`, `ChaveAcesso`, `Protocolo`, `DhAutorizacao`, `QrCodeUrl`). Nenhuma quebra de contrato na API.
