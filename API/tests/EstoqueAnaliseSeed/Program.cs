// Dados de demonstracao da analise de estoque (D8): empresa "demo-analise" com ~200 SKUs, 12 meses de vendas diarias
// (sazonalidade, parados, rupturas, excesso), pedidos de compra com lead times reais diferentes dos prometidos,
// perdas, 6 inventarios e as fotografias diarias reconstruidas.
//
// Uso: dotnet run --project API/tests/EstoqueAnaliseSeed -- --connection "<connection string de um SQL LOCAL de teste>"
// Seguranca: a conexao e obrigatoria e o servidor precisa ser localhost; nunca roda contra producao.
// Repetivel: apaga e recria apenas a empresa "demo-analise".
using System.Data;
using System.Globalization;
using System.Security.Cryptography;
using HORUSPDV_API.Repositories;
using HORUSPDV_API.Repositories.DataAccess;
using HORUSPDV_API.Repositories.DatabaseAccess;
using HORUSPDV_API.Services.Estoque;
using HORUSPDV_API.Services.Shared;
using Microsoft.Data.SqlClient;

const string Company = "demo-analise";
var argv = Environment.GetCommandLineArgs();
var idx = Array.IndexOf(argv, "--connection");
if (idx < 0 || idx + 1 >= argv.Length)
{
    Console.Error.WriteLine("Informe --connection \"<connection string>\" de um SQL Server LOCAL de teste.");
    return 2;
}
var connectionString = argv[idx + 1];
var csb = new SqlConnectionStringBuilder(connectionString);
var host = csb.DataSource.Replace("tcp:", "").Split(',')[0].Trim().ToLowerInvariant();
if (host is not ("localhost" or "127.0.0.1" or "(local)" or "." or "pdv-teste-sql"))
{
    Console.Error.WriteLine($"Recusado: o servidor '{host}' nao e local. O seed so roda em banco de teste em localhost.");
    return 2;
}

var configuration = new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string, string?> { ["ConnectionStrings:HorusPdv"] = connectionString }).Build();
var connection = new Connection(configuration);
var produtosAB = new ProdutoAB(connection);
var rng = new Random(2026);
var tz = HorusDateTime.TimeZone;
var hoje = DateOnly.FromDateTime(HorusDateTime.Now.Date);
var dias = 365;
var inicio = hoje.AddDays(-dias);            // primeiro dia simulado
var ultimo = hoje.AddDays(-1);               // ultimo dia simulado (ontem)

DateTime UtcDe(DateOnly d, int hora, int min) =>
    TimeZoneInfo.ConvertTimeToUtc(DateTime.SpecifyKind(d.ToDateTime(new TimeOnly(hora, min)), DateTimeKind.Unspecified), tz);
DateTimeOffset LocalDe(DateOnly d, int hora, int min)
{
    var local = DateTime.SpecifyKind(d.ToDateTime(new TimeOnly(hora, min)), DateTimeKind.Unspecified);
    return new DateTimeOffset(local, tz.GetUtcOffset(local));
}
int Poisson(double lambda)
{
    if (lambda <= 0) return 0;
    var l = Math.Exp(-lambda); var k = 0; var p = 1.0;
    do { k++; p *= rng.NextDouble(); } while (p > l && k < 200);
    return k - 1;
}
double Sazonal(DateOnly d)
{
    var f = d.DayOfWeek is DayOfWeek.Saturday or DayOfWeek.Sunday ? 1.35 : d.DayOfWeek == DayOfWeek.Monday ? 0.85 : 1.0;
    if (d.Month == 12) f *= 1.5;
    else if (d.Month is 6 or 7) f *= 0.9;
    else if (d.Month == 1) f *= 0.8;
    return f;
}

await using var db = await connection.OpenConnectionAsync();
async Task Exec(string sql, SqlTransaction? tx = null, params (string, object)[] ps)
{
    await using var c = new SqlCommand(sql, db, tx) { CommandTimeout = 600 };
    c.Parameters.AddWithValue("@C", Company);
    foreach (var (n, v) in ps) c.Parameters.AddWithValue(n, v);
    await c.ExecuteNonQueryAsync();
}

