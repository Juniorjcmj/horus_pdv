# Proposal

## Why

A Fase 3 deixou produtos e estoque na API Java, mas o Caixa ainda não roda contra ela: falta a Fase 4 do plano
(`docs/migracao-api-springboot.md`, § 11), com caixa, vendas, cancelamento, pedidos e fiado. Ela é também a primeira
peça do **modo loja** (§ 3.3, decisão de 03/11/2026). A loja de material de construção, com 7 máquinas, vai ter um
servidor dedicado rodando a API Java com SQL Server Express, e o prazo pedido é "o mais rápido possível" (prioridade
4 → 4b → 7 → 6).

O modo loja exige numeração gerada num só lugar, porque o ID da venda deriva do número (`sale-{empresa}-{número}`).
Por isso esta fase já nasce com um ponto único de numeração. A sincronização com a nuvem fica para a fase 4b, pelo
Change Tracking do SQL Server (design, D9), e não precisa de nada desta fase além disso.

## What Changes

- **Caixa** (`api/Caixa`, 6 rotas): status, sessão, movimentos, abrir, fechar, sangria e reforço.
  - Um turno por operador (multi-caixa);
  - abertura, fechamento e movimento idempotentes por `eventId` + hash;
  - dinheiro esperado na gaveta calculado como no .NET.
- **Vendas** (`api/HistoricoVendas`, 9 rotas):
  - **registrar venda:**
    - confere totais, itens e pagamentos;
    - preço diferente do cadastro só com autorização de gerente;
    - baixa de estoque pelo registrador da Fase 3 e baixa FEFO por lote depois do commit;
    - fiado com limite de crédito;
    - venda de pedido com preço congelado;
    - idempotência offline (`ProcessedEvents`: mesmo evento devolve a mesma resposta, conteúdo diferente dá 409);
    - aviso de ruptura na venda offline;
  - lista e detalhe;
  - impressão;
  - autorização de preço;
  - cancelamento com senha do gerente, com estorno de estoque, de lotes e de fiado e com a trilha completa
    (inclusive as recusas);
  - lista e auditoria de cancelamentos;
  - correção de venda (remove itens e gera venda nova).
- **Pedidos** (`api/Pedido`, 5 rotas): criar, listar abertos, consultar, finalizar no caixa e cancelar.
- **Fiado** (`api/Fiado`, 4 rotas): receber, extrato, devedores e resumo.
- **Fronteira fiscal** (a emissão é da Fase 7):
  - a venda decide se emite NFC-e (regras de emissão e contador) e grava a nota pendente com o número reservado, igual
    ao .NET;
  - uma fila fiscal mínima, sem SEFAZ:
    - retoma vendas que ficaram sem documento;
    - tira da fila, como "não emitida", a nota de venda cancelada;
    - marca erro quando a empresa não tem emitente.
- **Numeração centralizada:** um único serviço gera o número da venda, a numeração fiscal (`FiscalSequencias`) e o
  número do inventário. Hoje cada módulo calcula o seu. Um teste de arquitetura impede outro caminho.
- **Sem fila de saída nesta fase.** A fila de saída gravada pela aplicação foi avaliada e trocada pelo Change
  Tracking do SQL Server na fase 4b (design, D9). Ele cobre todas as tabelas (inclusive cadastros e estoque das Fases
  2 e 3) sem mexer em cada gravação.
- **Fora desta fase** (divergências conscientes, registradas no contrato e no README):
  - venda com `xmlNfceContingencia` (nota do Gateway em contingência): Fase 7. Recusa com 400 e mensagem própria;
  - canal do Gateway (`creditAuthorization`, token do Gateway, `X-Operator-Id`): Fase 5. Sem o token do Gateway, a
    venda com `creditAuthorization` já é recusada no .NET, e a Java reproduz essa recusa;
  - rotas `api/Nfce/*` e `api/Nfe/*`, emissão, cancelamento e inutilização na SEFAZ: Fase 7.
- **Aceite:**
  - os E2E do Caixa JavaFX (inclusive o offline) passam contra a API Java, menos o `NfceCancelE2ETest`, que é da Fase 7;
  - voltam ao aceite do Quack Estoque os 6 E2E de inventário que dependem de venda.

## Capabilities

### New Capabilities
- `vendas/caixa-turno`: turno de caixa por operador (abrir, fechar, sangria, reforço, status e conferência), com
  idempotência offline.
- `vendas/registro-venda`: registro de venda com validação de totais e pagamentos, preço autorizado, baixa de estoque e
  lotes, fiado, idempotência offline, histórico, detalhe e impressão.
- `vendas/cancelamento-correcao`: cancelamento com senha do gerente (estornos e trilha, inclusive recusas) e correção
  de venda.
- `vendas/pedidos`: pedidos montados pelo vendedor com preço congelado, finalizados no caixa.
- `fiscal/fila-nfce`: decisão de emissão por venda, nota pendente com número reservado e fila fiscal mínima (sem
  SEFAZ) até a Fase 7.
- `plataforma/numeracao`: numeração de venda, fiscal e de inventário gerada por um único serviço.

### Modified Capabilities
- (nenhuma: `vendas/fiado-conta-corrente`, `pdv/multiplas-formas-pagamento` e `fiscal/contingencia-offline-nfce` já
  descrevem o comportamento, e a API Java o reproduz sem mudar requisito. A API .NET não muda.)

## Impact

- **Código novo** só em `JAVA/api`:
  - pacotes `venda` (caixa, venda, cancelamento, correção, pedido, fiado, autorização de preço), `fiscal/fila` e
    `plataforma/numeracao`;
  - cenários e gravações de contrato.
- **Código ajustado em `JAVA/api`:**
  - `RegistradorEstoque` passa a aceitar origem provisória e vínculo da origem;
  - `LoteOperacoes` ganha consumo e estorno de venda;
  - `Inventario` passa a numerar pelo serviço central.
- **Nada muda em `API/NETCORE` nem no `GATEWAY`.** A .NET é só a referência (fonte da verdade) e o ambiente de
  gravação do contrato. O `contrato/vetores-dotnet` (ferramenta de teste dentro de `JAVA/api`) ganha vetores do hash
  de venda e de caixa.
- **Banco:** sem migração nova. Nenhuma tabela muda.
- **Clientes:** sem mudança de rota nem de JSON. O Caixa JavaFX e o Quack Estoque passam a ser testados contra a
  API Java.
- **Plano da migração:** a § 3.3 corrige a regra 2. Os IDs de venda não são GUID: derivam do número, por isso a
  numeração precisa de uma só autoridade.
- **Branch:** `spring-api`.
