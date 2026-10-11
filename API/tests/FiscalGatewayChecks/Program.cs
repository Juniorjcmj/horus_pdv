using System.Runtime.Loader;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Xml.Linq;
using HORUSPDV_API.Models.Requests;
using HORUSPDV_API.Services.Fiscal;
using HORUSPDV_API.Services.Shared;
using Microsoft.Extensions.FileProviders;
using Microsoft.Extensions.Logging.Abstractions;

if (args.Length != 1) throw new ArgumentException("Informe a pasta da API compilada.");
var output = Path.GetFullPath(args[0]);
AssemblyLoadContext.Default.Resolving += (context, name) =>
{
    var file = Path.Combine(output, name.Name + ".dll");
    return File.Exists(file) ? context.LoadFromAssemblyPath(file) : null;
};
var passed = 0;
void Check(bool ok, string name) { if (!ok) throw new Exception(name); passed++; Console.WriteLine("OK: " + name); }
void Reject(Action action, string name)
{
    try { action(); } catch (InvalidOperationException) { Check(true, name); return; }
    throw new Exception("Não recusou: " + name);
}
using var rsa = RSA.Create(2048);
var certificateRequest = new CertificateRequest("CN=FiscalGatewayFixture", rsa, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
using var certificate = certificateRequest.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddDays(1));
var issuer = new ContextoEmitente
{
    CompanyId="gateway-test", Cnpj="12345678000195", InscricaoEstadual="12345678", RazaoSocial="EMISSOR TESTE", NomeFantasia="TESTE", Crt=1, Cnae="4711302",
    Logradouro="RUA TESTE", Numero="1", Bairro="CENTRO", CodigoMunicipioIbge="3304557", NomeMunicipio="RIO DE JANEIRO", Uf="RJ", Cep="20000000",
    Ambiente=2, CscId="1", Csc="CSC-APENAS-TESTE", CertificadoPfx=certificate.Export(X509ContentType.Pfx,"fixture"), CertificadoSenha="fixture"
};
var item = new ItemFiscal
{
    Numero=1, CodigoProduto="fixture-1", Descricao="PRODUTO TESTE", Gtin="SEM GTIN", Ncm="10063021", Cfop="5102", Origem=0,
    UnidadeComercial="KG", Quantidade=.5m, ValorUnitario=20, ValorTotal=10, Desconto=2, Csosn="102", CstPis="07", CstCofins="07"
};
var now = HorusDateTime.Now;
var provider = new ZeusFiscalProvider(new TestEnvironment { ContentRootPath=output }, NullLogger<ZeusFiscalProvider>.Instance);
var prepared = await provider.PrepararNfceAsync(new EmissaoNfceRequest
{
    Emitente=issuer, Serie=900, NumeroNf=1, TipoEmissao=TipoEmissaoFiscal.ContingenciaOffline,
    DhContingencia=now, JustificativaContingencia="SEM CONEXAO COM INTERNET NA LOJA",
    Itens=[item], Pagamentos=[new() { Tipo="01", Valor=10 }], ValorTroco=2
});
Check(prepared.Status == StatusDocumentoFiscal.ContingenciaPendente && !string.IsNullOrWhiteSpace(prepared.XmlAssinado),
    "Fixture assinada offline sem HTTP e sem certificado real: " + prepared.MotivoStatus);
var xml = prepared.XmlAssinado!;
Console.WriteLine("Endpoint do QR Code da fixture: " + new Uri(prepared.QrCodeUrl!).GetLeftPart(UriPartial.Path));
var sale = new VendaRequest
{
    ClientSaleId="sale-offline-test", EventId="sale-event-test", ReenvioOffline=true, CustomerCpf="", CustomerName="Consumidor", TotalAmount="8,00",
    Items=[new() { ProductCode=item.CodigoProduto, Quantity=item.Quantidade, UnitPrice=item.ValorUnitario, Desconto=2 }],
    Payments=[new() { PaymentType="Dinheiro", Amount=8, CashGiven=10, ChangeAmount=2 }]
};
var imported = FiscalGatewayImport.Validate(xml, sale, issuer, output);
Check(imported.Xml == xml && imported.Chave == prepared.ChaveAcesso && imported.Numero==1 && imported.Serie==900,
    "Reconexão conserva exatamente o XML assinado, chave e numeração");
