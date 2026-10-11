using System.Runtime.Loader;
using HORUSPDV_API.Repositories;
using HORUSPDV_API.Repositories.DatabaseAccess;
using HORUSPDV_API.Services.Fiscal;
using Microsoft.Data.SqlClient;

if (args.Length != 3 || args[2] != "127.0.0.1,51461")
    throw new ArgumentException("Use a API compilada, arquivo da senha fictícia e SQL descartável 127.0.0.1,51461.");
var output = Path.GetFullPath(args[0]);
Directory.SetCurrentDirectory(output);
AssemblyLoadContext.Default.Resolving += (context, name) => {
    var path = Path.Combine(output, name.Name + ".dll");
    return File.Exists(path) ? context.LoadFromAssemblyPath(path) : null;
};
var connectionString = new SqlConnectionStringBuilder { DataSource = args[2], InitialCatalog = "master", UserID = "sa",
    Password = File.ReadAllText(args[1]).Trim(), TrustServerCertificate = true };
await using (var guardDb = new SqlConnection(connectionString.ConnectionString))
{
    await guardDb.OpenAsync();
    await using var guard = new SqlCommand("IF DB_ID('HorusPdv') IS NOT NULL AND OBJECT_ID('HorusPdv.dbo.__FiscalPersistenceOnly') IS NULL THROW 51000,'Banco não descartável: recusado.',1;", guardDb);
    await guard.ExecuteNonQueryAsync();
}
connectionString.InitialCatalog = "HorusPdv";
var builder = WebApplication.CreateBuilder(); builder.Logging.ClearProviders();
builder.Configuration["ConnectionStrings:HorusPdv"] = connectionString.ConnectionString;
builder.Services.AddSingleton<Connection>();
await using var app = builder.Build();
await HorusDatabaseInitializer.InitializeAsync(app.Services);
var connection = app.Services.GetRequiredService<Connection>();
async Task Sql(string sql)
{
    await using var db = await connection.OpenConnectionAsync();
    await using var command = new SqlCommand(sql, db); await command.ExecuteNonQueryAsync();
}
async Task<int> Count(string where)
{
    await using var db = await connection.OpenConnectionAsync();
    await using var command = new SqlCommand("SELECT COUNT(*) FROM DocumentosFiscais WHERE " + where, db);
    return Convert.ToInt32(await command.ExecuteScalarAsync());
}
await Sql("""
    IF OBJECT_ID('__FiscalPersistenceOnly') IS NULL CREATE TABLE __FiscalPersistenceOnly(Id INT);
    IF DB_NAME() <> N'HorusPdv' OR OBJECT_ID(N'__FiscalPersistenceOnly', N'U') IS NULL
        THROW 51000,'Limpeza recusada fora do banco descartável.',1;
    IF OBJECT_ID(N'trgFiscalImportRollback', N'TR') IS NOT NULL DROP TRIGGER trgFiscalImportRollback;
    IF OBJECT_ID(N'trgFiscalDecisionRollback', N'TR') IS NOT NULL DROP TRIGGER trgFiscalDecisionRollback;
    IF OBJECT_ID(N'trgFiscalDecisionQueueFailure', N'TR') IS NOT NULL DROP TRIGGER trgFiscalDecisionQueueFailure;
    DELETE FROM DocumentosFiscais WHERE CompanyId LIKE N'fiscal-persistence-%';
    DELETE FROM Vendas WHERE CompanyId LIKE N'fiscal-persistence-%';
    DELETE FROM RegrasEmissaoNfce WHERE CompanyId LIKE N'fiscal-persistence-%';
    DELETE FROM Empresas WHERE Id LIKE N'fiscal-persistence-%';
    IF NOT EXISTS(SELECT 1 FROM Empresas WHERE Id='fiscal-persistence-A') INSERT Empresas(Id,Uf,Crt) VALUES('fiscal-persistence-A','RJ',1);
    IF NOT EXISTS(SELECT 1 FROM Empresas WHERE Id='fiscal-persistence-B') INSERT Empresas(Id,Uf,Crt) VALUES('fiscal-persistence-B','RJ',1);
    IF NOT EXISTS(SELECT 1 FROM Vendas WHERE Id='persist-v1')
        INSERT Vendas(Id,CompanyId,SaleNumber,TotalAmount) VALUES
        ('persist-v1','fiscal-persistence-A','P-1',7.45),('persist-v2','fiscal-persistence-A','P-2',7.45),
        ('persist-v3','fiscal-persistence-A','P-3',7.45),('persist-v4','fiscal-persistence-A','P-4',7.45),
        ('persist-vb','fiscal-persistence-B','PB-1',7.45);
    """);