Console.WriteLine("Limpando a empresa demo...");
foreach (var sql in new[]
{
    "DELETE FROM InventarioEventos WHERE CompanyId=@C", "DELETE FROM InventarioContagens WHERE CompanyId=@C",
    "DELETE FROM InventarioItens WHERE CompanyId=@C", "DELETE FROM Inventarios WHERE CompanyId=@C",
    "DELETE FROM ProdutoClassificacao WHERE CompanyId=@C", "DELETE FROM EstoqueSnapshotDiario WHERE CompanyId=@C",
    "DELETE FROM EstoqueConfigAnalise WHERE CompanyId=@C", "DELETE FROM EstoqueMovimentacoes WHERE CompanyId=@C",
    "DELETE FROM OrdemCompraItens WHERE OrdemCompraId IN (SELECT Id FROM OrdemCompra WHERE CompanyId=@C)",
    "DELETE FROM OrdemCompra WHERE CompanyId=@C",
    "DELETE FROM VendaPagamentos WHERE CompanyId=@C", "DELETE FROM VendaItens WHERE VendaId IN (SELECT Id FROM Vendas WHERE CompanyId=@C)",
    "DELETE FROM Vendas WHERE CompanyId=@C", "DELETE FROM Produtos WHERE CompanyId=@C", "DELETE FROM Fornecedores WHERE CompanyId=@C",
    "DELETE FROM Categorias WHERE CompanyId=@C", "DELETE FROM Usuarios WHERE CompanyId=@C", "DELETE FROM Empresas WHERE Id=@C",
})
{
    try { await Exec(sql); } catch (SqlException ex) { Console.WriteLine($"  (ignorado: {ex.Message.Split('\n')[0]})"); }
}

// ----- empresa e usuarios -----
await Exec("INSERT INTO Empresas (Id, FantasyName, CorporateName, Cnpj, Status) VALUES (@C, N'Mercado Demo Analise', N'Mercado Demo Analise LTDA', N'00.000.000/0001-91', N'aprovada')");
string Hash(string senha)
{
    var salt = RandomNumberGenerator.GetBytes(16);
    var key = Rfc2898DeriveBytes.Pbkdf2(senha, salt, 100_000, HashAlgorithmName.SHA256, 32);
    return $"100000.{Convert.ToBase64String(salt)}.{Convert.ToBase64String(key)}";
}
foreach (var (id, nome, email, role, cpf) in new[]
{
    ("u-demo-gerente", "Gerente Demo", "gerente.demo@teste.com", "gerente", "11111111111"),
    ("u-demo-estoquista", "Estoquista Demo", "estoquista.demo@teste.com", "estoquista", "22222222222"),
})
{
    await Exec("INSERT INTO Usuarios (Id, CompanyId, Cpf, Name, Email, Role, Status, CreatedAt, PasswordHash, MustChangePassword) VALUES (@I, @C, @Cpf, @N, @E, @R, N'ativo', @D, @H, 0)", null,
        ("@I", id), ("@Cpf", cpf), ("@N", nome), ("@E", email), ("@R", role), ("@D", DateTime.Today.ToString("yyyy-MM-dd")), ("@H", Hash("Demo@12345")));
}

// ----- categorias e fornecedores -----
var categorias = new[] { "Mercearia", "Bebidas", "Laticinios", "Limpeza", "Higiene", "Hortifruti", "Padaria", "Frios", "Congelados", "Pet" };
for (var i = 0; i < categorias.Length; i++)
    await Exec("INSERT INTO Categorias (Id, CompanyId, Nome, Ordem, Ativa) VALUES (@I, @C, @N, @O, 1)", null, ("@I", $"cat-demo-{i}"), ("@N", categorias[i]), ("@O", i));

