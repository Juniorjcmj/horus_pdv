/**
 * Arquivo: API/NETCORE/Services/Produtos/NfeXmlParser.cs
 * Objetivo: extrai emitente e itens de XML de NF-e (55) ou NFC-e (65) do fornecedor
 *           para alimentar a importação de cadastro de produtos.
 * Entradas esperadas: recebe o XML bruto (bytes) de uma NF-e autorizada — aceita tanto o
 *           envelope completo &lt;nfeProc&gt; (com protocolo) quanto o &lt;NFe&gt; isolado.
 *
 * Não usa a lib Hercules.NET.NFe.NFCe aqui: aquela é voltada para MONTAR/assinar/transmitir a
 * NFC-e que a própria loja emite na venda (modelo 65), não para ler qualquer NF-e de compra de
 * terceiros. Leitura direta via System.Xml.Linq é mais simples e tolerante a variações entre
 * emissores do que forçar o modelo de objetos de emissão.
 */
using System.Globalization;
using System.Xml.Linq;

namespace HORUSPDV_API.Services.Produtos;

public sealed record NfeParsedEmitente(
    string Cnpj,
    string RazaoSocial,
    string? NomeFantasia,
    string Logradouro,
    string Numero,
    string Bairro,
    string Municipio,
    string Uf,
    string Cep,
    string? Telefone);

public sealed record NfeParsedItem(
    int NumeroItem,
    string CodigoFornecedor,
    string? Gtin,
    string Descricao,
    string Ncm,
    string? Cest,
    string UnidadeComercial,
    decimal Quantidade,
    decimal ValorUnitario);

public sealed record NfeParsedDocument(
    int Modelo,
    string NumeroNota,
    string Serie,
    NfeParsedEmitente Emitente,
    List<NfeParsedItem> Itens,
    string? ChaveAcesso = null,
    string? DataEmissao = null,
    decimal? ValorNota = null);

public sealed record NfeItemFiscalDetail(
    int NumeroItem, string CodigoFornecedor, string? Gtin, string Descricao,
    string Ncm, string? Cest, string? Cfop, string UnidadeComercial,
    decimal Quantidade, decimal ValorUnitario, decimal ValorTotal, decimal Desconto,
    string? Origem, string? CstIcms, string? Csosn,
    decimal? BaseCalculoIcms, decimal? AliquotaIcms, decimal? ValorIcms, decimal? PercentualReducaoBc,
    decimal? BaseCalculoIcmsSt, decimal? AliquotaIcmsSt, decimal? ValorIcmsSt,
    string? CstPis, decimal? BaseCalculoPis, decimal? AliquotaPis, decimal? ValorPis,
    string? CstCofins, decimal? BaseCalculoCofins, decimal? AliquotaCofins, decimal? ValorCofins);

public static class NfeXmlParser
{
    private static readonly XNamespace Ns = "http://www.portalfiscal.inf.br/nfe";

