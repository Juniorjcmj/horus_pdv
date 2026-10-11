using System.Xml;
using System.Xml.Linq;

namespace HORUSPDV_API.Services.Fiscal;

/// <summary>Combines the original signed invoice and its matching authorization, including legacy rows.</summary>
public static class FiscalXmlArchive
{
    private static readonly XNamespace Nfe = "http://www.portalfiscal.inf.br/nfe";
    private static readonly XNamespace Ds = "http://www.w3.org/2000/09/xmldsig#";

    public static bool IsAuthorized(int status) => status is 100 or 120 or 150;

    public static IReadOnlyList<FiscalAuthorizationAlert> Alerts(string? response)
    {
        var protocol = Parse(response)?.DescendantsAndSelf(Nfe + "infProt").FirstOrDefault();
        if (protocol is null) return [];
        var result = new List<FiscalAuthorizationAlert>();
        string? code = null;
        foreach (var element in protocol.Elements())
        {
            if (element.Name == Nfe + "cMsg") code = element.Value;
            else if (element.Name == Nfe + "xMsg")
            {
                result.Add(new(code, element.Value));
                code = null;
            }
        }
        return result;
    }

    public static ResultadoFiscal ReadResponse(string signed, string response)
    {
        var invoice = Parse(signed) ?? throw new InvalidOperationException("XML assinado ausente.");
        var returned = Parse(response) ?? throw new InvalidOperationException("Resposta fiscal ausente.");
        var key = invoice.Element(Nfe + "infNFe")?.Attribute("Id")?.Value.Replace("NFe", "", StringComparison.Ordinal);
        var outerStatus = int.TryParse(returned.Element(Nfe + "cStat")?.Value, out var outer) ? outer : 0;
        var protocol = returned.DescendantsAndSelf(Nfe + "infProt").FirstOrDefault();
        var status = int.TryParse(protocol?.Element(Nfe + "cStat")?.Value, out var parsed) ? parsed : outerStatus;
        var cancelled = outerStatus is 101 or 151;
        var authorized = !cancelled && IsAuthorized(status);
        return new ResultadoFiscal
        {
            Status = cancelled ? StatusDocumentoFiscal.Cancelado : authorized ? StatusDocumentoFiscal.Autorizado :
                status is 110 or 301 or 302 or 303 ? StatusDocumentoFiscal.Denegado : StatusDocumentoFiscal.Rejeitado,
            CodigoStatus = cancelled ? outerStatus : status,
            MotivoStatus = (cancelled ? returned.Element(Nfe + "xMotivo")?.Value : protocol?.Element(Nfe + "xMotivo")?.Value) ??
                returned.Element(Nfe + "xMotivo")?.Value ?? "Sem situação fiscal confirmada.",
            ChaveAcesso = key,
            Protocolo = protocol?.Element(Nfe + "nProt")?.Value,
            DhAutorizacao = DateTimeOffset.TryParse(protocol?.Element(Nfe + "dhRecbto")?.Value, out var received) ? received : null,
            XmlAssinado = signed,
            XmlProtocolado = IsAuthorized(status) ? Complete(signed, response, true) : null,
            AlertasAutorizacao = Alerts(response),
            Retentavel = status is 0 or 105 or 108 or 109 or 999
        };
    }

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
            info.Element(Nfe + "cStat")?.Value is not ("100" or "120" or "150"))
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

public sealed record FiscalAuthorizationAlert(string? Codigo, string Mensagem);