var store = new DocumentoFiscalAB(connection, null!, null!, null!, null!);
var passed = 0;
void Check(bool condition, string message) { if (!condition) throw new Exception(message); passed++; Console.WriteLine("OK: " + message); }
async Task Reject(Func<Task> action, string message)
{
    try { await action(); }
    catch (Exception ex) when (ex is InvalidOperationException or SqlException) { Check(true, message); return; }
    throw new Exception("Não recusou: " + message);
}
const string ns = "http://www.portalfiscal.inf.br/nfe";
var key1 = new string('1', 44); var key2 = new string('2', 44); var key3 = new string('3', 44);
// These are repository fixtures only. The gateway validator and cryptographic checks run separately.
string Snapshot(string key) => $"<NFe xmlns='{ns}'><infNFe Id='NFe{key}' versao='4.00'><ide><mod>65</mod><tpEmis>9</tpEmis></ide></infNFe><Signature xmlns='http://www.w3.org/2000/09/xmldsig#'><SignedInfo><Reference><DigestValue>c29tZWhhc2g=</DigestValue></Reference></SignedInfo></Signature></NFe>";
var xml1 = Snapshot(key1); var xml2 = Snapshot(key2);
var date = new DateTimeOffset(2026, 10, 10, 12, 0, 0, TimeSpan.FromHours(-3));
Task<string> Import(string sale, string key, string xml, int number, string company = "fiscal-persistence-A")
    => store.ImportarContingenciaGatewayAsync(company, sale, 900, number, 1, key, xml, "CONTINGENCIA FICTICIA PARA TESTE", date);
var imports = await Task.WhenAll(Import("persist-v1", key1, xml1, 1), Import("persist-v1", key1, xml1, 1));
Check(imports[0] == imports[1] && await Count("VendaId='persist-v1'") == 1, "Importação concorrente da mesma chave retorna um documento único");
var firstId = imports[0];
Check(await Import("persist-v1", key1, xml1, 1) == firstId, "Replay do XML Gateway não cria outro documento nem outro número");
await Reject(() => Import("persist-v2", key1, xml1, 1), "A chave não pode ser vinculada a outra venda da empresa");
await Reject(() => Import("persist-v1", key3, Snapshot(key3), 3), "Venda que já possui outra chave bloqueia importação duplicada");
await Reject(() => Import("persist-vb", key1, xml1, 1, "fiscal-persistence-B"), "Restrição de chave global impede vincular XML a outro tenant");
Check(await Count("CompanyId='fiscal-persistence-B'") == 0, "Conflito entre empresas não deixa linha parcial na fila");
Check(await store.EnfileirarAsync("fiscal-persistence-A", "persist-v1") == firstId && await Count("VendaId='persist-v1'") == 1,
    "Autoemissão de venda importada reaproveita o documento Gateway");
var cloudId = await store.EnfileirarAsync("fiscal-persistence-A", "persist-v2");
Check(await store.EnfileirarAsync("fiscal-persistence-A", "persist-v2") == cloudId, "Repetição de autoemissão preserva a numeração já reservada");
await Reject(() => Import("persist-v2", key2, xml2, 2), "Importação não substitui uma emissão cloud ativa da mesma venda");
var prepared = new ResultadoFiscal { Status = StatusDocumentoFiscal.Assinado, ChaveAcesso = key2, XmlAssinado = xml2 };
await store.GuardarAssinaturaAsync("fiscal-persistence-A", cloudId, prepared);
Check((await store.ObterPendentesAsync(20)).Any(d => d.Id == cloudId && d.XmlAssinado == xml2 && d.ChaveAcesso == key2),
    "XML e chave estão duráveis e disponíveis na fila antes da transmissão");
