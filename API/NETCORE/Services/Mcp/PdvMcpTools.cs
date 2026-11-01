/**
 * Arquivo: API/NETCORE/Services/Mcp/PdvMcpTools.cs
 * Objetivo: ferramentas do servidor MCP (endpoint /mcp) para agentes de IA consultarem relatórios, produtos,
 *           controle de validade e promoções. SOMENTE LEITURA: nenhuma ferramenta daqui altera dados
 *           (as de escrita ficam em ProdutoEdicaoMcpTools, só para token com permissão).
 * Entradas esperadas: chamadas MCP já autenticadas pelo HorusAuthMiddleware com token de integração.
 * Isolamento: a empresa vem sempre do token (HttpContext.Items["CurrentUser"]); nunca de parâmetro da ferramenta.
 * Documentação: docs/MCP.md (ao criar/alterar ferramenta, atualize lá também).
 */
using System.ComponentModel;
using System.Globalization;
using System.Text;
using System.Text.Encodings.Web;
using System.Text.Json;
using System.Text.Json.Nodes;
using HORUSPDV_API.Models.Categorias;
using HORUSPDV_API.Models.Produtos;
using HORUSPDV_API.Models.Requests;
using HORUSPDV_API.Repositories.DatabaseAccess;
using HORUSPDV_API.Services.Categorias;
using HORUSPDV_API.Services.Produtos;
using HORUSPDV_API.Services.Promocoes;
using HORUSPDV_API.Services.Security;
using HORUSPDV_API.Services.Shared;
using ModelContextProtocol;
using ModelContextProtocol.Server;

namespace HORUSPDV_API.Services.Mcp;

