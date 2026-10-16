/**
 * Arquivo: API/NETCORE/Repositories/HorusDatabaseInitializer.cs
 * Objetivo: executa a inicialização controlada do banco a partir dos scripts SQL do projeto.
 *
 * Versionamento estilo Flyway: cada script roda NO MÁXIMO UMA VEZ, controlado pela tabela
 * __MigrationHistory dentro de HorusPdv. Ao iniciar:
 *   1. Se o banco não existe, roda Resumo.sql para criá-lo (bootstrap).
 *   2. Cria __MigrationHistory se ainda não existir.
 *   3. Se o banco já existia mas a tabela de histórico acabou de ser criada (upgrade),
 *      registra todas as migrações como "já aplicadas" (baseline) — nada reexecuta.
 *   4. Caso contrário, executa apenas migrações pendentes na ordem declarada.
 */
using System.Text.RegularExpressions;
using Microsoft.Data.SqlClient;

namespace HORUSPDV_API.Repositories;

public static class HorusDatabaseInitializer
{
    private static readonly Regex BatchSeparator = new(
        @"^\s*GO\s*;?\s*$",
        RegexOptions.IgnoreCase | RegexOptions.Multiline | RegexOptions.Compiled);

    private static readonly Regex SqlComment = new(
        @"/\*.*?\*/|--[^\r\n]*",
        RegexOptions.Singleline | RegexOptions.Compiled);

    /// <summary>Cada migração tem uma chave única (nunca muda) e o caminho relativo do script.</summary>
    private sealed record MigrationEntry(string Key, string RelativePath);

