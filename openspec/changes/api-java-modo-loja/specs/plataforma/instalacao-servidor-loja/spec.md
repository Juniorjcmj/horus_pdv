# Spec Delta

## Purpose

Instalação e operação do servidor dedicado da loja no Windows: API Java em modo loja, SQL Server Express, backup e rede
local. Assim uma loja nova é posta em funcionamento seguindo um roteiro, sem depender de configuração manual do
desenvolvedor.

## ADDED Requirements

### Requirement: Instalador do servidor
SHALL existir um pacote de instalação para Windows. Ele instala ou reaproveita o SQL Server Express, cria o banco,
instala a API Java em modo loja como serviço do Windows com início automático e gera os segredos próprios da loja. Em
seguida registra o servidor na nuvem e roda a carga inicial.

#### Scenario: Instalação numa máquina limpa
- **WHEN** o instalador roda numa máquina Windows sem SQL Server
- **THEN** ao final o serviço está rodando, o banco tem os dados da empresa e a situação da sincronização mostra atraso
  zero

#### Scenario: Reinício da máquina
- **WHEN** a máquina do servidor é reiniciada
- **THEN** o serviço volta sozinho e os caixas reconectam sem ação do operador

### Requirement: Backup automático
O servidor SHALL fazer backup completo do banco todo dia, guardar pelo menos os últimos 7 e registrar a falha como
alerta. A restauração SHALL estar documentada e testada.

#### Scenario: Backup diário
- **WHEN** passa o horário do backup
- **THEN** existe um arquivo de backup novo e os mais antigos que a retenção foram apagados

#### Scenario: Restauração do backup
- **WHEN** o backup é restaurado com `restaurar.ps1`
- **THEN** o banco volta ao ponto do backup, a conferência NÃO é desligada na configuração e a recuperação (requisito abaixo) é pedida

### Requirement: Recuperação após restauração
Depois de restaurar um backup, o servidor da loja SHALL puxar da nuvem tudo o que o backup não tem antes de voltar ao
normal: as linhas da empresa mais novas que o backup, em todas as classes do catálogo (loja, editável e acréscimo), e
as sequências de numeração (vendas, fiscal, inventário e demais) SHALL avançar para não reusar número. Só depois
disso o envio e a conferência SHALL voltar sozinhos. Nada MUST ser apagado da nuvem pela recuperação nem pela
conferência seguinte, e nada MUST ser apagado da loja.

O `restaurar.ps1` SHALL pedir a recuperação (marcador lido pela API ao subir) e ela SHALL poder ser disparada por linha
de comando e por rota de administrador. Durante a recuperação o servidor MUST NOT aceitar vendas (responde 503, e os
caixas seguem na fila offline), o envio e a conferência SHALL ficar parados, e a situação da sincronização SHALL mostrar
que a loja está "recuperando". Sem internet, a recuperação SHALL ficar pendente e a conferência MUST continuar parada;
ela SHALL ser retomada de onde parou quando a internet voltar. Uma linha alterada na loja e depois do backup tem o valor
da nuvem, que é o mais recente que a loja enviou. Uma linha que só existe no backup (ainda não enviada) SHALL ser
mantida e enviada.

#### Scenario: Loja restaurada de backup anterior a N vendas
- **WHEN** a loja é restaurada de um backup anterior a N vendas já enviadas à nuvem
- **THEN** ao fim da recuperação as N vendas (com itens, pagamentos e movimentos de estoque) existem na loja, a numeração
  da próxima venda e da próxima nota continua acima das existentes e a nuvem continua com todas

#### Scenario: Conferência depois da recuperação
- **WHEN** a recuperação termina e a conferência roda
- **THEN** ela fecha `ok` e não apaga nada da nuvem

#### Scenario: Sem internet depois de restaurar
- **WHEN** o servidor é restaurado e a nuvem está inacessível
- **THEN** a recuperação fica pendente, as vendas são recusadas com 503, a conferência não roda, e tudo se resolve
  sozinho quando a internet volta

#### Scenario: Cadastro editado na nuvem depois do backup
- **WHEN** um cadastro foi alterado ou criado na nuvem depois do backup
- **THEN** a loja recuperada tem o cadastro com o valor da nuvem

#### Scenario: Restauração sem o script
- **WHEN** o banco é restaurado fora do `restaurar.ps1` e a nuvem está à frente da versão da loja
- **THEN** a loja percebe a diferença e executa a mesma recuperação

### Requirement: Acesso pela rede da loja
O serviço SHALL aceitar conexões só da rede local da loja, por regra de firewall criada pelo instalador. O banco MUST
NOT ser acessível fora da própria máquina.

#### Scenario: Acesso de fora da rede
- **WHEN** alguém tenta acessar a porta do serviço de fora da sub-rede da loja
- **THEN** a conexão é recusada pelo firewall

### Requirement: Ligação dos caixas
Os caixas e o Quack Estoque SHALL passar a usar o servidor da loja só trocando o endereço da API na configuração, sem
nova versão. Sem o servidor, cada caixa SHALL continuar na própria fila offline e reenviar quando ele voltar, sem pular
para a nuvem.

#### Scenario: Servidor fora do ar
- **WHEN** o servidor da loja fica fora do ar durante vendas
- **THEN** os caixas vendem offline e, quando ele volta, as vendas são registradas sem duplicar

### Requirement: Roteiro de implantação
SHALL existir um checklist de implantação com:
- máquina dedicada, nobreak e IP fixo;
- instalação;
- registro;
- carga inicial;
- troca do endereço em cada caixa, com os caixas sem vendas pendentes na fila offline;
- teste de queda de internet;
- teste de queda do servidor;
- restauração do backup.

#### Scenario: Implantação seguindo o roteiro
- **WHEN** a loja é implantada seguindo o checklist
- **THEN** cada item tem uma verificação registrada antes de liberar a loja para vender
