using System.Xml;
using System.Xml.Linq;

namespace HORUSPDV_API.Services.Fiscal;

/// <summary>Combines the original signed invoice and its matching authorization, including legacy rows.</summary>
public static class FiscalXmlArchive
{
    private static readonly XNamespace Nfe = "http://www.portalfiscal.inf.br/nfe";
    private static readonly XNamespace Ds = "http://www.w3.org/2000/09/xmldsig#";

    public static string? Complete(string? signed, string? response, bool requireProtocol = false)
    {
        var returned = Parse(response);
        if (returned?.Name == Nfe + "nfeProc" && returned.Element(Nfe + "NFe") is not null)
        {
            Validate(returned.Element(Nfe + "NFe")!, returned.Element(Nfe + "protNFe"));
            return response;
        }
        var invoice = Parse(signed);
        if (invoice is null)
        {
            if (requireProtocol) throw new InvalidOperationException("O XML assinado da nota não está disponível. O protocolo isolado não substitui o XML completo.");
            return null;
        }
        if (invoice.Name != Nfe + "NFe" || invoice.Element(Nfe + "infNFe") is null)
            throw new InvalidOperationException("O XML assinado da nota está incompleto.");
        var protocol = returned?.DescendantsAndSelf(Nfe + "protNFe").SingleOrDefault();
        if (protocol is null)
        {
            if (requireProtocol) throw new InvalidOperationException("O protocolo original da nota autorizada não está disponível.");
            return signed;
        }
        Validate(invoice, protocol);
        return new XElement(Nfe + "nfeProc", new XAttribute("versao", "4.00"), invoice, protocol)
            .ToString(SaveOptions.DisableFormatting);
    }

    private static void Validate(XElement invoice, XElement? protocol)
    {
        var info = protocol?.Element(Nfe + "infProt");
        var key = invoice.Element(Nfe + "infNFe")?.Attribute("Id")?.Value;
        if (info is null || key != "NFe" + info.Element(Nfe + "chNFe")?.Value ||
            info.Element(Nfe + "cStat")?.Value is not ("100" or "150"))
            throw new InvalidOperationException("O protocolo de autorização não corresponde ao XML assinado da nota.");
        var digest = invoice.Descendants(Ds + "DigestValue").FirstOrDefault()?.Value;
        if (digest is not null && digest != info.Element(Nfe + "digVal")?.Value)
            throw new InvalidOperationException("O resumo da assinatura não corresponde ao protocolo de autorização.");
    }

    private static XElement? Parse(string? xml)
    {
        if (string.IsNullOrWhiteSpace(xml)) return null;
        using var input = new StringReader(xml);
        using var reader = XmlReader.Create(input, new XmlReaderSettings
        { DtdProcessing = DtdProcessing.Prohibit, XmlResolver = null, MaxCharactersInDocument = 10_000_000 });
        return XElement.Load(reader, LoadOptions.PreserveWhitespace);
    }
}
