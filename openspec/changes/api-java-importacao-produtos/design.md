# Design

## Context

- Motivação em `proposal.md`; requisitos em `specs/produtos/importacao-planilha/spec.md`.
- Arquivo real analisado (não versionado): `docs/planilha material/planilha de produtos.csv` — 3.252 linhas, 114
  colunas, ISO-8859-1, `;`, CRLF, sem campos com quebra de linha; todos `Status=Ativo`; `Código` único.
  - Código de barras: 1.854 GTIN válidos, 948 iguais ao `Código` (etiquetas de 4 dígitos), ~220 numéricos inválidos,
    219 vazios, 3 `SEM GTIN`, 8 texto; nenhum GTIN válido repetido.
  - Unidades: UN 2.938, PC 91, m2 76, CT 33, RL 32, PAR 27, M 23, outras < 10.
  - Estoque: 2.340 positivos, 910 zero, 2 negativos, 45 fracionados. Preço Tab.3 = 0 em 33.
  - Categorias: 18 Classes, 116 Grupos, 501 Sub-Grupos; 298 linhas sem Classe.
  - Fiscal: CST 060 (2.191) / 000 (935) / 040 (124); CSOSN 500 / 102; CFOP 5405 / 5102 / vazio (302); NCM sempre 8 dígitos;
    CEST em 2.232.
- `Produtos` (V1) já tem as colunas do sistema anterior: `Marca`, `Fabricante`, `ReferenciaFabricante`,
  `UnidadeCompra`, `QtdEmbalagem`, `FatorConversao`, `CustoMedio`, `CustoComImposto`, `CustoSemImposto`,
  `MarkupCadastrado`, `MarkupPraticado`, `ComissaoPercentual`, `DescontoMaximoPercentual`, `LocalizacaoEstoque`,
  pesos e dimensões. `UQ_Produtos_Company_ProductCode` garante um código por empresa. Busca do caixa/estoque é por
  `ProductCode = ?` ou `Gtin = ?` (`ProdutoService`, linhas ~112/119) — não há tabela de códigos alternativos.
- `Categorias` tem `CategoriaPaiId` (hierarquia), via `CategoriaService`.
- Saldo só muda pelo `RegistradorEstoque` (tipo `EstoqueTipos.ENTRADA`), que grava a movimentação na mesma transação.
- Limites atuais: `quack.security.max-request-body-bytes: 2000000` (o arquivo tem 1,9 MB) e o multipart padrão do
  Spring (`max-file-size` 1 MB) — os dois bloqueiam o upload como estão.

## Goals / Non-Goals

**Goals:**
- Importar o arquivo real inteiro em uma confirmação, em menos de 1 minuto no servidor da loja.
- Simulação e confirmação darem exatamente o mesmo resultado para o mesmo arquivo e o mesmo banco.
- Rodar igual no servidor da loja (perfil `loja`) e na API completa (perfil padrão); no perfil `nuvem` a rota não
  existe (o `ModoNuvemFilter` já devolve 404).

**Non-Goals:**
- Importar fornecedores, clientes, tabelas de preço 2–7, lotes/validade, histórico de vendas/compras.
- Outros layouts de planilha (XLSX, outros sistemas): o leitor fica isolado para permitir isso depois.
- Escolher regime tributário ou corrigir dados fiscais (fica com o contador; o relatório ajuda).
- Códigos de barras alternativos por produto (os ~220 inválidos ficam só no aviso).

## Decisions

### I1. Módulo `produto/importacao` em três partes puras + uma gravação
- `LeitorCsvProdutos`: bytes → linhas `Map<String,String>` por nome de coluna. Detecta UTF-8 com BOM; senão
  ISO-8859-1. Parser CSV simples com aspas (`"` e `""`), separador `;`. Nomes de cabeçalho comparados sem acento,
  sem diferenciar maiúsculas e com espaços normalizados.
- `MapeadorProdutoLegado`: linha → `ProdutoImportado` (record) ou `Recusa(codigo, descricao, motivo)`, mais avisos.
  Sem banco; testável com unitários.
- `PlanoImportacao`: junta os itens com o estado atual do banco (produtos existentes por código, GTINs usados,
  categorias existentes, códigos já com estoque inicial) e decide novo/atualizado/recusado — é o relatório.
- `GravadorImportacao`: aplica o plano numa `TransactionTemplate` (uma transação), na ordem categorias → produtos →
  movimentações.
A simulação executa leitor+mapeador+plano; a confirmação executa os mesmos três e o gravador. Alternativa (guardar o
plano entre as chamadas com um token) descartada: estado no servidor, expiração e corrida com outra edição; reenviar
1,9 MB é barato.

### I2. Rotas e limites
- `POST /api/produtos/importacao/simular` e `POST /api/produtos/importacao/confirmar`, `multipart/form-data` campo
  `arquivo`, `@PerfisPermitidos({"administrador","gerente"})`. Resposta `ApiResponse<RelatorioImportacao>`.
