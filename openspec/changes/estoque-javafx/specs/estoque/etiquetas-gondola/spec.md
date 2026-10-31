# Spec Delta

## Purpose

Impressão de etiquetas de gôndola e de produto pelo aplicativo de estoque, na impressora térmica instalada no
Windows, com preço, nome, unidade e código de barras legível pelo leitor do caixa.

## ADDED Requirements

### Requirement: Fila de etiquetas
O aplicativo SHALL manter uma fila de etiquetas com produto e número de cópias, alimentada por busca manual, pelos
itens de uma entrada confirmada (cópias = quantidade recebida ou 1, à escolha) e pelos produtos com preço alterado
desde a última impressão, permitindo alterar cópias e remover itens antes de imprimir.

#### Scenario: Etiquetas de uma entrada
- **WHEN** o estoquista escolhe "imprimir etiquetas" ao confirmar uma NF-e com 8 itens
- **THEN** a fila recebe os 8 produtos com 1 cópia cada (ou a quantidade recebida, se escolhido)

#### Scenario: Preço alterado
- **WHEN** o preço de venda de 5 produtos mudou desde a última impressão de etiqueta deles
- **THEN** o botão "preços alterados" coloca esses 5 produtos na fila

#### Scenario: Fila preservada
- **WHEN** o aplicativo é fechado com etiquetas na fila
- **THEN** a fila é restaurada na próxima abertura no mesmo terminal

### Requirement: Modelos de etiqueta
O aplicativo SHALL oferecer modelos por tamanho (no mínimo 60x40 mm, 50x30 mm, 40x25 mm e 70x35 mm em rolo, e a
folha A4 Pimaco 6180) com preço em destaque, nome (quebra em até 2 linhas), unidade de venda, código interno e
código de barras, e permitir criar um tamanho personalizado (largura, altura, colunas, espaçamento e margens).

#### Scenario: Pré-visualização
- **WHEN** o estoquista escolhe o modelo 40x25 mm
- **THEN** o aplicativo mostra a pré-visualização em tamanho real de cada etiqueta da fila antes de imprimir

#### Scenario: Nome longo
- **WHEN** o nome do produto não cabe em 2 linhas no modelo escolhido
- **THEN** o nome é abreviado com reticências sem sobrepor o preço ou o código de barras

#### Scenario: Produto em promoção
- **WHEN** o produto tem promoção ativa de preço fixo
- **THEN** a etiqueta mostra o preço promocional em destaque e o preço normal riscado, com o período da promoção

### Requirement: Código de barras na etiqueta
A etiqueta SHALL imprimir EAN-13 quando o GTIN do produto for um EAN-13 válido e Code128 do código interno nos
demais casos, com zona de silêncio e tamanho que o leitor do caixa consiga ler, e o número legível abaixo.

#### Scenario: Produto sem GTIN
- **WHEN** o produto não tem GTIN
- **THEN** a etiqueta usa Code128 do código interno, e ao bipar no caixa o produto é encontrado

#### Scenario: GTIN inválido
- **WHEN** o GTIN cadastrado tem dígito verificador errado
- **THEN** a etiqueta usa Code128 do código interno e o aplicativo avisa que o GTIN do produto é inválido

### Requirement: Impressão pela térmica do Windows
O aplicativo SHALL imprimir as etiquetas na impressora escolhida (lista das impressoras do Windows), no tamanho de
papel do modelo, uma etiqueta por página no rolo, sem travar a tela, e guardar no terminal a impressora, o modelo e
um ajuste fino de deslocamento horizontal/vertical.

#### Scenario: Imprimir sem travar
- **WHEN** a fila tem 200 etiquetas e o estoquista manda imprimir
- **THEN** a impressão roda em segundo plano, a tela continua usável e um aviso informa o fim ou o erro da impressão

#### Scenario: Calibrar deslocamento
- **WHEN** a etiqueta sai 2 mm para a direita e o estoquista ajusta o deslocamento em -2 mm
- **THEN** as próximas impressões saem centralizadas e o ajuste continua salvo depois de reabrir o aplicativo

#### Scenario: Registro da impressão
- **WHEN** a impressão de um produto é concluída
- **THEN** o aplicativo grava no terminal o preço impresso, para o filtro de "preços alterados"
