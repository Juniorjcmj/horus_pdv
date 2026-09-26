/**
 * Arquivo: API/NETCORE/Repositories/HorusDatabaseInitializer.cs
 * Objetivo: executa a inicialização controlada do banco a partir dos scripts SQL do projeto.
 * Entradas esperadas: espera provedor de serviços configurado e SQL Server disponível no ambiente.
 *
 * Roda, nesta ordem, na mesma conexão (para preservar o contexto do `USE HorusPdv` entre lotes):
 *   1. DataBase/Resumo.sql               — sempre, idempotente (converge o schema a cada boot).
 *   2. DataBase/Migrations/01_migracao_valores.sql — só quando Vendas.TotalAmount ainda é NVARCHAR
 *      (script de conversão único, não idempotente por si só — a idempotência é decidida aqui).
 *   3. DataBase/Migrations/02_estrutura_fiscal.sql — sempre, idempotente (campos fiscais + filas).
 *
 * Antes do primeiro deploy que aplica o item 2 em produção, faça backup do banco: a conversão
 * aborta com THROW se algum valor existente não for parseável (ver script), mas é irreversível
 * sem backup em caso de erro humano no meio do caminho.
 */
using System.Text.RegularExpressions;
using Microsoft.Data.SqlClient;

namespace HORUSPDV_API.Repositories;

public static class HorusDatabaseInitializer
{
    private static readonly Regex BatchSeparator = new(
        @"^\s*GO\s*;?\s*$",
        RegexOptions.IgnoreCase | RegexOptions.Multiline | RegexOptions.Compiled);

    // Comentário de bloco (/* ... */, non-greedy, multilinha) ou de linha (-- até o fim da linha).
    private static readonly Regex SqlComment = new(
        @"/\*.*?\*/|--[^\r\n]*",
        RegexOptions.Singleline | RegexOptions.Compiled);

