# Tasks

> Antes de cada grupo, leia a decisão correspondente em `design.md`, o spec do módulo em `specs/` e os arquivos .NET
> de referência do Context. Mensagens, códigos HTTP, nomes e formatos de campos (inclusive os valores pt-BR em texto)
> e ordem das listas são copiados literalmente do .NET; em dúvida, vale a gravação do contrato. Trabalhe na branch
> `spring-api`, em `JAVA/api`.
>
> **Regra do dono do produto: não altere nada em `API/NETCORE/` nem em `GATEWAY/`.** A .NET só é lida e usada como
> ambiente de gravação.
>
> **Ciclo de cada grupo de rotas:**
> 1. escrever os cenários de contrato do grupo;
> 2. gravar contra a API .NET (`contrato/ambiente-dotnet/subir.sh` e o `GravadorDoContrato`);
> 3. implementar com os testes unitários e web;
> 4. rodar o comparador do grupo até ficar verde.
>
> Antes de rodar `mvn verify`, feche qualquer API Java iniciada pelo `.jar` de `target/`, que prende o arquivo.

## 1. Base: seed, numeração, hash e origem provisória

- [x] 1.1 Ampliar `contrato/seed/seed-contrato.sql` e `reset-contrato.sql` com os dados do D11: turnos, clientes com
  limite, regras de emissão, pedido aberto, vendas finalizada, cancelada e com nota autorizada, e uma empresa sem
  emitente para as vendas; verificar aplicando seed e reset duas vezes no ambiente .NET sem erro e com os 19 grupos
  de contrato existentes ainda verdes
- [x] 1.2 Criar `plataforma/numeracao/NumeradorDocumentos` (D4: venda, fiscal, inventário, `MANDATORY`), passar o
  `InventarioService` a usá-lo e criar o `NumeracaoUnicaTest`; verificar com IT de vendas concorrentes (duas threads,
  números distintos e consecutivos), IT da sequência fiscal (linha criada e números 1, 2), o grupo `inventario` do
  contrato ainda verde e o teste de arquitetura falhando num cálculo de `MAX(SaleNumber)` colocado de propósito fora
  do numerador (e removido depois)
- [x] 1.3 Portar o `HorusPayloadHash` (D3) e acrescentar ao `contrato/vetores-dotnet/Program.cs` os vetores de venda,
  abertura, fechamento e movimento (acentos, aspas, `+`, desconto, vários pagamentos, decimais com zeros à direita,
  `xmlNfceContingencia`); verificar com o `CompatibilidadeDotnetTest` comparando todos os vetores gerados pela .NET e
  um teste que confere o hash do `PayloadHash` do Caixa JavaFX para os mesmos dados
- [x] 1.4 Estender o `RegistradorEstoque` com a origem provisória e o `vincularOrigem` (porta de
  `NovaOrigemProvisoria`/`VincularOrigemAsync`) e o `LoteOperacoes` com `consumirVendaSeguro` e `estornarVendaSeguro`
  (porta do `LoteAB`, `REQUIRES_NEW`, sem lançar exceção); verificar com IT (movimento com origem provisória vinculado
  ao número, consumo FEFO de dois lotes, estorno devolvendo aos lotes de origem, falha no consumo não desfazendo a
  operação principal) e os grupos `movimentacao` e `lote` ainda verdes
- [x] 1.5 Nova tentativa da baixa de lotes (D13): migração Flyway `V4` com tabela de pendência só da Java (venda,
  tipo consumo/estorno, tentativas, próxima tentativa, estado); falha do `consumirVendaSeguro`/`estornarVendaSeguro`
  grava a pendência; rotina periódica com backoff e limite (depois do limite fica marcada para revisão e é logada como
  erro), idempotente (não consome duas vezes a mesma venda); verificar com IT de falha simulada no primeiro consumo e
  sucesso na nova tentativa sem consumo em dobro, e o `EsquemaEquivalenteIT` ainda verde

## 2. Caixa (`api/Caixa`)