    /// <summary>
    /// Lista ordenada de todas as migrações conhecidas.
    /// Para adicionar uma nova migração, basta acrescentar ao final.
    /// </summary>
    private static readonly MigrationEntry[] Migrations =
    [
        new("Resumo",                           Path.Combine("DataBase", "Resumo.sql")),
        new("01_migracao_valores",              Path.Combine("DataBase", "Migrations", "01_migracao_valores.sql")),
        new("02_estrutura_fiscal",              Path.Combine("DataBase", "Migrations", "02_estrutura_fiscal.sql")),
        new("03_multiplas_formas_pagamento",    Path.Combine("DataBase", "Migrations", "03_multiplas_formas_pagamento.sql")),
        new("04_serie_fiscal",                  Path.Combine("DataBase", "Migrations", "04_serie_fiscal.sql")),
        new("05_produtos_balanca",              Path.Combine("DataBase", "Migrations", "05_produtos_balanca.sql")),
        new("06_produtos_mercado_completo",     Path.Combine("DataBase", "Migrations", "06_produtos_mercado_completo.sql")),
        new("07_estoque_minimo",                Path.Combine("DataBase", "Migrations", "07_estoque_minimo.sql")),
        new("08_multitenant_seguranca",         Path.Combine("DataBase", "Migrations", "08_multitenant_seguranca.sql")),
        new("09_categorias_produto",            Path.Combine("DataBase", "Migrations", "09_categorias_produto.sql")),
        new("10_controle_validade",             Path.Combine("DataBase", "Migrations", "10_controle_validade.sql")),
        new("11_promocoes",                     Path.Combine("DataBase", "Migrations", "11_promocoes.sql")),
        new("12_fiado_conta_corrente",          Path.Combine("DataBase", "Migrations", "12_fiado_conta_corrente.sql")),
        new("13_modelo_produto_expandido",      Path.Combine("DataBase", "Migrations", "13_modelo_produto_expandido.sql")),
        new("14_aprovacao_empresas",            Path.Combine("DataBase", "Migrations", "14_aprovacao_empresas.sql")),
        new("15_nfe_modelo55",                  Path.Combine("DataBase", "Migrations", "15_nfe_modelo55.sql")),
        new("16_ordens_compra",                 Path.Combine("DataBase", "Migrations", "16_ordens_compra.sql")),
        new("17_ordens_compra_detalhes",        Path.Combine("DataBase", "Migrations", "17_ordens_compra_detalhes.sql")),
        new("18_coluna_lucro_produto",          Path.Combine("DataBase", "Migrations", "18_coluna_lucro_produto.sql")),
        new("19_idempotencia_eventos",          Path.Combine("DataBase", "Migrations", "19_idempotencia_eventos.sql")),
        new("20_indice_caixasessoes",           Path.Combine("DataBase", "Migrations", "20_indice_caixasessoes.sql")),
        new("21_lockout_persistente",           Path.Combine("DataBase", "Migrations", "21_lockout_persistente.sql")),
        new("22_cancelamento_nfce_caixa",       Path.Combine("DataBase", "Migrations", "22_cancelamento_nfce_caixa.sql")),
        new("23_devolucao_nfe_entrada",         Path.Combine("DataBase", "Migrations", "23_devolucao_nfe_entrada.sql")),
        new("24_serie_nfe",                     Path.Combine("DataBase", "Migrations", "24_serie_nfe.sql")),
        new("25_loja_gateway_config",           Path.Combine("DataBase", "Migrations", "25_loja_gateway_config.sql")),
        new("26_multi_caixa_operador",          Path.Combine("DataBase", "Migrations", "26_multi_caixa_operador.sql")),
        new("27_empresa_tema",                  Path.Combine("DataBase", "Migrations", "27_empresa_tema.sql")),
        new("28_atualizacao_produtos_mercado",  Path.Combine("DataBase", "Migrations", "28_atualizacao_produtos_mercado.sql")),
        new("29_fornecedores_cnpj_flexivel",    Path.Combine("DataBase", "Migrations", "29_fornecedores_cnpj_flexivel.sql")),
        new("30_regras_emissao_nfce",           Path.Combine("DataBase", "Migrations", "30_regras_emissao_nfce.sql")),
        new("31_mapeamento_produto_fornecedor", Path.Combine("DataBase", "Migrations", "31_mapeamento_produto_fornecedor.sql")),
        new("32_cancelamento_venda_auditoria",  Path.Combine("DataBase", "Migrations", "32_cancelamento_venda_auditoria.sql")),
        new("33_atualizacao_precos_bebidas",    Path.Combine("DataBase", "Migrations", "33_atualizacao_precos_bebidas.sql")),
        new("34_lotes_validade",                Path.Combine("DataBase", "Migrations", "34_lotes_validade.sql")),
        new("35_lotes_baixa_fefo",              Path.Combine("DataBase", "Migrations", "35_lotes_baixa_fefo.sql")),
        new("36_autorizacao_preco",             Path.Combine("DataBase", "Migrations", "36_autorizacao_preco.sql")),
        new("37_regras_emissao_sempre",         Path.Combine("DataBase", "Migrations", "37_regras_emissao_sempre.sql")),
        new("38_treinamento",                   Path.Combine("DataBase", "Migrations", "38_treinamento.sql")),
        new("39_gateway_tokens",                Path.Combine("DataBase", "Migrations", "39_gateway_tokens.sql")),
        new("40_usuario_perfis_adicionais",     Path.Combine("DataBase", "Migrations", "40_usuario_perfis_adicionais.sql")),
        new("41_clientes_documento_opcional",   Path.Combine("DataBase", "Migrations", "41_clientes_documento_opcional.sql")),
        new("42_notas_entrada_arquivo",         Path.Combine("DataBase", "Migrations", "42_notas_entrada_arquivo.sql")),
        new("43_fiscal_ia",                     Path.Combine("DataBase", "Migrations", "43_fiscal_ia.sql")),
        new("44_fiscal_qrcode",                 Path.Combine("DataBase", "Migrations", "44_fiscal_qrcode.sql")),
        new("45_fiscal_ia_fila",                Path.Combine("DataBase", "Migrations", "45_fiscal_ia_fila.sql")),
        new("46_custo_venda",                   Path.Combine("DataBase", "Migrations", "46_custo_venda.sql")),
        new("47_entrada_manual",                Path.Combine("DataBase", "Migrations", "47_entrada_manual.sql")),
        new("48_integracao_tokens",             Path.Combine("DataBase", "Migrations", "48_integracao_tokens.sql")),
        new("49_pagamento_dados_ausentes",      Path.Combine("DataBase", "Migrations", "49_pagamento_dados_ausentes.sql")),
        new("50_estorno_nota_entrada",          Path.Combine("DataBase", "Migrations", "50_estorno_nota_entrada.sql")),
    ];

