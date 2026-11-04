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
