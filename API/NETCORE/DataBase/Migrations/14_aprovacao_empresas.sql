/*
 * Arquivo: API/NETCORE/DataBase/Migrations/14_aprovacao_empresas.sql
 * Objetivo: adicionar controle de status e auditoria para aprovação de novas empresas pelo administrador geral:
 *           - Status: 'aprovada', 'pendente', 'rejeitada', 'bloqueada'
 *           - CreatedAt: data de cadastro
 *           - ReviewedAt: data de análise da inscrição
 *           - ReviewedBy: identificação do administrador que analisou
 *           - RejectionReason: justificativa caso a inscrição tenha sido recusada
 *           - RequireApprovalForNewCompanies: chave na empresa-principal para ligar/desligar exigência de aprovação manual
 *
 * Idempotente: seguro para rodar múltiplas vezes sem erro.
 */

USE HorusPdv;
GO

SET NOCOUNT ON;
GO

/* ------------------------------------------------------------------------- */
/* 1. Colunas de Status e Auditoria em Empresas                               */
/* ------------------------------------------------------------------------- */
IF COL_LENGTH(N'Empresas', N'Status') IS NULL
BEGIN
    ALTER TABLE Empresas ADD Status NVARCHAR(20) NOT NULL CONSTRAINT DF_Empresas_Status DEFAULT N'pendente';
END;
GO

IF COL_LENGTH(N'Empresas', N'CreatedAt') IS NULL
BEGIN
    ALTER TABLE Empresas ADD CreatedAt DATETIMEOFFSET NOT NULL CONSTRAINT DF_Empresas_CreatedAt DEFAULT SYSDATETIMEOFFSET();
END;
GO

IF COL_LENGTH(N'Empresas', N'ReviewedAt') IS NULL
BEGIN
    ALTER TABLE Empresas ADD ReviewedAt DATETIMEOFFSET NULL;
END;
GO

IF COL_LENGTH(N'Empresas', N'ReviewedBy') IS NULL
BEGIN
    ALTER TABLE Empresas ADD ReviewedBy NVARCHAR(180) NULL;
END;
GO

IF COL_LENGTH(N'Empresas', N'RejectionReason') IS NULL
BEGIN
    ALTER TABLE Empresas ADD RejectionReason NVARCHAR(500) NULL;
END;
GO

IF COL_LENGTH(N'Empresas', N'RequireApprovalForNewCompanies') IS NULL
BEGIN
    ALTER TABLE Empresas ADD RequireApprovalForNewCompanies BIT NOT NULL CONSTRAINT DF_Empresas_ReqApproval DEFAULT 1;
END;
GO

/* ------------------------------------------------------------------------- */
/* 2. Backfill: garantir que a empresa-principal e empresas pré-existentes    */
/*    estejam aprovadas                                                      */
/* ------------------------------------------------------------------------- */
UPDATE Empresas
   SET Status = N'aprovada'
 WHERE Status IS NULL 
    OR Status = N''
    OR Status = N'pendente' AND Id = N'empresa-principal';
GO

/* ------------------------------------------------------------------------- */
/* 3. Índice para filtragem rápida por Status no Painel Master                */
/* ------------------------------------------------------------------------- */
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_Empresas_Status')
BEGIN
    CREATE INDEX IX_Empresas_Status ON Empresas (Status);
END;
GO

/* ------------------------------------------------------------------------- */
/* 4. Garantir que o email jotanaval2009@gmail.com seja SuperAdmin           */
/* ------------------------------------------------------------------------- */
IF EXISTS (SELECT 1 FROM Usuarios WHERE Email = N'jotanaval2009@gmail.com')
BEGIN
    UPDATE Usuarios
       SET CompanyId = N'empresa-principal',
           Role = N'administrador',
           Status = N'ativo'
     WHERE Email = N'jotanaval2009@gmail.com';
END;
GO

