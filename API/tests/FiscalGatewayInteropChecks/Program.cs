using System.Runtime.Loader;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Security.Cryptography.Xml;
using System.Xml;
using System.Xml.Linq;
using HORUSPDV_API.Models.Requests;
using HORUSPDV_API.Services.Fiscal;
using HorusGateway.Configuration;
using HorusGateway.Models;
using HorusGateway.Services;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Options;
using NFe.Utils.NFe;

if (args.Length != 2) throw new ArgumentException("Informe as pastas compiladas da API e do Gateway.");
var apiOutput = Path.GetFullPath(args[0]);
var gatewayOutput = Path.GetFullPath(args[1]);
AssemblyLoadContext.Default.Resolving += (context, name) =>
{
    foreach (var folder in new[] { apiOutput, gatewayOutput })
    {
        var file = Path.Combine(folder, name.Name + ".dll");
        if (File.Exists(file)) return context.LoadFromAssemblyPath(file);
    }
    return null;
};
var passed = 0;
void Check(bool ok, string name)
{
    if (!ok) throw new Exception(name);
    passed++;
    Console.WriteLine("OK: " + name);
}
void Reject(Action action, string name)
{
    try { action(); }
    catch (InvalidOperationException) { Check(true, name); return; }
    throw new Exception("Não recusou: " + name);
}
using var rsa = RSA.Create(2048);
var certificateRequest = new CertificateRequest("CN=GATEWAY INTEROP TEST ONLY, C=BR", rsa, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
using var certificate = certificateRequest.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddDays(1));
var pfx = certificate.Export(X509ContentType.Pfx, "fixture-only");
XNamespace nfe = "http://www.portalfiscal.inf.br/nfe";

