## Purpose

Permitir autorização compartilhada de fiado entre caixas da loja usando crédito previamente reservado pela cloud, com segurança sob concorrência, desconexão e recuperação de falhas.

## ADDED Requirements

### Requirement: Orçamento delegado sem duplicar crédito
O sistema SHALL reservar atomicamente orçamento por empresa, cliente, loja e instalação do Gateway, descontando a parcela ainda delegada do disponível central. Toda concessão SHALL ser idempotente e vinculada à política e identidade válidas; cache ou snapshot isolado não autoriza fiado.

#### Scenario: Cloud e Gateway em conexão mista
- **WHEN** um cliente tem limite 100, dívida zero e orçamento 60 concedido ao Gateway
- **THEN** compras cloud fora desse orçamento podem consumir no máximo 40 e o Gateway pode comprometer no máximo 60, mesmo sem internet.

#### Scenario: Duas lojas concorrem por alocação
- **WHEN** dois Gateways da mesma empresa solicitam 70 cada para cliente com disponível global 100
- **THEN** o total concedido não excede 100 e a identidade da loja não cria um novo limite do cliente.

#### Scenario: Resposta de concessão perdida
- **WHEN** a cloud concede orçamento mas o Gateway repete a mesma operação após timeout
- **THEN** recebe a concessão original sem reservar duas vezes.

#### Scenario: Cache sem orçamento
- **WHEN** existe cliente em cache com saldo recente, mas não existe concessão válida
- **THEN** o fiado compartilhado é recusado com motivo de orçamento indisponível.

### Requirement: Autoridade comum entre terminais
O sistema SHALL exigir autorização do Gateway em todas vendas fiado novas de loja habilitada, inclusive em terminais online e no caixa principal. Ausência do serviço ou de orçamento SHALL bloquear fiado compartilhado sem bloquear por esse motivo outras formas de pagamento.

#### Scenario: Terminal online tenta contornar Gateway
- **WHEN** terminal da loja habilitada envia venda fiado nova diretamente à cloud sem autorização verificável
- **THEN** a venda é recusada sem débito, mesmo que haja disponível central.

#### Scenario: Serviço indisponível
- **WHEN** o Gateway fica inacessível durante fechamento de venda
- **THEN** o operador pode escolher outra forma de pagamento e não obtém aprovação fiado pelo cache local.

### Requirement: Reserva atômica e confirmação durável
O sistema SHALL reservar crédito atomicamente, vincular autorização a cliente, parcela fiado, terminal e venda, e confirmar a venda e seu consumo de crédito de forma durável e indivisível antes de informar conclusão ao PDV. Valores SHALL respeitar precisão monetária do domínio.

#### Scenario: Disputa entre caixas
- **WHEN** dois caixas reservam 70 simultaneamente sobre orçamento livre 100
- **THEN** exatamente uma reserva é aprovada e a outra é recusada por insuficiência.

#### Scenario: Pagamento misto
- **WHEN** venda de 100 contém 30 em fiado e 70 em outra forma
- **THEN** apenas 30 são reservados e consumidos pelo protocolo de crédito.

#### Scenario: Falha após aprovação da reserva
- **WHEN** o terminal falha após reservar e antes de confirmar a venda
- **THEN** a venda não é considerada finalizada e a reserva permanece consultável até cancelamento ou expiração.

### Requirement: Idempotência e recuperação de resultado incerto
O sistema SHALL oferecer consulta por identidade estável da operação/venda e devolver o resultado original a retries iguais; colisões de identidade com conteúdo diferente SHALL retornar conflito. O PDV SHALL recuperar resultado desconhecido antes de gerar substituição ou informar conclusão.

#### Scenario: Resposta do commit perdida e reinício
- **WHEN** o Gateway confirmou a venda, perdeu a resposta e serviço/PDV reiniciam
- **THEN** consultar ou repetir o mesmo commit recupera a mesma venda confirmada sem novo consumo.

#### Scenario: Conteúdo alterado
- **WHEN** a mesma identidade de autorização ou venda é repetida com cliente ou valor diferente
- **THEN** o sistema recusa por conflito e preserva o resultado e o comprometimento anteriores.

### Requirement: Cancelamento e expiração conservadores
O sistema SHALL liberar reserva local não confirmada por cancelamento ou expiração atômicos e SHALL impedir confirmação tardia. Venda confirmada SHALL exigir cancelamento de venda vinculado ao débito original; cancelamento ou vencimento não liberam automaticamente verba delegada na cloud.

#### Scenario: Corrida entre cancel e commit
- **WHEN** cancelamento e confirmação competem pela mesma reserva
- **THEN** somente uma transição vence e uma venda confirmada nunca fica com crédito liberado pela operação de cancelamento da reserva.

#### Scenario: Reserva expirada
- **WHEN** confirmação chega depois da expiração persistida da reserva
- **THEN** é recusada e exige nova autorização antes de concluir a venda.

#### Scenario: Cancelamento chega antes da venda à cloud
- **WHEN** cancelamento de venda confirmada é entregue antes do respectivo consumo
- **THEN** o sistema aguarda a dependência e não credita o cliente antes de registrar o débito original.

