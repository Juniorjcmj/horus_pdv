using System.Globalization;
using System.Text.Json;
using System.Xml.Linq;
using HORUSPDV_API.Services.Fiscal;
using HorusGateway.Models;

namespace HorusGateway.Services;

/// <summary>Same bounded tax groups as the cloud emitter. No product classification is inferred.</summary>
internal static class LocalFiscalTaxRules
{
    internal static readonly XNamespace Ns = "http://www.portalfiscal.inf.br/nfe";
    internal static XElement E(string name, object? value) => new(Ns + name, value);
    internal static string MoneyText(decimal value) => value.ToString("F2", CultureInfo.InvariantCulture);
    internal static decimal Money(decimal value) => Math.Round(value, 2, MidpointRounding.AwayFromZero);
    private static readonly Lazy<IReadOnlyDictionary<string, FiscalClass>> Classes = new(() =>
    {
        using var json = JsonDocument.Parse(File.ReadAllText(Path.Combine(AppContext.BaseDirectory, "DataBase", "FiscalTables", "cclass.json")));
        return FiscalReferenceTables.ParseClasses(json.RootElement);
    });

    internal static XElement Build(LocalItemFiscal item, byte crt, DateTimeOffset date)
    {
        if (item.OrigemMercadoria is null or > 8) throw new InvalidOperationException($"{item.Descricao}: origem da mercadoria não informada ou inválida.");
        if (item.AliquotaIcms is < 0 or > 100) throw new InvalidOperationException($"{item.Descricao}: alíquota ICMS inválida.");
        var origin = E("orig", item.OrigemMercadoria.Value);
        XElement icms = crt is 1 or 4 ? item.CsosnIcms switch
        {
            "102" or "103" or "300" or "400" => new XElement(Ns + "ICMSSN102", origin, E("CSOSN", item.CsosnIcms)),
            "500" => new XElement(Ns + "ICMSSN500", origin, E("CSOSN", "500")),
            _ => throw new InvalidOperationException($"{item.Descricao}: CSOSN ausente ou não suportado. Confira o cadastro fiscal.")
        } : item.CstIcms switch
        {
            "60" => new XElement(Ns + "ICMS60", origin, E("CST", "60")),
            "40" or "41" or "50" => new XElement(Ns + "ICMS40", origin, E("CST", item.CstIcms)),
            "00" when item.AliquotaIcms.HasValue => new XElement(Ns + "ICMS00", origin, E("CST", "00"), E("modBC", 3),
                E("vBC", MoneyText(item.ValorTotal - item.Desconto)), E("pICMS", MoneyText(item.AliquotaIcms.Value)),
                E("vICMS", MoneyText(Money((item.ValorTotal - item.Desconto) * item.AliquotaIcms.Value / 100m)))),
            _ => throw new InvalidOperationException($"{item.Descricao}: CST ICMS/alíquota ausente ou não suportado. Confira o cadastro fiscal.")
        };
        return new XElement(Ns + "imposto", E("ICMS", icms), Contribution("PIS", item.CstPis),
            Contribution("COFINS", item.CstCofins), BuildIbsCbs(item, crt, date, Value(icms, "vICMS")));
    }

    private static XElement Contribution(string tax, string? cst)
    {
        if (string.IsNullOrWhiteSpace(cst)) throw new InvalidOperationException($"CST {tax} não informado.");
        var code = cst.Trim().PadLeft(2, '0');
        if (code is "04" or "05" or "06" or "07" or "08" or "09") return E(tax, E(tax + "NT", E("CST", code)));
        if (code is "49" or "99") return E(tax, new XElement(Ns + tax + "Outr", E("CST", code), E("vBC", "0.00"), E("p" + tax, "0.00"), E("v" + tax, "0.00")));
        throw new InvalidOperationException($"CST {tax} exige cálculo ainda não disponível. Confira a tributação com a contabilidade.");
    }

