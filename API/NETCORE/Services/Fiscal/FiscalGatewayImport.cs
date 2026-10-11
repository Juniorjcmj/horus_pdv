using System.Globalization;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Security.Cryptography.Xml;
using System.Text;
using System.Xml;
using System.Xml.Linq;
using System.Xml.Schema;
using HORUSPDV_API.Models.Requests;
using HORUSPDV_API.Repositories.DataAccess;
using HORUSPDV_API.Services.Shared;

namespace HORUSPDV_API.Services.Fiscal;

public sealed record GatewayInvoice(int Serie, int Numero, byte Ambiente, string Chave, string Xml,
    string Justificativa, DateTimeOffset DhContingencia, string QrCodeUrl);

/// <summary>Accept an already-issued LAN invoice, never regenerate its key or signature on reconnection.</summary>
public static class FiscalGatewayImport
{
    private static readonly XNamespace Nfe = "http://www.portalfiscal.inf.br/nfe";
    private static readonly XNamespace Ds = SignedXml.XmlDsigNamespaceUrl;

    public static GatewayInvoice Validate(string xml, VendaRequest sale, ContextoEmitente issuer, string contentRoot)
    {
        try { return ValidateCore(xml, sale, issuer, contentRoot); }
        catch (Exception ex) when (ex is XmlException or XmlSchemaException or FormatException or CryptographicException or OverflowException or FiscalConfigurationException)
        { throw new InvalidOperationException("O XML original do Gateway não passou na validação fiscal. Preserve a venda e a nota para revisão na administração.", ex); }
    }

