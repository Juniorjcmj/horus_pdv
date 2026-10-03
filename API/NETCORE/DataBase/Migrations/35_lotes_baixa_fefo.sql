/*
 * Arquivo: API/NETCORE/DataBase/Migrations/35_lotes_baixa_fefo.sql
 * Objetivo: Fase 2 do controle de validade — baixa FEFO por lote na venda.
 *           - ProdutoLotes.QtdAtual: saldo real do lote (baixado pela venda, devolvido pelo cancelamento).
 *           - LoteConsumos: quanto cada venda/ajuste tirou de cada lote (permite estornar no lote certo).
 *           - LoteConfig: modo da baixa por empresa ('desligado', 'sombra' ou 'ativo').
 *
 * Modos: 'sombra' (padrão) grava a baixa real em QtdAtual mas a tela ainda usa o saldo estimado
 * (Fase 1) e mostra as divergências; 'ativo' passa a usar QtdAtual como saldo; 'desligado' não baixa.
 *
 * Idempotente: pode ser executado no boot repetidamente sem erro.
 */

SET NOCOUNT ON;
GO

/* ------------------------------------------------------------------------- */
/* 1. Saldo real do lote                                                     */
/* ------------------------------------------------------------------------- */
IF COL_LENGTH(N'ProdutoLotes', N'QtdAtual') IS NULL
BEGIN
    ALTER TABLE ProdutoLotes ADD QtdAtual DECIMAL(15,4) NULL;
END;
GO

/* Saldo inicial = o mesmo cálculo estimado da Fase 1 (estoque atual atribuído aos lotes de validade
   mais distante primeiro, limitado a QtdInicial), para o número não "pular" na virada. */
;WITH Ordenados AS (
    SELECT l.Id,
           l.QtdInicial,
           p.ProductQnt AS Estoque,
           SUM(l.QtdInicial) OVER (
               PARTITION BY l.CompanyId, l.ProdutoId
               ORDER BY l.DataValidade DESC, l.CriadoEm DESC
               ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING) AS Anterior
      FROM ProdutoLotes l
      JOIN Produtos p ON p.Id = l.ProdutoId AND p.CompanyId = l.CompanyId
     WHERE l.QtdAtual IS NULL
)
UPDATE l
   SET l.QtdAtual = CASE
           WHEN x.Estoque - ISNULL(x.Anterior, 0) <= 0 THEN 0
           WHEN x.Estoque - ISNULL(x.Anterior, 0) >= x.QtdInicial THEN x.QtdInicial
           ELSE x.Estoque - ISNULL(x.Anterior, 0)
       END
  FROM ProdutoLotes l
  JOIN Ordenados x ON x.Id = l.Id;
GO

/* ------------------------------------------------------------------------- */
/* 2. Registro do que cada venda/ajuste consumiu de cada lote                */
/* ------------------------------------------------------------------------- */
IF OBJECT_ID(N'LoteConsumos', N'U') IS NULL
BEGIN
    CREATE TABLE LoteConsumos (
        Id          NVARCHAR(40)    NOT NULL,
        CompanyId   NVARCHAR(40)    NOT NULL,
        RefId       NVARCHAR(60)    NOT NULL,   /* id da venda ou do ajuste */
        Origem      NVARCHAR(20)    NOT NULL,   /* 'venda' | 'ajuste' */
        ProdutoId   NVARCHAR(40)    NOT NULL,
        LoteId      NVARCHAR(40)    NULL,       /* NULL = quantidade que nenhum lote cobria ("sem lote") */
        Quantidade  DECIMAL(15,4)   NOT NULL,
        Estornado   BIT             NOT NULL CONSTRAINT DF_LoteConsumos_Estornado DEFAULT 0,
        CriadoEm    DATETIMEOFFSET  NOT NULL CONSTRAINT DF_LoteConsumos_CriadoEm DEFAULT SYSDATETIMEOFFSET(),
        CONSTRAINT PK_LoteConsumos PRIMARY KEY (Id)
    );
END;
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_LoteConsumos_Ref' AND object_id = OBJECT_ID(N'LoteConsumos'))
BEGIN
    CREATE INDEX IX_LoteConsumos_Ref ON LoteConsumos (CompanyId, RefId, ProdutoId);
END;
GO

/* ------------------------------------------------------------------------- */
/* 3. Modo da baixa por empresa (sem linha = 'sombra')                        */
/* ------------------------------------------------------------------------- */
IF OBJECT_ID(N'LoteConfig', N'U') IS NULL
BEGIN
    CREATE TABLE LoteConfig (
        CompanyId       NVARCHAR(40)    NOT NULL,
        Modo            NVARCHAR(12)    NOT NULL CONSTRAINT DF_LoteConfig_Modo DEFAULT N'sombra',
        AtualizadoEm    DATETIMEOFFSET  NOT NULL CONSTRAINT DF_LoteConfig_AtualizadoEm DEFAULT SYSDATETIMEOFFSET(),
        CONSTRAINT PK_LoteConfig PRIMARY KEY (CompanyId)
    );
END;
GO
