/*
 * Arquivo: API/NETCORE/DataBase/Migrations/29_fornecedores_cnpj_flexivel.sql
 * Objetivo: flexibiliza o cadastro de fornecedores permitindo fornecedores sem CNPJ
 *           ou com formatos alternativos, substituindo restrição única rígida por índice filtrado.
 *
 * Idempotente: pode ser executado repetidas vezes sem erro.
 */

USE HorusPdv;
GO

SET NOCOUNT ON;
GO

IF EXISTS (SELECT 1 FROM sys.key_constraints WHERE name = N'UQ_Fornecedores_Company_Cnpj')
BEGIN
    ALTER TABLE Fornecedores DROP CONSTRAINT UQ_Fornecedores_Company_Cnpj;
END;
GO

IF EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'UQ_Fornecedores_Company_Cnpj')
BEGIN
    DROP INDEX UQ_Fornecedores_Company_Cnpj ON Fornecedores;
END;
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'UQ_Fornecedores_Company_Cnpj')
BEGIN
    CREATE UNIQUE INDEX UQ_Fornecedores_Company_Cnpj
        ON Fornecedores (CompanyId, Cnpj)
        WHERE Cnpj IS NOT NULL AND Cnpj <> '';
END;
GO
