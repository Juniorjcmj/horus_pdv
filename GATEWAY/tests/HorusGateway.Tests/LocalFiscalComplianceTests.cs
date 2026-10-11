using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Security.Cryptography.Xml;
using System.Xml;
using System.Xml.Linq;
using HorusGateway.Configuration;
using HorusGateway.Controllers;
using HorusGateway.Data;
using HorusGateway.Models;
using HorusGateway.Services;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Data.Sqlite;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Options;
using Xunit;

namespace HorusGateway.Tests;

public sealed class LocalFiscalComplianceTests
{
    private static readonly XNamespace Ns = "http://www.portalfiscal.inf.br/nfe";
    private static GatewayOptions Configuration()
    {
        using var rsa = RSA.Create(2048);
        var req = new CertificateRequest("CN=FISCAL TEST ONLY, C=BR", rsa, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
        using var cert = req.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddDays(1));
        return new GatewayOptions
        {
            CompanyId = "fiscal-test-company", StoreId = "test-store", RequireTerminalAuth = true,
            CertificadoPfxBase64 = Convert.ToBase64String(cert.Export(X509ContentType.Pfx, "test-only")), CertificadoSenha = "test-only",
            EmitenteCnpj = "12345678000195", EmitenteRazaoSocial = "EMITENTE DE TESTE", EmitenteInscricaoEstadual = "12345678",
            EmitenteLogradouro = "RUA DO TESTE", EmitenteNumero = "42", EmitenteBairro = "BAIRRO TESTE",
            EmitenteMunicipio = "RIO DE JANEIRO", EmitenteCodigoMunicipioIbge = "3304557", EmitenteCep = "20040002",
            EmitenteCrt = 1, AmbienteFiscal = 2, Csc = "SEU-CODIGO-CSC-CONTRIBUINTE-36-CARACTERES", CscId = "000001"
        };
    }
    private static LocalFiscalSigner Signer(GatewayOptions cfg)
    {
        var opts = Options.Create(cfg);
        return new(new(opts, NullLogger<GatewayCertificateService>.Instance), opts, NullLogger<LocalFiscalSigner>.Instance);
    }
    private static LocalNfceContingenciaRequest Sale() => new()
    {
        VendaId = "stable-client-sale", TerminalId = "test-terminal", CustomerCpf = "52998224725",
        Itens = [new() { Numero = 1, CodigoProduto = "PROD1", Descricao = "PRODUTO REAL", Ncm = "21069090", Cfop = "5102",
            UnidadeComercial = "UN", UnidadeTributavel = "UN", Gtin = "SEM GTIN", OrigemMercadoria = 0,
            CsosnIcms = "102", CstPis = "49", CstCofins = "49", Quantidade = 2m, ValorUnitario = 10.50m, ValorTotal = 21m }],
        Pagamentos = [new() { Tipo = "01", Valor = 21m }]
    };
    private static LocalNfceContingenciaResponse Emit(GatewayOptions cfg, LocalNfceContingenciaRequest sale) =>
        Signer(cfg).EmitirContingencia(sale, 1, 900, new DateTimeOffset(2026, 10, 1, 1, 30, 0, TimeSpan.Zero));

    [Fact]
    public void QrOffline_ReproducesOfficialManualExample()
    {
        var result = LocalFiscalSigner.GerarQrCodeV2("28170800156225000131650110000151349562040824", "yzGYhUx1/XYYzksWB+fPR3Qc50c=",
            new(2017, 8, 2, 12, 0, 0, TimeSpan.FromHours(-3)), 60.90m, 1, "000001", "SEU-CODIGO-CSC-CONTRIBUINTE-36-CARACTERES");
        Assert.EndsWith("|2|1|02|60.90|797a4759685578312f5859597a6b7357422b6650523351633530633d|1|4615A93BB0D7C4E780F8D30EE77EDD5BA55C7D66", result);
        Assert.Equal(8, result[(result.IndexOf("?p=", StringComparison.Ordinal) + 3)..].Split('|').Length);
    }

