-- Tabelas de controle da sincronizacao loja -> nuvem (change api-java-modo-loja, D4 a D11).
-- Somente da API Java: a API .NET nao as conhece e nenhuma tabela existente muda. O prefixo Sync fica fora do catalogo.
-- O Change Tracking NAO e ligado aqui: so no perfil loja, na ativacao (ChangeTrackingAdmin; ver design D3/D5).

-- Estado unico da sincronizacao desta loja (sempre a linha Id = 1).
IF OBJECT_ID(N'dbo.SyncEstado', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.SyncEstado (
        Id                    INT               NOT NULL,
        CompanyId             NVARCHAR(40)      NULL,
        ServidorId            NVARCHAR(40)      NULL,
        NuvemUrl              NVARCHAR(300)     NULL,
        TokenCifrado          NVARCHAR(MAX)     NULL,       -- token do servidor (D9), cifrado com SecretProtector (enc:v1:)
        Ativada               BIT               NOT NULL CONSTRAINT DF_SyncEstado_Ativada DEFAULT 0,
        AtivadaEm             DATETIMEOFFSET(7) NULL,
        CargaVersao           BIGINT            NULL,       -- versao V do retrato da carga inicial (D5)
        VersaoDe              BIGINT            NULL,       -- "de" do proximo lote: ultima versao confirmada pela nuvem
        AplicadoAteNuvem      BIGINT            NULL,       -- aplicadoAte devolvido pela nuvem
        UltimaSincronizacaoEm DATETIMEOFFSET(7) NULL,
        UltimoErro            NVARCHAR(500)     NULL,
        TentativasSeguidas    INT               NOT NULL CONSTRAINT DF_SyncEstado_Tent DEFAULT 0,
        ProximaTentativaEm    DATETIMEOFFSET(7) NULL,
        PausadaEm             DATETIMEOFFSET(7) NULL,
        PausadaMotivo         NVARCHAR(500)     NULL,       -- nao nulo = sincronizacao pausada por erro definitivo (D4)
        ComandosApos          BIGINT            NOT NULL CONSTRAINT DF_SyncEstado_CmdApos DEFAULT 0,  -- ultimo seq de comando recebido
        AtualizadoEm          DATETIMEOFFSET(7) NOT NULL CONSTRAINT DF_SyncEstado_Atual DEFAULT SYSDATETIMEOFFSET(),
        CONSTRAINT PK_SyncEstado PRIMARY KEY CLUSTERED (Id),
        CONSTRAINT CK_SyncEstado_Unico CHECK (Id = 1)
    );
END;

IF NOT EXISTS (SELECT 1 FROM dbo.SyncEstado WHERE Id = 1)
    INSERT INTO dbo.SyncEstado (Id) VALUES (1);

-- Linhas que a nuvem pediu para reenviar no proximo lote (comando "reenviar", D6) e correcoes da conferencia (D10).
IF OBJECT_ID(N'dbo.SyncReenvios', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.SyncReenvios (
        Tabela   NVARCHAR(128)     NOT NULL,
        Chave    NVARCHAR(200)     NOT NULL,                 -- chave primaria serializada (valores separados por |)
        Motivo   NVARCHAR(100)     NULL,
        CriadoEm DATETIMEOFFSET(7) NOT NULL CONSTRAINT DF_SyncReenvios_Criado DEFAULT SYSDATETIMEOFFSET(),
        CONSTRAINT PK_SyncReenvios PRIMARY KEY CLUSTERED (Tabela, Chave)
    );
END;

-- Alertas (D11): um por motivo, com limite de um e-mail por hora.
IF OBJECT_ID(N'dbo.SyncAlertas', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.SyncAlertas (
        Motivo       NVARCHAR(100)     NOT NULL,
        CompanyId    NVARCHAR(40)      NOT NULL CONSTRAINT DF_SyncAlertas_Company DEFAULT N'',
        Detalhe      NVARCHAR(1000)    NULL,
        UltimoEnvio  DATETIMEOFFSET(7) NULL,                 -- ultimo e-mail enviado; nulo = pendente de envio
        UltimaEm     DATETIMEOFFSET(7) NOT NULL CONSTRAINT DF_SyncAlertas_Ultima DEFAULT SYSDATETIMEOFFSET(),
        Contagem     INT               NOT NULL CONSTRAINT DF_SyncAlertas_Cont DEFAULT 1,
        CONSTRAINT PK_SyncAlertas PRIMARY KEY CLUSTERED (Motivo, CompanyId)
    );
END;

-- Resultado de cada conferencia (D10).
IF OBJECT_ID(N'dbo.SyncConferencias', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.SyncConferencias (
        Id           BIGINT            IDENTITY (1, 1) NOT NULL,
        ExecutadaEm  DATETIMEOFFSET(7) NOT NULL CONSTRAINT DF_SyncConf_Exec DEFAULT SYSDATETIMEOFFSET(),
        Origem       NVARCHAR(20)      NOT NULL,             -- agendada | manual
        Resultado    NVARCHAR(20)      NOT NULL,             -- ok | divergente | erro
        Voltas       INT               NOT NULL CONSTRAINT DF_SyncConf_Voltas DEFAULT 0,
        DetalheJson  NVARCHAR(MAX)     NULL,
        CONSTRAINT PK_SyncConferencias PRIMARY KEY CLUSTERED (Id)
    );
END;

-- Progresso da carga inicial por tabela (D5): retoma de onde parou.
IF OBJECT_ID(N'dbo.SyncCarga', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.SyncCarga (
        Tabela       NVARCHAR(128)     NOT NULL,
        UltimaChave  NVARCHAR(400)     NULL,
        Linhas       BIGINT            NOT NULL CONSTRAINT DF_SyncCarga_Linhas DEFAULT 0,
        Concluida    BIT               NOT NULL CONSTRAINT DF_SyncCarga_Concl DEFAULT 0,
        AtualizadoEm DATETIMEOFFSET(7) NOT NULL CONSTRAINT DF_SyncCarga_Atual DEFAULT SYSDATETIMEOFFSET(),
        CONSTRAINT PK_SyncCarga PRIMARY KEY CLUSTERED (Tabela)
    );
END;

-- Comandos da nuvem ja aplicados (idempotencia: o mesmo seq entregue duas vezes nao reaplica, D6).
IF OBJECT_ID(N'dbo.SyncComandosAplicados', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.SyncComandosAplicados (
        Seq           BIGINT            NOT NULL,
        Tipo          NVARCHAR(20)      NOT NULL,            -- alterar | inserir | excluir | forcar | reenviar
        Tabela        NVARCHAR(128)     NOT NULL,
        Chave         NVARCHAR(200)     NOT NULL,
        Resultado     NVARCHAR(20)      NOT NULL,            -- aplicado | conflito | falhou
        ResultadoJson NVARCHAR(MAX)     NULL,
        AplicadoEm    DATETIMEOFFSET(7) NOT NULL CONSTRAINT DF_SyncCmdApl_Em DEFAULT SYSDATETIMEOFFSET(),
        CONSTRAINT PK_SyncComandosAplicados PRIMARY KEY CLUSTERED (Seq)
    );
END;
