# Design

## Context

Motivação: ver `proposal.md`. Comportamento: ver `specs/plataforma/operacoes-estoque-nuvem/spec.md`.

Estado atual (change `api-java-modo-loja`, design D2/D6/D7/D10):

- `CatalogoSincronizacao` classifica as tabelas. `EstoqueMovimentacoes`, `ProdutoLotes`, `NotasEntradaArquivo` e
  `MapeamentoProdutoFornecedor` são `LOJA`; em `Produtos`, `ProductQnt` e `TotalPriceOnProduct` são colunas `LOJA`.
- O detector da nuvem (`DetectorNuvem.ciclo` → `MotorDeteccao.processarLinha`) percorre as tabelas em
  `ordemDeAplicacao()` e, para gravação da nuvem em tabela `LOJA`, chama `tabelaDaLoja`: linha nova vai para
  `SyncQuarentena`; linha alterada vai para a quarentena + `reenviar`. Já existe a exceção `AceiteDaNuvem` (transição
  de status de `Vendas` desce por `forcar`).
- Em tabela `EDITAVEL`, inserção na nuvem vira `inserir` com **todas** as colunas, inclusive as `LOJA`.
- A loja aplica cada comando numa transação (`AplicadorComandos`, contexto `comando-nuvem`), em ordem de `seq`.
- A conferência (`ServicoConferencia`) exclui da nuvem as linhas que sobram em tabela `LOJA`.
- Na loja, todo saldo muda pelo `RegistradorEstoque.aplicar` (UPDATE com OUTPUT + INSERT do movimento).

O que a .NET grava (lido em `NfeImportService.ConfirmarAsync`, `ProdutoAB`, `LoteAB`, `NotaEntradaArquivoService`,
`MapeamentoProdutoFornecedorAB`, `EstoqueMovimentacaoAB.AplicarMovimentoAsync`):

| Fluxo | Tabelas | Transação |
| --- | --- | --- |
| Confirmar NF-e / cupom / manual | `Fornecedores` (MERGE), `Produtos` (novo ou custo/preço), `EstoqueMovimentacoes` (1 por item, `OrigemTipo='nfe'`), `ProdutoLotes` (INSERT), `Produtos.DataValidade/ControlaValidade`, `MapeamentoProdutoFornecedor` (MERGE), `NotasEntradaArquivo` (INSERT) | uma (`UseImportTransaction`); a tarefa 1.2 confere se `LoteAB` e o de-para usam a mesma |
| Estornar entrada | `EstoqueMovimentacoes` (saída por item, `OrigemTipo='nota_entrada'`, `OrigemId=notaId`), `NotasEntradaArquivo` (UPDATE `Estornada…`) | **uma por item + uma para a nota** |
| Ajuste manual (painel, MCP) | `Produtos.ProductQnt`, `EstoqueMovimentacoes` | uma |
| Editar produto mudando o estoque | `Produtos`, `EstoqueMovimentacoes` (diferença, `OrigemTipo='cadastro'`) | uma |
| Anexar XML a entrada sem documento | `NotasEntradaArquivo` (UPDATE `Identidade`, `ChaveAcesso`, `Origem='xml'`…) | uma |

Produto novo pela .NET: `SalvarAsync` lê saldo 0 e grava a diferença como movimentação. Então **toda** mudança de
saldo na nuvem tem uma linha em `EstoqueMovimentacoes`, e `EstoqueNovo − EstoqueAnterior` é a diferença com sinal.

Restrição: nada muda na .NET.

## Goals / Non-Goals

**Goals:**
- Operações de estoque da nuvem valem na loja, com saldo recalculado na loja.
- Tudo ou nada por transação da nuvem; idempotente; nota em dobro recusada.
- Lojas com jar antigo seguem como hoje.

**Non-Goals:**
- Inventário, ordem de compra, venda, caixa e fiado feitos na nuvem (continuam na quarentena).
- Juntar os passos do estorno da .NET numa transação só (a .NET grava separado; cada passo desce sozinho).
- Tela para a quarentena ou para os conflitos (o alerta por e-mail e a rota de situação bastam).
- Mudar a classificação `LOJA` dessas tabelas: a loja continua autora; a nuvem ganha só exceções de inserção.

