# Spec Delta

## Purpose

Define como o banco SQL Server da API Java é criado e evolui por migrações versionadas, aceitando tanto um banco vazio
quanto uma cópia restaurada da produção atual.

## ADDED Requirements

### Requirement: Banco vazio criado pelas migrações
Ao iniciar com um banco vazio, a API SHALL criar o esquema completo atual (as 59 tabelas, colunas, tipos, padrões,
índices e chaves da API .NET) e os dados de referência obrigatórios, por migrações versionadas.

#### Scenario: Primeira subida
- **WHEN** a API sobe apontando para um banco sem tabelas
- **THEN** as migrações V1 (esquema) e V2 (dados de referência) rodam e a tabela de histórico do Flyway registra as duas

#### Scenario: Esquema igual ao da API .NET
- **WHEN** um banco criado pelas migrações Java é comparado com um banco criado pelo inicializador .NET atual
- **THEN** tabelas, colunas, tipos, nulidade e índices são os mesmos

### Requirement: Banco restaurado da produção
Ao iniciar com um banco que já tem o esquema atual e ainda não tem histórico do Flyway, a API SHALL registrar o esquema
como versão 1 sem executar o V1 e seguir apenas com as migrações posteriores.

#### Scenario: Restauração do backup de produção
- **WHEN** a API sobe num banco restaurado de um backup da API .NET
- **THEN** o Flyway cria o histórico com baseline na versão 1, nenhuma tabela é recriada e os dados ficam intactos

### Requirement: Validação do esquema na inicialização
A API SHALL recusar a inicialização quando o esquema do banco não corresponder ao mapeamento das entidades.

#### Scenario: Coluna faltando
- **WHEN** a API sobe num banco onde falta uma coluna mapeada
- **THEN** a inicialização falha com erro indicando a tabela e a coluna

### Requirement: Mudanças de esquema só por migração
Toda alteração de esquema após o V1 SHALL ser uma nova migração versionada e imutável; migrações já aplicadas não
podem ser editadas.

#### Scenario: Migração aplicada alterada
- **WHEN** o conteúdo de uma migração já aplicada é modificado
- **THEN** a API não sobe e informa a divergência de checksum
