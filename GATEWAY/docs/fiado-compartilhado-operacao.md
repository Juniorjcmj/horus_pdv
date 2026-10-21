# Runbook: fiado compartilhado pelo Gateway

Este runbook orienta instalação, ativação, operação, recuperação e rollback do fiado compartilhado. O Gateway decide localmente apenas dentro de orçamento previamente concedido pela cloud. O saldo exibido no PDV não concede crédito.

## Pré-requisitos e inventário

Antes de habilitar uma loja:

1. Inventarie todos os terminais, suas versões, a loja e os vínculos gerenciais de operador. Cada operador autorizado deve estar vinculado a uma única loja; usuários sem vínculo ficam bloqueados para fiado quando qualquer loja migrar.
2. Atualize todos os terminais e o Gateway para versões compatíveis. Drene e reconcilie o fiado legado de todos os terminais, inclusive os que estejam temporariamente fora da rede. Terminal ausente, versão incompatível ou venda pendente impede ativação.
3. Confirme com a gestão o orçamento explícito por cliente e loja. A configuração começa sem verba concedida; não derive grant de cache ou saldo local.
4. Explique que indisponibilidade do Gateway bloqueia fiado compartilhado. O operador pode escolher outra forma de pagamento.

## Instalar e proteger o serviço

Instale o Gateway no caixa principal como Serviço do Windows, usando `GATEWAY/install-service.ps1` em PowerShell elevado. Defina os identificadores corretos de empresa/loja, porta, caminho persistente do banco e credenciais de cloud. O serviço tem inicialização automática e recuperação configurada para reiniciar após falhas. O processo é independente da UI do PDV; fechar o aplicativo do caixa principal não deve encerrar o serviço.

O instalador padrão cria `GATEWAY/gateway-data/horus-gateway.db`, registra inicialização automática, recovery e uma regra de firewall TCP na porta escolhida. Restrinja a regra à LAN da loja e aos terminais autorizados. Configure e valide HTTPS/TLS para o tráfego dos terminais e mantenha o canal cloud autenticado. O script aceita certificado HTTPS por `GATEWAY_HTTPS_CERT`; HTTP não deve ser considerado configuração final segura em produção.

O banco SQLite é o ledger persistente de grants e autorizações; mantenha-o no disco local do host e preserve-o em reinícios normais. Antes de ativar crédito, configure e verifique ACLs que permitam leitura/escrita somente à conta do serviço e aos administradores operacionais necessários. A validação dessas ACLs é requisito de implantação; não presuma que o instalador as configura. Proteja também certificado, token e segredos de serviço. Não copie a chave/identidade de instalação para outro host.

Após a instalação, valide serviço em execução, acesso LAN, TLS, autenticação cloud, persistência do banco, firewall restrito e recuperação após falha. Confira o painel e logs do Gateway. Para manutenção, verifique que eventos pendentes foram reconciliados antes de parar ou atualizar o serviço.

## Parâmetros e operação diária

Os padrões implementados são `CreditEnabled=false`, `CreditEpoch` vazio e `CreditReservationSeconds=120`, limitado automaticamente ao intervalo de 15 a 300 segundos. Grant não é concedido por padrão. Grants têm validade máxima de 24 horas; a expiração bloqueia novas autorizações locais, mas não devolve orçamento à cloud.

Monitore serviço, conectividade, horário do host, grants e seus vencimentos, reservas, commits, eventos pendentes e erros de reconciliação. Horário regressivo ou suspeito deve suspender novas autorizações até sincronização. Quando a cloud estiver inacessível, compromissos já confirmados continuam registrados localmente para reconciliação; novas alocações não são possíveis. Não prometa revogação remota imediata durante uma partição.

Recebimentos não recarregam um grant existente. Recebimento offline só afeta o crédito utilizável depois do aceite central; novo orçamento exige alocação explícita da cloud. Redução de limite abaixo da dívida somada à verba delegada exige recolhimento e reconciliação prévios.

Se o Gateway estiver indisponível, não aprove fiado pelo cache ou por envio direto à cloud. O operador deve selecionar outra forma de pagamento. Não promova automaticamente outro terminal ou Gateway: não há alta disponibilidade automática.

## Backup, restauração e perda do ledger

Reinício normal reutiliza o banco durável e a mesma instalação. Backup, restauração, clonagem, migração para outro host ou perda do ledger exigem iniciar com crédito bloqueado. Não use uma cópia antiga para aprovar vendas offline.

