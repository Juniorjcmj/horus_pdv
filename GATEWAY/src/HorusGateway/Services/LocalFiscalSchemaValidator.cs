using System.Xml;
using System.Xml.Schema;

namespace HorusGateway.Services;

/// <summary>Fail closed when the official package is absent or the signed NFC-e has an invalid layout.</summary>
public static class LocalFiscalSchemaValidator
{
    private static readonly Lazy<XmlSchemaSet> Schemas = new(() =>
    {
        var path = Path.Combine(AppContext.BaseDirectory, "DataBase", "Schemas", "nfe_v4.00.xsd");
        if (!File.Exists(path)) throw new InvalidOperationException("Schemas oficiais da NFC-e ausentes no Gateway. Reinstale o pacote completo.");
        var schemas = new XmlSchemaSet { XmlResolver = new XmlUrlResolver() };
        schemas.Add("http://www.portalfiscal.inf.br/nfe", path); schemas.Compile(); return schemas;
    });
    public static void Validate(string xml)
    {
        var settings = new XmlReaderSettings { DtdProcessing = DtdProcessing.Prohibit, XmlResolver = null,
            ValidationType = ValidationType.Schema, Schemas = Schemas.Value, MaxCharactersInDocument = 4_000_000 };
        settings.ValidationEventHandler += (_, e) => throw new InvalidOperationException("XML da NFC-e inválido no schema oficial: " + e.Message);
        using var reader = XmlReader.Create(new StringReader(xml), settings); while (reader.Read()) { }
    }
}
