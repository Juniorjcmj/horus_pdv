/**
 * Migração 16 — Ordens de compra e reposição de estoque.
 * Cria tabelas OrdemCompra (cabeçalho) e OrdemCompraItens (itens da ordem).
 */

IF OBJECT_ID(N'OrdemCompra', N'U') IS NULL
BEGIN
    CREATE TABLE OrdemCompra
    (
        Id              NVARCHAR(40)        NOT NULL CONSTRAINT PK_OrdemCompra PRIMARY KEY,
        CompanyId       NVARCHAR(40)        NOT NULL,
        OrderNumber     NVARCHAR(20)        NOT NULL,
        SupplierId      NVARCHAR(40)        NULL,
        SupplierName    NVARCHAR(180)       NOT NULL,
        SupplierCnpj    NVARCHAR(30)        NULL,
        Status          TINYINT             NOT NULL CONSTRAINT DF_OC_Status DEFAULT 0,
        CreatedBy       NVARCHAR(40)        NULL,
        CreatedByName   NVARCHAR(180)       NULL,
        CreatedAt       DATETIMEOFFSET      NOT NULL CONSTRAINT DF_OC_CreatedAt DEFAULT SYSDATETIMEOFFSET(),
        ReceivedAt      DATETIMEOFFSET      NULL,
        CanceledAt      DATETIMEOFFSET      NULL,
        Note            NVARCHAR(500)       NULL,
        TotalEstimado   DECIMAL(18,2)       NOT NULL CONSTRAINT DF_OC_Total DEFAULT 0,

        CONSTRAINT FK_OC_Company  FOREIGN KEY (CompanyId)  REFERENCES Empresas(Id),
        CONSTRAINT FK_OC_Supplier FOREIGN KEY (SupplierId) REFERENCES Fornecedores(Id) ON DELETE SET NULL,
        CONSTRAINT UQ_OC_Company_Number UNIQUE (CompanyId, OrderNumber)
    );

    CREATE INDEX IX_OrdemCompra_Company_Status
        ON OrdemCompra (CompanyId, Status, CreatedAt DESC);
END;

IF OBJECT_ID(N'OrdemCompraItens', N'U') IS NULL
BEGIN
    CREATE TABLE OrdemCompraItens
    (
        Id                NVARCHAR(40)    NOT NULL CONSTRAINT PK_OCItens PRIMARY KEY,
        OrdemCompraId     NVARCHAR(40)    NOT NULL,
        ProductCode       NVARCHAR(80)    NOT NULL,
        ProductName       NVARCHAR(180)   NOT NULL,
        Quantity          DECIMAL(15,4)   NOT NULL,
        UnitCost          DECIMAL(15,4)   NOT NULL,
        ItemTotal         DECIMAL(18,2)   NOT NULL,
        QuantityReceived  DECIMAL(15,4)   NOT NULL CONSTRAINT DF_OCItem_QtyRcv DEFAULT 0,

        CONSTRAINT FK_OCItens_OC FOREIGN KEY (OrdemCompraId)
            REFERENCES OrdemCompra(Id) ON DELETE CASCADE
    );
END;