    public static async Task InitializeAsync(IServiceProvider services)
    {
        using var scope = services.CreateScope();
        var connection = scope.ServiceProvider.GetRequiredService<Connection>();
        var logger = scope.ServiceProvider.GetRequiredService<ILoggerFactory>()
            .CreateLogger("HorusDatabaseInitializer");

        await using var sqlConnection = await connection.OpenConnectionAsync(database: "master");

        await RunScriptFileAsync(sqlConnection, logger, Path.Combine("DataBase", "Resumo.sql"));

        if (await IsColumnStillTextAsync(sqlConnection, "Vendas", "TotalAmount"))
        {
            logger.LogWarning(
                "Vendas.TotalAmount ainda esta em NVARCHAR. Aplicando " +
                "01_migracao_valores.sql para converter valores e quantidade para DECIMAL " +
                "(necessario para NFC-e com quantidade fracionada). Confirme que ha backup do " +
                "banco antes desta execucao.");
            await RunScriptFileAsync(
                sqlConnection, logger, Path.Combine("DataBase", "Migrations", "01_migracao_valores.sql"));
        }

        await RunScriptFileAsync(
            sqlConnection, logger, Path.Combine("DataBase", "Migrations", "02_estrutura_fiscal.sql"));

        await RunScriptFileAsync(
            sqlConnection, logger, Path.Combine("DataBase", "Migrations", "03_multiplas_formas_pagamento.sql"));

        await RunScriptFileAsync(
            sqlConnection, logger, Path.Combine("DataBase", "Migrations", "04_serie_fiscal.sql"));

        await RunScriptFileAsync(
            sqlConnection, logger, Path.Combine("DataBase", "Migrations", "05_produtos_balanca.sql"));

        await RunScriptFileAsync(
            sqlConnection, logger, Path.Combine("DataBase", "Migrations", "06_produtos_mercado_completo.sql"));

        await RunScriptFileAsync(
            sqlConnection, logger, Path.Combine("DataBase", "Migrations", "07_estoque_minimo.sql"));

        await RunScriptFileAsync(
            sqlConnection, logger, Path.Combine("DataBase", "Migrations", "08_multitenant_seguranca.sql"));

        await RunScriptFileAsync(
            sqlConnection, logger, Path.Combine("DataBase", "Migrations", "09_categorias_produto.sql"));

        await RunScriptFileAsync(
            sqlConnection, logger, Path.Combine("DataBase", "Migrations", "10_controle_validade.sql"));

        await RunScriptFileAsync(
            sqlConnection, logger, Path.Combine("DataBase", "Migrations", "11_promocoes.sql"));

        await RunScriptFileAsync(
            sqlConnection, logger, Path.Combine("DataBase", "Migrations", "12_fiado_conta_corrente.sql"));

        var migrationProdutoExpandido = File.Exists(Path.Combine(AppContext.BaseDirectory, "DataBase", "Migrations", "13_modelo_produto_expandido.sql"))
            || File.Exists(Path.Combine(Directory.GetCurrentDirectory(), "DataBase", "Migrations", "13_modelo_produto_expandido.sql"))
            ? "13_modelo_produto_expandido.sql"
            : "12_modelo_produto_expandido.sql";

        await RunScriptFileAsync(
            sqlConnection, logger, Path.Combine("DataBase", "Migrations", migrationProdutoExpandido));

        await RunScriptFileAsync(
            sqlConnection, logger, Path.Combine("DataBase", "Migrations", "14_aprovacao_empresas.sql"));

        await RunScriptFileAsync(
            sqlConnection, logger, Path.Combine("DataBase", "Migrations", "15_nfe_modelo55.sql"));

        await RunScriptFileAsync(
            sqlConnection, logger, Path.Combine("DataBase", "Migrations", "16_ordens_compra.sql"));

        await RunScriptFileAsync(
            sqlConnection, logger, Path.Combine("DataBase", "Migrations", "17_ordens_compra_detalhes.sql"));

        await RunScriptFileAsync(
            sqlConnection, logger, Path.Combine("DataBase", "Migrations", "18_coluna_lucro_produto.sql"));

        await RunScriptFileAsync(
            sqlConnection, logger, Path.Combine("DataBase", "Migrations", "19_idempotencia_eventos.sql"));

        await RunScriptFileAsync(
            sqlConnection, logger, Path.Combine("DataBase", "Migrations", "20_indice_caixasessoes.sql"));

        await RunScriptFileAsync(
            sqlConnection, logger, Path.Combine("DataBase", "Migrations", "21_lockout_persistente.sql"));

        await RunScriptFileAsync(
            sqlConnection, logger, Path.Combine("DataBase", "Migrations", "22_cancelamento_nfce_caixa.sql"));

        await RunScriptFileAsync(
            sqlConnection, logger, Path.Combine("DataBase", "Migrations", "23_devolucao_nfe_entrada.sql"));
    }

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

        // BEGIN/COMMIT TRANSACTION em T-SQL não pode ficar aberto entre dois lotes (GO)
        // separados nesta conexão: com MultipleActiveResultSets=True na connection string,
        // o SQL Server recusa (erro 3997, "A transaction that was started in a MARS batch
        // is still active at the end of the batch") porque cada ExecuteNonQueryAsync aqui é
        // um lote isolado do ponto de vista do MARS. Em vez de enviar o texto BEGIN/COMMIT
        // TRANSACTION como SQL, controla-se a mesma transação pelo SqlTransaction do ADO.NET,
        // que sabe manter o estado entre vários comandos na mesma conexão.
        SqlTransaction? transaction = null;
        try
        {
            foreach (var batch in batches)
            {
                // Remove comentários de bloco e de linha antes de checar se o lote inteiro
                // é só um BEGIN/COMMIT TRANSACTION — os scripts costumam ter um bloco de
                // comentário explicativo logo antes desses comandos, no mesmo lote (GO).
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

    /// <summary>
    /// Decide, em C#, se a migração de valores (não idempotente por si só) ainda precisa
    /// rodar — evita ter que expressar essa checagem em T-SQL dentro do próprio script.
    /// </summary>
    private static async Task<bool> IsColumnStillTextAsync(SqlConnection sqlConnection, string table, string column)
    {
        await using var command = new SqlCommand(
            "SELECT TYPE_NAME(system_type_id) FROM sys.columns WHERE object_id = OBJECT_ID(@Table) AND name = @Column;",
            sqlConnection);
        command.Parameters.AddWithValue("@Table", table);
        command.Parameters.AddWithValue("@Column", column);
        var typeName = await command.ExecuteScalarAsync() as string;
        return string.Equals(typeName, "nvarchar", StringComparison.OrdinalIgnoreCase);
    }
}