    public static async Task InitializeAsync(IServiceProvider services)
    {
        using var scope = services.CreateScope();
        var connection = scope.ServiceProvider.GetRequiredService<Connection>();
        var logger = scope.ServiceProvider.GetRequiredService<ILoggerFactory>()
            .CreateLogger("HorusDatabaseInitializer");

        await using var sqlConnection = await connection.OpenConnectionAsync(database: "master");

        // ---------------------------------------------------------------
        // 1. Bootstrap: garante que o banco exista para criar __MigrationHistory.
        //    Resumo.sql cria o banco + schema base.
        // ---------------------------------------------------------------
        bool isNewDatabase = !await DatabaseExistsAsync(sqlConnection);
        if (isNewDatabase)
        {
            logger.LogInformation("Banco HorusPdv não encontrado. Criando a partir de Resumo.sql...");
            await RunScriptFileAsync(sqlConnection, logger, Path.Combine("DataBase", "Resumo.sql"));
        }

        // ---------------------------------------------------------------
        // 2. Cria tabela de versionamento (estilo Flyway).
        // ---------------------------------------------------------------
        bool historyJustCreated = await EnsureMigrationHistoryAsync(sqlConnection);

        // ---------------------------------------------------------------
        // 3. Baseline: banco já existia mas nunca teve __MigrationHistory.
        //    Registra tudo como já aplicado — nenhum script reexecuta.
        // ---------------------------------------------------------------
        if (!isNewDatabase && historyJustCreated)
        {
            logger.LogInformation(
                "Banco existente detectado sem __MigrationHistory. Registrando baseline de {Count} migrações.",
                Migrations.Length);
            foreach (var m in Migrations)
                await RecordMigrationAsync(sqlConnection, m.Key);
            return;
        }

        // Se DB é novo, Resumo.sql já rodou acima — registrar.
        if (isNewDatabase)
        {
            await RecordMigrationAsync(sqlConnection, "Resumo");
        }

        // ---------------------------------------------------------------
        // 4. Executa somente migrações pendentes, na ordem declarada.
        // ---------------------------------------------------------------
        var applied = await GetAppliedMigrationsAsync(sqlConnection);

        foreach (var migration in Migrations)
        {
            if (applied.Contains(migration.Key)) continue;

            // Migration 01 tem condição especial: só é necessária se a coluna ainda for texto.
            if (migration.Key == "01_migracao_valores")
            {
                if (!await IsColumnStillTextAsync(sqlConnection, "Vendas", "TotalAmount"))
                {
                    await RecordMigrationAsync(sqlConnection, migration.Key);
                    logger.LogInformation(
                        "Migration {Key} desnecessária (coluna já convertida). Registrada sem executar.",
                        migration.Key);
                    continue;
                }

                logger.LogWarning(
                    "Vendas.TotalAmount ainda é NVARCHAR. Aplicando 01_migracao_valores.sql. " +
                    "Confirme que há backup do banco antes desta execução.");
            }

            var relativePath = ResolveMigrationPath(migration);
            await RunScriptFileAsync(sqlConnection, logger, relativePath);
            await RecordMigrationAsync(sqlConnection, migration.Key);
            logger.LogInformation("Migration {Key} aplicada com sucesso.", migration.Key);
        }
    }

    // ===================================================================
    //  Controle de versionamento
    // ===================================================================

    private static async Task<bool> DatabaseExistsAsync(SqlConnection sqlConnection)
    {
        await using var cmd = new SqlCommand(
            "SELECT CASE WHEN DB_ID('HorusPdv') IS NOT NULL THEN 1 ELSE 0 END;", sqlConnection);
        return (int)(await cmd.ExecuteScalarAsync())! == 1;
    }

    /// <summary>Cria __MigrationHistory se não existir. Retorna true se acabou de criar.</summary>
    private static async Task<bool> EnsureMigrationHistoryAsync(SqlConnection sqlConnection)
    {
        const string sql = """
            USE HorusPdv;
            IF OBJECT_ID('dbo.__MigrationHistory', 'U') IS NULL
            BEGIN
                CREATE TABLE dbo.__MigrationHistory (
                    MigrationKey NVARCHAR(200) NOT NULL PRIMARY KEY,
                    AppliedAt    DATETIME2     NOT NULL DEFAULT SYSUTCDATETIME()
                );
                SELECT 1;
            END
            ELSE
                SELECT 0;
            """;
        await using var cmd = new SqlCommand(sql, sqlConnection);
        var result = await cmd.ExecuteScalarAsync();
        return result is int i && i == 1;
    }

    private static async Task<HashSet<string>> GetAppliedMigrationsAsync(SqlConnection sqlConnection)
    {
        await using var cmd = new SqlCommand(
            "SELECT MigrationKey FROM HorusPdv.dbo.__MigrationHistory;", sqlConnection);
        await using var reader = await cmd.ExecuteReaderAsync();
        var keys = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        while (await reader.ReadAsync())
            keys.Add(reader.GetString(0));
        return keys;
    }