await Reject(() => store.GuardarAssinaturaAsync("fiscal-persistence-B", cloudId, prepared), "Assinatura de outro tenant não altera o documento");
await Reject(() => store.GuardarAssinaturaAsync("fiscal-persistence-A", cloudId, prepared with { XmlAssinado = Snapshot(key3), ChaveAcesso = key3 }),
    "Preparação concorrente não sobrescreve o XML original");
var alertsResponse = $"<retEnviNFe xmlns='{ns}' versao='4.00'><cStat>104</cStat><protNFe versao='4.00'><infProt><chNFe>{key2}</chNFe><cStat>120</cStat><digVal>c29tZWhhc2g=</digVal><nProt>123</nProt><dhRecbto>2026-10-10T12:00:00-03:00</dhRecbto><xMotivo>Autorizado com alerta</xMotivo><cMsg>1</cMsg><xMsg>Alerta fictício</xMsg></infProt></protNFe></retEnviNFe>";
await store.MarcarAutorizadoAsync(cloudId, FiscalXmlArchive.ReadResponse(xml2, alertsResponse));
var stored = await store.ObterXmlAsync("fiscal-persistence-A", cloudId);
Check(stored?.Status == StatusDocumentoFiscal.Autorizado && stored?.Xml?.Contains("Alerta fictício") == true,
    "Autorização 120 e mensagem original são persistidas e exportadas como XML completo");
var detail = await store.ObterDetalhePorVendaAsync("fiscal-persistence-A", "P-2");
Check(detail?.XmlDocumento == stored?.Xml && detail?.CodigoStatus == 120 && detail?.AlertasAutorizacao.Count == 1,
    "Detalhe da venda usa o XML real e devolve o alerta sem tratá-lo como rejeição");
await store.MarcarRejeitadoAsync(cloudId, prepared with { Status = StatusDocumentoFiscal.Rejeitado, CodigoStatus = 539 });
Check(await store.ReemitirAsync("fiscal-persistence-A", cloudId), "Duplicidade pode reenfileirar consulta da chave original");
var replay = (await store.ObterPendentesAsync(20)).Single(d => d.Id == cloudId);
Check(replay.XmlAssinado == xml2 && replay.ChaveAcesso == key2, "Reenfileirar duplicidade não apaga XML ou chave e não renumera");
await Sql("CREATE OR ALTER TRIGGER trgFiscalImportRollback ON DocumentosFiscais AFTER INSERT AS IF EXISTS(SELECT 1 FROM inserted WHERE VendaId='persist-v4') THROW 51000,'Falha fictícia de persistência',1;");
await Reject(() => Import("persist-v4", key3, Snapshot(key3), 3), "Falha SQL durante importação faz rollback da fila");
Check(await Count("VendaId='persist-v4'") == 0, "Importação rejeitada não deixa documento incompleto");
await Sql("DROP TRIGGER trgFiscalImportRollback;");