Check(imported.Ambiente==2 && imported.Justificativa=="SEM CONEXAO COM INTERNET NA LOJA", "Ambiente e contingência conservados");
Reject(() => FiscalGatewayImport.Validate(xml, sale, issuer with { Cnpj="98765432000198" }, output), "CNPJ de outra empresa é recusado");
Reject(() => FiscalGatewayImport.Validate(xml, sale, issuer with { Ambiente=1 }, output), "Homologação não entra como produção");
Reject(() => FiscalGatewayImport.Validate(xml, sale, issuer with { Crt=3 }, output), "Regime diferente é recusado");
var itemCopy = sale.Items[0];
sale.Items=[new() { ProductCode=item.CodigoProduto, Quantity=1, UnitPrice=20, Desconto=2 }];
Reject(() => FiscalGatewayImport.Validate(xml, sale, issuer, output), "XML de outra quantidade não é vinculado à venda");
sale.Items=[itemCopy]; sale.TotalAmount="9,00";
Reject(() => FiscalGatewayImport.Validate(xml, sale, issuer, output), "XML de outro total não é vinculado à venda");
sale.TotalAmount="8,00"; sale.CustomerCpf="12345678909";
Reject(() => FiscalGatewayImport.Validate(xml, sale, issuer, output), "Consumidor diferente é recusado");
sale.CustomerCpf=""; sale.ReenvioOffline=false;
Reject(() => FiscalGatewayImport.Validate(xml, sale, issuer, output), "Importação não é aceita como venda online comum");
sale.ReenvioOffline=true;
Reject(() => FiscalGatewayImport.Validate(xml.Replace("RUA TESTE","RUA ALTERADA"), sale, issuer, output), "Adulteração do XML invalida a assinatura");
XNamespace nfe="http://www.portalfiscal.inf.br/nfe";
var changedQr = XDocument.Parse(xml, LoadOptions.PreserveWhitespace);
changedQr.Root!.Element(nfe+"infNFeSupl")!.Element(nfe+"qrCode")!.Value="https://www4.fazenda.rj.gov.br/consultaNFCe/QRCode?p="+prepared.ChaveAcesso;
Reject(() => FiscalGatewayImport.Validate(changedQr.ToString(SaveOptions.DisableFormatting), sale, issuer, output), "QR incompleto não é importado");
changedQr = XDocument.Parse(xml, LoadOptions.PreserveWhitespace);
var qr = changedQr.Root!.Element(nfe+"infNFeSupl")!.Element(nfe+"qrCode")!;
qr.Value=qr.Value[..^1]+(qr.Value[^1]=='0'?'1':'0');
Reject(() => FiscalGatewayImport.Validate(changedQr.ToString(SaveOptions.DisableFormatting), sale, issuer, output), "Hash QR alterado é recusado");
Reject(() => FiscalGatewayImport.Validate("<!DOCTYPE a [<!ENTITY x SYSTEM 'file:///C:/Windows/win.ini'>]>"+xml, sale, issuer, output), "DTD é recusado antes de qualquer importação");
var legacyHash = HorusPayloadHash.ComputeHash(sale);
sale.XmlNfceContingencia=xml;
Check(HorusPayloadHash.ComputeHash(sale)!=legacyHash, "Idempotência inclui hash do XML quando presente");
var xmlHash=HorusPayloadHash.ComputeHash(sale); sale.XmlNfceContingencia=xml+" ";
Check(HorusPayloadHash.ComputeHash(sale)!=xmlHash, "Trocar XML não reaproveita hash anterior");
sale.XmlNfceContingencia=null;
Check(HorusPayloadHash.ComputeHash(sale)==legacyHash, "Hash de venda legada permanece igual");
Console.WriteLine($"{passed} verificações de reconexão fiscal passaram. Sem banco, SEFAZ ou certificado real.");

sealed class TestEnvironment : IWebHostEnvironment
{
    public string ApplicationName { get; set; }="FiscalGatewayChecks";
    public string EnvironmentName { get; set; }="Testing";
    public string WebRootPath { get; set; }="";
    public IFileProvider WebRootFileProvider { get; set; }=new NullFileProvider();
    public string ContentRootPath { get; set; }="";
    public IFileProvider ContentRootFileProvider { get; set; }=new NullFileProvider();
}
