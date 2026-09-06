/*
 * Arquivo: API/NETCORE/DataBase/Migrations/01_migracao_valores.sql
 * Objetivo: converter colunas monetárias e de quantidade de NVARCHAR para DECIMAL e
 *           corrigir a unicidade da numeração interna de venda por empresa.
 * Pré-requisito: executar com a aplicação PARADA e após backup completo do banco.
 *
 * Este arquivo só é executado pelo HorusDatabaseInitializer quando detecta que
 * Vendas.TotalAmount ainda é NVARCHAR (ver HorusDatabaseInitializer.cs) — a idempotência
 * é decidida em C# antes de abrir o arquivo, então o script abaixo permanece um script
 * de conversão único (não precisa ser seguro para rodar duas vezes por si só).
 *
 * A conversão aborta (THROW) se qualquer linha existente não for parseável, para
 * evitar silenciar perda de valor. Rode a seção de VALIDAÇÃO isoladamente antes.
 */

USE HorusPdv;
GO

/* ------------------------------------------------------------------------- */
/* 1. Função de normalização                                                  */
/*                                                                            */
/* Replica exatamente o HorusMoneyFormat.ParseDecimal (C#), incluindo o       */
/* espaço não separável (U+00A0) que o ToString("C", pt-BR) emite no ICU.     */
/* ------------------------------------------------------------------------- */
CREATE OR ALTER FUNCTION dbo.fn_HorusParseDecimal (@valor NVARCHAR(60))
RETURNS DECIMAL(18, 4)
AS
BEGIN
    DECLARE @n NVARCHAR(60) = LTRIM(RTRIM(ISNULL(@valor, N'0')));

    SET @n = REPLACE(@n, NCHAR(160), N'');   -- espaço não separável
    SET @n = REPLACE(@n, NCHAR(8239), N''); -- espaço estreito não separável
    SET @n = REPLACE(@n, N'R$', N'');
    SET @n = REPLACE(@n, N' ', N'');
    SET @n = REPLACE(@n, N'.', N'');         -- separador de milhar pt-BR
    SET @n = REPLACE(@n, N',', N'.');        -- separador decimal pt-BR

    IF @n = N'' OR @n = N'-' SET @n = N'0';

    RETURN TRY_CONVERT(DECIMAL(18, 4), @n);  -- NULL quando não parseável
END;
GO

/* ------------------------------------------------------------------------- */
/* 2. VALIDAÇÃO — rode isolado primeiro e inspecione o resultado              */
/* ------------------------------------------------------------------------- */
DECLARE @falhas INT = 0;

SELECT @falhas = @falhas + COUNT(*) FROM Vendas
 WHERE dbo.fn_HorusParseDecimal(TotalAmount) IS NULL;

SELECT @falhas = @falhas + COUNT(*) FROM VendaItens
 WHERE dbo.fn_HorusParseDecimal(UnitPrice) IS NULL
    OR dbo.fn_HorusParseDecimal(ItemTotal) IS NULL;

SELECT @falhas = @falhas + COUNT(*) FROM Produtos
 WHERE dbo.fn_HorusParseDecimal(ProductUnitPrice)   IS NULL
    OR dbo.fn_HorusParseDecimal(ProductSalePrice)   IS NULL
    OR dbo.fn_HorusParseDecimal(TotalPriceOnProduct) IS NULL
    OR dbo.fn_HorusParseDecimal(ProductQnt)         IS NULL;

SELECT @falhas = @falhas + COUNT(*) FROM CaixaSessoes
 WHERE dbo.fn_HorusParseDecimal(OpeningAmount) IS NULL
    OR dbo.fn_HorusParseDecimal(ClosingAmount) IS NULL;

IF @falhas > 0
BEGIN
    DECLARE @msg NVARCHAR(200) =
        CONCAT(N'Migração abortada: ', @falhas, N' linha(s) com valor não parseável. ',
               N'Inspecione com as queries de diagnóstico no rodapé deste script.');
    THROW 51000, @msg, 1;
END;
GO

/* ------------------------------------------------------------------------- */
/* 3. Conversão                                                               */
/*                                                                            */
/* Estratégia: coluna nova -> backfill -> drop antiga -> rename. Evita         */
/* ALTER COLUMN direto, que o SQL Server recusa sobre texto com vírgula.       */
/*                                                                            */
/* Precisões:                                                                 */
/*   valor monetário  DECIMAL(18,2)  -> vProd, vNF, vUnTrib (2 casas)         */
/*   quantidade       DECIMAL(15,4)  -> qCom/qTrib da NF-e (até 4 casas)      */
/*   preço unitário   DECIMAL(15,4)  -> vUnCom da NF-e (até 4 casas)          */
/* ------------------------------------------------------------------------- */

BEGIN TRANSACTION;