- Relatório: `totais` (lidas, novos, atualizados, recusados, avisos, categoriasNovas, produtosComSaldo,
  quantidadeTotal, valorEstoque), `resumoFiscal` (lista `{cst, csosn, cfop, quantidade}`), `recusados` e `avisos`
  (listas completas, `{linha, codigo, descricao, motivo}`), `hashArquivo` (SHA-256) e, na confirmação, `gravadoEm`.
- Limites: `spring.servlet.multipart.max-file-size: 15MB`, `max-request-size: 16MB`, e o filtro de tamanho de corpo
  (`max-request-body-bytes`) passa a aceitar até 16 MB **só** nessas duas rotas (manter 2 MB nas demais). Arquivo
  acima de 15 MB ou com mais de 50.000 linhas → 400.
- Tempo: a transação de confirmação usa timeout de 300 s.

### I3. Mapeamento de colunas

| Coluna do arquivo | Campo em `Produtos` | Regra |
|---|---|---|
| `Código` | `ProductCode` | obrigatório, trim, máx. 80 |
| `Descrição Completa` | `ProductName`, `ProductDescription` | obrigatório, máx. 180 |
| `Código de Barra` (senão `Código de Barra EAN14`) | `Gtin` | GTIN válido ou `SEM GTIN` (I4) |
| `Unidade Venda` | `UnidadeComercial`, `UnidadeTributavel` | obrigatório, trim, máx. 6, maiúsculas (`m2`→`M2`) |
| `Unidade Compra`, `Qtd.Embalagem` | `UnidadeCompra`, `QtdEmbalagem`, `FatorConversao` | embalagem ≤ 0 vira 1 |
| `Marca`, `Fabricante`, `Ref.Fabricante` | `Marca`, `Fabricante`, `ReferenciaFabricante` | `DIVERSOS`/`FORA DE LINHA` gravados como vieram |
| `Fornecedor Exclusivo` (senão 1º de `Fornecedores Relacionados`) | `ProductSupplier` | `SupplierId` nulo |
| `Código NCM` | `Ncm` | obrigatório, 8 dígitos |
| `Código CEST` | `Cest` | 7 dígitos ou nulo |
| `CFOP` | `Cfop` | vazio → `5405` se CST `060`/`010`/`070`, senão `5102`, com aviso |
| `Origem Mercadoria` | `OrigemMercadoria` | vazio → 0 |
| `CST`, `Cód. CSOSN`, `Alíquota ICMS%` | `CstIcms`, `CsosnIcms`, `AliquotaIcms` | como vieram |
| `CST do PIS`, `CST do COFINS` | `CstPis`, `CstCofins` | vazio → `99` com aviso |
| `Preço Venda Tab.3` | `ProductSalePrice` | obrigatório; zero → aviso |
| `Vl.Custo Compra (C/Impostos)` | `ProductUnitPrice`, `CustoComImposto` | |
| `Vl.Custo Médio`, `Vl.Custo Compra (S/Impostos)` | `CustoMedio`, `CustoSemImposto` | |
| `Estoque Mínimo`, `Estoque Máximo` | `EstoqueMinimo`, `EstoqueMaximo` | |
| `Localização Estoque` | `LocalizacaoEstoque` | trim dos `-` e espaços sobrando |
| `Comissão%`, `Desconto Máximo%`, `Markup Cadastrado %`, `Markup Praticado%` | colunas homônimas | |
| `Peso Líquido KG`, `Peso Bruto KG`, `Dimensão - *` | pesos e dimensões | |
| `Qtd.Estoque (Física)` | saldo inicial (I6) | |
| `Classe`, `Grupo`, `Sub-Grupo` | `CategoriaId` (I5) | |

Demais `NOT NULL` com padrão do cadastro atual (`ProductImageUrl`/`Name` vazios, `ControlaValidade=0`,
`DiasAlertaValidade` padrão, `ProductQnt=0` antes do registrador, `TotalPriceOnProduct`/`Lucro` como o cadastro
normal calcula). Reaproveitar o INSERT/UPDATE de `ProdutoService` (extraído para um método que recebe a entidade),
não escrever SQL paralelo — se o caminho atual fizer coisas por produto que não cabem em lote (ex.: auditoria por
linha), registrar em "Notas da implementação" o que foi mantido.

Números: remover `R$`, espaços e `.` de milhar, `,` → `.`; vazio = 0. Datas não são importadas.

### I4. GTIN
Válido = só dígitos, comprimento 8, 12, 13 ou 14, dígito verificador GS1 correto. Inválido ou vazio → `SEM GTIN`;
aviso quando o original não era vazio, `SEM GTIN` nem igual ao `Código`. GTIN válido repetido no arquivo → recusa a
segunda linha; GTIN válido já usado por **outro código** no banco → recusa a linha. Mesmo produto (mesmo código) com o
mesmo GTIN → ok.

