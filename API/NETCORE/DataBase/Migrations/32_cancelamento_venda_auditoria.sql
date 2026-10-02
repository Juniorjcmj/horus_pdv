-- ============================================================================
-- Migration 32: Cancelamento de Venda com Autorização de Supervisor
-- ============================================================================

-- 1. Garante coluna Status na tabela Vendas
IF COL_LENGTH(N'Vendas', N'Status') IS NULL
BEGIN
    ALTER TABLE Vendas ADD Status NVARCHAR(30) NOT NULL CONSTRAINT DF_Vendas_Status DEFAULT N'finalizada';
END;

-- 2. Campos de auditoria do cancelamento da venda
IF COL_LENGTH(N'Vendas', N'CanceladoEm') IS NULL
BEGIN
    ALTER TABLE Vendas ADD CanceladoEm DATETIMEOFFSET NULL;
END;

IF COL_LENGTH(N'Vendas', N'CanceladoPorOperadorId') IS NULL
BEGIN
    ALTER TABLE Vendas ADD CanceladoPorOperadorId NVARCHAR(40) NULL;
END;

IF COL_LENGTH(N'Vendas', N'CanceladoPorOperadorNome') IS NULL
BEGIN
    ALTER TABLE Vendas ADD CanceladoPorOperadorNome NVARCHAR(180) NULL;
END;

IF COL_LENGTH(N'Vendas', N'CanceladoPorSupervisorId') IS NULL
BEGIN
    ALTER TABLE Vendas ADD CanceladoPorSupervisorId NVARCHAR(40) NULL;
END;

IF COL_LENGTH(N'Vendas', N'CanceladoPorSupervisorNome') IS NULL
BEGIN
    ALTER TABLE Vendas ADD CanceladoPorSupervisorNome NVARCHAR(180) NULL;
END;

IF COL_LENGTH(N'Vendas', N'CanceladoJustificativa') IS NULL
BEGIN
    ALTER TABLE Vendas ADD CanceladoJustificativa NVARCHAR(500) NULL;
END;

-- 3. Índice para consultas rápidas de cancelamentos
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_Vendas_Company_Status_CanceladoEm' AND object_id = OBJECT_ID(N'Vendas'))
BEGIN
    CREATE NONCLUSTERED INDEX IX_Vendas_Company_Status_CanceladoEm
    ON Vendas (CompanyId, Status, CanceladoEm)
    INCLUDE (SaleNumber, TotalAmount, OperatorName, CanceladoPorSupervisorNome);
END;
