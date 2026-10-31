# Spec Delta

## Purpose

Inventário de estoque: contar fisicamente os produtos (loja toda ou parte dela), comparar com o saldo do sistema e
aplicar o acerto com autorização do gerente, deixando uma trilha de auditoria que não pode ser alterada.

## ADDED Requirements

### Requirement: Abertura de inventário
A API SHALL permitir a administrador, gerente e estoquista abrir um inventário com descrição e escopo: loja toda,
uma ou mais categorias/departamentos ou uma lista de produtos. O inventário nasce no status "em contagem" e guarda
quem abriu e quando. Só pode existir um inventário em contagem por escopo sobreposto na mesma empresa.

#### Scenario: Abrir inventário por categoria
- **WHEN** o estoquista abre um inventário com o escopo "Bebidas"
- **THEN** a API cria o inventário "em contagem" com os produtos ativos dessa categoria (e subcategorias) e devolve o número do inventário

#### Scenario: Escopo sobreposto
- **WHEN** já existe um inventário em contagem da loja toda e alguém tenta abrir outro para "Bebidas"
- **THEN** a API recusa com 409 e informa o número do inventário aberto

#### Scenario: Perfil sem acesso
- **WHEN** um usuário com perfil caixa ou atendente tenta abrir um inventário
- **THEN** a API responde 403

### Requirement: Registro de contagem
A API SHALL registrar cada contagem com produto, quantidade contada, quem contou, terminal, horário e o saldo do
sistema naquele instante. Contagens do mesmo produto se somam (prateleira + depósito), podem ser feitas por várias
pessoas ao mesmo tempo e só são aceitas enquanto o inventário está em contagem e o produto pertence ao escopo.

#### Scenario: Somar locais
- **WHEN** o produto "Arroz 5kg" recebe uma contagem de 10 (prateleira) e outra de 24 (depósito)
- **THEN** a quantidade contada do produto no inventário é 34 e as duas contagens ficam registradas separadamente

#### Scenario: Produto fora do escopo
- **WHEN** o estoquista bipa um produto que não pertence ao escopo do inventário
- **THEN** a API recusa a contagem informando que o produto está fora do escopo

#### Scenario: Contagem em inventário encerrado
- **WHEN** alguém envia uma contagem para um inventário aplicado ou cancelado
- **THEN** a API responde 409 e não grava a contagem

### Requirement: Correção e recontagem
A API SHALL permitir anular uma contagem lançada errada (registrando quem anulou e o motivo, sem apagar o registro)
e marcar produtos para recontagem.

#### Scenario: Anular contagem errada
- **WHEN** o estoquista anula a contagem de 240 lançada por engano no lugar de 24
- **THEN** a contagem fica marcada como anulada com autor e motivo, deixa de entrar na soma e continua visível na trilha

#### Scenario: Pedir recontagem
- **WHEN** o gerente marca "Leite 1L" para recontagem
- **THEN** o produto aparece como "recontar" na lista de divergências até receber uma nova contagem

### Requirement: Relatório de divergências
A API SHALL calcular, para cada produto do escopo, o saldo do sistema no momento da contagem, a quantidade contada,
a diferença em quantidade e o valor da diferença pelo custo, além dos produtos do escopo ainda não contados, com
totais de sobra, falta e valor líquido.

#### Scenario: Produto com falta
- **WHEN** o sistema mostrava 50 unidades de um produto de custo R$ 4,00 e foram contadas 46
- **THEN** a divergência do produto é -4 unidades e -R$ 16,00, e entra no total de faltas

#### Scenario: Produto não contado
- **WHEN** o inventário tem produtos do escopo sem nenhuma contagem
- **THEN** o relatório lista esses produtos como "não contados" e eles não entram no acerto, a menos que o gerente escolha zerar os não contados

### Requirement: Aplicação do acerto com autorização do gerente
A API SHALL aplicar o acerto somente com a senha de um gerente ou administrador e justificativa, numa única
transação: para cada produto contado ajusta o estoque pela diferença entre a contagem e o saldo do sistema no
momento da contagem, preservando vendas e entradas feitas depois, e registra uma movimentação do tipo "inventário".

#### Scenario: Venda durante a contagem
- **WHEN** um produto foi contado com 30 quando o sistema mostrava 32, e depois disso foram vendidas 5 unidades (saldo atual 27)
- **THEN** ao aplicar, o saldo passa a 25 (27 + (30 - 32)) e a movimentação registra anterior 27, novo 25, tipo "inventário"

#### Scenario: Senha errada
- **WHEN** o acerto é pedido com a senha do gerente incorreta
- **THEN** a API responde 403, não altera nenhum estoque e registra a tentativa negada na trilha

#### Scenario: Aplicação dupla
- **WHEN** o inventário já foi aplicado e alguém pede a aplicação de novo
- **THEN** a API responde 409 sem alterar estoque

### Requirement: Cancelamento de inventário
A API SHALL permitir ao gerente ou administrador cancelar um inventário em contagem, com motivo, sem alterar
estoque; as contagens continuam guardadas para consulta.

#### Scenario: Cancelar inventário
- **WHEN** o gerente cancela o inventário 12 com o motivo "contagem refeita amanhã"
- **THEN** o inventário fica "cancelado", nenhum estoque muda e o escopo fica livre para um novo inventário

### Requirement: Trilha de auditoria do inventário
A API SHALL manter de forma append-only o registro de abertura, cada contagem e anulação, pedidos de recontagem,
tentativas de aplicação (autorizadas ou negadas, com gerente informado, IP e terminal), o acerto aplicado por
produto (antes, contado, diferença, depois, valor) e o cancelamento, consultável por gerente e administrador.

#### Scenario: Consultar inventário aplicado
- **WHEN** o gerente consulta o inventário 12 depois de aplicado
- **THEN** a API devolve quem abriu, quem contou cada produto, quem aprovou, a justificativa e o antes/depois de cada produto ajustado

#### Scenario: Registro imutável
- **WHEN** qualquer rota da API é usada depois da aplicação
- **THEN** nenhuma contagem, ajuste ou tentativa registrada pode ser alterada ou apagada

### Requirement: Contagem no aplicativo
O aplicativo de estoque SHALL permitir contar bipando o código de barras (código interno, GTIN ou etiqueta de
balança), confirmando a quantidade (padrão 1, fracionada só para unidades diferentes de UN) e escolhendo o local,
mostrando o total já contado do produto e os últimos lançamentos do próprio usuário.

#### Scenario: Bipar com quantidade padrão
- **WHEN** o estoquista bipa um produto UN e pressiona Enter sem alterar a quantidade
- **THEN** o aplicativo registra 1 unidade, mostra o novo total contado do produto e volta o foco para a leitura

#### Scenario: Etiqueta de balança
- **WHEN** o estoquista bipa uma etiqueta de balança válida de um produto em kg
- **THEN** o aplicativo registra o peso da etiqueta como quantidade contada

#### Scenario: Código desconhecido
- **WHEN** o código bipado não corresponde a nenhum produto
- **THEN** o aplicativo avisa com som/destaque, não registra nada e mantém o foco na leitura
