/*
 * Arquivo: API/NETCORE/DataBase/Migrations/03_multiplas_formas_pagamento.sql
 * Objetivo: cria a tabela VendaPagamentos para suportar rateio de pagamentos múltiplos por venda
 *           e migra dados históricos existentes.
 *
 * Idempotente (IF OBJECT_ID em todo bloco) — executado no boot pelo HorusDatabaseInitializer.
 */

USE HorusPdv;
GO

IF OBJECT_ID(N'VendaPagamentos', N'U') IS NULL
BEGIN
    CREATE TABLE VendaPagamentos
    (
        Id           NVARCHAR(40)   NOT NULL CONSTRAINT PK_VendaPagamentos PRIMARY KEY,
        CompanyId    NVARCHAR(40)   NOT NULL,
        VendaId      NVARCHAR(40)   NOT NULL,
        PaymentType  NVARCHAR(30)   NOT NULL,
        Amount       DECIMAL(18, 2) NOT NULL,
        CashGiven    DECIMAL(18, 2) NOT NULL CONSTRAINT DF_VendaPagamentos_CashGiven DEFAULT 0,
        ChangeAmount DECIMAL(18, 2) NOT NULL CONSTRAINT DF_VendaPagamentos_ChangeAmount DEFAULT 0,
        CreatedAt    DATETIMEOFFSET NOT NULL CONSTRAINT DF_VendaPagamentos_CreatedAt DEFAULT SYSDATETIMEOFFSET(),
        CONSTRAINT FK_VendaPagamentos_Vendas FOREIGN KEY (VendaId) REFERENCES Vendas (Id) ON DELETE CASCADE
    );

    CREATE INDEX IX_VendaPagamentos_Company_Venda ON VendaPagamentos (CompanyId, VendaId);
    CREATE INDEX IX_VendaPagamentos_Company_Payment ON VendaPagamentos (CompanyId, PaymentType);
END;
GO

/* ------------------------------------------------------------------------- */
/* Popula VendaPagamentos para vendas históricas que ainda não possuem linhas */
/* ------------------------------------------------------------------------- */
IF OBJECT_ID(N'VendaPagamentos', N'U') IS NOT NULL
BEGIN
    INSERT INTO VendaPagamentos (Id, CompanyId, VendaId, PaymentType, Amount, CashGiven, ChangeAmount, CreatedAt)
    SELECT
        CONCAT(v.Id, N'-pag-001'),
        v.CompanyId,
        v.Id,
        CASE WHEN ISNULL(v.PaymentType, N'') = N'' THEN N'dinheiro' ELSE v.PaymentType END,
        CASE
            WHEN TRY_CONVERT(DECIMAL(18,2), v.TotalAmount) IS NOT NULL THEN TRY_CONVERT(DECIMAL(18,2), v.TotalAmount)
            ELSE 0
        END,
        CASE
            WHEN TRY_CONVERT(DECIMAL(18,2), v.TotalAmount) IS NOT NULL THEN TRY_CONVERT(DECIMAL(18,2), v.TotalAmount)
            ELSE 0
        END,
        0,
        v.SaleDate
    FROM Vendas v
    WHERE NOT EXISTS (
        SELECT 1 FROM VendaPagamentos vp WHERE vp.VendaId = v.Id
    );
END;
GO
