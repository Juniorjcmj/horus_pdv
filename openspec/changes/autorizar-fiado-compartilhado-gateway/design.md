## Context

Ver proposal.md para motivação. Evidências verificadas em 20/10/2026 (linhas podem mudar):

- `GATEWAY/src/HorusGateway/Controllers/GatewayController.cs:67,140`: ingestão autentica/valida e chama AppendAsync; não decide crédito. `Services/CloudEndpointSyncClient.cs:39,118`: encaminha SALE_CREATED para HistoricoVendas com reenvioOffline.
- `FRONTEND/src/application/sales/SaleOutboxAdapter.ts:100,203`: validateCredit sobre cliente local e atualização de saldo local. `application/customers/CustomerSyncAdapter.ts` considera débitos pendentes locais, sem arbitragem entre caixas. `infrastructure/synchronization/SyncEngine.ts:161,175,208` mantém caminho cloud online e forwardPendingToGateway offline. Os tasks antigos de local-gateway não descrevem integralmente o forwarder atual.
- `API/NETCORE/Repositories/DatabaseAccess/HistoricoVendasAB.cs:237-262,406-431`: locks na linha Clientes e SaleMoney.Credit, inclusive caminho de preços fixos/pedido. `FiadoAB.cs:51-78` também valida débito. `Services/Shared/SaleMoney.cs:46` bloqueia limite zero, contradizendo spec antiga.
- `GATEWAY/src/HorusGateway/Data/GatewayDatabase.cs`: SQLite durável, WAL, GatewayEvents, ProcessedEvents, CompanyId/EventId, PayloadHash e Terminals. `Program.cs:21` já usa UseWindowsService; `install-service.ps1` é base da instalação no caixa principal.

## Goals / Non-Goals

**Goals:** impedir consumo duplicado do limite sob concorrência e partição; dar decisão local comum com confirmação durável; assegurar recuperação e reconciliação auditáveis. Conservar semântica monetária decimal/cêntimos e débito somente da parcela fiado.

**Non-Goals:** coordenação distribuída genérica, eleição automática de líder, promoção offline de backup, sincronização de estoque ou alterações TEF. Não prometer revogação remota instantânea durante partição.

## Decisions

### 1. Autoridade por orçamento reservado, não por conectividade

A cloud mantém o limite e saldo canônicos por `(CompanyId, CustomerId)`. `StoreId` segmenta a delegação, nunca duplica o limite de um cliente da empresa. Clientes de empresas distintas permanecem independentes. Novos registros cloud de fiado, em qualquer loja, usam `disponivelCloud = max(0, limite - saldoDevedor - soma(orçamentoAindaDelegado))`, sob o mesmo lock/transação da alteração de crédito. Todos caminhos de débito, pedido, edição de limite e consumo delegado participam da invariância; não basta modificar somente um endpoint de vendas.

A alocação cloud cria GrantId único vinculado a CompanyId/CustomerId/StoreId/GatewayId/epoch e valor aprovado. O valor é configuração gerencial explícita por cliente/loja, limitada ao disponível central; padrão sem alocação. Snapshot versionado de cliente/política e grant são retornados pela mesma operação atômica. Snapshot isolado, data de última sincronização ou cache Dexie não concedem crédito. Retry da alocação usa OperationId/hash e retorna o mesmo grant, inclusive se a resposta se perder.

Dentro do grant, `livreLocal = valorConcedido - consumoLocalConfirmado - reservasAtivas`. Consumo confirmado é cumulativo durante a vida do grant; conciliar não repõe livre local. Cloud reduz orçamento delegado e aumenta saldoDevedor na mesma transação para cada consumo, mantendo constante a soma comprometida. Exemplo: limite 500, dívida 100, grant 200 -> cloud dispõe 200; consumo local 80 -> após conciliar, dívida 180, delegação 120, disponível cloud continua 200. Recebimentos cloud liberam espaço para nova alocação, nunca regeneram implicitamente um grant existente. Recebimento offline não aumenta crédito utilizável até aceite central.

