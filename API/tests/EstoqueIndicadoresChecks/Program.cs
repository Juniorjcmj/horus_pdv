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
        foreach (var sql in new[]
        {
            "DELETE FROM EstoqueSnapshotDiario WHERE CompanyId=@C",
            "DELETE FROM EstoqueMovimentacoes WHERE CompanyId=@C",
            "DELETE FROM VendaPagamentos WHERE VendaId IN (SELECT Id FROM Vendas WHERE CompanyId=@C)",
            "DELETE FROM VendaItens WHERE VendaId IN (SELECT Id FROM Vendas WHERE CompanyId=@C)",
            "DELETE FROM Vendas WHERE CompanyId=@C",
            "DELETE FROM Produtos WHERE CompanyId=@C",
        })
        {
            await using var cmd = new SqlCommand(sql, db);
            cmd.Parameters.AddWithValue("@C", company);
            try { await cmd.ExecuteNonQueryAsync(); } catch { }
        }
    }
    catch { }
}
