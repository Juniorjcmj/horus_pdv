/**
 * Arquivo: API/NETCORE/Services/Produtos/NfeXmlParser.cs
 * Objetivo: extrai emitente e itens de um XML de NF-e (nota de compra do fornecedor, modelo 55)
 *           para alimentar a importação de cadastro de produtos.
 * Entradas esperadas: recebe o XML bruto (bytes) de uma NF-e autorizada — aceita tanto o
 *           envelope completo <nfeProc> (com protocolo) quanto o <NFe> isolado.
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
    string NumeroNota,
    string Serie,
    NfeParsedEmitente Emitente,
    List<NfeParsedItem> Itens);

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

        return new NfeParsedDocument(Value(ide, "nNF"), Value(ide, "serie"), emitente, itens);
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
}