    private static GatewayInvoice ValidateCore(string xml, VendaRequest sale, ContextoEmitente issuer, string contentRoot)
    {
        void Require(bool condition, string message)
        { if (!condition) throw new InvalidOperationException("Nota do Gateway: " + message); }
        Require(sale.ReenvioOffline && !string.IsNullOrWhiteSpace(sale.ClientSaleId) && !string.IsNullOrWhiteSpace(sale.EventId),
            "a importação exige a identificação original da venda offline.");
        Require(xml.Length <= 4_000_000, "XML acima do tamanho permitido.");
        using var input = new StringReader(xml);
        using var reader = XmlReader.Create(input, new XmlReaderSettings
        { DtdProcessing = DtdProcessing.Prohibit, XmlResolver = null, MaxCharactersInDocument = 4_000_000 });
        var document = XDocument.Load(reader, LoadOptions.PreserveWhitespace);
        var root = document.Root;
        Require(root?.Name == Nfe + "NFe", "envie a NFC-e original assinada, sem substituir pelo protocolo.");
        var info = root!.Element(Nfe + "infNFe");
        Require(info is not null && root.Elements(Nfe + "infNFe").Count() == 1 && root.Elements(Ds + "Signature").Count() == 1,
            "estrutura de nota e assinatura inválida.");
        var key = info!.Attribute("Id")?.Value;
        Require(key is { Length: 47 } && key.StartsWith("NFe", StringComparison.Ordinal) && key[3..].All(char.IsAsciiDigit), "chave inválida.");
        key = key![3..];
        Require(document.Descendants().Attributes("Id").Select(a => a.Value).Distinct().Count() == document.Descendants().Attributes("Id").Count(),
            "identificadores duplicados no XML.");
        var ide = info.Element(Nfe + "ide")!;
        var emit = info.Element(Nfe + "emit")!;
        Require(ide is not null && emit is not null, "dados do emitente ou emissão ausentes.");
        string Value(XElement node, string name) => node.Element(Nfe + name)?.Value ?? "";
        decimal Number(XElement node, string name) => decimal.Parse(Value(node, name), CultureInfo.InvariantCulture);
        Require(Value(ide!, "mod") == "65" && Value(ide!, "tpEmis") == "9", "somente NFC-e em contingência offline pode ser importada.");
        Require(Value(ide!, "cUF") == "33" && issuer.Uf.Equals("RJ", StringComparison.OrdinalIgnoreCase), "UF incompatível com a loja.");
        Require(Value(emit!, "CNPJ") == issuer.Cnpj && key[6..20] == issuer.Cnpj, "CNPJ diferente da empresa autenticada.");
        Require(Value(emit!, "CRT") == issuer.Crt.ToString(CultureInfo.InvariantCulture), "regime tributário diferente da empresa.");
        var environment = byte.Parse(Value(ide!, "tpAmb"), CultureInfo.InvariantCulture);
        var serie = int.Parse(Value(ide!, "serie"), CultureInfo.InvariantCulture);
        var number = int.Parse(Value(ide!, "nNF"), CultureInfo.InvariantCulture);
        Require(environment == issuer.Ambiente, "ambiente diferente da empresa.");
        Require(key[..2] == "33" && key[20..22] == "65" && key[22..25] == serie.ToString("D3") && key[25..34] == number.ToString("D9")
            && key[34] == '9' && key[35..43] == Value(ide!, "cNF") && key[43..] == Value(ide!, "cDV"), "chave não corresponde à emissão.");
        var sum = 0;
        for (var i = 42; i >= 0; i--) sum += (key[i] - '0') * (2 + (42 - i) % 8);
        var digit = 11 - sum % 11; if (digit >= 10) digit = 0;
        Require(key[43] - '0' == digit, "verificador da chave inválido.");
        var issued = DateTimeOffset.Parse(Value(ide!, "dhEmi"), CultureInfo.InvariantCulture);
        var contingency = DateTimeOffset.Parse(Value(ide!, "dhCont"), CultureInfo.InvariantCulture);
        Require(key[2..6] == HorusDateTime.ToBrasilia(issued).ToString("yyMM"), "data incompatível com a chave.");
        var justification = Value(ide!, "xJust");
        Require(justification.Length is >= 15 and <= 256, "justificativa de contingência inválida.");

        var schemas = new XmlSchemaSet { XmlResolver = new XmlUrlResolver() };
        schemas.Add(Nfe.NamespaceName, Path.Combine(contentRoot, "DataBase", "Schemas", "nfe_v4.00.xsd"));
        document.Validate(schemas, (_, issue) => throw new InvalidOperationException("Nota do Gateway: XML incompatível com o schema fiscal. " + issue.Message));
        var dom = new XmlDocument { PreserveWhitespace = true, XmlResolver = null };
        using var signatureInput = new StringReader(xml);
        using var signatureReader = XmlReader.Create(signatureInput, new XmlReaderSettings { DtdProcessing = DtdProcessing.Prohibit, XmlResolver = null });
        dom.Load(signatureReader);
        var signature = new SignedXml(dom);
        signature.LoadXml((XmlElement)dom.GetElementsByTagName("Signature", Ds.NamespaceName)[0]!);
        Require(signature.SignedInfo?.References.Count == 1 && ((Reference)signature.SignedInfo.References[0]!).Uri == "#NFe" + key,
            "assinatura não referencia exclusivamente a nota original.");
        using var certificate = new X509Certificate2(issuer.CertificadoPfx, issuer.CertificadoSenha, X509KeyStorageFlags.EphemeralKeySet);
        Require(issued.UtcDateTime >= certificate.NotBefore.ToUniversalTime() && issued.UtcDateTime <= certificate.NotAfter.ToUniversalTime(),
            "certificado configurado não era válido na data da emissão. Preserve o XML para revisão.");
        Require(signature.CheckSignature(certificate, true), "assinatura inválida ou certificado diferente do configurado na empresa.");

        var invoiceItems = info.Elements(Nfe + "det").Select(d => d.Element(Nfe + "prod")!).ToArray();
        Require(invoiceItems.Length == sale.Items.Count, "quantidade de itens diferente da venda.");
        var remaining = sale.Items.ToList();
        foreach (var product in invoiceItems)
        {
            var matching = remaining.FindIndex(i => i.ProductCode == Value(product, "cProd") && i.Quantity == Number(product, "qCom")
                && i.UnitPrice == Number(product, "vUnCom") && FiscalTaxRules.Money(i.Desconto) == (product.Element(Nfe + "vDesc") is null ? 0 : Number(product, "vDesc")));
            Require(matching >= 0, "itens, quantidades, preços ou descontos diferentes da venda.");
            remaining.RemoveAt(matching);
        }
        var total = info.Element(Nfe + "total")!.Element(Nfe + "ICMSTot")!;
        var amount = HorusMoneyFormat.ParseDecimalStrict(sale.TotalAmount, nameof(sale.TotalAmount));
        Require(Number(total, "vNF") == amount, "total diferente da venda.");
        var targetDocument = FiscalReferenceTables.Digits(sale.CustomerCpf);
        var dest = info.Element(Nfe + "dest");
        var xmlCustomer = dest?.Element(Nfe + "CPF")?.Value ?? dest?.Element(Nfe + "CNPJ")?.Value ?? "";
        Require(xmlCustomer == targetDocument, "documento do consumidor diferente da venda.");
        var payments = info.Element(Nfe + "pag")!;
        var paid = payments.Elements(Nfe + "detPag").Sum(p => Number(p, "vPag"));
        var change = payments.Element(Nfe + "vTroco") is null ? 0 : Number(payments, "vTroco");
        Require(FiscalTaxRules.Money(paid - amount) == change && change >= 0, "pagamentos e troco não conferem.");
        if (sale.Payments.Count > 0)
        {
            Require(FiscalTaxRules.Money(sale.Payments.Sum(p => p.Amount)) == amount
                && FiscalTaxRules.Money(sale.Payments.Sum(p => p.ChangeAmount)) == change, "pagamentos diferentes da venda.");
        }
        string PaymentCode(string value)
        {
            var normalized = value.Trim().ToLowerInvariant();
            if (normalized.Contains("fiado") || normalized.Contains("crediario") || normalized.Contains("crediário")) return "05";
            if (normalized.Contains("pix")) return "17";
            if (normalized.Contains("debit") || normalized.Contains("débit")) return "04";
            if (normalized.Contains("credit") || normalized.Contains("crédit")) return "03";
            return "01";
        }
        var expectedPayments = sale.Payments.Count > 0
            ? sale.Payments.Select(p => new PagamentoFiscal
            {
                Tipo = PaymentCode(p.PaymentType),
                Valor = FiscalPaymentRules.DeclaredAmount(new VendaPagamentoAD
                { PaymentType = p.PaymentType, Amount = p.Amount, CashGiven = p.CashGiven, ChangeAmount = p.ChangeAmount })
            }).ToList()
            : new List<PagamentoFiscal> { new() { Tipo = PaymentCode(sale.PaymentType), Valor = amount } };
        var expectedByType = expectedPayments.GroupBy(p => p.Tipo).ToDictionary(g => g.Key, g => FiscalTaxRules.Money(g.Sum(p => p.Valor)));
        var actualByType = payments.Elements(Nfe + "detPag").GroupBy(p => Value(p, "tPag"))
            .ToDictionary(g => g.Key, g => FiscalTaxRules.Money(g.Sum(p => Number(p, "vPag"))));
        Require(expectedByType.Count == actualByType.Count && expectedByType.All(p => actualByType.TryGetValue(p.Key, out var value) && value == p.Value),
            "formas ou valores recebidos diferentes dos pagamentos da venda.");
        var qr = root.Element(Nfe + "infNFeSupl")?.Element(Nfe + "qrCode")?.Value ?? "";
        Require(Uri.TryCreate(qr, UriKind.Absolute, out var qrUri) && qrUri.Scheme is "https" or "http"
            && ((qrUri.Host.Equals("www.fazenda.rj.gov.br", StringComparison.OrdinalIgnoreCase)
                 && qrUri.AbsolutePath.Equals("/nfce/qrcode", StringComparison.OrdinalIgnoreCase))
                || ((qrUri.Host.Equals("www4.fazenda.rj.gov.br", StringComparison.OrdinalIgnoreCase)
                     || qrUri.Host.Equals("consultadfe.fazenda.rj.gov.br", StringComparison.OrdinalIgnoreCase))
                    && qrUri.AbsolutePath.Equals("/consultaNFCe/QRCode", StringComparison.OrdinalIgnoreCase)))
            && string.IsNullOrEmpty(qrUri.UserInfo), "endereço de QR Code não é oficial do RJ.");
        var parameter = qrUri!.Query.TrimStart('?').Split('&').FirstOrDefault(p => p.StartsWith("p=", StringComparison.Ordinal));
        var parts = Uri.UnescapeDataString(parameter?[2..] ?? "").Split('|');
        var digest = root.Descendants(Ds + "DigestValue").Single().Value;
        Require(parts.Length == 8, "QR Code de contingência incompleto.");
        var digestHex = Convert.ToHexString(Encoding.UTF8.GetBytes(digest));
        Require(parts[0] == key && parts[1] == "2" && parts[2] == environment.ToString() && parts[3] == issued.Day.ToString("D2")
            && decimal.TryParse(parts[4], CultureInfo.InvariantCulture, out var qrValue) && qrValue == amount
            && parts[5].Equals(digestHex, StringComparison.OrdinalIgnoreCase)
            && int.TryParse(parts[6], out var token) && token == int.Parse(issuer.CscId, CultureInfo.InvariantCulture), "parâmetros do QR Code não correspondem à nota.");
        var expectedHash = Convert.ToHexString(SHA1.HashData(Encoding.UTF8.GetBytes(string.Join('|', parts.Take(7)) + issuer.Csc)));
        Require(parts[7].Equals(expectedHash, StringComparison.OrdinalIgnoreCase), "hash do QR Code inválido.");
        return new(serie, number, environment, key, xml, justification, contingency, qr);
    }
}
