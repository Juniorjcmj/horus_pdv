/*
 * Arquivo: API/NETCORE/DataBase/Migrations/13_modelo_produto_expandido.sql
 * Objetivo: expandir o modelo de produto para suportar múltiplos segmentos de varejo
 *           (material de construção, mercado, etc.) com campos de unidade, marca,
 *           peso/dimensões, estoque expandido, custos e campos comerciais.
 *
 * Pré-requisito: migrações 01-12 aplicadas.
 * Idempotente: pode ser executado repetidamente sem erro (IF COL_LENGTH).
 */

USE HorusPdv;
GO

SET NOCOUNT ON;
GO

/* ------------------------------------------------------------------------- */
/* 1. Unidades de medida (compra vs. venda + fator de conversão)             */
/* ------------------------------------------------------------------------- */
IF COL_LENGTH(N'Produtos', N'UnidadeCompra') IS NULL
    ALTER TABLE Produtos ADD UnidadeCompra NVARCHAR(6) NOT NULL CONSTRAINT DF_Produtos_UnidadeCompra DEFAULT N'UN';
GO

IF COL_LENGTH(N'Produtos', N'FatorConversao') IS NULL
    ALTER TABLE Produtos ADD FatorConversao DECIMAL(15,4) NOT NULL CONSTRAINT DF_Produtos_FatorConversao DEFAULT 1;
GO

IF COL_LENGTH(N'Produtos', N'QtdEmbalagem') IS NULL
    ALTER TABLE Produtos ADD QtdEmbalagem DECIMAL(15,4) NOT NULL CONSTRAINT DF_Produtos_QtdEmbalagem DEFAULT 1;
GO

/* ------------------------------------------------------------------------- */
/* 2. Marca e fabricante                                                      */
/* ------------------------------------------------------------------------- */
IF COL_LENGTH(N'Produtos', N'Marca') IS NULL
    ALTER TABLE Produtos ADD Marca NVARCHAR(120) NULL;
GO

IF COL_LENGTH(N'Produtos', N'Fabricante') IS NULL
    ALTER TABLE Produtos ADD Fabricante NVARCHAR(120) NULL;
GO

IF COL_LENGTH(N'Produtos', N'ReferenciaFabricante') IS NULL
    ALTER TABLE Produtos ADD ReferenciaFabricante NVARCHAR(80) NULL;
GO

/* Índice para busca por marca no PDV */
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_Produtos_Company_Marca')
BEGIN
    CREATE INDEX IX_Produtos_Company_Marca
        ON Produtos (CompanyId, Marca)
        WHERE Marca IS NOT NULL;
END;
GO

/* ------------------------------------------------------------------------- */
/* 3. Peso e dimensões                                                        */
/* ------------------------------------------------------------------------- */
IF COL_LENGTH(N'Produtos', N'PesoLiquidoKg') IS NULL
    ALTER TABLE Produtos ADD PesoLiquidoKg DECIMAL(15,4) NOT NULL CONSTRAINT DF_Produtos_PesoLiquidoKg DEFAULT 0;
GO

IF COL_LENGTH(N'Produtos', N'PesoBrutoKg') IS NULL
    ALTER TABLE Produtos ADD PesoBrutoKg DECIMAL(15,4) NOT NULL CONSTRAINT DF_Produtos_PesoBrutoKg DEFAULT 0;
GO

IF COL_LENGTH(N'Produtos', N'LarguraCm') IS NULL
    ALTER TABLE Produtos ADD LarguraCm DECIMAL(10,2) NOT NULL CONSTRAINT DF_Produtos_LarguraCm DEFAULT 0;
GO

IF COL_LENGTH(N'Produtos', N'AlturaCm') IS NULL
    ALTER TABLE Produtos ADD AlturaCm DECIMAL(10,2) NOT NULL CONSTRAINT DF_Produtos_AlturaCm DEFAULT 0;
GO