## Decisions

### O1. Comando novo `operacao`, agrupado pela transação da nuvem

O detector, em cada ciclo, junta as gravações da nuvem nas tabelas de operação (`EstoqueMovimentacoes`,
`ProdutoLotes`, `NotasEntradaArquivo`) pelo `SYS_CHANGE_VERSION` (o Change Tracking dá a mesma versão a tudo o que
uma transação gravou). Cada grupo vira um comando `operacao` com uma lista ordenada de passos:

```json
{ "versaoNuvem": 812, "passos": [
  { "tipo": "inserir", "tabela": "ProdutoLotes", "chave": "...", "colunas": [{"nome": "...", "valor": "..."}] },
  { "tipo": "movimento", "chave": "mov-...", "colunas": [...] },
  { "tipo": "inserir", "tabela": "NotasEntradaArquivo", "chave": "...", "colunas": [...] },
  { "tipo": "transicao", "tabela": "NotasEntradaArquivo", "chave": "...", "regra": "estorno", "colunas": [...] }
]}
```

- `chave` do comando: a versão da nuvem (`v812`); `tabela`: `*operacao*`.
- Ordem dos passos: a de `ordemDeAplicacao()` (pais antes de filhos).
- O comando é criado **depois** dos comandos `inserir`/`alterar` das tabelas `EDITAVEL` do mesmo ciclo, então o
  produto e o fornecedor novos chegam antes (o `seq` garante a ordem na loja).
- **Por quê:** um comando por linha (o modelo atual) deixa a nota pela metade se um item falhar. Agrupar por versão
  usa o que o banco já sabe, sem mudar a .NET.
- **Alternativa descartada:** reexecutar a operação de negócio na loja (mandar o pedido da nota, não as linhas). Exige
  saber, pelas linhas, qual tela da .NET gerou a gravação, e duplicaria regras (custo médio, preço por margem, lote
  padrão) já aplicadas pela .NET. Mandar as linhas é mais simples e fiel.
- **Alternativa descartada:** incluir no `operacao` as linhas `EDITAVEL` da mesma transação (fornecedor, custo e preço
  do produto). Elas já descem por `inserir`/`alterar` com conferência de base, testados. Consequência aceita: se a
  operação for recusada (nota em dobro), o custo/preço que a nota da nuvem gravou ainda pode valer na loja. É cadastro,
  editável, e o conflito é alertado.

### O2. Movimentação aplicada pela diferença

Passo `movimento`: a loja calcula `delta = EstoqueNovo − EstoqueAnterior` da linha da nuvem e chama o
`RegistradorEstoque` com **o `Id` da linha da nuvem**, o `Tipo`, o `CustoUnitario`, a origem (`OrigemTipo`,
`OrigemId`, `Motivo`, `UsuarioId`, `UsuarioNome`) e o `DepositoId`. O registrador grava `EstoqueAnterior` e
`EstoqueNovo` da loja. Novo método no registrador: `aplicarComId(...)` (o atual gera `Id` próprio).

- A linha da loja sobe no próximo lote e sobrescreve a da nuvem (mesmo `Id`, tabela `LOJA`), e o `ProductQnt` da loja
  sobrescreve o da nuvem. As pontas convergem.
- **Por quê** a diferença e não `Quantidade` + `Tipo`: o significado de `Tipo` varia (`entrada`, `saida`, `ajuste`,
  `estorno`…) e a diferença já tem o sinal certo, para qualquer fluxo da .NET.

### O3. Saldo da nuvem não é copiado

- `Produtos` inserido pela nuvem (`inserir` de tabela `EDITAVEL`): quando a loja suporta `operacao` (O9), as colunas
  `LOJA` (`ProductQnt`, `TotalPriceOnProduct`) vão com zero. O saldo vem da movimentação do mesmo ciclo.