var prometido = new[] { 3, 5, 7, 7, 10, 4, 6, 14, 5, 8, 7, 9 };
var atrasoMedio = new[] { 0.0, 0.5, 1.0, 3.5, 0.0, 2.0, -1.0, 6.0, 0.5, 1.0, 4.0, 0.0 };   // dias a mais que o prometido, em media
for (var f = 0; f < 12; f++)
{
    var cnpj = $"{10 + f:00}.{300 + f * 7:000}.{500 + f:000}/0001-{(f * 13) % 90 + 10:00}";
    await Exec("INSERT INTO Fornecedores (Id, CompanyId, CompanyName, FantasyName, Cnpj, LeadTimePrometidoDias) VALUES (@I, @C, @N, @F, @J, @L)", null,
        ("@I", $"forn-demo-{f}"), ("@N", $"Distribuidora {f + 1} LTDA"), ("@F", $"Distribuidora {f + 1}"), ("@J", cnpj), ("@L", prometido[f]));
}

// ----- produtos -----
const int NProdutos = 200;
var nomes = new[] { "Arroz", "Feijao", "Macarrao", "Cafe", "Acucar", "Oleo", "Leite", "Iogurte", "Queijo", "Presunto", "Refrigerante", "Suco", "Agua", "Cerveja",
    "Detergente", "Sabao", "Amaciante", "Shampoo", "Sabonete", "Papel Higienico", "Banana", "Maca", "Tomate", "Pao Frances", "Bolo", "Pizza Congelada",
    "Sorvete", "Racao Cao", "Racao Gato", "Biscoito" };
var prod = new List<Prod>();
// pesos de Zipf embaralhados: poucos produtos concentram a venda
var pesos = Enumerable.Range(0, NProdutos).Select(r => 1.0 / Math.Pow(r + 1, 0.85)).OrderBy(_ => rng.Next()).ToArray();
var somaPesos = pesos.Sum();
var unidadesPorDia = 120.0;
for (var i = 0; i < NProdutos; i++)
{
    var custo = Math.Round(0.8 + rng.NextDouble() * 38, 2);
    var p = new Prod
    {
        Idx = i, Id = $"pr-demo-{i:000}", Codigo = $"D{i + 1:0000}", Nome = $"{nomes[i % nomes.Length]} {(i / nomes.Length) + 1} {(char)('A' + i % 7)}",
        Cat = i % categorias.Length, Forn = i % 12, Custo = (decimal)custo,
        Venda = (decimal)Math.Round(custo * (1.25 + rng.NextDouble() * 0.55), 2),
        Lambda = unidadesPorDia * pesos[i] / somaPesos,
    };
    prod.Add(p);
}
// parados: 15 produtos de venda media que pararam de vender entre 95 e 200 dias atras; 5 nunca venderam
var meio = prod.OrderByDescending(p => p.Lambda).Skip(30).Take(15).ToList();
foreach (var p in meio) p.ParouHaDias = rng.Next(95, 200);
foreach (var p in prod.OrderBy(p => p.Lambda).Take(5)) { p.Lambda = 0; p.Nunca = true; }
// excesso: 10 produtos de giro baixo com estoque inicial enorme e sem reposicao
var excesso = prod.Where(p => !p.Nunca && p.ParouHaDias == 0).OrderBy(p => p.Lambda).Skip(8).Take(10).ToList();
foreach (var p in excesso) { p.Excesso = true; }
// reposicao sub-dimensionada (gera ruptura): 12 produtos de alto giro
foreach (var p in prod.Where(p => !p.Excesso && p.ParouHaDias == 0).OrderByDescending(p => p.Lambda).Take(12)) p.RupturaProne = true;

Console.WriteLine("Cadastrando 200 produtos...");
foreach (var p in prod)
{
    var ad = new ProdutoAD
    {
        Id = p.Id, ProductCode = p.Codigo, ProductName = p.Nome, ProductDescription = p.Nome, ProductQnt = 0, ProductUnitPrice = p.Custo,
        ProductSalePrice = p.Venda, TotalPriceOnProduct = 0, Lucro = p.Venda - p.Custo, Ncm = "21069090", Cfop = "5102",
        UnidadeComercial = "UN", UnidadeTributavel = "UN", Gtin = "SEM GTIN", CsosnIcms = "102", CstPis = "07", CstCofins = "07",
        CategoriaId = $"cat-demo-{p.Cat}", ProductSupplier = $"Distribuidora {p.Forn + 1}",
    };
    await produtosAB.SalvarAsync(Company, ad);
    p.Estoque = p.Excesso ? rng.Next(500, 900) : Math.Max(12, (int)Math.Round(p.Lambda * (14 + rng.Next(0, 10))));
    if (p.Nunca) p.Estoque = rng.Next(20, 60);
}
await Exec("UPDATE Produtos SET SupplierId = 'forn-demo-' + CAST(CAST(SUBSTRING(Id, 9, 3) AS INT) % 12 AS NVARCHAR(10)) WHERE CompanyId=@C AND SupplierId IS NULL");