    public static NfeParsedDocument Parse(byte[] xmlBytes)
    {
        XDocument doc;
        try
        {
            using var stream = new MemoryStream(xmlBytes);
            doc = XDocument.Load(stream, LoadOptions.None);
        }
        catch (Exception ex)
        {
            throw new InvalidOperationException("Arquivo enviado não é um XML válido.", ex);
        }

        var infNFe = doc.Descendants(Ns + "infNFe").FirstOrDefault()
            ?? throw new InvalidOperationException(
                "XML não parece ser uma NF-e (elemento infNFe não encontrado). Envie o XML autorizado da nota de compra.");

        var ide = infNFe.Element(Ns + "ide")
            ?? throw new InvalidOperationException("XML sem o bloco <ide> — nota fiscal inválida.");
        var emit = infNFe.Element(Ns + "emit")
            ?? throw new InvalidOperationException("XML sem o bloco <emit> — nota fiscal inválida.");
        if (!int.TryParse(Value(ide, "mod"), out var modelo) || modelo is not (55 or 65))
            throw new InvalidOperationException("Envie o XML de uma NF-e (modelo 55) ou NFC-e (modelo 65).");
        var enderEmit = emit.Element(Ns + "enderEmit");

        var cnpj = Value(emit, "CNPJ");
        if (string.IsNullOrWhiteSpace(cnpj))
        {
            throw new InvalidOperationException("Emitente da nota sem CNPJ — não é possível identificar o fornecedor.");
        }

        var emitente = new NfeParsedEmitente(
            Cnpj: cnpj,
            RazaoSocial: Value(emit, "xNome"),
            NomeFantasia: EmptyToNull(Value(emit, "xFant")),
            Logradouro: Value(enderEmit, "xLgr"),
            Numero: Value(enderEmit, "nro"),
            Bairro: Value(enderEmit, "xBairro"),
            Municipio: Value(enderEmit, "xMun"),
            Uf: Value(enderEmit, "UF"),
            Cep: Value(enderEmit, "CEP"),
            Telefone: EmptyToNull(Value(enderEmit, "fone")));

        var itens = new List<NfeParsedItem>();
        foreach (var det in infNFe.Elements(Ns + "det"))
        {
            var prod = det.Element(Ns + "prod");
            if (prod is null) continue;

            _ = int.TryParse(det.Attribute("nItem")?.Value, out var numeroItem);
            var gtin = Value(prod, "cEAN");

            itens.Add(new NfeParsedItem(
                NumeroItem: numeroItem,
                CodigoFornecedor: Value(prod, "cProd"),
                Gtin: IsGtinValido(gtin) ? gtin : null,
                Descricao: Value(prod, "xProd"),
                Ncm: EmptyToDefault(Value(prod, "NCM"), "00000000"),
                Cest: EmptyToNull(Value(prod, "CEST")),
                UnidadeComercial: EmptyToDefault(Value(prod, "uCom"), "UN").ToUpperInvariant(),
                Quantidade: ParseXmlDecimal(Value(prod, "qCom")),
                ValorUnitario: ParseXmlDecimal(Value(prod, "vUnCom"))));
        }

        if (itens.Count == 0)
        {
            throw new InvalidOperationException("XML não tem nenhum item (<det>) para importar.");
        }

        var id = infNFe.Attribute("Id")?.Value;
        var chave = id?.StartsWith("NFe", StringComparison.Ordinal) == true ? id[3..] : null;
        var protocolo = doc.Descendants(Ns + "chNFe").FirstOrDefault()?.Value.Trim();
        if (!string.IsNullOrWhiteSpace(chave) && !string.IsNullOrWhiteSpace(protocolo) && chave != protocolo)
            throw new InvalidOperationException("Chaves divergentes no XML e no protocolo da nota.");
        var total = Value(infNFe.Element(Ns + "total")?.Element(Ns + "ICMSTot"), "vNF");
        return new NfeParsedDocument(modelo, Value(ide, "nNF"), Value(ide, "serie"), emitente, itens,
            chave ?? EmptyToNull(protocolo ?? ""), EmptyToNull(Value(ide, "dhEmi")) ?? EmptyToNull(Value(ide, "dEmi")),
            decimal.TryParse(total, NumberStyles.Number, CultureInfo.InvariantCulture, out var valor) ? valor : null);
    }

