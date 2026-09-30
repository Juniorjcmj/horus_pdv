## 1. Backend: Zeus Fiscal Provider e Regras de Contingência

- [x] 1.1 Configurar geração de XML com `tpEmis = 9`, `dhCont` e `xJust` (validação mínima de 15 caracteres) no [`ZeusFiscalProvider.cs`](file:///e:/PROJETOPDV/horus_pdv/API/NETCORE/Services/Fiscal/ZeusFiscalProvider.cs).
- [x] 1.2 Implementar cálculo da chave de acesso de 44 dígitos com tipo de emissão 9 e geração de QR Code versão 2.0 com `cHashQRCode` (digest value) para contingência offline.
- [x] 1.3 Adicionar método `EmitirContingenciaAsync` no `IFiscalProvider` e `ZeusFiscalProvider` para assinar o XML localmente sem chamar webservices da SEFAZ, retornando o XML assinado, chave, digest value e URL do QR Code.

## 2. Backend: Fila de Documentos Fiscais e Outbox Worker

- [x] 2.1 Em [`DocumentoFiscalAB.cs`](file:///e:/PROJETOPDV/horus_pdv/API/NETCORE/Repositories/DatabaseAccess/DocumentoFiscalAB.cs), adicionar suporte a enfileirar diretamente em contingência (`Status = ContingenciaPendente`, `TpEmis = 9`) e gravar o XML assinado.
- [x] 2.2 No [`NfceOutboxWorker.cs`](file:///e:/PROJETOPDV/horus_pdv/API/NETCORE/Services/Fiscal/NfceOutboxWorker.cs), implementar transmissão prioritária dos documentos com status `ContingenciaPendente` enviando o XML original assinado à SEFAZ.
- [x] 2.3 Implementar tratamento de autorização (`cStat 100`) e resolução automática de duplicidade (`cStat 539`) via consulta de situação da chave (`consSitNFe`) sem alterar a chave ou criar nova venda.
- [x] 2.4 Criar endpoint `POST /api/nfce/contingencia/transmitir-pendentes` e `GET /api/nfce/contingencia/pendentes` no [`NfceController.cs`](file:///e:/PROJETOPDV/horus_pdv/API/NETCORE/Controllers/Fiscal/NfceController.cs).

## 3. Local Gateway: Emissão e Assinatura Offline na LAN

- [x] 3.1 Adicionar suporte a carregamento e validação de Certificado Digital A1 (.pfx) no `HorusGateway`.
- [x] 3.2 Criar serviço fiscal local no Gateway capaz de assinar XML de NFC-e com `tpEmis = 9` e gerar QR Code de contingência.
- [x] 3.3 Criar endpoint `POST /api/gateway/fiscal/nfce/contingencia` no Gateway recebendo os itens e pagamentos da venda, persistindo a NFC-e no SQLite local e retornando o XML assinado e dados do DANFE térmico para o PDV.
- [x] 3.4 Integrar envio das notas emitidas no Gateway para a Cloud via `CloudSyncDispatcher` quando a internet retornar.

## 4. Frontend: Impressão Térmica de 80mm e Fluxo no PDV

- [x] 4.1 Atualizar gerador de impressão térmica em [`danfePrint.ts`](file:///e:/PROJETOPDV/horus_pdv/FRONTEND/src/utils/danfePrint.ts) para renderizar a tarja oficial em destaque **"EMITIDA EM CONTINGÊNCIA - Pendente de autorização"**, motivo, data/hora e QR Code offline.
- [x] 4.2 Em [`SalesStartPage.tsx`](file:///e:/PROJETOPDV/horus_pdv/FRONTEND/src/pages/Admin/SalesStartPage.tsx), implementar detecção de timeout/erro da SEFAZ com fallback suave para emissão em contingência (com diálogo opcional ou automático de contingência) e impressão imediata.
- [x] 4.3 Integrar o PDV ao Local Gateway para emissão de NFC-e offline quando o navegador detectar ausência de internet.
- [x] 4.4 Em [`FiscalPage.tsx`](file:///e:/PROJETOPDV/horus_pdv/FRONTEND/src/pages/Admin/FiscalPage.tsx), criar aba/seção dedicada "Contingência Offline" exibindo notas pendentes de transmissão, contagem regressiva do prazo legal de 24h e botão "Transmitir Pendentes Agora".

## 5. Validação e Testes

- [x] 5.1 Testes unitários de montagem de chave de 44 dígitos com tipo 9 e validação de 15 caracteres mínimos de justificativa.
- [x] 5.2 Teste de emissão offline no backend Cloud com SEFAZ simulada fora do ar e impressão do DANFE com QR Code de contingência.
- [x] 5.3 Teste de emissão no Local Gateway sem internet na LAN e sincronização posterior.
- [x] 5.4 Teste de compilação geral (`dotnet build` e `npm run build`).
