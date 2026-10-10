using System.Net;
using System.Reflection;
using System.Runtime.Loader;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Security.Cryptography.Xml;
using System.Text.Json;
using System.Xml;
using System.Xml.Linq;
using System.Xml.Schema;
using DFe.Classes.Entidades;
using DFe.Classes.Flags;
using HORUSPDV_API.Repositories.DataAccess;
using HORUSPDV_API.Services.Fiscal;
using Microsoft.Extensions.FileProviders;
using Microsoft.Extensions.Logging.Abstractions;
using NFe.Classes.Informacoes.Detalhe;
using NFe.Classes.Informacoes.Detalhe.Tributacao.Estadual;
using NFe.Classes.Informacoes.Detalhe.Tributacao.Federal;
using NFe.Utils.NFe;
using NFe.Utils;
using NFe.Classes.Servicos.Tipos;

var apiOutput = Path.GetFullPath(args[0]);
AssemblyLoadContext.Default.Resolving += (context, name) => {
    var file = Path.Combine(apiOutput, name.Name + ".dll");
    return File.Exists(file) ? context.LoadFromAssemblyPath(file) : null;
};
var passed = 0;
void Check(bool value, string name) { if (!value) throw new Exception(name); passed++; Console.WriteLine("OK: " + name); }
void Reject(Action action, string name) { try { action(); } catch (Exception ex) when (ex is InvalidOperationException or FiscalConfigurationException or XmlException) { Check(true, name); return; } throw new Exception("Não rejeitou: " + name); }
var env = new TestEnvironment { ContentRootPath = apiOutput };
var offline = new OfflineHandler();
using var client = new HttpClient(offline);
var service = new FiscalReferenceTables(env, NullLogger<FiscalReferenceTables>.Instance, client);
var table = await service.GetAsync();
Check(!table.Online && table.DataBase == "2026-10-10" && table.Ncms.Count > 10000 && table.Classes.Count > 150, "Tabelas locais completas, com data e limitação explícita durante falta de internet");
await service.GetAsync(); Check(offline.Requests == 2, "Cache impede repetição de downloads durante a indisponibilidade");
var date = new DateTimeOffset(2026, 10, 10, 12, 0, 0, TimeSpan.FromHours(-3));
var product = new ProdutoAD { Id = "teste", ProductName = "Produto teste", Ncm = "10063021", Cfop = "5102", CsosnIcms = "102", Gtin = "SEM GTIN", CstPis = "07", CstCofins = "07" };
var company = new EmpresaAD { Uf = "RJ", Crt = 1, AmbienteFiscal = 1 };
var report = ProductFiscalReview.Check(product, company, table, date);
Check(!report.Apontamentos.Any(f => f.Nivel == "erro") && report.DescricaoNcm is not null && report.Apontamentos.Any(f => f.Campo == "Revisão contábil"), "NCM válido inclui descrição e limites da conferência, sem aprovação fiscal falsa");
Check(report.Apontamentos.Single(f => f.Campo == "IBS/CBS").Nivel == "informacao", "Simples em 2026 sem classificação IBS/CBS recebe informação, sem aviso de irregularidade");
Check(ProductFiscalReview.Check(product, company, table, date.AddYears(1)).Apontamentos.Single(f => f.Campo == "IBS/CBS").Nivel == "aviso", "Ausência de classificação no Simples exige revisão em 2027");
company.Crt = 3;
Check(ProductFiscalReview.Check(product, company, table, date).Apontamentos.Single(f => f.Campo == "IBS/CBS").Nivel == "aviso", "Regime normal não herda a orientação de Simples em 2026");
company.Crt = 1;
product.Ncm = "00000000"; Check(ProductFiscalReview.Check(product, company, table, date).Apontamentos.Any(f => f.Campo == "NCM" && f.Nivel == "erro"), "NCM padrão zerado é apontado");
product.Ncm = "10063021"; product.CstIbsCbs = "200"; product.CClassTrib = "000001";
Check(ProductFiscalReview.Check(product, company, table, date).Apontamentos.Any(f => f.Campo == "IBS/CBS" && f.Nivel == "erro"), "CST incompatível com cClassTrib é apontado");
product.CClassTrib = "999999"; Check(ProductFiscalReview.Check(product, company, table, date).Apontamentos.Any(f => f.Campo == "cClassTrib" && f.Nivel == "erro"), "Classificação inexistente é apontada");
var prohibited = table.Classes.Values.First(c => !c.Nfce);
product.CClassTrib = prohibited.Codigo; product.CstIbsCbs = prohibited.Cst;
Check(ProductFiscalReview.Check(product, company, table, date).Apontamentos.Any(f => f.Mensagem.Contains("não permite NFC-e")), "Classificação vedada para modelo 65 é apontada");
company.Crt = 3; product.CstIcms = null;
Check(ProductFiscalReview.Check(product, company, table, date).Apontamentos.Any(f => f.Campo == "CST ICMS" && f.Nivel == "erro"), "CSOSN não substitui CST no regime normal");
Check(ProductFiscalReview.ValidGtin("7894900011517") && !ProductFiscalReview.ValidGtin("7894900011518") && !ProductFiscalReview.ValidGtin("00000000"), "GTIN com verificador incorreto ou zerado é rejeitado");
var futureTable = table with { Ncms = new Dictionary<string, FiscalNcm> { ["10063021"] = new("10063021", "Futuro", new(2027,1,1), new(9999,12,31)) } };
Check(ProductFiscalReview.Check(product, company, futureTable, date).Apontamentos.Any(f => f.Campo == "NCM" && f.Mensagem.Contains("não está vigente")), "Vigência de NCM usa a data da consulta");
Check(product.Ncm == "10063021" && product.CsosnIcms == "102" && product.CstIcms is null, "Conferência não altera o produto");