// ----- simulacao diaria -----
var movs = new DataTable();
foreach (var (n, t) in new (string, Type)[] { ("Id", typeof(string)), ("CompanyId", typeof(string)), ("ProdutoId", typeof(string)), ("Tipo", typeof(string)),
    ("Quantidade", typeof(decimal)), ("EstoqueAnterior", typeof(decimal)), ("EstoqueNovo", typeof(decimal)), ("Motivo", typeof(string)),
    ("UsuarioId", typeof(string)), ("UsuarioNome", typeof(string)), ("CriadoEm", typeof(DateTime)), ("CustoUnitario", typeof(decimal)),
    ("OrigemTipo", typeof(string)), ("OrigemId", typeof(string)), ("DepositoId", typeof(string)) }) movs.Columns.Add(n, t);
var vendasT = new DataTable();
foreach (var (n, t) in new (string, Type)[] { ("Id", typeof(string)), ("CompanyId", typeof(string)), ("SaleNumber", typeof(string)), ("CustomerName", typeof(string)),
    ("CustomerCpf", typeof(string)), ("PaymentType", typeof(string)), ("OperatorName", typeof(string)), ("SaleDate", typeof(DateTimeOffset)),
    ("TotalAmount", typeof(decimal)), ("Status", typeof(string)) }) vendasT.Columns.Add(n, t);
var itensT = new DataTable();
foreach (var (n, t) in new (string, Type)[] { ("Id", typeof(string)), ("VendaId", typeof(string)), ("ProductCode", typeof(string)), ("ProductName", typeof(string)),
    ("Quantity", typeof(decimal)), ("UnitPrice", typeof(decimal)), ("ItemTotal", typeof(decimal)), ("Desconto", typeof(decimal)), ("CostPrice", typeof(decimal)) }) itensT.Columns.Add(n, t);
var pagT = new DataTable();
foreach (var (n, t) in new (string, Type)[] { ("Id", typeof(string)), ("CompanyId", typeof(string)), ("VendaId", typeof(string)), ("PaymentType", typeof(string)),
    ("Amount", typeof(decimal)), ("CashGiven", typeof(decimal)), ("ChangeAmount", typeof(decimal)), ("CreatedAt", typeof(DateTimeOffset)) }) pagT.Columns.Add(n, t);
var ocT = new DataTable();
foreach (var (n, t) in new (string, Type)[] { ("Id", typeof(string)), ("CompanyId", typeof(string)), ("OrderNumber", typeof(string)), ("SupplierId", typeof(string)),
    ("SupplierName", typeof(string)), ("Status", typeof(byte)), ("CreatedBy", typeof(string)), ("CreatedByName", typeof(string)), ("CreatedAt", typeof(DateTimeOffset)),
    ("ReceivedAt", typeof(DateTimeOffset)), ("TotalEstimado", typeof(decimal)), ("PrevisaoEntrega", typeof(DateTimeOffset)), ("ReceivedBy", typeof(string)), ("ReceivedByName", typeof(string)) }) ocT.Columns.Add(n, t);
var ocItT = new DataTable();
foreach (var (n, t) in new (string, Type)[] { ("Id", typeof(string)), ("OrdemCompraId", typeof(string)), ("ProductCode", typeof(string)), ("ProductName", typeof(string)),
    ("Quantity", typeof(decimal)), ("UnitCost", typeof(decimal)), ("ItemTotal", typeof(decimal)), ("QuantityReceived", typeof(decimal)) }) ocItT.Columns.Add(n, t);