var rules = new RegrasEmissaoNfceAB(connection);
const string companyA = "fiscal-persistence-A";
const string companyB = "fiscal-persistence-B";
const string defaultCompany = "fiscal-persistence-default";
await Sql("""
    IF NOT EXISTS(SELECT 1 FROM Empresas WHERE Id='fiscal-persistence-default')
        INSERT Empresas(Id,Uf,Crt) VALUES('fiscal-persistence-default','RJ',1);
    INSERT Vendas(Id,CompanyId,SaleNumber,TotalAmount) VALUES
        ('persist-decision-first','fiscal-persistence-A','PD-1',7.45),
        ('persist-decision-second','fiscal-persistence-A','PD-2',7.45),
        ('persist-decision-third','fiscal-persistence-A','PD-3',7.45),
        ('persist-decision-interval','fiscal-persistence-A','PD-4',7.45),
        ('persist-decision-mixed','fiscal-persistence-A','PD-5',7.45),
        ('persist-decision-cpf','fiscal-persistence-A','PD-6',7.45),
        ('persist-decision-cnpj','fiscal-persistence-A','PD-7',7.45),
        ('persist-decision-invalid','fiscal-persistence-A','PD-8',7.45),
        ('persist-decision-disabled','fiscal-persistence-A','PD-9',7.45),
        ('persist-decision-model55','fiscal-persistence-A','PD-10',7.45),
        ('persist-decision-rollback-inc','fiscal-persistence-A','PD-11',7.45),
        ('persist-decision-rollback-reset','fiscal-persistence-A','PD-12',7.45),
        ('persist-decision-default','fiscal-persistence-default','PD-DEFAULT',7.45);
    WITH Numbers AS (SELECT 1 AS N UNION ALL SELECT N + 1 FROM Numbers WHERE N < 12)
    INSERT Vendas(Id,CompanyId,SaleNumber,TotalAmount)
        SELECT CONCAT('persist-decision-concurrent-',N),'fiscal-persistence-A',CONCAT('PD-C-',N),7.45 FROM Numbers;
    """);
Task<bool> Decide(string sale, string? primary = "dinheiro", IEnumerable<string>? payments = null,
    string? cpf = null, string company = companyA)
    => rules.AvaliarEmissaoParaVendaAsync(company, sale, primary, payments, cpf);
async Task<bool?> StoredDecision(string sale, string company = companyA)
{
    await using var db = await connection.OpenConnectionAsync();
    await using var command = new SqlCommand("SELECT EmitirNfce FROM Vendas WHERE CompanyId=@CompanyId AND Id=@Id", db);
    command.Parameters.AddWithValue("@CompanyId", company); command.Parameters.AddWithValue("@Id", sale);
    return await command.ExecuteScalarAsync() is bool value ? value : null;
}
async Task<int> Counter(string company = companyA) => (await rules.GetByCompanyAsync(company)).ContadorVendas;

await rules.UpsertAsync(companyA, true, "", 3, false, null);
await rules.ResetContadorAsync(companyA);
Check(!await Decide("persist-decision-first") && await StoredDecision("persist-decision-first") == false && await Counter() == 1,
    "Decisão de não emitir é persistida junto com um único incremento do intervalo");
await rules.UpsertAsync(companyA, true, "", 1, false, null);
Check(!await Decide("persist-decision-first") && await Counter() == 1,
    "Replay mantém a decisão de não emitir mesmo após mudar a configuração");
await rules.UpsertAsync(companyA, true, "", 3, false, null);
var sameSale = await Task.WhenAll(Enumerable.Range(0, 10).Select(_ => Decide("persist-decision-first")));
Check(sameSale.All(value => !value) && await Counter() == 1,
    "Repetições concorrentes da mesma venda não consomem o contador novamente");
Check(!await Decide("persist-decision-second") && await Counter() == 2,
    "Segunda venda do intervalo mantém a decisão de não emitir");
Check(await Decide("persist-decision-third") && await StoredDecision("persist-decision-third") == true && await Counter() == 0,
    "Venda que atinge o intervalo persiste emissão e reinício do contador juntos");
await Sql("CREATE OR ALTER TRIGGER trgFiscalDecisionQueueFailure ON DocumentosFiscais AFTER INSERT AS IF EXISTS(SELECT 1 FROM inserted WHERE VendaId='persist-decision-third') THROW 51000,'Falha fictícia de enfileiramento',1;");
try
{
    await Reject(() => store.EnfileirarAsync(companyA, "persist-decision-third"), "Falha da fila fiscal é propagada após a decisão persistida");
    Check(await StoredDecision("persist-decision-third") == true && await Counter() == 0 && await Count("VendaId='persist-decision-third'") == 0,
        "Falha da fila conserva a decisão fiscal sem deixar documento parcial");
}
finally { await Sql("DROP TRIGGER trgFiscalDecisionQueueFailure;"); }
Check(await Decide("persist-decision-third") && await Counter() == 0,
    "Replay após falha da fila conserva a emissão sem incrementar novamente");