var item = new ItemFiscal { Numero = 1, CodigoProduto = "SKU", Descricao = "Teste", Gtin = "SEM GTIN", Ncm = "10063021", Cfop = "5102", Origem = 0, UnidadeComercial = "UN", Quantidade = 1, ValorUnitario = 100, ValorTotal = 100, Desconto = 10, Csosn = "102", CstIcms = "00", AliquotaIcms = 20, CstPis = "07", CstCofins = "07", CstIbsCbs = "000", CClassTrib = "000001" };
var method = typeof(ZeusFiscalProvider).GetMethod("MontarItem", BindingFlags.NonPublic | BindingFlags.Static)!;
det Build(ItemFiscal input, byte crt) => (det)method.Invoke(null, [input, (byte)1, (byte)1, crt, 65, date])!;
var normal = Build(item, 3); var icms = (ICMS00)normal.imposto.ICMS.TipoICMS;
Check(icms.vBC == 90 && icms.vICMS == 18 && normal.imposto.ICMS.TipoICMS is ICMS00, "CRT normal ignora CSOSN remanescente e calcula ICMS após desconto");
var rtc = normal.imposto.IBSCBS.gIBSCBS;
Check(rtc.vBC == 72 && rtc.gIBSUF.pIBSUF == .1m && rtc.gIBSUF.vIBSUF == .07m && rtc.gIBSMun.pIBSMun == 0 && rtc.gCBS.vCBS == .65m && rtc.vIBS == .07m, "IBS estadual 0,1%, municipal zero, CBS 0,9% com base líquida e arredondamento");
var sn = Build(item, 1); Check(sn.imposto.ICMS.TipoICMS is ICMSSN102 && sn.imposto.IBSCBS is null, "Simples não recebe as alíquotas do regime normal em 2026");
var exempt = Build(item with { CstIcms = "40" }, 3); Check(exempt.imposto.ICMS.TipoICMS is ICMS40, "CST 40 preservado, sem substituição por ICMS 00");
var reducedClass = table.Classes.Values.First(c => c.Nfce && c.Cst == "200" && !c.Especial && c.RedIbs == 60 && c.RedCbs == 60);
var reduced = Build(item with { CstIbsCbs = "200", CClassTrib = reducedClass.Codigo }, 3).imposto.IBSCBS.gIBSCBS;
Check(reduced.gIBSUF.gRed.pAliqEfet == .04m && reduced.gCBS.gRed.pAliqEfet == .36m && reduced.gCBS.vCBS == .26m, "Redução de IBS/CBS vem da classificação oficial selecionada, sem inferência por nome");
var totals = FiscalTaxRules.Totals([normal, normal]);
Check(totals!.vBCIBSCBS == 144 && totals.gIBS.vIBS == .14m && totals.gCBS.vCBS == 1.30m && FiscalTaxRules.IcmsTotal([normal, normal]) == 36, "Totais agregam os valores arredondados por item");
Check(FiscalTaxRules.Money(.005m) == .01m, "Arredondamento fiscal não perde meio centavo");
Reject(() => FiscalTaxRules.Build(item with { CClassTrib = "999999" }, 3, 65, date, 18), "Classificação inválida nunca gera grupo zerado genérico");
Reject(() => FiscalTaxRules.Build(item, 3, 65, date.AddYears(1), 18), "Alíquotas de 2026 não são reutilizadas automaticamente em 2027");
try { Build(item with { Csosn = "900" }, 1); throw new Exception("CSOSN 900 substituído"); } catch (TargetInvocationException ex) when (ex.InnerException is FiscalConfigurationException) { Check(true, "CSOSN não suportado exige revisão e não vira 102"); }
try { Build(item with { CstPis = "01" }, 3); throw new Exception("PIS tributável zerado"); } catch (TargetInvocationException ex) when (ex.InnerException is FiscalConfigurationException) { Check(true, "CST PIS tributável não gera PISOutr zerado"); }