void Mov(Prod p, string tipo, decimal delta, string motivo, string usuId, string usuNome, DateTime utc, decimal custo, string origemTipo, string origemId)
{
    var ant = (decimal)p.Saldo; p.Saldo += (double)delta;
    movs.Rows.Add($"mov-{Guid.NewGuid():N}", Company, p.Id, tipo, Math.Abs(delta), ant, (decimal)p.Saldo, motivo, usuId, usuNome, utc, custo, origemTipo, origemId, "loja");
}

// estoque inicial: entrada de cadastro antes do primeiro dia
foreach (var p in prod) { p.Saldo = 0; Mov(p, EstoqueTipos.Entrada, p.Estoque, "Estoque inicial", "u-demo-gerente", "Gerente Demo", UtcDe(inicio.AddDays(-1), 20, 0), p.Custo, "cadastro", p.Id); }

var saleSeq = 0; var ocSeq = 0;
var pendentes = new List<(Prod P, DateOnly Chega, int Qtd, string Oc, decimal Custo, DateOnly Pedido, int Lead)>();
var ocPorDia = new Dictionary<(string Forn, DateOnly Dia), string>();
var ocItens = new Dictionary<string, List<(Prod P, int Qtd, decimal Custo)>>();
var ocMeta = new Dictionary<string, (string Forn, DateOnly Pedido, DateOnly Prevista, DateOnly? Recebida)>();
var perdasMotivos = new[] { "Vencido", "Avariado na reposicao", "Quebra no transporte", "Furto" };
var inventarioDias = new[] { 330, 270, 210, 150, 90, 30 }.Select(d => hoje.AddDays(-d)).ToHashSet();
var invs = new List<(string Id, int Numero, DateOnly Dia, List<(Prod P, decimal Sistema, decimal Contado)> Itens)>();

