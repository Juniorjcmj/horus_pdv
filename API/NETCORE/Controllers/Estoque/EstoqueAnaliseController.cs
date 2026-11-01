/**
 * Arquivo: API/NETCORE/Controllers/Estoque/EstoqueAnaliseController.cs
 * Objetivo: endpoints da análise de estoque (api/Estoque/analise/*). Cada bloco de tela tem o seu endpoint, com cache de
 *           5 minutos por (empresa, rota, filtros) e resposta com o horário do cálculo. Campos em R$ (valor, CMV, excesso)
 *           só para gerente/administrador; o estoquista recebe quantidades e dias. Perfil caixa recebe 403.
 *           Filtros comuns (query): de, ate (AAAA-MM-DD), categoriaId, fornecedorId, depositoId, comparar.
 */
using System.Globalization;
using HORUSPDV_API.Models.Response;
using HORUSPDV_API.Repositories.DatabaseAccess;
using HORUSPDV_API.Services.Estoque;
using HORUSPDV_API.Services.Security;
using Microsoft.AspNetCore.Mvc;

namespace HORUSPDV_API.Controllers.Estoque;

[ApiController]
[Route("api/Estoque/analise")]
public class EstoqueAnaliseController(
    EstoqueSnapshotAB snapshots, EstoqueIndicadoresAB indicadores, EstoqueConfigAnaliseAB config,
    EstoqueClassificacaoAB classificacao, EstoqueAnaliseCache cache, OrdemCompraAB ordensCompra) : ControllerBase
{
    private const int MaxPontosSerie = 120;
    private const int MaxDiasPeriodo = 400;

    /// <summary>Grava (ou reconstrói) a fotografia de um dia (padrão: ontem). Idempotente.</summary>
    [HttpPost("snapshot")]
    [HorusAuthorizeRoles("administrador", "gerente")]
    public async Task<IActionResult> Snapshot([FromQuery] string? data = null)
    {
        if (Usuario() is not { } u) return Sessao();

        var dia = EstoqueSnapshotAB.Ontem();
        if (!string.IsNullOrWhiteSpace(data))
        {
            if (!DateOnly.TryParseExact(data, "yyyy-MM-dd", CultureInfo.InvariantCulture, DateTimeStyles.None, out dia))
                return BadRequest(Erro("Data inválida. Use AAAA-MM-DD."));
            if (dia > EstoqueSnapshotAB.Ontem())
                return BadRequest(Erro("A fotografia só existe para dias já encerrados."));
        }

        var linhas = await snapshots.GravarAsync(u.CompanyId, dia, HttpContext.RequestAborted);
        cache.Invalidar(u.CompanyId);
        return Ok(new ApiResponse<object>
        {
            Success = true,
            Message = "Fotografia do estoque gravada.",
            Data = new { data = dia.ToString("yyyy-MM-dd"), produtos = linhas },
        });
    }

    /// <summary>Cards do dashboard: valor, giro, DIO, cobertura, ruptura, parados, excesso e acuracidade, com variação e semáforo.</summary>
    [HttpGet("kpis")]
    [HorusAuthorizeRoles("administrador", "gerente", "estoquista")]
    public Task<IActionResult> Kpis([FromQuery] FiltrosQuery q) => Bloco("kpis", q, async (u, f, p, comValores) =>
    {
        var r = await indicadores.ResumoAsync(u.CompanyId, f, p, q.Comparar, HttpContext.RequestAborted);
        var metas = EstoqueMetas.Efetivas(p.MetasJson);
        var acAtual = await indicadores.AcuraciaAsync(u.CompanyId, f, HttpContext.RequestAborted);
        var acAnt = r.FiltroAnterior is { } fa ? await indicadores.AcuraciaAsync(u.CompanyId, fa, HttpContext.RequestAborted) : null;
        var a = r.Atual;
        var b = r.Anterior;

        KpiDto Kpi(string chave, string titulo, string unidade, decimal? atual, decimal? anterior, string? motivo = null, bool emReais = false)
            => new(chave, titulo, unidade, atual, anterior,
                atual is { } x && anterior is { } y ? EstoqueCalculos.VariacaoPercentual(x, y) : null,
                EstoqueMetas.Semaforo(metas, chave, atual), atual is null ? motivo : null, emReais);

        var lista = new List<KpiDto>
        {
            Kpi("valorEstoque", "Valor em estoque", "R$", a.ValorEstoque, b?.ValorEstoque, "sem histórico", true),
            Kpi("itensComSaldo", "Itens com saldo", "itens", a.SkusComSaldo, b?.SkusComSaldo),
            Kpi("giroAnualizado", "Giro anualizado", "x", a.GiroAnualizado, b?.GiroAnualizado, a.MotivoSemGiro),
            Kpi("dio", "DIO (dias de estoque)", "dias", a.Dio, b?.Dio, a.MotivoSemGiro),
            Kpi("coberturaMediaDias", "Cobertura média", "dias", a.CoberturaMediaDias, b?.CoberturaMediaDias, "sem giro"),
            Kpi("emRupturaAgoraPercentual", "Em ruptura agora", "%", a.EmRupturaAgoraPercentual, b?.EmRupturaAgoraPercentual),
            Kpi("taxaRupturaPercentual", "Taxa de ruptura", "%", a.TaxaRupturaMediaPercentual, b?.TaxaRupturaMediaPercentual, "sem histórico"),
            Kpi("itensParados", "Itens parados", "itens", a.ItensParados, b?.ItensParados),
            Kpi("valorParado", "Valor parado", "R$", a.ValorParado, b?.ValorParado, null, true),
            Kpi("valorExcesso", "Valor em excesso", "R$", a.ValorExcesso, b?.ValorExcesso, null, true),
            Kpi("cmv", "CMV do período", "R$", a.Cmv, b?.Cmv, null, true),
            Kpi("acuracidade", "Acuracidade do inventário", "%", acAtual?.AcuraciaPercentual, acAnt?.AcuraciaPercentual, "sem contagens"),
        };
        if (!comValores) lista = lista.Where(k => !k.EmReais).ToList();

        return new
        {
            periodo = Periodo(f), periodoAnterior = r.FiltroAnterior is { } pa ? Periodo(pa) : null,
            kpis = lista, historicoCurto = f.Dias < 14, semHistorico = a.MotivoSemGiro == "sem histórico",
        };
    });

    /// <summary>Estoque ao longo do tempo (fotografias diárias; semanal acima de 120 pontos).</summary>
    [HttpGet("serie-estoque")]
    [HorusAuthorizeRoles("administrador", "gerente", "estoquista")]
    public Task<IActionResult> SerieEstoque([FromQuery] FiltrosQuery q) => Bloco("serie-estoque", q, async (u, f, p, comValores) =>
    {
        var dias = await indicadores.SerieDiariaAsync(u.CompanyId, f, HttpContext.RequestAborted);
        var semanal = dias.Count > MaxPontosSerie;
        var pontos = (semanal ? dias.Chunk(7).Select(g => g[^1]) : dias)
            .Select(d => new { data = d.Data.ToString("yyyy-MM-dd"), quantidade = d.Quantidade, valor = comValores ? (decimal?)d.Valor : null }).ToList();
        return new { periodo = Periodo(f), agregacao = semanal ? "semanal" : "diaria", pontos, vazio = dias.Count == 0 };
    });

    /// <summary>Percentual de produtos em ruptura por dia.</summary>
    [HttpGet("serie-ruptura")]
    [HorusAuthorizeRoles("administrador", "gerente", "estoquista")]
    public Task<IActionResult> SerieRuptura([FromQuery] FiltrosQuery q) => Bloco("serie-ruptura", q, async (u, f, p, comValores) =>
    {
        var dias = await indicadores.SerieDiariaAsync(u.CompanyId, f, HttpContext.RequestAborted);
        var semanal = dias.Count > MaxPontosSerie;
        var grupos = semanal ? dias.Chunk(7).ToList() : dias.Select(d => new[] { d }).ToList();
        var pontos = grupos.Select(g => new
        {
            data = g[^1].Data.ToString("yyyy-MM-dd"),
            percentual = Math.Round(g.Average(d => d.Produtos == 0 ? 0m : 100m * d.ProdutosEmRuptura / d.Produtos), 2),
            produtosEmRuptura = (int)Math.Round(g.Average(d => (double)d.ProdutosEmRuptura)),
        }).ToList();
        return new { periodo = Periodo(f), agregacao = semanal ? "semanal" : "diaria", pontos, vazio = dias.Count == 0 };
    });

    /// <summary>Cobertura (dias) por categoria = saldo ÷ consumo dos produtos com giro.</summary>
    [HttpGet("cobertura-categorias")]
    [HorusAuthorizeRoles("administrador", "gerente", "estoquista")]
    public Task<IActionResult> CoberturaCategorias([FromQuery] FiltrosQuery q) => Bloco("cobertura-categorias", q, async (u, f, p, comValores) =>
    {
        var itens = await indicadores.ProdutosAsync(u.CompanyId, f, p, HttpContext.RequestAborted);
        var nomes = await indicadores.NomesCategoriasAsync(u.CompanyId, HttpContext.RequestAborted);
        var categorias = itens.GroupBy(i => i.Bruto.CategoriaId ?? "")
            .Select(g =>
            {
                var comGiro = g.Where(i => i.Consumo > 0).ToList();
                var consumo = comGiro.Sum(i => i.Consumo);
                return new
                {
                    categoriaId = g.Key,
                    nome = g.Key != "" && nomes.TryGetValue(g.Key, out var n) ? n : "Sem categoria",
                    produtos = g.Count(),
                    coberturaDias = consumo > 0 ? (decimal?)Math.Round(comGiro.Sum(i => Math.Max(0m, i.Saldo)) / consumo, 1) : null,
                    valorEstoque = comValores ? (decimal?)g.Sum(i => i.ValorEstoque) : null,
                };
            })
            .OrderByDescending(c => c.coberturaDias ?? -1m).ToList();
        return new { periodo = Periodo(f), categorias, vazio = itens.Count == 0 };
    });

    /// <summary>Os 10 produtos com maior giro anualizado (CMV ÷ estoque médio das fotografias).</summary>
    [HttpGet("top-giro")]
    [HorusAuthorizeRoles("administrador", "gerente", "estoquista")]
    public Task<IActionResult> TopGiro([FromQuery] FiltrosQuery q) => Bloco("top-giro", q, async (u, f, p, comValores) =>
    {
        var itens = await indicadores.ProdutosAsync(u.CompanyId, f, p, HttpContext.RequestAborted);
        var lista = itens
            .Select(i => new { i, giro = EstoqueCalculos.GiroAnualizado(EstoqueCalculos.Giro(i.Bruto.Cmv, i.Bruto.EstoqueMedioValor), f.Dias) })
            .Where(x => x.giro is > 0)
            .OrderByDescending(x => x.giro).Take(10)
            .Select(x => new
            {
                produtoId = x.i.ProdutoId, codigo = x.i.Bruto.Codigo, nome = x.i.Bruto.Nome,
                giroAnualizado = Math.Round(x.giro!.Value, 2), consumoDia = Math.Round(x.i.Consumo, 3), saldo = x.i.Saldo,
            }).ToList();
        return new { periodo = Periodo(f), produtos = lista, vazio = lista.Count == 0 };
    });

    /// <summary>Os 10 produtos parados (sem saída há mais de X dias) de maior valor (estoquista: maior tempo parado).</summary>
    [HttpGet("top-parados")]
    [HorusAuthorizeRoles("administrador", "gerente", "estoquista")]
    public Task<IActionResult> TopParados([FromQuery] FiltrosQuery q) => Bloco("top-parados", q, async (u, f, p, comValores) =>
    {
        var itens = await indicadores.ProdutosAsync(u.CompanyId, f, p, HttpContext.RequestAborted);
        var parados = itens.Where(i => i.Parado);
        var ordenados = comValores
            ? parados.OrderByDescending(i => i.ValorEstoque)
            : parados.OrderByDescending(i => i.DiasSemSaida ?? int.MaxValue).ThenByDescending(i => i.Saldo);
        var lista = ordenados.Take(10).Select(i => new
        {
            produtoId = i.ProdutoId, codigo = i.Bruto.Codigo, nome = i.Bruto.Nome, saldo = i.Saldo,
            diasSemSaida = i.DiasSemSaida, valor = comValores ? (decimal?)i.ValorEstoque : null,
        }).ToList();
        return new { periodo = Periodo(f), diasParado = p.DiasParado, totalParados = parados.Count(), produtos = lista, vazio = lista.Count == 0 };
    });

    /// <summary>Distribuição do estoque por classe ABC (rosca): produtos e valor.</summary>
    [HttpGet("abc-valor")]
    [HorusAuthorizeRoles("administrador", "gerente", "estoquista")]
    public Task<IActionResult> AbcValor([FromQuery] FiltrosQuery q) => Bloco("abc-valor", q, async (u, f, p, comValores) =>
    {
        var matriz = await classificacao.MatrizAsync(u.CompanyId, f.CategoriaId, f.FornecedorId, HttpContext.RequestAborted);
        var totalValor = matriz.Sum(c => c.ValorEstoque);
        var classes = matriz.GroupBy(c => c.Abc).Select(g => new
        {
            classe = g.Key.ToString(), produtos = g.Sum(c => c.Produtos),
            valor = comValores ? (decimal?)g.Sum(c => c.ValorEstoque) : null,
            percentual = comValores && totalValor > 0 ? (decimal?)Math.Round(100m * g.Sum(c => c.ValorEstoque) / totalValor, 1) : null,
        }).ToList();
        return new { classes, vazio = matriz.All(c => c.Produtos == 0) };
    });

    // ---------- etapa 4: curva ABC e reposição ----------

    /// <summary>Curva ABC do período (Pareto top 100 + "Outros", tabela e resumo por classe). Só gerente/administrador (valores).</summary>
    [HttpGet("abc")]
    [HorusAuthorizeRoles("administrador", "gerente")]
    public Task<IActionResult> Abc([FromQuery] FiltrosQuery q, [FromQuery] string? classe = null, [FromQuery] string? fsn = null)
        => Bloco($"abc|{classe}|{fsn}", q, async (u, f, p, comValores) =>
        {
            var todos = await classificacao.CurvaAbcAsync(u.CompanyId, f, p, HttpContext.RequestAborted);
            var total = todos.Sum(i => i.Valor);
            var pareto = todos.Take(100).Select((i, n) => (object)new { posicao = n + 1, codigo = i.Codigo, nome = i.Nome, valor = i.Valor, acumulada = Math.Round(i.Acumulada, 2) }).ToList();
            if (todos.Count > 100)
            {
                var resto = todos.Skip(100).Sum(i => i.Valor);
                pareto.Add(new { posicao = 101, codigo = "", nome = $"Outros ({todos.Count - 100})", valor = resto, acumulada = 100m });
            }
            var filtrados = todos.AsEnumerable();
            if (!string.IsNullOrWhiteSpace(classe)) filtrados = filtrados.Where(i => string.Equals(i.Classe.ToString(), classe, StringComparison.OrdinalIgnoreCase));
            if (!string.IsNullOrWhiteSpace(fsn)) filtrados = filtrados.Where(i => i.Fsn is { } x && string.Equals(x.ToString(), fsn, StringComparison.OrdinalIgnoreCase));
            var tabela = filtrados.Take(1000).Select(i => new
            {
                produtoId = i.ProdutoId, codigo = i.Codigo, nome = i.Nome, classe = i.Classe.ToString(), fsn = i.Fsn?.ToString(),
                quantidade = i.Quantidade, valor = i.Valor, participacao = Math.Round(i.Participacao, 2), acumulada = Math.Round(i.Acumulada, 2),
                saldo = i.Saldo, valorEstoque = i.ValorEstoque,
            }).ToList();
            var resumo = todos.GroupBy(i => i.Classe).OrderBy(g => g.Key).Select(g => new
            {
                classe = g.Key.ToString(), produtos = g.Count(), valor = g.Sum(i => i.Valor),
                participacao = total > 0 ? Math.Round(100m * g.Sum(i => i.Valor) / total, 1) : 0m,
            }).ToList();
            return new { periodo = Periodo(f), baseAbc = p.AbcBase, limiteA = p.AbcLimiteA, limiteB = p.AbcLimiteB, totalValor = total, pareto, itens = tabela, resumo, vazio = todos.Count == 0 };
        });

    /// <summary>Matriz ABC x FSN: 9 combinações com produtos e valor em estoque (estoquista: só produtos).</summary>
    [HttpGet("abc-fsn")]
    [HorusAuthorizeRoles("administrador", "gerente", "estoquista")]
    public Task<IActionResult> AbcFsn([FromQuery] FiltrosQuery q) => Bloco("abc-fsn", q, async (u, f, p, comValores) =>
    {
        var matriz = await classificacao.MatrizAsync(u.CompanyId, f.CategoriaId, f.FornecedorId, HttpContext.RequestAborted);
        return new
        {
            celulas = matriz.Select(c => new { abc = c.Abc.ToString(), fsn = c.Fsn.ToString(), produtos = c.Produtos, valorEstoque = comValores ? (decimal?)c.ValorEstoque : null }).ToList(),
            vazio = matriz.All(c => c.Produtos == 0),
        };
    });

    /// <summary>
    /// Reposição: produtos abaixo do ponto de pedido ou com sugestão de compra, por urgência. Consumo e desvio vêm da
    /// janela de consumo da configuração (termina hoje); categoria e fornecedor filtram.
    /// </summary>
    [HttpGet("reposicao")]
    [HorusAuthorizeRoles("administrador", "gerente", "estoquista")]
    public async Task<IActionResult> Reposicao([FromQuery] FiltrosQuery q)
    {
        if (Usuario() is not { } u) return Sessao();
        var parametros = await config.ObterAsync(u.CompanyId, HttpContext.RequestAborted);
        var janela = FiltroAnalise.UltimosDias(parametros.JanelaConsumoDias, q.CategoriaId, q.FornecedorId);
        var comValores = HorusRoles.IsGerenteOuAdmin(u.Role);
        var (dados, calculadoEm, doCache) = await cache.ObterAsync(u.CompanyId, $"reposicao|{janela}|{(comValores ? "v" : "q")}", async () =>
        {
            var itens = await indicadores.ProdutosAsync(u.CompanyId, janela, parametros, HttpContext.RequestAborted);
            var fornecedores = await indicadores.FornecedoresAsync(u.CompanyId, HttpContext.RequestAborted);
            var ordem = new[] { "ruptura", "critico", "repor", "planejar" };
            var linhas = itens
                .Where(i => i.Sugestao > 0 || (i.Consumo > 0 && i.Saldo <= i.PontoPedido))
                .Select(i =>
                {
                    var urgencia = i.Saldo <= 0 ? "ruptura" : i.Consumo > 0 && i.Saldo < i.Consumo * i.LeadTimeDias ? "critico" : i.Saldo <= i.PontoPedido ? "repor" : "planejar";
                    return new
                    {
                        produtoId = i.ProdutoId, codigo = i.Bruto.Codigo, nome = i.Bruto.Nome, unidade = i.Bruto.Unidade,
                        fornecedorId = i.Bruto.FornecedorId,
                        fornecedor = i.Bruto.FornecedorId is not null && fornecedores.TryGetValue(i.Bruto.FornecedorId, out var fo) ? fo.Nome : "Sem fornecedor",
                        saldo = i.Saldo, minimo = i.Bruto.Minimo, consumoDia = Math.Round(i.Consumo, 3), desvio = Math.Round(i.Desvio, 3),
                        leadTimeDias = i.LeadTimeDias, estoqueSeguranca = Math.Round(i.EstoqueSeguranca, 3), pontoPedido = Math.Round(i.PontoPedido, 3),
                        estoqueAlvo = Math.Round(i.EstoqueAlvo, 3), emTransito = i.Bruto.EmTransito, sugestao = i.Sugestao,
                        coberturaDias = i.CoberturaDias is { } c ? (decimal?)Math.Round(c, 1) : null, urgencia,
                        historicoCurto = i.HistoricoCurto,
                        custoUnitario = comValores ? (decimal?)i.Bruto.Custo : null,
                        custoEstimado = comValores ? (decimal?)Math.Round(i.Sugestao * i.Bruto.Custo, 2) : null,
                    };
                })
                .OrderBy(l => Array.IndexOf(ordem, l.urgencia)).ThenBy(l => l.coberturaDias ?? -1m).ThenBy(l => l.nome)
                .ToList();
            return (object)new
            {
                janelaDias = parametros.JanelaConsumoDias, coberturaAlvoDias = parametros.CoberturaAlvoDias,
                resumo = new
                {
                    itens = linhas.Count, emRuptura = linhas.Count(l => l.urgencia == "ruptura"), criticos = linhas.Count(l => l.urgencia == "critico"),
                    fornecedores = linhas.Where(l => l.fornecedorId is not null).Select(l => l.fornecedorId).Distinct().Count(),
                    semFornecedor = linhas.Count(l => l.fornecedorId is null),
                    custoTotal = comValores ? (decimal?)linhas.Sum(l => l.custoEstimado ?? 0m) : null,
                },
                itens = linhas, vazio = itens.Count == 0,
            };
        });
        return Ok(new ApiResponse<object> { Success = true, Message = "Reposição.", Data = new { dados, calculadoEm = DateTime.SpecifyKind(calculadoEm, DateTimeKind.Utc).ToString("o"), doCache } });
    }

    public sealed class PedidoReposicaoItem
    {
        public string ProdutoId { get; set; } = string.Empty;
        public decimal Quantidade { get; set; }
    }

    public sealed class PedidoReposicaoRequest
    {
        public List<PedidoReposicaoItem> Itens { get; set; } = [];
        public string? Observacao { get; set; }
    }

    /// <summary>Gera um rascunho de pedido de compra (pendente) por fornecedor com os itens escolhidos. Produto sem fornecedor é recusado.</summary>
    [HttpPost("reposicao/pedidos")]
    [HorusAuthorizeRoles("administrador", "gerente")]
    public async Task<IActionResult> GerarPedidos([FromBody] PedidoReposicaoRequest body)
    {
        if (Usuario() is not { } u) return Sessao();
        if (body?.Itens is not { Count: > 0 }) return BadRequest(Erro("Escolha ao menos um produto."));
        if (body.Itens.Any(i => i.Quantidade <= 0)) return BadRequest(Erro("As quantidades devem ser maiores que zero."));

        var parametros = await config.ObterAsync(u.CompanyId, HttpContext.RequestAborted);
        var janela = FiltroAnalise.UltimosDias(parametros.JanelaConsumoDias);
        var porProduto = (await indicadores.ProdutosAsync(u.CompanyId, janela, parametros, HttpContext.RequestAborted)).ToDictionary(i => i.ProdutoId);
        var fornecedores = await indicadores.FornecedoresAsync(u.CompanyId, HttpContext.RequestAborted);

        var ignorados = new List<object>();
        var grupos = new Dictionary<string, List<(IndicadorProduto Item, decimal Quantidade)>>(StringComparer.Ordinal);
        foreach (var it in body.Itens.GroupBy(i => i.ProdutoId).Select(g => (Id: g.Key, Qtd: g.Sum(x => x.Quantidade))))
        {
            if (!porProduto.TryGetValue(it.Id, out var prod)) { ignorados.Add(new { produtoId = it.Id, motivo = "Produto não encontrado." }); continue; }
            if (prod.Bruto.FornecedorId is not { } fid || !fornecedores.ContainsKey(fid))
            {
                ignorados.Add(new { produtoId = it.Id, codigo = prod.Bruto.Codigo, nome = prod.Bruto.Nome, motivo = "Produto sem fornecedor cadastrado." });
                continue;
            }
            var qtd = EstoqueIndicadoresAB.UnidadeFracionada(prod.Bruto.Unidade) ? Math.Round(it.Qtd, 3) : Math.Ceiling(it.Qtd);
            if (!grupos.TryGetValue(fid, out var lista)) grupos[fid] = lista = [];
            lista.Add((prod, qtd));
        }
        if (grupos.Count == 0) return BadRequest(new ApiResponse<object> { Success = false, Message = "Nenhum produto pôde ser incluído em pedido.", Data = new { ignorados } });

        var criados = new List<object>();
        foreach (var (fid, itens) in grupos)
        {
            var (nome, cnpj) = fornecedores[fid];
            var previsao = DateTimeOffset.Now.AddDays(itens.Max(i => i.Item.LeadTimeDias));
            var nota = string.IsNullOrWhiteSpace(body.Observacao) ? "Rascunho gerado pela análise de reposição." : body.Observacao!.Trim();
            var oc = await ordensCompra.CriarAsync(u.CompanyId, fid, nome, cnpj, u.Id, u.Name, nota, previsao, null, null, 0m, 0m,
                itens.Select(i => (i.Item.Bruto.Codigo, i.Item.Bruto.Nome, i.Quantidade, i.Item.Bruto.Custo)).ToList());
            criados.Add(new { numero = oc.OrderNumber, fornecedorId = fid, fornecedor = nome, itens = itens.Count, total = oc.TotalEstimado, previsaoEntrega = previsao.ToString("yyyy-MM-dd") });
        }
        cache.Invalidar(u.CompanyId);
        return Ok(new ApiResponse<object> { Success = true, Message = $"{criados.Count} pedido(s) em rascunho criado(s).", Data = new { pedidos = criados, ignorados } });
    }

    // ---------- infraestrutura dos blocos ----------

    private sealed record KpiDto(string Chave, string Titulo, string Unidade, decimal? Valor, decimal? Anterior,
        decimal? VariacaoPercentual, string? Semaforo, string? Motivo, [property: System.Text.Json.Serialization.JsonIgnore] bool EmReais);

    public sealed class FiltrosQuery
    {
        public string? De { get; set; }
        public string? Ate { get; set; }
        public string? CategoriaId { get; set; }
        public string? FornecedorId { get; set; }
        public string? DepositoId { get; set; }
        public bool Comparar { get; set; }
    }

    private async Task<IActionResult> Bloco(string rota, FiltrosQuery q,
        Func<AuthenticatedUser, FiltroAnalise, EstoqueParametros, bool, Task<object>> calcular)
    {
        if (Usuario() is not { } u) return Sessao();
        var (filtro, erro) = Filtro(q);
        if (filtro is null) return BadRequest(Erro(erro!));

        var comValores = HorusRoles.IsGerenteOuAdmin(u.Role);
        var chave = $"{rota}|{filtro}|{q.Comparar}|{(comValores ? "v" : "q")}";
        var (dados, calculadoEm, doCache) = await cache.ObterAsync(u.CompanyId, chave, async () =>
        {
            var parametros = await config.ObterAsync(u.CompanyId, HttpContext.RequestAborted);
            return await calcular(u, filtro, parametros, comValores);
        });
        return Ok(new ApiResponse<object>
        {
            Success = true,
            Message = "Análise de estoque.",
            Data = new { dados, calculadoEm = DateTime.SpecifyKind(calculadoEm, DateTimeKind.Utc).ToString("o"), doCache },
        });
    }

    private static (FiltroAnalise? Filtro, string? Erro) Filtro(FiltrosQuery q)
    {
        var hoje = FiltroAnalise.Hoje();
        var ate = hoje;
        var de = hoje.AddDays(-29);
        if (!string.IsNullOrWhiteSpace(q.Ate) && !DateOnly.TryParseExact(q.Ate, "yyyy-MM-dd", CultureInfo.InvariantCulture, DateTimeStyles.None, out ate))
            return (null, "Data final inválida. Use AAAA-MM-DD.");
        if (!string.IsNullOrWhiteSpace(q.De) && !DateOnly.TryParseExact(q.De, "yyyy-MM-dd", CultureInfo.InvariantCulture, DateTimeStyles.None, out de))
            return (null, "Data inicial inválida. Use AAAA-MM-DD.");
        if (string.IsNullOrWhiteSpace(q.De) && !string.IsNullOrWhiteSpace(q.Ate)) de = ate.AddDays(-29);
        if (de > ate) return (null, "A data inicial não pode ser depois da final.");
        if (ate.DayNumber - de.DayNumber + 1 > MaxDiasPeriodo) return (null, $"O período máximo é de {MaxDiasPeriodo} dias.");
        return (new FiltroAnalise(de, ate, q.CategoriaId, q.FornecedorId, string.IsNullOrWhiteSpace(q.DepositoId) ? "loja" : q.DepositoId), null);
    }

    private static object Periodo(FiltroAnalise f) => new { de = f.De.ToString("yyyy-MM-dd"), ate = f.Ate.ToString("yyyy-MM-dd"), dias = f.Dias };

    private static ApiResponse<object> Erro(string mensagem) => new() { Success = false, Message = mensagem };

    private AuthenticatedUser? Usuario() => HttpContext.Items["CurrentUser"] as AuthenticatedUser;

    private IActionResult Sessao() => Unauthorized(Erro("Sessão não encontrada."));
}