/* --- Vendas.TotalAmount ------------------------------------------------- */
ALTER TABLE Vendas ADD TotalAmount_new DECIMAL(18, 2) NULL;
GO
UPDATE Vendas SET TotalAmount_new = CONVERT(DECIMAL(18,2), dbo.fn_HorusParseDecimal(TotalAmount));
ALTER TABLE Vendas ALTER COLUMN TotalAmount_new DECIMAL(18, 2) NOT NULL;
ALTER TABLE Vendas DROP CONSTRAINT DF_Vendas_TotalAmount;
ALTER TABLE Vendas DROP COLUMN TotalAmount;
EXEC sp_rename N'Vendas.TotalAmount_new', N'TotalAmount', N'COLUMN';
ALTER TABLE Vendas ADD CONSTRAINT DF_Vendas_TotalAmount DEFAULT 0 FOR TotalAmount;
GO

/* --- VendaItens: Quantity, UnitPrice, ItemTotal -------------------------- */
ALTER TABLE VendaItens ADD Quantity_new  DECIMAL(15, 4) NULL;
ALTER TABLE VendaItens ADD UnitPrice_new DECIMAL(15, 4) NULL;
ALTER TABLE VendaItens ADD ItemTotal_new DECIMAL(18, 2) NULL;
GO
UPDATE VendaItens
   SET Quantity_new  = CONVERT(DECIMAL(15,4), Quantity),
       UnitPrice_new = CONVERT(DECIMAL(15,4), dbo.fn_HorusParseDecimal(UnitPrice)),
       ItemTotal_new = CONVERT(DECIMAL(18,2), dbo.fn_HorusParseDecimal(ItemTotal));

ALTER TABLE VendaItens ALTER COLUMN Quantity_new  DECIMAL(15, 4) NOT NULL;
ALTER TABLE VendaItens ALTER COLUMN UnitPrice_new DECIMAL(15, 4) NOT NULL;
ALTER TABLE VendaItens ALTER COLUMN ItemTotal_new DECIMAL(18, 2) NOT NULL;

ALTER TABLE VendaItens DROP CONSTRAINT DF_VendaItens_UnitPrice;
ALTER TABLE VendaItens DROP CONSTRAINT DF_VendaItens_ItemTotal;
ALTER TABLE VendaItens DROP COLUMN Quantity, UnitPrice, ItemTotal;

EXEC sp_rename N'VendaItens.Quantity_new',  N'Quantity',  N'COLUMN';
EXEC sp_rename N'VendaItens.UnitPrice_new', N'UnitPrice', N'COLUMN';
EXEC sp_rename N'VendaItens.ItemTotal_new', N'ItemTotal', N'COLUMN';

ALTER TABLE VendaItens ADD CONSTRAINT DF_VendaItens_Quantity  DEFAULT 0 FOR Quantity;
ALTER TABLE VendaItens ADD CONSTRAINT DF_VendaItens_UnitPrice DEFAULT 0 FOR UnitPrice;
ALTER TABLE VendaItens ADD CONSTRAINT DF_VendaItens_ItemTotal DEFAULT 0 FOR ItemTotal;
GO

/* --- Produtos ------------------------------------------------------------ */
ALTER TABLE Produtos ADD ProductQnt_new          DECIMAL(15, 4) NULL;
ALTER TABLE Produtos ADD ProductUnitPrice_new    DECIMAL(15, 4) NULL;
ALTER TABLE Produtos ADD ProductSalePrice_new    DECIMAL(15, 4) NULL;
ALTER TABLE Produtos ADD TotalPriceOnProduct_new DECIMAL(18, 2) NULL;
GO
UPDATE Produtos
   SET ProductQnt_new          = CONVERT(DECIMAL(15,4), dbo.fn_HorusParseDecimal(ProductQnt)),
       ProductUnitPrice_new    = CONVERT(DECIMAL(15,4), dbo.fn_HorusParseDecimal(ProductUnitPrice)),
       ProductSalePrice_new    = CONVERT(DECIMAL(15,4), dbo.fn_HorusParseDecimal(ProductSalePrice)),
       TotalPriceOnProduct_new = CONVERT(DECIMAL(18,2), dbo.fn_HorusParseDecimal(TotalPriceOnProduct));

ALTER TABLE Produtos ALTER COLUMN ProductQnt_new          DECIMAL(15, 4) NOT NULL;
ALTER TABLE Produtos ALTER COLUMN ProductUnitPrice_new    DECIMAL(15, 4) NOT NULL;
ALTER TABLE Produtos ALTER COLUMN ProductSalePrice_new    DECIMAL(15, 4) NOT NULL;
ALTER TABLE Produtos ALTER COLUMN TotalPriceOnProduct_new DECIMAL(18, 2) NOT NULL;

ALTER TABLE Produtos DROP CONSTRAINT DF_Produtos_ProductQnt;
ALTER TABLE Produtos DROP CONSTRAINT DF_Produtos_ProductUnitPrice;
ALTER TABLE Produtos DROP CONSTRAINT DF_Produtos_ProductSalePrice;
ALTER TABLE Produtos DROP CONSTRAINT DF_Produtos_TotalPriceOnProduct;
ALTER TABLE Produtos DROP COLUMN ProductQnt, ProductUnitPrice, ProductSalePrice, TotalPriceOnProduct;

