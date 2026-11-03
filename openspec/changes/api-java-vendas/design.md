# Design

## Context

- **Regra do dono do produto: só a parte Java muda.** Nada em `API/NETCORE/` nem em `GATEWAY/` é alterado. A API .NET
  é a fonte da verdade (código e gravações do contrato) e o ambiente de gravação (`contrato/ambiente-dotnet`, porta 5270).
  Ferramentas de teste dentro de `JAVA/api` (como `contrato/vetores-dotnet`) podem mudar.
- **Pastas:** a API Java está em `JAVA/api`, o Caixa JavaFX em `JAVA/caixa`, o Quack Estoque em `JAVA/estoque`.
- **O que já existe em `JAVA/api` (Fases 1 a 3).** Leia a change `openspec/changes/api-java-produtos-estoque/`
  (design D1 a D11) e os archives `…-api-java-fundacao/` e `…-api-java-cadastros/`.
  - **Envelope e erros:** `ApiResponse`, `NegocioException` (400), `ConflitoException` (409), `GlobalExceptionHandler`.
  - **Segurança:** `UsuarioAutenticado`, `@PerfisPermitidos`, `ValidadorSupervisor` (porta do
    `ValidateSupervisorCredentials`), `AuditoriaService`, resolução de IP do cliente.
  - **Formatação:** `shared/Dinheiro` (equivalente ao `HorusMoneyFormat`), `FormatoDataDotNet`, `Clock` injetado
    (America/Sao_Paulo).
  - **Estoque:** `estoque/registrador/RegistradorEstoque` (porta única de saldo, `@Transactional(MANDATORY)`,
    `EstoqueTipos`, `MovimentoOrigem`), guarda `RegistradorUnicoTest`.
  - **Lotes:** `produto/lote/app/LoteOperacoes` (`consumirNosLotes`, `consumirAjusteSeguro`), `LoteService`.
  - **Clientes, usuários, empresa, promoções, inventário** já portados.
  - **Contrato:** cenários em `src/test/resources/contrato/cenarios/*.json`, gravações em
    `src/test/resources/contrato/gravacoes/<grupo>/`, `MotorDeContrato.GRUPOS`, seed/reset em `contrato/seed/`.
  - **Padrões que valem aqui:**
    - SQL do .NET portado **literalmente** (`JdbcTemplate`), com as mesmas dicas de bloqueio (`UPDLOCK`, `HOLDLOCK`,
      `ROWLOCK`);
    - lote T-SQL com várias instruções roda instrução por instrução;
    - operações "seguras" pós-commit em `REQUIRES_NEW`, sem lançar exceção.
