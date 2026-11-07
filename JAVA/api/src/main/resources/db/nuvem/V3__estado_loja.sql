-- Relato de estado da loja (change api-java-monitoramento, M4 e M5).
-- Guarda so o ULTIMO relato por empresa, enviado a cada minuto pelo servidor da loja (POST /api/loja-sync/estado).
-- Tabela so da API Java na nuvem: nao entra no Change Tracking nem no catalogo de sincronizacao.
IF OBJECT_ID(N'dbo.SyncEstadoLoja', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.SyncEstadoLoja (
        CompanyId      NVARCHAR(40)      NOT NULL,
        RecebidoEm     DATETIMEOFFSET(7) NOT NULL,
        GeradoEm       DATETIMEOFFSET(7) NULL,
        AtrasoSegundos BIGINT            NULL,
        Pausada        BIT               NULL,
        UltimoBackupEm DATETIMEOFFSET(7) NULL,   -- hora do ULTIMO BACKUP BEM-SUCEDIDO (ver backup.ps1 / comum.ps1)
        BackupSucesso  BIT               NULL,   -- resultado da ULTIMA TENTATIVA de backup
        BackupErro     NVARCHAR(500)     NULL,
        NfcePendentes  INT               NULL,
        VersaoPrograma NVARCHAR(40)      NULL,
        CONSTRAINT PK_SyncEstadoLoja PRIMARY KEY CLUSTERED (CompanyId)
    );
END;
