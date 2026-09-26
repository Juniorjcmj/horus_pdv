## Why

Pela legislação fiscal brasileira (Ajuste SINIEF 19/2016 e regulamentos estaduais da SEFAZ), a NFC-e (modelo 65) possui prazo limite de 30 minutos para cancelamento direto através de evento (`110111`). Transcorrido esse prazo, a SEFAZ rejeita o cancelamento, exigindo obrigatoriamente a emissão de uma NF-e (Modelo 55) de Entrada por Devolução/Estorno referenciando a chave de acesso de 44 dígitos da NFC-e original para anular o débito fiscal e reverter a movimentação mercantil. Atualmente, o Horus PDV emite NF-e Modelo 55 apenas para saídas normais (`tpNF = 1`, `finNFe = 1`), não possuindo suporte à tag `<NFref>`, tipo de entrada e finalidade de devolução (`finNFe = 4`).

## What Changes

- **Suporte Fiscal a NF-e de Devolução (Modelo 55)**: Atualização do provedor fiscal (`ZeusFiscalProvider`) para suportar emissão com Tipo de Operação Entrada (`tpNF = 0 / tnEntrada`), Finalidade de Devolução (`finNFe = 4 / fnDevolucao`), vinculação de notas referenciadas no grupo `<NFref>` (`refNFe`) e mapeamento automático de CFOP de devolução de venda (`1.202` / `1.411`).
- **Endpoint de Emissão de Devolução Referenciada**: Criação do endpoint `POST /api/nfe/emitir-devolucao-nfce` no backend, recebendo o ID ou chave da NFC-e de origem e a justificativa/destinatário, gerando o documento fiscal modelo 55 de entrada referenciado.
- **Estorno Mercantil e Auditoria**: Registro do documento de devolução vinculado à venda/NFC-e original, estorno de estoque caso ainda não tenha sido devolvido e registro no log de auditoria fiscal.
- **Apoio no Modal de Cancelamento do PDV**: No componente `PdvNfceCancelModal`, quando a NFC-e consultada tiver mais de 30 minutos de emissão ou caso a SEFAZ responda com rejeição de prazo expirado, o sistema alerta o operador e oferece a opção de encaminhar para a emissão de NF-e de devolução.
- **Interface de Emissão de Devolução**: Visualização dos dados da devolução (dados do destinatário/consumidor, chave referenciada e itens) antes da transmissão para a SEFAZ.

## Capabilities

### New Capabilities
- `fiscal/nfe-devolucao`: Emissão de NF-e Modelo 55 de Entrada por Devolução com referência à chave de acesso de 44 dígitos da NFC-e original, anulação fiscal e estorno de estoque.

### Modified Capabilities
<!-- Nenhuma especificação de comportamento existente foi alterada -->

## Impact

- **Backend Fiscal**: `API/NETCORE/Services/Fiscal/ZeusFiscalProvider.cs`, `IFiscalProvider.cs`, `API/NETCORE/Models/Requests/NfeRequest.cs`.
- **Controllers & Repositórios**: `API/NETCORE/Controllers/Fiscal/NfeController.cs`, `API/NETCORE/Repositories/DatabaseAccess/DocumentoFiscalAB.cs`.
- **Frontend**: `FRONTEND/src/services/api/fiscalService.ts`, `FRONTEND/src/components/Admin/PdvNfceCancelModal.tsx` e tela/modal de visualização de NF-e.
- **Dependências**: Biblioteca Zeus.Net.NFe já instalada no projeto.