Console.WriteLine("Simulando 12 meses...");
for (var d = inicio; d <= ultimo; d = d.AddDays(1))
{
    var diasAteHoje = hoje.DayNumber - d.DayNumber;
    var saz = Sazonal(d);

    // recebimentos do dia
    foreach (var r in pendentes.Where(x => x.Chega == d).ToList())
    {
        var custoNovo = Math.Round(r.Custo * (decimal)(0.97 + rng.NextDouble() * 0.08), 2);
        r.P.Custo = custoNovo;
        Mov(r.P, EstoqueTipos.Entrada, r.Qtd, $"Recebimento do pedido {r.Oc}", "u-demo-estoquista", "Estoquista Demo", UtcDe(d, 9, rng.Next(0, 50)), custoNovo, "pedido_compra", r.Oc);
        pendentes.Remove(r);
    }

    // vendas do dia: demanda por produto -> linhas -> vendas de 1 a 5 linhas
    var linhas = new List<(Prod P, int Qtd)>();
    foreach (var p in prod)
    {
        if (p.Lambda <= 0 || (p.ParouHaDias > 0 && diasAteHoje <= p.ParouHaDias)) continue;
        var q = Poisson(p.Lambda * saz);
        var vend = Math.Min(q, (int)Math.Floor(p.Saldo));
        if (vend <= 0) { if (p.Saldo <= 0) p.DiasRuptura++; continue; }
        linhas.Add((p, vend));
    }
    linhas = linhas.OrderBy(_ => rng.Next()).ToList();
    var pos = 0;
    while (pos < linhas.Count)
    {
        var n = Math.Min(rng.Next(1, 6), linhas.Count - pos);
        var grupo = linhas.GetRange(pos, n); pos += n;
        saleSeq++;
        var numero = saleSeq.ToString(CultureInfo.InvariantCulture);
        var vendaId = $"sale-{Company}-{numero}";
        var quando = LocalDe(d, rng.Next(8, 21), rng.Next(0, 60));
        decimal total = 0;
        foreach (var (p, q) in grupo)
        {
            var itemTotal = p.Venda * q; total += itemTotal;
            itensT.Rows.Add($"vi-{Guid.NewGuid():N}", vendaId, p.Codigo, p.Nome, (decimal)q, p.Venda, itemTotal, 0m, p.Custo);
            Mov(p, EstoqueTipos.SaidaVenda, -q, $"Venda #{numero}", "u-demo-caixa", "Caixa Demo", quando.UtcDateTime, p.Custo, "venda", numero);
        }
        var forma = new[] { "dinheiro", "pix", "cartao_credito", "cartao_debito" }[rng.Next(4)];
        vendasT.Rows.Add(vendaId, Company, numero, "Consumidor", "", forma, "Caixa Demo", quando, total, "finalizada");
        pagT.Rows.Add($"vp-{Guid.NewGuid():N}", Company, vendaId, forma, total, total, 0m, quando);
    }

    // perdas semanais e uso interno mensal
    if (d.DayOfWeek == DayOfWeek.Wednesday)
    {
        for (var k = 0; k < 3; k++)
        {
            var p = prod[rng.Next(NProdutos)];
            var q = Math.Min(rng.Next(1, 5), (int)Math.Floor(p.Saldo));
            if (q > 0) Mov(p, EstoqueTipos.Perda, -q, perdasMotivos[rng.Next(perdasMotivos.Length)], "u-demo-estoquista", "Estoquista Demo", UtcDe(d, 16, 30), p.Custo, "ajuste", "");
        }
    }
    if (d.Day == 5)
    {
        var p = prod[rng.Next(NProdutos)];
        var q = Math.Min(rng.Next(1, 4), (int)Math.Floor(p.Saldo));
        if (q > 0) Mov(p, EstoqueTipos.UsoInterno, -q, "Consumo da loja", "u-demo-estoquista", "Estoquista Demo", UtcDe(d, 17, 0), p.Custo, "ajuste", "");
    }

    // inventarios (contagem de 25 produtos, divergencias pequenas)
    if (inventarioDias.Contains(d))
    {
        var amostra = prod.Where(p => !p.Nunca).OrderBy(_ => rng.Next()).Take(25).ToList();
        var itens = new List<(Prod, decimal, decimal)>();
        foreach (var p in amostra)
        {
            var sis = (decimal)p.Saldo;
            var dif = rng.NextDouble() switch { < 0.78 => 0, < 0.9 => -1, < 0.96 => 1, _ => -rng.Next(2, 5) };
            var cont = Math.Max(0, sis + dif);
            itens.Add((p, sis, cont));
            var delta = cont - sis;
            if (delta != 0) Mov(p, EstoqueTipos.Inventario, delta, "Inventario de demonstracao", "u-demo-gerente", "Gerente Demo (inventario)", UtcDe(d, 19, 0), p.Custo, "inventario", $"inv-demo-{invs.Count + 1}");
        }
        invs.Add(($"inv-demo-{invs.Count + 1}", invs.Count + 1, d, itens));
    }

    // reposicao: pede quando saldo + a caminho <= ponto de pedido
    foreach (var p in prod)
    {
        if (p.Lambda <= 0 || p.Excesso || (p.ParouHaDias > 0 && diasAteHoje <= p.ParouHaDias + 20)) continue;
        var aCaminho = pendentes.Where(x => x.P == p).Sum(x => x.Qtd);
        var leadP = prometido[p.Forn];
        var cobertura = p.RupturaProne ? leadP - 2 : leadP + 4;
        var rop = p.Lambda * Math.Max(2, cobertura);
        if (p.Saldo + aCaminho > rop) continue;
        var qtd = (int)Math.Max(6, Math.Ceiling(p.Lambda * (p.RupturaProne ? 12 : 28)));
        var lead = Math.Max(1, (int)Math.Round(leadP + atrasoMedio[p.Forn] + (rng.NextDouble() - 0.5) * 3));
        var chega = d.AddDays(lead);
        var chave = ($"forn-demo-{p.Forn}", d);
        if (!ocPorDia.TryGetValue(chave, out var ocId))
        {
            ocSeq++; ocId = $"OC-DEMO-{ocSeq:0000}"; ocPorDia[chave] = ocId; ocItens[ocId] = [];
            ocMeta[ocId] = (chave.Item1, d, d.AddDays(leadP), null);
        }
        ocItens[ocId].Add((p, qtd, p.Custo));
        var recebeu = chega <= ultimo;
        if (recebeu)
        {
            var m = ocMeta[ocId];
            // a nota recebe quando o ULTIMO item chega
            ocMeta[ocId] = (m.Forn, m.Pedido, m.Prevista, m.Recebida is { } r0 && r0 > chega ? r0 : chega);
        }
        pendentes.Add((p, chega, qtd, ocId, p.Custo, d, lead));
    }
}

