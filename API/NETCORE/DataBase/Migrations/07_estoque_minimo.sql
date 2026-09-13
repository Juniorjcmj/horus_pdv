/* ------------------------------------------------------------------------- */
/* 07_estoque_minimo.sql                                                     */
/* Adiciona a coluna EstoqueMinimo na tabela Produtos                        */
/* Idempotente — executado pelo HorusDatabaseInitializer                     */
/* ------------------------------------------------------------------------- */

SET NOCOUNT ON;

IF COL_LENGTH(N'Produtos', N'EstoqueMinimo') IS NULL
BEGIN
    ALTER TABLE Produtos ADD EstoqueMinimo DECIMAL(15, 4) NOT NULL CONSTRAINT DF_Produtos_EstoqueMinimo DEFAULT 0;
END;
GO
