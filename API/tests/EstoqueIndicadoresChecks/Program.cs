// Checks da analise de estoque (API/NETCORE) contra o SQL Server de TESTE (padrao: pdv-teste-sql em localhost,14333).
// Uso: dotnet run --project API/tests/EstoqueIndicadoresChecks [-- --connection "<connection string>"]
// Tambem le HORUSPDV_CONNECTION_STRING. Recusa qualquer servidor que nao seja localhost.
using HORUSPDV_API.Models.Requests;
using HORUSPDV_API.Repositories;
using HORUSPDV_API.Repositories.DataAccess;
using HORUSPDV_API.Repositories.DatabaseAccess;
using HORUSPDV_API.Services.Estoque;
using HORUSPDV_API.Services.Shared;
using Microsoft.Data.SqlClient;
using Microsoft.Extensions.Logging.Abstractions;

var argv = Environment.GetCommandLineArgs();
var idx = Array.IndexOf(argv, "--connection");
var connectionString = idx >= 0 && idx + 1 < argv.Length ? argv[idx + 1]
    : Environment.GetEnvironmentVariable("HORUSPDV_CONNECTION_STRING")
    ?? "Server=localhost,14333;Database=HorusPdv;User ID=sa;Password=Teste#Caixa2026;TrustServerCertificate=True;Encrypt=True;MultipleActiveResultSets=True;Connect Timeout=15";
var builder = new SqlConnectionStringBuilder(connectionString);
var host = builder.DataSource.Split(',')[0].Trim().ToLowerInvariant();
if (host is not ("localhost" or "127.0.0.1" or "(local)" or "." or "pdv-teste-sql"))
{ Console.Error.WriteLine("Recusado: estes checks so rodam em SQL Server local de teste."); return 2; }

var company = "est-checks-" + Guid.NewGuid().ToString("N")[..8];
var configuration = new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string, string?> { ["ConnectionStrings:HorusPdv"] = connectionString }).Build();
var connection = new Connection(configuration);
var lotes = new LoteAB(connection, NullLogger<LoteAB>.Instance);
var produtos = new ProdutoAB(connection);
var vendas = new HistoricoVendasAB(connection, new FiadoAB(connection), new AuditLogAB(connection), lotes);
var inventarios = new InventarioAB(connection, lotes, NullLogger<InventarioAB>.Instance);

var passed = 0;
void Check(bool ok, string name) { if (!ok) throw new Exception("FALHOU: " + name); passed++; Console.WriteLine("PASS " + name); }

async Task<T> Query<T>(string sql, Func<SqlDataReader, T> read, params (string, object)[] ps)
{
    await using var db = await connection.OpenConnectionAsync();
    await using var cmd = new SqlCommand(sql, db);
    cmd.Parameters.AddWithValue("@C", company);
    foreach (var (n, v) in ps) cmd.Parameters.AddWithValue(n, v);
    await using var r = await cmd.ExecuteReaderAsync();
    await r.ReadAsync();
    return read(r);
}
async Task<decimal> Saldo(string id) => await Query("SELECT ProductQnt FROM Produtos WHERE Id=@I AND CompanyId=@C", r => r.GetDecimal(0), ("@I", id));
async Task<decimal> SomaMov(string id) => await Query("SELECT ISNULL(SUM(EstoqueNovo-EstoqueAnterior),0) FROM EstoqueMovimentacoes WHERE ProdutoId=@I AND CompanyId=@C", r => r.GetDecimal(0), ("@I", id));
async Task<int> Qtd(string where, params (string, object)[] ps) => await Query($"SELECT COUNT(*) FROM EstoqueMovimentacoes WHERE CompanyId=@C AND {where}", r => r.GetInt32(0), ps);

// Conciliacao: para produtos criados pelo proprio fluxo, soma(novo-anterior) = saldo; e a cadeia anterior/novo e continua.
async Task Conciliar(string label, params string[] ids)
{
    foreach (var id in ids)
    {
        var saldo = await Saldo(id);
        var soma = await SomaMov(id);
        Check(saldo == soma, $"{label}: soma das movimentacoes ({soma}) = saldo ({saldo}) [{id}]");
    }
    var quebras = await Query(
        """
        SELECT COUNT(*) FROM (
          SELECT EstoqueAnterior, LAG(EstoqueNovo) OVER (PARTITION BY ProdutoId ORDER BY CriadoEm, Id) AS Prev
          FROM EstoqueMovimentacoes WHERE CompanyId=@C) x WHERE Prev IS NOT NULL AND Prev <> EstoqueAnterior
        """, r => r.GetInt32(0));
    Check(quebras == 0, $"{label}: cadeia anterior/novo continua");
}

ProdutoAD Novo(string id, string codigo, decimal qtd, decimal custo, decimal venda) => new()
{
    Id = id, ProductCode = codigo, ProductName = "Produto " + codigo, ProductQnt = qtd, ProductUnitPrice = custo,
    ProductSalePrice = venda, TotalPriceOnProduct = custo * qtd, Lucro = venda - custo, Ncm = "00000000", Cfop = "5102",
    UnidadeComercial = "UN", UnidadeTributavel = "UN", Gtin = "SEM GTIN", CsosnIcms = "102", CstPis = "07", CstCofins = "07",
};