- [x] 2.1 Escrever e gravar `cenarios/caixa.json`: status sem turno e com turno, sessão (dono, outro operador,
  gerente), movimentos por período, abrir (normal, já aberto, com `eventId`, replay, conflito), fechar (com e sem
  diferença, replay), sangria e reforço (replay, conflito), 401 e isolamento entre empresas; verificar que a gravação
  existe para todos os cenários
- [x] 2.2 Implementar o módulo `venda/caixa` (D7 e D3: status, `CanSell`/`BlockReason`, multi-caixa, esperado em
  dinheiro, idempotência com placeholder em `ProcessedEvents`, auditoria, `EnsureVendaPermitida`); testes: web
  (perfis e mensagens), IT (dois operadores com turnos abertos, fechamento com esperado igual ao da .NET para fundo +
  vendas em dinheiro + reforço − sangria, replay sem segundo turno, conflito 409) e o grupo `caixa` do comparador verde

## 3. Registro de venda (`api/HistoricoVendas`)

- [x] 3.1 Escrever e gravar `cenarios/vendas.json` para registrar: venda simples, vários pagamentos com troco, total
  divergente, item sem preço, sem itens, caixa fechado online e offline, estoque insuficiente online, ruptura offline,
  fiado (sem cliente, cliente inexistente, limite estourado, ok), preço diferente sem e com autorização, autorizar
  preço (ok, senha errada, produto inexistente, preço zero), replay e conflito de idempotência, `orderNumber` com
  itens divergentes, `xmlNfceContingencia` (divergência D10) e `creditAuthorization`; verificar a gravação de todos
- [x] 3.2 Implementar `venda/registro` (D2: transação na ordem do .NET, `SaleMoney`, preço autorizado, fiado com
  leituras do crédito do Gateway, baixa pelo registrador, numeração, IDs derivados, `ProcessedEvents` com
  `ResponsePayload` legível pela .NET, auditorias, lotes e rupturas pós-commit) e a autorização de preço; testes:
  Mockito das regras de dinheiro, IT (venda baixa estoque e grava itens, pagamentos e movimentação com origem
  `Venda #n`; venda recusada não deixa nada; replay não baixa de novo; `ResponsePayload` gravado pela .NET em fixture
  é lido e devolvido), web (perfis, 401) e o grupo `vendas` do comparador verde nos cenários de registro; travas de produto em ordem de código (D13) com IT de vendas cruzadas sem deadlock
- [x] 3.3 Acrescentar a `cenarios/vendas.json` lista (por data, por turno próprio, de outro operador como caixa e como
  gerente), detalhe (com e sem documento fiscal, inexistente) e imprimir; implementar e verificar com o comparador
  verde e IT do filtro por turno

## 4. Fronteira fiscal (`fiscal/fila`)

- [x] 4.1 Implementar a decisão de emissão e a pendência (D6: `AvaliarEmissaoParaVenda`, `Enfileirar` com
  `proximoNumeroFiscal`, 503 na falha) ligadas ao registro e à finalização de pedido; testes: IT do intervalo (3 vendas
  em dinheiro com intervalo 3: só a terceira emite e o contador zera), forma "sempre emitir", CPF na nota, replay sem
  avançar o contador, pendência única por venda, venda cancelada recusada, e unitário do 503 com `data` igual ao .NET
- [x] 4.2 Implementar a `FilaFiscalMinima` (D6: retomar vendas sem documento, nota de venda cancelada não emitida com
  o motivo do .NET e auditoria `NfceNaoEmitida`, erro de empresa sem emitente, demais paradas,
  `quack.fiscal.fila-minima.ativa`); testes: IT de cada ramo com `Clock` fixo, IT com a propriedade desligada sem
  mudar nenhuma nota, e verificação de que nenhuma classe da fila chama provedor fiscal ou rede

## 5. Cancelamento e correção