var recoveredId = await store.EnfileirarAsync(companyA, "persist-decision-third");
Check(await store.EnfileirarAsync(companyA, "persist-decision-third") == recoveredId && await Count("VendaId='persist-decision-third'") == 1,
    "Fila pode ser recuperada no replay com um documento único e numeração preservada");
Check(await Decide("persist-v1") && await StoredDecision("persist-v1") == true && await Counter() == 0,
    "Venda legada com NFC-e Gateway infere emissão sem consumir o intervalo");

await rules.UpsertAsync(companyB, true, "", 2, false, null);
await rules.ResetContadorAsync(companyB);
await Reject(() => Decide("persist-decision-first", company: companyB), "Decisão fiscal não permite consultar venda de outro tenant");
await Reject(() => Decide("persist-decision-missing"), "Venda inexistente não cria uma decisão fiscal");
Check(await Counter(companyB) == 0 && await Counter() == 0,
    "Tentativas de outro tenant ou de venda inexistente não alteram os contadores");
Check(await Decide("persist-decision-default", primary: "outra", company: defaultCompany)
    && await StoredDecision("persist-decision-default", defaultCompany) == true && await Counter(defaultCompany) == 0,
    "Empresa sem configuração conserva o padrão de emitir todas as vendas");

await rules.UpsertAsync(companyA, true, "pix", 4, true, null);
Check(!await Decide("persist-decision-interval") && await Counter() == 1,
    "Forma fora da lista de emissão obrigatória segue o intervalo");
Check(await Decide("persist-decision-mixed", "01", ["03", "17"]) && await Counter() == 1,
    "Pagamento misto contendo PIX normalizado emite sem consumir o intervalo");
Check(await Decide("persist-decision-cpf", cpf: "123.456.789-01")
    && await Decide("persist-decision-cnpj", cpf: "12.345.678/0001-90") && await Counter() == 1,
    "CPF e CNPJ informados emitem sem consumir o intervalo");
Check(!await Decide("persist-decision-invalid", cpf: "123") && await Counter() == 2,
    "Documento incompleto conserva o comportamento do intervalo");
await rules.UpsertAsync(companyA, false, "", 4, false, null);
Check(await Decide("persist-decision-disabled") && await Counter() == 2,
    "Controle de regras desativado emite sem alterar o contador");
await rules.UpsertAsync(companyA, true, "", 3, false, null);
await rules.ResetContadorAsync(companyA);
await Sql("""
    INSERT DocumentosFiscais(Id,CompanyId,VendaId,Modelo,Serie,NumeroNf,Ambiente,Status)
    VALUES('persist-decision-doc55','fiscal-persistence-A','persist-decision-model55',55,901,1,2,1);
    """);
Check(!await Decide("persist-decision-model55") && await Counter() == 1,
    "Documento modelo 55 não substitui uma decisão de NFC-e modelo 65");

await rules.ResetContadorAsync(companyA);
var concurrentSales = Enumerable.Range(1, 12).Select(number => "persist-decision-concurrent-" + number).ToArray();
var concurrentDecisions = await Task.WhenAll(concurrentSales.Select(sale => Decide(sale)));
Check(concurrentDecisions.Count(value => value) == 4 && await Counter() == 0,
    "Doze vendas concorrentes com intervalo três emitem exatamente quatro NFC-e sem perder incrementos");
await rules.UpsertAsync(companyA, true, "", 1, false, null);
var concurrentReplays = await Task.WhenAll(concurrentSales.Select(sale => Decide(sale)));
Check(concurrentReplays.SequenceEqual(concurrentDecisions) && await Counter() == 0,
    "Replay concorrente conserva cada decisão persistida após mudar o intervalo");

