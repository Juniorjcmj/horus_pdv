using System.Runtime.Loader;
using HORUSPDV_API.Repositories.DataAccess;
using HORUSPDV_API.Services.Fiscal;

if (args.Length != 1) throw new ArgumentException("Informe a pasta da API compilada.");
var output = Path.GetFullPath(args[0]);
AssemblyLoadContext.Default.Resolving += (context, name) =>
{
    var file = Path.Combine(output, name.Name + ".dll");
    return File.Exists(file) ? context.LoadFromAssemblyPath(file) : null;
};
var passed = 0;
void Check(bool ok, string name) { if (!ok) throw new Exception(name); passed++; Console.WriteLine("OK: " + name); }
var cests = FiscalCestReference.Load(output);
var october = new DateOnly(2026, 10, 31);
var november = october.AddDays(1);
var product = new ProdutoAD { ProductName = "Fogão de cozinha", Ncm = "85166000", Cfop = "5102", CsosnIcms = "102", CstPis = "07", CstCofins = "07", Gtin = "SEM GTIN" };
var company = new EmpresaAD { Crt = 1, Uf = "RJ", AmbienteFiscal = 1 };
var tables = new FiscalTableData(new Dictionary<string, FiscalNcm>
{
    [product.Ncm] = new(product.Ncm, "Aparelhos eletrotérmicos", new(2022, 1, 1), new(9999, 12, 31))
}, new Dictionary<string, FiscalClass>(), false, DateTimeOffset.UtcNow, "2026-10-10");
Check(cests.Count > 1000 && cests.All(c => c.Inicio != default), "Todas as referências CEST possuem cobertura de datas");
foreach (var code in new[] { "2104600", "2104700" })
{
    Check(cests.Count(c => c.Codigo == code && c.Vigente(october)) == 1, "Redação antiga selecionada até 31/10: " + code);
    var futureCode = code == "2104600" ? "21.046.01" : "21.047.01";
    Check(!cests.Single(c => c.Codigo == code && c.Vigente(october)).Descricao.Contains(futureCode), "Texto vigente não contém exclusão futura: " + code);
    Check(cests.Count(c => c.Codigo == code && c.Vigente(november)) == 1, "Nova redação selecionada em 01/11: " + code);
    Check(cests.Single(c => c.Codigo == code && c.Vigente(november)).Descricao.Contains(futureCode), "Exclusão correta a partir de novembro: " + code);
}
foreach (var code in new[] { "2104601", "2104701", "2105101" })
{
    product.Ncm = code == "2105101" ? "85169000" : "85166000";
    Check(FiscalAiRules.Validate("cest", code, product, company, tables, cests, october) is not null, "IA bloqueia CEST futuro: " + code);
    Check(FiscalAiRules.Validate("cest", code, product, company, tables, cests, november) is null, "IA aceita CEST vigente com NCM compatível: " + code);
}
product.Ncm = "85166000";
Check(!FiscalAiRules.Sources(product, tables, cests, october).Any(s => s.Id is "cest:2104601" or "cest:2104701"), "Prompt de outubro não recebe códigos futuros");
Check(FiscalAiRules.Sources(product, tables, cests, november).Any(s => s.Id == "cest:2104601"), "Prompt de novembro recebe novo código");
Check(FiscalAiRules.Validate("cest", "0302103", product, company, tables, cests, october) is not null, "Código de cerveja não pode ser aplicado a fogão");
Check(FiscalAiRules.Validate("cest", null, product, company, tables, cests, october) is not null, "IA não apaga CEST por ausência de prova");
Check(FiscalAiRules.Validate("cest", "", product, company, tables, cests, october) is not null, "String vazia também não apaga CEST");
product.Cest = null;
var report = ProductFiscalReview.Check(product, company, tables, new(2026, 10, 31, 23, 0, 0, TimeSpan.FromHours(-3)), cests);
Check(report.Apontamentos.Any(a => a.Campo == "CEST" && a.Nivel == "aviso" && a.Mensagem.Contains("mesmo sem ST")), "CEST ausente gera pendência mesmo no Simples sem ST");
product.Cest = "2104601";
report = ProductFiscalReview.Check(product, company, tables, new(2026, 11, 1, 1, 0, 0, TimeSpan.Zero), cests);
Check(report.Apontamentos.Any(a => a.Campo == "CEST" && a.Nivel == "erro"), "Virada UTC ainda usa outubro no horário do RJ");
report = ProductFiscalReview.Check(product, company, tables, new(2026, 11, 1, 3, 0, 0, TimeSpan.Zero), cests);
Check(!report.Apontamentos.Any(a => a.Campo == "CEST" && a.Nivel == "erro"), "Nova vigência começa à meia-noite no horário do RJ");
Console.WriteLine($"{passed} verificações CEST passaram. Sem banco, IA real ou transmissão fiscal.");
