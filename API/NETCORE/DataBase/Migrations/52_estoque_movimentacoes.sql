-- Migration 52: Tabela de movimentações de estoque com log de auditoria completo.
-- Registra toda operação de estoque (entrada, saída, uso interno, perda) com quem fez, quando e por quê.

IF NOT EXISTS (SELECT 1 FROM sys.tables WHERE name = 'EstoqueMovimentacoes')
BEGIN
    CREATE TABLE EstoqueMovimentacoes (
        Id              NVARCHAR(40)  NOT NULL PRIMARY KEY,
        CompanyId       NVARCHAR(100) NOT NULL,
        ProdutoId       NVARCHAR(40)  NOT NULL,
        Tipo            NVARCHAR(20)  NOT NULL,   -- 'entrada', 'saida', 'uso_interno', 'perda'
        Quantidade      DECIMAL(18,3) NOT NULL,
        EstoqueAnterior DECIMAL(18,3) NOT NULL,
        EstoqueNovo     DECIMAL(18,3) NOT NULL,
        Motivo          NVARCHAR(500) NOT NULL,
        UsuarioId       NVARCHAR(36)  NOT NULL,
        UsuarioNome     NVARCHAR(200) NOT NULL,
        CriadoEm        DATETIME2     NOT NULL DEFAULT SYSUTCDATETIME(),
        CONSTRAINT FK_EstoqueMovimentacoes_Produto FOREIGN KEY (ProdutoId) REFERENCES Produtos(Id)
    );

    CREATE INDEX IX_EstoqueMovimentacoes_Produto ON EstoqueMovimentacoes(CompanyId, ProdutoId, CriadoEm DESC);
    CREATE INDEX IX_EstoqueMovimentacoes_Tipo    ON EstoqueMovimentacoes(CompanyId, Tipo, CriadoEm DESC);
END;
