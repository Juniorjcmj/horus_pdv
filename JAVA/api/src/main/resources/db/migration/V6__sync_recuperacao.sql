-- Recuperacao do servidor da loja depois de uma restauracao de backup (change api-java-modo-loja, tarefa 7.4).
-- Somente da API Java. O prefixo Sync fica fora do catalogo de sincronizacao.

-- Estado unico da recuperacao (sempre a linha Id = 1). Estado: nenhuma | pendente | executando | concluida.
-- Enquanto estiver pendente ou executando, o servidor nao aceita vendas, o envio e a conferencia ficam parados e a
-- situacao mostra "recuperando". Fase: preparando | puxando | alinhando | sequencias (retoma de onde parou).
IF OBJECT_ID(N'dbo.SyncRecuperacao', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.SyncRecuperacao (
        Id                 INT               NOT NULL,
        Estado             NVARCHAR(20)      NOT NULL CONSTRAINT DF_SyncRecup_Estado DEFAULT N'nenhuma',
        Fase               NVARCHAR(20)      NULL,
        Motivo             NVARCHAR(200)     NULL,
        SolicitadaEm       DATETIMEOFFSET(7) NULL,
        IniciadaEm         DATETIMEOFFSET(7) NULL,
        ConcluidaEm        DATETIMEOFFSET(7) NULL,
        Tentativas         INT               NOT NULL CONSTRAINT DF_SyncRecup_Tent DEFAULT 0,
        UltimoErro         NVARCHAR(500)     NULL,
        VersaoAntiga       BIGINT            NULL,       -- SyncEstado.VersaoDe do banco restaurado (o que ja tinha subido)
        CargaVersao        BIGINT            NULL,       -- versao do retrato da nuvem em que o delta comeca
        ReenviosEnfileirados BIT             NOT NULL CONSTRAINT DF_SyncRecup_Reenv DEFAULT 0,
        RetratoCompleto    BIT               NOT NULL CONSTRAINT DF_SyncRecup_Retrato DEFAULT 0,  -- Change Tracking inutilizavel: envia o retrato completo
        TabelaAtual        NVARCHAR(128)     NULL,
        Linhas             BIGINT            NOT NULL CONSTRAINT DF_SyncRecup_Linhas DEFAULT 0,
        AtualizadoEm       DATETIMEOFFSET(7) NOT NULL CONSTRAINT DF_SyncRecup_Atual DEFAULT SYSDATETIMEOFFSET(),
        CONSTRAINT PK_SyncRecuperacao PRIMARY KEY CLUSTERED (Id),
        CONSTRAINT CK_SyncRecuperacao_Unico CHECK (Id = 1)
    );
END;

IF NOT EXISTS (SELECT 1 FROM dbo.SyncRecuperacao WHERE Id = 1)
    INSERT INTO dbo.SyncRecuperacao (Id) VALUES (1);

-- Progresso da puxada da nuvem por tabela (como SyncCarga, mas da recuperacao).
IF OBJECT_ID(N'dbo.SyncRecuperacaoTabelas', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.SyncRecuperacaoTabelas (
        Tabela       NVARCHAR(128)     NOT NULL,
        UltimaChave  NVARCHAR(400)     NULL,
        Linhas       BIGINT            NOT NULL CONSTRAINT DF_SyncRecupTab_Linhas DEFAULT 0,
        Concluida    BIT               NOT NULL CONSTRAINT DF_SyncRecupTab_Concl DEFAULT 0,
        AtualizadoEm DATETIMEOFFSET(7) NOT NULL CONSTRAINT DF_SyncRecupTab_Atual DEFAULT SYSDATETIMEOFFSET(),
        CONSTRAINT PK_SyncRecuperacaoTabelas PRIMARY KEY CLUSTERED (Tabela)
    );
END;