EXEC sp_rename N'Produtos.ProductQnt_new',          N'ProductQnt',          N'COLUMN';
EXEC sp_rename N'Produtos.ProductUnitPrice_new',    N'ProductUnitPrice',    N'COLUMN';
EXEC sp_rename N'Produtos.ProductSalePrice_new',    N'ProductSalePrice',    N'COLUMN';
EXEC sp_rename N'Produtos.TotalPriceOnProduct_new', N'TotalPriceOnProduct', N'COLUMN';

ALTER TABLE Produtos ADD CONSTRAINT DF_Produtos_ProductQnt          DEFAULT 0 FOR ProductQnt;
ALTER TABLE Produtos ADD CONSTRAINT DF_Produtos_ProductUnitPrice    DEFAULT 0 FOR ProductUnitPrice;
ALTER TABLE Produtos ADD CONSTRAINT DF_Produtos_ProductSalePrice    DEFAULT 0 FOR ProductSalePrice;
ALTER TABLE Produtos ADD CONSTRAINT DF_Produtos_TotalPriceOnProduct DEFAULT 0 FOR TotalPriceOnProduct;
GO

/* --- CaixaSessoes -------------------------------------------------------- */
ALTER TABLE CaixaSessoes ADD OpeningAmount_new DECIMAL(18, 2) NULL;
ALTER TABLE CaixaSessoes ADD ClosingAmount_new DECIMAL(18, 2) NULL;
GO
UPDATE CaixaSessoes
   SET OpeningAmount_new = CONVERT(DECIMAL(18,2), dbo.fn_HorusParseDecimal(OpeningAmount)),
       ClosingAmount_new = CONVERT(DECIMAL(18,2), dbo.fn_HorusParseDecimal(ClosingAmount));

ALTER TABLE CaixaSessoes ALTER COLUMN OpeningAmount_new DECIMAL(18, 2) NOT NULL;
ALTER TABLE CaixaSessoes ALTER COLUMN ClosingAmount_new DECIMAL(18, 2) NOT NULL;

ALTER TABLE CaixaSessoes DROP CONSTRAINT DF_CaixaSessoes_OpeningAmount;
ALTER TABLE CaixaSessoes DROP CONSTRAINT DF_CaixaSessoes_ClosingAmount;
ALTER TABLE CaixaSessoes DROP COLUMN OpeningAmount, ClosingAmount;

EXEC sp_rename N'CaixaSessoes.OpeningAmount_new', N'OpeningAmount', N'COLUMN';
EXEC sp_rename N'CaixaSessoes.ClosingAmount_new', N'ClosingAmount', N'COLUMN';

ALTER TABLE CaixaSessoes ADD CONSTRAINT DF_CaixaSessoes_OpeningAmount DEFAULT 0 FOR OpeningAmount;
ALTER TABLE CaixaSessoes ADD CONSTRAINT DF_CaixaSessoes_ClosingAmount DEFAULT 0 FOR ClosingAmount;
GO

COMMIT TRANSACTION;
GO

/* ------------------------------------------------------------------------- */
/* 4. Numeração interna de venda: unicidade por empresa                       */
/*                                                                            */
/* SaleNumber deixa de ser identificador fiscal e passa a ser apenas o número */
/* interno do pedido. A numeração da NFC-e (nNF + série) vive em              */
/* DocumentosFiscais/FiscalSequencias — ver script 02.                        */
/* ------------------------------------------------------------------------- */
IF EXISTS (SELECT 1 FROM sys.key_constraints WHERE name = N'UQ_Vendas_SaleNumber')
    ALTER TABLE Vendas DROP CONSTRAINT UQ_Vendas_SaleNumber;

IF NOT EXISTS (SELECT 1 FROM sys.key_constraints WHERE name = N'UQ_Vendas_Company_SaleNumber')
    ALTER TABLE Vendas ADD CONSTRAINT UQ_Vendas_Company_SaleNumber UNIQUE (CompanyId, SaleNumber);

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_Vendas_Company_SaleDate')
    CREATE INDEX IX_Vendas_Company_SaleDate ON Vendas (CompanyId, SaleDate DESC) INCLUDE (TotalAmount);
GO

/* ------------------------------------------------------------------------- */
/* Diagnóstico (para rodar antes, se a validação abortar)                     */
/* ------------------------------------------------------------------------- */
-- SELECT Id, TotalAmount FROM Vendas       WHERE dbo.fn_HorusParseDecimal(TotalAmount) IS NULL;
-- SELECT Id, UnitPrice, ItemTotal FROM VendaItens WHERE dbo.fn_HorusParseDecimal(UnitPrice) IS NULL
--                                                    OR dbo.fn_HorusParseDecimal(ItemTotal) IS NULL;
-- SELECT Id, ProductCode, ProductQnt, ProductSalePrice FROM Produtos
--  WHERE dbo.fn_HorusParseDecimal(ProductQnt) IS NULL OR dbo.fn_HorusParseDecimal(ProductSalePrice) IS NULL;
