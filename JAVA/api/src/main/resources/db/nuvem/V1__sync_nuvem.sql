-- Receptor da nuvem (perfil "nuvem", change api-java-modo-loja): tabelas Sync* + Change Tracking + snapshot (D3, D4 a D11).
-- Roda SEM transacao (V1__sync_nuvem.sql.conf): ALTER DATABASE nao aceita transacao. Por isso tudo e idempotente
-- (pode ser repetido depois de uma falha no meio). Nao toca em nenhuma tabela da API .NET alem de ligar o tracking.
-- Historico proprio: flyway_quack_sync. A lista de tabelas do tracking deve bater com o catalogo
-- (CatalogoSincronizacao: todas as nao FORA); o ChangeTrackingNuvemIT confere.

------------------------------------------------------------------------------------------------------------------
-- 1. Tabelas de controle
------------------------------------------------------------------------------------------------------------------

-- Um servidor de loja por empresa (D5, D9). O token nunca e guardado: so o hash SHA-256 (hex).
IF OBJECT_ID(N'dbo.SyncServidores', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.SyncServidores (
        Id            NVARCHAR(40)      NOT NULL,
        CompanyId     NVARCHAR(40)      NOT NULL,
        Nome          NVARCHAR(120)     NOT NULL,
        TokenHash     NVARCHAR(64)      NOT NULL,
        Status        NVARCHAR(20)      NOT NULL CONSTRAINT DF_SyncServidores_Status DEFAULT N'pendente',  -- pendente | ativo | revogado
        CriadoEm      DATETIMEOFFSET(7) NOT NULL CONSTRAINT DF_SyncServidores_Criado DEFAULT SYSDATETIMEOFFSET(),
        AtivadoEm     DATETIMEOFFSET(7) NULL,
        UltimoContato DATETIMEOFFSET(7) NULL,
        RevogadoEm    DATETIMEOFFSET(7) NULL,
        RevogadoPor   NVARCHAR(40)      NULL,
        CONSTRAINT PK_SyncServidores PRIMARY KEY CLUSTERED (Id),
        CONSTRAINT UQ_SyncServidores_Token UNIQUE (TokenHash)
    );
    -- No maximo um servidor nao revogado por empresa.
    CREATE UNIQUE NONCLUSTERED INDEX UX_SyncServidores_EmpresaAtiva
        ON dbo.SyncServidores (CompanyId) WHERE Status <> N'revogado';
END;

-- Estado por empresa: ate onde a nuvem ja aplicou da loja e desde onde o detector olha (D4, D6).
IF OBJECT_ID(N'dbo.SyncEstadoNuvem', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.SyncEstadoNuvem (
        CompanyId             NVARCHAR(40)      NOT NULL,
        ServidorId            NVARCHAR(40)      NULL,
        AplicadoAte           BIGINT            NOT NULL CONSTRAINT DF_SyncEstadoNuvem_Ate DEFAULT 0,
        UltimoLoteId          NVARCHAR(40)      NULL,
        DetectorDesde         BIGINT            NULL,
        UltimaSincronizacaoEm DATETIMEOFFSET(7) NULL,
        AtualizadoEm          DATETIMEOFFSET(7) NOT NULL CONSTRAINT DF_SyncEstadoNuvem_Atual DEFAULT SYSDATETIMEOFFSET(),
        CONSTRAINT PK_SyncEstadoNuvem PRIMARY KEY CLUSTERED (CompanyId)
    );
END;

-- Partes de um lote grande, guardadas ate chegar a ultima (D4). Payload = JSON em gzip.
IF OBJECT_ID(N'dbo.SyncLotePartes', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.SyncLotePartes (
        CompanyId   NVARCHAR(40)      NOT NULL,
        LoteId      NVARCHAR(40)      NOT NULL,
        Parte       INT               NOT NULL,
        TotalPartes INT               NOT NULL,
        VersaoDe    BIGINT            NOT NULL,
        VersaoAte   BIGINT            NOT NULL,
        Tipo        NVARCHAR(20)      NOT NULL CONSTRAINT DF_SyncLotePartes_Tipo DEFAULT N'incremental',  -- incremental | completo
        Payload     VARBINARY(MAX)    NOT NULL,
        RecebidoEm  DATETIMEOFFSET(7) NOT NULL CONSTRAINT DF_SyncLotePartes_Rec DEFAULT SYSDATETIMEOFFSET(),
        CONSTRAINT PK_SyncLotePartes PRIMARY KEY CLUSTERED (CompanyId, LoteId, Parte)
    );
END;

-- Espelho das colunas EDITAVEL (ultimo valor vindo da loja) das tabelas EDITAVEL: base dos comandos (D6).
IF OBJECT_ID(N'dbo.SyncEspelho', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.SyncEspelho (
        Tabela       NVARCHAR(128)     NOT NULL,
        Chave        NVARCHAR(200)     NOT NULL,           -- chave primaria serializada (valores separados por |)
        CompanyId    NVARCHAR(40)      NOT NULL,
        ColunasJson  NVARCHAR(MAX)     NOT NULL,
        VersaoLoja   BIGINT            NULL,
        AtualizadoEm DATETIMEOFFSET(7) NOT NULL CONSTRAINT DF_SyncEspelho_Atual DEFAULT SYSDATETIMEOFFSET(),
        CONSTRAINT PK_SyncEspelho PRIMARY KEY CLUSTERED (Tabela, Chave)
    );
    CREATE NONCLUSTERED INDEX IX_SyncEspelho_Empresa ON dbo.SyncEspelho (CompanyId, Tabela);
END;

-- Fila de comandos nuvem -> loja (D6).
IF OBJECT_ID(N'dbo.SyncComandos', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.SyncComandos (
        Seq           BIGINT            IDENTITY (1, 1) NOT NULL,
        CompanyId     NVARCHAR(40)      NOT NULL,
        Tipo          NVARCHAR(20)      NOT NULL,          -- alterar | inserir | excluir | forcar | reenviar
        Tabela        NVARCHAR(128)     NOT NULL,
        Chave         NVARCHAR(200)     NOT NULL,
        PayloadJson   NVARCHAR(MAX)     NULL,
        Status        NVARCHAR(20)      NOT NULL CONSTRAINT DF_SyncComandos_Status DEFAULT N'pendente',  -- pendente | aplicado | conflito | falhou | parado
        Tentativas    INT               NOT NULL CONSTRAINT DF_SyncComandos_Tent DEFAULT 0,
        ResultadoJson NVARCHAR(MAX)     NULL,
        CriadoEm      DATETIMEOFFSET(7) NOT NULL CONSTRAINT DF_SyncComandos_Criado DEFAULT SYSDATETIMEOFFSET(),
        AtualizadoEm  DATETIMEOFFSET(7) NOT NULL CONSTRAINT DF_SyncComandos_Atual DEFAULT SYSDATETIMEOFFSET(),
        CONSTRAINT PK_SyncComandos PRIMARY KEY CLUSTERED (Seq)
    );
    CREATE NONCLUSTERED INDEX IX_SyncComandos_Fila ON dbo.SyncComandos (CompanyId, Status, Seq);
END;

-- Gravacoes da nuvem em tabela ou coluna LOJA, guardadas antes de reverter (D7).
IF OBJECT_ID(N'dbo.SyncQuarentena', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.SyncQuarentena (
        Id          BIGINT            IDENTITY (1, 1) NOT NULL,
        CompanyId   NVARCHAR(40)      NOT NULL,
        Tabela      NVARCHAR(128)     NOT NULL,
        Chave       NVARCHAR(200)     NOT NULL,
        Origem      NVARCHAR(40)      NOT NULL,            -- edicao-nuvem | ressincronizacao
        LinhaJson   NVARCHAR(MAX)     NULL,
        Motivo      NVARCHAR(500)     NOT NULL,
        CriadoEm    DATETIMEOFFSET(7) NOT NULL CONSTRAINT DF_SyncQuarentena_Criado DEFAULT SYSDATETIMEOFFSET(),
        ResolvidoEm DATETIMEOFFSET(7) NULL,
        ResolvidoPor NVARCHAR(40)     NULL,
        CONSTRAINT PK_SyncQuarentena PRIMARY KEY CLUSTERED (Id)
    );
    CREATE NONCLUSTERED INDEX IX_SyncQuarentena_Abertas ON dbo.SyncQuarentena (CompanyId, ResolvidoEm);
END;

-- AuditLog: Id da loja -> Id da nuvem, para inserir uma vez so (D4).
IF OBJECT_ID(N'dbo.SyncAuditoriaMapa', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.SyncAuditoriaMapa (
        CompanyId NVARCHAR(40) NOT NULL,
        IdLoja    BIGINT       NOT NULL,
        IdNuvem   BIGINT       NOT NULL,
        CONSTRAINT PK_SyncAuditoriaMapa PRIMARY KEY CLUSTERED (CompanyId, IdLoja)
    );
END;

IF OBJECT_ID(N'dbo.SyncAlertas', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.SyncAlertas (
        Motivo       NVARCHAR(100)     NOT NULL,
        CompanyId    NVARCHAR(40)      NOT NULL CONSTRAINT DF_SyncAlertas_Company DEFAULT N'',
        Detalhe      NVARCHAR(1000)    NULL,
        UltimoEnvio  DATETIMEOFFSET(7) NULL,
        UltimaEm     DATETIMEOFFSET(7) NOT NULL CONSTRAINT DF_SyncAlertas_Ultima DEFAULT SYSDATETIMEOFFSET(),
        Contagem     INT               NOT NULL CONSTRAINT DF_SyncAlertas_Cont DEFAULT 1,
        CONSTRAINT PK_SyncAlertas PRIMARY KEY CLUSTERED (Motivo, CompanyId)
    );
END;

IF OBJECT_ID(N'dbo.SyncConferencias', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.SyncConferencias (
        Id          BIGINT            IDENTITY (1, 1) NOT NULL,
        CompanyId   NVARCHAR(40)      NOT NULL,
        ExecutadaEm DATETIMEOFFSET(7) NOT NULL CONSTRAINT DF_SyncConf_Exec DEFAULT SYSDATETIMEOFFSET(),
        Origem      NVARCHAR(20)      NOT NULL,            -- agendada | manual
        Resultado   NVARCHAR(20)      NOT NULL,            -- ok | divergente | erro
        Voltas      INT               NOT NULL CONSTRAINT DF_SyncConf_Voltas DEFAULT 0,
        DetalheJson NVARCHAR(MAX)     NULL,
        CONSTRAINT PK_SyncConferencias PRIMARY KEY CLUSTERED (Id)
    );
END;

-- Cargas iniciais abertas por servidor (D5).
IF OBJECT_ID(N'dbo.SyncCargas', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.SyncCargas (
        Id            NVARCHAR(40)      NOT NULL,
        CompanyId     NVARCHAR(40)      NOT NULL,
        ServidorId    NVARCHAR(40)      NOT NULL,
        VersaoRetrato BIGINT            NOT NULL,
        Status        NVARCHAR(20)      NOT NULL CONSTRAINT DF_SyncCargas_Status DEFAULT N'aberta',  -- aberta | ativada | cancelada
        CriadoEm      DATETIMEOFFSET(7) NOT NULL CONSTRAINT DF_SyncCargas_Criado DEFAULT SYSDATETIMEOFFSET(),
        AtualizadoEm  DATETIMEOFFSET(7) NOT NULL CONSTRAINT DF_SyncCargas_Atual DEFAULT SYSDATETIMEOFFSET(),
        CONSTRAINT PK_SyncCargas PRIMARY KEY CLUSTERED (Id)
    );
END;

------------------------------------------------------------------------------------------------------------------
-- 2. Snapshot e Change Tracking (D3): retencao de 3 dias; TRACK_COLUMNS_UPDATED = OFF (o detector compara com o espelho)
------------------------------------------------------------------------------------------------------------------
DECLARE @sql NVARCHAR(MAX);

IF EXISTS (SELECT 1 FROM sys.databases WHERE database_id = DB_ID() AND snapshot_isolation_state = 0)
BEGIN
    SET @sql = N'ALTER DATABASE ' + QUOTENAME(DB_NAME()) + N' SET ALLOW_SNAPSHOT_ISOLATION ON';
    EXEC (@sql);
END;

IF NOT EXISTS (SELECT 1 FROM sys.change_tracking_databases WHERE database_id = DB_ID())
BEGIN
    SET @sql = N'ALTER DATABASE ' + QUOTENAME(DB_NAME())
             + N' SET CHANGE_TRACKING = ON (CHANGE_RETENTION = 3 DAYS, AUTO_CLEANUP = ON)';
    EXEC (@sql);
END;

DECLARE @tabelas TABLE (Nome SYSNAME NOT NULL);
INSERT INTO @tabelas (Nome) VALUES
    (N'Empresas'), (N'Categorias'), (N'Fornecedores'), (N'Usuarios'), (N'Clientes'), (N'EmpresaTema'),
    (N'CustomerCreditPolicies'), (N'EstoqueConfigAnalise'), (N'LoteConfig'), (N'RegrasEmissaoNfce'),
    (N'Produtos'), (N'Promocoes'), (N'PromocaoProdutos'),
    (N'CaixaSessoes'), (N'Vendas'), (N'VendaItens'), (N'VendaPagamentos'), (N'VendaCancelamentos'),
    (N'Pedidos'), (N'PedidoItens'), (N'DocumentosFiscais'), (N'FiscalSequencias'),
    (N'AutorizacoesPreco'), (N'CaixaMovimentos'), (N'EstoqueMovimentacoes'), (N'FiadoMovimentos'),
    (N'Inventarios'), (N'InventarioContagens'), (N'InventarioEventos'), (N'InventarioItens'),
    (N'LoteConsumos'), (N'ProdutoLotes'), (N'MapeamentoProdutoFornecedor'), (N'NotasEntradaArquivo'),
    (N'OrdemCompra'), (N'OrdemCompraItens'), (N'ModuloMercadoRegistros'), (N'AuditLog');

DECLARE @nome SYSNAME;
DECLARE cur CURSOR LOCAL FAST_FORWARD FOR SELECT Nome FROM @tabelas;
OPEN cur;
FETCH NEXT FROM cur INTO @nome;
WHILE @@FETCH_STATUS = 0
BEGIN
    IF OBJECT_ID(N'dbo.' + QUOTENAME(@nome), N'U') IS NULL
        THROW 50001, 'Tabela do catalogo ausente: o banco da nuvem precisa ter o esquema da API .NET antes do perfil nuvem.', 1;
    IF NOT EXISTS (SELECT 1 FROM sys.change_tracking_tables WHERE object_id = OBJECT_ID(N'dbo.' + QUOTENAME(@nome)))
    BEGIN
        SET @sql = N'ALTER TABLE dbo.' + QUOTENAME(@nome) + N' ENABLE CHANGE_TRACKING WITH (TRACK_COLUMNS_UPDATED = OFF)';
        EXEC (@sql);
    END;
    FETCH NEXT FROM cur INTO @nome;
END;
CLOSE cur;
DEALLOCATE cur;