const string ns = "http://www.portalfiscal.inf.br/nfe", key = "33261055719385000133650030000000211132918790";
var document = new XmlDocument { PreserveWhitespace = true };
document.LoadXml($"<NFe xmlns='{ns}'><infNFe Id='NFe{key}' versao='4.00'><det nItem='1'><prod><NCM>10063021</NCM></prod></det></infNFe></NFe>");
using var rsa = RSA.Create(2048);
var signer = new SignedXml(document) { SigningKey = rsa };
var reference = new Reference("#NFe" + key); reference.AddTransform(new XmlDsigEnvelopedSignatureTransform()); reference.AddTransform(new XmlDsigC14NTransform()); signer.AddReference(reference);
signer.KeyInfo = new KeyInfo(); signer.KeyInfo.AddClause(new RSAKeyValue(rsa)); signer.ComputeSignature(); document.DocumentElement!.AppendChild(document.ImportNode(signer.GetXml(), true));
var digest = Convert.ToBase64String(reference.DigestValue!);
var response = $"<retEnviNFe xmlns='{ns}' versao='4.00'><cStat>104</cStat><protNFe versao='4.00'><infProt><chNFe>{key}</chNFe><cStat>100</cStat><digVal>{digest}</digVal><nProt>123</nProt></infProt></protNFe></retEnviNFe>";
var complete = FiscalXmlArchive.Complete(document.OuterXml, response)!;
var parsed = XDocument.Parse(complete); XNamespace xn = ns;
Check(parsed.Root!.Name == xn + "nfeProc" && parsed.Descendants(xn + "NCM").Single().Value == "10063021" && parsed.Descendants(xn + "protNFe").Count() == 1, "XML completo reúne itens e protocolo original de autorização");
var recombined = new XmlDocument { PreserveWhitespace = true }; recombined.LoadXml(complete);
var verifier = new SignedXml(recombined); verifier.LoadXml((XmlElement)recombined.GetElementsByTagName("Signature", SignedXml.XmlDsigNamespaceUrl)[0]!);
Check(verifier.CheckSignature(rsa), "Assinatura criptográfica original continua válida após compor nfeProc");
Check(FiscalXmlArchive.Complete(document.OuterXml, complete) == complete, "XML completo já salvo é preservado");
Check(FiscalXmlArchive.Complete(null, response) is null, "Protocolo sem nota não é exportado como XML completo");
Reject(() => FiscalXmlArchive.Complete(document.OuterXml, response.Replace(key, new string('1', 44))), "Chave de autorização divergente é rejeitada");
Reject(() => FiscalXmlArchive.Complete(document.OuterXml, response.Replace(digest, "outro")), "Digest divergente é rejeitado");
Reject(() => FiscalXmlArchive.Complete(document.OuterXml, response.Replace("<cStat>100", "<cStat>204")), "Resposta de rejeição não vira nota autorizada");
Reject(() => FiscalXmlArchive.Complete("<!DOCTYPE a [<!ENTITY x SYSTEM 'file:///C:/Windows/win.ini'>]><NFe>&x;</NFe>", response), "DTD e entidades externas são proibidos");
Reject(() => FiscalXmlArchive.Complete(document.OuterXml, null, true), "Nota autorizada sem protocolo não é apresentada como XML completo");

