-- Operacoes de estoque lancadas na nuvem (change api-java-operacoes-estoque-nuvem, O7 e O9).
-- Somente da API Java: a API .NET nao as conhece.

-- Recursos que o servidor da loja anuncia no cabecalho X-Quack-Recursos do long-poll de comandos
-- (lista separada por virgula; nulo = nenhum). A nuvem so cria o comando "operacao" se a loja anunciar "operacao-estoque".
IF COL_LENGTH(N'dbo.SyncServidores', N'Recursos') IS NULL
    ALTER TABLE dbo.SyncServidores ADD Recursos NVARCHAR(200) NULL;

-- Chaves tocadas por cada comando "operacao": a conferencia nao apaga da nuvem a linha de uma operacao que a loja ainda
-- nao respondeu, e a deteccao do saldo (O3) sabe quais produtos tem operacao pendente (Tabela = 'Produtos').
IF OBJECT_ID(N'dbo.SyncComandoChaves', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.SyncComandoChaves (
        Seq    BIGINT        NOT NULL,
        Tabela NVARCHAR(128) NOT NULL,
        Chave  NVARCHAR(400) NOT NULL,
        CONSTRAINT PK_SyncComandoChaves PRIMARY KEY CLUSTERED (Tabela, Chave, Seq)
    );
    CREATE NONCLUSTERED INDEX IX_SyncComandoChaves_Seq ON dbo.SyncComandoChaves (Seq);
END;
