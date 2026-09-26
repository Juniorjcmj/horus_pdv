## Context

O Horus PDV possui uma frente de caixa ([`SalesStartPage.tsx`](file:///e:/PROJETOPDV/horus_pdv/FRONTEND/src/pages/Admin/SalesStartPage.tsx)) operada comumente por usuários com papel `caixa` ou `atendente`. O cancelamento formal de NFC-e atualmente reside no módulo fiscal ([`FiscalPage.tsx`](file:///e:/PROJETOPDV/horus_pdv/FRONTEND/src/pages/Admin/FiscalPage.tsx)), protegido por `[HorusAuthorizeRoles("administrador", "gerente")]`.

Para viabilizar o cancelamento diretamente no caixa mantendo a conformidade fiscal e a segurança, é necessário permitir a delegação de autorização presencial (supervisão), onde o operador de caixa solicita a presença de um gerente, seleciona-o em um dropdown e valida a respectiva senha no servidor antes de disparar o cancelamento na SEFAZ.

Ver motivação detalhada em [`proposal.md`](file:///e:/PROJETOPDV/horus_pdv/openspec/changes/cancelamento-nfce-caixa-gerente/proposal.md) e requisitos em [`specs/pdv/cancelamento-nfce/spec.md`](file:///e:/PROJETOPDV/horus_pdv/openspec/changes/cancelamento-nfce-caixa-gerente/specs/pdv/cancelamento-nfce/spec.md).

## Goals / Non-Goals

**Goals:**
- Prover interface ágil e orientada a teclado na frente de caixa com botão fixo no cabeçalho ("Cancelar NFC-e") e atalho `Alt+C` (ou `F7`).
- Digitação/leitura do código da NFC-e (número da venda, número da nota ou chave de 44 dígitos) com acionamento ao teclar Enter.
- Exibir card resumo com os dados da nota localizada (número, série, valor total, protocolo, data e cliente) e validação de status (`Autorizado`).
- Disponibilizar `select` com os gerentes/administradores ativos da empresa e campo protegido de senha.
- Validar as credenciais do gerente no backend de forma segura via `PasswordHasher.Verify` sem alterar a sessão autenticada do caixa.
- Executar o cancelamento perante a SEFAZ via `IFiscalProvider.CancelarAsync` e registrar o gerente autorizador na auditoria.
- Estornar automaticamente o estoque dos itens vendidos e atualizar a venda para o status cancelada.
- Oferecer botões de motivos rápidos com 1 clique (ex: "Desistência da compra pelo cliente") mantendo o limite mínimo de 15 caracteres da SEFAZ.
- Oferecer diálogo para impressão sob demanda do comprovante de cancelamento na impressora térmica.

**Non-Goals:**
- Não altera a tela de retaguarda administrativa fiscal (`FiscalPage.tsx`), que continua funcionando para gerentes/administradores.
- Não contempla cancelamento de NF-e modelo 55 no caixa (foco exclusivo em NFC-e modelo 65).
- Não realiza estorno financeiro automático de máquinas de cartão POS externas (o operador segue o procedimento da maquininha).

## Decisions

### Decisão 1: Endpoint dedicado para listagem de supervisores (`GET /api/usuario/supervisores`)
- **Escolha**: Criar um endpoint leve e autorizado para perfil `caixa` que retorna apenas `{ id, name, role }` dos usuários ativos com papel `administrador` ou `gerente` da empresa atual.
- **Por que**: O endpoint existente `GET /api/usuario` expõe dados cadastrais sensíveis (CPF, e-mails, telefones, histórico) e exige papel de gerente/administrador. O novo endpoint atende ao princípio do menor privilégio.

### Decisão 2: Autorização por credencial gerencial na requisição de cancelamento
- **Escolha**: Criar endpoint `POST /api/nfce/{id}/cancelar-com-supervisor`, onde o caixa submete a requisição acompanhada de `supervisorId` e `supervisorPassword`.
- **Por que**: O operador de caixa não perde sua sessão de trabalho no PDV. O backend verifica se o supervisor pertence à mesma empresa, possui papel gerencial ativo e se a senha confere com o hash armazenado.

### Decisão 3: Busca flexível por Código de Venda, Número de Nota ou Chave de Acesso
- **Escolha**: No campo de busca do modal, aceitar busca por `saleNumber` (ex: `VENDA-123`), número da NFC-e (ex: `102`) ou chave de acesso de 44 dígitos (capturada por leitor de código de barras).
- **Por que**: Na frente de caixa, o cupom impresso pode conter o número da venda, o número da nota ou o código de barras da chave.

### Decisão 4: Componente de Modal Especializado para Frente de Caixa (`PdvNfceCancelModal`)
- **Escolha**: Criar componente dedicado para o PDV com foco automático, navegação facilitada e layout otimizado para teclado.

### Decisão 5: Estorno automático de estoque e cancelamento da venda
- **Escolha**: Ao confirmar o cancelamento da NFC-e na SEFAZ, o backend executa em transação o estorno das quantidades dos produtos no saldo de estoque (`EstoqueAB`) e atualiza o registro da venda (`Vendas`) para o status cancelado.
- **Por que**: Evita inconsistências de estoque entre a parte fiscal e o controle físico da loja, poupando o operador de realizar ajustes manuais de estoque após a desistência da compra.

### Decisão 6: Impressão opcional do comprovante térmico de cancelamento
- **Escolha**: Após a resposta positiva da SEFAZ, abrir um diálogo/botão perguntando se o operador deseja imprimir o comprovante de cancelamento.
- **Por que**: Muitos clientes ou procedimentos internos exigem o cupom de cancelamento como comprovante formal de estorno, enquanto em outros casos a impressão pode ser dispensada para economizar bobina.

## Risks / Trade-offs

- **[Risco] Bloqueio de conta do gerente por tentativas sucessivas de senha incorreta no caixa**:
  - *Mitigação*: Tratar falha de senha de supervisor com mensagem específica "Senha do gerente incorreta", registrando a tentativa no repositório de segurança padrão.
- **[Risco] Tentativa de cancelamento fora do prazo SEFAZ (ex: após 30 minutos)**:
  - *Mitigação*: Exibir aviso visual de prazo legal no modal e repassar detalhadamente a mensagem de rejeição oficial da SEFAZ caso o prazo tenha expirado.
- **[Risco] Interrupção acidental da venda em andamento no caixa**:
  - *Mitigação*: O modal de cancelamento de NFC-e é aberto em camada de diálogo (`z-layer-dialog`), permitindo fechamento com `Esc` sem descartar os itens que já estavam no carrinho da venda atual.