Para recuperação, mantenha novas reservas bloqueadas, contate a cloud, reconcilie commits e pendências conhecidas, desative ou comprove o fechamento da instalação anterior e só então configure um novo epoch e retome o crédito com orçamento novo/validado. Nunca reemita verba ainda comprometida pela instalação antiga. Se o ledger foi perdido, a verba de estado desconhecido permanece retida; encaminhe a resolução para procedimento assistido, sem zerar o compromisso com base em snapshot ou backup incompleto.

## Ativação de uma loja

Ative somente depois de concluir o inventário de todos os terminais e versões, drenar e reconciliar todas as vendas legadas, validar vínculo único de cada operador autorizado à loja, verificar a proteção do serviço/banco e provisionar orçamento explícito na cloud. Ausência de terminal ou usuário sem vínculo impede fiado conforme a política de migração. Habilite `CreditEnabled` somente com `CreditEpoch` válido e grant obtido da cloud por operação autenticada. Faça o piloto e valide concorrência, reinício e reconciliação antes de ampliar a operação.

Todos os terminais da loja habilitada, inclusive o caixa principal e os que estejam online, devem usar o Gateway para vendas novas no fiado. A cloud pode usar somente a parcela não delegada. Não contorne o Gateway usando saldo local ou um caminho cloud direto.

## Provisionamento pelos contratos implementados

Os exemplos abaixo são modelos com placeholders; não os execute sem substituir valores e revisar o ambiente. Use credenciais de gerente para operações gerenciais e a credencial `tokenGateway` da loja para operações de Gateway. Não registre tokens, chaves ou permits em logs/documentos.

### 1. Instalar e registrar a identidade do Gateway

Defina as variáveis no processo de implantação, usando certificados e tokens provisionados para a instalação:

```powershell
$env:GATEWAY_HTTPS_CERT = "<caminho-absoluto-do-certificado-pfx>"
$env:GATEWAY_REGISTRATION_TOKEN = "<token-de-registro>"
$env:GATEWAY_CLOUD_TOKEN = "<tokenGateway-da-loja>"
.\install-service.ps1 `
  -CompanyId "<empresa>" `
  -StoreId "<loja>" `
  -GatewayId "<gw>" `
  -CreditEpoch "<epoch>" `
  -CreditEnabled