// ---------- Formulas puras (EstoqueCalculos): exemplos numericos do spec e bordas ----------
bool Perto(decimal? a, decimal b, decimal tol = 0.01m) => a is { } v && Math.Abs(v - b) <= tol;
Check(EstoqueCalculos.ConsumoMedio(30, 30) == 1m, "consumo: 30 unidades em 30 dias = 1,0/dia");
var demandas = Enumerable.Repeat(0m, 20).Concat(Enumerable.Repeat(3m, 10)).ToList(); // 30 vendidas em 10 dos 30 dias
Check(demandas.Sum() == 30m && EstoqueCalculos.ConsumoMedio(demandas.Sum(), 30) == 1m, "consumo com dias sem venda");
Check(Perto(EstoqueCalculos.DesvioPadraoDemanda(demandas), 1.414m, 0.001m), "desvio considera os 20 dias com demanda zero (1,414)");
Check(EstoqueCalculos.DesvioPadraoDemanda(demandas) == EstoqueCalculos.DesvioPadraoDemanda(30m, 90m, 30), "desvio por totais = desvio pela lista");
Check(EstoqueCalculos.ConsumoMedio(0, 30) == 0m && EstoqueCalculos.DesvioPadraoDemanda(0m, 0m, 30) == 0m && EstoqueCalculos.DesvioPadraoDemanda(Array.Empty<decimal>()) == 0m, "sem historico: consumo 0 e desvio 0");
Check(EstoqueCalculos.Cobertura(20, 0) is null, "cobertura sem consumo = sem giro (null, nao infinito)");
Check(EstoqueCalculos.Cobertura(20, 4) == 5m && EstoqueCalculos.Cobertura(-3, 4) == 0m, "cobertura = saldo / consumo; saldo negativo conta 0");
var giro = EstoqueCalculos.Giro(3000m, 6000m);
var giroAnual = EstoqueCalculos.GiroAnualizado(giro, 30);
Check(giro == 0.5m && Perto(giroAnual, 6.0833m, 0.001m) && Perto(EstoqueCalculos.Dio(giroAnual), 60m, 0.01m), "giro 0,5; anualizado ~6,08; DIO ~60 dias");
Check(EstoqueCalculos.Giro(3000m, null) is null && EstoqueCalculos.Giro(3000m, 0m) is null && EstoqueCalculos.Dio(null) is null && EstoqueCalculos.Dio(0m) is null, "giro sem fotografias = vazio (sem historico)");
var seg = EstoqueCalculos.EstoqueSeguranca(1.65m, 4m, 9);
var rop = EstoqueCalculos.PontoPedido(10m, 9, seg);
var alvo = EstoqueCalculos.EstoqueAlvo(10m, 9, 15, seg);
Check(Perto(seg, 19.8m) && Perto(rop, 109.8m) && Perto(alvo, 259.8m), "seguranca 19,8; ponto de pedido 109,8; alvo 259,8");
Check(Perto(EstoqueCalculos.SugestaoCompra(alvo, 80m, 50m, true), 129.8m) && EstoqueCalculos.SugestaoCompra(alvo, 80m, 50m, false) == 130m, "sugestao 129,8 (fracionado) / 130 (unidade inteira, para cima)");
Check(EstoqueCalculos.SugestaoCompra(alvo, 200m, 100m, false) == 0m, "pedido em transito cobre a necessidade: sugestao 0");
Check(EstoqueCalculos.SugestaoCompra(100m, -5m, 0m, false) == 100m, "saldo negativo nao aumenta a sugestao alem do alvo");
Check(EstoqueCalculos.LeadTimeEfetivo(5, 8.2m, 6) == 5 && EstoqueCalculos.LeadTimeEfetivo(null, 8.2m, 6) == 9 && EstoqueCalculos.LeadTimeEfetivo(null, null, 6) == 6 && EstoqueCalculos.LeadTimeEfetivo(null, null, null) == 7, "lead time: produto > real > prometido > 7");
Check(EstoqueCalculos.LeadTimeEfetivo(null, null, null, 10) == 10, "lead time padrao configuravel");
Check(EstoqueCalculos.TaxaRupturaPercentual(6, 30) == 20m && EstoqueCalculos.TaxaRupturaPercentual(0, 0) == 0m, "ruptura: 6 de 30 dias = 20%");
var ac = EstoqueCalculos.Acuracidade([(10m, 10m), (20m, 18m), (5m, 7m)]);
Check(ac is not null && Perto(ac.AcuraciaPercentual, 94.2857m, 0.001m) && Perto(ac.ItensExatosPercentual, 33.333m, 0.01m), "acuracidade 94,29% e 33,3% dos itens bateram");
Check(EstoqueCalculos.Acuracidade([]) is null, "acuracidade sem contagens = vazio");
Check(EstoqueCalculos.ValorExcesso(500m, 2m, 3m, 120) == 780m && EstoqueCalculos.Cobertura(500m, 2m) == 250m, "excesso: cobertura 250 dias, valor R$ 780,00");
Check(EstoqueCalculos.ValorExcesso(100m, 2m, 3m, 120) == 0m && EstoqueCalculos.ValorExcesso(500m, 0m, 3m, 120) == 0m, "sem excesso / sem consumo = 0");
Check(EstoqueCalculos.ValorEmEstoque(-4m, 10m) == 0m && EstoqueCalculos.ValorEmEstoque(3m, 10m) == 30m, "valor em estoque: saldo negativo conta 0");
var abc = EstoqueCalculos.ClassificarAbc([("a", 700m), ("b", 150m), ("c", 100m), ("d", 40m), ("e", 10m), ("f", 0m)]);
Check(abc["a"] == 'A' && abc["b"] == 'B' && abc["c"] == 'B' && abc["d"] == 'C' && abc["e"] == 'C' && abc["f"] == 'C', "ABC 80/95: a=70%->A, b=85%->B, c=95%->B, d,e,f->C");
Check(EstoqueCalculos.ClassificarAbc([("x", 0m), ("y", 0m)]).Values.All(c => c == 'C'), "ABC sem valor = tudo C");
Check(EstoqueCalculos.ClassificarFsn(0, 13) == 'N' && EstoqueCalculos.ClassificarFsn(7, 13) == 'F' && EstoqueCalculos.ClassificarFsn(6, 13) == 'S' && EstoqueCalculos.ClassificarFsn(3, 0) == 'N', "FSN: N sem saida, F >= 50% das semanas, S nos demais");
Check(Perto(EstoqueCalculos.VariacaoPercentual(120m, 100m), 20m) && EstoqueCalculos.VariacaoPercentual(5m, 0m) is null, "variacao percentual (anterior zero = vazio)");
Check(EstoqueParametros.Padrao is { JanelaConsumoDias: 30, NivelServicoZ: 1.65m, AbcLimiteA: 80m, AbcLimiteB: 95m, DiasParado: 90, DiasExcesso: 120, CoberturaAlvoDias: 15, LeadTimePadraoDias: 7 }, "parametros padrao do spec");

