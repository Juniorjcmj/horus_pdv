using System.Globalization;
using System.Text.Json;
using System.Text.RegularExpressions;
using HORUSPDV_API.Repositories.DataAccess;

namespace HORUSPDV_API.Services.Fiscal;

public sealed record FiscalAiConfigStatus(bool Configurada, string Modelo, bool UsarJev);
public sealed record FiscalAiSource(string Id, string Titulo, string Url, string Conteudo);
public sealed record FiscalAiSuggestion(string Campo, string? Atual, string? Sugerido, string Justificativa,
    string[] Fontes, bool PodeAplicar, string? Bloqueio, double? ProbabilidadeJev);
public sealed record FiscalAiReport(Guid Id, string ProdutoId, string ProdutoNome, DateTimeOffset AnalisadoEm,
    byte Crt, string Uf, string Modelo, string Resumo, string[] Pendencias,
    IReadOnlyDictionary<string, string?> CadastroOriginal, IReadOnlyList<FiscalAiSource> Fontes,
    IReadOnlyList<FiscalAiSuggestion> Sugestoes, string SituacaoJev, decimal? CustoUsd);
public sealed record FiscalAiDraft(string Resumo, string[] Pendencias, FiscalAiDraftSuggestion[] Sugestoes);
public sealed record FiscalAiDraftSuggestion(string Campo, string? Sugerido, string Justificativa, string[] Fontes);
public sealed record FiscalCest(string Codigo, string[] Ncms, string Descricao);

