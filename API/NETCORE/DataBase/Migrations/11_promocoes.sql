/* ------------------------------------------------------------------------- */
/* 11_promocoes.sql                                                          */
/* Cria tabelas Promocoes, PromocaoProdutos e colunas de desconto na venda   */
/* Idempotente — executado pelo HorusDatabaseInitializer                     */
/* ------------------------------------------------------------------------- */

SET NOCOUNT ON;

IF OBJECT_ID(N'dbo.Promocoes', N'U') IS NULL
BEGIN
    CREATE TABLE Promocoes (
        Id                  NVARCHAR(40)    NOT NULL,
        CompanyId           NVARCHAR(40)    NOT NULL CONSTRAINT DF_Promocoes_CompanyId DEFAULT N'empresa-principal',
        Nome                NVARCHAR(180)   NOT NULL,
        Tipo                NVARCHAR(30)    NOT NULL, -- desconto_percentual, desconto_valor, preco_fixo, leve_x_pague_y, combo_quantidade, preco_atacado
        ValorDesconto       DECIMAL(18,2)   NULL,
        PrecoFixo           DECIMAL(18,2)   NULL,
        QuantidadeLeva      INT             NULL,
        QuantidadePaga      INT             NULL,
        QuantidadeMinima    INT             NULL,
        InicioVigencia      DATETIMEOFFSET  NOT NULL,
        FimVigencia         DATETIMEOFFSET  NOT NULL,
        Ativa               BIT             NOT NULL CONSTRAINT DF_Promocoes_Ativa DEFAULT 1,
        CategoriaId         NVARCHAR(40)    NULL,
        CriadoPor           NVARCHAR(180)   NOT NULL CONSTRAINT DF_Promocoes_CriadoPor DEFAULT N'',
        CriadoEm            DATETIMEOFFSET  NOT NULL CONSTRAINT DF_Promocoes_CriadoEm DEFAULT SYSDATETIMEOFFSET(),
        CONSTRAINT PK_Promocoes PRIMARY KEY (Id),
        CONSTRAINT FK_Promocoes_Categorias FOREIGN KEY (CategoriaId) REFERENCES Categorias(Id) ON DELETE SET NULL
    );
END;
GO

IF NOT EXISTS (
    SELECT 1 FROM sys.indexes 
    WHERE name = N'IX_Promocoes_Vigencia' 
      AND object_id = OBJECT_ID(N'Promocoes')
)
BEGIN
    CREATE NONCLUSTERED INDEX IX_Promocoes_Vigencia
    ON Promocoes (CompanyId, Ativa, InicioVigencia, FimVigencia);
END;
GO

IF OBJECT_ID(N'dbo.PromocaoProdutos', N'U') IS NULL
BEGIN
    CREATE TABLE PromocaoProdutos (
        Id          NVARCHAR(40)    NOT NULL,
        PromocaoId  NVARCHAR(40)    NOT NULL,
        ProdutoId   NVARCHAR(40)    NOT NULL,
        CONSTRAINT PK_PromocaoProdutos PRIMARY KEY (Id),
        CONSTRAINT FK_PromocaoProdutos_Promocoes FOREIGN KEY (PromocaoId) REFERENCES Promocoes(Id) ON DELETE CASCADE,
        CONSTRAINT FK_PromocaoProdutos_Produtos FOREIGN KEY (ProdutoId) REFERENCES Produtos(Id) ON DELETE CASCADE,
        CONSTRAINT UQ_PromocaoProdutos UNIQUE (PromocaoId, ProdutoId)
    );
END;
GO

IF COL_LENGTH(N'VendaItens', N'Desconto') IS NULL
BEGIN
    ALTER TABLE VendaItens ADD Desconto DECIMAL(18,2) NOT NULL CONSTRAINT DF_VendaItens_Desconto DEFAULT 0;
END;
GO

IF COL_LENGTH(N'VendaItens', N'PromocaoId') IS NULL
BEGIN
    ALTER TABLE VendaItens ADD PromocaoId NVARCHAR(40) NULL;
END;
GO
