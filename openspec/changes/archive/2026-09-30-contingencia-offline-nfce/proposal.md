## Why

Atualmente, a emissão de NFC-e (Modelo 65) depende de comunicação online síncrona com os servidores da SEFAZ estadual. Em situações em que a SEFAZ se encontra fora do ar (falha de comunicação, timeouts) ou em que a loja física perde o acesso à internet, o operador do caixa fica impedido de concluir a venda com documento fiscal válido, correndo o risco de paralisar as vendas no balcão ou descumprir a legislação tributária.

A legislação do SPED / SEFAZ prevê expressamente a **Contingência Offline da NFC-e (`tpEmis = 9`)**, na qual o documento fiscal é gerado, assinado digitalmente com o Certificado Digital A1 da empresa e impresso na bobina térmica com a tarja obrigatória "EMITIDA EM CONTINGÊNCIA - Pendente de autorização", devendo o lote ser transmitido para autorização na SEFAZ assim que a conexão for restabelecida (no prazo de até 24 horas).

Implementar essa contingência de ponta a ponta — cobrindo tanto a indisponibilidade dos servidores da SEFAZ quanto a queda total de internet da loja física via Local Gateway — garante operação ininterrupta na frente de caixa, conformidade fiscal e experiência de venda sem atrito para o consumidor.

## What Changes

- **Suporte Completo ao Tipo de Emissão Offline (`tpEmis = 9`)**:
  - Inclusão dos campos obrigatórios da SEFAZ no XML: `tpEmis = 9`, Data e Hora de Entrada em Contingência (`dhCont`) e Justificativa (`xJust`, mínimo 15 caracteres).
  - Geração da chave de acesso de 44 dígitos com o tipo de emissão 9 na posição correta.
  - Geração do QR Code oficial de contingência da SEFAZ (incluindo digest value / assinatura do XML e token CSC).
- **Contingência por Queda da SEFAZ (Backend Cloud)**:
  - Detecção automática de indisponibilidade da SEFAZ (timeout, erros 5xx de webservice ou status inoperante) ou ativação manual preventiva pelo administrador/gerente.
  - Geração e assinatura digital imediata do XML no backend via certificado A1, persistindo o documento no banco de dados com status `ContingenciaPendente`.
  - Retorno imediato do XML assinado e QR Code ao PDV para impressão térmica, sem travar a thread de caixa.
- **Contingência por Queda de Internet na Loja (Local Gateway)**:
  - Quando o terminal do PDV estiver sem internet, o PDV aciona o `HorusGateway` na LAN para gerar e assinar localmente a NFC-e em contingência (`tpEmis = 9`) utilizando o certificado digital A1 provisionado no Gateway.
  - O Gateway devolve o XML assinado e dados do DANFE com QR Code para impressão térmica local na estação do caixa.
  - O Gateway armazena a NFC-e no SQLite local como pendente de sincronização para envio à Cloud e SEFAZ assim que a internet for restabelecida.
- **Transmissão e Autorização Posterior (Outbox Worker)**:
  - `NfceOutboxWorker` (na Cloud e sincronizado pelo Gateway) monitora documentos com status `ContingenciaPendente`.
  - Assim que a SEFAZ estiver online, o lote é transmitido automaticamente para obter o protocolo de homologação SEFAZ (`cStat 100`) sem alterar a chave nem os dados originais da nota.
  - Tratamento inteligente de duplicidades (`cStat 539`), rejeições e conciliação de protocolo.
- **Frontend: Impressão Térmica de 80mm e Alertas Visuais**:
  - Atualização do gerador do DANFE térmico ([`danfePrint.ts`](file:///e:/PROJETOPDV/horus_pdv/FRONTEND/src/utils/danfePrint.ts)) para exibir com destaque oficial: **"EMITIDA EM CONTINGÊNCIA - Pendente de autorização"**.
  - Impressão em 2 vias caso configurado ou exigido pela UF do contribuinte.
  - Indicador de status visual no PDV informando que o caixa está operando em contingência offline.
  - Painel Fiscal com visão de documentos em contingência pendente de transmissão e contador regressivo do prazo legal de 24h.

## Capabilities

### New Capabilities
- `fiscal/contingencia-offline-nfce`: Emissão, assinatura com certificado digital A1, geração de QR Code offline, impressão de DANFE térmico com tarja obrigatória e transmissão posterior de NFC-e em contingência offline (`tpEmis = 9`) tanto no backend Cloud quanto no Local Gateway.

### Modified Capabilities
<!-- Nenhuma especificação existente teve seus requisitos de negócio alterados; esta é uma capacidade aditiva sobre o módulo fiscal e PDV. -->

## Impact

- **Backend (`API/NETCORE`)**:
  - `Services/Fiscal/ZeusFiscalProvider.cs`: parametrização exata de `dhCont`, `xJust`, chave de 44 dígitos e QR Code versão 2 em contingência offline.
  - `Repositories/DatabaseAccess/DocumentoFiscalAB.cs`: enfileiramento direto em contingência, métodos de atualização de protocolo mantendo chave original.
  - `Services/Fiscal/NfceOutboxWorker.cs`: despacho ordenado dos documentos em contingência para a SEFAZ.
  - `Controllers/Fiscal/NfceController.cs`: endpoint de transmissão forçada e consulta de documentos pendentes de transmissão.
- **Local Gateway (`GATEWAY`)**:
  - Módulo de assinatura e emissão fiscal local em contingência (`tpEmis = 9`) no `HorusGateway`, persistindo no SQLite e despachando para a Cloud quando a rede voltar.
- **Frontend (`FRONTEND`)**:
  - `src/utils/danfePrint.ts`: renderização da tarja oficial de contingência e QR Code offline.
  - `src/pages/Admin/SalesStartPage.tsx`: detecção de falha de conexão/SEFAZ e fluxo de impressão direta do comprovante de contingência.
  - `src/pages/Admin/FiscalPage.tsx`: listagem de notas em contingência pendente e ação de envio em lote.
