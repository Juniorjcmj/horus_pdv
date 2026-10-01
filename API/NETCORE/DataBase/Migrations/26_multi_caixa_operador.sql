-- Migration 26: Suporte a múltiplos caixas simultâneos (um por operador)
-- Adiciona CaixaSessaoId nas Vendas para vincular cada venda à sessão que a registrou.
-- Adiciona índice filtrado para busca rápida da sessão aberta por operador.

-- 1. Nova coluna CaixaSessaoId em Vendas (nullable para backward-compat com vendas existentes)
IF COL_LENGTH(N'Vendas', N'CaixaSessaoId') IS NULL
    ALTER TABLE Vendas ADD CaixaSessaoId NVARCHAR(40) NULL;

-- 2. FK de Vendas para CaixaSessoes
IF NOT EXISTS (
    SELECT 1 FROM sys.foreign_keys
    WHERE name = N'FK_Vendas_CaixaSessoes' AND parent_object_id = OBJECT_ID(N'Vendas')
)
    ALTER TABLE Vendas ADD CONSTRAINT FK_Vendas_CaixaSessoes
        FOREIGN KEY (CaixaSessaoId) REFERENCES CaixaSessoes (Id);

-- 3. Índice em Vendas(CaixaSessaoId) para consultas de conferência de fechamento
IF NOT EXISTS (
    SELECT 1 FROM sys.indexes
    WHERE name = N'IX_Vendas_CaixaSessaoId' AND object_id = OBJECT_ID(N'Vendas')
)
    CREATE INDEX IX_Vendas_CaixaSessaoId ON Vendas (CaixaSessaoId) WHERE CaixaSessaoId IS NOT NULL;

-- 4. Índice composto para busca rápida: sessão aberta de um operador específico
IF NOT EXISTS (
    SELECT 1 FROM sys.indexes
    WHERE name = N'IX_CaixaSessoes_Company_Operator_Open'
      AND object_id = OBJECT_ID(N'CaixaSessoes')
)
    CREATE INDEX IX_CaixaSessoes_Company_Operator_Open
    ON CaixaSessoes (CompanyId, OperatorId, ClosedAt)
    WHERE ClosedAt IS NULL;
