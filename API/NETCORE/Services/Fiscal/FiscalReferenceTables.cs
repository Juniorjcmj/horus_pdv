using System.Globalization;
using System.Net;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace HORUSPDV_API.Services.Fiscal;

public sealed record FiscalNcm(string Codigo, string Descricao, DateOnly Inicio, DateOnly Fim);
public sealed record FiscalClass(string Codigo, string Cst, string Descricao, DateOnly Inicio, DateOnly? Fim,
    bool Nfce, bool Nfe, bool ExigeTrib, decimal RedIbs, decimal RedCbs, bool Especial);
public sealed record FiscalTableData(IReadOnlyDictionary<string, FiscalNcm> Ncms,
    IReadOnlyDictionary<string, FiscalClass> Classes, bool Online, DateTimeOffset ConsultadoEm, string DataBase);

/// <summary>Downloads public tables, without transmitting product, company or certificate data.</summary>
public sealed class FiscalReferenceTables(IWebHostEnvironment environment, ILogger<FiscalReferenceTables> logger, HttpClient? client = null)
{
    public const string NcmUrl = "https://portalunico.siscomex.gov.br/classif/api/publico/nomenclatura/download/json";
    public const string ClassUrl = "https://dfe-portal.svrs.rs.gov.br/CFF/ClassificacaoTributaria";
    public const string SnapshotDate = "2026-10-10";
    private static readonly HttpClient Client = new() { Timeout = TimeSpan.FromSeconds(15), MaxResponseContentBufferSize = 10_000_000 };
    private readonly HttpClient http = client ?? Client;
    private readonly SemaphoreSlim gate = new(1);
    private FiscalTableData? current;
    private DateTimeOffset retryAfter;

    public async Task<FiscalTableData> GetAsync(CancellationToken ct = default)
    {
        await gate.WaitAsync(ct);
        try
        {
            if (current is not null && DateTimeOffset.UtcNow < retryAfter) return current;
            try
            {
                var ncmTask = http.GetStringAsync(NcmUrl, ct);
                var classTask = http.GetStringAsync(ClassUrl, ct);
                await Task.WhenAll(ncmTask, classTask);
                using var ncms = JsonDocument.Parse(await ncmTask);
                using var classes = ParseClassPage(await classTask);
                current = new(ParseLiveNcm(ncms.RootElement), ParseClasses(classes.RootElement), true,
                    DateTimeOffset.UtcNow, ncms.RootElement.GetProperty("Data_Ultima_Atualizacao_NCM").GetString() ?? "Consulta oficial");
                retryAfter = DateTimeOffset.UtcNow.AddHours(24);
            }
            catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException or JsonException or InvalidOperationException or KeyNotFoundException or FormatException or ArgumentException)
            {
                ct.ThrowIfCancellationRequested();
                logger.LogWarning("Tabelas fiscais oficiais indisponíveis ({Tipo}); usando referência local datada.", ex.GetType().Name);
                var path = Path.Combine(environment.ContentRootPath, "DataBase", "FiscalTables");
                using var ncm = JsonDocument.Parse(await File.ReadAllTextAsync(Path.Combine(path, "ncm.json"), ct));
                using var classes = JsonDocument.Parse(await File.ReadAllTextAsync(Path.Combine(path, "cclass.json"), ct));
                var items = ncm.RootElement.GetProperty("itens").EnumerateArray().Select(r => new FiscalNcm(
                    r.GetProperty("codigo").GetString()!, r.GetProperty("descricao").GetString()!,
                    ParseDate(r.GetProperty("inicio").GetString()!), ParseDate(r.GetProperty("fim").GetString()!)));
                // Keep newer cached data after an outage, but never label it a fresh online consultation.
                current = current is null
                    ? new(items.ToDictionary(r => r.Codigo), ParseClasses(classes.RootElement), false, DateTimeOffset.UtcNow, SnapshotDate)
                    : current with { Online = false };
                retryAfter = DateTimeOffset.UtcNow.AddMinutes(15);
            }
            return current;
        }
        finally { gate.Release(); }
    }

    public static JsonDocument ParseClassPage(string html)
    {
        var match = Regex.Match(html, @"var\s+dadosOriginais\s*=\s*", RegexOptions.CultureInvariant);
        if (!match.Success) throw new JsonException("Tabela pública de classificação indisponível.");
        var reader = new Utf8JsonReader(Encoding.UTF8.GetBytes(html[match.Index..][match.Length..]));
        return JsonDocument.ParseValue(ref reader);
    }

    public static Dictionary<string, FiscalNcm> ParseLiveNcm(JsonElement root)
    {
        var rows = root.GetProperty("Nomenclaturas").EnumerateArray().ToArray();
        var descriptions = rows.ToDictionary(r => Digits(r.GetProperty("Codigo").GetString()),
            r => WebUtility.HtmlDecode(Regex.Replace(r.GetProperty("Descricao").GetString() ?? "", "<[^>]+>", "")));
        return rows.Where(r => Digits(r.GetProperty("Codigo").GetString()).Length == 8).Select(r =>
        {
            var code = Digits(r.GetProperty("Codigo").GetString());
            var description = string.Join(" > ", new[] { 2, 4, 5, 6, 7, 8 }.Where(n => descriptions.ContainsKey(code[..n]))
                .Select(n => descriptions[code[..n]]).Distinct());
            return new FiscalNcm(code, description, ParseDate(r.GetProperty("Data_Inicio").GetString()!), ParseDate(r.GetProperty("Data_Fim").GetString()!));
        }).ToDictionary(r => r.Codigo);
    }

    public static Dictionary<string, FiscalClass> ParseClasses(JsonElement root) => root.EnumerateArray().SelectMany(group =>
        group.GetProperty("ClassificacoesTributarias").EnumerateArray().Select(row => new FiscalClass(
            row.GetProperty("CodClassTrib").GetString()!, row.GetProperty("Cst").GetString()!, row.GetProperty("NomeClassTrib").GetString()!,
            ParseDate(row.GetProperty("DthIniVig").GetString()!), row.GetProperty("DthFimVig").ValueKind == JsonValueKind.Null ? null : ParseDate(row.GetProperty("DthFimVig").GetString()!),
            Flag(row, "IndNfce"), Flag(row, "IndNfe"), Flag(group, "IndExigeTrib"), row.GetProperty("PercRedIbs").GetDecimal(), row.GetProperty("PercRedCbs").GetDecimal(),
            Flag(row, "IndTribRegular") || Flag(row, "IndEstornoCred") || Flag(row, "IndPbioDiferenca") || Flag(group, "IndMonofasica") || Flag(group, "IndDiferimento"))))
        .ToDictionary(r => r.Codigo);

    public static string Digits(string? value) => new((value ?? "").Where(char.IsAsciiDigit).ToArray());
    private static bool Flag(JsonElement row, string key) => row.TryGetProperty(key, out var value) && value.ValueKind == JsonValueKind.True;
    private static DateOnly ParseDate(string value) => value.Contains('/')
        ? DateOnly.ParseExact(value, "dd/MM/yyyy", CultureInfo.InvariantCulture)
        : DateOnly.Parse(value[..10], CultureInfo.InvariantCulture);
}
