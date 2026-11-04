-- Rollback do receptor da nuvem (change api-java-modo-loja, D12): desliga o Change Tracking e o isolamento snapshot.
-- MANTEM as tabelas Sync* (historico, comandos, quarentena, espelho) e nao toca em nenhum dado da API .NET.
--
-- QUANDO USAR: emergencia (custo de escrita/tempdb inaceitavel) ou desativacao do modo loja. Antes, PARE o servico
-- pdv-api-sync (senao o verificador da subida religa o tracking). Idempotente: pode rodar de novo.
-- Efeito: as lojas deixam de receber/enviar ate a religacao; ao religar (subir pdv-api-sync), a versao minima valida do
-- tracking avanca e cada loja faz a ressincronizacao completa (D5) sozinha.
--
-- COMO RODAR (banco HorusPdv): sqlcmd -S <servidor> -d HorusPdv -i desligar.sql  (ou colar no SSMS/Azure Data Studio).
-- Equivale a ChangeTrackingAdmin.desligarTudo(). Nao roda dentro de transacao (ALTER DATABASE).

SET NOCOUNT ON;
DECLARE @sql NVARCHAR(MAX);
DECLARE @banco SYSNAME = DB_NAME();

-- 1. Tabelas
DECLARE @t SYSNAME;
DECLARE tabelas CURSOR LOCAL FAST_FORWARD FOR
    SELECT s.name + N'.' + t.name
    FROM sys.change_tracking_tables ct
    JOIN sys.tables t ON t.object_id = ct.object_id
    JOIN sys.schemas s ON s.schema_id = t.schema_id;
OPEN tabelas;
FETCH NEXT FROM tabelas INTO @t;
WHILE @@FETCH_STATUS = 0
BEGIN
    SET @sql = N'ALTER TABLE ' + @t + N' DISABLE CHANGE_TRACKING';
    EXEC (@sql);
    FETCH NEXT FROM tabelas INTO @t;
END;
CLOSE tabelas;
DEALLOCATE tabelas;

-- 2. Banco
IF EXISTS (SELECT 1 FROM sys.change_tracking_databases WHERE database_id = DB_ID())
BEGIN
    SET @sql = N'ALTER DATABASE ' + QUOTENAME(@banco) + N' SET CHANGE_TRACKING = OFF';
    EXEC (@sql);
END;

-- 3. Snapshot
IF EXISTS (SELECT 1 FROM sys.databases WHERE database_id = DB_ID() AND snapshot_isolation_state <> 0)
BEGIN
    SET @sql = N'ALTER DATABASE ' + QUOTENAME(@banco) + N' SET ALLOW_SNAPSHOT_ISOLATION OFF';
    EXEC (@sql);
END;

SELECT
    (SELECT COUNT(*) FROM sys.change_tracking_tables)                                   AS TabelasComTracking,
    (SELECT COUNT(*) FROM sys.change_tracking_databases WHERE database_id = DB_ID())    AS BancoComTracking,
    (SELECT snapshot_isolation_state_desc FROM sys.databases WHERE database_id = DB_ID()) AS Snapshot;
