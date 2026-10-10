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
    decimal ValorEntrada, int QuantidadeItens, string Origem, bool TemXml, DateTime CriadaEm, string UsuarioNome);
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
        if (documento is not null && xml is null && string.IsNullOrWhiteSpace(chave))
            throw new InvalidOperationException("Informe o XML ou a chave do cupom antes de confirmar a entrada.");
        // Clientes antigos não enviavam o documento. Guardamos o movimento sem atribuir a ele um XML fiscal inexistente.
        var identidade = chave ?? (xml is not null ? Convert.ToHexString(SHA256.HashData(xml)) : Guid.NewGuid().ToString("N"));
        return new NotaEntradaDocumento(Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(identidade))),
            chave, parsed?.Modelo ?? (chave is not null ? int.Parse(chave.Substring(20, 2)) : 0),
            parsed?.NumeroNota ?? (chave is not null ? long.Parse(chave.Substring(25, 9)).ToString() : ""),
            parsed?.Serie ?? (chave is not null ? int.Parse(chave.Substring(22, 3)).ToString() : ""),
            parsed?.DataEmissao, parsed?.ValorNota, xml is not null ? "xml" : chave is not null ? "digitada" : "sem-documento", xml);
    }

    public async Task ReservarAsync(string companyId, NotaEntradaDocumento documento)
    {
        await using var db = await connection.OpenLeaseAsync();
        await using var command = connection.CreateCommand("""
            DECLARE @result INT;
            EXEC @result = sys.sp_getapplock @Resource=@Resource, @LockMode='Exclusive', @LockOwner='Transaction', @LockTimeout=15000;
            IF @result < 0 THROW 51000, 'Outra entrada desta nota está em processamento. Tente novamente.', 1;
            SELECT Id FROM dbo.NotasEntradaArquivo WHERE CompanyId=@CompanyId AND Identidade=@Identidade;
            """, db);
        command.Parameters.AddWithValue("@Resource", "nota-entrada:" + companyId + ":" + documento.Identidade);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        command.Parameters.AddWithValue("@Identidade", documento.Identidade);
        if (await command.ExecuteScalarAsync() is string)
            throw new InvalidOperationException("Esta nota já deu entrada no sistema. Consulte Notas de entrada para ver os itens; o estoque não foi alterado.");
    }

    public async Task<string> SalvarAsync(string companyId, NfeImportConfirmRequest request,
        NotaEntradaDocumento documento, string usuarioId, string usuarioNome)
    {
        var id = "ne-" + Guid.NewGuid().ToString("N");
        await using var db = await connection.OpenLeaseAsync();
        await using var command = connection.CreateCommand("""
            INSERT INTO dbo.NotasEntradaArquivo
            (Id,CompanyId,Identidade,ChaveAcesso,Modelo,NumeroNota,Serie,FornecedorNome,FornecedorCnpj,
             DataEmissao,ValorNota,ValorEntrada,QuantidadeItens,Origem,XmlOriginal,EntradaJson,UsuarioId,UsuarioNome)
            VALUES (@Id,@CompanyId,@Identidade,@Chave,@Modelo,@Numero,@Serie,@Fornecedor,@Cnpj,
             @Emissao,@Total,@Entrada,@Itens,@Origem,@Xml,@Json,@UsuarioId,@UsuarioNome);
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
        command.Parameters.AddWithValue("@Entrada", request.Itens.Sum(item => HorusMoneyFormat.ParseDecimal(item.Quantidade) * HorusMoneyFormat.ParseDecimal(item.PrecoCusto)));
        command.Parameters.AddWithValue("@Itens", request.Itens.Count);
        command.Parameters.AddWithValue("@Origem", documento.Origem);
        command.Parameters.Add("@Xml", System.Data.SqlDbType.VarBinary, -1).Value = (object?)documento.Xml ?? DBNull.Value;
        command.Parameters.AddWithValue("@Json", JsonSerializer.Serialize(new { request.Fornecedor, request.Itens }, JsonOptions));
        command.Parameters.AddWithValue("@UsuarioId", usuarioId);
        command.Parameters.AddWithValue("@UsuarioNome", usuarioNome);
        await command.ExecuteNonQueryAsync();
        return id;
    }

    private const string Columns = "Id,ChaveAcesso,Modelo,NumeroNota,Serie,FornecedorNome,FornecedorCnpj,DataEmissao,ValorNota,ValorEntrada,QuantidadeItens,Origem,CASE WHEN XmlOriginal IS NULL THEN CAST(0 AS BIT) ELSE CAST(1 AS BIT) END AS TemXml,CriadaEm,UsuarioNome";
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
        return await reader.ReadAsync() ? new(Map(reader), JsonSerializer.Deserialize<JsonElement>(reader.GetString(15))) : null;
    }

    public async Task<byte[]?> ObterXmlAsync(string companyId, string id)
    {
        await using var db = await connection.OpenConnectionAsync();
        await using var cmd = new SqlCommand("SELECT XmlOriginal FROM dbo.NotasEntradaArquivo WHERE CompanyId=@CompanyId AND Id=@Id", db);
        cmd.Parameters.AddWithValue("@CompanyId", companyId); cmd.Parameters.AddWithValue("@Id", id);
        return await cmd.ExecuteScalarAsync() as byte[];
    }

    private static string Digitos(string value) => new(value.Where(char.IsDigit).ToArray());
    private static NotaEntradaResumo Map(SqlDataReader r) => new(r.GetString(0), r.IsDBNull(1) ? null : r.GetString(1), r.GetInt32(2),
        r.GetString(3), r.GetString(4), r.GetString(5), r.GetString(6), r.IsDBNull(7) ? null : r.GetString(7),
        r.IsDBNull(8) ? null : r.GetDecimal(8), r.GetDecimal(9), r.GetInt32(10), r.GetString(11), r.GetBoolean(12),
        DateTime.SpecifyKind(r.GetDateTime(13), DateTimeKind.Utc), r.GetString(14));
}
