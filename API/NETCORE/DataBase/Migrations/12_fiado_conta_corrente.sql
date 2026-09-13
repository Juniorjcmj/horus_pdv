/* ------------------------------------------------------------------------- */
/* 12_fiado_conta_corrente.sql                                               */
/* Adiciona LimiteCredito e SaldoDevedor em Clientes e cria FiadoMovimentos  */
/* Idempotente — executado pelo HorusDatabaseInitializer                     */
/* ------------------------------------------------------------------------- */

SET NOCOUNT ON;

IF COL_LENGTH(N'Clientes', N'LimiteCredito') IS NULL
BEGIN
    ALTER TABLE Clientes ADD LimiteCredito DECIMAL(18,2) NOT NULL CONSTRAINT DF_Clientes_LimiteCredito DEFAULT 0;
END;
GO

IF COL_LENGTH(N'Clientes', N'SaldoDevedor') IS NULL
BEGIN
    ALTER TABLE Clientes ADD SaldoDevedor DECIMAL(18,2) NOT NULL CONSTRAINT DF_Clientes_SaldoDevedor DEFAULT 0;
END;
GO

IF OBJECT_ID(N'dbo.FiadoMovimentos', N'U') IS NULL
BEGIN
    CREATE TABLE FiadoMovimentos (
        Id                  NVARCHAR(40)    NOT NULL,
        CompanyId           NVARCHAR(40)    NOT NULL CONSTRAINT DF_FiadoMovimentos_CompanyId DEFAULT N'empresa-principal',
        ClienteId           NVARCHAR(40)    NOT NULL,
        Tipo                TINYINT         NOT NULL, -- 1 = Debito (compra fiado), 2 = Credito (pagamento/abatimento)
        Valor               DECIMAL(18,2)   NOT NULL,
        SaldoAnterior       DECIMAL(18,2)   NOT NULL,
        SaldoAtual          DECIMAL(18,2)   NOT NULL,
        VendaId             NVARCHAR(40)    NULL,
        FormaPagamento      NVARCHAR(30)    NULL,
        Observacao          NVARCHAR(500)   NULL,
        OperadorNome        NVARCHAR(100)   NOT NULL CONSTRAINT DF_FiadoMovimentos_Operador DEFAULT N'Operador',
        CriadoEm            DATETIMEOFFSET  NOT NULL CONSTRAINT DF_FiadoMovimentos_CriadoEm DEFAULT SYSDATETIMEOFFSET(),
        CONSTRAINT PK_FiadoMovimentos PRIMARY KEY (Id),
        CONSTRAINT FK_FiadoMovimentos_Clientes FOREIGN KEY (ClienteId) REFERENCES Clientes(Id) ON DELETE CASCADE
    );
END;
GO

IF NOT EXISTS (
    SELECT 1 FROM sys.indexes 
    WHERE name = N'IX_FiadoMovimentos_Cliente' 
      AND object_id = OBJECT_ID(N'FiadoMovimentos')
)
BEGIN
    CREATE NONCLUSTERED INDEX IX_FiadoMovimentos_Cliente
    ON FiadoMovimentos (CompanyId, ClienteId, CriadoEm DESC);
END;
GO
