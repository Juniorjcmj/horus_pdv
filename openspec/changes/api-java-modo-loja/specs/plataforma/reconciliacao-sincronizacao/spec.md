# Spec Delta

## Purpose

Prova periódica de que a réplica da nuvem é igual à loja, e situação visível da sincronização com alertas. Assim uma
divergência, um atraso ou uma nota parada é percebida antes de virar problema na loja ou no fiscal.

## ADDED Requirements

### Requirement: Conferência diária
Uma vez por dia, e quando pedido, a loja SHALL comparar com a nuvem, por tabela sincronizada da empresa, a quantidade de
linhas e uma soma de verificação do conteúdo. Uma tabela divergente SHALL ser conferida por faixas da chave e as linhas
diferentes SHALL ser reenviadas da loja, até a conferência fechar.

#### Scenario: Linha alterada direto no banco da nuvem sem registro
- **WHEN** uma linha da réplica fica diferente da loja sem que o registro de mudanças perceba
- **THEN** a conferência acusa a tabela, reenvia a linha e termina sem diferença, com o resultado registrado

### Requirement: Conferência de saldos
A conferência SHALL também verificar, na loja, se o saldo de cada produto bate com a soma das movimentações e se o
saldo de fiado de cada cliente bate com os movimentos. Diferença SHALL gerar alerta com a lista, sem corrigir sozinha.

#### Scenario: Saldo de produto divergente
- **WHEN** o saldo de um produto não bate com as movimentações
- **THEN** o resultado da conferência lista o produto, os dois valores e gera alerta

### Requirement: Situação da sincronização
As duas pontas SHALL expor, só para administrador da empresa ou para o token do servidor, a situação da sincronização.
Ela inclui: última sincronização bem-sucedida, atraso, se está pausada e por quê, comandos pendentes e parados,
quarentena, NFC-e pendentes há mais de 10 minutos e o resultado da última conferência.

#### Scenario: Consulta da situação
- **WHEN** um administrador consulta a situação no servidor da loja
- **THEN** a resposta mostra o atraso, os comandos pendentes e a última conferência

#### Scenario: Caixa consulta a situação
- **WHEN** um usuário com perfil caixa consulta a situação
- **THEN** a resposta é 403

### Requirement: Alertas
SHALL gerar alerta, registrado em log de erro e enviado por e-mail ao administrador da empresa (no máximo um por motivo
a cada hora), quando houver:
- atraso acima de 15 minutos com internet;
- sincronização pausada;
- comando parado;
- nova entrada na quarentena;
- conferência com diferença;
- NFC-e pendente há mais de 30 minutos;
- falha no backup.

#### Scenario: Sincronização pausada
- **WHEN** um lote é recusado definitivamente
- **THEN** o administrador recebe um e-mail com o motivo, e outro só se a pausa continuar depois de uma hora
