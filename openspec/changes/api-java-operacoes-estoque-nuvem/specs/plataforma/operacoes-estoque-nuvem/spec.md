# Spec Delta

## Purpose

Faz as operações de estoque lançadas na nuvem (entrada de nota, estorno, ajuste e saldo pela edição do produto) valerem
no servidor da loja, que continua a única autoridade sobre o saldo, sem nota em dobro e sem perder vendas da loja.

## ADDED Requirements

### Requirement: Entrada de nota feita na nuvem chega à loja
Uma entrada de nota confirmada na nuvem para uma empresa em modo loja SHALL passar a existir no servidor da loja com a
nota, os itens, os lotes, o de-para do fornecedor, o fornecedor e os produtos novos que ela criou, e o estoque da loja
SHALL subir pela quantidade de cada item.

#### Scenario: Dono lança NF-e de casa
- **WHEN** o dono confirma pela nuvem uma NF-e com 10 sacos de cimento de um produto que já existe na loja
- **THEN** em até 1 minuto, com a loja conectada, a nota aparece em Entradas no Quack Estoque da loja e o saldo do produto na loja sobe 10

#### Scenario: Nota com produto novo
- **WHEN** a nota lançada na nuvem cria um produto que não existia, com quantidade 5
- **THEN** o produto passa a existir na loja com saldo 5, e não 10

#### Scenario: Loja sem internet no momento do lançamento
- **WHEN** a nota é lançada na nuvem enquanto a loja está sem internet
- **THEN** a nota e o estoque entram na loja assim que a conexão volta, sem ação de ninguém

### Requirement: Saldo recalculado na loja
Toda movimentação de estoque criada na nuvem SHALL ser aplicada na loja pela diferença que ela representa (entrada
soma, saída subtrai) sobre o saldo atual da loja. O saldo da nuvem MUST NOT ser copiado para a loja. Depois da
aplicação, a nuvem SHALL passar a mostrar o saldo da loja.

#### Scenario: Venda na loja enquanto a nota é lançada na nuvem
- **WHEN** a loja tem 20 unidades, vende 3 que ainda não subiram, e a nuvem registra entrada de 10 calculada sobre 20
- **THEN** a loja fica com 27, a movimentação da loja mostra anterior 17 e novo 27, e a nuvem passa a mostrar 27

#### Scenario: Ajuste de estoque pelo MCP
- **WHEN** um ajuste de saída de 2 unidades é feito na nuvem pelo MCP
- **THEN** o saldo da loja cai 2 a partir do valor que a loja tinha

#### Scenario: Edição do saldo no cadastro do produto pela nuvem
- **WHEN** o dono muda o estoque de um produto de 8 para 12 no cadastro do painel da nuvem
- **THEN** a loja soma 4 ao seu saldo atual e a mudança não vai para a quarentena

### Requirement: Operação aplicada inteira ou recusada
O que a nuvem gravou numa mesma transação SHALL ser aplicado na loja numa única transação. Se qualquer parte falhar ou
conflitar, nada dessa operação MUST ficar gravado na loja e o resultado SHALL ser conflito ou falha, com o motivo.

#### Scenario: Falha no meio da nota
- **WHEN** a aplicação de uma nota de 5 itens falha no quarto item
- **THEN** nenhum dos 5 itens altera o estoque da loja e o resultado registra o motivo

### Requirement: Aplicação idempotente
Reaplicar uma operação, ou uma linha dela, que já está na loja MUST NOT alterar o estoque de novo nem duplicar linhas.

#### Scenario: Comando entregue duas vezes
- **WHEN** a loja recebe de novo uma operação de entrada que já tinha aplicado
- **THEN** o saldo e as linhas da loja não mudam e o resultado é aplicado

### Requirement: Nota em dobro
Se a loja já tem uma entrada ativa (não estornada) da mesma nota fiscal, a operação da nuvem com essa nota SHALL
terminar como conflito, sem alterar o estoque da loja, e o dono SHALL ser alertado com o número da nota.

#### Scenario: Mesma NF-e lançada na loja e na nuvem
- **WHEN** a NF-e 1234 é lançada na loja e, antes de sincronizar, também na nuvem
- **THEN** o estoque da loja sobe uma vez só, a operação da nuvem fica como conflito e o dono recebe o alerta "nota 1234 já lançada na loja"

#### Scenario: A nuvem se ajusta à loja depois do conflito
- **WHEN** uma operação da nuvem termina como conflito
- **THEN** depois da conferência, a nuvem mostra o saldo e as entradas da loja, sem a entrada recusada

### Requirement: Estorno de nota feito na nuvem
O estorno, na nuvem, de uma entrada que existe na loja SHALL marcar a entrada como estornada na loja e baixar do saldo
da loja as quantidades estornadas.

#### Scenario: Estorno pelo painel
- **WHEN** o dono estorna pela nuvem uma entrada de 10 unidades que está na loja
- **THEN** a entrada aparece estornada no Quack Estoque da loja e o saldo da loja cai 10

### Requirement: Conferência não apaga operação pendente
A conferência diária MUST NOT excluir da nuvem as linhas de uma operação de estoque da nuvem que ainda não teve
resultado da loja.

#### Scenario: Conferência com a loja offline desde o lançamento
- **WHEN** a conferência roda enquanto uma nota lançada na nuvem ainda não chegou à loja
- **THEN** a nota continua na nuvem e é aplicada na loja quando ela se conectar

### Requirement: Operações que continuam só na loja
Inventário, ordens de compra, vendas, caixa e fiado feitos na nuvem MUST continuar indo para a quarentena, como hoje.
Alterações na nuvem de linhas de entrada já existentes, além do estorno e do vínculo do XML, MUST continuar indo para
a quarentena.

#### Scenario: Inventário finalizado na nuvem
- **WHEN** um inventário é gravado na nuvem para uma empresa em modo loja
- **THEN** ele vai para a quarentena e não altera o estoque da loja

### Requirement: Compatibilidade de versões
A nuvem MUST NOT enviar operações de estoque a um servidor de loja que não as suporta. Para esse servidor, o
comportamento anterior (quarentena) SHALL continuar.

#### Scenario: Loja ainda com o jar antigo
- **WHEN** a nuvem já está atualizada e o servidor da loja ainda não
- **THEN** a nota lançada na nuvem vai para a quarentena como hoje, sem erro de comando na loja