IF COL_LENGTH(N'Produtos', N'ComprimentoCm') IS NULL
    ALTER TABLE Produtos ADD ComprimentoCm DECIMAL(10,2) NOT NULL CONSTRAINT DF_Produtos_ComprimentoCm DEFAULT 0;
GO

/* ------------------------------------------------------------------------- */
/* 4. Estoque expandido                                                       */
/* ------------------------------------------------------------------------- */
IF COL_LENGTH(N'Produtos', N'EstoqueMaximo') IS NULL
    ALTER TABLE Produtos ADD EstoqueMaximo DECIMAL(15,4) NOT NULL CONSTRAINT DF_Produtos_EstoqueMaximo DEFAULT 0;
GO

IF COL_LENGTH(N'Produtos', N'LocalizacaoEstoque') IS NULL
    ALTER TABLE Produtos ADD LocalizacaoEstoque NVARCHAR(100) NULL;
GO

/* ------------------------------------------------------------------------- */
/* 5. Dados de custo detalhados                                               */
/* ------------------------------------------------------------------------- */
IF COL_LENGTH(N'Produtos', N'CustoMedio') IS NULL
    ALTER TABLE Produtos ADD CustoMedio DECIMAL(15,4) NOT NULL CONSTRAINT DF_Produtos_CustoMedio DEFAULT 0;
GO

IF COL_LENGTH(N'Produtos', N'CustoComImposto') IS NULL
    ALTER TABLE Produtos ADD CustoComImposto DECIMAL(15,4) NOT NULL CONSTRAINT DF_Produtos_CustoComImposto DEFAULT 0;
GO

IF COL_LENGTH(N'Produtos', N'CustoSemImposto') IS NULL
    ALTER TABLE Produtos ADD CustoSemImposto DECIMAL(15,4) NOT NULL CONSTRAINT DF_Produtos_CustoSemImposto DEFAULT 0;
GO

/* ------------------------------------------------------------------------- */
/* 6. Campos comerciais                                                       */
/* ------------------------------------------------------------------------- */
IF COL_LENGTH(N'Produtos', N'DescontoMaximoPercentual') IS NULL
    ALTER TABLE Produtos ADD DescontoMaximoPercentual DECIMAL(9,4) NOT NULL CONSTRAINT DF_Produtos_DescontoMaxPerc DEFAULT 0;
GO

IF COL_LENGTH(N'Produtos', N'ComissaoPercentual') IS NULL
    ALTER TABLE Produtos ADD ComissaoPercentual DECIMAL(9,4) NOT NULL CONSTRAINT DF_Produtos_ComissaoPerc DEFAULT 0;
GO

IF COL_LENGTH(N'Produtos', N'MarkupCadastrado') IS NULL
    ALTER TABLE Produtos ADD MarkupCadastrado DECIMAL(9,4) NOT NULL CONSTRAINT DF_Produtos_MarkupCadastrado DEFAULT 0;
GO

IF COL_LENGTH(N'Produtos', N'MarkupPraticado') IS NULL
    ALTER TABLE Produtos ADD MarkupPraticado DECIMAL(9,4) NOT NULL CONSTRAINT DF_Produtos_MarkupPraticado DEFAULT 0;
GO

/* ------------------------------------------------------------------------- */
/* 7. Backfill: popular CustoMedio a partir do custo unitário existente       */
/* ------------------------------------------------------------------------- */
UPDATE Produtos
   SET CustoMedio = TRY_CONVERT(DECIMAL(15,4), ProductUnitPrice)
 WHERE CustoMedio = 0
   AND ProductUnitPrice IS NOT NULL
   AND TRY_CONVERT(DECIMAL(15,4), ProductUnitPrice) > 0;
GO

/* Inicializar UnidadeCompra com o mesmo valor de UnidadeComercial */
IF COL_LENGTH(N'Produtos', N'UnidadeComercial') IS NOT NULL
BEGIN
    UPDATE Produtos
       SET UnidadeCompra = UnidadeComercial
     WHERE UnidadeCompra = N'UN'
       AND UnidadeComercial <> N'UN';
END;
GO
