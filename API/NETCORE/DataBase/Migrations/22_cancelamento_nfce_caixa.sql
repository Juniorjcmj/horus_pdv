-- ============================================================================
-- Migration 22: Cancelamento de NFC-e na Frente de Caixa com Supervisor
-- ============================================================================

-- 1. Garante coluna Status na tabela Vendas (para permitir cancelamento formal da venda)
IF COL_LENGTH(N'Vendas', N'Status') IS NULL
BEGIN
    ALTER TABLE Vendas ADD Status NVARCHAR(30) NOT NULL CONSTRAINT DF_Vendas_Status DEFAULT N'finalizada';
END;

-- 2. Campos de auditoria de supervisor e operador no cancelamento fiscal
IF COL_LENGTH(N'DocumentosFiscais', N'CanceladoPorSupervisorId') IS NULL
BEGIN
    ALTER TABLE DocumentosFiscais ADD CanceladoPorSupervisorId NVARCHAR(40) NULL;
END;

IF COL_LENGTH(N'DocumentosFiscais', N'CanceladoPorSupervisorNome') IS NULL
BEGIN
    ALTER TABLE DocumentosFiscais ADD CanceladoPorSupervisorNome NVARCHAR(180) NULL;
END;

IF COL_LENGTH(N'DocumentosFiscais', N'CanceladoPorOperador') IS NULL
BEGIN
    ALTER TABLE DocumentosFiscais ADD CanceladoPorOperador NVARCHAR(180) NULL;
END;

IF COL_LENGTH(N'DocumentosFiscais', N'CanceladoJustificativa') IS NULL
BEGIN
    ALTER TABLE DocumentosFiscais ADD CanceladoJustificativa NVARCHAR(500) NULL;
END;