    [Fact]
    public void SignedDocument_PassesOfficialSchema_SignatureAndCivilDateRemainConsistent()
    {
        var cfg = Configuration(); var response = Emit(cfg, Sale());
        Assert.True(response.Success, response.Message);
        LocalFiscalSchemaValidator.Validate(response.XmlDocumento);
        var document = XDocument.Parse(response.XmlDocumento);
        Assert.Equal(new[] { "infNFe", "infNFeSupl", "Signature" }, document.Root!.Elements().Select(e => e.Name.LocalName));
        Assert.Equal("2609", response.ChaveAcesso[2..6]);
        Assert.Contains("2026-09-30T22:30:00-03:00", response.XmlDocumento);
        var qr = response.QrCodeUrl[(response.QrCodeUrl.IndexOf("?p=", StringComparison.Ordinal) + 3)..].Split('|');
        Assert.Equal("30", qr[3]); Assert.Equal("21.00", qr[4]); Assert.Equal(56, qr[5].Length); Assert.Equal("1", qr[6]);
        Assert.Equal("RUA DO TESTE", document.Descendants(Ns + "xLgr").Single().Value);
        Assert.Equal("42", document.Descendants(Ns + "nro").Single().Value);
        Assert.Equal("49", document.Descendants(Ns + "PISOutr").Single().Element(Ns + "CST")!.Value);
        Assert.Equal(response.XmlAssinado, response.XmlDocumento);
        var xml = new XmlDocument { PreserveWhitespace = true }; xml.LoadXml(response.XmlDocumento);
        var signed = new SignedXml(xml); signed.LoadXml((XmlElement)xml.GetElementsByTagName("Signature", SignedXml.XmlDsigNamespaceUrl)[0]!);
        using var certificate = new X509Certificate2(Convert.FromBase64String(cfg.CertificadoPfxBase64), "test-only", X509KeyStorageFlags.EphemeralKeySet);
        Assert.True(signed.CheckSignature(certificate, true));
        var invalid = XDocument.Parse(response.XmlDocumento); invalid.Descendants(Ns + "xLgr").Single().Remove();
        Assert.Throws<InvalidOperationException>(() => LocalFiscalSchemaValidator.Validate(invalid.ToString(SaveOptions.DisableFormatting)));
    }

    [Theory]
    [InlineData("ncm", "NCM")]
    [InlineData("origin", "origem")]
    [InlineData("csosn", "CSOSN")]
    [InlineData("pis", "PIS")]
    [InlineData("gtin", "GTIN")]
    [InlineData("csc", "CSC")]
    [InlineData("address", "emitente")]
    [InlineData("payments", "pagamentos")]
    public void MissingFiscalData_FailsWithoutInventingValues(string field, string expected)
    {
        var cfg = Configuration(); var sale = Sale();
        switch (field)
        {
            case "ncm": sale.Itens[0].Ncm = "00000000"; break;
            case "origin": sale.Itens[0].OrigemMercadoria = null; break;
            case "csosn": sale.Itens[0].CsosnIcms = null; break;
            case "pis": sale.Itens[0].CstPis = "01"; break;
            case "gtin": sale.Itens[0].Gtin = ""; break;
            case "csc": cfg.Csc = ""; break;
            case "address": cfg.EmitenteLogradouro = ""; break;
            case "payments": sale.Pagamentos.Clear(); break;
        }
        var response = Emit(cfg, sale);
        Assert.False(response.Success); Assert.Contains(expected, response.Message, StringComparison.OrdinalIgnoreCase); Assert.Empty(response.XmlDocumento);
    }

    [Fact]
    public void CashChange_UsesReceivedGrossAmount_AndRejectsInconsistentPayments()
    {
        var cfg = Configuration(); var sale = Sale(); sale.Pagamentos[0].Valor = 30m; sale.ValorTroco = 9m;
        var response = Emit(cfg, sale); Assert.True(response.Success, response.Message);
        Assert.Contains("<vPag>30.00</vPag>", response.XmlDocumento); Assert.Contains("<vTroco>9.00</vTroco>", response.XmlDocumento);
        sale.Pagamentos[0].Valor = 21m; Assert.False(Emit(cfg, sale).Success);
        sale.Pagamentos[0].Tipo = "03"; sale.Pagamentos[0].Valor = 30m; Assert.False(Emit(cfg, sale).Success);
    }

    [Fact]
    public void RegisteredTaxes_ArePreservedForStGoodsAndNormalRegime()
    {
        var cfg = Configuration(); var sale = Sale(); sale.Itens[0].CsosnIcms = "500"; sale.Itens[0].OrigemMercadoria = 2;
        sale.Itens[0].Cest = "0302100"; sale.Itens[0].Ncm = "22030000"; sale.Itens[0].Cfop = "5405"; sale.Itens[0].CstPis = "04"; sale.Itens[0].CstCofins = "04";
        var sn = Emit(cfg, sale); Assert.True(sn.Success, sn.Message); Assert.Contains("<ICMSSN500><orig>2</orig><CSOSN>500</CSOSN>", sn.XmlDocumento);
        Assert.Contains("<CEST>0302100</CEST>", sn.XmlDocumento); Assert.Contains("<PISNT><CST>04</CST>", sn.XmlDocumento);
        cfg.EmitenteCrt = 3; sale.Itens[0].CstIcms = "00"; sale.Itens[0].AliquotaIcms = 18m; sale.Itens[0].CstIbsCbs = "000"; sale.Itens[0].CClassTrib = "000001";
        var normal = Emit(cfg, sale); Assert.True(normal.Success, normal.Message);
        var xml = XDocument.Parse(normal.XmlDocumento); Assert.Equal("3.78", xml.Descendants(Ns + "ICMS00").Single().Element(Ns + "vICMS")!.Value);
        Assert.Equal("17.22", xml.Descendants(Ns + "gIBSCBS").Single().Element(Ns + "vBC")!.Value);
        Assert.Equal("0.02", xml.Descendants(Ns + "gIBSUF").First().Element(Ns + "vIBSUF")!.Value);
        Assert.Equal("0.15", xml.Descendants(Ns + "gCBS").First().Element(Ns + "vCBS")!.Value);
    }