- [x] 5.1 Escrever e gravar os cenários de cancelamento (ok com estorno, justificativa curta, senha errada,
  inexistente, já cancelada, corrigida, nota autorizada, nota pendente com aviso, fiado estornado, fiado já pago em
  parte), lista e auditoria de cancelamentos (perfis, filtros `de`/`ate`/`venda`, tentativa negada na trilha) e
  correção (com gerente, sem gerente com e sem perfil financeiro, justificativa curta, sem produtos); verificar a
  gravação de todos
- [x] 5.2 Implementar o cancelamento e a trilha (D5: `Negar`, mapa de código para HTTP, transação na ordem do .NET,
  estorno de estoque, fiado e lotes); testes: IT (estoque volta pelo registrador com tipo `Devolucao`; fiado
  estornado; lotes de volta; dois cancelamentos simultâneos estornam uma vez só; recusa grava `VendaCancelamentos`
  negada e `AuditLog`) e o comparador verde nos cenários de cancelamento; travas de produto em ordem de código (D13) com IT de vendas cruzadas sem deadlock
- [x] 5.3 Implementar a correção (porte literal do `CorrigirVendaAsync`, número pelo `NumeradorDocumentos`, decisão
  fiscal só com emitente, sem derrubar a correção); testes: IT (venda original "corrigida", nova venda com os itens
  restantes, estoque dos removidos de volta) e o comparador verde nos cenários de correção

## 6. Pedidos e fiado

- [x] 6.1 Escrever e gravar `cenarios/pedido.json` (criar, perfis, listar abertos, consultar, inexistente, finalizar
  com preço congelado após mudança de preço, finalizar cancelado, finalizar duas vezes, cancelar) e implementar
  `venda/pedido` (D8, `RegistrarComPrecosFixos`); verificar com IT (criar não muda estoque; finalizar baixa e cobra o
  preço do pedido; segunda finalização devolve a mesma venda) e o grupo `pedido` do comparador verde
- [x] 6.2 Escrever e gravar `cenarios/fiado.json` (receber com perfis e valores inválidos, extrato com período,
  devedores com busca, resumo) e implementar `venda/fiado` (D8, spec `vendas/fiado-conta-corrente`); verificar com IT
  (recebimento atualiza o saldo de forma atômica e gera auditoria) e o grupo `fiado` do comparador verde

## 7. Aceite pelos clientes JavaFX

- [ ] 7.1 Rodar os E2E do Caixa JavaFX contra a API Java com o comando do D11 (`subir.sh` antes, `descer.sh` depois) e
  corrigir até ficarem verdes; para cada falha, gravar o cenário de contrato que faltava e corrigir pelo comparador;
  verificar pelo relatório do Surefire do `JAVA/caixa` (todos os E2E menos `NfceCancelE2ETest`, inclusive o
  `OfflineE2ETest`)
- [ ] 7.2 Rodar o comando D10 do Quack Estoque sem as exclusões de inventário (D11) e corrigir até ficarem verdes;
  verificar pelo relatório do Surefire do `JAVA/estoque` com `InventarioAppE2ETest` e os 14 métodos de
  `InventarioE2ETest` verdes

## 8. Integração

- [ ] 8.1 Rodar `mvn -B verify` em `JAVA/api` com todos os grupos de contrato (os 19 anteriores e os 4 novos) e toda
  a suíte verde; verificar pelo relatório do Failsafe/Surefire
- [ ] 8.2 Atualizar `JAVA/api/README.md` (rotas portadas na Fase 4, divergências do D10, fila fiscal mínima e sua
  propriedade, comandos de aceite do Caixa e do Estoque) e `contrato/README.md` (grupos novos, empresa sem emitente,
  dados do seed); atualizar a nota do aceite em `openspec/changes/api-java-produtos-estoque/tasks.md` (ou no archive,
  se já arquivada) e a linha da Fase 4 em `docs/migracao-api-springboot.md`; verificar que os comandos documentados
  funcionam

## Workflow follow-up

- Arquivar a change com `/opsx:archive` depois do aceite.
- Abrir a proposta da fase 4b (modo loja e sincronização por Change Tracking, D9).
