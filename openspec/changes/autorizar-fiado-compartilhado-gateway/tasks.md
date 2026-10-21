## 1. Contratos e inventário de invariantes

- [x] 1.1 Ler proposal.md, design.md e as duas delta specs; inventariar todos pontos que debitam fiado, alteram limite ou cancelam venda/pedido e registrar sua participação na mesma invariância central.
- [x] 1.2 Definir contratos versionados de concessão/consulta/fechamento de grant e reserva/consulta/commit/cancel de autorização, com identidades, epoch, hash canônico, erros de negócio e resposta idempotente.
- [x] 1.3 Definir parâmetros operacionais configuráveis (valor por cliente/loja, TTL de reserva, validade de grant e alertas) com feature desabilitada e sem orçamento por padrão; documentar limites e recebimentos que não repõem verba local.

## 2. Ledger e autorização central

- [x] 2.1 Criar migrações aditivas de grants, consumos e resultados idempotentes, com chaves por empresa, cliente, loja, Gateway e epoch; testar unicidade e preservação em retry.
- [x] 2.2 Implementar concessão e consulta transacionais sob lock do cliente, lendo saldo/política e descontando delegações de todas lojas; testar duas alocações concorrentes e resposta perdida.
- [x] 2.3 Integrar disponível líquido de delegações a todos débitos (HistoricoVendasAB, preços fixos/pedidos, FiadoAB) e à edição de limite, incluindo limite zero e redução incompatível.
- [x] 2.4 Implementar consumo autenticado de grant junto à venda/débito central em uma transação, sem reaprovar crédito ordinário já garantido; validar id/hash/valor/escopo e duplicatas por múltiplos canais.
- [x] 2.5 Implementar fechamento/checkpoint e devolução idempotentes; proibir liberação central por TTL, timeout, perda de ledger ou ausência de resposta.
- [x] 2.6 Integrar cancelamento de venda dependente do débito original e recebimentos sem recarga implícita; manter pendências independentes de reconciliação auditáveis e comprometidas.

## 3. Arbitragem durável no Gateway

- [x] 3.1 Estender GatewayDatabase com grants/autorizações/operações e migração segura; garantir precisão monetária, tenant e persistência de concessão antes do uso.
- [x] 3.2 Implementar reserva e teste de disponível em uma transação SQLite serializada; verificar dois terminais concorrentes sobre mesmo orçamento.
- [x] 3.3 Implementar commit com transação única SQLite abrangendo autorização, consumo e GatewayEvents/outbox, adaptando composição do EventStore; provar que falha parcial não deixa venda sem crédito ou crédito sem evento.
- [x] 3.4 Implementar consulta/replay por autorização e SaleEventId, cancel de reserva, expiração e corrida commit/cancel; não permitir cancel de reserva liberar venda COMMITTED.
- [x] 3.5 Reusar dispatcher/CloudEndpointSyncClient para consumir e fechar grants com backoff e identidade estável; confirmar que sincronizar consumo não repõe livre local.
- [x] 3.6 Implementar validação de prazo/política e relógio regressivo com bloqueio seguro e reconciliação de compromissos anteriores.

## 4. Segurança e ciclo de instalação

- [x] 4.1 Aplicar autenticação e autorização por empresa/loja/terminal/operador nas novas rotas, derivando escopo de credenciais e recusando spoofing no body.
- [x] 4.2 Implementar evidência verificável de commit por canal autenticado servidor-servidor Gateway/cloud e grant/epoch provisionado; testar comprovante forjado pelo terminal.
- [x] 4.3 Impedir bypass por POST direto à cloud ou versão antiga em loja habilitada; preservar clientes legados somente em lojas ainda não ativadas.
- [x] 4.4 Proteger chave de instalação vinculada ao host e base por ACL, garantir instância única e diferenciar restart normal de restore/migração; bloquear crédito na recuperação sem cloud e sem fechamento do ledger anterior.
- [x] 4.5 Adaptar documentação/scripts existentes de instalação Windows do caixa principal com auto-start/recovery, TLS, firewall LAN, armazenamento persistente e runbook de restore sem promoção automática ou reemissão de verba incerta.

## 5. Integração PDV e visibilidade

- [x] 5.1 Integrar SaleOutboxAdapter ao fluxo de reservar/confirmar no Gateway para todos terminais de loja habilitada, inclusive online, cobrando apenas parcela fiado e persistindo intenção antes do commit.
- [x] 5.2 Implementar recuperação após timeout/reload por mesma autorização/EventId; concluir UI/recibo apenas após resultado durável conhecido e recuperar venda confirmada sem criar duplicata.
- [x] 5.3 Integrar SyncEngine/forwarder/outbox com comprovantes e emissor primário Gateway para fiado; preservar payload/hash e idempotência quando cloud e Gateway receberem mesmo evento.
- [x] 5.4 Ajustar CustomerSyncAdapter e apresentação para separar saldo informativo de orçamento autorizável; remover cache local como fallback decisório em loja habilitada.
- [x] 5.5 Exibir insuficiência, orçamento ausente/vencido, Gateway indisponível e resultado incerto; manter escolha de outras formas de pagamento e recuperação de intenção pendente sem duplicar venda.
- [x] 5.6 Estender dashboard/SignalR e auditoria com livre/reservado/consumido, grants, pendências e motivos de bloqueio; garantir que broadcast perdido não afete autorização.

## 6. Implantação e recuperação operacional

- [x] 6.1 Implementar ativação opt-in com inventário de terminais/versões, drenagem de fiado legado, ausência de pendências e concessão inicial cloud; testar terminal ausente impedindo ativação.
- [x] 6.2 Implementar desativação segura bloqueando novas reservas, reconciliando commits e fechando grants antes de retornar ao legado; preservar ledger quando rollback não puder concluir.
- [x] 6.3 Documentar política de bloqueio remoto sob partição, verba indisponível à cloud, recebimentos sem recarga automática e ação operacional quando caixa principal/serviço estiver indisponível.

## 7. Validação de integridade e aceite

- [x] 7.1 Testar concorrência real em SQLite e banco central: duas reservas 70 em 100; grant 60 e compra cloud 60 em limite 100; duas lojas/Gateways sobre o mesmo cliente; invariantes após cada interleaving.
- [x] 7.2 Testar perdas de resposta em concessão/reserva/commit/consumo/fechamento, reinício após gravação, duplicatas pelos dois canais e conflito de hash; exigir uma venda, um débito e um consumo.
- [x] 7.3 Testar reserva expirada, cancel concorrente, cancel antes de consumo cloud, recebimento offline, política bloqueada/redução de limite, prazo de grant e relógio regressivo.
- [x] 7.4 Testar tenant/loja/terminal forjados, comprovante fabricado, versões antigas e bypass direto; verificar ausência de consumo e de vazamento de saldo.
- [~] 7.5 Executar smoke Windows com dois terminais, internet seletivamente indisponível, fechamento da UI principal, restart do serviço e recuperação de backup antigo; exigir bloqueio seguro no restore e continuidade das outras formas de pagamento. **PENDENTE: requer dois computadores reais na rede da loja. Bloqueio de backup e instância única validados em CreditInstallationGuardTests.**
- [x] 7.6 Rodar suítes existentes relevantes API/Gateway/frontend e registrar comandos/resultados; verificar todas scenarios das specs e migração/rollback antes de habilitar piloto.
- [x] 7.7 Após implementação e revisão, executar graphify update . e openspec validate autorizar-fiado-compartilhado-gateway --strict; preservar artefatos/políticas de agentes e registrar limitações operacionais remanescentes.