Alternativas descartadas: snapshot recente permite double spend; alternar autoridade pelo online/offline permite split-brain; autoridade exclusiva de toda empresa reduz desnecessariamente disponibilidade de outras lojas. Escrow permite cloud e vários Gateways simultâneos sem dividir o mesmo recurso.

### 2. Todos caixas habilitados passam pelo Gateway

O Gateway do caixa principal é um serviço independente do aplicativo PDV; o próprio caixa principal usa a mesma API e regras dos demais. Todos terminais da loja habilitada, mesmo online, reservam/confirmam fiado nele. A API exige comprovante correspondente para vendas novas dessa loja. Fora dela, cloud pode vender apenas com a parcela não delegada. Não há fallback silencioso para saldo local ou cloud se Gateway cair. Outras formas de pagamento continuam conforme fluxo existente.

O caminho existente de sincronização pode enviar venda pela cloud ou pelo Gateway; ambos devem representar o mesmo EventId, autorização e payload canônico. Para a primeira versão, eventos fiado confirmados têm Gateway como emissor primário; retries diretos do terminal devem consultar/aguardar comprovante durável ou obter resultado idempotente da cloud, sem efetuar um segundo débito. UI distingue saldo informativo, orçamento utilizável local e resultado incerto. SignalR só invalida/atualiza a visão; nunca autoriza.

### 3. Protocolo reserva / commit / cancel com resultado consultável

Contratos novos (nomes finais ajustáveis às convenções): reservar, consultar por AuthorizationId/OperationId, confirmar com venda completa, cancelar reserva; cloud conceder orçamento, consultar grant, consumir venda autorizada e fechar/devolver grant. Todos incluem versão de contrato, identidade autenticada, id estável e hash canônico. A reserva fixa cliente, parcela fiado positiva, moeda, terminal e SaleEventId pré-gerado; commit fixa hash da venda. Alterar valor/cliente exige cancelar e criar nova reserva. Repetição igual retorna resultado original; mesma chave com conteúdo diferente retorna conflito.

Máquina de estados: RESERVED -> COMMITTED ou CANCELLED ou EXPIRED; COMMITTED é terminal para autorização (cancelamento de venda é evento separado). SQLite serializa teste de disponível e gravação da reserva na mesma transação; commit grava autorização, débito local e GatewayEvents/outbox na mesma transação/conexão, sem chamar AppendAsync depois como operação independente. Reserva pode ter TTL configurável e ser liberada atomicamente se ainda RESERVED; commit tardio após expiração falha. Enquanto estado é incerto, caixa consulta a mesma chave e não finaliza nem cria outra venda. Falha após reservar/antes commit não constitui venda finalizada.

O PDV persiste intenção local antes de pedir commit e só considera fiado concluído após confirmar COMMITTED durável no Gateway. Se resposta perder, reinício recupera por SaleEventId e hidrata resultado/outbox/recibo, sem segunda venda. Se cancel competir com commit, uma transição vence; cancel de COMMITTED não libera orçamento. Fiscal/recibo/entrega seguem o fluxo existente somente após decisão conhecida, com recuperação da venda já confirmada caso a UI falhe. Não se exige transação distribuída entre Dexie e SQLite.

### 4. Reconciliação e cancelamentos

Cloud valida identidade do emissor, grant, epoch, loja, cliente, valor, vínculo SaleEventId e integridade; credencial do terminal não pode fabricar consumo de grant. O consumo é registrado por AuthorizationId e EventId únicos por empresa, com hash e resultado duráveis, na mesma transação que venda e débito. O comprovante deve ser verificável por credencial de Gateway e vínculo ao grant (autenticação do canal servidor-servidor e registro central), não um booleano confiado do payload do PDV.

Para autorização válida dentro do orçamento, não executar novamente a aprovação ordinária pelo saldo atual: o recurso já estava reservado. Mudanças posteriores de limite não tornam retroativamente inválida a aprovação. Manter demais validações de integridade/fiscal; falhas independentes viram pendência de reconciliação auditável, retendo comprometimento, não liberação automática ou desaparecimento de venda. Duplicatas por caminhos diferentes retornam resultado anterior sem segundo débito.

