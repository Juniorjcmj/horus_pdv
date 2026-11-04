# Spec Delta

## Purpose

Envio contínuo das mudanças do banco do servidor da loja para a nuvem, para que a nuvem tenha uma réplica fiel e
consistente da empresa, sem perder nem duplicar nada, mesmo com internet instável.

## ADDED Requirements

### Requirement: Envio das mudanças da loja
O servidor da loja SHALL enviar à nuvem o estado atual de toda linha das tabelas sincronizadas que for criada, alterada
ou excluída, inclusive as gravadas fora das rotas da API. O envio SHALL ser contínuo, com atraso de no máximo 1 minuto
quando há internet.

#### Scenario: Venda chega à nuvem
- **WHEN** a loja registra uma venda com internet
- **THEN** em até 1 minuto a venda, os itens, os pagamentos, a movimentação de estoque e o novo saldo do produto existem
  iguais no banco da nuvem

#### Scenario: Exclusão chega à nuvem
- **WHEN** uma linha sincronizada é excluída na loja
- **THEN** ela é excluída na nuvem

### Requirement: Lotes consistentes
Cada lote SHALL conter as mudanças até uma versão do banco da loja, lidas num retrato consistente. A nuvem SHALL aplicar
cada lote numa só transação, em ordem de dependência (pais antes de filhos; exclusões na ordem inversa). Uma transação
da loja MUST NOT aparecer pela metade na nuvem.

#### Scenario: Venda com nota no mesmo lote
- **WHEN** uma venda que emite NFC-e é registrada com a nota pendente
- **THEN** a venda e a nota pendente aparecem na nuvem na mesma transação, nunca a venda sem a nota

### Requirement: Aplicação idempotente
Reenviar um lote já aplicado SHALL ser aceito sem efeito. A nuvem SHALL guardar até que versão aplicou de cada loja, e a
loja SHALL continuar dessa versão depois de reiniciar ou de perder a resposta.

#### Scenario: Resposta perdida
- **WHEN** a nuvem aplica um lote e a resposta não chega à loja
- **THEN** a loja reenvia o lote, a nuvem confirma sem aplicar de novo e os dados ficam iguais

### Requirement: Escopo da empresa
A nuvem SHALL aplicar só linhas da empresa do token. Tabelas filhas sem coluna de empresa SHALL ser validadas pela linha
mãe. Uma linha de outra empresa SHALL fazer o lote inteiro ser recusado.

#### Scenario: Item de venda de outra empresa
- **WHEN** um lote traz um item de venda cuja venda pertence a outra empresa
- **THEN** o lote é recusado, nada é aplicado e a sincronização pausa com alerta

### Requirement: Tabelas fora da sincronização
Sessões, tokens de redefinição de senha, eventos processados do Caixa, tokens de integração e do Gateway e as tabelas
internas da sincronização MUST NOT ser enviadas. O histórico de auditoria SHALL ser enviado sem colidir com a numeração
própria da nuvem.

#### Scenario: Auditoria da loja na nuvem
- **WHEN** a loja grava registros de auditoria e eles são sincronizados duas vezes
- **THEN** cada registro aparece uma só vez na nuvem

### Requirement: Erros temporários e definitivos
Falha de rede, tempo esgotado, 408, 429 e 5xx SHALL gerar nova tentativa com espera crescente até 5 minutos, sem
limite de tentativas. Recusa definitiva (4xx) ou erro ao aplicar SHALL pausar a sincronização da loja e gerar alerta.
A loja MUST continuar vendendo normalmente durante a pausa.

#### Scenario: Internet cai por uma hora
- **WHEN** a loja fica uma hora sem internet vendendo nos 7 caixas
- **THEN** ao voltar a internet todas as mudanças da hora chegam à nuvem, na ordem, sem intervenção

#### Scenario: Lote recusado
- **WHEN** a nuvem recusa um lote como inválido
- **THEN** a loja para de enviar, registra o erro e alerta, e volta a enviar quando o problema é resolvido e a
  sincronização é retomada

### Requirement: Ressincronização completa
Se a loja ficar sem enviar além da retenção do registro de mudanças, ela SHALL detectar isso e enviar um retrato
completo da empresa. A nuvem SHALL então substituir a réplica e excluir o que não existir mais na loja.

#### Scenario: Loja offline além da retenção
- **WHEN** a loja volta depois de mais dias sem internet do que a retenção configurada
- **THEN** a loja envia o retrato completo e, ao final, a reconciliação não acusa diferença