// pedidos de compra gravados
foreach (var (ocId, itens) in ocItens)
{
    var m = ocMeta[ocId];
    var forn = int.Parse(m.Forn[(m.Forn.LastIndexOf('-') + 1)..]);
    var recebida = m.Recebida;
    var temPendente = pendentes.Any(x => x.Oc == ocId);
    var status = temPendente ? (byte)0 : (byte)1;
    ocT.Rows.Add(ocId, Company, ocId, m.Forn, $"Distribuidora {forn + 1}", status, "u-demo-gerente", "Gerente Demo", LocalDe(m.Pedido, 10, 0),
        temPendente || recebida is null ? DBNull.Value : LocalDe(recebida.Value, 10, 0), itens.Sum(i => i.Qtd * i.Custo), LocalDe(m.Prevista, 12, 0),
        temPendente ? DBNull.Value : "u-demo-estoquista", temPendente ? DBNull.Value : "Estoquista Demo");
    foreach (var (p, q, c) in itens)
        ocItT.Rows.Add($"oci-{Guid.NewGuid():N}", ocId, p.Codigo, p.Nome, (decimal)q, c, q * c, pendentes.Any(x => x.Oc == ocId && x.P == p) ? 0m : (decimal)q);
}

Console.WriteLine($"Gravando: {vendasT.Rows.Count} vendas, {itensT.Rows.Count} itens, {movs.Rows.Count} movimentacoes, {ocT.Rows.Count} pedidos...");
async Task Bulk(string tabela, DataTable dt)
{
    using var bc = new SqlBulkCopy(db) { DestinationTableName = tabela, BulkCopyTimeout = 600, BatchSize = 5000 };
    foreach (DataColumn c in dt.Columns) bc.ColumnMappings.Add(c.ColumnName, c.ColumnName);
    await bc.WriteToServerAsync(dt);
}
await Bulk("Vendas", vendasT); await Bulk("VendaItens", itensT); await Bulk("VendaPagamentos", pagT);
await Bulk("OrdemCompra", ocT); await Bulk("OrdemCompraItens", ocItT);

// remove a movimentacao de cadastro criada no SalvarAsync? (qtd 0 -> nao grava) e grava as simuladas
await Bulk("EstoqueMovimentacoes", movs);

// saldo e custo finais dos produtos (consistentes com as movimentacoes) e lead time de alguns produtos
foreach (var p in prod)
{
    await Exec("UPDATE Produtos SET ProductQnt=@Q, ProductUnitPrice=@Cu, TotalPriceOnProduct=@T, LeadTimeDias=@L, EstoqueMinimo=@Min, EstoqueMaximo=@Max WHERE Id=@I AND CompanyId=@C", null,
        ("@Q", (decimal)p.Saldo), ("@Cu", p.Custo), ("@T", (decimal)p.Saldo * p.Custo), ("@L", p.Idx % 5 == 0 ? prometido[p.Forn] + 2 : DBNull.Value),
        ("@Min", Math.Round((decimal)(p.Lambda * 5), 0)), ("@Max", Math.Round((decimal)(p.Lambda * 45), 0) + 10), ("@I", p.Id));
}