- `ProductQnt`/`TotalPriceOnProduct` alterados na nuvem num produto que tem movimentação da nuvem no mesmo ciclo (ou
  em `operacao` ainda sem resultado): **não** vão para a quarentena nem geram `reenviar`. O detector faz uma
  pré-leitura de `EstoqueMovimentacoes` do ciclo para saber quais produtos têm movimentação da nuvem.
- A detecção inline (`DeteccaoInline`, quando um lote da loja chega) segue a mesma exceção, consultando as
  `operacao` pendentes da empresa.
- Mudança de `ProductQnt` na nuvem **sem** movimentação continua indo para a quarentena (gravação fora do registrador).

### O4. Inserção da nuvem aceita em tabela `LOJA` (nova regra do catálogo)

`TabelaSincronizada` ganha `insercaoDaNuvem` (sim/não) e uma lista de `transicoes` aceitas (generalizando
`AceiteDaNuvem`):

| Tabela | Inserção da nuvem | Transições aceitas |
| --- | --- | --- |
| `EstoqueMovimentacoes` | sim, como passo `movimento` | — |
| `ProdutoLotes` | sim | — |
| `NotasEntradaArquivo` | sim | `estorno`: `Estornada`, `EstornadaEm`, `EstornadaPorUsuarioId`, `EstornadaPorUsuarioNome`, `EstornoJustificativa`, quando a loja tem `Estornada = 0`; `xml`: `Identidade`, `ChaveAcesso`, `Modelo`, `NumeroNota`, `Serie`, `DataEmissao`, `ValorNota`, `Origem`, `XmlOriginal`, quando a loja tem `Origem = 'sem-documento'` |

- Qualquer outra alteração da nuvem nessas tabelas segue para a quarentena, como hoje.
- **Por quê** não trocar a classe para `EDITAVEL`: `EDITAVEL` deixaria a nuvem editar qualquer coluna de movimento e
  de lote, e a loja deixaria de ser a autora. A exceção cobre só o que a .NET faz.

### O5. `MapeamentoProdutoFornecedor` vira `EDITAVEL`

O de-para é cadastro (a .NET faz MERGE nele a cada nota). Como `EDITAVEL`, a inserção e a troca de `ProdutoId` descem
com conferência de base, pelo mecanismo existente. A tarefa 1.1 confere, na Java da loja, quem grava a tabela (se a
loja também faz MERGE, a conferência de base resolve a colisão como nas outras tabelas editáveis).

### O6. Aplicação na loja: tudo ou nada e idempotente

`AplicadorComandos` ganha o caso `operacao`, delegado a um `AplicadorOperacoes` novo, numa única transação com o
contexto `comando-nuvem`:

1. **Nota em dobro:** para cada `inserir` de `NotasEntradaArquivo` com `Identidade` preenchida, se a loja tem outra
   linha (Id diferente) com a mesma `Identidade` e `Estornada = 0`, a operação inteira termina como `conflito` com
   `{"motivo": "nota-ja-lancada", "numero": ..., "idLoja": ...}`. Nada é gravado.
2. **Idempotência:** passo cuja chave já existe na loja é pulado (`inserir`, `movimento`); `transicao` cuja condição já
   não vale porque a loja já está no estado de destino é pulada.
3. **Transição sem a linha na loja** (ex.: estorno de nota que foi recusada): `conflito`, motivo `linha-ausente`.
4. Qualquer exceção desfaz a transação; o resultado é `falhou` com a mensagem (a nuvem conta tentativas e para na 5ª,
   como os outros comandos).
5. Grava `AuditLog` (`EventType = SincronizacaoOperacao`) com a versão da nuvem e o resumo dos passos.

- `conflito` de `operacao` gera alerta ao dono (mecanismo de `AlertasSync`) com a nota e o motivo.
- **Por quê** conflito e não "aplicar e alertar": a nota em dobro dobraria o estoque, o que é pior que pedir para
  lançar de novo.

### O7. Conferência não apaga operação pendente

