/*
 * Arquivo: API/NETCORE/DataBase/Migrations/36_autorizacao_preco.sql
 * Objetivo: autorização de gerente para alterar o preço de um produto no caixa. O gerente digita a
 *           senha uma vez; o servidor grava a autorização (produto, preço novo, quem autorizou) e a venda
 *           só aceita o preço alterado se apresentar uma autorização válida, de uso único.
 *
 * Idempotente: pode ser executado no boot repetidamente sem erro.
 */

SET NOCOUNT ON;
GO

IF OBJECT_ID(N'AutorizacoesPreco', N'U') IS NULL
BEGIN
    CREATE TABLE AutorizacoesPreco (
        Id              NVARCHAR(40)    NOT NULL,
        CompanyId       NVARCHAR(40)    NOT NULL,
        ProductCode     NVARCHAR(60)    NOT NULL,
        PrecoTabela     DECIMAL(15,4)   NOT NULL,   /* preço cadastrado no momento da autorização */
        PrecoNovo       DECIMAL(15,4)   NOT NULL,   /* preço autorizado para a venda */
        SupervisorId    NVARCHAR(40)    NOT NULL,
        SupervisorNome  NVARCHAR(120)   NOT NULL,
        OperadorId      NVARCHAR(40)    NULL,
        OperadorNome    NVARCHAR(120)   NULL,
        Motivo          NVARCHAR(200)   NULL,
        CriadoEm        DATETIMEOFFSET  NOT NULL CONSTRAINT DF_AutorizacoesPreco_CriadoEm DEFAULT SYSDATETIMEOFFSET(),
        ExpiraEm        DATETIMEOFFSET  NOT NULL,
        UsadoEm         DATETIMEOFFSET  NULL,
        VendaId         NVARCHAR(40)    NULL,
        CONSTRAINT PK_AutorizacoesPreco PRIMARY KEY (Id)
    );
END;
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_AutorizacoesPreco_Company' AND object_id = OBJECT_ID(N'AutorizacoesPreco'))
BEGIN
    CREATE INDEX IX_AutorizacoesPreco_Company ON AutorizacoesPreco (CompanyId, CriadoEm DESC);
END;
GO