Cancelamento de reserva não consumida devolve apenas livre local. Cancelamento de venda confirmada segue fluxo central existente, ligado ao débito original e idempotente; não recicla consumo cumulativo local. Se cancelamento chegar antes da venda, aguarda/reordena por dependência, nunca credita cliente antes do débito. Depois do aceite central, nova capacidade depende de novo grant. Devolução de grant exige estado local fechado para novas reservas/commits, resolução de reservas, envio de todos consumos e confirmação cloud do checkpoint; só a parcela comprovadamente não consumida é liberada. Timeout ou vencimento nunca devolve automaticamente orçamento central.

### 5. Segurança, vencimento e recuperação

Reusar identidade provisionada, TLS e credenciais de terminais, adicionando autorização de operações e binding CompanyId/StoreId/GatewayId/epoch do lado servidor. Não confiar identidade do body. Credenciais de operador e permissões de fiado continuam obrigatórias. Registros auditam motivo de rejeição, valor, grant, autorização, venda, terminal e operador, sem segredos.

Grant tem prazo de novas autorizações e política versionada. Expiração bloqueia novas reservas e commits ainda não confirmados, mas aceita reconciliação de commits duráveis anteriores. Cloud retém parcela delegada até fechamento explícito. Relógio atrasado detectado suspende novas autorizações e exige sincronização; prazo limita exposição operacional, não fundamenta liberação central. Redução de limite abaixo de dívida+delegações é recusada até recolher/conciliar orçamento; bloqueio remoto impede novas alocações e é aplicado localmente quando recebido, preservando compromissos válidos anteriores. Limite zero não permite novas alocações.

GatewayId/epoch são estáveis por instalação provisionada. SQLite e chave de instalação protegidos por ACL; serviço único por instalação e chave vinculada ao host, fora do backup portável. Restart normal reutiliza ledger durável. Restore, perda de ledger, migração/clonagem ou troca de máquina inicia em modo crédito bloqueado e exige contato cloud, novo epoch e reconciliação. Fencing central sozinho não impede máquina antiga offline de aprovar: nunca reemitir orçamento pendente nem ativar substituto com parcela do grant antigo até comprovar fechamento e desativação do anterior. Se ledger foi perdido, manter valor comprometido e resolução assistida, sem resetar para o snapshot. Não oferecer HA automático. Definir/runbook verificar restauração suportada em ambiente real; cópias arbitrárias de disco fora do procedimento não são mecanismo suportado de failover.

### 6. Dados e áreas de integração

Adicionar ledger central de grants/consumos/fechamentos idempotentes e tabelas SQLite equivalentes de grants, autorizações e operação/hash/resultado, com índices compostos de tenant e integridade referencial. Reusar GatewayDatabase e EventStore com composição transacional. Persistir grant antes de usá-lo; resposta perdida é recuperável. Reusar CloudEndpointSyncClient/dispatcher para entrega e backoff, estendendo metadados de crédito sem alterar hash do evento já aceito. Payloads antigos continuam legados e nunca ganham autorização por inferência.

API: HistoricoVendasAB, FiadoAB, caminho de pedidos/preços fixos, cadastro de limite e cancelamentos; frontend: SaleOutboxAdapter, CustomerSyncAdapter, SyncEngine e forwarder. Gateway: controllers, serviços, base SQLite, dashboard/SignalR e provisionamento. Testar contratos em ambos canais de entrega. Expor orçamento livre/reservado/consumido, vencimento, última sincronização, pendências e bloqueios; evitar confundir saldo consolidado com crédito local disponível.

## Risks / Trade-offs