- **Referência .NET** (`API/NETCORE/`), leia antes de portar cada parte:

  | Módulo | Arquivos |
  | --- | --- |
  | Caixa | `Controllers/Caixa/CaixaController.cs`, `Services/Caixa/HorusCaixaService.cs` (542 l.), `Services/Caixa/CaixaClientSession.cs`, `Repositories/DatabaseAccess/CaixaAB.cs` (1067 l.), `Repositories/DataAccess/CaixaSessionAD.cs`, `CaixaMovimentoAD.cs` |
  | Vendas | `Controllers/HistoricoVendas/HistoricoVendasController.cs` (578 l.), `Repositories/DatabaseAccess/HistoricoVendasAB.cs` (1740 l.), `Models/Requests/VendaRequest.cs`, `Services/Shared/SaleMoney*` |
  | Hash | `Services/Shared/HorusPayloadHash.cs` (vendas, abertura, fechamento, movimento) |
  | Autorização de preço | `Repositories/DatabaseAccess/AutorizacaoPrecoAB.cs` |
  | Cancelamento | `HistoricoVendasAB.CancelarVendaComSupervisorAsync`, `Repositories/DatabaseAccess/VendaCancelamentoAB.cs` |
  | Correção | `HistoricoVendasAB.CorrigirVendaAsync` |
  | Pedidos | `Controllers/Pedidos/PedidoController.cs`, `Repositories/DatabaseAccess/PedidoAB.cs` |
  | Fiado | `Controllers/Fiado/FiadoController.cs`, `Services/Fiado/FiadoService.cs`, `Repositories/DatabaseAccess/FiadoAB.cs` |
  | Crédito do Gateway (só leituras) | `Repositories/DatabaseAccess/GatewayCreditAB.cs` (`RequireLegacyAsync`, `DelegatedAsync`, início do `ConsumeAsync`) |
  | Fiscal (fronteira) | `RegrasEmissaoNfceAB.AvaliarEmissaoParaVendaAsync`, `DocumentoFiscalAB.EnfileirarAsync`, `AlocarProximoNumeroAsync`, `ObterVendasSemDocumentoFiscalAsync`, `ObterPendentesAsync`, `ObterCancelamentoDaVendaAsync`, `EncerrarSemEmissaoAsync`, `MarcarErroAsync`, `ObterDetalhePorCodigoAsync`; `Services/Fiscal/NfceOutboxWorker.cs` (só `NaoEmitirAsync` sem SEFAZ e o ramo "sem emitente") |
  | Estoque/lotes na venda | `EstoqueMovimentacaoAB.NovaOrigemProvisoria`, `VincularOrigemAsync`, `AplicarMovimentoPorCodigoAsync`; `LoteAB.ConsumirVendaSeguroAsync`, `EstornarVendaSeguroAsync` |

- **Clientes que definem o aceite:**
  - `JAVA/caixa`: E2E com `-Dquack.e2e.url`, usuário `caixa@teste.com` / `Teste@12345`; o `SaleCancelE2ETest` lê o
    SQL pelo container de `-Dquack.e2e.docker` (padrão `pdv-teste-sql`).
  - `JAVA/estoque`: comando D10 da Fase 3 (no `JAVA/api/README.md`).

## Goals / Non-Goals

**Goals:**
- As 24 rotas de caixa, vendas, pedidos e fiado com o mesmo contrato da API .NET, medido pelo comparador.
- Venda, cancelamento e correção com os mesmos efeitos no banco que a .NET: estoque, lotes, fiado, auditoria,
  `ProcessedEvents`, decisão e pendência fiscal.
- Idempotência compatível nos dois sentidos com a .NET: hash igual e `ResponsePayload` legível pelas duas APIs.
- Um ponto único de numeração (venda, fiscal, inventário), base do modo loja.
- E2E do Caixa JavaFX verdes contra a API Java (menos `NfceCancelE2ETest`); os 6 E2E de inventário do Quack Estoque
  de volta ao aceite.

**Non-Goals:**
- Emissão, assinatura, transmissão, contingência, cancelamento e inutilização na SEFAZ; rotas `api/Nfce/*` e
  `api/Nfe/*`; importação da NFC-e de contingência do Gateway (`xmlNfceContingencia`): **Fase 7**.
- Canal do Gateway (token `qgw_`, `X-Operator-Id`, `creditAuthorization`, `GrantAsync`/`ConsumeAsync` completos,
  protocolo do `Shared`): **Fase 5**.
- Sincronização loja → nuvem, comandos nuvem → loja, perfil `loja`: **fase 4b** (D9 registra a direção).
- Relatórios de vendas e análises (Fase 6).
- Corrigir esquisitices do .NET (D12).

## Decisions

### D1. Pacotes

Seguem o § 3.2 do plano, com `web/ app/ domain/ repo/` em cada módulo:

```
br.com.quack.pdv.venda        caixa/ (turno, movimentos, status), registro/ (venda, histórico, impressão,
                              autorização de preço, hash), cancelamento/ (cancelar, trilha, correção), pedido/, fiado/
br.com.quack.pdv.fiscal       fila/ (decisão de emissão, pendência, fila mínima)
br.com.quack.pdv.plataforma   numeracao/ (NumeradorDocumentos)
```