```

Para crédito habilitado, configure `CreditWitnessPath` com caminho absoluto obrigatório no serviço. O arquivo witness deve ficar em `ProgramData/HorusGatewayCredit/HorusGateway.witness`, fora do backup, protegido por DPAPI `LocalMachine` e ACL restrita a `SYSTEM` e Administradores. A instalação inicial cria `Revision0` somente quando o witness ainda não existe. Se já houver witness ou ledger não vazio em estado incompatível, o Gateway bloqueia em vez de resetar. Não edite nem recrie o witness como forma de recuperação; siga o procedimento de restauração com crédito bloqueado, cloud e novo epoch após reconciliação/desativação da instalação anterior.

Mantenha `OpenRegistration=false` e `RequireTerminalAuth=true`. A rota de provisionamento exige o cabeçalho `X-Registration-Token`. O cadastro de terminal que participará de crédito exige `provisionToken`; endereço IP isolado não comprova identidade.

### 2. Vincular operadores à loja

Com JWT de gerente, vincule cada operador autorizado por `PUT /api/gateway-credit/operator-store`:

```json
{
  "userId": "<usuario>",
  "storeId": "<loja>"
}
```

Repita para todos os operadores e confirme que cada um possui vínculo com uma única loja. Usuário sem vínculo fica bloqueado para fiado quando qualquer loja migra. A configuração `GatewayURL` é por loja, e `GET gateway-config` deriva o binding da identidade autenticada do usuário.

### 3. Preparar a loja e terminais

Com JWT de gerente, cadastre a loja em estado `PREPARING` por `PUT /api/gateway-credit/store`. O token informado deve estar vinculado à mesma loja:

```json
{
  "storeId": "<loja>",
  "gatewayId": "<gw>",
  "gatewayUrl": "https://<host>:5443",
  "epoch": "<epoch>",
  "tokenId": "<tokenId-da-credencial-da-loja>",
  "state": "PREPARING",
  "terminalIds": ["<terminal-1>", "<terminal-2>"],
  "initialBudgets": []
}
```

Cada terminal atualizado aprende a política e a URL da sua loja, recebe um permit aleatório autenticado com validade de 8 horas por `POST /operator-permit` e envia readiness, incluindo outbox e intents pendentes:

```json
{
  "terminalId": "<terminal-id>"
}
```

O Gateway sincroniza a cada 15 segundos; readiness local vence em 1 minuto e a visão cloud em 2 minutos. Aguarde readiness atual de todos os terminais inventariados e confirme outbox/intents drenados. Terminal ausente, antigo ou com fiado legado pendente impede ativação.

### 4. Ativar e conceder orçamento inicial

Somente depois do inventário e readiness completos, use `PUT /api/gateway-credit/store` com o mesmo escopo de loja/Gateway/epoch, estado `ACTIVE` e orçamento aprovado pela gestão:

```json
{
  "storeId": "<loja>",
  "gatewayId": "<gw>",
  "gatewayUrl": "https://<host>:5443",
  "epoch": "<epoch>",
  "tokenId": "<tokenId-da-credencial-da-loja>",
  "state": "ACTIVE",
  "terminalIds": ["<terminal-1>", "<terminal-2>"],
  "initialBudgets": [
    {
      "customerId": "<cliente>",
      "amountCents": "<valor-gerencial-em-centavos>",
      "operationId": "<id-estavel-da-operacao>",
      "expiresAt": "<ISO-8601-futuro-com-maximo-de-24h>"
    }
  ]
}
```

A transição para `ACTIVE` e as concessões iniciais ocorrem na mesma transação. Em caso de timeout ou resposta perdida, repita exatamente a mesma operação, identidade e conteúdo; não gere outro `operationId` para contornar resultado incerto. Grant não nasce de cache, e a alocação inicial é explícita.

Concessões posteriores usam `POST /grants` autenticado somente pelo `tokenGateway` da mesma loja/Gateway. Respeite o teto gerencial por cliente e loja e o disponível calculado contra a soma ainda delegada. Não automatize concessões com base em cache, saldo local ou recebimentos.

Para bloquear novas alocações de um cliente, gerente pode enviar `PUT /api/gateway-credit/customer-policy`:

```json
{
  "customerId": "<cliente>",
  "blocked": true
}
```

O bloqueio impede novas alocações; durante partição, ele não revoga instantaneamente verba já delegada nem compromissos válidos.

### 5. Dashboard e decisões incertas

O dashboard está em `/` e `/index.html`. A seção de crédito exige identificação e `apiKey` do terminal; não persista segredo em `localStorage` ou `sessionStorage`. `GET /api/gateway/credit/dashboard` exige autenticação. Atualizações SignalR de crédito atualizam a visão e não autorizam operações. O parâmetro `CreditLowBudgetAlertCents` tem padrão `0` e expõe no dashboard o limiar configurado para alerta de verba baixa; alerta informa, não concede orçamento.

Se o PDV apresentar resultado incerto, use as ações de recuperar ou cancelar somente para reservas ainda não confirmadas. Cancelamento de reserva nunca cancela autorização `COMMITTED`. Cancelamento de venda confirmada segue o fluxo central vinculado ao débito original; se a dívida já foi paga, qualquer devolução exige revisão operacional e não deve gerar estorno silencioso.

### 6. Drenar e desativar

No rollback, gerente envia `PUT /api/gateway-credit/store` em estado `DRAINING`, com a mesma identidade de loja/Gateway/epoch e `initialBudgets: []`:

```json
{
  "storeId": "<loja>",
  "gatewayId": "<gw>",
  "gatewayUrl": "https://<host>:5443",
  "epoch": "<epoch-atual>",
  "tokenId": "<tokenId-da-credencial-da-loja>",
  "state": "DRAINING",
  "terminalIds": ["<terminal-1>", "<terminal-2>"],
  "initialBudgets": []
}
```

O Gateway bloqueia novas autorizações, cancela reservas `RESERVED` não confirmadas, reconcilia commits e envia checkpoint de fechamento. Aguarde a confirmação cloud do fechamento/liberação antes de gerente alterar o estado para `DISABLED`. Não troque epoch enquanto houver grant aberto, commit incerto ou verba sem fechamento confirmado.

## Rollback

1. Bloqueie novas reservas e autorizações na loja.
2. Resolva reservas em aberto; consulte operações de resultado incerto e entregue todos os commits ainda pendentes.
3. Feche cada grant e aguarde confirmação da cloud de que todos os consumos foram reconciliados e a parcela não consumida foi devolvida.
4. Só após o fechamento confirmado desabilite a exigência de comprovante do Gateway e retorne ao fluxo legado.

Se houver partição, timeout, commit incerto ou grant não conciliado, mantenha o bloqueio e a verba retida. Não reative o legado enquanto houver operações ou grants sem resolução.

## Verificações de implantação e evidência

Os itens de inventário, TLS, restrição de firewall à LAN, ACL do SQLite, reinício com UI fechada, concorrência entre terminais, restauração supervisionada e reconciliação devem ser validados no ambiente de implantação/piloto antes de ativar a loja. A documentação dos requisitos e a presença de scripts/configurações não comprovam que um smoke test Windows ou uma validação em ambiente real tenha sido executado. Registre os resultados e responsáveis no procedimento operacional da implantação.