### I5. Categorias
Chave de comparação = nome sem acento, maiúsculo, com espaços internos colapsados; o nome gravado é o do arquivo
(trim, máx. 80). Resolver por `(pai, chave)` contra as categorias da empresa (raiz = `CategoriaPaiId` nulo); criar o
que faltar na transação, na ordem Classe → Grupo → Sub-Grupo, `Ativa=1`, `Ordem` sequencial. Grupo sem Classe ou
Sub-Grupo sem Grupo: usa o nível preenchido mais profundo cujo pai existe, com aviso.

### I6. Saldo inicial e idempotência
Tabela `ImportacoesProduto (CompanyId, ProductCode, ImportadoEm, HashArquivo, QtdInicial, PK(CompanyId, ProductCode))`
na migração `db/migration/V7__importacao_produtos.sql`. Na confirmação, para cada produto **novo** com quantidade > 0:
`RegistradorEstoque.aplicar(companyId, produtoId, qtd, EstoqueTipos.ENTRADA, custo, origem("importacao", hashArquivo,
"Saldo inicial da importação", usuário))` e insere a linha em `ImportacoesProduto`. Produto existente ou código já
presente em `ImportacoesProduto` → sem movimento. Negativo → 0 com aviso. Quantidade com até 4 casas.

Classe da tabela no catálogo do modo loja: `FORA` (controle local; não sobe). Conferir que `GuardaCatalogoTest`
exige a classificação de toda tabela nova.

Alternativa descartada: gravar `ProductQnt` direto no INSERT — saldo sem movimentação quebra o histórico e o
registrador único (decisão D2 da Fase 3).

### I7. Desempenho
3.252 produtos × (1 INSERT + 1 registrador) + ~635 categorias numa transação. Pré-carregar em memória os mapas
(código→id, gtin→código, categorias) com 3 consultas; usar `batchUpdate` onde o SQL atual permitir. Meta: < 60 s no
SQL Express da loja; medir no teste com o arquivo sintético de 3.300 linhas.

No modo loja, a confirmação gera ~10 mil linhas no Change Tracking; o envio existente as leva em lotes. Aviso no
relatório da tela: "a nuvem recebe tudo em alguns minutos".

### I8. Tela no Quack Estoque
Item novo "Importar planilha" em `CadastrosView` (ou no menu de Produtos), visível para administrador/gerente:
1. escolher arquivo (`FileChooser`, `*.csv`); 2. chamar `simular`, mostrar cartões com os totais, a tabela do resumo
fiscal e abas "Recusados" e "Avisos"; 3. botões "Exportar recusados/avisos (CSV)" (usa `Csv` existente) e
"Importar" (habilitado só após simulação sem erro de arquivo, com confirmação "Importar N produtos e lançar o estoque
inicial de M produtos?"); 4. resultado da confirmação e atualização da lista de produtos. Upload com indicador
`Carregando` e timeout de 5 min no cliente HTTP.

### I9. Testes com dados anonimizados
O arquivo real não entra no repositório. Gerar `src/test/resources/importacao/amostra-legado.csv` (ISO-8859-1,
mesmo cabeçalho de 114 colunas) com ~40 linhas sintéticas cobrindo os casos (etiqueta curta, EAN válido/inválido,
repetido, vazio, negativo, fracionado, sem Classe, CFOP vazio, preço zero, acentos) e um gerador de 3.300 linhas para
o teste de volume. O teste manual com o arquivo real é tarefa separada.

## Risks / Trade-offs

- [Etiquetas com código de barras inválido (~220) não acham o produto no caixa] → aparecem nos avisos com o código
  original; a loja reimprime pelo Quack Estoque (etiqueta com o código do produto). Tabela de códigos alternativos fica
  para outra change, se necessário.
- [Dados fiscais mistos (CST e CSOSN) podem gerar rejeição na NFC-e] → importados como vieram; o resumo fiscal vai ao
  contador antes da primeira venda; a correção em massa pode ser feita depois pelo cadastro.
- [Unidade `m2` em produtos vendidos por metro (ex.: caibro "MT")] → importa como veio (em maiúsculas); aviso para
  unidades `M2` cujo nome contém `MT`, para revisão.
- [Transação longa trava o caixa] → importar antes de ligar os caixas (ordem no CHECKLIST); transação ≤ 60 s.
- [Upload grande aumenta superfície] → limite só nas duas rotas, perfis administrador/gerente.

## Migration Plan

1. Implementar e testar; publicar o pacote do servidor da loja e o Quack Estoque novos.
2. Na loja nova: criar a empresa, instalar o servidor (`instalar.ps1`), entrar no Quack Estoque como administrador,
   simular com o arquivo real, levar o resumo fiscal ao contador se necessário, confirmar.
3. Conferir uma amostra (10 produtos, 2 categorias, saldo de 5 produtos) no Quack Estoque e, alguns minutos depois, na
   nuvem; só então ligar os 3 caixas.
Rollback: a importação não apaga nada; em caso de erro de mapeamento antes da operação, restaurar o backup da
instalação (banco novo) e importar de novo após a correção.

## Open Questions

- Nenhuma que mude o plano. A semântica de `Preço Venda Tab.3` como preço de balcão foi confirmada pelo usuário.
