## Purpose

Permite cadastrar promoções com 6 tipos diferentes (desconto %, desconto R$, preço fixo, leve X pague Y, combo quantidade, preço atacado), com vigência temporal, aplicação automática no PDV, integração fiscal via `vDesc` e relatório de resultado.

## Requirements

### Requirement: CRUD de promoções com vigência
O sistema DEVE permitir criar, editar, ativar/desativar e excluir promoções com período de vigência e vínculo a produtos ou categorias.

#### Scenario: Criação de promoção leve X pague Y
- **WHEN** o gerente cria promoção "Sexta da Cerveja" tipo `leve_x_pague_y` com QuantidadeLeva=3, QuantidadePaga=2, vigência 13/09 00:00 a 13/09 23:59, vinculando 12 produtos de cerveja
- **THEN** o sistema persiste a promoção com status ativa e vincula os 12 produtos via `PromocaoProdutos`.

#### Scenario: Criação de promoção por categoria
- **WHEN** o gerente cria promoção "20% Laticínios" tipo `desconto_percentual` com ValorDesconto=20 e `CategoriaId` = "Laticínios"
- **THEN** o sistema aplica a promoção a todos os produtos da categoria "Laticínios" e suas subcategorias.

#### Scenario: Desativação manual de promoção
- **WHEN** o gerente desativa uma promoção antes do fim da vigência
- **THEN** o sistema marca `Ativa = 0` e o PDV para de aplicar a promoção imediatamente na próxima consulta.

#### Scenario: Promoção expirada automaticamente
- **WHEN** a data/hora atual ultrapassa `FimVigencia` de uma promoção
- **THEN** o endpoint `GET /api/Promocao/ativas` não a retorna e o PDV não a aplica.

### Requirement: 6 tipos de promoção suportados
O sistema DEVE suportar os seguintes tipos de promoção com seus parâmetros específicos.

#### Scenario: Desconto percentual
- **WHEN** promoção tipo `desconto_percentual` com ValorDesconto=20 é aplicada a um produto de R$10,00
- **THEN** o preço promocional é R$8,00 (desconto de R$2,00 = 20%).

#### Scenario: Desconto valor fixo
- **WHEN** promoção tipo `desconto_valor` com ValorDesconto=2 é aplicada a um produto de R$10,00
- **THEN** o preço promocional é R$8,00 (desconto de R$2,00).

#### Scenario: Preço fixo
- **WHEN** promoção tipo `preco_fixo` com PrecoFixo=18.90 é aplicada a um produto de R$22,90
- **THEN** o preço promocional é R$18,90 (desconto de R$4,00).

#### Scenario: Leve X pague Y
- **WHEN** promoção tipo `leve_x_pague_y` com QuantidadeLeva=3, QuantidadePaga=2 é aplicada e o cliente adiciona 3 unidades de um produto de R$5,00
- **THEN** o total é R$10,00 (paga 2, ganha 1 grátis), desconto de R$5,00 distribuído entre os 3 itens.

#### Scenario: Combo quantidade
- **WHEN** promoção tipo `combo_quantidade` com QuantidadeLeva=3, PrecoFixo=10.00 é aplicada e o cliente adiciona 3 unidades de um produto de R$4,50
- **THEN** o total é R$10,00 (combo 3 por R$10), desconto de R$3,50.

#### Scenario: Preço atacado
- **WHEN** promoção tipo `preco_atacado` com QuantidadeMinima=5, PrecoFixo=1.50 é aplicada e o cliente adiciona 6 unidades de um produto de R$2,00
- **THEN** o preço unitário cai para R$1,50, total R$9,00 (desconto de R$3,00).

#### Scenario: Quantidade abaixo do mínimo para atacado
- **WHEN** promoção tipo `preco_atacado` com QuantidadeMinima=5 e o cliente adiciona 3 unidades
- **THEN** o preço permanece o normal (R$2,00 cada), sem desconto.

### Requirement: Aplicação automática no PDV
O sistema DEVE aplicar promoções ativas automaticamente ao adicionar itens ao carrinho, sem intervenção do operador.

#### Scenario: Promoção aplicada automaticamente
- **WHEN** o operador adiciona ao carrinho um produto que possui promoção ativa e dentro da vigência
- **THEN** o sistema aplica o desconto automaticamente, exibe badge "PROMO" no item e mostra o preço original riscado com o preço promocional.

#### Scenario: Conflito de promoções — maior desconto vence
- **WHEN** um produto está vinculado a 2 promoções ativas (uma de 10% e outra de 15%)
- **THEN** o sistema aplica a promoção de 15% (maior desconto) e ignora a de 10%.

#### Scenario: Promoções não acumulam
- **WHEN** um produto está em promoção de 20% e também em combo "3 por R$10"
- **THEN** o sistema aplica apenas UMA promoção por item (a de maior desconto efetivo), nunca ambas.

### Requirement: Integração fiscal (NFC-e)
O sistema DEVE lançar o desconto da promoção no campo `vDesc` do item na NFC-e.

#### Scenario: Desconto no XML da NFC-e
- **WHEN** uma venda com produto promocional é transmitida para a SEFAZ
- **THEN** o XML do item contém `<vDesc>` com o valor do desconto aplicado pela promoção e `<vProd>` com o valor cheio (preço original × quantidade).

### Requirement: Registro de desconto na venda
O sistema DEVE persistir o desconto aplicado por item na tabela `VendaItens`.

#### Scenario: Desconto registrado no item de venda
- **WHEN** uma venda com promoção é finalizada
- **THEN** o campo `Desconto` de `VendaItens` é preenchido com o valor do desconto e `ItemTotal` reflete o valor líquido (após desconto).

### Requirement: Relatório de resultado de promoção
O sistema DEVE fornecer relatório de desempenho por promoção.

#### Scenario: Resultado da promoção
- **WHEN** o gerente consulta `GET /api/Promocao/{id}/resultado`
- **THEN** o sistema retorna: quantidade de vendas afetadas, receita bruta (sem desconto), receita líquida (com desconto), total de desconto concedido e margem líquida.
