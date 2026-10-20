using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using HORUSPDV_API.Models.Requests;
using HORUSPDV_API.Repositories;
using HORUSPDV_API.Services.Shared;
using Microsoft.Data.SqlClient;

namespace HORUSPDV_API.Services.Produtos;

public sealed record NotaEntradaResumo(string Id, string? ChaveAcesso, int Modelo, string NumeroNota,
    string Serie, string FornecedorNome, string FornecedorCnpj, string? DataEmissao, decimal? ValorNota,
    decimal ValorEntrada, int QuantidadeItens, string Origem, bool TemXml, DateTime CriadaEm, string UsuarioNome,
    bool TemComprovante = false, string? ComprovanteNome = null,
    bool Estornada = false, DateTime? EstornadaEm = null, string? EstornadaPorUsuarioNome = null, string? EstornoJustificativa = null);
public sealed record NotaEntradaPagina(List<NotaEntradaResumo> Notas, int Total, int Pagina, int TamanhoPagina);
public sealed record NotaEntradaDetalhe(NotaEntradaResumo Nota, JsonElement Entrada);
public sealed record NotaEntradaDocumento(string Identidade, string? Chave, int Modelo, string Numero,
    string Serie, string? Emissao, decimal? Total, string Origem, byte[]? Xml);

public class NotaEntradaArquivoService(Connection connection)
{
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web);
    public NotaEntradaDocumento Preparar(NfeImportConfirmRequest request)
    {
        var documento = request.Documento;
        byte[]? xml = null;
        NfeParsedDocument? parsed = null;
        if (!string.IsNullOrWhiteSpace(documento?.XmlBase64))
        {
            if (documento.XmlBase64.Length > 14_000_000) throw new InvalidOperationException("O XML deve ter no máximo 10 MB.");
            try { xml = Convert.FromBase64String(documento.XmlBase64); }
            catch (FormatException) { throw new InvalidOperationException("Conteúdo do XML inválido. Selecione o arquivo novamente."); }
            parsed = NfeXmlParser.Parse(xml);
            if (parsed.Emitente.Cnpj != Digitos(request.Fornecedor.Cnpj))
                throw new InvalidOperationException("O fornecedor da entrada deve ser o emitente do XML.");
        }
        var chave = parsed?.ChaveAcesso ?? documento?.ChaveAcesso;
        if (!string.IsNullOrWhiteSpace(chave))
        {
            chave = NfeAccessKey.ValidateInvoiceKey(chave);
            if (chave.Substring(6, 14) != Digitos(request.Fornecedor.Cnpj))
                throw new InvalidOperationException("O CNPJ da chave não corresponde ao fornecedor da entrada.");
            if (parsed is not null && (int.Parse(chave.Substring(20, 2)) != parsed.Modelo ||
                !int.TryParse(parsed.Serie, out var serie) || serie != int.Parse(chave.Substring(22, 3)) ||
                !long.TryParse(parsed.NumeroNota, out var numero) || numero != long.Parse(chave.Substring(25, 9))))
                throw new InvalidOperationException("Modelo, número ou série do XML não correspondem à chave de acesso.");
            if (!string.IsNullOrWhiteSpace(documento?.ChaveAcesso) && Digitos(documento.ChaveAcesso) != chave)
                throw new InvalidOperationException("A chave informada não corresponde ao XML.");
        }
        // Versões antigas do aplicativo (cache) não enviavam o documento e a entrada ficava sem número de nota.
        if (documento is null)
            throw new InvalidOperationException("Seu aplicativo está desatualizado e não enviou os dados da nota. Recarregue a página (Ctrl+F5) ou reinicie o Quack PDV e confirme a entrada de novo.");
        if (xml is null && string.IsNullOrWhiteSpace(chave))
            throw new InvalidOperationException("Informe o XML ou a chave do cupom antes de confirmar a entrada.");
        var identidade = chave ?? (xml is not null ? Convert.ToHexString(SHA256.HashData(xml)) : Guid.NewGuid().ToString("N"));
        return new NotaEntradaDocumento(Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(identidade))),
            chave, parsed?.Modelo ?? (chave is not null ? int.Parse(chave.Substring(20, 2)) : 0),
            parsed?.NumeroNota ?? (chave is not null ? long.Parse(chave.Substring(25, 9)).ToString() : ""),
            parsed?.Serie ?? (chave is not null ? int.Parse(chave.Substring(22, 3)).ToString() : ""),
            parsed?.DataEmissao, parsed?.ValorNota, xml is not null ? "xml" : chave is not null ? "digitada" : "sem-documento", xml);
    }

    /// <summary>
    /// Vincula o XML a uma entrada gravada sem documento (versões antigas do aplicativo).
    /// Só altera os dados fiscais do registro; o estoque e os itens da entrada não mudam.
    /// </summary>
    public async Task AnexarXmlAsync(string companyId, string id, string? xmlBase64)
    {
        if (string.IsNullOrWhiteSpace(xmlBase64)) throw new InvalidOperationException("Selecione o arquivo XML da nota.");
        if (xmlBase64.Length > 14_000_000) throw new InvalidOperationException("O XML deve ter no máximo 10 MB.");
        byte[] xml;
        try { xml = Convert.FromBase64String(xmlBase64); }
        catch (FormatException) { throw new InvalidOperationException("Conteúdo do XML inválido. Selecione o arquivo novamente."); }
        var parsed = NfeXmlParser.Parse(xml);
        var chave = NfeAccessKey.ValidateInvoiceKey(parsed.ChaveAcesso);
        var identidade = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(chave)));

        await using var db = await connection.OpenLeaseAsync();
        await using var command = connection.CreateCommand("""
            DECLARE @result INT;
            EXEC @result = sys.sp_getapplock @Resource=@Resource, @LockMode='Exclusive', @LockOwner='Session', @LockTimeout=15000;
            IF @result < 0 THROW 51000, 'Outra operação com esta nota está em andamento. Tente novamente.', 1;
            BEGIN TRY
              DECLARE @origem NVARCHAR(40), @cnpj NVARCHAR(20), @estornada BIT;
              SELECT @origem=Origem, @cnpj=FornecedorCnpj, @estornada=ISNULL(Estornada,0)
                FROM dbo.NotasEntradaArquivo WHERE CompanyId=@CompanyId AND Id=@Id;
              IF @origem IS NULL THROW 51001, 'Entrada não encontrada.', 1;
              IF @estornada = 1 THROW 51002, 'Esta entrada foi estornada; não é possível anexar o XML.', 1;
              IF @origem <> 'sem-documento' THROW 51003, 'Esta entrada já possui documento fiscal vinculado.', 1;
              IF @cnpj <> @Cnpj THROW 51004, 'O emitente do XML não é o fornecedor desta entrada.', 1;
              IF EXISTS (SELECT 1 FROM dbo.NotasEntradaArquivo WHERE CompanyId=@CompanyId AND Identidade=@Identidade AND Id<>@Id AND ISNULL(Estornada,0)=0)
                THROW 51005, 'Esta nota já está vinculada a outra entrada.', 1;
              UPDATE dbo.NotasEntradaArquivo
                 SET Identidade=@Identidade, ChaveAcesso=@Chave, Modelo=@Modelo, NumeroNota=@Numero, Serie=@Serie,
                     DataEmissao=@Emissao, ValorNota=@Total, Origem='xml', XmlOriginal=@Xml
               WHERE CompanyId=@CompanyId AND Id=@Id;
              EXEC sys.sp_releaseapplock @Resource=@Resource, @LockOwner='Session';
            END TRY
            BEGIN CATCH
              EXEC sys.sp_releaseapplock @Resource=@Resource, @LockOwner='Session';
              THROW;
            END CATCH
            """, db);
        command.Parameters.AddWithValue("@Resource", "nota-entrada:" + companyId + ":" + identidade);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        command.Parameters.AddWithValue("@Id", id);
        command.Parameters.AddWithValue("@Cnpj", parsed.Emitente.Cnpj);
        command.Parameters.AddWithValue("@Identidade", identidade);
        command.Parameters.AddWithValue("@Chave", chave);
        command.Parameters.AddWithValue("@Modelo", parsed.Modelo);
        command.Parameters.AddWithValue("@Numero", parsed.NumeroNota);
        command.Parameters.AddWithValue("@Serie", parsed.Serie);
        command.Parameters.AddWithValue("@Emissao", (object?)parsed.DataEmissao ?? DBNull.Value);
        command.Parameters.AddWithValue("@Total", (object?)parsed.ValorNota ?? DBNull.Value);
        command.Parameters.Add("@Xml", System.Data.SqlDbType.VarBinary, -1).Value = xml;
        try { await command.ExecuteNonQueryAsync(); }
        catch (SqlException ex) when (ex.Number is >= 51001 and <= 51005 or 51000) { throw new InvalidOperationException(ex.Message); }
        catch (SqlException ex) when (ex.Number is 2601 or 2627) { throw new InvalidOperationException("Esta nota já está vinculada a outra entrada."); }
    }

    public async Task ReservarAsync(string companyId, NotaEntradaDocumento documento)
    {
        await using var db = await connection.OpenLeaseAsync();
        await using var command = connection.CreateCommand("""
            DECLARE @result INT;
            EXEC @result = sys.sp_getapplock @Resource=@Resource, @LockMode='Exclusive', @LockOwner='Transaction', @LockTimeout=15000;
            IF @result < 0 THROW 51000, 'Outra entrada desta nota está em processamento. Tente novamente.', 1;
            DELETE FROM dbo.NotasEntradaArquivo WHERE CompanyId=@CompanyId AND Identidade=@Identidade AND Estornada=1;
            SELECT Id FROM dbo.NotasEntradaArquivo WHERE CompanyId=@CompanyId AND Identidade=@Identidade;
            """, db);
        command.Parameters.AddWithValue("@Resource", "nota-entrada:" + companyId + ":" + documento.Identidade);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        command.Parameters.AddWithValue("@Identidade", documento.Identidade);
        if (await command.ExecuteScalarAsync() is string)
            throw new InvalidOperationException("Esta nota já deu entrada no sistema. Consulte Notas de entrada para ver os itens; o estoque não foi alterado.");
    }

    public async Task<string> SalvarAsync(string companyId, NfeImportConfirmRequest request,
        NotaEntradaDocumento documento, string usuarioId, string usuarioNome, object? entrada = null,
        byte[]? comprovante = null, string? comprovanteNome = null, string? comprovanteTipo = null, decimal? valorEntrada = null,
        string? operacaoManualId = null)
    {
        var id = "ne-" + Guid.NewGuid().ToString("N");
        await using var db = await connection.OpenLeaseAsync();
        await using var command = connection.CreateCommand("""
            INSERT INTO dbo.NotasEntradaArquivo
            (Id,CompanyId,Identidade,ChaveAcesso,Modelo,NumeroNota,Serie,FornecedorNome,FornecedorCnpj,
             DataEmissao,ValorNota,ValorEntrada,QuantidadeItens,Origem,XmlOriginal,EntradaJson,UsuarioId,UsuarioNome,
             ComprovanteOriginal,ComprovanteNome,ComprovanteTipo,OperacaoManualId)
            VALUES (@Id,@CompanyId,@Identidade,@Chave,@Modelo,@Numero,@Serie,@Fornecedor,@Cnpj,
             @Emissao,@Total,@Entrada,@Itens,@Origem,@Xml,@Json,@UsuarioId,@UsuarioNome,@Arquivo,@ArquivoNome,@ArquivoTipo,@OperacaoManualId);
            """, db);
        command.Parameters.AddWithValue("@Id", id);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        command.Parameters.AddWithValue("@Identidade", documento.Identidade);
        command.Parameters.AddWithValue("@Chave", (object?)documento.Chave ?? DBNull.Value);
        command.Parameters.AddWithValue("@Modelo", documento.Modelo);
        command.Parameters.AddWithValue("@Numero", documento.Numero);
        command.Parameters.AddWithValue("@Serie", documento.Serie);
        command.Parameters.AddWithValue("@Fornecedor", request.Fornecedor.CompanyName);
        command.Parameters.AddWithValue("@Cnpj", Digitos(request.Fornecedor.Cnpj));
        command.Parameters.AddWithValue("@Emissao", (object?)documento.Emissao ?? DBNull.Value);
        command.Parameters.AddWithValue("@Total", (object?)documento.Total ?? DBNull.Value);
        command.Parameters.AddWithValue("@Entrada", valorEntrada ?? request.Itens.Sum(item => HorusMoneyFormat.ParseDecimal(item.Quantidade) * HorusMoneyFormat.ParseDecimal(item.PrecoCusto)));
        command.Parameters.AddWithValue("@Itens", request.Itens.Count);
        command.Parameters.AddWithValue("@Origem", documento.Origem);
        command.Parameters.Add("@Xml", System.Data.SqlDbType.VarBinary, -1).Value = (object?)documento.Xml ?? DBNull.Value;
        command.Parameters.AddWithValue("@Json", JsonSerializer.Serialize(entrada ?? new { request.Fornecedor, request.Itens }, JsonOptions));
        command.Parameters.Add("@Arquivo", System.Data.SqlDbType.VarBinary, -1).Value = (object?)comprovante ?? DBNull.Value;
        command.Parameters.AddWithValue("@ArquivoNome", (object?)comprovanteNome ?? DBNull.Value);
        command.Parameters.AddWithValue("@ArquivoTipo", (object?)comprovanteTipo ?? DBNull.Value);
        command.Parameters.AddWithValue("@OperacaoManualId", (object?)operacaoManualId ?? DBNull.Value);
        command.Parameters.AddWithValue("@UsuarioId", usuarioId);
        command.Parameters.AddWithValue("@UsuarioNome", usuarioNome);
        await command.ExecuteNonQueryAsync();
        return id;
    }

    private const string Columns = "Id,ChaveAcesso,Modelo,NumeroNota,Serie,FornecedorNome,FornecedorCnpj,DataEmissao,ValorNota,ValorEntrada,QuantidadeItens,Origem,CASE WHEN XmlOriginal IS NULL THEN CAST(0 AS BIT) ELSE CAST(1 AS BIT) END AS TemXml,CriadaEm,UsuarioNome,CASE WHEN ComprovanteOriginal IS NULL THEN CAST(0 AS BIT) ELSE CAST(1 AS BIT) END AS TemComprovante,ComprovanteNome,ISNULL(Estornada,0) AS Estornada,EstornadaEm,EstornadaPorUsuarioNome,EstornoJustificativa";
    public async Task<NotaEntradaPagina> ListarAsync(string companyId, string? busca, int pagina, int tamanhoPagina)
    {
        pagina = Math.Max(1, Math.Min(1_000_000, pagina));
        tamanhoPagina = Math.Clamp(tamanhoPagina, 1, 100);
        await using var db = await connection.OpenConnectionAsync();
        var term = (busca ?? "").Trim();
        if (term.Length > 100) term = term[..100];
        term = term.Replace("~", "~~").Replace("%", "~%").Replace("_", "~_").Replace("[", "~[");
        const string where = "CompanyId=@CompanyId AND (@Busca='%%' OR FornecedorNome LIKE @Busca ESCAPE '~' OR FornecedorCnpj LIKE @Busca ESCAPE '~' OR ChaveAcesso LIKE @Busca ESCAPE '~' OR NumeroNota LIKE @Busca ESCAPE '~')";
        await using var cmd = new SqlCommand($"SELECT COUNT(*) FROM dbo.NotasEntradaArquivo WHERE {where}; SELECT {Columns} FROM dbo.NotasEntradaArquivo WHERE {where} ORDER BY CriadaEm DESC,Id DESC OFFSET @Offset ROWS FETCH NEXT @Limit ROWS ONLY;", db);
        cmd.Parameters.AddWithValue("@CompanyId", companyId);
        cmd.Parameters.AddWithValue("@Busca", "%" + term + "%");
        cmd.Parameters.AddWithValue("@Offset", (pagina - 1) * tamanhoPagina);
        cmd.Parameters.AddWithValue("@Limit", tamanhoPagina);
        await using var reader = await cmd.ExecuteReaderAsync();
        await reader.ReadAsync(); var total = reader.GetInt32(0);
        await reader.NextResultAsync();
        var notas = new List<NotaEntradaResumo>();
        while (await reader.ReadAsync()) notas.Add(Map(reader));
        return new(notas, total, pagina, tamanhoPagina);
    }

    public async Task<NotaEntradaDetalhe?> ObterAsync(string companyId, string id)
    {
        await using var db = await connection.OpenConnectionAsync();
        await using var cmd = new SqlCommand($"SELECT {Columns},EntradaJson FROM dbo.NotasEntradaArquivo WHERE CompanyId=@CompanyId AND Id=@Id;", db);
        cmd.Parameters.AddWithValue("@CompanyId", companyId); cmd.Parameters.AddWithValue("@Id", id);
        await using var reader = await cmd.ExecuteReaderAsync();
        return await reader.ReadAsync() ? new(Map(reader), JsonSerializer.Deserialize<JsonElement>(reader.GetString(21))) : null;
    }

    /// <summary>
    /// ZIP para o contador com as entradas do mês (pela data de entrada no sistema, horário de Brasília):
    /// os XMLs originais das notas não estornadas e um resumo.csv com todas as entradas do período.
    /// </summary>
    public async Task<byte[]> ExportarMesAsync(string companyId, int ano, int mes)
    {
        if (ano < 2000 || ano > 2100 || mes < 1 || mes > 12) throw new InvalidOperationException("Mês inválido.");
        var offset = TimeSpan.FromHours(-3);
        var inicio = new DateTimeOffset(ano, mes, 1, 0, 0, 0, offset);
        var fim = inicio.AddMonths(1);

        await using var db = await connection.OpenConnectionAsync();
        await using var cmd = new SqlCommand("""
            SELECT Id, SWITCHOFFSET(CAST(CriadaEm AS datetimeoffset), '-03:00'), Origem, Modelo, NumeroNota, Serie, ChaveAcesso,
                   FornecedorNome, FornecedorCnpj, DataEmissao, ValorNota, ValorEntrada, ISNULL(Estornada,0), XmlOriginal
              FROM dbo.NotasEntradaArquivo
             WHERE CompanyId=@CompanyId AND CriadaEm >= @Inicio AND CriadaEm < @Fim
             ORDER BY CriadaEm;
            """, db);
        cmd.Parameters.AddWithValue("@CompanyId", companyId);
        cmd.Parameters.AddWithValue("@Inicio", inicio);
        cmd.Parameters.AddWithValue("@Fim", fim);

        using var buffer = new MemoryStream();
        using (var zip = new System.IO.Compression.ZipArchive(buffer, System.IO.Compression.ZipArchiveMode.Create, leaveOpen: true))
        {
            var csv = new StringBuilder("Data entrada;Tipo;Numero;Serie;Chave de acesso;Fornecedor;CNPJ;Emissao;Valor da nota;Custo da entrada;Situacao;XML no pacote\r\n");
            static string Cell(string? v) => "\"" + (v ?? "").Replace("\"", "\"\"") + "\"";
            static string Money(decimal? v) => v?.ToString("0.00", new System.Globalization.CultureInfo("pt-BR")) ?? "";
            var nomes = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            await using var reader = await cmd.ExecuteReaderAsync();
            while (await reader.ReadAsync())
            {
                var origem = reader.GetString(2);
                var modelo = reader.GetInt32(3);
                var chave = reader.IsDBNull(6) ? null : reader.GetString(6);
                var estornada = reader.GetBoolean(12);
                var xml = reader.IsDBNull(13) ? null : (byte[])reader[13];
                var incluiXml = xml is not null && !estornada;
                if (incluiXml)
                {
                    var nome = $"xml/{(string.IsNullOrEmpty(chave) ? reader.GetString(0) : chave)}.xml";
                    if (nomes.Add(nome))
                    {
                        var entry = zip.CreateEntry(nome, System.IO.Compression.CompressionLevel.Optimal);
                        await using var s = entry.Open();
                        await s.WriteAsync(xml!);
                    }
                }
                var tipo = origem == "manual" ? "Entrada manual" : modelo == 65 ? "NFC-e" : modelo == 55 ? "NF-e" : "Sem documento";
                csv.Append(string.Join(';',
                    Cell(reader.GetDateTimeOffset(1).ToString("dd/MM/yyyy HH:mm")), Cell(tipo), Cell(reader.GetString(4)), Cell(reader.GetString(5)),
                    Cell(chave is null ? "" : "'" + chave), Cell(reader.GetString(7)), Cell(reader.GetString(8)),
                    Cell(reader.IsDBNull(9) ? "" : reader.GetValue(9).ToString()), Money(reader.IsDBNull(10) ? null : reader.GetDecimal(10)),
                    Money(reader.GetDecimal(11)), Cell(estornada ? "Estornada" : "Ativa"), Cell(incluiXml ? "Sim" : "Nao"))).Append("\r\n");
            }
            var resumo = zip.CreateEntry("resumo.csv");
            await using var rs = resumo.Open();
            // BOM para o Excel abrir acentos corretamente.
            await rs.WriteAsync(Encoding.UTF8.GetPreamble());
            await rs.WriteAsync(Encoding.UTF8.GetBytes(csv.ToString()));
        }
        return buffer.ToArray();
    }

    public async Task<byte[]?> ObterXmlAsync(string companyId, string id)
    {
        await using var db = await connection.OpenConnectionAsync();
        await using var cmd = new SqlCommand("SELECT XmlOriginal FROM dbo.NotasEntradaArquivo WHERE CompanyId=@CompanyId AND Id=@Id", db);
        cmd.Parameters.AddWithValue("@CompanyId", companyId); cmd.Parameters.AddWithValue("@Id", id);
        return await cmd.ExecuteScalarAsync() as byte[];
    }

    public async Task<(byte[] Bytes, string Nome, string Tipo)?> ObterComprovanteAsync(string companyId, string id)
    {
        await using var db = await connection.OpenConnectionAsync();
        await using var cmd = new SqlCommand("SELECT ComprovanteOriginal,ComprovanteNome,ComprovanteTipo FROM dbo.NotasEntradaArquivo WHERE CompanyId=@CompanyId AND Id=@Id", db);
        cmd.Parameters.AddWithValue("@CompanyId", companyId); cmd.Parameters.AddWithValue("@Id", id);
        await using var reader = await cmd.ExecuteReaderAsync();
        return await reader.ReadAsync() && !reader.IsDBNull(0)
            ? ((byte[])reader[0], reader.GetString(1), reader.GetString(2)) : null;
    }

    public async Task<(string Id, JsonElement Entrada)?> ObterPorIdentidadeAsync(string companyId, string identidade)
    {
        await using var db = await connection.OpenLeaseAsync();
        await using var cmd = connection.CreateCommand("SELECT Id,EntradaJson FROM dbo.NotasEntradaArquivo WHERE CompanyId=@CompanyId AND Identidade=@Identidade", db);
        cmd.Parameters.AddWithValue("@CompanyId", companyId); cmd.Parameters.AddWithValue("@Identidade", identidade);
        await using var reader = await cmd.ExecuteReaderAsync();
        return await reader.ReadAsync() ? (reader.GetString(0), JsonSerializer.Deserialize<JsonElement>(reader.GetString(1))) : null;
    }

    /// <summary>
    /// Marca a nota de entrada como estornada. NÃO reverte estoque — isso é feito pelo serviço de orquestração.
    /// </summary>
    public async Task<bool> EstornarAsync(string companyId, string id, string usuarioId, string usuarioNome, string justificativa)
    {
        await using var db = await connection.OpenLeaseAsync();
        await using var cmd = connection.CreateCommand("""
            UPDATE dbo.NotasEntradaArquivo
               SET Estornada = 1, EstornadaEm = SYSUTCDATETIME(),
                   EstornadaPorUsuarioId = @UsuarioId, EstornadaPorUsuarioNome = @UsuarioNome,
                   EstornoJustificativa = @Justificativa
             WHERE CompanyId = @CompanyId AND Id = @Id AND ISNULL(Estornada, 0) = 0;
            """, db);
        cmd.Parameters.AddWithValue("@CompanyId", companyId);
        cmd.Parameters.AddWithValue("@Id", id);
        cmd.Parameters.AddWithValue("@UsuarioId", usuarioId);
        cmd.Parameters.AddWithValue("@UsuarioNome", usuarioNome);
        cmd.Parameters.AddWithValue("@Justificativa", justificativa);
        return await cmd.ExecuteNonQueryAsync() > 0;
    }

    /// <summary>
    /// Retorna os dados JSON da entrada para permitir o estorno de estoque item a item.
    /// </summary>
    public async Task<(NotaEntradaResumo Nota, JsonElement Entrada)?> ObterParaEstornoAsync(string companyId, string id)
    {
        await using var db = await connection.OpenConnectionAsync();
        await using var cmd = new SqlCommand($"SELECT {Columns},EntradaJson FROM dbo.NotasEntradaArquivo WHERE CompanyId=@CompanyId AND Id=@Id AND ISNULL(Estornada,0)=0;", db);
        cmd.Parameters.AddWithValue("@CompanyId", companyId); cmd.Parameters.AddWithValue("@Id", id);
        await using var reader = await cmd.ExecuteReaderAsync();
        return await reader.ReadAsync() ? (Map(reader), JsonSerializer.Deserialize<JsonElement>(reader.GetString(21))) : null;
    }

    private static string Digitos(string value) => new(value.Where(char.IsDigit).ToArray());
    public async Task<(string Id, JsonElement Entrada)?> ObterPorOperacaoManualAsync(string companyId, string operacao)
    {
        await using var db = await connection.OpenLeaseAsync();
        await using var cmd = connection.CreateCommand("SELECT Id,EntradaJson FROM dbo.NotasEntradaArquivo WHERE CompanyId=@CompanyId AND OperacaoManualId=@Operacao", db);
        cmd.Parameters.AddWithValue("@CompanyId", companyId); cmd.Parameters.AddWithValue("@Operacao", operacao);
        await using var reader = await cmd.ExecuteReaderAsync();
        return await reader.ReadAsync() ? (reader.GetString(0), JsonSerializer.Deserialize<JsonElement>(reader.GetString(1))) : null;
    }
    private static NotaEntradaResumo Map(SqlDataReader r) => new(r.GetString(0), r.IsDBNull(1) ? null : r.GetString(1), r.GetInt32(2),
        r.GetString(3), r.GetString(4), r.GetString(5), r.GetString(6), r.IsDBNull(7) ? null : r.GetString(7),
        r.IsDBNull(8) ? null : r.GetDecimal(8), r.GetDecimal(9), r.GetInt32(10), r.GetString(11), r.GetBoolean(12),
        DateTime.SpecifyKind(r.GetDateTime(13), DateTimeKind.Utc), r.GetString(14), r.GetBoolean(15), r.IsDBNull(16) ? null : r.GetString(16),
        r.GetBoolean(17), r.IsDBNull(18) ? null : DateTime.SpecifyKind(r.GetDateTime(18), DateTimeKind.Utc),
        r.IsDBNull(19) ? null : r.GetString(19), r.IsDBNull(20) ? null : r.GetString(20));
}
