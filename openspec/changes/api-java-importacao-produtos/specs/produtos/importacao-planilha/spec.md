# Spec Delta

## Purpose

Permite trazer o cadastro de produtos de outro sistema (com categorias, preços, dados fiscais e saldo inicial) a
partir da planilha exportada por ele, conferindo antes o que vai entrar e gravando tudo ou nada.

## ADDED Requirements

### Requirement: Simular antes de gravar
A importação SHALL ter uma etapa de simulação que lê o arquivo, valida cada linha e devolve o relatório (novos,
atualizados, recusados com motivo, avisos, categorias a criar, produtos com saldo inicial e soma do estoque a lançar)
sem gravar nada. Só administrador ou gerente da empresa podem simular ou confirmar.

#### Scenario: Simulação não grava
- **WHEN** um gerente simula a planilha de 3.252 produtos numa empresa sem produtos
- **THEN** o relatório indica 3.252 novos e nenhuma linha é gravada em produtos, categorias ou movimentações

#### Scenario: Perfil sem permissão
- **WHEN** um atendente ou estoquista chama a simulação ou a confirmação
- **THEN** a resposta é 403

### Requirement: Confirmar grava tudo ou nada
A confirmação SHALL repetir a validação sobre o mesmo arquivo e gravar produtos, categorias e saldos iniciais numa
única transação. Se qualquer gravação falhar, nada do arquivo MUST ficar gravado. Linhas recusadas na validação não
impedem as demais.

#### Scenario: Falha no meio
- **WHEN** a gravação do produto de número 2.000 falha por erro do banco
- **THEN** a resposta indica erro e a empresa fica sem nenhum dos produtos do arquivo

#### Scenario: Linha recusada não bloqueia
- **WHEN** o arquivo tem uma linha sem `Código`
- **THEN** essa linha aparece como recusada com o motivo e as outras são gravadas

### Requirement: Reconhecer o layout pelo cabeçalho
O arquivo SHALL ser lido como CSV com separador `;` e codificação ISO-8859-1 (ou UTF-8 com BOM), localizando as colunas
pelo nome no cabeçalho, em qualquer ordem. Faltando uma coluna obrigatória (`Código`, `Descrição Completa`,
`Unidade Venda`, `Código NCM`, `Preço Venda Tab.3`), o arquivo inteiro MUST ser recusado com a lista das que faltam.

#### Scenario: Colunas fora de ordem
- **WHEN** o arquivo traz as mesmas colunas em outra ordem
- **THEN** o resultado da simulação é igual ao do arquivo original

#### Scenario: Coluna obrigatória ausente
- **WHEN** o cabeçalho não tem `Preço Venda Tab.3`
- **THEN** a simulação responde 400 citando a coluna faltante e nenhuma linha é analisada

#### Scenario: Acentos preservados
- **WHEN** a descrição no arquivo ISO-8859-1 é `BOCAL RECEPTACULO DE LOUÇA E-27`
- **THEN** o produto é gravado com `LOUÇA` corretamente acentuado

### Requirement: Valores no formato brasileiro
Valores monetários e quantidades SHALL ser lidos no formato brasileiro (`R$ 1.234,56`, `0,5`); célula vazia em campo
numérico vale zero. Valor que não é número MUST recusar a linha citando a coluna.

#### Scenario: Preço com símbolo e milhar
- **WHEN** `Preço Venda Tab.3` é `R$ 1.234,56`
- **THEN** o preço de venda gravado é 1234.56

#### Scenario: Número inválido
- **WHEN** `Qtd.Estoque (Física)` é `abc`
- **THEN** a linha é recusada com o motivo citando `Qtd.Estoque (Física)`

### Requirement: Código do produto e GTIN
O código do produto SHALL ser o `Código` do sistema anterior. O GTIN SHALL ser o `Código de Barra` só quando for um
GTIN válido (8, 12, 13 ou 14 dígitos com dígito verificador correto); caso contrário MUST ser `SEM GTIN`, com aviso
quando o código não era vazio nem igual ao `Código`.

#### Scenario: Etiqueta antiga
- **WHEN** a linha tem `Código` 6398 e `Código de Barra` 6398
- **THEN** o produto fica com código `6398`, GTIN `SEM GTIN`, sem aviso, e o caixa encontra o produto lendo `6398`

#### Scenario: EAN válido
- **WHEN** `Código de Barra` é `7898105774222`
- **THEN** o GTIN gravado é `7898105774222`

#### Scenario: Código de barras inválido
- **WHEN** `Código de Barra` tem 13 dígitos com dígito verificador errado
- **THEN** o GTIN gravado é `SEM GTIN` e o relatório traz um aviso com o código original

#### Scenario: GTIN repetido
- **WHEN** duas linhas trazem o mesmo GTIN válido
- **THEN** a segunda linha é recusada com o motivo `GTIN já usado pelo código <Código da primeira>`