    [Fact]
    public void CachedIssuer_RequiresSameTenantAndCompleteAddress_WithoutMergingSources()
    {
        var cfg = Configuration(); cfg.EmitenteLogradouro = ""; var sale = Sale();
        sale.Emitente = new() { Cnpj = cfg.EmitenteCnpj, RazaoSocial = cfg.EmitenteRazaoSocial, InscricaoEstadual = cfg.EmitenteInscricaoEstadual,
            Logradouro = "ENDERECO DO CACHE", Numero = "73", Bairro = "BAIRRO CACHE", Municipio = cfg.EmitenteMunicipio,
            CodigoMunicipioIbge = cfg.EmitenteCodigoMunicipioIbge, Uf = "RJ", Cep = "20040002" };
        var response = Emit(cfg, sale); Assert.True(response.Success, response.Message); Assert.Contains("<xLgr>ENDERECO DO CACHE</xLgr>", response.XmlDocumento);
        Assert.Contains("<nro>73</nro>", response.XmlDocumento);
        sale.Emitente.Cnpj = "11222333000181"; Assert.False(Emit(cfg, sale).Success);
        sale.Emitente.Cnpj = cfg.EmitenteCnpj; sale.Emitente.Bairro = ""; Assert.False(Emit(cfg, sale).Success);
    }

    private sealed class Clock : IClock { public DateTimeOffset UtcNow => DateTimeOffset.UtcNow; }
    [Fact]
    public async Task PostReplay_AndAuthenticatedGet_AfterRestartReturnOriginalXml_WithoutNewNumber()
    {
        var cfg = Configuration(); cfg.DatabasePath = Path.Combine(Path.GetTempPath(), $"fiscal-replay-{Guid.NewGuid():N}.db");
        try
        {
            var options = Options.Create(cfg); var database = new GatewayDatabase(options, NullLogger<GatewayDatabase>.Instance); database.Initialize();
            var clock = new Clock(); var events = new SqliteEventStore(database, clock, NullLogger<SqliteEventStore>.Instance);
            var terminalStore = new SqliteTerminalStore(database, new GatewayIdentity(options), options, NullLogger<SqliteTerminalStore>.Instance);
            var registration = await terminalStore.RegisterAsync(new() { CompanyId = cfg.CompanyId, TerminalId = "test-terminal", TerminalType = "CASH" }, "test-gateway");
            GatewayFiscalController Controller()
            {
                var certs = new GatewayCertificateService(options, NullLogger<GatewayCertificateService>.Instance);
                var store = new LocalFiscalStore(database, events, clock, NullLogger<LocalFiscalStore>.Instance);
                var controller = new GatewayFiscalController(Signer(cfg), store, certs, terminalStore, options, NullLogger<GatewayFiscalController>.Instance);
                controller.ControllerContext = new() { HttpContext = new DefaultHttpContext() };
                controller.Request.Headers["X-Terminal-Id"] = registration.TerminalId; controller.Request.Headers["X-Terminal-Key"] = registration.ApiKey;
                return controller;
            }
            var sale = Sale(); var first = Assert.IsType<LocalNfceContingenciaResponse>(Assert.IsType<OkObjectResult>(await Controller().EmitirContingencia(sale)).Value);
            // Discard the response/controller and rebuild all fiscal service instances, as after a timeout/restart.
            var replay = Assert.IsType<LocalNfceContingenciaResponse>(Assert.IsType<OkObjectResult>(await Controller().EmitirContingencia(sale)).Value);
            var recovered = Assert.IsType<LocalNfceContingenciaResponse>(Assert.IsType<OkObjectResult>(await Controller().ObterContingencia(sale.VendaId)).Value);
            Assert.Equal(first.XmlDocumento, replay.XmlDocumento); Assert.Equal(first.XmlDocumento, recovered.XmlDocumento);
            Assert.Equal(first.ChaveAcesso, replay.ChaveAcesso);
            var storeAfterRestart = new LocalFiscalStore(database, events, clock, NullLogger<LocalFiscalStore>.Instance);
            Assert.Equal((1, 1), storeAfterRestart.ObterEstatisticas(cfg.CompanyId, 900));
            Assert.Null(storeAfterRestart.Find("other-company", sale.TerminalId, sale.VendaId));
            sale.Pagamentos[0].Valor = 22m; Assert.IsType<ConflictObjectResult>(await Controller().EmitirContingencia(sale));
            var unauthenticated = Controller(); unauthenticated.Request.Headers.Remove("X-Terminal-Key");
            Assert.IsType<UnauthorizedObjectResult>(await unauthenticated.ObterContingencia(sale.VendaId));
            Assert.IsType<NotFoundObjectResult>(await Controller().ObterContingencia("unknown-sale"));
        }
        finally
        {
            SqliteConnection.ClearAllPools();
            foreach (var suffix in new[] { "", "-wal", "-shm" }) { var path = cfg.DatabasePath + suffix; if (File.Exists(path)) File.Delete(path); }
        }
    }
}
