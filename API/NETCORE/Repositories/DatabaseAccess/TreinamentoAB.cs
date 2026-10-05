/**
 * Arquivo: API/NETCORE/Repositories/DatabaseAccess/TreinamentoAB.cs
 * Objetivo: acesso a dados da página de aprendizado (seções e vídeos de treinamento). Conteúdo global da
 *           plataforma: não há CompanyId, todas as empresas veem o mesmo material.
 * Entradas esperadas: recebe conexão configurada; a autorização de edição é feita no controlador.
 */
using Microsoft.Data.SqlClient;

namespace HORUSPDV_API.Repositories.DatabaseAccess;

public class TreinamentoAB(Connection connection)
{
    public async Task<List<TreinamentoSecaoRow>> ListarAsync(CancellationToken cancellationToken = default)
    {
        const string sql = """
            SELECT Id, Nome, Descricao, Ordem FROM TreinamentoSecoes ORDER BY Ordem, Nome;
            SELECT Id, SecaoId, Titulo, Descricao, Instrucoes, YoutubeId, Ordem
            FROM TreinamentoVideos ORDER BY Ordem, CriadoEm;
            """;

        await using var db = await connection.OpenConnectionAsync(cancellationToken);
        await using var command = new SqlCommand(sql, db);
        await using var reader = await command.ExecuteReaderAsync(cancellationToken);

        var secoes = new List<TreinamentoSecaoRow>();
        while (await reader.ReadAsync(cancellationToken))
        {
            secoes.Add(new TreinamentoSecaoRow(
                reader.GetString(0), reader.GetString(1), ReadString(reader, 2), reader.GetInt32(3), []));
        }

        await reader.NextResultAsync(cancellationToken);
        while (await reader.ReadAsync(cancellationToken))
        {
            var secaoId = reader.GetString(1);
            secoes.FirstOrDefault(item => item.Id == secaoId)?.Videos.Add(new TreinamentoVideoRow(
                reader.GetString(0), secaoId, reader.GetString(2), ReadString(reader, 3),
                ReadString(reader, 4), reader.GetString(5), reader.GetInt32(6)));
        }

        return secoes;
    }

    public async Task<string> SalvarSecaoAsync(string? id, string nome, string? descricao, int ordem, CancellationToken cancellationToken = default)
    {
        await using var db = await connection.OpenConnectionAsync(cancellationToken);
        if (string.IsNullOrWhiteSpace(id))
        {
            id = $"trs-{Guid.NewGuid():N}";
            await using var insert = new SqlCommand(
                "INSERT INTO TreinamentoSecoes (Id, Nome, Descricao, Ordem) VALUES (@Id, @Nome, @Descricao, @Ordem);", db);
            Bind(insert, id, nome, descricao, ordem);
            await insert.ExecuteNonQueryAsync(cancellationToken);
            return id;
        }

        await using var update = new SqlCommand(
            "UPDATE TreinamentoSecoes SET Nome = @Nome, Descricao = @Descricao, Ordem = @Ordem WHERE Id = @Id;", db);
        Bind(update, id, nome, descricao, ordem);
        var rows = await update.ExecuteNonQueryAsync(cancellationToken);
        return rows > 0 ? id : string.Empty;
    }

    public async Task<bool> ExcluirSecaoAsync(string id, CancellationToken cancellationToken = default)
    {
        await using var db = await connection.OpenConnectionAsync(cancellationToken);
        await using var command = new SqlCommand("DELETE FROM TreinamentoSecoes WHERE Id = @Id;", db);
        command.Parameters.AddWithValue("@Id", id);
        return await command.ExecuteNonQueryAsync(cancellationToken) > 0;
    }

    public async Task<string> SalvarVideoAsync(
        string? id, string secaoId, string titulo, string? descricao, string? instrucoes, string youtubeId, int ordem,
        CancellationToken cancellationToken = default)
    {
        await using var db = await connection.OpenConnectionAsync(cancellationToken);
        var novo = string.IsNullOrWhiteSpace(id);
        id = novo ? $"trv-{Guid.NewGuid():N}" : id!;

        var sql = novo
            ? """
              INSERT INTO TreinamentoVideos (Id, SecaoId, Titulo, Descricao, Instrucoes, YoutubeId, Ordem)
              VALUES (@Id, @SecaoId, @Titulo, @Descricao, @Instrucoes, @YoutubeId, @Ordem);
              """
            : """
              UPDATE TreinamentoVideos
              SET SecaoId = @SecaoId, Titulo = @Titulo, Descricao = @Descricao, Instrucoes = @Instrucoes,
                  YoutubeId = @YoutubeId, Ordem = @Ordem
              WHERE Id = @Id;
              """;

        await using var command = new SqlCommand(sql, db);
        command.Parameters.AddWithValue("@Id", id);
        command.Parameters.AddWithValue("@SecaoId", secaoId);
        command.Parameters.AddWithValue("@Titulo", titulo);
        command.Parameters.AddWithValue("@Descricao", NullIfBlank(descricao));
        command.Parameters.AddWithValue("@Instrucoes", NullIfBlank(instrucoes));
        command.Parameters.AddWithValue("@YoutubeId", youtubeId);
        command.Parameters.AddWithValue("@Ordem", ordem);
        var rows = await command.ExecuteNonQueryAsync(cancellationToken);
        return rows > 0 ? id : string.Empty;
    }

    public async Task<bool> ExcluirVideoAsync(string id, CancellationToken cancellationToken = default)
    {
        await using var db = await connection.OpenConnectionAsync(cancellationToken);
        await using var command = new SqlCommand("DELETE FROM TreinamentoVideos WHERE Id = @Id;", db);
        command.Parameters.AddWithValue("@Id", id);
        return await command.ExecuteNonQueryAsync(cancellationToken) > 0;
    }

    private static void Bind(SqlCommand command, string id, string nome, string? descricao, int ordem)
    {
        command.Parameters.AddWithValue("@Id", id);
        command.Parameters.AddWithValue("@Nome", nome);
        command.Parameters.AddWithValue("@Descricao", NullIfBlank(descricao));
        command.Parameters.AddWithValue("@Ordem", ordem);
    }

    private static object NullIfBlank(string? value)
        => string.IsNullOrWhiteSpace(value) ? DBNull.Value : value.Trim();

    private static string ReadString(SqlDataReader reader, int ordinal)
        => reader.IsDBNull(ordinal) ? string.Empty : reader.GetString(ordinal);
}

public record TreinamentoSecaoRow(string Id, string Nome, string Descricao, int Ordem, List<TreinamentoVideoRow> Videos);

public record TreinamentoVideoRow(string Id, string SecaoId, string Titulo, string Descricao, string Instrucoes, string YoutubeId, int Ordem);
