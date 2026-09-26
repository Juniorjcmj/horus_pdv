/*
 * Arquivo: API/NETCORE/DataBase/Migrations/19_idempotencia_eventos.sql
 * Objetivo: criar tabela ProcessedEvents para suporte a idempotência transacional no backend
 *           e adicionar colunas ClientSaleId, OfflineReference e SyncedAt em Vendas.
 *
 * Pré-requisito: migrações 01-18 aplicadas.
 * Idempotente: pode ser executado repetidamente sem erro.
 */

USE HorusPdv;
GO

SET NOCOUNT ON;
GO

/* ------------------------------------------------------------------------- */
/* 1. Criar tabela ProcessedEvents                                            */
/* ------------------------------------------------------------------------- */
IF OBJECT_ID(N'ProcessedEvents', N'U') IS NULL
BEGIN
    CREATE TABLE ProcessedEvents
    (
        Id NVARCHAR(50) NOT NULL CONSTRAINT PK_ProcessedEvents PRIMARY KEY,
        CompanyId NVARCHAR(40) NOT NULL,
        EventId NVARCHAR(50) NOT NULL,
        EventType NVARCHAR(50) NOT NULL,
        ClientSaleId NVARCHAR(50) NULL,
        PayloadHash NVARCHAR(64) NOT NULL,
        ProcessedAt DATETIMEOFFSET NOT NULL CONSTRAINT DF_ProcessedEvents_ProcessedAt DEFAULT SYSDATETIMEOFFSET(),
        ResponsePayload NVARCHAR(MAX) NOT NULL,
        CONSTRAINT UQ_ProcessedEvents_Company_Event UNIQUE (CompanyId, EventId)
    );
END
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_ProcessedEvents_Company_ClientSaleId' AND object_id = OBJECT_ID(N'ProcessedEvents'))
BEGIN
    CREATE INDEX IX_ProcessedEvents_Company_ClientSaleId 
        ON ProcessedEvents (CompanyId, ClientSaleId) 
        WHERE ClientSaleId IS NOT NULL;
END
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_ProcessedEvents_ProcessedAt' AND object_id = OBJECT_ID(N'ProcessedEvents'))
BEGIN
    CREATE INDEX IX_ProcessedEvents_ProcessedAt 
        ON ProcessedEvents (ProcessedAt);
END
GO

/* ------------------------------------------------------------------------- */
/* 2. Adicionar colunas de rastreamento offline na tabela Vendas              */
/* ------------------------------------------------------------------------- */
IF COL_LENGTH(N'Vendas', N'ClientSaleId') IS NULL
BEGIN
    ALTER TABLE Vendas ADD ClientSaleId NVARCHAR(50) NULL;
END
GO

IF COL_LENGTH(N'Vendas', N'OfflineReference') IS NULL
BEGIN
    ALTER TABLE Vendas ADD OfflineReference NVARCHAR(50) NULL;
END
GO

IF COL_LENGTH(N'Vendas', N'SyncedAt') IS NULL
BEGIN
    ALTER TABLE Vendas ADD SyncedAt DATETIMEOFFSET NULL;
END
GO

/* ------------------------------------------------------------------------- */
/* 3. Índice único condicional para ClientSaleId por empresa na tabela Vendas  */
/* ------------------------------------------------------------------------- */
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_Vendas_Company_ClientSaleId' AND object_id = OBJECT_ID(N'Vendas'))
BEGIN
    CREATE UNIQUE INDEX IX_Vendas_Company_ClientSaleId 
        ON Vendas (CompanyId, ClientSaleId) 
        WHERE ClientSaleId IS NOT NULL;
END
GO
