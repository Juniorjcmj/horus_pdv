using System.Text.Json;

namespace HORUSPDV_API.Services.Fiscal;

/// <summary>Local dated coverage of the consolidated CONFAZ annexes, including scheduled changes.</summary>
public sealed class FiscalCestReference(IWebHostEnvironment environment)
{
    public IReadOnlyList<FiscalCest> Items { get; } = Load(environment.ContentRootPath);

    public static IReadOnlyList<FiscalCest> Load(string root)
    {
        using var document = JsonDocument.Parse(File.ReadAllText(Path.Combine(root, "DataBase", "FiscalTables", "cest.json")));
        var items = document.RootElement.GetProperty("itens").Deserialize<FiscalCest[]>(FiscalAiRules.Json)
            ?? throw new InvalidOperationException("Referência CEST indisponível.");
        if (items.Length == 0 || items.Any(c => c.Inicio == default || c.Fim < c.Inicio
            || c.Codigo.Length != 7 || !c.Codigo.All(char.IsAsciiDigit) || c.Ncms.Length == 0))
            throw new InvalidOperationException("Referência CEST sem cobertura de datas válida.");
        foreach (var versions in items.GroupBy(c => c.Codigo))
        {
            var ordered = versions.OrderBy(c => c.Inicio).ToArray();
            for (var i = 1; i < ordered.Length; i++)
                if (ordered[i - 1].Fim is not { } end || end >= ordered[i].Inicio)
                    throw new InvalidOperationException("Referência CEST com versões sobrepostas.");
        }
        return items;
    }
}
