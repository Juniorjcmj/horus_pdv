/*
 * Arquivo: API/NETCORE/DataBase/Migrations/56_inventario.sql
 * Objetivo: inventário de estoque (contagem física + acerto com autorização do gerente).
 *   Inventarios          cabeçalho (escopo, status, quem abriu/aplicou/cancelou, totais)
 *   InventarioItens      uma linha por produto do escopo, criada na abertura (lista de "não contados")
 *   InventarioContagens  append-only: cada contagem lançada (a anulação é a única atualização permitida)
 *   InventarioEventos    append-only: abertura, recontagem, tentativas de aplicação, aplicado, cancelado
 *
 * Idempotente: pode ser executado no boot repetidamente sem erro.
 */

SET NOCOUNT ON;
GO

IF OBJECT_ID(N'Inventarios', N'U') IS NULL
BEGIN
    CREATE TABLE Inventarios (
        Id                  NVARCHAR(40)    NOT NULL,
        CompanyId           NVARCHAR(40)    NOT NULL,
        Numero              INT             NOT NULL,
        Descricao           NVARCHAR(200)   NOT NULL,
        Escopo              NVARCHAR(20)    NOT NULL,   /* 'loja' | 'categorias' | 'produtos' */
        EscopoJson          NVARCHAR(MAX)   NULL,       /* ids de categorias/produtos informados na abertura */
        Status              NVARCHAR(20)    NOT NULL CONSTRAINT DF_Inventarios_Status DEFAULT N'em_contagem', /* em_contagem | aplicado | cancelado */
        AbertoPorId         NVARCHAR(40)    NOT NULL,
        AbertoPorNome       NVARCHAR(180)   NOT NULL,
        AbertoEm            DATETIMEOFFSET  NOT NULL CONSTRAINT DF_Inventarios_AbertoEm DEFAULT SYSDATETIMEOFFSET(),
        AplicadoPorId       NVARCHAR(40)    NULL,
        AplicadoPorNome     NVARCHAR(180)   NULL,
        AplicadoEm          DATETIMEOFFSET  NULL,
        SupervisorId        NVARCHAR(40)    NULL,
        SupervisorNome      NVARCHAR(180)   NULL,
        Justificativa       NVARCHAR(500)   NULL,
        ZerarNaoContados    BIT             NOT NULL CONSTRAINT DF_Inventarios_Zerar DEFAULT 0,
        CanceladoPorId      NVARCHAR(40)    NULL,
        CanceladoPorNome    NVARCHAR(180)   NULL,
        CanceladoEm         DATETIMEOFFSET  NULL,
        MotivoCancelamento  NVARCHAR(500)   NULL,
        TotalSobra          DECIMAL(15,2)   NULL,       /* valor (R$) das sobras aplicadas */
        TotalFalta          DECIMAL(15,2)   NULL,       /* valor (R$) das faltas aplicadas (negativo) */
        TotalLiquido        DECIMAL(15,2)   NULL,
        CONSTRAINT PK_Inventarios PRIMARY KEY (Id)
    );
END;
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'UQ_Inventarios_Numero' AND object_id = OBJECT_ID(N'Inventarios'))
    CREATE UNIQUE INDEX UQ_Inventarios_Numero ON Inventarios (CompanyId, Numero);
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_Inventarios_Status' AND object_id = OBJECT_ID(N'Inventarios'))
    CREATE INDEX IX_Inventarios_Status ON Inventarios (CompanyId, Status, AbertoEm DESC);
GO

