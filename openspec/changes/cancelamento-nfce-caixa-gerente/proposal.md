## Why

No dia a dia do varejo na frente de caixa (PDV), quando ocorre desistência imediata do cliente, devolução ou erro operacional no fechamento da venda, a NFC-e emitida precisa ser cancelada com rapidez perante a SEFAZ dentro do prazo regulamentar (geralmente até 30 minutos).

Atualmente, o cancelamento formal só é disponibilizado na tela de retaguarda administrativa fiscal (`FiscalPage.tsx`), exigindo que o operador possua privilégios administrativos globais de gerente ou administrador. Operadores de caixa não podem ter perfil de gerente por razões de conformidade e controle interno. Esta mudança introduz o fluxo nativo de cancelamento de NFC-e na frente de caixa: o caixa digita o código da nota, tecla Enter, seleciona o gerente responsável em um dropdown (`select`) e, mediante a conferência da senha desse gerente, o cancelamento é homologado na SEFAZ com estorno de estoque da venda, registro de auditoria e opção de impressão do comprovante.

## What Changes

- **Ponto de Acesso de Cancelamento no PDV**:
  - Botão visível no cabeçalho da frente de caixa (`SalesStartPage.tsx`) com a identificação "Cancelar NFC-e".
  - Atalho de teclado rápido (`Alt+C` ou `F7`) para acionamento direto sem necessidade do mouse.
- **Busca e Identificação da NFC-e**:
  - Campo de digitação de código da NFC-e com suporte a número da venda, número da nota ou chave de acesso de 44 dígitos (podendo ser digitada ou bipada por leitor de código de barras).
  - Ao teclar Enter, busca e exibe o resumo da nota (valor total, série, número, data de emissão, protocolo e cliente) validando se ela está no status `Autorizado`.
- **Seleção e Autorização do Gerente**:
  - Dropdown (`select`) listando os gerentes e administradores ativos da empresa atual.
  - Campo de senha exclusivo do gerente selecionado para validação das credenciais no backend.
  - Justificativa SEFAZ: botões de motivos sugeridos rápidos com 1 clique (ex: "Desistência da compra pelo cliente", "Erro na forma de pagamento") e campo de texto livre com validação mínima de 15 caracteres.
- **Endpoint de Supervisores para Frente de Caixa**:
  - Endpoint `GET /api/usuario/supervisores` acessível por caixas e atendentes, retornando apenas `{ id, name, role }` dos gerentes/administradores ativos para alimentar o `select` sem expor dados restritos (CPF, hashes).
- **Cancelamento com Validação de Supervisor e Efeitos no Sistema**:
  - Endpoint `POST /api/nfce/{id}/cancelar-com-supervisor` permitindo ao caixa submeter o cancelamento acompanhado de `supervisorId` e `supervisorPassword`.
  - Validação da senha do gerente via `PasswordHasher.Verify` no servidor sem deslogar o caixa.
  - Homologação do cancelamento perante a SEFAZ via `IFiscalProvider.CancelarAsync`.
  - **Estorno de Estoque e Venda**: Reversão automática das quantidades vendidas de volta ao estoque dos produtos e atualização do status da venda para cancelada.
  - **Registro de Auditoria**: Gravação do supervisor que autorizou, operador de caixa, data/hora e justificativa.
  - **Comprovante de Cancelamento**: Diálogo/botão oferecendo a impressão do comprovante térmico de cancelamento da NFC-e para o cliente ou fechamento de caixa.

## Capabilities

### New Capabilities
- `pdv/cancelamento-nfce`: Fluxo completo de busca, conferência visual, autorização gerencial via select e senha, cancelamento homologado de NFC-e na frente de caixa, estorno de estoque e emissão de comprovante.

### Modified Capabilities
<!-- Nenhuma especificação de requisito preexistente é alterada -->

## Impact

- **Frontend**:
  - `FRONTEND/src/pages/Admin/SalesStartPage.tsx`: Botão no cabeçalho, atalho `Alt+C`/`F7` e integração com o modal.
  - `FRONTEND/src/components/Admin/PdvNfceCancelModal.tsx`: Modal especializado para PDV com busca por código, exibição de resumo, select de gerente, senha, motivos rápidos e diálogo de impressão de comprovante.
  - `FRONTEND/src/services/api/fiscalService.ts`: Métodos de busca flexível e cancelamento com supervisor.
  - `FRONTEND/src/services/api/usuarioService.ts`: Método de listagem de supervisores ativos.
- **Backend**:
  - `API/NETCORE/Controllers/Fiscal/NfceController.cs`: Endpoints de cancelamento autorizado por gerente e busca flexível.
  - `API/NETCORE/Controllers/Usuario/UsuarioController.cs`: Endpoint de supervisores acessível para perfil caixa.
  - `API/NETCORE/Repositories/DatabaseAccess/HorusSecurityStore.cs`: Método de validação de credencial gerencial.
  - `API/NETCORE/Repositories/DatabaseAccess/DocumentoFiscalAB.cs`: Trilha de auditoria e integração com estorno de estoque da venda vinculada.
  - `API/NETCORE/Repositories/DatabaseAccess/EstoqueAB.cs` / `HistoricoVendasAB.cs`: Estorno de itens e status cancelado.
