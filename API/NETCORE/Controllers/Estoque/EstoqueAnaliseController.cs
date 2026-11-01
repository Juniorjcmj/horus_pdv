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
    EstoqueClassificacaoAB classificacao, EstoqueAnaliseCache cache, OrdemCompraAB ordensCompra,
    EstoqueAnaliseDetalheAB detalhe) : ControllerBase
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
            .Select(i => new { i, giro = EstoqueCalculos.GiroAnualizado(EstoqueCalculos.Giro(i.Bruto.Cmv, i.Bruto.EstoqueMedioValor ?? (i.ValorEstoque > 0 ? i.ValorEstoque : null)), f.Dias) })
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
            // Curva completa reduzida a ~400 pontos (sempre com o primeiro, o último e o último item de cada classe), para
            // desenhar a curva ABC com milhares de produtos.
            var n = todos.Count;
            var passo = Math.Max(1, n / 400);
            var curva = new List<object>();
            for (var k = 0; k < n; k++)
            {
                var it = todos[k];
                var fimDeClasse = k < n - 1 && todos[k + 1].Classe != it.Classe;
                if (k % passo == 0 || k == n - 1 || fimDeClasse)
                    curva.Add(new { pos = k + 1, acumulada = Math.Round(it.Acumulada, 2), classe = it.Classe.ToString(), codigo = it.Codigo, nome = it.Nome, valor = it.Valor });
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
            return new { periodo = Periodo(f), baseAbc = p.AbcBase, limiteA = p.AbcLimiteA, limiteB = p.AbcLimiteB, totalValor = total, pareto, curva, totalProdutos = n, itens = tabela, resumo, vazio = todos.Count == 0 };
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

    // ---------- etapa 5: produto, acuracidade, perdas, parados e fornecedores ----------

    /// <summary>Detalhe de um produto: indicadores, saldo diário, entradas x saídas por semana e histograma da demanda diária.</summary>
    [HttpGet("produto/{id}")]
    [HorusAuthorizeRoles("administrador", "gerente", "estoquista")]
    public Task<IActionResult> Produto(string id, [FromQuery] FiltrosQuery q) => Bloco($"produto|{id}", q, async (u, f, p, comValores) =>
    {
        var itens = await indicadores.ProdutosAsync(u.CompanyId, f with { CategoriaId = null, FornecedorId = null }, p, HttpContext.RequestAborted);
        var i = itens.FirstOrDefault(x => x.ProdutoId == id);
        if (i is null) return null;
        var series = await detalhe.SeriesDoProdutoAsync(u.CompanyId, id, f, HttpContext.RequestAborted);
        var classe = await detalhe.ClassificacaoDoProdutoAsync(u.CompanyId, id, HttpContext.RequestAborted);
        var giro = EstoqueCalculos.GiroAnualizado(EstoqueCalculos.Giro(i.Bruto.Cmv, i.Bruto.EstoqueMedioValor ?? (i.ValorEstoque > 0 ? i.ValorEstoque : null)), f.Dias);
        return (object?)new
        {
            periodo = Periodo(f),
            produto = new { id = i.ProdutoId, codigo = i.Bruto.Codigo, nome = i.Bruto.Nome, unidade = i.Bruto.Unidade, minimo = i.Bruto.Minimo, maximo = i.Bruto.Maximo },
            kpis = new
            {
                saldo = i.Saldo, valorEstoque = comValores ? (decimal?)i.ValorEstoque : null, consumoDia = Math.Round(i.Consumo, 3), desvio = Math.Round(i.Desvio, 3),
                coberturaDias = i.CoberturaDias is { } c ? (decimal?)Math.Round(c, 1) : null, leadTimeDias = i.LeadTimeDias,
                estoqueSeguranca = Math.Round(i.EstoqueSeguranca, 3), pontoPedido = Math.Round(i.PontoPedido, 3), estoqueAlvo = Math.Round(i.EstoqueAlvo, 3),
                sugestao = i.Sugestao, emTransito = i.Bruto.EmTransito, giroAnualizado = giro is { } g ? (decimal?)Math.Round(g, 2) : null,
                taxaRupturaPercentual = i.Bruto.DiasFotografados > 0 ? (decimal?)Math.Round(i.TaxaRupturaPercentual, 1) : null,
                diasSemSaida = i.DiasSemSaida, abc = classe?.Abc.ToString(), fsn = classe?.Fsn.ToString(), historicoCurto = i.HistoricoCurto,
            },
            series = new
            {
                saldoDiario = series.SaldoDiario.Select(s => new { data = s.Data.ToString("yyyy-MM-dd"), quantidade = s.Quantidade }).ToList(),
                semanal = series.Semanal.Select(s => new { inicio = s.Inicio.ToString("yyyy-MM-dd"), entradas = s.Entradas, saidas = s.Saidas }).ToList(),
                histograma = Histograma(series.DemandaDiaria),
            },
        };
    });

    /// <summary>Extrato do produto (movimentações do período), paginado. Custo só para gerente/administrador.</summary>
    [HttpGet("produto/{id}/extrato")]
    [HorusAuthorizeRoles("administrador", "gerente", "estoquista")]
    public async Task<IActionResult> Extrato(string id, [FromQuery] FiltrosQuery q, [FromQuery] int pagina = 1, [FromQuery] int tamanho = 50)
    {
        if (Usuario() is not { } u) return Sessao();
        var (filtro, erro) = Filtro(q);
        if (filtro is null) return BadRequest(Erro(erro!));
        tamanho = Math.Clamp(tamanho, 1, 200);
        pagina = Math.Max(1, pagina);
        var (total, itens) = await detalhe.ExtratoAsync(u.CompanyId, id, filtro, pagina, tamanho, HttpContext.RequestAborted);
        var comValores = HorusRoles.IsGerenteOuAdmin(u.Role);
        return Ok(new ApiResponse<object>
        {
            Success = true,
            Message = "Extrato do produto.",
            Data = new
            {
                total, pagina, tamanho, paginas = (int)Math.Ceiling(total / (double)tamanho),
                itens = itens.Select(m => new
                {
                    data = DateTime.SpecifyKind(m.CriadoEmUtc, DateTimeKind.Utc).ToString("o"), tipo = m.Tipo, quantidade = m.Quantidade, anterior = m.Anterior,
                    novo = m.Novo, custoUnitario = comValores ? m.Custo : null, origemTipo = m.OrigemTipo, origemId = m.OrigemId, motivo = m.Motivo, usuario = m.Usuario,
                }).ToList(),
            },
        });
    }

    /// <summary>Lista do dia da contagem cíclica (A, B e C pela periodicidade da configuração). Não mostra o saldo do sistema: a contagem é cega.</summary>
    [HttpGet("contagem-ciclica")]
    [HorusAuthorizeRoles("administrador", "gerente", "estoquista")]
    public async Task<IActionResult> ContagemCiclica()
    {
        if (Usuario() is not { } u) return Sessao();
        var parametros = await config.ObterAsync(u.CompanyId, HttpContext.RequestAborted);
        var lista = await detalhe.ContagemCiclicaAsync(u.CompanyId, parametros, HttpContext.RequestAborted);
        return Ok(new ApiResponse<object>
        {
            Success = true,
            Message = "Contagem cíclica do dia.",
            Data = new
            {
                itensPorDia = parametros.ContagemItensDia,
                periodicidade = new { a = parametros.ContagemDiasA, b = parametros.ContagemDiasB, c = parametros.ContagemDiasC },
                itens = lista.Select(i => new
                {
                    produtoId = i.ProdutoId, codigo = i.Codigo, nome = i.Nome, unidade = i.Unidade, classe = i.Classe.ToString(),
                    ultimaContagem = i.UltimaContagemUtc is { } d ? DateTime.SpecifyKind(d, DateTimeKind.Utc).ToString("o") : null, diasDesdeUltima = i.DiasDesdeUltima,
                }).ToList(),
            },
        });
    }

    /// <summary>Acuracidade de cada inventário aplicado no período, com a meta (padrão 98%) e a do período todo.</summary>
    [HttpGet("acuracidade")]
    [HorusAuthorizeRoles("administrador", "gerente", "estoquista")]
    public Task<IActionResult> Acuracidade([FromQuery] FiltrosQuery q) => Bloco("acuracidade", q, async (u, f, p, comValores) =>
    {
        var pontos = await detalhe.AcuracidadePorInventarioAsync(u.CompanyId, f, HttpContext.RequestAborted);
        var geral = await indicadores.AcuraciaAsync(u.CompanyId, f, HttpContext.RequestAborted);
        var metas = EstoqueMetas.Efetivas(p.MetasJson);
        return (object?)new
        {
            periodo = Periodo(f), meta = metas["acuracidade"].Verde,
            atual = geral is null ? null : new { acuracidade = Math.Round(geral.AcuraciaPercentual, 2), itensExatos = Math.Round(geral.ItensExatosPercentual, 1), itens = geral.Itens },
            pontos = pontos.Select(x => new
            {
                inventarioId = x.InventarioId, numero = x.Numero, data = DateTime.SpecifyKind(x.AplicadoEmUtc, DateTimeKind.Utc).ToString("o"),
                itens = x.Itens, acuracidade = x.Acuracidade, itensExatos = x.ItensExatosPercentual,
            }).ToList(),
            vazio = pontos.Count == 0,
        };
    });

    /// <summary>Itens com divergência nos inventários aplicados do período. Valor (R$) só para gerente/administrador.</summary>
    [HttpGet("divergencias")]
    [HorusAuthorizeRoles("administrador", "gerente", "estoquista")]
    public Task<IActionResult> Divergencias([FromQuery] FiltrosQuery q) => Bloco("divergencias", q, async (u, f, p, comValores) =>
    {
        var lista = await detalhe.DivergenciasAsync(u.CompanyId, f, HttpContext.RequestAborted);
        return (object?)new
        {
            periodo = Periodo(f),
            sobras = lista.Where(d => d.Delta > 0).Sum(d => d.Delta), faltas = lista.Where(d => d.Delta < 0).Sum(d => -d.Delta),
            valorSobras = comValores ? (decimal?)lista.Where(d => d.ValorDelta > 0).Sum(d => d.ValorDelta) : null,
            valorFaltas = comValores ? (decimal?)lista.Where(d => d.ValorDelta < 0).Sum(d => -d.ValorDelta) : null,
            itens = lista.Take(300).Select(d => new
            {
                produtoId = d.ProdutoId, codigo = d.Codigo, nome = d.Nome, inventario = d.Numero,
                data = DateTime.SpecifyKind(d.AplicadoEmUtc, DateTimeKind.Utc).ToString("o"),
                sistema = d.SaldoAntes, contado = d.Contado, diferenca = d.Delta, valor = comValores ? (decimal?)d.ValorDelta : null,
            }).ToList(),
            vazio = lista.Count == 0,
        };
    });

    /// <summary>Perdas por motivo e por mês (quantidade e, para gerente/administrador, valor e % do CMV).</summary>
    [HttpGet("perdas")]
    [HorusAuthorizeRoles("administrador", "gerente", "estoquista")]
    public Task<IActionResult> Perdas([FromQuery] FiltrosQuery q) => Bloco("perdas", q, async (u, f, p, comValores) =>
    {
        var movs = await detalhe.PerdasAsync(u.CompanyId, f, HttpContext.RequestAborted);
        var cmv = EstoqueIndicadoresAB.Consolidar(await indicadores.ProdutosAsync(u.CompanyId, f, p, HttpContext.RequestAborted), f, p).Cmv;
        var principais = movs.GroupBy(m => m.Motivo).OrderByDescending(g => g.Sum(m => m.Valor)).Take(5).Select(g => g.Key).ToHashSet();
        string Grupo(string motivo) => principais.Contains(motivo) ? motivo : "Outros";
        var motivos = movs.GroupBy(m => Grupo(m.Motivo)).Select(g => new
        {
            motivo = g.Key, quantidade = g.Sum(m => m.Quantidade), valor = comValores ? (decimal?)g.Sum(m => m.Valor) : null,
        }).OrderByDescending(m => m.valor ?? m.quantidade).ToList();
        var meses = movs.GroupBy(m => m.Data.ToString("yyyy-MM")).OrderBy(g => g.Key).Select(g => new
        {
            mes = g.Key,
            motivos = g.GroupBy(m => Grupo(m.Motivo)).Select(x => new { motivo = x.Key, quantidade = x.Sum(m => m.Quantidade), valor = comValores ? (decimal?)x.Sum(m => m.Valor) : null }).ToList(),
        }).ToList();
        var valorTotal = movs.Sum(m => m.Valor);
        return (object?)new
        {
            periodo = Periodo(f),
            totais = new
            {
                quantidade = movs.Sum(m => m.Quantidade), ocorrencias = movs.Count,
                valor = comValores ? (decimal?)valorTotal : null,
                percentualCmv = comValores && cmv > 0 ? (decimal?)Math.Round(100m * valorTotal / cmv, 2) : null,
            },
            motivos, meses, vazio = movs.Count == 0,
        };
    });

    /// <summary>Todos os produtos parados (sem saída além do limite de dias da configuração), do maior valor ao menor (estoquista: do mais antigo).</summary>
    [HttpGet("parados")]
    [HorusAuthorizeRoles("administrador", "gerente", "estoquista")]
    public Task<IActionResult> Parados([FromQuery] FiltrosQuery q) => Bloco("parados", q, async (u, f, p, comValores) =>
    {
        var itens = await indicadores.ProdutosAsync(u.CompanyId, f, p, HttpContext.RequestAborted);
        var parados = itens.Where(i => i.Parado).ToList();
        var ordenados = comValores ? parados.OrderByDescending(i => i.ValorEstoque) : parados.OrderByDescending(i => i.DiasSemSaida ?? int.MaxValue);
        return (object?)new
        {
            periodo = Periodo(f), diasParado = p.DiasParado, total = parados.Count,
            valorTotal = comValores ? (decimal?)parados.Sum(i => i.ValorEstoque) : null,
            itens = ordenados.Take(500).Select(i => new
            {
                produtoId = i.ProdutoId, codigo = i.Bruto.Codigo, nome = i.Bruto.Nome, saldo = i.Saldo, diasSemSaida = i.DiasSemSaida,
                ultimaSaida = i.Bruto.UltimaSaidaUtc is { } d ? DateTime.SpecifyKind(d, DateTimeKind.Utc).ToString("o") : null,
                valor = comValores ? (decimal?)i.ValorEstoque : null,
            }).ToList(),
            vazio = parados.Count == 0,
        };
    });

    /// <summary>Fornecedores: lead time prometido x real, pedidos recebidos, % no prazo e produtos vinculados.</summary>
    [HttpGet("fornecedores")]
    [HorusAuthorizeRoles("administrador", "gerente", "estoquista")]
    public Task<IActionResult> Fornecedores([FromQuery] FiltrosQuery q) => Bloco("fornecedores", q, async (u, f, p, comValores) =>
    {
        var lead = await indicadores.LeadTimeFornecedoresAsync(u.CompanyId, HttpContext.RequestAborted);
        var produtos = await indicadores.AgregarAsync(u.CompanyId, f with { CategoriaId = null, FornecedorId = null }, HttpContext.RequestAborted);
        var porFornecedor = produtos.Where(x => x.FornecedorId is not null).GroupBy(x => x.FornecedorId!).ToDictionary(g => g.Key, g => g.Count());
        return (object?)new
        {
            fornecedores = lead.Select(l => new
            {
                fornecedorId = l.FornecedorId, nome = l.Nome, prometidoDias = l.PrometidoDias, realDias = l.MediaDias, desvioDias = l.DesvioDias,
                pedidos = l.Pedidos, noPrazoPercentual = l.NoPrazoPercentual, produtos = porFornecedor.GetValueOrDefault(l.FornecedorId),
                atrasoDias = l.MediaDias is { } r && l.PrometidoDias is { } pr ? (decimal?)Math.Round(r - pr, 1) : null,
            }).OrderByDescending(x => x.pedidos).ThenBy(x => x.nome).ToList(),
            vazio = lead.Count == 0,
        };
    });

    /// <summary>Faixas do histograma da demanda diária: uma para os dias sem venda e até 6 de mesma largura para os demais.</summary>
    private static List<object> Histograma(IReadOnlyList<decimal> demanda)
    {
        var faixas = new List<object>();
        var zeros = demanda.Count(d => d <= 0);
        faixas.Add(new { faixa = "0", dias = zeros });
        var positivos = demanda.Where(d => d > 0).ToList();
        if (positivos.Count == 0) return faixas;
        var max = positivos.Max();
        var bins = (int)Math.Min(6, Math.Max(1, Math.Ceiling(max)));
        var largura = max / bins;
        for (var b = 0; b < bins; b++)
        {
            var de = largura * b;
            var ate = largura * (b + 1);
            var n = positivos.Count(d => b == bins - 1 ? d > de : d > de && d <= ate);
            faixas.Add(new { faixa = $"{de.ToString("0.#", CultureInfo.GetCultureInfo("pt-BR"))}–{ate.ToString("0.#", CultureInfo.GetCultureInfo("pt-BR"))}", dias = n });
        }
        return faixas;
    }

    // ---------- etapa 6: configurações ----------

    public sealed class MetaRequest
    {
        public decimal? Verde { get; set; }
        public decimal? Amarelo { get; set; }
    }

    public sealed class ConfigRequest
    {
        public int? JanelaConsumoDias { get; set; }
        public decimal? NivelServicoZ { get; set; }
        public decimal? AbcLimiteA { get; set; }
        public decimal? AbcLimiteB { get; set; }
        public string? AbcBase { get; set; }
        public decimal? FsnLimiteFast { get; set; }
        public int? DiasParado { get; set; }
        public int? DiasExcesso { get; set; }
        public int? CoberturaAlvoDias { get; set; }
        public int? LeadTimePadraoDias { get; set; }
        public int? ContagemDiasA { get; set; }
        public int? ContagemDiasB { get; set; }
        public int? ContagemDiasC { get; set; }
        public int? ContagemItensDia { get; set; }
        public Dictionary<string, MetaRequest>? Metas { get; set; }
    }

    private static object ConfigDto(EstoqueParametros p, bool podeEditar) => new
    {
        atual = ParametrosDto(p), padrao = ParametrosDto(EstoqueParametros.Padrao), podeEditar,
    };

    private static object ParametrosDto(EstoqueParametros p) => new
    {
        janelaConsumoDias = p.JanelaConsumoDias, nivelServicoZ = p.NivelServicoZ, abcLimiteA = p.AbcLimiteA, abcLimiteB = p.AbcLimiteB, abcBase = p.AbcBase,
        fsnLimiteFast = p.FsnLimiteFast, diasParado = p.DiasParado, diasExcesso = p.DiasExcesso, coberturaAlvoDias = p.CoberturaAlvoDias,
        leadTimePadraoDias = p.LeadTimePadraoDias, contagemDiasA = p.ContagemDiasA, contagemDiasB = p.ContagemDiasB, contagemDiasC = p.ContagemDiasC,
        contagemItensDia = p.ContagemItensDia,
        metas = EstoqueMetas.Efetivas(p.MetasJson).ToDictionary(k => k.Key, k => new { verde = k.Value.Verde, amarelo = k.Value.Amarelo, maiorMelhor = k.Value.MaiorMelhor }),
    };

    /// <summary>Configurações da análise da empresa (padrões do spec quando nunca salvas). Leitura para os três perfis.</summary>
    [HttpGet("config")]
    [HorusAuthorizeRoles("administrador", "gerente", "estoquista")]
    public async Task<IActionResult> ObterConfig()
    {
        if (Usuario() is not { } u) return Sessao();
        var p = await config.ObterAsync(u.CompanyId, HttpContext.RequestAborted);
        return Ok(new ApiResponse<object> { Success = true, Message = "Configurações da análise.", Data = ConfigDto(p, HorusRoles.IsGerenteOuAdmin(u.Role)) });
    }

    /// <summary>Salva as configurações (só gerente/administrador), invalida o cache e recalcula a classificação ABC/FSN na hora.</summary>
    [HttpPut("config")]
    [HorusAuthorizeRoles("administrador", "gerente")]
    public async Task<IActionResult> SalvarConfig([FromBody] ConfigRequest body)
    {
        if (Usuario() is not { } u) return Sessao();
        var atual = await config.ObterAsync(u.CompanyId, HttpContext.RequestAborted);
        var metas = EstoqueMetas.Efetivas(atual.MetasJson).ToDictionary(k => k.Key, k => new { verde = k.Value.Verde, amarelo = k.Value.Amarelo });
        if (body.Metas is not null)
        {
            foreach (var (chave, m) in body.Metas)
            {
                if (!EstoqueMetas.Padrao.ContainsKey(chave)) return BadRequest(Erro($"Meta desconhecida: {chave}."));
                var anterior = metas[chave];
                var verde = m.Verde ?? anterior.verde;
                var amarelo = m.Amarelo ?? anterior.amarelo;
                var maior = EstoqueMetas.Padrao[chave].MaiorMelhor;
                if (maior ? amarelo > verde : amarelo < verde) return BadRequest(Erro($"Meta de {chave}: o limite do amarelo deve ficar além do verde."));
                metas[chave] = new { verde, amarelo };
            }
        }
        var novo = atual with
        {
            JanelaConsumoDias = body.JanelaConsumoDias ?? atual.JanelaConsumoDias, NivelServicoZ = body.NivelServicoZ ?? atual.NivelServicoZ,
            AbcLimiteA = body.AbcLimiteA ?? atual.AbcLimiteA, AbcLimiteB = body.AbcLimiteB ?? atual.AbcLimiteB,
            AbcBase = string.IsNullOrWhiteSpace(body.AbcBase) ? atual.AbcBase : body.AbcBase.Trim().ToLowerInvariant(),
            FsnLimiteFast = body.FsnLimiteFast ?? atual.FsnLimiteFast, DiasParado = body.DiasParado ?? atual.DiasParado,
            DiasExcesso = body.DiasExcesso ?? atual.DiasExcesso, CoberturaAlvoDias = body.CoberturaAlvoDias ?? atual.CoberturaAlvoDias,
            LeadTimePadraoDias = body.LeadTimePadraoDias ?? atual.LeadTimePadraoDias, ContagemDiasA = body.ContagemDiasA ?? atual.ContagemDiasA,
            ContagemDiasB = body.ContagemDiasB ?? atual.ContagemDiasB, ContagemDiasC = body.ContagemDiasC ?? atual.ContagemDiasC,
            ContagemItensDia = body.ContagemItensDia ?? atual.ContagemItensDia,
            MetasJson = System.Text.Json.JsonSerializer.Serialize(metas),
        };
        if (EstoqueConfigAnaliseAB.Validar(novo) is { } erro) return BadRequest(Erro(erro));
        await config.SalvarAsync(u.CompanyId, novo, u.Name, HttpContext.RequestAborted);
        await classificacao.RecalcularAsync(u.CompanyId, novo, ct: HttpContext.RequestAborted);
        cache.Invalidar(u.CompanyId);
        return Ok(new ApiResponse<object> { Success = true, Message = "Configurações salvas.", Data = ConfigDto(novo, true) });
    }

    /// <summary>Volta aos padrões do spec (apaga a configuração da empresa).</summary>
    [HttpDelete("config")]
    [HorusAuthorizeRoles("administrador", "gerente")]
    public async Task<IActionResult> RestaurarConfig()
    {
        if (Usuario() is not { } u) return Sessao();
        await config.RestaurarPadraoAsync(u.CompanyId, HttpContext.RequestAborted);
        await classificacao.RecalcularAsync(u.CompanyId, EstoqueParametros.Padrao, ct: HttpContext.RequestAborted);
        cache.Invalidar(u.CompanyId);
        return Ok(new ApiResponse<object> { Success = true, Message = "Configurações restauradas para o padrão.", Data = ConfigDto(EstoqueParametros.Padrao, true) });
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
        Func<AuthenticatedUser, FiltroAnalise, EstoqueParametros, bool, Task<object?>> calcular)
    {
        if (Usuario() is not { } u) return Sessao();
        var (filtro, erro) = Filtro(q);
        if (filtro is null) return BadRequest(Erro(erro!));

        var comValores = HorusRoles.IsGerenteOuAdmin(u.Role);
        var chave = $"{rota}|{filtro}|{q.Comparar}|{(comValores ? "v" : "q")}";
        var (dados, calculadoEm, doCache) = await cache.ObterAsync<object?>(u.CompanyId, chave, async () =>
        {
            var parametros = await config.ObterAsync(u.CompanyId, HttpContext.RequestAborted);
            return await calcular(u, filtro, parametros, comValores);
        });
        if (dados is null) return NotFound(Erro("Não encontrado."));
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
