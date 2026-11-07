-- Importacao de produtos por planilha (change api-java-importacao-produtos, decisao I6).
-- Somente da API Java. Controle local de idempotencia: guarda os produtos cujo saldo inicial ja foi lancado, para a
-- reimportacao da mesma planilha nao duplicar o estoque. Classe FORA do catalogo de sincronizacao (nao sobe para a nuvem).
IF OBJECT_ID(N'dbo.ImportacoesProduto', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.ImportacoesProduto (
        CompanyId    NVARCHAR(40)   NOT NULL,
        ProductCode  NVARCHAR(80)   NOT NULL,
        ImportadoEm  DATETIME2(7)   NOT NULL CONSTRAINT DF_ImportacoesProduto_Em DEFAULT SYSUTCDATETIME(),
        HashArquivo  NVARCHAR(64)   NOT NULL,
        QtdInicial   DECIMAL(18,3)  NOT NULL,
        CONSTRAINT PK_ImportacoesProduto PRIMARY KEY CLUSTERED (CompanyId, ProductCode)
    );
END;
