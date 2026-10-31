# Spec Delta

## Purpose

Aplicativo desktop de estoque (JavaFX) para estoquista, gerente e administrador: entrada de mercadorias, baixas e
ajustes, movimentações e cadastro de produtos, falando com a mesma API do Hórus PDV.

## ADDED Requirements

### Requirement: Acesso por perfil
O aplicativo SHALL autenticar pelo login da API (mesmo endereço padrão de produção do caixa, alterável na tela de
login) e liberar funções por perfil: administrador e gerente acessam tudo; estoquista acessa entrada, ajustes,
inventário (sem aplicar acerto), consulta de produtos e etiquetas, sem ver custo, margem ou relatórios de valor.
Caixa e atendente não entram.

#### Scenario: Estoquista não vê custo
- **WHEN** um estoquista abre a ficha de um produto
- **THEN** o aplicativo esconde preço de custo e margem, e a API não é chamada em rotas de valor

#### Scenario: Perfil caixa
- **WHEN** um usuário com perfil caixa faz login no aplicativo de estoque
- **THEN** o aplicativo recusa o acesso informando que o aplicativo é para estoque e gerência

#### Scenario: Sessão expirada
- **WHEN** qualquer chamada à API responde 401 depois do login
- **THEN** o aplicativo volta à tela de login mantendo o rascunho da tela aberta (entrada ou contagem não enviada)

### Requirement: Entrada por NF-e
O aplicativo SHALL importar uma NF-e de compra pelo arquivo XML ou pela chave de acesso (consulta na SEFAZ),
mostrar fornecedor, número, valores e itens, e exigir que cada item seja vinculado a um produto existente ou novo,
com fator de conversão de unidade (ex.: caixa com 12) antes de confirmar.

#### Scenario: Item sem vínculo
- **WHEN** a nota tem um item cujo código do fornecedor/GTIN não corresponde a nenhum produto
- **THEN** o aplicativo marca o item como pendente e só libera a confirmação depois de vincular ou cadastrar o produto

#### Scenario: Conversão de unidade
- **WHEN** o item da nota vem como 5 CX e o fator informado é 12
- **THEN** a entrada no estoque é de 60 unidades e o custo unitário é o custo da caixa dividido por 12

#### Scenario: Nota já importada
- **WHEN** a mesma chave de acesso é importada de novo
- **THEN** o aplicativo mostra a mensagem da API de nota já lançada e não duplica o estoque

### Requirement: Lote, validade e preço na entrada
Na conferência da entrada (NF-e ou manual), o aplicativo SHALL permitir informar lote e validade por item (exigidos
quando o produto controla validade) e, opcionalmente, atualizar o preço de venda com base no novo custo e na margem,
mostrando o preço atual e o sugerido.

#### Scenario: Produto com controle de validade
- **WHEN** um item de produto que controla validade é confirmado sem data de validade
- **THEN** o aplicativo pede a validade (ou aceita o prazo padrão da categoria, quando houver) antes de enviar

#### Scenario: Atualizar preço de venda
- **WHEN** o gerente marca "atualizar preço" num item com custo novo
- **THEN** a entrada é enviada com o novo preço de venda e o produto entra automaticamente na fila de etiquetas

### Requirement: Entrada manual
O aplicativo SHALL lançar entrada sem NF-e (cupom, recibo ou sem documento) com data, fornecedor opcional, número
do documento, itens com quantidade, unidade de compra, fator e custo, e anexo opcional do comprovante.

#### Scenario: Entrada manual com cupom
- **WHEN** o estoquista lança 3 itens de um cupom de atacarejo e confirma
- **THEN** o estoque dos 3 produtos aumenta e a entrada aparece na lista de notas de entrada como documento "cupom"

### Requirement: Consulta e estorno de entradas
O aplicativo SHALL listar as entradas (NF-e e manuais) com busca, abrir os itens, baixar o XML e permitir ao gerente
estornar uma entrada com justificativa.

#### Scenario: Estornar entrada
- **WHEN** o gerente estorna uma nota de entrada com justificativa
- **THEN** a API retira do estoque o que a nota havia dado entrada e a nota aparece como estornada

### Requirement: Baixas e ajustes avulsos
O aplicativo SHALL lançar ajuste por produto do tipo entrada, saída, uso interno ou perda, com quantidade e motivo
obrigatório (lote e validade opcionais na entrada), mostrando o saldo antes e depois.

#### Scenario: Registrar perda
- **WHEN** o estoquista registra perda de 2 unidades com o motivo "embalagem avariada"
- **THEN** o estoque cai 2 unidades e a movimentação "perda" aparece no histórico com o usuário e o motivo

#### Scenario: Saída maior que o saldo
- **WHEN** a saída informada é maior que o estoque atual
- **THEN** o aplicativo mostra a recusa da API e não altera o saldo

### Requirement: Histórico de movimentações
O aplicativo SHALL mostrar as movimentações de estoque por produto e gerais, com tipo, quantidade, saldo anterior e
novo, motivo, usuário e data, filtráveis por tipo e período, e exportáveis em CSV.

#### Scenario: Filtrar perdas do mês
- **WHEN** o gerente filtra o histórico por tipo "perda" no mês atual
- **THEN** o aplicativo lista só as perdas do período com o total de quantidade

### Requirement: Cadastro de produtos
O aplicativo SHALL buscar produtos por nome, código ou GTIN e permitir a gerente/administrador criar e editar
produtos (código, GTIN, nome, unidade, custo, preço de venda, estoque mínimo e máximo, categoria, fornecedor e
controle de validade) e alterar só o preço de venda.

#### Scenario: Alterar preço
- **WHEN** o gerente altera o preço de venda de um produto
- **THEN** a API grava o novo preço e o aplicativo oferece colocar o produto na fila de etiquetas

#### Scenario: GTIN duplicado
- **WHEN** o gerente salva um produto com GTIN já usado por outro produto
- **THEN** o aplicativo mostra a mensagem de duplicidade da API e não salva

### Requirement: Categorias e fornecedores
O aplicativo SHALL permitir a gerente/administrador consultar, criar e editar categorias (até 2 níveis) e
fornecedores, usando as regras já existentes na API.

#### Scenario: Novo fornecedor durante a entrada
- **WHEN** a NF-e importada é de um fornecedor ainda não cadastrado
- **THEN** o aplicativo oferece cadastrar o fornecedor com os dados do emitente da nota antes de confirmar