var issuer = new ContextoEmitente {
    CompanyId="teste", Cnpj="12345678000195", InscricaoEstadual="12345678", RazaoSocial="EMISSOR TESTE", NomeFantasia="TESTE", Crt=3, Cnae="4711302",
    Logradouro="RUA TESTE", Numero="1", Bairro="CENTRO", CodigoMunicipioIbge="3304557", NomeMunicipio="RIO DE JANEIRO", Uf="RJ", Cep="20000000",
    Ambiente=2, CscId="1", Csc="TESTE", CertificadoPfx=[], CertificadoSenha=""
};
var request = new EmissaoNfceRequest { Emitente=issuer, Serie=3, NumeroNf=1, TipoEmissao=TipoEmissaoFiscal.Normal, Itens=[item], Pagamentos=[new() { Tipo="01", Valor=90 }] };
var builder = typeof(ZeusFiscalProvider).GetMethod("MontarNfe", BindingFlags.NonPublic | BindingFlags.Static)!;
var invoice = (NFe.Classes.NFe)builder.Invoke(null, [request])!;
var certRequest = new CertificateRequest("CN=FiscalRegression", rsa, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
using var cert = certRequest.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddDays(1));
var cfg = new ConfiguracaoServico { cUF=Estado.RJ, tpAmb=TipoAmbiente.Homologacao, ModeloDocumento=ModeloDocumento.NFCe, VersaoLayout=VersaoServico.Versao400 };
invoice.Assina(cfg, cert);
var builtXml = invoice.ObterXmlString();
var schemas = new XmlSchemaSet { XmlResolver = new XmlUrlResolver() };
schemas.Add(ns, Path.Combine(apiOutput, "DataBase", "Schemas", "nfe_v4.00.xsd"));
var schemaErrors = new List<string>();
XDocument.Parse(builtXml).Validate(schemas, (_, issue) => schemaErrors.Add(issue.Message));
Check(schemaErrors.Count == 0, "NFC-e completa assinada com IBS/CBS passa nos schemas locais: " + string.Join("; ", schemaErrors));
Check(invoice.infNFe.total.ICMSTot.vICMS == 18 && invoice.infNFe.total.IBSCBSTot.gCBS.vCBS == .65m, "Builder de documento agrega ICMS e IBS/CBS corretos no XML");
if (args.Contains("--live")) {
    var live = await new FiscalReferenceTables(env, NullLogger<FiscalReferenceTables>.Instance).GetAsync();
    Check(live.Online && live.Ncms.ContainsKey("10063021") && live.Classes.ContainsKey("000001"), "Consulta real das duas fontes oficiais sem dados da loja");
}
Console.WriteLine($"{passed} verificações fiscais passaram. Nenhuma emissão ou acesso ao banco da loja.");

sealed class OfflineHandler : HttpMessageHandler {
    public int Requests;
    protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken token) { Requests++; throw new HttpRequestException("Indisponível no teste"); }
}
sealed class TestEnvironment : IWebHostEnvironment {
    public string ApplicationName { get; set; } = "FiscalChecks"; public string EnvironmentName { get; set; } = "Testing";
    public string WebRootPath { get; set; } = ""; public IFileProvider WebRootFileProvider { get; set; } = new NullFileProvider();
    public string ContentRootPath { get; set; } = ""; public IFileProvider ContentRootFileProvider { get; set; } = new NullFileProvider();
}