### Requirement: Preço, custo e cadastro
O preço de venda SHALL vir de `Preço Venda Tab.3`; custo de compra de `Vl.Custo Compra (C/Impostos)`, com custo médio e
sem impostos nas colunas próprias. Marca, fabricante, referência, unidades, embalagem, estoques mínimo e máximo,
localização, comissão, desconto máximo, markup, peso e dimensões SHALL ser copiados quando existirem.

#### Scenario: Preço de venda da Tab.3
- **WHEN** a linha tem `Preço Venda Base` R$ 6,00 e `Preço Venda Tab.3` R$ 6,90
- **THEN** o preço de venda gravado é 6.90

#### Scenario: Preço zero
- **WHEN** `Preço Venda Tab.3` é R$ 0,00
- **THEN** o produto é importado com preço zero e o relatório traz um aviso `sem preço de venda`

### Requirement: Dados fiscais como vieram
NCM, CEST, CFOP, origem, CST, CSOSN, alíquota de ICMS e CSTs de PIS e COFINS SHALL ser gravados como estão no arquivo,
sem escolher regime tributário. O relatório SHALL resumir as combinações encontradas (CST, CSOSN, CFOP) com a contagem.
NCM sem 8 dígitos MUST recusar a linha.

#### Scenario: Resumo fiscal
- **WHEN** a simulação termina
- **THEN** o relatório lista, por exemplo, `CST 060 / CSOSN 500 / CFOP 5405: 2.038 produtos`

#### Scenario: CFOP vazio
- **WHEN** a linha não tem `CFOP`
- **THEN** o produto recebe `5102` se o CST for `000`, `5405` se for `060`, e o relatório traz um aviso

### Requirement: Categorias em três níveis
`Classe`, `Grupo` e `Sub-Grupo` SHALL virar categorias encadeadas (Grupo dentro de Classe, Sub-Grupo dentro de Grupo),
reaproveitando categoria de mesmo nome (sem diferenciar maiúsculas, acentos e espaços nas pontas) sob o mesmo pai. O
produto fica no nível mais profundo preenchido; sem `Classe`, fica sem categoria.

#### Scenario: Categoria criada uma vez
- **WHEN** 561 linhas têm a Classe `MATERIAIS HIDRAULICOS`
- **THEN** existe uma única categoria raiz `MATERIAIS HIDRAULICOS` e as subcategorias delas ficam dentro dela

#### Scenario: Mesmo nome em pais diferentes
- **WHEN** o Sub-Grupo `ACESSORIOS` aparece sob dois Grupos diferentes
- **THEN** são criadas duas categorias `ACESSORIOS`, uma em cada Grupo

### Requirement: Saldo inicial com movimentação
Para cada produto novo com `Qtd.Estoque (Física)` maior que zero, a confirmação SHALL lançar uma movimentação de
entrada com origem `importacao`, a quantidade (aceitando fração) e o custo de compra. Saldo negativo MUST entrar como
zero, com aviso. O estoque inicial de um produto SHALL ser lançado uma única vez por empresa.

#### Scenario: Entrada inicial
- **WHEN** um produto novo tem `Qtd.Estoque (Física)` 24 e custo R$ 1,33
- **THEN** o saldo do produto é 24 e existe uma movimentação `entrada` de 24, custo 1,33, origem `importacao`

#### Scenario: Saldo negativo
- **WHEN** `Qtd.Estoque (Física)` é -3
- **THEN** o produto é importado com saldo zero, sem movimentação, e o relatório traz um aviso

#### Scenario: Reimportação não duplica estoque
- **WHEN** a mesma planilha é confirmada uma segunda vez
- **THEN** nenhum produto é criado, os existentes têm cadastro e preço atualizados e nenhuma movimentação nova é lançada

### Requirement: Atualizar produto existente
Produto já existente com o mesmo código SHALL ter cadastro, preços, dados fiscais e categoria atualizados pelo arquivo,
mantendo o saldo atual e o histórico de movimentações.

#### Scenario: Preço atualizado
- **WHEN** o produto `2769` já existe com preço 6,50 e o arquivo traz Tab.3 de 6,90
- **THEN** o preço passa a 6,90, o saldo não muda e o relatório conta o produto como atualizado

### Requirement: Importação pelo Quack Estoque
O Quack Estoque SHALL ter a tela "Importar planilha" para administrador ou gerente: escolher o arquivo, ver o resumo da
simulação com os recusados e avisos (exportáveis em CSV) e só então confirmar.

#### Scenario: Confirmar depois de simular
- **WHEN** o usuário escolhe o arquivo
- **THEN** a tela mostra o resumo da simulação e o botão "Importar" só fica disponível depois dele

#### Scenario: Exportar recusados
- **WHEN** a simulação tem linhas recusadas
- **THEN** o usuário pode salvar um CSV com código, descrição e motivo de cada linha recusada
