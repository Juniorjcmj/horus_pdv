/*
 * Arquivo: API/NETCORE/DataBase/Migrations/34_lotes_validade.sql
 * Objetivo: controle de validade por LOTE (Fase 1). Cria ProdutoLotes, adiciona prazo padrão e janela
 *           de alerta de validade por categoria e gera um lote "inicial" para cada produto que já
 *           tinha validade cadastrada, para nada do que está em produção ficar sem controle.
 *
 * Fase 1 é informativa: a venda NÃO baixa lote. O saldo por lote é ESTIMADO no consumo FEFO
 * (o estoque atual do produto é atribuído aos lotes de validade mais distante primeiro).
 *
 * Idempotente: pode ser executado no boot repetidamente sem erro.
 */

SET NOCOUNT ON;
GO

/* ------------------------------------------------------------------------- */
/* 1. Tabela de lotes                                                        */
/* ------------------------------------------------------------------------- */
IF OBJECT_ID(N'ProdutoLotes', N'U') IS NULL
BEGIN
    CREATE TABLE ProdutoLotes (
        Id              NVARCHAR(40)    NOT NULL,
        CompanyId       NVARCHAR(40)    NOT NULL,
        ProdutoId       NVARCHAR(40)    NOT NULL,
        NumeroLote      NVARCHAR(60)    NULL,
        DataValidade    DATE            NOT NULL,
        QtdInicial      DECIMAL(15,4)   NOT NULL,
        Origem          NVARCHAR(20)    NOT NULL CONSTRAINT DF_ProdutoLotes_Origem DEFAULT N'manual',
        ValidadePadrao  BIT             NOT NULL CONSTRAINT DF_ProdutoLotes_ValidadePadrao DEFAULT 0,
        CriadoEm        DATETIMEOFFSET  NOT NULL CONSTRAINT DF_ProdutoLotes_CriadoEm DEFAULT SYSDATETIMEOFFSET(),
        CriadoPorNome   NVARCHAR(120)   NULL,
        CONSTRAINT PK_ProdutoLotes PRIMARY KEY (Id)
    );
END;
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_ProdutoLotes_Produto' AND object_id = OBJECT_ID(N'ProdutoLotes'))
BEGIN
    CREATE INDEX IX_ProdutoLotes_Produto
        ON ProdutoLotes (CompanyId, ProdutoId, DataValidade DESC);
END;
GO

/* ------------------------------------------------------------------------- */
/* 2. Prazo padrão (sugestão de validade na entrada) e janela de alerta      */
/*    por categoria. NULL = sem prazo padrão / usa o alerta do produto.      */
/* ------------------------------------------------------------------------- */
IF COL_LENGTH(N'Categorias', N'PrazoPadraoValidadeDias') IS NULL
BEGIN
    ALTER TABLE Categorias ADD PrazoPadraoValidadeDias INT NULL;
END;
GO

IF COL_LENGTH(N'Categorias', N'DiasAlertaValidade') IS NULL
BEGIN
    ALTER TABLE Categorias ADD DiasAlertaValidade INT NULL;
END;
GO

/* ------------------------------------------------------------------------- */
/* 3. Valores iniciais sugeridos (só onde ainda não foram definidos).        */
/*    Ajustáveis na tela de validade; categorias não listadas ficam sem      */
/*    prazo padrão (ex.: limpeza, higiene, bazar).                           */
/* ------------------------------------------------------------------------- */
UPDATE c SET c.PrazoPadraoValidadeDias = v.Prazo, c.DiasAlertaValidade = v.Alerta
  FROM Categorias c
  JOIN (VALUES
        (N'cat-acougue',                5,   2),
        (N'cat-acougue-bovinos',        5,   2),
        (N'cat-acougue-aves',           5,   2),
        (N'cat-acougue-suinos',         5,   2),
        (N'cat-frios',                  20,  5),
        (N'cat-frios-queijos',          30,  7),
        (N'cat-frios-embutidos',        20,  5),
        (N'cat-laticinios',             30,  7),
        (N'cat-laticinios-leite',       120, 15),
        (N'cat-laticinios-iogurtes',    30,  7),
        (N'cat-laticinios-manteigas',   60,  10),
        (N'cat-hortifruti',             7,   2),
        (N'cat-hortifruti-frutas',      7,   2),
        (N'cat-hortifruti-legumes',     7,   2),
        (N'cat-hortifruti-verduras',    5,   2),
        (N'cat-padaria',                5,   2),
        (N'cat-padaria-paes',           3,   1),
        (N'cat-padaria-bolos',          5,   2),
        (N'cat-padaria-biscoitos',      180, 30),
        (N'cat-congelados',             180, 15),
        (N'cat-bebidas',                180, 30),
        (N'cat-mercearia',              365, 60),
        (N'cat-bomboniere',             180, 30)
       ) AS v(Id, Prazo, Alerta) ON v.Id = c.Id
 WHERE c.PrazoPadraoValidadeDias IS NULL
   AND c.DiasAlertaValidade IS NULL;
GO

/* ------------------------------------------------------------------------- */
/* 4. Lote inicial para quem já tinha validade cadastrada e estoque          */
/* ------------------------------------------------------------------------- */
INSERT INTO ProdutoLotes (Id, CompanyId, ProdutoId, NumeroLote, DataValidade, QtdInicial, Origem, ValidadePadrao, CriadoPorNome)
SELECT LEFT(CONCAT(N'li-', p.Id), 40), p.CompanyId, p.Id, NULL, p.DataValidade, p.ProductQnt, N'inicial', 0, N'Migração inicial'
  FROM Produtos p
 WHERE p.ControlaValidade = 1
   AND p.DataValidade IS NOT NULL
   AND p.ProductQnt > 0
   AND NOT EXISTS (SELECT 1 FROM ProdutoLotes l WHERE l.CompanyId = p.CompanyId AND l.ProdutoId = p.Id);
GO
