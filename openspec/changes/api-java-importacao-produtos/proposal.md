# Proposal

## Why

Uma loja nova de material de construção (3 máquinas de venda, servidor da loja em modo loja, só Java) vai sair de
outro sistema e trazer o cadastro numa planilha exportada (`docs/planilha material/planilha de produtos.csv`: 3.252
produtos, 114 colunas, ISO-8859-1, separador `;`). A API Java não tem importação de planilha (a única rota,
`importar-legado`, carrega uma lista fixa de mercado). Cadastrar à mão é inviável e o saldo inicial precisa entrar com
rastro de movimentação, não como número solto no cadastro.

## What Changes

- Importação de produtos por planilha na API Java, em **duas etapas**: `simular` (lê, valida, devolve o relatório de
  tudo que vai entrar, mudar ou ser recusado, sem gravar) e `confirmar` (grava tudo numa transação, ou nada).
- Layout de entrada: o CSV exportado pelo sistema anterior da loja, reconhecido pelos **nomes das colunas** do
  cabeçalho (não pela posição), com valores em formato brasileiro (`R$ 1.234,56`, `0,5`, datas `dd/MM/aaaa`).
- Regras fechadas com o usuário:
  - preço de venda = **`Preço Venda Tab.3`**; custo = `Vl.Custo Compra (C/Impostos)`, custo médio e sem impostos
    nas colunas próprias;
  - código do produto no Quack = **`Código` do sistema anterior** (as etiquetas antigas de 4 dígitos continuam
    funcionando no caixa); GTIN só quando o código de barras é um EAN/GTIN válido, senão `SEM GTIN`;
  - campos fiscais importados **como vieram** (CST e CSOSN), sem escolher regime; o relatório resume o que veio para o
    contador conferir;
  - **estoque como entrada inicial**: uma movimentação `entrada` com origem `importacao` por produto com saldo
    positivo, com o custo; saldo negativo entra como zero e aparece no relatório;
  - categorias em três níveis `Classe › Grupo › Sub-Grupo`, criadas quando não existem.
- Reimportar a mesma planilha não duplica: produto com o mesmo `Código` é **atualizado** (cadastro e preço), e o
  estoque inicial só é lançado na primeira vez.
- Tela "Importar planilha" no Quack Estoque: escolher o arquivo, ver o resumo da simulação (contagens, avisos, lista de
  recusados exportável) e confirmar.
- Nada muda na API .NET (`API/NETCORE/`) nem no Gateway (`GATEWAY/`).

## Capabilities

### New Capabilities
- `produtos/importacao-planilha`: importação do cadastro de produtos (com categorias e saldo inicial) a partir de
  planilha exportada de outro sistema, com simulação e confirmação.

### Modified Capabilities
<!-- Nenhuma: usa o cadastro, as categorias e o registrador de estoque como já são; não muda requisito existente. -->

## Impact

- API Java (`JAVA/api`): módulo novo em `produto/importacao/` (leitor do CSV, mapeamento, validação, gravação),
  rotas `POST /api/produtos/importacao/simular` e `/confirmar` (administrador/gerente), reaproveitando
  `CategoriaService`, o SQL de inserção/atualização de `ProdutoService` e `RegistradorEstoque`.
- Banco: nenhuma tabela nova no esquema; tabela de controle `ImportacoesProduto` para idempotência do estoque inicial
  (migração `db/migration/V7__importacao_produtos.sql`) — no modo loja ela é classe `FORA` do catálogo (não sincroniza).
- Modo loja: produtos, categorias e movimentações gravados no servidor da loja sobem para a nuvem pela sincronização
  existente (Change Tracking), sem nada novo. Importar **no servidor da loja**, depois da instalação.
- Quack Estoque (`JAVA/estoque`): tela nova + chamada às rotas; versão incrementada pelo `build-exe.ps1`.
- Arquivo do cliente: a planilha em `docs/planilha material/` tem custos, preços e CNPJ de fornecedores — **não é
  versionada**; os testes usam um recorte anonimizado.
