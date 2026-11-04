# Spec Delta

## Purpose

Leva para o servidor da loja, como comandos, as alterações que o dono faz na nuvem (painel web, MCP) para a empresa da
loja. Mantém a loja como única autoridade, protege a réplica de gravações indevidas e devolve à loja o resultado
fiscal enquanto a emissão está na nuvem.

## ADDED Requirements

### Requirement: Edições permitidas na nuvem
Alterações feitas na nuvem em produtos (inclusive preços), clientes, fornecedores, categorias, promoções, usuários e
configurações da empresa SHALL virar comandos para a loja. Colunas que só operações da loja mudam (saldo de estoque,
saldo de fiado, contadores) MUST NOT entrar no comando.

#### Scenario: Dono muda o preço pela nuvem
- **WHEN** o dono altera o preço de venda de um produto no painel da nuvem
- **THEN** em até 1 minuto, com internet, o produto tem o novo preço na loja e o próximo caixa já vende por ele

#### Scenario: Cliente novo pela nuvem
- **WHEN** um cliente é cadastrado no painel da nuvem
- **THEN** o cliente passa a existir na loja

### Requirement: Detecção das edições da nuvem
O receptor SHALL perceber as gravações feitas na nuvem pela API .NET ou por qualquer outro meio, sem mudar a API .NET.
Gravações feitas pelo próprio receptor ao aplicar a sincronização MUST NOT virar comando.

#### Scenario: Sincronização não volta como comando
- **WHEN** a nuvem aplica um lote vindo da loja
- **THEN** nenhum comando é criado por causa dessas linhas

### Requirement: Aplicação com conferência de base
Cada comando SHALL levar, por coluna, o valor novo e o valor que a nuvem tinha da loja antes da edição. A loja SHALL
aplicar a coluna só se o valor atual for igual a esse valor de base. Se a loja mudou a mesma coluna nesse meio-tempo,
vale a loja e o comando termina como conflito, com as duas versões.

#### Scenario: Edição na loja e na nuvem ao mesmo tempo
- **WHEN** o preço de um produto é mudado na loja e, antes de sincronizar, também na nuvem
- **THEN** o preço da loja prevalece nas duas pontas e o comando fica como conflito, com os dois valores registrados

#### Scenario: Colunas diferentes
- **WHEN** a loja muda o estoque de um produto e a nuvem muda a descrição do mesmo produto
- **THEN** as duas mudanças valem

### Requirement: Edição da nuvem não se perde
Uma edição feita na nuvem MUST NOT ser apagada pela chegada de um lote da loja com a versão antiga da linha. O receptor
SHALL criar o comando antes de aplicar o lote.

#### Scenario: Venda logo depois da edição de preço
- **WHEN** o dono muda o preço na nuvem e, antes do comando chegar à loja, a loja sincroniza uma venda que alterou o
  saldo do mesmo produto
- **THEN** o comando de preço é criado e aplicado na loja, e as duas pontas terminam com o preço novo e o saldo da venda

### Requirement: Entrega e resultado dos comandos
A loja SHALL buscar os comandos na nuvem, sempre por iniciativa dela, e aplicá-los na ordem de criação. Para cada um,
SHALL devolver o resultado: aplicado, conflito ou falhou. Comando que falhar 5 vezes SHALL ficar parado com o erro e
gerar alerta, sem travar os seguintes.

#### Scenario: Loja sem internet
- **WHEN** o dono altera preços enquanto a loja está sem internet
- **THEN** os comandos ficam guardados na nuvem e são aplicados na loja, em ordem, quando a internet volta

### Requirement: Edição indevida em tabela da loja
Gravação feita na nuvem em tabela que só a loja grava (vendas, caixa, estoque, fiado, inventário, compras) SHALL ser
guardada em quarentena com a versão da nuvem e gerar alerta. Linha alterada ou excluída SHALL voltar ao estado da loja.
Linha criada só na nuvem MUST NOT ser excluída automaticamente.

#### Scenario: Venda registrada na nuvem por engano
- **WHEN** um caixa ainda apontado para a nuvem registra uma venda da empresa da loja
- **THEN** a venda é copiada para a quarentena e gera alerta

#### Scenario: Estoque ajustado na nuvem
- **WHEN** o saldo de um produto é alterado direto na nuvem
- **THEN** o saldo volta ao valor da loja e o alerta registra o valor tentado

### Requirement: Resultado fiscal vindo da nuvem
Até a Fase 7, a nuvem emite as NFC-e da loja. O resultado da emissão gravado na nuvem (situação, protocolo, XML,
motivo, tentativas) SHALL descer para a loja e ser aplicado nela. A loja SHALL manter o número que reservou.

#### Scenario: Nota autorizada na nuvem
- **WHEN** a nuvem autoriza a NFC-e de uma venda feita na loja
- **THEN** a loja passa a mostrar a nota como autorizada, com o mesmo número e chave
