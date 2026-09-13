/* ------------------------------------------------------------------------- */
/* 10_controle_validade.sql                                                 */
/* Adiciona campos de controle de validade e indice filtrado na Produtos    */
/* Idempotente — executado pelo HorusDatabaseInitializer                     */
/* ------------------------------------------------------------------------- */

SET NOCOUNT ON;

IF COL_LENGTH(N'Produtos', N'DataValidade') IS NULL
BEGIN
    ALTER TABLE Produtos ADD DataValidade DATE NULL;
END;
GO

IF COL_LENGTH(N'Produtos', N'ControlaValidade') IS NULL
BEGIN
    ALTER TABLE Produtos ADD ControlaValidade BIT NOT NULL CONSTRAINT DF_Produtos_ControlaValidade DEFAULT 0;
END;
GO

IF COL_LENGTH(N'Produtos', N'DiasAlertaValidade') IS NULL
BEGIN
    ALTER TABLE Produtos ADD DiasAlertaValidade INT NOT NULL CONSTRAINT DF_Produtos_DiasAlertaValidade DEFAULT 15;
END;
GO

IF NOT EXISTS (
    SELECT 1 FROM sys.indexes 
    WHERE name = N'IX_Produtos_Validade' 
      AND object_id = OBJECT_ID(N'Produtos')
)
BEGIN
    CREATE NONCLUSTERED INDEX IX_Produtos_Validade 
    ON Produtos (CompanyId, ControlaValidade, DataValidade)
    INCLUDE (BarCode, Description, Stock, CostPrice, SalePrice, CategoriaId)
    WHERE ControlaValidade = 1 AND DataValidade IS NOT NULL;
END;
GO