public static class FiscalAiRules
{
    public const string Model = "google/gemini-2.5-flash-lite";
    public const string JevModel = "typesafe/jev-1.13";
    public const string CestUrl = "https://www.confaz.fazenda.gov.br/legislacao/convenios/2018/CV142_18";
    public static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web);
    public static readonly IReadOnlyDictionary<string, string> Columns = new Dictionary<string, string>
    {
        ["ncm"] = "Ncm", ["cest"] = "Cest", ["cfop"] = "Cfop", ["origemMercadoria"] = "OrigemMercadoria",
        ["csosnIcms"] = "CsosnIcms", ["cstIcms"] = "CstIcms", ["aliquotaIcms"] = "AliquotaIcms",
        ["cstPis"] = "CstPis", ["cstCofins"] = "CstCofins", ["cstIbsCbs"] = "CstIbsCbs", ["cClassTrib"] = "CClassTrib"
    };

    public static Dictionary<string, string?> Snapshot(ProdutoAD p)
    {
        var result = Columns.ToDictionary(k => k.Key, k =>
        {
            var value = typeof(ProdutoAD).GetProperty(k.Value)!.GetValue(p);
            return value is decimal number ? number.ToString("G29", CultureInfo.InvariantCulture) : Convert.ToString(value, CultureInfo.InvariantCulture);
        });
        result["_nome"] = p.ProductName; result["_descricao"] = p.ProductDescription; result["_codigo"] = p.ProductCode;
        result["_marca"] = p.Marca; result["_gtin"] = p.Gtin;
        return result;
    }

    public static string? Validate(string field, string? value, ProdutoAD p, EmpresaAD company,
        FiscalTableData tables, IReadOnlyList<FiscalCest> cests, DateOnly date)
    {
        if (!Columns.ContainsKey(field)) return "Campo não permitido para correção fiscal.";
        value = string.IsNullOrWhiteSpace(value) ? null : value.Trim();
        bool Code(int size) => value is not null && value.Length == size && value.All(char.IsAsciiDigit);
        return field switch
        {
            "ncm" when !Code(8) || !tables.Ncms.TryGetValue(value!, out var ncm) || date < ncm.Inicio || date > ncm.Fim => "NCM ausente, inexistente ou fora da vigência consultada.",
            "cest" when value is not null && (!Code(7) || !cests.Any(c => c.Codigo == value && c.Ncms.Any(n => p.Ncm.StartsWith(n, StringComparison.Ordinal)))) => "CEST não corresponde ao NCM na referência CONFAZ consultada.",
            "cfop" when !Code(4) || value![0] != '5' => "Esta ferramenta contempla vendas internas com CFOP iniciado em 5.",
            "origemMercadoria" when !byte.TryParse(value, out var origin) || origin > 8 => "Origem deve estar entre 0 e 8.",
            "csosnIcms" when value is not null && (company.Crt is not (1 or 4) || value is not ("102" or "103" or "300" or "400" or "500")) => "CSOSN incompatível com o regime ou não suportado pelo emissor.",
            "cstIcms" when value is not null && (company.Crt is 1 or 4 || value is not ("00" or "40" or "41" or "50" or "60")) => "CST ICMS incompatível com o regime ou não suportado pelo emissor.",
            "aliquotaIcms" when !decimal.TryParse(value, NumberStyles.AllowDecimalPoint, CultureInfo.InvariantCulture, out var rate) || rate < 0 || rate > 100 => "Alíquota ICMS inválida.",
            "cstPis" or "cstCofins" when value is not ("04" or "05" or "06" or "07" or "08" or "09" or "49" or "99") => "CST não suportado pelo emissor; não substitua tributo devido por zero.",
            "cstIbsCbs" or "cClassTrib" when company.Crt is 1 or 4 && date.Year == 2026 && value is not null => "Não preencher IBS/CBS automaticamente para Simples/MEI em 2026.",
            "cstIbsCbs" when value is not null && !Code(3) => "CST IBS/CBS exige três dígitos.",
            "cClassTrib" when value is not null && (!Code(6) || !tables.Classes.TryGetValue(value, out var cls) || !cls.Nfce || date < cls.Inicio || cls.Fim is { } end && date > end || cls.Especial || cls.Cst is not ("000" or "200" or "400" or "410")) => "Classificação IBS/CBS inválida, não vigente ou não suportada para NFC-e.",
            _ => null
        };
    }

    public static void Set(ProdutoAD p, string field, string? value)
    {
        if (!Columns.TryGetValue(field, out var column)) throw new InvalidOperationException("Campo não permitido.");
        var property = typeof(ProdutoAD).GetProperty(column)!;
        object? parsed = property.PropertyType == typeof(decimal) ? decimal.Parse(value!, CultureInfo.InvariantCulture)
            : property.PropertyType == typeof(byte) ? byte.Parse(value!, CultureInfo.InvariantCulture)
            : string.IsNullOrWhiteSpace(value) ? null : value.Trim();
        property.SetValue(p, parsed);
    }

    public static ProdutoAD Copy(ProdutoAD p) => JsonSerializer.Deserialize<ProdutoAD>(JsonSerializer.Serialize(p, Json), Json)!;

    public static IReadOnlyList<FiscalAiSource> Sources(ProdutoAD p, FiscalTableData tables, IReadOnlyList<FiscalCest> cests)
    {
        var terms = Regex.Split($"{p.ProductName} {p.ProductDescription}".ToLowerInvariant(), @"\W+").Where(t => t.Length >= 4).Distinct().ToArray();
        var prefix = FiscalReferenceTables.Digits(p.Ncm);
        var sources = tables.Ncms.Values.Where(n => n.Codigo == p.Ncm || prefix.Length >= 4 && n.Codigo.StartsWith(prefix[..4]) || terms.Any(t => n.Descricao.Contains(t, StringComparison.OrdinalIgnoreCase)))
            .OrderByDescending(n => n.Codigo == p.Ncm).Take(40)
            .Select(n => new FiscalAiSource("ncm:" + n.Codigo, "NCM " + n.Codigo, FiscalReferenceTables.NcmUrl, $"{n.Descricao}; vigência {n.Inicio:yyyy-MM-dd} a {n.Fim:yyyy-MM-dd}; base {tables.DataBase}." )).ToList();
        sources.AddRange(cests.Where(c => c.Ncms.Any(n => prefix.StartsWith(n)) || terms.Any(t => c.Descricao.Contains(t, StringComparison.OrdinalIgnoreCase)))
            .Take(35).Select(c => new FiscalAiSource("cest:" + c.Codigo, "CEST " + c.Codigo, CestUrl,
                $"NCM {string.Join(", ", c.Ncms)}: {c.Descricao}; referência local consultada em 2026-10-10. CEST não comprova incidência de ST na operação.")));
        sources.AddRange(tables.Classes.Values.Where(c => c.Nfce && !c.Especial && (c.Codigo == p.CClassTrib || c.Cst is "000" or "200" or "400" or "410"))
            .OrderByDescending(c => c.Codigo == p.CClassTrib).Take(35).Select(c => new FiscalAiSource("classe:" + c.Codigo, "cClassTrib " + c.Codigo, FiscalReferenceTables.ClassUrl, $"CST {c.Cst}; {c.Descricao}; vigência {c.Inicio:yyyy-MM-dd} a {c.Fim:yyyy-MM-dd}; redução IBS {c.RedIbs}% CBS {c.RedCbs}%.")));
        sources.Add(new("simples2026", "Simples Nacional em 2026", "https://www.planalto.gov.br/ccivil_03/leis/lcp/lcp214compilado.htm", "Art. 348: as alíquotas de IBS/CBS de 2026 não se aplicam às operações dos optantes pelo Simples Nacional. Não concluir obrigatoriedade de preenchimento para Simples/MEI em 2026. Reavaliar em 2027."));
        sources.Add(new("bebidas-varejo", "Bebidas frias no varejo", "https://www.planalto.gov.br/ccivil_03/_ato2015-2018/2015/decreto/d8442.htm", "Art. 22 prevê alíquota zero de PIS/COFINS para venda por varejistas definidos no art. 17. Alíquota zero não equivale a isenção nem comprova tributação monofásica. Confirmar condições e tratamento do Simples com a contabilidade."));
        return sources;
    }
}