await rules.UpsertAsync(companyA, true, "", 3, false, null);
await Sql("CREATE OR ALTER TRIGGER trgFiscalDecisionRollback ON Vendas AFTER UPDATE AS IF UPDATE(EmitirNfce) AND EXISTS(SELECT 1 FROM inserted WHERE Id IN ('persist-decision-rollback-inc','persist-decision-rollback-reset')) THROW 51000,'Falha fictícia da decisão fiscal',1;");
try
{
    await Reject(() => Decide("persist-decision-rollback-inc"), "Falha ao salvar a decisão também desfaz o incremento");
    Check(await StoredDecision("persist-decision-rollback-inc") is null && await Counter() == 0,
        "Rollback do incremento mantém venda sem decisão e contador original");
    await Sql("UPDATE RegrasEmissaoNfce SET ContadorVendas=2 WHERE CompanyId='fiscal-persistence-A';");
    await Reject(() => Decide("persist-decision-rollback-reset"), "Falha ao salvar a decisão também desfaz o reinício do contador");
    Check(await StoredDecision("persist-decision-rollback-reset") is null && await Counter() == 2,
        "Rollback do reinício conserva os incrementos anteriores");
}
finally { await Sql("DROP TRIGGER trgFiscalDecisionRollback;"); }
Check(await Decide("persist-decision-rollback-reset") && await Counter() == 0,
    "Retry após rollback recupera emissão e reinício do contador na mesma transação");
Check(!await Decide("persist-decision-rollback-inc") && await Counter() == 1,
    "Retry após rollback recupera uma única decisão de não emitir e um único incremento");

await store.MarcarRejeitadoAsync(cloudId, prepared with { Status = StatusDocumentoFiscal.Rejeitado, CodigoStatus = 225 });
var rejectedBeforeReplay = await store.ObterDetalhePorVendaAsync(companyA, "P-2");
var rejectedReplayId = await store.EnfileirarAsync(companyA, "persist-v2");
var rejectedAfterReplay = await store.ObterDetalhePorVendaAsync(companyA, "P-2");
Check(rejectedReplayId == cloudId && await Count("VendaId='persist-v2'") == 1
    && rejectedAfterReplay?.Status == StatusDocumentoFiscal.Rejeitado
    && rejectedAfterReplay?.NumeroNf == rejectedBeforeReplay?.NumeroNf
    && rejectedAfterReplay?.ChaveAcesso == key2 && rejectedBeforeReplay?.XmlDocumento is not null
    && rejectedAfterReplay?.XmlDocumento == rejectedBeforeReplay.XmlDocumento,
    "Replay de documento rejeitado mantém o mesmo ID, número, status, chave e XML sem criar outra NFC-e");

var eligibleBeforeCancellation = (await store.ObterVendasSemDocumentoFiscalAsync(100))
    .Any(sale => sale.CompanyId == companyA && sale.VendaId == "persist-decision-cpf");
await Sql("UPDATE Vendas SET Status=N'cancelada' WHERE CompanyId=N'fiscal-persistence-A' AND Id=N'persist-decision-cpf';");
await Reject(() => store.EnfileirarAsync(companyA, "persist-decision-cpf"), "Venda cancelada sem documento não permite criar NFC-e no replay");
Check(eligibleBeforeCancellation && (await store.ObterVendasSemDocumentoFiscalAsync(100))
        .All(sale => sale.CompanyId != companyA || sale.VendaId != "persist-decision-cpf")
    && await StoredDecision("persist-decision-cpf") == true && await Count("VendaId='persist-decision-cpf'") == 0,
    "Cancelamento retira a decisão fiscal pendente da recuperação sem apagar a decisão original nem criar documento");
await Reject(() => store.EnfileirarAsync(companyB, "persist-decision-default"), "Enfileiramento recusa venda de outro tenant");
Check(await Count("VendaId='persist-decision-default'") == 0 && await Count("CompanyId='fiscal-persistence-B'") == 0,
    "Tentativa de enfileirar em outro tenant não cria documento nem altera a fila da empresa");
Console.WriteLine($"{passed} verificações de persistência passaram no SQL descartável. Nenhum acesso à loja ou à SEFAZ.");