- [Disponível cloud menor enquanto verba está delegada] -> exibir valores separados e permitir recolhimento online seguro; tamanho por configuração gerencial, sem percentual inventado.
- [Bloqueio remoto durante partição não chega instantaneamente] -> orçamento/pré-prazo limitado, auditoria e preservação dos compromissos; documentar ao gerente antes de ativar.
- [Caixa principal desligado, LAN ou serviço indisponível] -> fiado compartilhado bloqueado; serviço com auto-start/recovery e orientação operacional, sem promover outro PDV automaticamente.
- [Backup antigo duplica capacidade] -> restore bloqueado, chave vinculada ao host, epoch e fechamento conservador; não liberar valor desconhecido.
- [Alteração incompleta dos caminhos cloud] -> inventariar todos débitos/limites e testes de concorrência, incluindo múltiplas lojas e pedido.

## Migration Plan

1. Entregar migrations aditivas e API compatível com contratos novos, crédito Gateway inicialmente desabilitado. Validar todos débitos contra delegações mesmo antes de habilitar primeira loja.
2. Atualizar Gateway/PDVs; instalar serviço existente no caixa principal com caminho de banco persistente, auto-start, TLS/firewall LAN e credenciais. Testar fechamento da UI sem parar serviço.
3. Para habilitar loja, drenar e reconciliar fiado legado de TODOS terminais, comprovar inventário/versões compatíveis e ausência de pendências. Terminal ausente impede ativação; não dar grant ignorando vendas antigas. Bloquear versão antiga/cliente não migrado para fiado após ativação.
4. Provisionar orçamento e snapshot numa operação cloud válida, testar concorrência e reinício no piloto, então habilitar por loja. Nenhum grant nasce de cache local.
5. Rollback: bloquear novas autorizações, resolver reservas e entregar commits, fechar grants confirmadamente e só então desabilitar exigência de comprovante/retornar ao legado. Preservar ledger e migrations; se offline ou resultado desconhecido, manter bloqueio e verba retida.

## Validation and acceptance

Testes unitários de dinheiro/estado são complementados por integração com SQLite real e banco central transacional: limite 100, duas reservas 70 -> exatamente uma aceita; grant 60 + compra cloud 60 sobre livre global 100 -> no máximo 100 comprometidos; dois Gateways/lojas concorrem pela mesma linha cliente. Exercitar resposta perdida em cada transição, reinício do serviço após commit, colisão de hash, duplicatas por dois caminhos, cancel antes/depois de commit, expiração, relógio regressivo, restore e tenant/loja falsificados. Ver scenarios das duas specs para contrato completo. Rodar suítes existentes de API/Gateway e frontend afetadas, mais smoke Windows com dois terminais e cloud isolada seletivamente. Após futura implementação, executar graphify update .; nesta proposta nenhum código é alterado.

## Open Questions

### Refinamento de integração verificado no apply

As sessões existentes identificam empresa e operador, sem loja confiável. A matrícula gerencial `UserCreditStores` vincula um operador a uma loja por vez; o middleware consulta o vínculo atual em cada requisição, sem confiar em `StoreId` do body. Em empresa com loja migrada, operador sem matrícula não pode usar fiado até matrícula. Operadores vinculados a lojas não habilitadas continuam usando o disponível cloud líquido. O token servidor-servidor existente fornece a loja do Gateway. A migração deve matricular todos operadores antes da ativação.

Inventário: `HistoricoVendasAB.RegistrarAsync` e `RegistrarComPrecosFixosAsync` bloqueiam a linha `Clientes`; ambos chamam `FiadoAB.RegistrarDebitoAsync`, que também bloqueia a mesma linha antes de atualizar dívida e inserir `FiadoMovimentos`. `ClienteAB.SalvarAsync` altera limite e deve usar esse mesmo lock. `FiadoAB.RegistrarCreditoAsync` reduz dívida sem recarregar grant. `HistoricoVendasAB.CancelarVendaComSupervisorAsync` e correção de venda dependem de venda persistida e estornam movimento original; `PedidoAB.CancelarAsync` cancela pedido aberto, sem débito financeiro. `PedidoController.Finalizar` usa preços fixos e precisa transportar a mesma autorização.

Parâmetros operacionais (valor por cliente/loja, TTL de reserva, validade de grant, retenção e alertas) serão definidos na configuração do piloto. Não alteram as invariantes e começam sem autorização de crédito até provisionamento explícito. Não há decisão arquitetural pendente para começar implementação.