try
{
    Check(await Query("SELECT COUNT(*) FROM sys.tables WHERE name IN ('EstoqueSnapshotDiario','EstoqueConfigAnalise','ProdutoClassificacao')", r => r.GetInt32(0)) == 3, "migracao 57: tabelas criadas");
    Check(await Query("SELECT COL_LENGTH('EstoqueMovimentacoes','CustoUnitario') + COL_LENGTH('EstoqueMovimentacoes','OrigemTipo') + COL_LENGTH('EstoqueMovimentacoes','OrigemId') + COL_LENGTH('EstoqueMovimentacoes','DepositoId') + COL_LENGTH('Produtos','LeadTimeDias') + COL_LENGTH('Fornecedores','LeadTimePrometidoDias')", r => !r.IsDBNull(0)), "migracao 57: colunas criadas");

    // Cadastro: quantidade inicial vira movimentacao de entrada (origem cadastro)
    var user = new MovimentoOrigem("cadastro", null, "Cadastro de teste", "u-teste", "Usuario Teste");
    await produtos.SalvarAsync(company, Novo("p-a", "A", 10, 4m, 9m), user);
    await produtos.SalvarAsync(company, Novo("p-b", "B", 0, 2m, 5m), user);
    Check(await Qtd("ProdutoId='p-a' AND Tipo='entrada' AND OrigemTipo='cadastro' AND EstoqueAnterior=0 AND EstoqueNovo=10 AND UsuarioNome=N'Usuario Teste'") == 1, "cadastro com saldo inicial grava entrada");
    Check(await Qtd("ProdutoId='p-b'") == 0, "cadastro com saldo zero nao grava movimentacao");

    // Edicao com quantidade diferente (para mais e para menos) e igual (nada)
    await produtos.SalvarAsync(company, Novo("p-a", "A", 12, 4m, 9m), user);
    await produtos.SalvarAsync(company, Novo("p-a", "A", 11, 4m, 9m), user);
    await produtos.SalvarAsync(company, Novo("p-a", "A", 11, 4m, 9m), user);
    Check(await Qtd("ProdutoId='p-a' AND OrigemTipo='cadastro'") == 3, "edicao grava so quando a quantidade muda");
    Check(await Saldo("p-a") == 11, "saldo apos edicoes");

    // Ajuste avulso (entrada, perda, uso interno) com tipo real
    await produtos.AjustarEstoqueAsync(company, "p-a", "entrada", 5, "Achado", "u1", "Fulano");
    await produtos.AjustarEstoqueAsync(company, "p-a", "perda", 2, "Quebra", "u1", "Fulano");
    await produtos.AjustarEstoqueAsync(company, "p-a", "uso_interno", 1, "Consumo", "u1", "Fulano");
    Check(await Qtd("ProdutoId='p-a' AND Tipo='perda' AND CustoUnitario=4 AND Quantidade=2") == 1, "perda guarda custo e tipo");
    Check(await Qtd("ProdutoId='p-a' AND Tipo='uso_interno'") == 1, "uso interno guarda tipo");
    var recusou = false;
    try { await produtos.AjustarEstoqueAsync(company, "p-a", "saida", 9999, "x", "u1", "Fulano"); } catch (InvalidOperationException) { recusou = true; }
    Check(recusou && await Saldo("p-a") == 13, "saida acima do saldo e recusada sem alterar nada");
    await Conciliar("cadastro+ajustes", "p-a", "p-b");

    // Entrada (NF-e/manual/pedido) com custo novo e estorno
    await produtos.EntradaEstoqueAsync(company, "p-b", 20, 3m, origem: new MovimentoOrigem("nfe", "CHAVE123", "Entrada por NF-e", "u2", "Compras"));
    Check(await Qtd("ProdutoId='p-b' AND Tipo='entrada' AND OrigemTipo='nfe' AND OrigemId='CHAVE123' AND CustoUnitario=3") == 1, "entrada por nota guarda custo e documento");
    await produtos.EstornarEstoqueAsync(company, "p-b", 5, new MovimentoOrigem("nota_entrada", "nota-1", "Estorno", "u2", "Compras"));
    Check(await Qtd("ProdutoId='p-b' AND Tipo='saida' AND OrigemTipo='nota_entrada'") == 1 && await Saldo("p-b") == 15, "estorno de nota gera saida");
    await produtos.BaixarEstoqueAsync(company, [("B", 3m)]);
    Check(await Saldo("p-b") == 12, "baixa avulsa");
    await Conciliar("entradas/estorno/baixa", "p-a", "p-b");

    // Rollback desfaz saldo e movimentacao juntos
    await using (var db = await connection.OpenConnectionAsync())
    {
        await using var tx = (SqlTransaction)await db.BeginTransactionAsync();
        await EstoqueMovimentacaoAB.AplicarMovimentoAsync(db, tx, company, "p-b", -4, EstoqueTipos.Saida, null, MovimentoOrigem.Sistema("ajuste", null, "teste"));
        await tx.RollbackAsync();
    }
    Check(await Saldo("p-b") == 12, "rollback desfaz o saldo");
    await Conciliar("rollback", "p-a", "p-b");

    // Venda -> cancelamento -> correcao
    VendaRequest Venda(params (string Code, decimal Qty, decimal Price)[] itens) => new()
    {
        ClientSaleId = Guid.NewGuid().ToString(), EventId = Guid.NewGuid().ToString(), ReenvioOffline = true,
        CustomerName = "Cliente", CustomerCpf = "", OperatorName = "Caixa", PaymentType = "dinheiro",
        TotalAmount = HorusMoneyFormat.Format(itens.Sum(i => i.Qty * i.Price)),
        Items = itens.Select(i => new VendaItemRequest { ProductCode = i.Code, ProductName = "x", Quantity = i.Qty, UnitPrice = i.Price }).ToList(),
        Payments = [new() { PaymentType = "dinheiro", Amount = itens.Sum(i => i.Qty * i.Price), CashGiven = itens.Sum(i => i.Qty * i.Price), ChangeAmount = 0 }],
    };
    var antesA = await Saldo("p-a");
    var v1 = await vendas.RegistrarAsync(company, Venda(("A", 3, 9m)));
    Check(await Saldo("p-a") == antesA - 3, "venda baixa o saldo");
    Check(await Qtd("ProdutoId='p-a' AND Tipo='saida_venda' AND OrigemTipo='venda' AND OrigemId=@N AND Quantidade=3 AND EstoqueAnterior=@A AND EstoqueNovo=@B AND CustoUnitario=4",
        ("@N", v1.SaleNumber), ("@A", antesA), ("@B", antesA - 3)) == 1, "venda registra saida_venda com numero da venda, anterior/novo e custo");
    await Conciliar("venda", "p-a", "p-b");

    await vendas.CancelarVendaComSupervisorAsync(company, v1.SaleNumber, "sup", "Gerente", "op", "Caixa", "Cliente desistiu");
    Check(await Saldo("p-a") == antesA && await Qtd("ProdutoId='p-a' AND Tipo='devolucao' AND OrigemTipo='venda' AND OrigemId=@N", ("@N", v1.SaleNumber)) == 1, "cancelamento devolve e registra devolucao");
    await Conciliar("cancelamento", "p-a", "p-b");

    var v2 = await vendas.RegistrarAsync(company, Venda(("A", 2, 9m), ("B", 1, 5m)));
    var corr = await vendas.CorrigirVendaAsync(company, v2.SaleNumber, ["B"], "fin", "Financeiro", "Item errado");
    Check(await Qtd("Tipo='devolucao' AND OrigemTipo='correcao' AND OrigemId=@N", ("@N", v2.SaleNumber)) == 2, "correcao devolve os itens da venda original");
    Check(await Qtd("Tipo='saida_venda' AND OrigemTipo='correcao' AND OrigemId=@N", ("@N", corr.NovaVendaNumber)) == 1, "correcao baixa a venda nova com o novo numero");
    await Conciliar("correcao", "p-a", "p-b");

    // Devolucao/cancelamento fiscal (helper compartilhado pelas rotas de NFC-e/NF-e)
    var v3 = await vendas.RegistrarAsync(company, Venda(("A", 1, 9m)));
    var antesFiscal = await Saldo("p-a");
    await using (var db = await connection.OpenConnectionAsync())
    {
        await using var tx = (SqlTransaction)await db.BeginTransactionAsync();
        var vendaId = $"sale-{company}-{v3.SaleNumber}";
        var n = await EstoqueMovimentacaoAB.DevolverItensDaVendaAsync(db, tx, company, vendaId, "nfce", "Cancelamento da NFC-e da venda #{venda}", "sup", "Gerente");
        await tx.CommitAsync();
        Check(n == 1, "devolucao fiscal encontra os itens da venda");
    }
    Check(await Saldo("p-a") == antesFiscal + 1 && await Qtd("Tipo='devolucao' AND OrigemTipo='nfce' AND OrigemId=@N", ("@N", v3.SaleNumber)) == 1, "cancelamento de NFC-e devolve com origem nfce");
    await Conciliar("fiscal", "p-a", "p-b");

    // Inventario
    var ator = new InventarioAtor("u-inv", "Estoquista", null, "T1");
    var abrir = await inventarios.AbrirAsync(company, ator, "Teste", "produtos", null, ["p-a"]);
    Check(abrir.Sucesso, "inventario abre: " + abrir.Mensagem);
    var invId = System.Text.Json.JsonSerializer.SerializeToElement(abrir.Dados).EnumerateObject().First(p => p.Name.Equals("id", StringComparison.OrdinalIgnoreCase)).Value.GetString()!;
    var saldoAntesInv = await Saldo("p-a");
    var cont = await inventarios.ContarAsync(company, invId, ator, "p-a", null, saldoAntesInv - 2, "Prateleira");
    Check(cont.Sucesso, "contagem registrada: " + cont.Mensagem);
    var aplicar = await inventarios.AplicarAsync(company, invId, ator, "sup", "Gerente", "Contagem de teste", false);
    Check(aplicar.Sucesso, "inventario aplicado: " + aplicar.Mensagem);
    Check(await Saldo("p-a") == saldoAntesInv - 2 && await Qtd("ProdutoId='p-a' AND Tipo='inventario' AND CustoUnitario=4 AND Quantidade=2") == 1, "inventario grava movimentacao inventario com custo");
    await Conciliar("inventario", "p-a", "p-b");
    var indic = new EstoqueIndicadoresAB(connection);
    var acInv = await indic.AcuraciaAsync(company, FiltroAnalise.UltimosDias(1));
    Check(acInv is { Itens: 1 } && Perto(acInv.AcuraciaPercentual, 100m * (saldoAntesInv - 2) / saldoAntesInv, 0.01m), "acuracidade da contagem aplicada (sistema x contado)");

    // Fotografia diaria: idempotente e reconstrucao pelo saldo atual menos movimentacoes posteriores
    await produtos.SalvarAsync(company, Novo("p-s", "S", 10, 4m, 9m), user);
    await using (var db = await connection.OpenConnectionAsync())
    {
        await using var cmd = new SqlCommand("UPDATE EstoqueMovimentacoes SET CriadoEm = DATEADD(day,-5,SYSUTCDATETIME()) WHERE CompanyId=@C AND ProdutoId='p-s'", db);
        cmd.Parameters.AddWithValue("@C", company);
        await cmd.ExecuteNonQueryAsync();
    }
    await produtos.AjustarEstoqueAsync(company, "p-s", "entrada", 5, "Hoje", "u1", "Fulano");
    await produtos.SalvarAsync(company, Novo("p-novo", "NOVO", 7, 1m, 2m), user); // cadastrado hoje: nao existia ontem
    var snap = new EstoqueSnapshotAB(connection);
    var ontem = EstoqueSnapshotAB.Ontem();
    await snap.GravarAsync(company, ontem);
    await snap.GravarAsync(company, ontem);
    async Task<int> Linhas(string where) => await Query($"SELECT COUNT(*) FROM EstoqueSnapshotDiario WHERE CompanyId=@C AND Data=@D {where}", r => r.GetInt32(0), ("@D", ontem.ToDateTime(TimeOnly.MinValue)));
    Check(await Linhas("AND ProdutoId='p-s'") == 1, "fotografia repetida nao duplica");
    Check(await Query("SELECT Quantidade FROM EstoqueSnapshotDiario WHERE CompanyId=@C AND ProdutoId='p-s' AND Data=@D", r => r.GetDecimal(0), ("@D", ontem.ToDateTime(TimeOnly.MinValue))) == 10m, "reconstrucao: ontem tinha 10 (hoje 15, +5 hoje)");
    Check(await Query("SELECT Valor FROM EstoqueSnapshotDiario WHERE CompanyId=@C AND ProdutoId='p-s' AND Data=@D", r => r.GetDecimal(0), ("@D", ontem.ToDateTime(TimeOnly.MinValue))) == 40m, "valor da fotografia = quantidade x custo");
    Check(await Linhas("AND ProdutoId='p-novo'") == 0, "produto cadastrado hoje nao entra na fotografia de ontem");
    var hoje = DateOnly.FromDateTime(HORUSPDV_API.Services.Shared.HorusDateTime.Now.Date);
    Check(EstoqueSnapshotAB.FimDoDiaUtc(hoje.AddDays(-1)) <= DateTime.UtcNow, "fim do dia de ontem (UTC) ja passou");
    await Conciliar("fotografia", "p-s", "p-novo");

    // ---------- Etapa 2.2/2.3/2.4: agregacoes com dados controlados ----------
    var hojeDia = FiltroAnalise.Hoje();
    DateTime Dia(int diasAtras, int hora = 15) => EstoqueSnapshotAB.FimDoDiaUtc(hojeDia.AddDays(-diasAtras - 1)).AddHours(hora);
    async Task Exec(string sql, params (string, object)[] ps)
    {
        await using var db = await connection.OpenConnectionAsync();
        await using var cmd = new SqlCommand(sql, db);
        foreach (var (n, v) in ps) cmd.Parameters.AddWithValue(n, v);
        await cmd.ExecuteNonQueryAsync();
    }
    async Task Mov(string comp, string prod, string tipo, decimal qtd, decimal custo, DateTime utc) => await Exec(
        "INSERT INTO EstoqueMovimentacoes (Id, CompanyId, ProdutoId, Tipo, Quantidade, EstoqueAnterior, EstoqueNovo, Motivo, UsuarioId, UsuarioNome, CriadoEm, CustoUnitario, OrigemTipo) " +
        "VALUES (@I, @C, @P, @T, @Q, 100, CASE WHEN @T IN ('saida_venda','saida','perda','uso_interno') THEN 100 - @Q ELSE 100 + @Q END, N'check', 'u', 'u', @D, @K, 'venda')",
        ("@I", Guid.NewGuid().ToString("N")), ("@C", comp), ("@P", prod), ("@T", tipo), ("@Q", qtd), ("@D", utc), ("@K", custo));
    async Task Snap(string comp, string prod, int diasAtras, decimal qtd, decimal custo) => await Exec(
        "INSERT INTO EstoqueSnapshotDiario (CompanyId, DepositoId, Data, ProdutoId, Quantidade, Valor, CustoUnitario) VALUES (@C, 'loja', @D, @P, @Q, @V, @K)",
        ("@C", comp), ("@D", hojeDia.AddDays(-diasAtras).ToDateTime(TimeOnly.MinValue)), ("@P", prod), ("@Q", qtd), ("@V", qtd > 0 ? qtd * custo : 0m), ("@K", custo));
    async Task Pedido(string num, string forn, int criadoAtras, int? recebidoAtras, int? previsaoAtras, int status, string? codigo = null, decimal qtd = 0, decimal recebida = 0)
    {
        var id = "oc-" + num + "-" + company;
        await Exec(
            "INSERT INTO OrdemCompra (Id, CompanyId, OrderNumber, SupplierId, SupplierName, Status, CreatedAt, ReceivedAt, PrevisaoEntrega) " +
            "VALUES (@I, @C, @N, @F, N'Fornecedor', @S, @Cr, @Rc, @Pv)",
            ("@I", id), ("@C", company), ("@N", num), ("@F", forn), ("@S", status),
            ("@Cr", new DateTimeOffset(Dia(criadoAtras, 12), TimeSpan.Zero)),
            ("@Rc", recebidoAtras is { } r ? new DateTimeOffset(Dia(r, 12), TimeSpan.Zero) : DBNull.Value),
            ("@Pv", previsaoAtras is { } v ? new DateTimeOffset(Dia(v, 12), TimeSpan.Zero) : DBNull.Value));
        if (codigo is not null)
            await Exec("INSERT INTO OrdemCompraItens (Id, OrdemCompraId, ProductCode, ProductName, Quantity, UnitCost, ItemTotal, QuantityReceived) VALUES (@I, @O, @P, N'x', @Q, 1, @Q, @R)",
                ("@I", id + "-i"), ("@O", id), ("@P", codigo), ("@Q", qtd), ("@R", recebida));
    }

    await Exec("INSERT INTO Empresas (Id) VALUES (@C)", ("@C", company));
    await Exec("INSERT INTO Categorias (Id, CompanyId, Nome) VALUES (@I, @C, N'Pai')", ("@I", "c-pai-" + company), ("@C", company));
    await Exec("INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId) VALUES (@I, @C, N'Filha', @P)", ("@I", "c-fil-" + company), ("@C", company), ("@P", "c-pai-" + company));
    await Exec("INSERT INTO Categorias (Id, CompanyId, Nome) VALUES (@I, @C, N'Outra')", ("@I", "c-out-" + company), ("@C", company));
    await Exec("INSERT INTO Fornecedores (Id, CompanyId, CompanyName, FantasyName, LeadTimePrometidoDias) VALUES (@I, @C, N'Forn Um', N'Forn Um', 12)", ("@I", "f1-" + company), ("@C", company));
    await Exec("INSERT INTO Fornecedores (Id, CompanyId, CompanyName, FantasyName) VALUES (@I, @C, N'Forn Dois', N'Forn Dois')", ("@I", "f2-" + company), ("@C", company));

    await produtos.SalvarAsync(company, Novo("d-1", "D1", 70, 4m, 9m), user);
    await produtos.SalvarAsync(company, Novo("d-2", "D2", 20, 5m, 9m), user);
    await Exec("UPDATE Produtos SET CategoriaId=@Cat, SupplierId=@F WHERE Id='d-1' AND CompanyId=@C", ("@Cat", "c-fil-" + company), ("@F", "f1-" + company), ("@C", company));
    await Exec("UPDATE Produtos SET CategoriaId=@Cat, SupplierId=@F, LeadTimeDias=5 WHERE Id='d-2' AND CompanyId=@C", ("@Cat", "c-out-" + company), ("@F", "f1-" + company), ("@C", company));
    // d-1: 3 unidades em cada um de 10 dias (30 vendidas); d-2: dia com venda 5 e devolucao 2 (liquido 3) e dia so com devolucao (ignorado)
    for (var k = 1; k <= 10; k++) await Mov(company, "d-1", "saida_venda", 3, 4m, Dia(k));
    await Mov(company, "d-2", "saida_venda", 5, 5m, Dia(2));
    await Mov(company, "d-2", "devolucao", 2, 5m, Dia(2, 18));
    await Mov(company, "d-2", "devolucao", 1, 5m, Dia(4));
    // fotografias: d-1 com 5 dias no periodo (2 em ruptura) e 1 no fim do periodo anterior
    foreach (var (d, q) in new[] { (5, 100m), (4, 100m), (3, 100m), (2, 0m), (1, 0m), (30, 50m) }) await Snap(company, "d-1", d, q, 4m);
    // pedidos: 3 recebidos do fornecedor 1 (lead 8, 6 e 4 dias; previsao: no prazo, atrasado, sem previsao) e 1 em transito (40, 10 recebidas)
    await Pedido("T1", "f1-" + company, 20, 12, 11, 1);
    await Pedido("T2", "f1-" + company, 10, 4, 5, 1);
    await Pedido("T3", "f1-" + company, 6, 2, null, 1);
    await Pedido("T4", "f1-" + company, 1, null, null, 3, "D1", 40, 10);

    var f30 = FiltroAnalise.UltimosDias(30, fornecedorId: "f1-" + company);
    var agg = (await indic.AgregarAsync(company, f30)).ToDictionary(a => a.ProdutoId);
    Check(agg.Count == 2, "filtro por fornecedor traz so os 2 produtos do fornecedor");
    var a1 = agg["d-1"]; var a2 = agg["d-2"];
    Check(a1.SaidasLiquidas == 30m && a1.DiasComVenda == 10 && a1.SomaQuadrados == 90m && a1.Cmv == 120m, "d-1: 30 vendidas em 10 dias, soma dos quadrados 90, CMV 120");
    Check(a2.SaidasLiquidas == 3m && a2.SomaQuadrados == 9m && a2.DiasComVenda == 1 && a2.Cmv == 10m, "d-2: venda 5 - devolucao 2 no mesmo dia = demanda 3; devolucao sem venda no dia nao entra na demanda (mas abate o CMV: 15 - 5 = 10)");
    Check(a1.DiasFotografados == 5 && a1.DiasRuptura == 2 && a1.EstoqueMedioValor == 240m && a2.EstoqueMedioValor is null, "d-1: 5 fotografias, 2 em ruptura, estoque medio R$ 240; d-2 sem fotografia");
    Check(a1.EmTransito == 30m && a2.EmTransito == 0m, "pedido em transito = quantidade - recebida (30)");
    Check(a1.UltimaSaidaUtc is not null, "ultima saida preenchida");

    var parametros = EstoqueParametros.Padrao;
    var lead = (await indic.LeadTimeFornecedoresAsync(company)).First(l => l.FornecedorId == "f1-" + company);
    Check(lead.Pedidos == 3 && Perto(lead.MediaDias, 6m, 0.01m) && Perto(lead.DesvioDias, 1.63m, 0.01m), "lead time real: 3 pedidos, media 6 dias, desvio 1,63");
    Check(lead.PedidosComPrevisao == 2 && lead.PedidosNoPrazo == 1 && lead.NoPrazoPercentual == 50m && lead.PrometidoDias == 12, "no prazo 50% (pedido sem previsao fora do calculo); prometido 12");
    var f2l = (await indic.LeadTimeFornecedoresAsync(company)).First(l => l.FornecedorId == "f2-" + company);
    Check(f2l.Pedidos == 0 && f2l.MediaDias is null && f2l.NoPrazoPercentual is null, "fornecedor sem pedidos recebidos: lead time vazio");

    var indicProdutos = (await indic.ProdutosAsync(company, f30, parametros)).ToDictionary(i => i.ProdutoId);
    var i1 = indicProdutos["d-1"]; var i2 = indicProdutos["d-2"];
    Check(i1.Consumo == 1m && Perto(i1.Desvio, 1.414m, 0.001m), "d-1: consumo 1,0/dia e desvio 1,414 (20 dias zero)");
    Check(i1.LeadTimeDias == 6 && i2.LeadTimeDias == 5, "lead time: real do fornecedor (6) sem lead do produto; lead do produto (5) tem prioridade");
    Check(Perto(i1.EstoqueSeguranca, 1.65m * 1.414m * (decimal)Math.Sqrt(6), 0.01m) && Perto(i1.CoberturaDias, 70m), "d-1: seguranca = Z x desvio x raiz(lead); cobertura 70 dias");
    Check(i1.Saldo == 70m && i1.ValorEstoque == 280m && i1.DiasSemSaida == 1 && !i1.Parado && !i1.EmRuptura, "d-1: saldo 70, valor R$ 280, ultima saida ha 1 dia");
    Check(Perto(i1.TaxaRupturaPercentual, 40m), "d-1: ruptura 2 de 5 dias fotografados = 40%");
    var resumo = await indic.ResumoAsync(company, f30, parametros, comparar: true);
    Check(resumo.Atual.ValorEstoque == 380m && resumo.Atual.Cmv == 130m && resumo.Atual.EstoqueMedioValor == 240m, "resumo: valor R$ 380, CMV R$ 130, estoque medio R$ 240");
    Check(Perto(resumo.Atual.Giro, 130m / 240m, 0.0001m) && resumo.Atual.MotivoSemGiro is null, "resumo: giro = CMV / estoque medio");
    Check(resumo.Anterior is not null && resumo.Anterior.ValorEstoque == 200m && Perto(resumo.VariacaoValorEstoque, 90m), "comparacao: periodo anterior R$ 200 (fotografia do ultimo dia) e variacao +90%");
    Check(resumo.Anterior!.Cmv == 0m && resumo.Anterior.Giro == 0m, "periodo anterior sem vendas: CMV 0 e giro 0 (ha fotografia)");
    var fCat = FiltroAnalise.UltimosDias(30, categoriaId: "c-pai-" + company);
    var porCat = await indic.AgregarAsync(company, fCat);
    Check(porCat.Count == 1 && porCat[0].ProdutoId == "d-1", "filtro por categoria inclui as filhas (pai -> d-1 na filha)");
    var semFiltro = Perto(((await indic.ResumoAsync(company, FiltroAnalise.UltimosDias(30, fornecedorId: "nao-existe"), parametros, false)).Atual.Skus), 0m);
    Check(semFiltro, "fornecedor sem produtos: resumo vazio sem erro");

    // Configuracao e classificacao ABC/FSN em empresa propria (dados controlados)
    var company2 = company + "-cl";
    var configAb = new EstoqueConfigAnaliseAB(connection);
    Check(await configAb.ObterAsync(company2) == EstoqueParametros.Padrao, "sem configuracao: padroes do spec");
    await configAb.SalvarAsync(company2, EstoqueParametros.Padrao with { DiasParado = 60, AbcBase = "cmv" }, "Gerente");
    var salvo = await configAb.ObterAsync(company2);
    Check(salvo.DiasParado == 60 && salvo.AbcBase == "cmv" && salvo.NivelServicoZ == 1.65m, "configuracao salva e lida de volta");
    Check(EstoqueConfigAnaliseAB.Validar(EstoqueParametros.Padrao) is null && EstoqueConfigAnaliseAB.Validar(EstoqueParametros.Padrao with { AbcLimiteA = 96 }) is not null, "validacao dos limites ABC");
    await configAb.RestaurarPadraoAsync(company2);
    Check(await configAb.ObterAsync(company2) == EstoqueParametros.Padrao, "restaurar padrao");

    var classif = new EstoqueClassificacaoAB(connection);
    var valores = new (string Id, decimal Total, int[] Dias)[]
    {
        ("k1", 700m, new[] { 1, 8, 15, 22, 29, 36, 43, 50 }), ("k2", 150m, new[] { 1, 8, 15 }), ("k3", 100m, new[] { 1 }),
        ("k4", 40m, new[] { 1 }), ("k5", 10m, new[] { 1 }), ("k6", 0m, Array.Empty<int>()),
    };
    foreach (var (id, total, dias) in valores)
    {
        await produtos.SalvarAsync(company2, Novo(id, id.ToUpperInvariant(), 1, 1m, 2m), user);
        foreach (var d in dias) await Mov(company2, id, "saida_venda", 1, total / dias.Length, Dia(d));
    }
    var nClass = await classif.RecalcularAsync(company2, EstoqueParametros.Padrao with { AbcBase = "cmv" });
    Check(nClass == 6, "classificacao grava os 6 produtos");
    var matriz = await classif.MatrizAsync(company2);
    int Cel(char a, char f) => matriz.First(c => c.Abc == a && c.Fsn == f).Produtos;
    Check(matriz.Count == 9 && matriz.Sum(c => c.Produtos) == 6, "matriz tem as 9 combinacoes e soma 6 produtos");
    Check(Cel('A', 'F') == 1 && Cel('B', 'S') == 2 && Cel('C', 'S') == 2 && Cel('C', 'N') == 1, "matriz: k1=A/F, k2,k3=B/S, k4,k5=C/S, k6=C/N");
    Check(matriz.First(c => c.Abc == 'B' && c.Fsn == 'S').ValorEstoque == 2m, "valor em estoque da celula B/S (2 produtos de R$ 1,00)");
    Check((await classif.EmpresasDesatualizadasAsync()).All(e => e != company2), "empresa recem classificada nao aparece como desatualizada");

    // ---------- Etapa 5: detalhe do produto, extrato, contagem ciclica, acuracidade, divergencias e perdas ----------
    var det = new EstoqueAnaliseDetalheAB(connection);
    var f30c = FiltroAnalise.UltimosDias(30);
    var ser = await det.SeriesDoProdutoAsync(company, "d-1", f30c);
    Check(ser.SaldoDiario.Count == 5 && ser.SaldoDiario.Count(s => s.Quantidade == 0) == 2, "serie do produto: 5 fotografias, 2 com saldo zero");
    Check(ser.DemandaDiaria.Count == 30 && ser.DemandaDiaria.Sum() == 30m && ser.DemandaDiaria.Count(d => d == 3m) == 10 && ser.DemandaDiaria.Count(d => d == 0m) == 20, "demanda diaria: 30 dias, 10 com 3 unidades e 20 zeros");
    Check(ser.Semanal.Sum(s => s.Saidas) == 30m && ser.Semanal.Sum(s => s.Entradas) == 70m && ser.Semanal.Count == 5, "entradas x saidas por semana: 70 entradas (cadastro), 30 saidas, 5 semanas");
    var (totalExtrato, paginaExtrato) = await det.ExtratoAsync(company, "d-1", f30c, 1, 5);
    Check(totalExtrato == 11 && paginaExtrato.Count == 5 && paginaExtrato.Zip(paginaExtrato.Skip(1)).All(p => p.First.CriadoEmUtc >= p.Second.CriadoEmUtc), "extrato: 11 movimentacoes, pagina de 5 do mais recente ao mais antigo");
    var (_, ultimaPagina) = await det.ExtratoAsync(company, "d-1", f30c, 3, 5);
    Check(ultimaPagina.Count == 1, "extrato: a terceira pagina tem 1 item");
    var classD1 = await det.ClassificacaoDoProdutoAsync(company, "d-1");
    Check(classD1 is null, "produto ainda nao classificado: sem classe");
    var ciclica = await det.ContagemCiclicaAsync(company, EstoqueParametros.Padrao with { ContagemItensDia = 3 });
    Check(ciclica.Count == 3 && ciclica.All(i => i.ProdutoId != "p-a" && i.UltimaContagemUtc is null), "contagem ciclica: 3 itens nunca contados; p-a (contado hoje) fora da lista");
    var acPontos = await det.AcuracidadePorInventarioAsync(company, f30c);
    Check(acPontos.Count == 1 && acPontos[0].Itens == 1 && acPontos[0].Acuracidade < 100m && acPontos[0].ItensExatosPercentual == 0m, "acuracidade por inventario: 1 contagem com divergencia");
    var diverg = await det.DivergenciasAsync(company, f30c);
    Check(diverg.Count == 1 && diverg[0].Delta == -2m && diverg[0].ValorDelta == -8m, "divergencias: falta de 2 unidades (R$ 8,00 ao custo 4)");
    var perdas = await det.PerdasAsync(company, f30c);
    Check(perdas.Any(x => x.Motivo == "Quebra" && x.Quantidade == 2m && x.Valor == 8m), "perdas: motivo Quebra, 2 unidades, R$ 8,00");
    Check(perdas.Any(x => x.Motivo == "Inventário (falta)" && x.Quantidade == 2m), "perdas incluem a falta de inventario");
    Check(perdas.Any(x => x.Motivo == "Ajuste negativo" && x.Quantidade == 1m), "perdas incluem o ajuste negativo (edicao de cadastro para menos)");
    Check(perdas.All(x => x.Motivo != "Consumo") , "uso interno nao e perda");
    var perdasCat = await det.PerdasAsync(company, FiltroAnalise.UltimosDias(30, categoriaId: "c-pai-" + company));
    Check(perdasCat.Count == 0, "perdas respeitam o filtro de categoria");

    // Curva ABC do periodo (base CMV): ordenada, com participacao, acumulado, classe e FSN
    var curva = await classif.CurvaAbcAsync(company2, FiltroAnalise.UltimosDias(90), EstoqueParametros.Padrao with { AbcBase = "cmv" });
    Check(curva.Count == 5 && curva[0].Codigo == "K1" && curva[0].Valor == 700m && Perto(curva[0].Participacao, 70m) && curva[0].Classe == 'A' && curva[0].Fsn == 'F', "curva ABC: k1 lidera com R$ 700 (70%), classe A, FSN F; k6 sem venda fora da curva");
    Check(Perto(curva[1].Acumulada, 85m) && curva[1].Classe == 'B' && Perto(curva[2].Acumulada, 95m) && curva[2].Classe == 'B' && curva[3].Classe == 'C' && Perto(curva[4].Acumulada, 100m), "curva ABC: acumulado 85/95/99/100 e classes A,B,B,C,C");
    var curvaPeriodoCurto = await classif.CurvaAbcAsync(company2, FiltroAnalise.UltimosDias(5), EstoqueParametros.Padrao with { AbcBase = "cmv" });
    Check(curvaPeriodoCurto.Count == 5 && curvaPeriodoCurto.All(c => c.Codigo != "K6"), "curva ABC respeita o periodo (vendas de ha 1 dia)");

    Check(await Query("SELECT COUNT(*) FROM EstoqueMovimentacoes WHERE CompanyId=@C AND Tipo NOT IN ('entrada','saida','saida_venda','devolucao','uso_interno','perda','inventario','transferencia')", r => r.GetInt32(0)) == 0, "todos os tipos sao validos");
    Check(await Query("SELECT COUNT(*) FROM EstoqueMovimentacoes WHERE CompanyId=@C AND (CustoUnitario IS NULL OR DepositoId <> 'loja')", r => r.GetInt32(0)) == 0, "todas as movimentacoes tem custo e deposito loja");
    Console.WriteLine($"\n{passed} verificacoes OK");
    return 0;
}
catch (Exception ex)
{
    Console.Error.WriteLine(ex);
    return 1;
}
finally
{
    try
    {
        await using var db = await connection.OpenConnectionAsync();
        foreach (var comp in new[] { company, company + "-cl" })
        foreach (var sql in new[]
        {
            "DELETE FROM OrdemCompraItens WHERE OrdemCompraId IN (SELECT Id FROM OrdemCompra WHERE CompanyId=@C)",
            "DELETE FROM OrdemCompra WHERE CompanyId=@C",
            "DELETE FROM InventarioContagens WHERE InventarioId IN (SELECT Id FROM Inventarios WHERE CompanyId=@C)",
            "DELETE FROM InventarioItens WHERE CompanyId=@C",
            "DELETE FROM InventarioEventos WHERE InventarioId IN (SELECT Id FROM Inventarios WHERE CompanyId=@C)",
            "DELETE FROM Inventarios WHERE CompanyId=@C",
            "DELETE FROM EstoqueSnapshotDiario WHERE CompanyId=@C",
            "DELETE FROM EstoqueMovimentacoes WHERE CompanyId=@C",
            "DELETE FROM ProdutoClassificacao WHERE CompanyId=@C",
            "DELETE FROM EstoqueConfigAnalise WHERE CompanyId=@C",
            "DELETE FROM VendaPagamentos WHERE VendaId IN (SELECT Id FROM Vendas WHERE CompanyId=@C)",
            "DELETE FROM VendaItens WHERE VendaId IN (SELECT Id FROM Vendas WHERE CompanyId=@C)",
            "DELETE FROM Vendas WHERE CompanyId=@C",
            "DELETE FROM Produtos WHERE CompanyId=@C",
            "DELETE FROM Categorias WHERE CompanyId=@C AND CategoriaPaiId IS NOT NULL",
            "DELETE FROM Categorias WHERE CompanyId=@C",
            "DELETE FROM Fornecedores WHERE CompanyId=@C",
            "DELETE FROM Empresas WHERE Id=@C",
        })
        {
            await using var cmd = new SqlCommand(sql, db);
            cmd.Parameters.AddWithValue("@C", comp);
            try { await cmd.ExecuteNonQueryAsync(); } catch { }
        }
    }
    catch { }
}