foreach (var scenario in new[] { (Environment: (byte)2, Customer: "52998224725"), (Environment: (byte)1, Customer: "12345678000195") })
{
    var options = new GatewayOptions
    {
        CompanyId = "gateway-interop-test", StoreId = "test-store", AmbienteFiscal = scenario.Environment,
        EmitenteCnpj = "12345678000195", EmitenteCrt = 1, EmitenteRazaoSocial = "EMITENTE TESTE",
        EmitenteInscricaoEstadual = "12345678", EmitenteLogradouro = "RUA DO TESTE", EmitenteNumero = "42",
        EmitenteBairro = "CENTRO", EmitenteMunicipio = "RIO DE JANEIRO", EmitenteCodigoMunicipioIbge = "3304557", EmitenteCep = "20040002",
        CscId = "000001", Csc = "CSC-APENAS-TESTE", CertificadoPfxBase64 = Convert.ToBase64String(pfx), CertificadoSenha = "fixture-only"
    };
    var configured = Options.Create(options);
    var signer = new LocalFiscalSigner(new GatewayCertificateService(configured, NullLogger<GatewayCertificateService>.Instance),
        configured, NullLogger<LocalFiscalSigner>.Instance);
    var request = new LocalNfceContingenciaRequest
    {
        VendaId = "sale-offline-interop-" + scenario.Environment, TerminalId = "test-terminal", CustomerCpf = scenario.Customer,
        CustomerName = "CONSUMIDOR TESTE", Justificativa = "SEM CONEXAO COM INTERNET NA LOJA",
        Itens =
        [
            new() { Numero = 1, CodigoProduto = "fixture-weight", Descricao = "PRODUTO POR PESO", Ncm = "10063021", Cfop = "5102",
                Gtin = "SEM GTIN", UnidadeComercial = "KG", OrigemMercadoria = 0, CsosnIcms = "102", CstPis = "07", CstCofins = "07",
                Quantidade = .5m, ValorUnitario = 20m, ValorTotal = 10m, Desconto = 2m },
            new() { Numero = 2, CodigoProduto = "fixture-units", Descricao = "PRODUTO POR UNIDADE", Ncm = "21069090", Cfop = "5102",
                Gtin = "SEM GTIN", UnidadeComercial = "UN", OrigemMercadoria = 0, CsosnIcms = "102", CstPis = "07", CstCofins = "07",
                Quantidade = 2m, ValorUnitario = 3m, ValorTotal = 6m }
        ],
        Pagamentos = [new() { Tipo = "01", Valor = 15m }, new() { Tipo = "17", Valor = 2m }], ValorTroco = 3m
    };
    var emitted = signer.EmitirContingencia(request, 7, 900, DateTimeOffset.UtcNow);
    Check(emitted.Success && !string.IsNullOrWhiteSpace(emitted.XmlAssinado), "Gateway real assina sem transmissão: " + emitted.Message);
    // This is the deserialization/serialization performed by Zeus before the preserved XML is sent.
    // Its canonical signed subtree must still verify without signing again.
    var roundTripped = new NFe.Classes.NFe().CarregarDeXmlString(emitted.XmlAssinado).ObterXmlString();
    var roundTripDocument = new XmlDocument { PreserveWhitespace = true, XmlResolver = null };
    roundTripDocument.LoadXml(roundTripped);
    var roundTripSignature = new SignedXml(roundTripDocument);
    roundTripSignature.LoadXml((XmlElement)roundTripDocument.GetElementsByTagName("Signature", SignedXml.XmlDsigNamespaceUrl)[0]!);
    Check(roundTripSignature.CheckSignature(certificate, true), "Zeus preserva a assinatura ao carregar o XML original do Gateway");
    var issuer = new ContextoEmitente
    {
        CompanyId = options.CompanyId, Cnpj = options.EmitenteCnpj, InscricaoEstadual = options.EmitenteInscricaoEstadual,
        RazaoSocial = options.EmitenteRazaoSocial, NomeFantasia = "TESTE", Crt = 1, Cnae = "4711302", Uf = "RJ",
        Logradouro = options.EmitenteLogradouro, Numero = options.EmitenteNumero, Bairro = options.EmitenteBairro,
        CodigoMunicipioIbge = options.EmitenteCodigoMunicipioIbge, NomeMunicipio = options.EmitenteMunicipio, Cep = options.EmitenteCep,
        Ambiente = scenario.Environment, CscId = options.CscId, Csc = options.Csc, CertificadoPfx = pfx, CertificadoSenha = options.CertificadoSenha
    };
    var sale = new VendaRequest
    {
        ClientSaleId = request.VendaId, EventId = "event-" + request.VendaId, ReenvioOffline = true, CustomerCpf = scenario.Customer,
        CustomerName = request.CustomerName, TotalAmount = "14,00", PaymentType = "Dinheiro",
        Items = request.Itens.Select(i => new VendaItemRequest { ProductCode = i.CodigoProduto, Quantity = i.Quantidade,
            UnitPrice = i.ValorUnitario, Desconto = i.Desconto }).Reverse().ToList(),
        Payments = [new() { PaymentType = "Dinheiro", Amount = 12m, CashGiven = 15m, ChangeAmount = 3m }, new() { PaymentType = "PIX", Amount = 2m }]
    };
    var original = FiscalGatewayImport.Validate(emitted.XmlAssinado, sale, issuer, AppContext.BaseDirectory);
    Check(original.Xml == emitted.XmlAssinado && original.Chave == emitted.ChaveAcesso && original.Serie == 900 && original.Numero == 7,
        "API importa o XML do Gateway sem alterar assinatura, chave, série ou número");
    var document = XDocument.Parse(original.Xml);
    Check(original.Ambiente == scenario.Environment && original.QrCodeUrl == emitted.QrCodeUrl &&
        original.DhContingencia == DateTimeOffset.Parse(document.Descendants(nfe + "dhCont").Single().Value),
        "API preserva ambiente, QR original e data de contingência do XML");
    Check(document.Descendants(nfe + "vNF").Single().Value == "14.00" && document.Descendants(nfe + "vTroco").Single().Value == "3.00",
        "Quantidade fracionada, desconto, pagamento misto e troco mantidos");
    Check(document.Descendants(nfe + (scenario.Customer.Length == 11 ? "CPF" : "CNPJ")).Any(e => e.Value == scenario.Customer),
        "Documento original do consumidor preservado");
    Reject(() => FiscalGatewayImport.Validate(emitted.XmlAssinado, sale, issuer with { Cnpj = "98765432000198" }, AppContext.BaseDirectory), "CNPJ de outra empresa recusado");
    Reject(() => FiscalGatewayImport.Validate(emitted.XmlAssinado, sale, issuer with { Ambiente = (byte)(scenario.Environment == 1 ? 2 : 1) }, AppContext.BaseDirectory), "Ambiente de outra configuração recusado");
    sale.CustomerCpf = "11144477735";
    Reject(() => FiscalGatewayImport.Validate(emitted.XmlAssinado, sale, issuer, AppContext.BaseDirectory), "CPF divergente recusado");
    sale.CustomerCpf = scenario.Customer;
    sale.Items[0].Quantity += 1m;
    Reject(() => FiscalGatewayImport.Validate(emitted.XmlAssinado, sale, issuer, AppContext.BaseDirectory), "Quantidade divergente recusada");
    sale.Items[0].Quantity -= 1m;
    sale.Items[1].Desconto = 1m;
    Reject(() => FiscalGatewayImport.Validate(emitted.XmlAssinado, sale, issuer, AppContext.BaseDirectory), "Desconto divergente recusado");
    sale.Items[1].Desconto = 2m;
    sale.Payments[0].ChangeAmount = 2m;
    Reject(() => FiscalGatewayImport.Validate(emitted.XmlAssinado, sale, issuer, AppContext.BaseDirectory), "Troco divergente recusado");
    sale.Payments[0].ChangeAmount = 3m;
    sale.Payments[1].PaymentType = "Crédito";
    Reject(() => FiscalGatewayImport.Validate(emitted.XmlAssinado, sale, issuer, AppContext.BaseDirectory), "PIX do XML não é vinculado a parcela em crédito");
    sale.Payments[1].PaymentType = "PIX";
    sale.Payments[0].CashGiven = 16m;
    Reject(() => FiscalGatewayImport.Validate(emitted.XmlAssinado, sale, issuer, AppContext.BaseDirectory), "Dinheiro recebido divergente recusado");
    sale.Payments[0].CashGiven = 14m;
    sale.Payments[0].Amount = 11m;
    sale.Payments[1].Amount = 3m;
    Reject(() => FiscalGatewayImport.Validate(emitted.XmlAssinado, sale, issuer, AppContext.BaseDirectory), "Parcelas diferentes não passam apenas por manterem a mesma soma e troco");
}
Console.WriteLine($"{passed} verificações Gateway/API passaram. Sem banco, rede, SEFAZ ou certificado real.");
