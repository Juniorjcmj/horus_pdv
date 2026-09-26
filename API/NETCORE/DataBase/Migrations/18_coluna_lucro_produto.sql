/*
 * Arquivo: API/NETCORE/DataBase/Migrations/18_coluna_lucro_produto.sql
 * Objetivo: adicionar coluna Lucro (preço de venda − preço de custo) na tabela Produtos
 *           para facilitar relatórios financeiros.
 *
 * Pré-requisito: migrações 01-17 aplicadas.
 * Idempotente: pode ser executado repetidamente sem erro (IF COL_LENGTH).
 */

USE HorusPdv;
GO

SET NOCOUNT ON;
GO

/* ------------------------------------------------------------------------- */
/* 1. Adicionar coluna Lucro (lucro unitário = venda − custo)                */
/* ------------------------------------------------------------------------- */
IF COL_LENGTH(N'Produtos', N'Lucro') IS NULL
    ALTER TABLE Produtos ADD Lucro DECIMAL(18,2) NOT NULL CONSTRAINT DF_Produtos_Lucro DEFAULT 0;
GO

/* ------------------------------------------------------------------------- */
/* 2. Backfill: calcular lucro a partir dos valores já existentes            */
/* ------------------------------------------------------------------------- */
UPDATE Produtos
   SET Lucro = ISNULL(ProductSalePrice, 0) - ISNULL(ProductUnitPrice, 0)
 WHERE Lucro = 0
   AND ProductSalePrice IS NOT NULL
   AND ProductUnitPrice IS NOT NULL;
GO