    /// <summary>Extrai dados tributários (ICMS, PIS, COFINS) de cada &lt;det&gt; do XML.</summary>
    public static List<NfeItemFiscalDetail> ParseFiscalDetails(byte[] xmlBytes)
    {
        XDocument doc;
        using (var stream = new MemoryStream(xmlBytes))
            doc = XDocument.Load(stream, LoadOptions.None);

        var infNFe = doc.Descendants(Ns + "infNFe").FirstOrDefault();
        if (infNFe is null) return [];

        var result = new List<NfeItemFiscalDetail>();
        foreach (var det in infNFe.Elements(Ns + "det"))
        {
            _ = int.TryParse(det.Attribute("nItem")?.Value, out var nItem);
            var prod = det.Element(Ns + "prod");
            var imposto = det.Element(Ns + "imposto");

            // ICMS: pode estar em qualquer sub-elemento (ICMS00, ICMS10, ICMS20, ICMS60, ICMSSN102, etc.)
            var icmsGroup = imposto?.Element(Ns + "ICMS")?.Elements().FirstOrDefault();
            // PIS: PISAliq, PISQtde, PISNT, PISOutr
            var pisGroup = imposto?.Element(Ns + "PIS")?.Elements().FirstOrDefault();
            // COFINS: COFINSAliq, COFINSQtde, COFINSNT, COFINSOutr
            var cofinsGroup = imposto?.Element(Ns + "COFINS")?.Elements().FirstOrDefault();

            result.Add(new NfeItemFiscalDetail(
                NumeroItem: nItem,
                CodigoFornecedor: Value(prod, "cProd"),
                Gtin: IsGtinValido(Value(prod, "cEAN")) ? Value(prod, "cEAN") : null,
                Descricao: Value(prod, "xProd"),
                Ncm: EmptyToDefault(Value(prod, "NCM"), "00000000"),
                Cest: EmptyToNull(Value(prod, "CEST")),
                Cfop: EmptyToNull(Value(prod, "CFOP")),
                UnidadeComercial: EmptyToDefault(Value(prod, "uCom"), "UN").ToUpperInvariant(),
                Quantidade: ParseXmlDecimal(Value(prod, "qCom")),
                ValorUnitario: ParseXmlDecimal(Value(prod, "vUnCom")),
                ValorTotal: ParseXmlDecimal(Value(prod, "vProd")),
                Desconto: ParseXmlDecimal(Value(prod, "vDesc")),
                Origem: EmptyToNull(Value(icmsGroup, "orig")),
                CstIcms: EmptyToNull(Value(icmsGroup, "CST")),
                Csosn: EmptyToNull(Value(icmsGroup, "CSOSN")),
                BaseCalculoIcms: ParseNullableDecimal(Value(icmsGroup, "vBC")),
                AliquotaIcms: ParseNullableDecimal(Value(icmsGroup, "pICMS")),
                ValorIcms: ParseNullableDecimal(Value(icmsGroup, "vICMS")),
                PercentualReducaoBc: ParseNullableDecimal(Value(icmsGroup, "pRedBC")),
                BaseCalculoIcmsSt: ParseNullableDecimal(Value(icmsGroup, "vBCST")),
                AliquotaIcmsSt: ParseNullableDecimal(Value(icmsGroup, "pICMSST")),
                ValorIcmsSt: ParseNullableDecimal(Value(icmsGroup, "vICMSST")),
                CstPis: EmptyToNull(Value(pisGroup, "CST")),
                BaseCalculoPis: ParseNullableDecimal(Value(pisGroup, "vBC")),
                AliquotaPis: ParseNullableDecimal(Value(pisGroup, "pPIS")),
                ValorPis: ParseNullableDecimal(Value(pisGroup, "vPIS")),
                CstCofins: EmptyToNull(Value(cofinsGroup, "CST")),
                BaseCalculoCofins: ParseNullableDecimal(Value(cofinsGroup, "vBC")),
                AliquotaCofins: ParseNullableDecimal(Value(cofinsGroup, "pCOFINS")),
                ValorCofins: ParseNullableDecimal(Value(cofinsGroup, "vCOFINS"))));
        }
        return result;
    }

    private static bool IsGtinValido(string value) =>
        !string.IsNullOrWhiteSpace(value) && !string.Equals(value, "SEM GTIN", StringComparison.OrdinalIgnoreCase);

    private static string Value(XElement? parent, string localName) =>
        parent?.Element(Ns + localName)?.Value?.Trim() ?? string.Empty;

    private static string? EmptyToNull(string value) => string.IsNullOrWhiteSpace(value) ? null : value;

    private static string EmptyToDefault(string value, string fallback) =>
        string.IsNullOrWhiteSpace(value) ? fallback : value;

    // Valores numéricos do XML da NF-e usam ponto decimal (formato invariante), nunca vírgula
    // pt-BR — diferente do HorusMoneyFormat usado no restante do contrato HTTP da aplicação.
    private static decimal ParseXmlDecimal(string value) =>
        decimal.TryParse(value, NumberStyles.Number, CultureInfo.InvariantCulture, out var parsed) ? parsed : 0m;

    private static decimal? ParseNullableDecimal(string value) =>
        string.IsNullOrWhiteSpace(value) ? null
        : decimal.TryParse(value, NumberStyles.Number, CultureInfo.InvariantCulture, out var parsed) ? parsed : null;
}