Regras de erro, usuário logado e perfis iguais às fases anteriores:
- `InvalidOperationException` e `ArgumentException` do .NET viram `NegocioException(400)`;
- `IdempotencyConflictException` vira 409 com a mensagem do .NET;
- `@PerfisPermitidos` com os perfis do `HorusAuthorizeRoles` **de cada método**. Rota sem atributo (por exemplo
  `POST /api/HistoricoVendas`, `imprimir` e todo o `CaixaController`) aceita qualquer autenticado.

### D2. Transação da venda (`RegistrarAsync`), na mesma ordem do .NET

Um método `@Transactional` no serviço de registro. Os passos, nesta ordem:

1. **Hash de identidade.** `payloadHash` enviado (trim, minúsculas) ou o calculado (D3).
2. **Idempotência.** Com `eventId`: `SELECT PayloadHash, ResponsePayload FROM ProcessedEvents WITH (UPDLOCK, HOLDLOCK)`.
   - Hash diferente: 409.
   - Hash igual: devolve o `ResponsePayload` desserializado com `isReplay = true`, sem mais nada.
3. **Pedido** (`orderNumber`): trava o pedido e confere status aberto, CPF e itens idênticos.
4. **Normalizações e conferências de dinheiro**, iguais ao `SaleMoney`: total, líquido por item, soma, pagamentos,
   tipo "Múltiplo", valor fiado.
5. **Fiado:**
   - cliente por documento só com dígitos, com `UPDLOCK, ROWLOCK`;
   - `RequireLegacyAsync` e `DelegatedAsync` (leituras em `GatewayCreditStores` e `GatewayCreditGrants`);
   - limite de crédito;
   - com `creditAuthorization`, recusa como a .NET recusa quem não tem token do Gateway (D10).
6. **Preço autorizado:** `AutorizacaoPrecoAB.ValidarPrecosDaVendaAsync` na transação (sem pedido).
7. **Baixa de estoque** pelo `RegistradorEstoque`:
   - origem `venda` com **origem provisória** (`pend-<uuid>`), tipo `SaidaVenda`;
   - online sem saldo dá 400; offline (`eventId`, `offlineReference` ou `occurredAt`) aceita e devolve a ruptura.
   - Na venda de pedido: preço fixo e sempre exige saldo.
8. **Número e inserção:**
   - `NumeradorDocumentos.proximoNumeroVenda` (D4);
   - `VincularOrigemAsync` troca a origem provisória pelo número, com descrição `Venda #<n>`;
   - `INSERT` em `Vendas`, `VendaPagamentos` e `VendaItens` com os IDs derivados (`sale-…`, `…-pag-001`,
     `…-item-001`).
9. **Vínculos:** vincula autorizações de preço à venda; finaliza o pedido (`Status=1`).
10. **Débito de fiado** (`FiadoAB.RegistrarDebitoAsync`).
11. **Evento processado:** com `eventId`, `INSERT ProcessedEvents` com o `ResponsePayload` (D3).
12. **Auditoria na transação:** preço offline abaixo do cadastro e débito de fiado.
13. **Commit.**

Depois do commit:
- **Lotes:** `ConsumirVendaSeguro` em `REQUIRES_NEW`; nunca lança exceção.
- **Rupturas:** auditoria fora da transação.

Depois o controller, como no .NET:
- caixa liberado: online exige; offline só registra aviso no log;
- `caixaSessaoId` do turno aberto do operador;
- decisão fiscal e pendência (D6);
- status 201 (nova) ou 200 (replay).

**Pedido:** `RegistrarComPrecosFixosAsync` (finalização) segue o mesmo esqueleto, com as diferenças do .NET:
- sem `ProcessedEvents`;
- replay pelo status do pedido;
- sem a checagem de preço autorizado.

### D3. Idempotência compatível com a .NET (hash e resposta guardada)

