using System.Text;
using HORUSPDV_API.Services.Produtos;

var passed = 0;
void Check(bool condition, string name) { if (!condition) throw new Exception(name); Console.WriteLine("OK " + name); passed++; }
void Reject(Action action, string text, string name) {
    try { action(); } catch (InvalidOperationException error) { Check(error.Message.Contains(text), name); return; }
    throw new Exception("Não rejeitou: " + name);
}
byte[] Xml(int model, bool envelope = true) {
    var nfe = $"""
    <NFe xmlns="http://www.portalfiscal.inf.br/nfe"><infNFe><ide><mod>{model}</mod><nNF>10489</nNF><serie>116</serie></ide>
    <emit><CNPJ>36716865000104</CNPJ><xNome>FORNECEDOR TESTE</xNome><enderEmit><xLgr>AV TESTE</xLgr><nro>1</nro><xMun>Rio de Janeiro</xMun><UF>RJ</UF></enderEmit></emit>
    <det nItem="1"><prod><cProd>7896035210018</cProd><cEAN>7896035210018</cEAN><xProd>SAL GROSSO</xProd><NCM>25010020</NCM><uCom>UN</uCom><qCom>4.0000</qCom><vUnCom>7.4500</vUnCom></prod></det>
    <det nItem="2"><prod><cProd>1857</cProd><cEAN>SEM GTIN</cEAN><xProd>PRODUTO POR PESO</xProd><NCM>16010000</NCM><uCom>KG</uCom><qCom>0.4520</qCom><vUnCom>19.9900</vUnCom></prod></det>
    </infNFe></NFe>
    """;
    return Encoding.UTF8.GetBytes(envelope ? $"<nfeProc xmlns=\"http://www.portalfiscal.inf.br/nfe\">{nfe}<protNFe /></nfeProc>" : nfe);
}
foreach (var model in new[] { 55, 65 }) {
    var document = NfeXmlParser.Parse(Xml(model));
    Check(document.Modelo == model && document.NumeroNota == "10489" && document.Serie == "116", $"XML modelo {model}, número e série");
    Check(document.Emitente.Cnpj == "36716865000104" && document.Itens.Count == 2, $"fornecedor e itens modelo {model}");
    Check(document.Itens[0].Quantidade == 4 && document.Itens[0].ValorUnitario == 7.45m && document.Itens[0].Gtin == "7896035210018", $"custo, quantidade e barras modelo {model}");
    Check(document.Itens[1].Quantidade == 0.452m && document.Itens[1].UnidadeComercial == "KG" && document.Itens[1].Gtin is null, $"peso e SEM GTIN modelo {model}");
}
Check(NfeXmlParser.Parse(Xml(65, false)).Modelo == 65, "NFC-e sem envelope mantém compatibilidade");
Reject(() => NfeXmlParser.Parse(Xml(57)), "modelo 65", "XML de outro modelo rejeitado");
Reject(() => NfeXmlParser.Parse(Encoding.UTF8.GetBytes("<invalido>")), "XML válido", "XML corrompido rejeitado");
Reject(() => NfeAccessKey.ValidateDistributionKey("33261036716865000104651160000104891000214277"), "Digitar itens do cupom", "chave do cliente segue fluxo NFC-e antes da consulta/certificado");
Reject(() => NfeAccessKey.ValidateDistributionKey("33261036716865000104651160000104891000214278"), "dígito verificador", "dígito inválido rejeitado");
Reject(() => NfeAccessKey.ValidateDistributionKey("123"), "44 dígitos", "chave incompleta rejeitada");
var prefix = "3326103671686500010455116000010489100021427";
var sum = 0;
for (int i = 42, weight = 2; i >= 0; i--, weight = weight == 9 ? 2 : weight + 1) sum += (prefix[i] - '0') * weight;
var remainder = sum % 11;
var key55 = prefix + (remainder < 2 ? 0 : 11 - remainder);
Check(NfeAccessKey.ValidateDistributionKey(key55) == key55, "NF-e 55 continua na distribuição nacional");
Console.WriteLine($"{passed} verificações passaram; nenhum dado de loja foi acessado.");