### Requirement: Reconciliação de autorização previamente garantida
O sistema SHALL consumir autorização válida de forma idempotente na mesma transação da venda e do débito central, reduzindo o orçamento delegado pelo mesmo valor. O sistema SHALL preservar crédito previamente garantido sem reprovar novamente por saldo ordinário alterado após a aprovação; erros independentes SHALL manter pendência auditável e comprometimento.

#### Scenario: Mesma venda entregue por dois caminhos
- **WHEN** terminal e Gateway entregam a mesma venda autorizada à cloud
- **THEN** existe uma venda, um débito e um consumo do grant, com resultado idêntico nos retries.

#### Scenario: Limite alterado após commit
- **WHEN** a política central muda depois de um commit local válido
- **THEN** a reconciliação honra a autorização garantida e não rejeita por uma nova avaliação do limite atual.

#### Scenario: Recebimento offline
- **WHEN** o cliente paga dívida em terminal sem aceite central
- **THEN** o recebimento pendente não amplia orçamento local nem concede crédito reutilizável.

### Requirement: Fechamento e devolução comprovados
O sistema SHALL devolver verba central somente após fechar o grant para novas operações, resolver reservas e reconciliar todos consumos até um ponto de fechamento verificável. Timeout, expiração, falta de conectividade ou perda de ledger SHALL manter verba comprometida.

#### Scenario: Grant vence durante partição
- **WHEN** vence o prazo para novas autorizações e a cloud não conhece os consumos locais finais
- **THEN** novas operações locais bloqueiam e a cloud não reutiliza a parcela delegada até fechamento comprovado.

#### Scenario: Fechamento repetido
- **WHEN** fechamento já aceito é reenviado após resposta perdida
- **THEN** o mesmo resultado é retornado e a parcela não consumida é liberada uma única vez.

### Requirement: Identidade e isolamento verificáveis
O sistema SHALL validar empresa, loja, cliente, Gateway, epoch, terminal e permissão de operador a partir de identidade autenticada. Comprovantes não verificáveis ou de outro escopo SHALL ser recusados sem consumo; dados do body não substituem autenticação.

#### Scenario: Tentativa entre empresas ou lojas
- **WHEN** terminal apresenta autorização de outra empresa ou loja
- **THEN** o servidor recusa a operação sem revelar saldo nem modificar o ledger de outro escopo.

#### Scenario: Comprovante fabricado
- **WHEN** terminal envia um identificador de grant real sem evidência válida de commit do Gateway autorizado
- **THEN** a cloud recusa consumo e não aceita o simples campo de aprovação enviado pelo terminal.

### Requirement: Operação independente e recuperação segura
O Gateway SHALL executar no caixa principal como serviço independente da UI e preservar decisões após reinício normal. Restore, clonagem, troca de máquina ou perda de ledger SHALL iniciar com crédito bloqueado até recuperação supervisionada com a cloud, sem reemitir verba antiga ainda comprometida.

#### Scenario: Fechar aplicativo do caixa principal
- **WHEN** o aplicativo PDV principal é fechado e o serviço continua ativo
- **THEN** outros terminais continuam usando as mesmas regras e o mesmo orçamento compartilhado.

#### Scenario: Backup anterior ao consumo
- **WHEN** uma instalação tenta restaurar backup que antecede vendas autorizadas
- **THEN** não pode aprovar offline usando o saldo antigo e depende de recuperação central, mantendo a verba antiga retida enquanto houver incerteza.

### Requirement: Mudanças de política e observabilidade
O sistema SHALL distinguir orçamento local, saldo central informativo, reservas e pendências, auditar decisões e mostrar motivo de bloqueio. Redução de limite abaixo de dívida mais verba delegada SHALL exigir recolhimento prévio; bloqueio remoto SHALL impedir novas alocações e ser aplicado pelo Gateway quando recebido, sem promessa de revogação instantânea offline.

#### Scenario: Redução incompatível
- **WHEN** gerente tenta reduzir limite abaixo da dívida somada à verba ainda delegada
- **THEN** recebe impedimento e indicação de recolher/reconciliar orçamentos antes de efetivar a redução.

#### Scenario: Bloqueio durante partição
- **WHEN** um cliente é bloqueado na cloud enquanto o Gateway está offline com grant válido
- **THEN** a cloud não concede novos grants, mantém a verba já comprometida e informa que a política local será atualizada ao reconectar ou o prazo local encerrará novas autorizações.

### Requirement: Ativação e rollback sem mistura insegura
O sistema SHALL habilitar o modo compartilhado apenas após conciliar fiado legado de todos terminais, verificar versões compatíveis e provisionar orçamento. Rollback SHALL resolver e fechar grants antes de liberar o modo anterior.

#### Scenario: Terminal legado ausente
- **WHEN** há terminal não inventariado, versão incompatível ou fiado legado pendente
- **THEN** a ativação é impedida até resolver a condição, sem criar orçamento baseado em saldo incompleto.

#### Scenario: Desativação sem conexão
- **WHEN** operador tenta desativar o recurso com commits ou grants ainda não conciliados
- **THEN** novas autorizações podem ser bloqueadas, mas a verba não é liberada nem o fiado legado reativado até fechamento seguro.
