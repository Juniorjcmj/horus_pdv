## 1. Backend: Endpoints, Segurança e Regras Fiscais

- [x] 1.1 Criar endpoint `GET /api/usuario/supervisores` no [`UsuarioController`](file:///e:/PROJETOPDV/horus_pdv/API/NETCORE/Controllers/Usuario/UsuarioController.cs) retornando apenas `{ id, name, role }` dos usuários ativos com papel `administrador` ou `gerente` da empresa atual, com acesso liberado para perfis `caixa` e `atendente`.
- [x] 1.2 Implementar método de validação de credencial em [`HorusSecurityStore`](file:///e:/PROJETOPDV/horus_pdv/API/NETCORE/Repositories/DatabaseAccess/HorusSecurityStore.cs) (`ValidateSupervisorCredentials(string supervisorId, string password, string companyId)`) utilizando `PasswordHasher.Verify`.
- [x] 1.3 Adicionar busca flexível de documento fiscal em [`DocumentoFiscalAB`](file:///e:/PROJETOPDV/horus_pdv/API/NETCORE/Repositories/DatabaseAccess/DocumentoFiscalAB.cs) e endpoint `GET /api/nfce/buscar/{codigo}` em [`NfceController`](file:///e:/PROJETOPDV/horus_pdv/API/NETCORE/Controllers/Fiscal/NfceController.cs) suportando número de venda, número da nota ou chave de 44 dígitos.
- [x] 1.4 Criar endpoint `POST /api/nfce/{id}/cancelar-com-supervisor` em [`NfceController`](file:///e:/PROJETOPDV/horus_pdv/API/NETCORE/Controllers/Fiscal/NfceController.cs) que valida a senha do supervisor, executa o cancelamento na SEFAZ via `fiscalProvider.CancelarAsync` e atualiza o status para `Cancelado`.
- [x] 1.5 Implementar estorno automático das quantidades dos itens no estoque ([`EstoqueAB`](file:///e:/PROJETOPDV/horus_pdv/API/NETCORE/Repositories/DatabaseAccess/EstoqueAB.cs)) e marcar o registro da venda correspondente como cancelada.
- [x] 1.6 Gravar trilha de auditoria do cancelamento no banco (`DocumentosFiscais`) registrando o supervisor autorizador, operador do caixa e justificativa.

## 2. Frontend: Camada de Serviços

- [x] 2.1 Adicionar método `listSupervisores()` em `usuarioService.ts` consumindo `/api/usuario/supervisores`.
- [x] 2.2 Adicionar métodos `buscarPorCodigo(codigo: string)` e `cancelarComSupervisor(id: string, payload: CancelarComSupervisorPayload)` em [`fiscalService.ts`](file:///e:/PROJETOPDV/horus_pdv/FRONTEND/src/services/api/fiscalService.ts).

## 3. Frontend: Modal de Cancelamento no PDV (`PdvNfceCancelModal`)

- [x] 3.1 Criar componente [`PdvNfceCancelModal.tsx`](file:///e:/PROJETOPDV/horus_pdv/FRONTEND/src/components/Admin/PdvNfceCancelModal.tsx) com input inicial de código/chave e busca acionada com Enter.
- [x] 3.2 Implementar card de resumo da nota localizada (número, série, valor total, protocolo, data e validação de status `Autorizado`).
- [x] 3.3 Implementar dropdown (`<select>`) com os gerentes/administradores ativos e input protegido de senha.
- [x] 3.4 Implementar botões de motivos sugeridos com 1 clique (ex: "Desistência da compra pelo cliente") e campo de texto livre com validação de 15 caracteres mínimos da SEFAZ.
- [x] 3.5 Implementar diálogo pós-cancelamento oferecendo a impressão sob demanda do comprovante de cancelamento da NFC-e na impressora térmica.

## 4. Frente de Caixa (`SalesStartPage`)

- [x] 4.1 Adicionar botão "Cancelar NFC-e" no cabeçalho do PDV e atalho de teclado `Alt+C` (ou `F7`) em [`SalesStartPage.tsx`](file:///e:/PROJETOPDV/horus_pdv/FRONTEND/src/pages/Admin/SalesStartPage.tsx).
- [x] 4.2 Isolar a abertura e fechamento do modal para não descartar ou alterar produtos no carrinho de venda em andamento.
- [x] 4.3 Integrar o layout HTML de impressão do comprovante de cancelamento (dados da nota cancelada, protocolo SEFAZ e supervisor).

## 5. Validação e Testes

- [x] 5.1 Testar busca de NFC-e por número de venda, número de nota e chave de 44 dígitos com a tecla Enter.
- [x] 5.2 Testar rejeição de senha incorreta do gerente e autorização bem-sucedida com a senha correta.
- [x] 5.3 Validar a homologação na SEFAZ, o estorno automático das quantidades no estoque e a impressão opcional do comprovante térmico.