    private static XElement? BuildIbsCbs(LocalItemFiscal item, byte crt, DateTimeOffset date, decimal icms)
    {
        var hasCst = !string.IsNullOrWhiteSpace(item.CstIbsCbs); var hasClass = !string.IsNullOrWhiteSpace(item.CClassTrib);
        if (hasCst != hasClass) throw new InvalidOperationException($"{item.Descricao}: preencha CST IBS/CBS e cClassTrib em conjunto.");
        if (!hasClass) return null;
        if (date.Year != 2026) throw new InvalidOperationException("Revise IBS/CBS para o ano de emissão. Esta versão calcula a transição de 2026.");
        if (crt is 1 or 2 or 4) return null;
        var day = DateOnly.FromDateTime(date.DateTime);
        if (!Classes.Value.TryGetValue(item.CClassTrib!, out var classification) || classification.Cst != item.CstIbsCbs ||
            !classification.Nfce || day < classification.Inicio || (classification.Fim.HasValue && day > classification.Fim.Value))
            throw new InvalidOperationException($"{item.Descricao}: CST/cClassTrib inválido, fora da vigência ou incompatível com NFC-e.");
        if (classification.Especial || classification.Cst is not ("000" or "200" or "400" or "410"))
            throw new InvalidOperationException($"{item.Descricao}: enquadramento IBS/CBS exige grupos fiscais ainda não implementados.");
        var result = new XElement(Ns + "IBSCBS", E("CST", classification.Cst), E("cClassTrib", classification.Codigo));
        if (!classification.ExigeTrib) return result;
        var net = item.ValorTotal - item.Desconto - icms;
        if (net < 0) throw new InvalidOperationException($"{item.Descricao}: base IBS/CBS negativa.");
        var ufRate = 0.1m * (1 - classification.RedIbs / 100m); var cbsRate = 0.9m * (1 - classification.RedCbs / 100m); var uf = Money(net * ufRate / 100m);
        XElement? Reduction(decimal percent, decimal rate) => classification.Cst == "200"
            ? new XElement(Ns + "gRed", E("pRedAliq", MoneyText(percent)), E("pAliqEfet", rate.ToString("F4", CultureInfo.InvariantCulture))) : null;
        result.Add(new XElement(Ns + "gIBSCBS", E("vBC", MoneyText(net)),
            new XElement(Ns + "gIBSUF", E("pIBSUF", "0.10"), Reduction(classification.RedIbs, ufRate), E("vIBSUF", MoneyText(uf))),
            new XElement(Ns + "gIBSMun", E("pIBSMun", "0.00"), Reduction(classification.RedIbs, 0m), E("vIBSMun", "0.00")), E("vIBS", MoneyText(uf)),
            new XElement(Ns + "gCBS", E("pCBS", "0.90"), Reduction(classification.RedCbs, cbsRate), E("vCBS", MoneyText(Money(net * cbsRate / 100m))))));
        return result;
    }

    internal static XElement? IbsTotals(IEnumerable<XElement> taxes)
    {
        var ibs = taxes.Select(t => t.Element(Ns + "IBSCBS")).OfType<XElement>().ToArray();
        if (ibs.Length == 0) return null;
        var groups = ibs.Select(i => i.Element(Ns + "gIBSCBS")).OfType<XElement>().ToArray();
        XElement Subtotal(string group, string value) => new(Ns + group, E("vDif", "0.00"), E("vDevTrib", "0.00"), E(value, MoneyText(groups.Sum(g => Value(g.Element(Ns + group), value)))));
        return new XElement(Ns + "IBSCBSTot", E("vBCIBSCBS", MoneyText(groups.Sum(g => Value(g, "vBC")))),
            new XElement(Ns + "gIBS", Subtotal("gIBSUF", "vIBSUF"), Subtotal("gIBSMun", "vIBSMun"), E("vIBS", MoneyText(groups.Sum(g => Value(g, "vIBS")))), E("vCredPres", "0.00"), E("vCredPresCondSus", "0.00")),
            new XElement(Ns + "gCBS", E("vDif", "0.00"), E("vDevTrib", "0.00"), E("vCBS", MoneyText(groups.Sum(g => Value(g.Element(Ns + "gCBS"), "vCBS")))), E("vCredPres", "0.00"), E("vCredPresCondSus", "0.00")));
    }
    internal static decimal Value(XElement? parent, string name) => decimal.TryParse(parent?.Element(Ns + name)?.Value, NumberStyles.Number, CultureInfo.InvariantCulture, out var value) ? value : 0m;
}
