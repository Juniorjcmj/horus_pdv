using System.Text.Json;
using NFe.Classes.Informacoes.Detalhe;
using NFe.Classes.Informacoes.Detalhe.Tributacao;
using NFe.Classes.Informacoes.Detalhe.Tributacao.Estadual;
using NFe.Classes.Informacoes.Total;

namespace HORUSPDV_API.Services.Fiscal;

public sealed class FiscalConfigurationException(string message) : Exception(message);

/// <summary>Bounded support for the 2026 transition. No inferred product classifications or future rates.</summary>
public static class FiscalTaxRules
{
    private static readonly Lazy<IReadOnlyDictionary<string, FiscalClass>> Classes = new(() =>
    {
        using var json = JsonDocument.Parse(File.ReadAllText(Path.Combine(AppContext.BaseDirectory, "DataBase", "FiscalTables", "cclass.json")));
        return FiscalReferenceTables.ParseClasses(json.RootElement);
    });

    public static IBSCBS? Build(ItemFiscal item, byte crt, int model, DateTimeOffset date, decimal icms)
    {
        var hasCst = !string.IsNullOrWhiteSpace(item.CstIbsCbs);
        var hasClass = !string.IsNullOrWhiteSpace(item.CClassTrib);
        if (hasCst != hasClass) throw new FiscalConfigurationException($"{item.Descricao}: preencha CST IBS/CBS e cClassTrib em conjunto.");
        if (!hasClass) return null;
        if (date.Year != 2026) throw new FiscalConfigurationException("Revise as regras e alíquotas de IBS/CBS para o ano de emissão. Esta versão calcula a transição de 2026.");
        // Simples/MEI/excesso de sublimite: do not apply regular-regime transition rates.
        if (crt is 1 or 2 or 4) return null;
        if (!Classes.Value.TryGetValue(item.CClassTrib!, out var classification) || classification.Cst != item.CstIbsCbs ||
            (model == 65 ? !classification.Nfce : !classification.Nfe) ||
            DateOnly.FromDateTime(date.DateTime) < classification.Inicio || DateOnly.FromDateTime(date.DateTime) > classification.Fim)
            throw new FiscalConfigurationException($"{item.Descricao}: CST/cClassTrib inválido, fora da vigência ou incompatível com o modelo da nota.");
        if (classification.Especial || classification.Cst is not ("000" or "200" or "400" or "410"))
            throw new FiscalConfigurationException($"{item.Descricao}: enquadramento IBS/CBS exige grupos fiscais ainda não implementados.");
        var cst = ZeusFiscalProvider.ParseIbsCbs(classification.Cst);
        var result = new IBSCBS { CST = cst, cClassTrib = classification.Codigo };
        if (!classification.ExigeTrib) return result;
        var net = item.ValorTotal - item.Desconto - icms; // Supported PIS/COFINS have no declared amount.
        if (net < 0) throw new FiscalConfigurationException($"{item.Descricao}: base IBS/CBS negativa.");
        var ufRate = 0.1m * (1 - classification.RedIbs / 100m);
        var cbsRate = 0.9m * (1 - classification.RedCbs / 100m);
        var uf = Money(net * ufRate / 100m); var cbs = Money(net * cbsRate / 100m);
        result.gIBSCBS = new gIBSCBS
        {
            vBC = net,
            gIBSUF = new gIBSUF { pIBSUF = 0.1m, vIBSUF = uf,
                gRed = classification.Cst == "200" ? new gRed { pRedAliq = classification.RedIbs, pAliqEfet = ufRate } : null },
            gIBSMun = new gIBSMun { pIBSMun = 0m, vIBSMun = 0m,
                gRed = classification.Cst == "200" ? new gRed { pRedAliq = classification.RedIbs, pAliqEfet = 0m } : null },
            vIBS = uf,
            gCBS = new gCBS { pCBS = 0.9m, vCBS = cbs,
                gRed = classification.Cst == "200" ? new gRed { pRedAliq = classification.RedCbs, pAliqEfet = cbsRate } : null }
        };
        return result;
    }

    public static IBSCBSTot? Totals(IReadOnlyList<det> items)
    {
        if (!items.Any(i => i.imposto.IBSCBS is not null)) return null;
        var groups = items.Select(i => i.imposto.IBSCBS?.gIBSCBS).OfType<gIBSCBS>().ToArray();
        return new IBSCBSTot
        {
            vBCIBSCBS = groups.Sum(g => g.vBC),
            gIBS = new gIBSTotal
            {
                gIBSUF = new gIBSUFTotal { vIBSUF = groups.Sum(g => g.gIBSUF.vIBSUF) },
                gIBSMun = new gIBSMunTotal { vIBSMun = groups.Sum(g => g.gIBSMun.vIBSMun) },
                vIBS = groups.Sum(g => g.vIBS ?? 0m)
            },
            gCBS = new gCBSTotal { vCBS = groups.Sum(g => g.gCBS.vCBS) }
        };
    }

    public static decimal IcmsBase(IReadOnlyList<det> items) => items.Sum(i => (i.imposto.ICMS.TipoICMS as ICMS00)?.vBC ?? 0m);
    public static decimal IcmsTotal(IReadOnlyList<det> items) => items.Sum(i => (i.imposto.ICMS.TipoICMS as ICMS00)?.vICMS ?? 0m);
    public static decimal Money(decimal value) => Math.Round(value, 2, MidpointRounding.AwayFromZero);
}
