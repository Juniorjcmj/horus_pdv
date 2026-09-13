/*
 * Arquivo: API/NETCORE/DataBase/Migrations/08_multitenant_seguranca.sql
 * Objetivo: garantir o isolamento estrito entre empresas (multi-tenant) no banco de dados:
 *           1. Remove a unicidade global de SaleNumber em Vendas e cria UQ_Vendas_Company_SaleNumber (CompanyId, SaleNumber).
 *           2. Ajusta a unicidade de CPF em Usuarios para o escopo da empresa (CompanyId, Cpf), mantendo Email único global.
 *           3. Adiciona índices compostos cobrindo CompanyId para alta performance de isolamento em consultas.
 *
 * Idempotente: pode ser executado múltiplas vezes sem erros.
 */

USE HorusPdv;
GO

SET NOCOUNT ON;
GO

/* ------------------------------------------------------------------------- */
/* 1. Vendas — isolamento de numeração por empresa                           */
/* ------------------------------------------------------------------------- */
IF EXISTS (SELECT 1 FROM sys.key_constraints WHERE name = N'UQ_Vendas_SaleNumber')
BEGIN
    ALTER TABLE Vendas DROP CONSTRAINT UQ_Vendas_SaleNumber;
END;
GO

IF EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'UQ_Vendas_SaleNumber')
BEGIN
    DROP INDEX UQ_Vendas_SaleNumber ON Vendas;
END;
GO

IF NOT EXISTS (SELECT 1 FROM sys.key_constraints WHERE name = N'UQ_Vendas_Company_SaleNumber')
   AND NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'UQ_Vendas_Company_SaleNumber')
BEGIN
    ALTER TABLE Vendas ADD CONSTRAINT UQ_Vendas_Company_SaleNumber UNIQUE (CompanyId, SaleNumber);
END;
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_Vendas_Company_SaleDate')
BEGIN
    CREATE INDEX IX_Vendas_Company_SaleDate ON Vendas (CompanyId, SaleDate DESC);
END;
GO

/* ------------------------------------------------------------------------- */
/* 2. Usuarios — unicidade de CPF por empresa                                */
/* ------------------------------------------------------------------------- */
IF EXISTS (SELECT 1 FROM sys.key_constraints WHERE name = N'UQ_Usuarios_Cpf')
BEGIN
    ALTER TABLE Usuarios DROP CONSTRAINT UQ_Usuarios_Cpf;
END;
GO

IF EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'UQ_Usuarios_Cpf')
BEGIN
    DROP INDEX UQ_Usuarios_Cpf ON Usuarios;
END;
GO

IF NOT EXISTS (SELECT 1 FROM sys.key_constraints WHERE name = N'UQ_Usuarios_Company_Cpf')
   AND NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'UQ_Usuarios_Company_Cpf')
BEGIN
    ALTER TABLE Usuarios ADD CONSTRAINT UQ_Usuarios_Company_Cpf UNIQUE (CompanyId, Cpf);
END;
GO

/* ------------------------------------------------------------------------- */
/* 3. Índices adicionais de performance para isolamento por CompanyId         */
/* ------------------------------------------------------------------------- */
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_Produtos_Company_Code')
BEGIN
    CREATE INDEX IX_Produtos_Company_Code ON Produtos (CompanyId, ProductCode);
END;
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_Clientes_Company_Name')
BEGIN
    CREATE INDEX IX_Clientes_Company_Name ON Clientes (CompanyId, CustomerName);
END;
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_Fornecedores_Company_Name')
BEGIN
    CREATE INDEX IX_Fornecedores_Company_Name ON Fornecedores (CompanyId, FantasyName);
END;
GO