    private static async Task RecordMigrationAsync(SqlConnection sqlConnection, string key)
    {
        await using var cmd = new SqlCommand(
            "INSERT INTO HorusPdv.dbo.__MigrationHistory (MigrationKey) VALUES (@Key);", sqlConnection);
        cmd.Parameters.AddWithValue("@Key", key);
        await cmd.ExecuteNonQueryAsync();
    }

    /// <summary>
    /// Resolve o caminho do script para a migração 13 (que pode existir como 12 em deploys antigos).
    /// Para as demais, retorna o caminho declarado.
    /// </summary>
    private static string ResolveMigrationPath(MigrationEntry migration)
    {
        if (migration.Key != "13_modelo_produto_expandido")
            return migration.RelativePath;

        if (ScriptFileExists(migration.RelativePath))
            return migration.RelativePath;

        return Path.Combine("DataBase", "Migrations", "12_modelo_produto_expandido.sql");
    }

    private static bool ScriptFileExists(string relativePath)
    {
        var path = Path.Combine(AppContext.BaseDirectory, relativePath);
        if (File.Exists(path)) return true;
        path = Path.Combine(Directory.GetCurrentDirectory(), relativePath);
        return File.Exists(path);
    }

    // ===================================================================
    //  Execução de scripts SQL (inalterado)
    // ===================================================================

    private static async Task RunScriptFileAsync(SqlConnection sqlConnection, ILogger logger, string relativePath)
    {
        var scriptPath = Path.Combine(AppContext.BaseDirectory, relativePath);
        if (!File.Exists(scriptPath))
        {
            scriptPath = Path.Combine(Directory.GetCurrentDirectory(), relativePath);
        }

        if (!File.Exists(scriptPath))
        {
            logger.LogWarning("Script SQL não encontrado em {RelativePath}. Etapa de inicialização ignorada.", relativePath);
            return;
        }

        var script = await File.ReadAllTextAsync(scriptPath);
        var batches = BatchSeparator.Split(script)
            .Select(batch => batch.Trim())
            .Where(batch => !string.IsNullOrWhiteSpace(batch))
            .ToList();

        SqlTransaction? transaction = null;
        try
        {
            foreach (var batch in batches)
            {
                var normalized = SqlComment.Replace(batch, string.Empty).Trim().TrimEnd(';');

                if (string.Equals(normalized, "BEGIN TRANSACTION", StringComparison.OrdinalIgnoreCase)
                    || string.Equals(normalized, "BEGIN TRAN", StringComparison.OrdinalIgnoreCase))
                {
                    transaction = (SqlTransaction)await sqlConnection.BeginTransactionAsync();
                    continue;
                }

                if (string.Equals(normalized, "COMMIT TRANSACTION", StringComparison.OrdinalIgnoreCase)
                    || string.Equals(normalized, "COMMIT TRAN", StringComparison.OrdinalIgnoreCase)
                    || string.Equals(normalized, "COMMIT", StringComparison.OrdinalIgnoreCase))
                {
                    if (transaction is not null)
                    {
                        await transaction.CommitAsync();
                        await transaction.DisposeAsync();
                        transaction = null;
                    }
                    continue;
                }

                await using var command = new SqlCommand(batch, sqlConnection, transaction)
                {
                    CommandTimeout = 180
                };
                await command.ExecuteNonQueryAsync();
            }
        }
        catch
        {
            if (transaction is not null)
            {
                await transaction.RollbackAsync();
                await transaction.DisposeAsync();
            }
            throw;
        }

        logger.LogInformation("Script SQL {RelativePath} executado com sucesso.", relativePath);
    }

    private static async Task<bool> IsColumnStillTextAsync(SqlConnection sqlConnection, string table, string column)
    {
        await using var command = new SqlCommand(
            "SELECT TYPE_NAME(system_type_id) FROM HorusPdv.sys.columns WHERE object_id = OBJECT_ID('HorusPdv.dbo.' + @Table) AND name = @Column;",
            sqlConnection);
        command.Parameters.AddWithValue("@Table", table);
        command.Parameters.AddWithValue("@Column", column);
        var typeName = await command.ExecuteScalarAsync() as string;
        return string.Equals(typeName, "nvarchar", StringComparison.OrdinalIgnoreCase);
    }
}