- **Hash** (`venda/registro/HashPayload`): porta **byte a byte** do `HorusPayloadHash`. O SHA-256 é calculado sobre o
  JSON que o `System.Text.Json` produz para o objeto anônimo, então a Java precisa reproduzir:
  - a ordem das propriedades;
  - a ordenação de itens e pagamentos (`OrdinalIgnoreCase`);
  - os arredondamentos (`Math.Round` com `MidpointRounding.ToEven`, o padrão do .NET);
  - **a escala do `decimal`** (o .NET mantém zeros à direita: `2.50` serializa `2.50`);
  - **o escape padrão do `System.Text.Json`**: caracteres não ASCII e `<`, `>`, `&`, `'`, `"`, `+`, `` ` `` (confirme cada caso pelos vetores) viram
    `\uXXXX` maiúsculo;
  - o campo extra `xmlNfceContingenciaSha256` só quando vier o XML.
- **Referências:**
  - o Caixa JavaFX já reproduz esse hash em `JAVA/caixa/src/main/java/br/com/quack/caixa/domain/PayloadHash.java`;
    use-o como referência, sem depender do módulo;
  - **vetores:** acrescentar ao `JAVA/api/contrato/vetores-dotnet/Program.cs` a geração de hashes de venda, abertura,
    fechamento e movimento (acentos, desconto, vários pagamentos, decimais com zeros à direita). O
    `CompatibilidadeDotnetTest` confere com o gerado pela .NET.
- **`ResponsePayload`:** JSON camelCase do `VendaRegistroResultadoAD` (e do `CaixaStatusDto` no caixa).
  - A Java lê respostas gravadas pela .NET: teste com uma fixture copiada de uma linha real do ambiente .NET.
  - A Java grava no mesmo formato, para que a .NET leia num rollback do corte.
- **Caixa:** `CASH_OPEN`, `CASH_CLOSE` e movimentos seguem o `CaixaAB` (`ObterEventoProcessadoAsync`,
  `AbrirIdempotenteAsync`, `FecharIdempotenteAsync`, `RegistrarMovimentoIdempotenteAsync`), inclusive o placeholder
  inserido antes e atualizado com a resposta. Copie a sequência exata.

### D4. Numeração centralizada (`plataforma/numeracao/NumeradorDocumentos`)

- `@Transactional(propagation = MANDATORY)`: só numera dentro da transação de quem grava.
- **`proximoNumeroVenda(companyId)`:** SQL do `NextSaleNumberAsync` literal:
  - `MAX(TRY_CONVERT(INT, SaleNumber))` com `UPDLOCK, HOLDLOCK`;
  - base 15039 para `empresa-principal`;
  - devolve texto.
- **`proximoNumeroFiscal(companyId, modelo, serie, ambiente)`:** `AlocarProximoNumeroAsync` literal: garante a linha
  em `FiscalSequencias` e faz `UPDATE … WITH (UPDLOCK, HOLDLOCK) … OUTPUT DELETED.ProximoNumero`.
- **`proximoNumeroInventario(companyId)`:** move para cá o `SELECT ISNULL(MAX(Numero), 0) + 1 FROM Inventarios` do
  `InventarioService` (Fase 3), sem mudar o resultado.
- **Guarda** `NumeracaoUnicaTest`, como o `RegistradorUnicoTest`: varre `src/main/java` e falha se `MAX(` sobre
  `SaleNumber`/`Numero` de `Inventarios` ou `FiscalSequencias` aparecer fora do `NumeradorDocumentos`.
- **Por quê agora:** o ID da venda deriva do número. No modo loja (4b) só o servidor da loja pode numerar as empresas
  dele, e esse controle entra num só lugar. Nesta fase não há verificação de autoridade (YAGNI).

### D5. Cancelamento, trilha e correção

- **Controller:**
  - a função `Negar` grava toda recusa (`VendaCancelamentoAB.RegistrarNegadaAsync` + `AuditLog`
    `CancelamentoVendaNegado`);
  - mapa de código para HTTP igual ao do .NET: `NAO_ENCONTRADA` 404; `NOTA_AUTORIZADA`/`NOTA_PENDENTE`/
    `JA_CANCELADA`/`STATUS_INVALIDO` 409; resto 400.
- **Transação do cancelamento**, em ordem:
  1. trava a venda (`UPDLOCK, ROWLOCK`);
  2. `VendaCancelamentoAB.CapturarAsync` (fotografia);
  3. confere status e notas;
  4. `UPDATE Vendas`;
  5. estorno de cada item por `aplicarPorCodigo` (tipo `Devolucao`, origem `venda`/número/supervisor);
  6. estorno do fiado (`FiadoAB.ReverseDebitAsync`; a exceção `CANCELLATION_REQUIRES_REFUND_REVIEW` vira a mensagem
     do .NET);
  7. auditoria e `VendaCancelamentos` na transação;
  8. commit;
  9. `EstornarVendaSeguro` dos lotes em `REQUIRES_NEW`.
- **Correção** (`CorrigirVendaAsync`, cerca de 300 linhas): porte literal.
  - A venda nova é numerada pelo `NumeradorDocumentos`.
  - Depois, decisão fiscal e pendência só se houver emitente, sem derrubar a correção (como o .NET).

### D6. Fronteira fiscal da Fase 4 (`fiscal/fila`)

- **Decisão** (`AvaliarEmissaoParaVendaAsync`), em transação própria depois da venda:
  - lê `Vendas.EmitirNfce` (`UPDLOCK, HOLDLOCK`): se já tem valor, devolve;
  - senão, reaproveita uma NFC-e existente;
  - senão, aplica as regras (`DeveEmitirSempre`, intervalo com `ContadorVendas` sob `UPDLOCK, HOLDLOCK`);
  - grava `EmitirNfce`.
- **Pendência** (`EnfileirarAsync`):
  - trava a venda;
  - reaproveita a nota existente;
  - recusa venda cancelada;
  - lê série e ambiente (`ObterConfigFiscalAsync`);
  - número por `NumeradorDocumentos.proximoNumeroFiscal`;
  - `INSERT DocumentosFiscais (…, Status 1, TpEmis 1)`.
  - Falha dá 503 com a mensagem e `data` do .NET.
- **Fila mínima** (`FilaFiscalMinima`, `@Scheduled(fixedDelay = 1s)`, como o `Intervalo` do worker), ligada por
  `quack.fiscal.fila-minima.ativa` (padrão `true`; desligar quando a .NET usar o mesmo banco). Faz só:
  1. retoma `ObterVendasSemDocumentoFiscalAsync` com `EnfileirarAsync`;
  2. para cada pendente (`ObterPendentesAsync`) de venda cancelada e sem `XmlAssinado`: o ramo de `NaoEmitirAsync`
     **sem SEFAZ**:
     - status `Rejeitado` (4);
     - motivo `"<tipo> não emitida: a venda #<n> foi cancelada antes da emissão. Inutilize o número <num> (série <s>)
       na SEFAZ"`, com sufixo `" (empresa sem certificado configurado)."` ou `"."`;
     - `EncerrarSemEmissaoAsync`;
     - auditoria `NfceNaoEmitida`;
  3. pendente de empresa sem emitente: `MarcarErroAsync("Empresa sem certificado ou CSC configurado.", null)`;
  4. **qualquer outra pendente fica parada**, sem transmissão (a Fase 7 troca esta classe pelo worker completo).
- **Emitente:** só a leitura "existe emitente configurado?". A Fase 7 porta a decifragem e o certificado.
- **Detalhe da venda:** `DocumentoFiscal` vem de `ObterDetalhePorCodigoAsync`, só leitura.

### D7. Caixa

- Porte do `HorusCaixaService` + `CaixaAB`:
  - status (`BuildStatusAsync`, `CanSell`, `BlockReason`, `State`);
  - sessão por id (dono ou gerente/administrador);
  - movimentos por período;
  - abrir, fechar e movimentar, idempotentes (D3);
  - `ComputeExpectedCashAsync` (dinheiro esperado: fundo + vendas em dinheiro do turno + reforços − sangrias, como o
    .NET calcula, inclusive o troco);
  - `EnsureVendaPermitidaAsync` com auditoria `VendaBloqueada`;
  - `CaixaClientSession` (dados de terminal e IP que o .NET grava).
- Multi-caixa: `ObterSessaoAbertaAsync(companyId, operatorId)`.

### D8. Pedidos e fiado

- **Pedido:** `PedidoAB` e `PedidoController` portados.
  - Criar não toca estoque.
  - Finalizar: caixa liberado, `RegistrarComPrecosFixos`, decisão fiscal e pendência.
  - Perfis por método.
- **Fiado:** `FiadoService`/`FiadoAB` portados (receber com perfil administrador ou caixa; extrato, devedores e resumo
  com administrador, gerente ou caixa), cumprindo o spec existente `vendas/fiado-conta-corrente`.

### D9. Fila de saída: avaliada e trocada por Change Tracking na 4b

- **Pedido original:** gravar, na transação de cada operação de venda e caixa, um evento numa fila de saída (outbox)
  para o servidor da loja enviar à nuvem.
- **Decisão:** não criar fila de saída nesta fase. A fase 4b sincroniza pelo **Change Tracking do SQL Server**:
  - existe em todas as edições, inclusive a Express;
  - fica ligado só no banco da loja;
  - um processo envia à nuvem, em lotes consistentes (leitura em snapshot até uma versão), o estado das linhas que
    mudaram.
- **Por quê:**
  - Com **uma autoridade por empresa** (§ 3.3), replicar estado não tem conflito: a nuvem só aplica o que a loja
    gravou.
  - O Change Tracking cobre **todas** as tabelas da loja (cadastros, estoque, inventário, entradas da Fase 3, vendas)
    sem tocar em cada caminho de gravação; a fila da aplicação exigiria eventos em cada serviço das Fases 2 e 3, com
    risco de esquecer um.
  - Esta fase fica menor, o que atende ao prazo da loja.
- **O que a 4b herda daqui:** o ponto único de numeração (D4). Itens que a 4b precisa resolver no próprio desenho:
  - retenção do Change Tracking com ressincronização completa se a loja ficar offline além dela;
  - ordem de aplicação por chave estrangeira;
  - `AuditLog` com `IDENTITY`;
  - tabelas filhas sem `CompanyId` (validar pela linha mãe);
  - comandos nuvem → loja.

### D10. Divergências conscientes (registradas no cenário e no README)

- **`xmlNfceContingencia`:** 400 "A importação da NFC-e de contingência do Gateway ainda não está disponível nesta
  versão." antes de qualquer gravação. A Fase 7 remove.
- **`creditAuthorization`:**
  - sem canal do Gateway, a .NET recusa no início do `ConsumeAsync` (`AuthenticatedGatewayTokenId` nulo) com a
    mensagem `INVALID…` daquele `throw`;
  - a Java recusa com a **mesma** mensagem e status;
  - não é divergência para clientes comuns; registre no cenário.
- **Rotas `api/Nfce/*` e `api/Nfe/*`:** não mapeadas, 404 (Fase 7).

### D11. Contrato e aceite

- **Grupos novos** em `MotorDeContrato.GRUPOS`: `caixa`, `vendas`, `pedido`, `fiado`.
  - Cenários por rota: sucesso, cada recusa com mensagem, 401, 403 por perfil, isolamento entre empresas.
  - Venda: replay, conflito de idempotência, venda offline com ruptura, fiado, preço autorizado, pedido.
  - Cancelamento: cada código e a trilha.
  - Correção e pedidos (finalizar duas vezes).
- **Voláteis:** `saleNumber`/`vendaId` quando dependem de ordem, datas, ids de autorização, `canceladoEm`.
  - Prefira fixar a ordem no seed a declarar volátil.
- **Ambiente .NET de gravação:** o `NfceOutboxWorker` da .NET roda a cada 1 s e muda `DocumentosFiscais`. Grave os
  cenários de venda numa empresa do seed **sem emitente** (a nota vai para erro de forma determinística) e não
  compare status de documento fiscal em respostas logo após a venda.
- **Seed de contrato:** ampliar `contrato/seed/seed-contrato.sql`/`reset-contrato.sql` com:
  - turnos;
  - clientes com limite;
  - regras de emissão;
  - pedido aberto;
  - venda finalizada, venda cancelada e venda com nota autorizada (para `NOTA_AUTORIZADA`).
- **Aceite E2E:** os scripts `contrato/aceite-estoque/subir.sh`/`descer.sh` já servem (mesmo banco de origem).
  - **Caixa:** `mvn -o -f JAVA/caixa/pom.xml test -Dquack.e2e.url=http://localhost:5261 -Dquack.e2e.docker=pdv-java-sql
    -Dtest='*E2ETest,!NfceCancelE2ETest' -Dsurefire.failIfNoSpecifiedTests=false`.
  - **Quack Estoque:** o comando D10 da Fase 3 **sem** as exclusões `InventarioAppE2ETest` e
    `InventarioE2ETest#…` (as 5 de inventário). Atualizar `JAVA/api/README.md` e a nota do `tasks.md` da Fase 3.

### D12. Fidelidade a esquisitices do .NET

Copiar, sem corrigir, por exemplo:
- `saleNumber` texto;
- base 15039 só para `empresa-principal`;
- `printedAt` com `DateTime.Now` local formatado `dd/MM/yyyy HH:mm:ss`;
- `ate` + 1 dia nos filtros de cancelamento;
- auditoria de ruptura fora da transação.

Corrigir depois do corte.

## Risks / Trade-offs

- **Hash diferente da .NET** quebraria o replay de eventos offline após o corte → vetores gerados pela própria .NET
  (D3) e teste com o `PayloadHash` do Caixa JavaFX.
- **Bloqueios e concorrência** (número, saldo, contador fiscal): o SQL e as dicas portados literalmente; um IT com
  vendas concorrentes na mesma empresa confere números sem repetição e saldo exato.
- **Worker da .NET mexendo no ambiente de gravação** → empresa sem emitente e voláteis (D11).
- **Fila fiscal mínima e .NET no mesmo banco** marcariam notas duas vezes → a propriedade
  `quack.fiscal.fila-minima.ativa` fica desligada quando as duas APIs usarem o mesmo banco.
- **Tamanho:** `HistoricoVendasAB` + `CaixaAB` passam de 2.800 linhas → tarefas em blocos pequenos, cada um com seus
  testes e cenários.
- **Troca da fila de saída por Change Tracking (D9):** a confirmação técnica final é da 4b. Se o Change Tracking não
  servir, a fila de saída da aplicação continua possível, porque a numeração única desta fase serve aos dois caminhos.

## Migration Plan

- **Banco:** sem migração nova e sem mudança de esquema.
- **Entrega:** branch `spring-api`, imagem pelo CI.
- **Homologação:** Caixa JavaFX e Quack Estoque apontados para a API Java sobre a cópia do banco.
- **Volta atrás:** voltar a imagem. As linhas gravadas pela Java (vendas, `ProcessedEvents`, `DocumentosFiscais`
  pendentes) são as mesmas que a .NET lê e processa.

## Open Questions

- Nenhuma que bloqueie a Fase 4. A forma exata da sincronização (D9) é decidida na proposta da 4b.