Na exclusão das linhas que sobram em tabela `LOJA`, a nuvem não apaga linhas que estão num comando `operacao` com
status sem resultado (`pendente`, `entregue`, em retentativa). Depois de `conflito`, `falhou` (parado) ou `aplicado`, a
regra normal vale: a linha recusada é apagada da nuvem e a nuvem converge para a loja (spec "A nuvem se ajusta à
loja").

- A busca é pela chave em `SyncComandos.PayloadJson` das `operacao` pendentes da empresa. Para não varrer JSON, o
  comando grava as chaves dos passos numa tabela auxiliar `SyncComandoChaves(Seq, Tabela, Chave)` (migração da nuvem).

### O8. Ordem e lacunas

- Uma `operacao` cujo produto ainda não existe na loja (inserção do produto deu conflito, por exemplo código repetido
  na loja) falha pela FK e fica em retentativa até parar com alerta. É o comportamento certo: estoque de produto que a
  loja não tem não pode entrar.
- Estorno da .NET em vários passos: cada movimentação de estorno desce como uma `operacao` própria e vale sozinha; a
  transição da nota desce depois. Se a loja recusou a nota original, os movimentos de estorno (origem
  `nota_entrada:<id>`) também são recusados com `conflito`, motivo `nota-recusada`, para não baixar estoque de uma
  entrada que nunca entrou.

### O9. Compatibilidade: a loja anuncia o recurso

- A loja manda `X-Quack-Recursos: operacao-estoque` no `GET /api/loja-sync/comandos`. O filtro do servidor da loja
  grava em `SyncServidores.Recursos` (migração da nuvem, coluna nova, nula = nenhum).
- O detector só usa O1–O4 para empresas cujo servidor ativo anunciou o recurso. Sem o recurso: comportamento atual
  (quarentena).
- Implantação: **primeiro a loja** (o jar novo anuncia, a nuvem antiga ignora o cabeçalho), **depois a nuvem**.
  Rollback da nuvem: imagem anterior; as `operacao` já criadas ficam sem efeito se a loja voltar ao jar antigo
  (`tipo` desconhecido → `falhou`), e a conferência devolve a nuvem ao estado da loja.

## Risks / Trade-offs

- [Nota da nuvem lançada enquanto a loja está com o jar antigo vai para a quarentena e some na conferência] → implantar
  a loja antes da nuvem (O9); o alerta de quarentena já existe e mostra a nota.
- [Custo/preço da nota recusada vale na loja (O1)] → é cadastro editável; o alerta do conflito cita a nota para o dono
  conferir.
- [Saldo da nuvem diferente do da loja até a loja aplicar e subir] → esperado; com a loja conectada, menos de 1 minuto.
- [Transação da .NET grande (nota de 300 itens) vira uma `operacao` grande] → o payload segue o limite de lote atual; a
  tarefa 3.4 mede uma nota de 300 itens. Se passar do limite, o comando é dividido por tabela mantendo a ordem, e a
  idempotência (O6.2) garante a retomada.
- [`SYS_CHANGE_VERSION` de uma linha alterada duas vezes no mesmo ciclo é a da última transação] → a linha cai no
  grupo da última; inserção seguida de alteração vira um `inserir` com o valor final, que é o que importa.
- [`LoteAB`/de-para fora da transação da nota na .NET] → ficam em grupos próprios; continuam corretos por serem
  inserções idempotentes. A tarefa 1.2 confirma.

## Migration Plan

1. Migração da nuvem: `SyncServidores.Recursos`, `SyncComandoChaves`.
2. Jar da loja com o aplicador de `operacao` e o anúncio do recurso → atualizar o servidor da loja
   (`instalar.ps1 -EmailAdmin … -PularCarga`).
3. Imagem da nuvem (`pdv-api-sync`) com o detector novo → Portainer, "Update the service" com "Pull latest image".
4. Teste na loja real: lançar pela nuvem uma nota de 1 item de teste, conferir saldo e entrada na loja, estornar pela
   nuvem, conferir de novo.

Rollback: imagem anterior da nuvem; o jar da loja novo continua compatível com a nuvem antiga.

## Open Questions

- Nenhuma que mude specs ou tarefas. Os pontos a confirmar no código estão nas tarefas 1.1 e 1.2.