IF OBJECT_ID(N'InventarioItens', N'U') IS NULL
BEGIN
    CREATE TABLE InventarioItens (
        InventarioId        NVARCHAR(40)    NOT NULL,
        ProdutoId           NVARCHAR(40)    NOT NULL,
        CompanyId           NVARCHAR(40)    NOT NULL,
        Codigo              NVARCHAR(80)    NOT NULL,
        Nome                NVARCHAR(180)   NOT NULL,
        Unidade             NVARCHAR(6)     NOT NULL,
        Custo               DECIMAL(15,4)   NOT NULL,
        SaldoNa1aContagem   DECIMAL(15,4)   NULL,
        PrimeiraContagemEm  DATETIMEOFFSET  NULL,
        UltimaContagemEm    DATETIMEOFFSET  NULL,
        Recontar            BIT             NOT NULL CONSTRAINT DF_InventarioItens_Recontar DEFAULT 0,
        Contado             DECIMAL(15,4)   NULL,       /* soma das contagens válidas (null = não contado) */
        SaldoAntes          DECIMAL(15,4)   NULL,       /* preenchidos ao aplicar */
        Delta               DECIMAL(15,4)   NULL,
        SaldoDepois         DECIMAL(15,4)   NULL,
        ValorDelta          DECIMAL(15,2)   NULL,
        PisoAplicado        DECIMAL(15,4)   NULL,       /* quanto faltou para o saldo não ficar negativo */
        CONSTRAINT PK_InventarioItens PRIMARY KEY (InventarioId, ProdutoId),
        CONSTRAINT FK_InventarioItens_Inventario FOREIGN KEY (InventarioId) REFERENCES Inventarios (Id)
    );
END;
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_InventarioItens_Produto' AND object_id = OBJECT_ID(N'InventarioItens'))
    CREATE INDEX IX_InventarioItens_Produto ON InventarioItens (CompanyId, ProdutoId);
GO

IF OBJECT_ID(N'InventarioContagens', N'U') IS NULL
BEGIN
    CREATE TABLE InventarioContagens (
        Id              NVARCHAR(40)    NOT NULL,
        InventarioId    NVARCHAR(40)    NOT NULL,
        CompanyId       NVARCHAR(40)    NOT NULL,
        ProdutoId       NVARCHAR(40)    NOT NULL,
        Quantidade      DECIMAL(15,4)   NOT NULL,
        Local           NVARCHAR(80)    NULL,
        UsuarioId       NVARCHAR(40)    NOT NULL,
        UsuarioNome     NVARCHAR(180)   NOT NULL,
        Terminal        NVARCHAR(80)    NULL,
        Ip              NVARCHAR(64)    NULL,
        SaldoSistema    DECIMAL(15,4)   NOT NULL,
        CriadoEm        DATETIMEOFFSET  NOT NULL CONSTRAINT DF_InventarioContagens_CriadoEm DEFAULT SYSDATETIMEOFFSET(),
        AnuladaPorId    NVARCHAR(40)    NULL,
        AnuladaPorNome  NVARCHAR(180)   NULL,
        AnuladaEm       DATETIMEOFFSET  NULL,
        AnuladaMotivo   NVARCHAR(300)   NULL,
        CONSTRAINT PK_InventarioContagens PRIMARY KEY (Id),
        CONSTRAINT FK_InventarioContagens_Inventario FOREIGN KEY (InventarioId) REFERENCES Inventarios (Id)
    );
END;
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_InventarioContagens_Inv' AND object_id = OBJECT_ID(N'InventarioContagens'))
    CREATE INDEX IX_InventarioContagens_Inv ON InventarioContagens (InventarioId, ProdutoId, CriadoEm);
GO

IF OBJECT_ID(N'InventarioEventos', N'U') IS NULL
BEGIN
    CREATE TABLE InventarioEventos (
        Id              NVARCHAR(40)    NOT NULL,
        InventarioId    NVARCHAR(40)    NOT NULL,
        CompanyId       NVARCHAR(40)    NOT NULL,
        Tipo            NVARCHAR(30)    NOT NULL,   /* aberto | recontagem | aplicacao_negada | aplicado | cancelado */
        OcorridoEm      DATETIMEOFFSET  NOT NULL CONSTRAINT DF_InventarioEventos_Em DEFAULT SYSDATETIMEOFFSET(),
        UsuarioId       NVARCHAR(40)    NOT NULL,
        UsuarioNome     NVARCHAR(180)   NOT NULL,
        SupervisorId    NVARCHAR(40)    NULL,
        SupervisorNome  NVARCHAR(180)   NULL,
        Ip              NVARCHAR(64)    NULL,
        Terminal        NVARCHAR(80)    NULL,
        Descricao       NVARCHAR(500)   NULL,
        CONSTRAINT PK_InventarioEventos PRIMARY KEY (Id)
    );
END;
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_InventarioEventos_Inv' AND object_id = OBJECT_ID(N'InventarioEventos'))
    CREATE INDEX IX_InventarioEventos_Inv ON InventarioEventos (InventarioId, OcorridoEm);
GO
