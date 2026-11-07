# Proposal

## Why

Na loja com servidor próprio (Fase 4b, `api-java-modo-loja`), o servidor da loja é a única autoridade sobre estoque.
Uma nota de entrada lançada na nuvem (painel .NET, Quack Estoque apontado para a nuvem ou MCP) grava em tabelas que só
a loja pode gravar. Hoje essa gravação vai para a quarentena, e a conferência diária a apaga da nuvem: **a nota não
chega à loja e o estoque não sobe**. O dono da loja de material de construção lança notas de casa e precisa que elas
valham na loja, como já valem as edições de produto, preço e usuário.

## What Changes

- Operações de estoque feitas na nuvem passam a **descer para a loja**:
  - entrada de nota (NF-e por XML ou chave, cupom, entrada manual);
  - estorno de nota de entrada;
  - ajuste manual de estoque (painel e MCP `ajustar_estoque_produto`);
  - mudança de saldo pela edição do produto (a .NET já a registra como movimentação).
- O saldo **nunca é copiado** da nuvem. A loja aplica a **diferença** de cada movimentação da nuvem sobre o saldo dela
  (que tem as vendas que a nuvem ainda não viu), recalcula o saldo anterior e o novo, e devolve o resultado pela
  sincronização normal. As duas pontas convergem para o saldo da loja.
- O que uma transação da .NET gravou junto desce como **uma operação**, aplicada na loja numa transação só (tudo ou
  nada). Cada passo é idempotente pela chave da linha.
- Proteção contra **nota em dobro**: se a mesma nota (mesma identidade) já entrou na loja, a operação da nuvem termina
  como conflito, o estoque não sobe duas vezes e o dono recebe alerta.
- Produto criado na nuvem pela nota chega à loja com saldo zero e recebe o saldo pela movimentação (sem somar duas
  vezes).
- `MapeamentoProdutoFornecedor` (de-para de código do fornecedor) passa a ser cadastro editável nas duas pontas.
- A conferência diária não apaga da nuvem as linhas de uma operação ainda não aplicada na loja.
- Edições de produtos, preços, fornecedores, categorias, promoções e usuários **já descem** (Fase 4b) e não mudam.
- **Sem mudança na API .NET**: tudo é feito pelo receptor Java na nuvem e pelo servidor Java da loja.

## Capabilities

### New Capabilities
- `plataforma/operacoes-estoque-nuvem`: operações de estoque feitas na nuvem (entrada, estorno, ajuste e saldo pela
  edição do produto) descem para o servidor da loja e passam a valer lá, com o saldo recalculado na loja e proteção
  contra nota em dobro.

### Modified Capabilities
<!-- As regras de "edição indevida em tabela da loja" e de conferência estão em capacidades ainda não arquivadas
     (`plataforma/comandos-nuvem-loja` e `plataforma/reconciliacao-sincronizacao`, da change `api-java-modo-loja`).
     As exceções que esta change abre nelas estão descritas como requisitos da capacidade nova. -->
Nenhuma em `openspec/specs/` (ver comentário).

## Impact

- **Java, perfil `nuvem`:** catálogo de sincronização (nova regra de aceite de inserção da nuvem em tabela `LOJA`),
  detector (`MotorDeteccao`, `DetectorNuvem`: agrupar por transação e criar o comando `operacao`), conferência
  (`ServicoConferencia`: não apagar linha com operação pendente).
- **Java, perfil `loja`:** `AplicadorComandos` (novo tipo `operacao`), aplicação de movimentação pela diferença
  reaproveitando o `RegistradorEstoque`, alerta de conflito.
- **Banco:** nenhuma tabela nova obrigatória; `SyncComandos` passa a guardar comandos do tipo `operacao`.
- **Dependência:** `api-java-modo-loja` (Fase 4b) implantada. A loja precisa do jar novo **e** a nuvem da imagem nova;
  a nuvem só cria `operacao` para lojas cujo servidor anuncia suportar o tipo (ver design).
- **Fora:** inventário feito na nuvem, ordens de compra, vendas e caixa continuam só na loja.
- **.NET:** nada muda.
