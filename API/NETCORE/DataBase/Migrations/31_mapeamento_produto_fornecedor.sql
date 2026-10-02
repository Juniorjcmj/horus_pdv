-- Migration 31: De-Para e Vinculo de Produtos de Fornecedor (NF-e de Entrada)
-- Permite atrelar o item da nota (codigoFornecedor/cProd ou GTIN) a um produto ja existente
-- no catalogo da loja (ex.: presunto fatiado / balança).
-- Idempotente e multi-tenant por CompanyId.

IF NOT EXISTS (SELECT 1 FROM sys.tables WHERE name = N'MapeamentoProdutoFornecedor')
BEGIN
    CREATE TABLE MapeamentoProdutoFornecedor (
        Id                          NVARCHAR(100) NOT NULL CONSTRAINT PK_MapeamentoProdutoFornecedor PRIMARY KEY,
        CompanyId                   NVARCHAR(100) NOT NULL,
        FornecedorCnpj              NVARCHAR(20) NOT NULL,
        CodigoProdutoFornecedor     NVARCHAR(100) NOT NULL,
        GtinFornecedor              NVARCHAR(50) NULL,
        DescricaoFornecedor         NVARCHAR(300) NULL,
        ProdutoId                   NVARCHAR(100) NOT NULL,
        CreatedAt                   DATETIME2 NOT NULL CONSTRAINT DF_MapeamentoProdFornec_CreatedAt DEFAULT (SYSUTCDATETIME()),
        UpdatedAt                   DATETIME2 NOT NULL CONSTRAINT DF_MapeamentoProdFornec_UpdatedAt DEFAULT (SYSUTCDATETIME())
    );

    CREATE UNIQUE INDEX IX_MapeamentoProdFornec_Lookup 
        ON MapeamentoProdutoFornecedor(CompanyId, FornecedorCnpj, CodigoProdutoFornecedor);

    CREATE INDEX IX_MapeamentoProdFornec_Produto 
        ON MapeamentoProdutoFornecedor(CompanyId, ProdutoId);
END