// inventarios
Console.WriteLine("Gravando inventarios...");
foreach (var (id, numero, dia, itens) in invs)
{
    await Exec("INSERT INTO Inventarios (Id, CompanyId, Numero, Descricao, Escopo, Status, AbertoPorId, AbertoPorNome, AbertoEm, AplicadoPorId, AplicadoPorNome, AplicadoEm, SupervisorId, SupervisorNome, Justificativa, TotalSobra, TotalFalta, TotalLiquido) " +
        "VALUES (@I, @C, @N, @D, N'produtos', N'aplicado', N'u-demo-estoquista', N'Estoquista Demo', @Em, N'u-demo-estoquista', N'Estoquista Demo', @Em, N'u-demo-gerente', N'Gerente Demo', N'Contagem de demonstracao', @S, @F, @L)", null,
        ("@I", id), ("@N", numero), ("@D", $"Contagem {numero}"), ("@Em", LocalDe(dia, 19, 0)),
        ("@S", itens.Where(x => x.Contado > x.Sistema).Sum(x => (x.Contado - x.Sistema) * x.P.Custo)),
        ("@F", itens.Where(x => x.Contado < x.Sistema).Sum(x => (x.Contado - x.Sistema) * x.P.Custo)),
        ("@L", itens.Sum(x => (x.Contado - x.Sistema) * x.P.Custo)));
    foreach (var (p, sis, cont) in itens)
    {
        await Exec("INSERT INTO InventarioItens (InventarioId, ProdutoId, CompanyId, Codigo, Nome, Unidade, Custo, SaldoNa1aContagem, PrimeiraContagemEm, UltimaContagemEm, Contado, SaldoAntes, Delta, SaldoDepois, ValorDelta) " +
            "VALUES (@Inv, @P, @C, @Cod, @Nome, N'UN', @Cu, @Sis, @Em, @Em, @Cont, @Sis, @Dl, @Cont, @Vd)", null,
            ("@Inv", id), ("@P", p.Id), ("@Cod", p.Codigo), ("@Nome", p.Nome), ("@Cu", p.Custo), ("@Sis", sis), ("@Em", LocalDe(dia, 18, 0)), ("@Cont", cont), ("@Dl", cont - sis), ("@Vd", (cont - sis) * p.Custo));
        await Exec("INSERT INTO InventarioContagens (Id, InventarioId, CompanyId, ProdutoId, Quantidade, Local, UsuarioId, UsuarioNome, SaldoSistema, CriadoEm) VALUES (@Id, @Inv, @C, @P, @Q, N'Loja', N'u-demo-estoquista', N'Estoquista Demo', @Sis, @Em)", null,
            ("@Id", $"ic-{Guid.NewGuid():N}"), ("@Inv", id), ("@P", p.Id), ("@Q", cont), ("@Sis", sis), ("@Em", LocalDe(dia, 18, 0)));
    }
}

// fotografias diarias: para cada dia do periodo, reconstruidas pelas movimentacoes
Console.WriteLine("Reconstruindo as fotografias diarias...");
var snap = new EstoqueSnapshotAB(connection);
for (var d = inicio; d <= ultimo; d = d.AddDays(1))
    await EstoqueSnapshotAB.GravarAsync(db, null, Company, d);

// classificacao ABC/FSN (o worker recalcula todo dia; aqui ja deixa pronta)
Console.WriteLine("Classificando ABC/FSN...");
await new EstoqueClassificacaoAB(connection).RecalcularAsync(Company, EstoqueParametros.Padrao);

// conferencia: soma das movimentacoes = saldo
await using (var chk = new SqlCommand(
    "SELECT COUNT(*) FROM Produtos p WHERE p.CompanyId=@C AND p.ProductQnt <> (SELECT ISNULL(SUM(EstoqueNovo-EstoqueAnterior),0) FROM EstoqueMovimentacoes m WHERE m.ProdutoId=p.Id)", db))
{
    chk.Parameters.AddWithValue("@C", Company);
    var errados = Convert.ToInt32(await chk.ExecuteScalarAsync());
    Console.WriteLine(errados == 0 ? "Conciliacao OK (soma das movimentacoes = saldo em todos os produtos)." : $"ATENCAO: {errados} produtos com saldo diferente da soma das movimentacoes.");
    if (errados != 0) return 1;
}
Console.WriteLine($"Pronto. Empresa '{Company}': gerente.demo@teste.com / estoquista.demo@teste.com (senha Demo@12345).");
return 0;

sealed class Prod
{
    public int Idx; public string Id = "", Codigo = "", Nome = ""; public int Cat, Forn; public decimal Custo, Venda;
    public double Lambda; public int Estoque; public double Saldo; public int ParouHaDias; public bool Nunca, Excesso, RupturaProne; public int DiasRuptura;
}