[McpServerToolType]
public class PdvMcpTools(
    IHttpContextAccessor httpContextAccessor,
    RelatorioAB relatorioAB,
    IProdutoService produtoService,
    ICategoriaService categoriaService,
    LoteService loteService,
    IPromocaoService promocaoService)
{
    public const string ServerInstructions =
        "Servidor MCP do Quack PDV. Consulta relatórios, produtos, controle de validade por lote, " +
        "promoções, auditoria fiscal e notas de entrada da loja dona do token. Datas sempre em AAAA-MM-DD. " +
        "Valores em reais já formatados (R$) nos relatórios. Comece por listar_relatorios para conhecer os " +
        "relatórios e filtros, e por listar_categorias quando precisar de um categoriaId. Para auditoria fiscal, " +
        "comece por resumo_auditoria_fiscal e use listar_produtos_fiscais/obter_fiscal_produto para conferir os " +
        "dados. Listas grandes são paginadas: use pagina/tamanhoPagina e confira totalLinhas/total antes de " +
        "concluir que viu tudo. Escrita (só com token que tenha permissão de edição): editar_produto e " +
        "ajustar_estoque_produto SIMULAM por padrão (confirmar=false); mostre o antes → depois ao lojista e só chame " +
        "com confirmar=true depois que ele confirmar. Valores em reais como número (12.9 = R$ 12,90).";

    private const int MaxPageSize = 200;

    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web)
    {
        // Acentos e R$ legíveis para o agente, em vez de \uXXXX.
        Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping
    };

    // ---------------------------------------------------------------- Relatórios

    [McpServerTool(Name = "listar_relatorios", Title = "Listar relatórios", ReadOnly = true, Idempotent = true, OpenWorld = false)]
    [Description("Lista os relatórios disponíveis (id, nome, descrição e filtros aceitos). Use antes de gerar_relatorio.")]
    public string ListarRelatorios()
        => Serialize(new
        {
            observacao = "Sem dataInicio/dataFim, os relatórios de período usam os últimos 30 dias.",
            relatorios = McpReportCatalog.Reports.Select(r => new { id = r.Id, nome = r.Nome, descricao = r.Descricao, filtros = r.Filtros })
        });

    [McpServerTool(Name = "gerar_relatorio", Title = "Gerar relatório", ReadOnly = true, Idempotent = true, OpenWorld = false)]
    [Description("Gera um relatório do PDV e devolve colunas e linhas (paginadas). Cada relatório aceita só os filtros " +
        "indicados em listar_relatorios; filtros que não se aplicam são ignorados.")]
    public async Task<string> GerarRelatorio(
        [Description("Id do relatório, ex.: vendas-periodo, produtos-mais-vendidos, estoque-critico, vencimentos, margem-por-categoria.")] string relatorio,
        [Description("Início do período, AAAA-MM-DD (padrão: 30 dias atrás).")] string? dataInicio = null,
        [Description("Fim do período, AAAA-MM-DD, inclusive (padrão: hoje).")] string? dataFim = null,
        [Description("vendas-periodo: 'diario' (padrão), 'semanal' ou 'mensal'.")] string? agruparPor = null,
        [Description("Relatórios de vendas: 'pix', 'debito', 'credito' ou 'dinheiro'.")] string? formaPagamento = null,
        [Description("Id de departamento ou subcategoria (ver listar_categorias).")] string? categoriaId = null,
        [Description("vencimentos: 'todos', 'vencidos', '7d', '15d' ou '30d'. inadimplencia: 'todos', 'em_dia', 'atraso_30' ou 'atraso_60'.")] string? faixa = null,
        [Description("estoque-critico: true = só produtos sem estoque (padrão: estoque até 5).")] bool somenteSemEstoque = false,
        [Description("lucro-markup-produtos: trecho do código ou nome do produto.")] string? buscaProduto = null,
        [Description("clientes-frequentes: hora inicial HH:mm.")] string? horaInicio = null,
        [Description("clientes-frequentes: hora final HH:mm.")] string? horaFim = null,
        [Description("log-atividades: tipo exato do evento.")] string? tipoEvento = null,
        [Description("Página das linhas, a partir de 1.")] int pagina = 1,
        [Description("Linhas por página (1 a 200, padrão 100).")] int tamanhoPagina = 100)
    {
        var id = (relatorio ?? string.Empty).Trim().ToLowerInvariant();
        if (McpReportCatalog.Reports.All(r => r.Id != id))
        {
            throw new McpException($"Relatório '{relatorio}' não existe. Use listar_relatorios para ver os ids válidos.");
        }

        var filters = new Dictionary<string, JsonElement>();
        void Add(string key, string? value)
        {
            if (!string.IsNullOrWhiteSpace(value)) filters[key] = JsonSerializer.SerializeToElement(value.Trim());
        }

        Add("startDate", NormalizeDate(dataInicio, nameof(dataInicio)));
        Add("endDate", NormalizeDate(dataFim, nameof(dataFim)));
        Add("groupBy", Normalize(agruparPor) switch
        {
            "" => null,
            "diario" or "daily" => "daily",
            "semanal" or "weekly" => "weekly",
            "mensal" or "monthly" => "monthly",
            _ => throw new McpException("agruparPor deve ser 'diario', 'semanal' ou 'mensal'.")
        });
        Add("paymentMethod", formaPagamento);
        Add("categoriaId", categoriaId);
        Add("faixa", faixa?.ToLowerInvariant());
        Add("productSearch", buscaProduto);
        Add("startTime", NormalizeTime(horaInicio, nameof(horaInicio)));
        Add("endTime", NormalizeTime(horaFim, nameof(horaFim)));
        Add("eventType", tipoEvento);
        if (somenteSemEstoque) filters["onlyOutOfStock"] = JsonSerializer.SerializeToElement(true);

        var companyId = CurrentCompanyId();
        object data;
        try
        {
            // O token representa a gestão da loja: o log de atividades não é restrito a um usuário.
            data = await relatorioAB.GerarAsync(companyId, id, filters);
        }
        catch (InvalidOperationException ex)
        {
            throw new McpException(ex.Message);
        }

        var node = JsonSerializer.SerializeToNode(data, Json)!.AsObject();
        var rows = node["rows"] as JsonArray ?? [];
        var (page, size) = NormalizePaging(pagina, tamanhoPagina, 100);
        var total = rows.Count;
        var slice = new JsonArray(rows.Skip((page - 1) * size).Take(size).Select(row => row?.DeepClone()).ToArray());

        node["rows"] = slice;
        node["relatorio"] = id;
        node["totalLinhas"] = total;
        node["pagina"] = page;
        node["tamanhoPagina"] = size;
        node["temMais"] = page * size < total;
        return node.ToJsonString(Json);
    }

    // ---------------------------------------------------------------- Produtos

    [McpServerTool(Name = "buscar_produtos", Title = "Buscar produtos", ReadOnly = true, Idempotent = true, OpenWorld = false)]
    [Description("Busca produtos do cadastro (resumo: código, nome, categoria, estoque, custo, preço de venda, validade). " +
        "Sem 'busca', lista todos em ordem alfabética, paginado. Para todos os campos de um produto use obter_produto.")]
    public async Task<string> BuscarProdutos(
        [Description("Trecho do nome, código, código de barras (GTIN), marca ou fornecedor. Ignora acentos e maiúsculas.")] string? busca = null,
        [Description("Id de departamento ou subcategoria (ver listar_categorias).")] string? categoriaId = null,
        [Description("true = só produtos com estoque menor ou igual ao estoque mínimo cadastrado.")] bool somenteEstoqueBaixo = false,
        [Description("true = só produtos sem estoque (quantidade zero ou negativa).")] bool somenteSemEstoque = false,
        [Description("Página, a partir de 1.")] int pagina = 1,
        [Description("Itens por página (1 a 200, padrão 50).")] int tamanhoPagina = 50)
    {
        var companyId = CurrentCompanyId();
        IEnumerable<ProdutoModel> produtos = await produtoService.ListarAsync(companyId);

        var termo = Normalize(busca);
        if (termo.Length > 0)
        {
            produtos = produtos.Where(p =>
                Normalize(p.ProductName).Contains(termo) ||
                Normalize(p.ProductCode).Contains(termo) ||
                Normalize(p.Gtin).Contains(termo) ||
                Normalize(p.Marca).Contains(termo) ||
                Normalize(p.ProductSupplier).Contains(termo));
        }

        if (!string.IsNullOrWhiteSpace(categoriaId))
        {
            // Um departamento inclui suas subcategorias, como nos relatórios.
            var ids = await CategoriaComFilhasAsync(companyId, categoriaId.Trim());
            produtos = produtos.Where(p => p.CategoriaId is not null && ids.Contains(p.CategoriaId));
        }

        if (somenteSemEstoque) produtos = produtos.Where(p => Quantity(p.ProductQnt) <= 0);
        if (somenteEstoqueBaixo) produtos = produtos.Where(p => Quantity(p.ProductQnt) <= Quantity(p.EstoqueMinimo));

        var lista = produtos.OrderBy(p => p.ProductName, StringComparer.Create(new CultureInfo("pt-BR"), true)).ToList();
        var (page, size) = NormalizePaging(pagina, tamanhoPagina, 50);

        return Serialize(new
        {
            total = lista.Count,
            pagina = page,
            tamanhoPagina = size,
            temMais = page * size < lista.Count,
            itens = lista.Skip((page - 1) * size).Take(size).Select(p => new
            {
                id = p.Id,
                codigo = p.ProductCode,
                nome = p.ProductName,
                categoria = p.CategoriaNome,
                categoriaId = p.CategoriaId,
                fornecedor = p.ProductSupplier,
                marca = p.Marca,
                gtin = p.Gtin,
                estoque = p.ProductQnt,
                estoqueMinimo = p.EstoqueMinimo,
                custo = p.ProductUnitPrice,
                precoVenda = p.ProductSalePrice,
                lucro = p.Lucro,
                controlaValidade = p.ControlaValidade,
                dataValidade = p.DataValidade,
                diasParaVencer = p.DiasRestantes
            })
        });
    }

    [McpServerTool(Name = "obter_produto", Title = "Obter produto", ReadOnly = true, Idempotent = true, OpenWorld = false)]
    [Description("Devolve o cadastro completo de um produto (estoque, custos, preços, margem, validade e dados fiscais).")]
    public async Task<string> ObterProduto(
        [Description("Id do produto, código interno ou código de barras (GTIN) exato.")] string idOuCodigo)
    {
        var chave = (idOuCodigo ?? string.Empty).Trim();
        if (chave.Length == 0) throw new McpException("Informe o id, código ou GTIN do produto.");

        var produtos = await produtoService.ListarAsync(CurrentCompanyId());
        var produto = produtos.FirstOrDefault(p => string.Equals(p.Id, chave, StringComparison.OrdinalIgnoreCase))
            ?? produtos.FirstOrDefault(p => string.Equals(p.ProductCode, chave, StringComparison.OrdinalIgnoreCase))
            ?? produtos.FirstOrDefault(p => string.Equals(p.Gtin, chave, StringComparison.OrdinalIgnoreCase));
        if (produto is null)
        {
            throw new McpException($"Produto '{chave}' não encontrado. Use buscar_produtos para localizar pelo nome.");
        }

        var node = JsonSerializer.SerializeToNode(produto, Json)!.AsObject();
        // Campos de tela, sem valor para o agente (a imagem pode ser um data URL enorme).
        node.Remove("productImageUrl");
        node.Remove("productImageName");
        node.Remove("versaoPreco");
        return node.ToJsonString(Json);
    }

    [McpServerTool(Name = "listar_categorias", Title = "Listar categorias", ReadOnly = true, Idempotent = true, OpenWorld = false)]
    [Description("Lista departamentos e subcategorias (id, nome, quantidade de produtos). Os ids servem no filtro " +
        "categoriaId das outras ferramentas.")]
    public async Task<string> ListarCategorias(
        [Description("true = só categorias ativas.")] bool apenasAtivas = false)
    {
        var arvore = await categoriaService.ListarArvoreAsync(CurrentCompanyId(), apenasAtivas);
        return Serialize(arvore.Select(ToCategoria));
    }

    // ---------------------------------------------------------------- Controle de validade

    [McpServerTool(Name = "resumo_validade", Title = "Resumo de validade", ReadOnly = true, Idempotent = true, OpenWorld = false)]
    [Description("Panorama do controle de validade: lotes vencidos, críticos e em atenção, valor em risco (saldo x custo), " +
        "produtos que controlam validade sem lote e contagem de produtos vencendo em 7/15/30 dias. " +
        "Para a lista dos lotes use consultar_lotes.")]
    public async Task<string> ResumoValidade(CancellationToken cancellationToken = default)
    {
        var companyId = CurrentCompanyId();
        var alertas = await loteService.ListarAlertasAsync(companyId, cancellationToken);
        var produtos = await produtoService.ObterResumoVencimentosAsync(companyId);
        return Serialize(new
        {
            lotes = new
            {
                vencidos = alertas.Vencidos,
                criticos = alertas.Criticos,
                atencao = alertas.Atencao,
                valorEmRisco = alertas.ValorEmRisco,
                produtosSemLote = alertas.ProdutosSemLote,
                modoBaixaPorLote = alertas.Modo
            },
            produtos
        });
    }

    [McpServerTool(Name = "consultar_lotes", Title = "Consultar lotes", ReadOnly = true, Idempotent = true, OpenWorld = false)]
    [Description("Consulta lotes de validade com filtros e paginação. Cada lote traz produto, número do lote, data de " +
        "validade, dias para vencer, saldo estimado, faixa de alerta e valor em risco. Ordenado pela validade mais próxima.")]
    public async Task<string> ConsultarLotes(
        [Description("Trecho do nome do produto, código do produto ou número do lote.")] string? busca = null,
        [Description("Validade a partir de, AAAA-MM-DD (inclusive).")] string? validadeDe = null,
        [Description("Validade até, AAAA-MM-DD (inclusive).")] string? validadeAte = null,
        [Description("Id de departamento ou subcategoria (ver listar_categorias).")] string? categoriaId = null,
        [Description("'vencido', 'critico', 'atencao', 'ok' (fora da janela de alerta) ou 'esgotado' (sem saldo). Vazio = todas.")] string? faixa = null,
        [Description("true = só lotes com saldo estimado maior que zero.")] bool somenteComSaldo = false,
        [Description("Origem do lote: 'nfe', 'compra', 'ajuste', 'cadastro', 'manual' ou 'inicial'.")] string? origem = null,
        [Description("Página, a partir de 1.")] int pagina = 1,
        [Description("Lotes por página (1 a 200, padrão 50).")] int tamanhoPagina = 50,
        CancellationToken cancellationToken = default)
    {
        // O serviço devolve lista vazia para faixa desconhecida; para o agente, um erro claro evita conclusão errada.
        var faixaNormalizada = Normalize(faixa);
        if (faixaNormalizada is not ("" or "todas" or "vencido" or "critico" or "atencao" or "ok" or "esgotado"))
        {
            throw new McpException("faixa deve ser 'vencido', 'critico', 'atencao', 'ok' ou 'esgotado' (ou vazio para todas).");
        }

        var (page, size) = NormalizePaging(pagina, tamanhoPagina, 50);
        var filtro = new LoteConsultaFiltro
        {
            Busca = busca,
            De = NormalizeDate(validadeDe, nameof(validadeDe)),
            Ate = NormalizeDate(validadeAte, nameof(validadeAte)),
            CategoriaId = categoriaId,
            Faixa = faixaNormalizada,
            ComSaldo = somenteComSaldo ? true : null,
            Origem = origem,
            Pagina = page,
            TamanhoPagina = size
        };

        try
        {
            var data = await loteService.ConsultarAsync(CurrentCompanyId(), filtro, cancellationToken);
            return Serialize(new
            {
                total = data.Total,
                pagina = data.Pagina,
                tamanhoPagina = data.TamanhoPagina,
                temMais = data.Pagina * data.TamanhoPagina < data.Total,
                valorEmRisco = data.ValorEmRisco,
                modoBaixaPorLote = data.Modo,
                itens = data.Itens
            });
        }
        catch (InvalidOperationException ex)
        {
            throw new McpException(ex.Message);
        }
    }

    [McpServerTool(Name = "lotes_do_produto", Title = "Lotes de um produto", ReadOnly = true, Idempotent = true, OpenWorld = false)]
    [Description("Lista todos os lotes de validade de um produto (número, validade, dias para vencer, quantidade inicial e saldo estimado).")]
    public async Task<string> LotesDoProduto(
        [Description("Id do produto (campo 'id' de buscar_produtos).")] string produtoId,
        CancellationToken cancellationToken = default)
    {
        if (string.IsNullOrWhiteSpace(produtoId)) throw new McpException("Informe o id do produto.");
        return Serialize(await loteService.ListarPorProdutoAsync(CurrentCompanyId(), produtoId.Trim(), cancellationToken));
    }

    [McpServerTool(Name = "produtos_a_vencer", Title = "Produtos a vencer", ReadOnly = true, Idempotent = true, OpenWorld = false)]
    [Description("Lista produtos que controlam validade e vencem dentro de N dias (inclui os já vencidos), pela data de " +
        "validade do cadastro do produto. Para o detalhe por lote prefira consultar_lotes.")]
    public async Task<string> ProdutosAVencer(
        [Description("Janela em dias a partir de hoje (1 a 365, padrão 15).")] int dias = 15)
    {
        var janela = Math.Clamp(dias, 1, 365);
        var produtos = await produtoService.ListarVencimentosAsync(CurrentCompanyId(), janela);
        return Serialize(new
        {
            dias = janela,
            total = produtos.Count,
            itens = produtos.Select(p => new
            {
                id = p.Id,
                codigo = p.ProductCode,
                nome = p.ProductName,
                categoria = p.CategoriaNome,
                estoque = p.ProductQnt,
                custo = p.ProductUnitPrice,
                precoVenda = p.ProductSalePrice,
                dataValidade = p.DataValidade,
                diasParaVencer = p.DiasRestantes
            })
        });
    }

    [McpServerTool(Name = "status_baixa_por_lote", Title = "Status da baixa por lote (FEFO)", ReadOnly = true, Idempotent = true, OpenWorld = false)]
    [Description("Situação da baixa por lote (FEFO: vence primeiro, sai primeiro): modo atual, lotes com saldo e as " +
        "maiores divergências entre saldo real e estimado.")]
    public async Task<string> StatusBaixaPorLote(CancellationToken cancellationToken = default)
        => Serialize(await loteService.ObterFefoStatusAsync(CurrentCompanyId(), cancellationToken));

    [McpServerTool(Name = "prazos_validade_por_categoria", Title = "Prazos de validade por categoria", ReadOnly = true, Idempotent = true, OpenWorld = false)]
    [Description("Prazo padrão de validade (dias) e antecedência do alerta configurados em cada categoria.")]
    public async Task<string> PrazosValidadePorCategoria(CancellationToken cancellationToken = default)
        => Serialize(await loteService.ListarCategoriasAsync(CurrentCompanyId(), cancellationToken));

    // ---------------------------------------------------------------- Promoções

    [McpServerTool(Name = "listar_promocoes", Title = "Listar promoções", ReadOnly = true, Idempotent = true, OpenWorld = false)]
    [Description("Lista as promoções da loja: nome, tipo, desconto/preço fixo/leve-pague, vigência, se está ativa, " +
        "categoria e quantidade de produtos. Para os produtos e o resultado de uma promoção use obter_promocao.")]
    public async Task<string> ListarPromocoes(
        [Description("true = só as promoções em vigor agora (ativas e dentro da vigência).")] bool somenteAtivas = false)
    {
        var companyId = CurrentCompanyId();
        var promocoes = somenteAtivas
            ? await promocaoService.ListarAtivasAsync(companyId)
            : await promocaoService.ListarAsync(companyId);

        return Serialize(new
        {
            total = promocoes.Count,
            itens = promocoes.Select(p => new
            {
                id = p.Id,
                nome = p.Nome,
                tipo = p.Tipo,
                valorDesconto = p.ValorDesconto,
                precoFixo = p.PrecoFixo,
                quantidadeLeva = p.QuantidadeLeva,
                quantidadePaga = p.QuantidadePaga,
                quantidadeMinima = p.QuantidadeMinima,
                inicioVigencia = p.InicioVigencia,
                fimVigencia = p.FimVigencia,
                ativa = p.Ativa,
                categoriaId = p.CategoriaId,
                categoria = p.CategoriaNome,
                quantidadeProdutos = p.ProdutoIds.Count,
                criadoPor = p.CriadoPor,
                criadoEm = p.CriadoEm
            })
        });
    }

    [McpServerTool(Name = "obter_promocao", Title = "Obter promoção", ReadOnly = true, Idempotent = true, OpenWorld = false)]
    [Description("Detalhe de uma promoção: regras, vigência, produtos participantes (código e nome) e resultado " +
        "(quantidade de vendas, receita bruta e líquida, desconto total concedido e margem líquida).")]
    public async Task<string> ObterPromocao(
        [Description("Id da promoção (campo 'id' de listar_promocoes).")] string id)
    {
        if (string.IsNullOrWhiteSpace(id)) throw new McpException("Informe o id da promoção.");

        var companyId = CurrentCompanyId();
        var promocao = await promocaoService.ObterPorIdAsync(companyId, id.Trim())
            ?? throw new McpException($"Promoção '{id}' não encontrada. Use listar_promocoes para ver os ids.");

        var porId = promocao.ProdutoIds.Count == 0
            ? []
            : (await produtoService.ListarAsync(companyId)).ToDictionary(p => p.Id, StringComparer.OrdinalIgnoreCase);

        var node = JsonSerializer.SerializeToNode(promocao, Json)!.AsObject();
        node.Remove("companyId");
        node.Remove("produtoIds");
        node["produtos"] = JsonSerializer.SerializeToNode(promocao.ProdutoIds.Select(produtoId =>
            porId.TryGetValue(produtoId, out var p)
                ? new { id = produtoId, codigo = (string?)p.ProductCode, nome = (string?)p.ProductName, precoVenda = (string?)p.ProductSalePrice }
                : new { id = produtoId, codigo = (string?)null, nome = (string?)null, precoVenda = (string?)null }), Json);
        node["resultado"] = JsonSerializer.SerializeToNode(await promocaoService.ObterResultadoAsync(companyId, promocao.Id), Json);
        return node.ToJsonString(Json);
    }

    // ---------------------------------------------------------------- Apoio

    private string CurrentCompanyId()
    {
        // Só chega aqui com token de integração validado; qualquer outra identidade é recusada.
        if (httpContextAccessor.HttpContext?.Items["CurrentUser"] is not AuthenticatedUser { Role: HorusRoles.Integracao } user
            || string.IsNullOrWhiteSpace(user.CompanyId))
        {
            throw new McpException("Token de integração não identificado.");
        }

        return user.CompanyId;
    }

    private async Task<HashSet<string>> CategoriaComFilhasAsync(string companyId, string categoriaId)
    {
        var todas = await categoriaService.ListarTodasAsync(companyId);
        var ids = new HashSet<string>(StringComparer.OrdinalIgnoreCase) { categoriaId };
        foreach (var categoria in todas.Where(c => string.Equals(c.CategoriaPaiId, categoriaId, StringComparison.OrdinalIgnoreCase)))
        {
            ids.Add(categoria.Id);
        }
        return ids;
    }

    private static object ToCategoria(CategoriaModel categoria) => new
    {
        id = categoria.Id,
        nome = categoria.Nome,
        ativa = categoria.Ativa,
        quantidadeProdutos = categoria.QuantidadeProdutos,
        subcategorias = categoria.Subcategorias.Select(ToCategoria)
    };

    private static string Serialize(object? value) => JsonSerializer.Serialize(value, Json);

    private static (int Page, int Size) NormalizePaging(int pagina, int tamanhoPagina, int defaultSize)
        => (Math.Max(1, pagina), tamanhoPagina <= 0 ? defaultSize : Math.Min(tamanhoPagina, MaxPageSize));

    private static decimal Quantity(string? value) => HorusMoneyFormat.ParseDecimal(value);

    private static string? NormalizeDate(string? value, string parameter)
    {
        if (string.IsNullOrWhiteSpace(value)) return null;
        return DateTime.TryParseExact(value.Trim(), "yyyy-MM-dd", CultureInfo.InvariantCulture, DateTimeStyles.None, out var date)
            ? date.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture)
            : throw new McpException($"{parameter} deve estar no formato AAAA-MM-DD (ex.: 2026-10-13).");
    }

    private static string? NormalizeTime(string? value, string parameter)
    {
        if (string.IsNullOrWhiteSpace(value)) return null;
        return TimeSpan.TryParseExact(value.Trim(), @"hh\:mm", CultureInfo.InvariantCulture, out var time)
            ? time.ToString(@"hh\:mm", CultureInfo.InvariantCulture)
            : throw new McpException($"{parameter} deve estar no formato HH:mm (ex.: 08:30).");
    }

    /// <summary>Minúsculas e sem acento, para a busca casar "acucar" com "Açúcar".</summary>
    private static string Normalize(string? value)
    {
        if (string.IsNullOrWhiteSpace(value)) return string.Empty;
        var builder = new StringBuilder(value.Length);
        foreach (var ch in value.Trim().Normalize(NormalizationForm.FormD))
        {
            if (CharUnicodeInfo.GetUnicodeCategory(ch) != UnicodeCategory.NonSpacingMark) builder.Append(char.ToLowerInvariant(ch));
        }
        return builder.ToString();
    }
}
