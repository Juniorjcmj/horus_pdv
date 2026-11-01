-- Migration 57: Análise de estoque — movimentação completa, fotografia diária, configurações, ABC/FSN e lead time.
-- Idempotente. Aditiva (colunas/tabelas novas são ignoradas pela versão anterior da API).

-- Colunas novas de EstoqueMovimentacoes
IF COL_LENGTH('EstoqueMovimentacoes', 'CustoUnitario') IS NULL
    ALTER TABLE EstoqueMovimentacoes ADD CustoUnitario DECIMAL(18,4) NULL;
IF COL_LENGTH('EstoqueMovimentacoes', 'OrigemTipo') IS NULL
    ALTER TABLE EstoqueMovimentacoes ADD OrigemTipo NVARCHAR(20) NULL;
IF COL_LENGTH('EstoqueMovimentacoes', 'OrigemId') IS NULL
    ALTER TABLE EstoqueMovimentacoes ADD OrigemId NVARCHAR(80) NULL;
IF COL_LENGTH('EstoqueMovimentacoes', 'DepositoId') IS NULL
    ALTER TABLE EstoqueMovimentacoes ADD DepositoId NVARCHAR(40) NOT NULL
        CONSTRAINT DF_EstoqueMovimentacoes_Deposito DEFAULT 'loja';

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'IX_EstoqueMov_Analise_Produto' AND object_id = OBJECT_ID('EstoqueMovimentacoes'))
    CREATE INDEX IX_EstoqueMov_Analise_Produto ON EstoqueMovimentacoes(CompanyId, ProdutoId, CriadoEm)
        INCLUDE (Tipo, Quantidade, CustoUnitario);
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'IX_EstoqueMov_Analise_Tipo' AND object_id = OBJECT_ID('EstoqueMovimentacoes'))
    CREATE INDEX IX_EstoqueMov_Analise_Tipo ON EstoqueMovimentacoes(CompanyId, Tipo, CriadoEm)
        INCLUDE (ProdutoId, Quantidade, CustoUnitario);

-- Lead time
IF COL_LENGTH('Produtos', 'LeadTimeDias') IS NULL
    ALTER TABLE Produtos ADD LeadTimeDias INT NULL;
IF COL_LENGTH('Fornecedores', 'LeadTimePrometidoDias') IS NULL
    ALTER TABLE Fornecedores ADD LeadTimePrometidoDias INT NULL;

-- Fotografia diária do estoque
IF NOT EXISTS (SELECT 1 FROM sys.tables WHERE name = 'EstoqueSnapshotDiario')
BEGIN
    CREATE TABLE EstoqueSnapshotDiario (
        CompanyId     NVARCHAR(100) NOT NULL,
        DepositoId    NVARCHAR(40)  NOT NULL CONSTRAINT DF_EstoqueSnapshot_Deposito DEFAULT 'loja',
        Data          DATE          NOT NULL,
        ProdutoId     NVARCHAR(40)  NOT NULL,
        Quantidade    DECIMAL(18,3) NOT NULL,
        Valor         DECIMAL(18,2) NOT NULL,
        CustoUnitario DECIMAL(18,4) NOT NULL,
        CONSTRAINT PK_EstoqueSnapshotDiario PRIMARY KEY (CompanyId, DepositoId, Data, ProdutoId)
    );
    CREATE INDEX IX_EstoqueSnapshot_Produto ON EstoqueSnapshotDiario(CompanyId, ProdutoId, Data);
END;

-- Configurações de análise por empresa (ausência de linha = padrões)
IF NOT EXISTS (SELECT 1 FROM sys.tables WHERE name = 'EstoqueConfigAnalise')
BEGIN
    CREATE TABLE EstoqueConfigAnalise (
        CompanyId          NVARCHAR(100) NOT NULL PRIMARY KEY,
        JanelaConsumoDias  INT           NOT NULL DEFAULT 30,
        NivelServicoZ      DECIMAL(6,3)  NOT NULL DEFAULT 1.65,
        AbcLimiteA         DECIMAL(5,2)  NOT NULL DEFAULT 80,
        AbcLimiteB         DECIMAL(5,2)  NOT NULL DEFAULT 95,
        AbcBase            NVARCHAR(12)  NOT NULL DEFAULT 'faturamento',
        FsnLimiteFast      DECIMAL(5,2)  NOT NULL DEFAULT 50,
        DiasParado         INT           NOT NULL DEFAULT 90,
        DiasExcesso        INT           NOT NULL DEFAULT 120,
        CoberturaAlvoDias  INT           NOT NULL DEFAULT 15,
        LeadTimePadraoDias INT           NOT NULL DEFAULT 7,
        ContagemDiasA      INT           NOT NULL DEFAULT 30,
        ContagemDiasB      INT           NOT NULL DEFAULT 90,
        ContagemDiasC      INT           NOT NULL DEFAULT 180,
        ContagemItensDia   INT           NOT NULL DEFAULT 20,
        MetasJson          NVARCHAR(MAX) NULL,
        AtualizadoPor      NVARCHAR(200) NULL,
        AtualizadoEm       DATETIME2     NOT NULL DEFAULT SYSUTCDATETIME()
    );
END;

-- Classificação ABC/FSN recalculada pelo job diário
IF NOT EXISTS (SELECT 1 FROM sys.tables WHERE name = 'ProdutoClassificacao')
BEGIN
    CREATE TABLE ProdutoClassificacao (
        CompanyId   NVARCHAR(100) NOT NULL,
        ProdutoId   NVARCHAR(40)  NOT NULL,
        Abc         CHAR(1)       NOT NULL,
        Fsn         CHAR(1)       NOT NULL,
        CalculadoEm DATETIME2     NOT NULL DEFAULT SYSUTCDATETIME(),
        CONSTRAINT PK_ProdutoClassificacao PRIMARY KEY (CompanyId, ProdutoId)
    );
END;
