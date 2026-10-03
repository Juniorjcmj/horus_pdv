/**
 * Arquivo: API/NETCORE/Services/Produtos/LoteService.cs
 * Objetivo: regras do controle de validade por lote: alertas por lote (saldo estimado FEFO, janela
 *           por categoria, valor em risco), registro manual de lote e prazos por categoria.
 * Entradas esperadas: recebe dados já validados pelos controladores e aplica consistência do domínio.
 */
using System.Globalization;
using HORUSPDV_API.Models.Produtos;
using HORUSPDV_API.Models.Requests;
using HORUSPDV_API.Repositories.DatabaseAccess;
using HORUSPDV_API.Services.Security;
using HORUSPDV_API.Services.Shared;

namespace HORUSPDV_API.Services.Produtos;

public class LoteService(LoteAB loteAB)
{
    private const int JanelaAlertaPadraoDias = 15;

    private static readonly string[] FaixasDeAlerta = ["vencido", "critico", "atencao"];

    /// <summary>
    /// Monta TODOS os lotes cadastrados com saldo estimado (FEFO), dias para vencer e faixa.
    /// Faixa: "esgotado" (sem saldo), "vencido", "critico", "atencao" (dentro da janela de alerta) ou "ok".
    /// </summary>
    private async Task<(List<LoteAlertaModel> Itens, string Modo)> MontarTodosAsync(string companyId, CancellationToken cancellationToken)
    {
        var linhas = await loteAB.ListarLinhasAsync(companyId, null, cancellationToken);
        var modo = await loteAB.ObterModoAsync(companyId, cancellationToken);
        var hoje = HorusDateTime.NowDateTime.Date;
        var itens = new List<LoteAlertaModel>();

        foreach (var grupo in linhas.GroupBy(linha => linha.ProdutoId))
        {
            var doProduto = grupo.ToList();
            // 'ativo' usa o saldo real baixado pela venda; 'sombra'/'desligado' mantêm o estimado (Fase 1).
            var saldos = LoteEstimador.Saldos(doProduto, modo == LoteAB.ModoAtivo);

            foreach (var linha in doProduto)
            {
                var saldo = saldos.GetValueOrDefault(linha.Id);
                var dias = (linha.DataValidade.Date - hoje).Days;
                var janela = linha.DiasAlertaCategoria
                    ?? (linha.DiasAlertaProduto > 0 ? linha.DiasAlertaProduto : JanelaAlertaPadraoDias);

                string faixa;
                if (saldo <= 0) faixa = "esgotado";
                else if (dias < 0) faixa = "vencido";
                else if (dias > janela) faixa = "ok";
                else faixa = dias <= Math.Max(2, janela / 3) ? "critico" : "atencao";

                itens.Add(new LoteAlertaModel
                {
                    Id = linha.Id,
                    ProdutoId = linha.ProdutoId,
                    NumeroLote = linha.NumeroLote,
                    DataValidade = linha.DataValidade.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture),
                    DiasParaVencer = dias,
                    QtdInicial = linha.QtdInicial,
                    QtdEstimada = saldo,
                    Origem = linha.Origem,
                    ValidadePadrao = linha.ValidadePadrao,
                    CriadoEm = HorusDateTime.FormatIso(linha.CriadoEm),
                    ProductCode = linha.ProductCode,
                    ProductName = linha.ProductName,
                    CategoriaNome = linha.CategoriaNome,
                    CategoriaId = linha.CategoriaId,
                    CategoriaPaiId = linha.CategoriaPaiId,
                    JanelaAlertaDias = janela,
                    Faixa = faixa,
                    CustoUnitario = linha.CustoUnitario,
                    PrecoVenda = linha.PrecoVenda,
                    ValorEmRisco = Math.Round(saldo * linha.CustoUnitario, 2),
                });
            }
        }

        return (itens, modo);
    }

    public async Task<LoteAlertasResumoModel> ListarAlertasAsync(string companyId, CancellationToken cancellationToken = default)
    {
        var (todos, modo) = await MontarTodosAsync(companyId, cancellationToken);
        var itens = todos
            .Where(item => FaixasDeAlerta.Contains(item.Faixa))
            .OrderBy(item => item.DiasParaVencer)
            .ThenBy(item => item.ProductName)
            .ToList();

        return new LoteAlertasResumoModel
        {
            Vencidos = itens.Count(item => item.Faixa == "vencido"),
            Criticos = itens.Count(item => item.Faixa == "critico"),
            Atencao = itens.Count(item => item.Faixa == "atencao"),
            ValorEmRisco = itens.Sum(item => item.ValorEmRisco),
            ProdutosSemLote = await loteAB.ContarProdutosSemLoteAsync(companyId, cancellationToken),
            Modo = modo,
            Itens = itens,
        };
    }

    /// <summary>
    /// Consulta qualquer lote cadastrado (não só os em alerta), com filtros por texto, período de
    /// validade, categoria, situação, saldo e origem, ordenado pela validade e paginado.
    /// </summary>
    public async Task<LoteConsultaModel> ConsultarAsync(string companyId, LoteConsultaFiltro filtro, CancellationToken cancellationToken = default)
    {
        var de = ParseFiltroData(filtro.De, "inicial");
        var ate = ParseFiltroData(filtro.Ate, "final");
        if (de is not null && ate is not null && string.CompareOrdinal(ate, de) < 0)
        {
            throw new InvalidOperationException("Período inválido: a validade final é anterior à inicial.");
        }

        var busca = filtro.Busca?.Trim().ToLowerInvariant();
        var categoriaId = filtro.CategoriaId?.Trim();
        var faixa = filtro.Faixa?.Trim().ToLowerInvariant();
        var origem = filtro.Origem?.Trim().ToLowerInvariant();

        var (todos, modo) = await MontarTodosAsync(companyId, cancellationToken);
        IEnumerable<LoteAlertaModel> query = todos;

        if (!string.IsNullOrEmpty(busca))
        {
            query = query.Where(item =>
                item.ProductName.ToLowerInvariant().Contains(busca)
                || item.ProductCode.ToLowerInvariant().Contains(busca)
                || item.NumeroLote.ToLowerInvariant().Contains(busca));
        }

        if (de is not null) query = query.Where(item => string.CompareOrdinal(item.DataValidade, de) >= 0);
        if (ate is not null) query = query.Where(item => string.CompareOrdinal(item.DataValidade, ate) <= 0);

        if (!string.IsNullOrEmpty(categoriaId))
        {
            // Departamento inclui as subcategorias dele.
            query = query.Where(item => item.CategoriaId == categoriaId || item.CategoriaPaiId == categoriaId);
        }

        if (!string.IsNullOrEmpty(faixa) && faixa != "todas") query = query.Where(item => item.Faixa == faixa);
        if (filtro.ComSaldo == true) query = query.Where(item => item.QtdEstimada > 0);
        if (!string.IsNullOrEmpty(origem) && origem != "todas") query = query.Where(item => item.Origem.ToLowerInvariant() == origem);

        var filtrados = query
            .OrderBy(item => item.DataValidade, StringComparer.Ordinal)
            .ThenBy(item => item.ProductName)
            .ToList();

        var tamanho = Math.Clamp(filtro.TamanhoPagina, 5, 200);
        var totalPaginas = Math.Max(1, (int)Math.Ceiling(filtrados.Count / (double)tamanho));
        var pagina = Math.Clamp(filtro.Pagina, 1, totalPaginas);

        return new LoteConsultaModel
        {
            Total = filtrados.Count,
            Pagina = pagina,
            TamanhoPagina = tamanho,
            ValorEmRisco = filtrados.Sum(item => item.ValorEmRisco),
            Modo = modo,
            Itens = filtrados.Skip((pagina - 1) * tamanho).Take(tamanho).ToList(),
        };
    }

    private static string? ParseFiltroData(string? value, string nome)
    {
        if (string.IsNullOrWhiteSpace(value)) return null;

        if (!DateTime.TryParseExact(value.Trim(), "yyyy-MM-dd", CultureInfo.InvariantCulture, DateTimeStyles.None, out var data))
        {
            throw new InvalidOperationException($"Data {nome} inválida no filtro de validade.");
        }

        return data.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture);
    }

    public async Task<List<LoteModel>> ListarPorProdutoAsync(string companyId, string produtoId, CancellationToken cancellationToken = default)
    {
        var linhas = await loteAB.ListarLinhasAsync(companyId, produtoId, cancellationToken);
        var modo = await loteAB.ObterModoAsync(companyId, cancellationToken);
        var saldos = LoteEstimador.Saldos(linhas, modo == LoteAB.ModoAtivo);
        var hoje = HorusDateTime.NowDateTime.Date;

        return linhas
            .Select(linha => new LoteModel
            {
                Id = linha.Id,
                ProdutoId = linha.ProdutoId,
                NumeroLote = linha.NumeroLote,
                DataValidade = linha.DataValidade.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture),
                DiasParaVencer = (linha.DataValidade.Date - hoje).Days,
                QtdInicial = linha.QtdInicial,
                QtdEstimada = saldos.GetValueOrDefault(linha.Id),
                Origem = linha.Origem,
                ValidadePadrao = linha.ValidadePadrao,
                CriadoEm = HorusDateTime.FormatIso(linha.CriadoEm),
            })
            .OrderBy(lote => lote.DataValidade)
            .ToList();
    }

    public async Task RegistrarManualAsync(AuthenticatedUser currentUser, RegistrarLoteRequest request, CancellationToken cancellationToken = default)
    {
        if (string.IsNullOrWhiteSpace(request.ProdutoId))
        {
            throw new InvalidOperationException("Informe o produto do lote.");
        }

        if (!DateTime.TryParseExact(request.DataValidade, "yyyy-MM-dd", CultureInfo.InvariantCulture, DateTimeStyles.None, out var validade))
        {
            throw new InvalidOperationException("Data de validade inválida.");
        }

        if (request.Quantidade <= 0)
        {
            throw new InvalidOperationException("A quantidade do lote deve ser maior que zero.");
        }

        await loteAB.RegistrarEntradaAsync(
            currentUser.CompanyId,
            request.ProdutoId.Trim(),
            validade,
            request.Quantidade,
            request.NumeroLote,
            "manual",
            currentUser.Name,
            cancellationToken);
    }

    /// <summary>
    /// Compara o saldo REAL por lote (baixado pela venda) com o ESTIMADO da Fase 1. Serve para decidir
    /// quando passar do modo "sombra" para "ativo": poucas divergências = a baixa por lote está confiável.
    /// </summary>
    public async Task<FefoStatusModel> ObterFefoStatusAsync(string companyId, CancellationToken cancellationToken = default)
    {
        const decimal tolerancia = 0.001m;

        var linhas = await loteAB.ListarLinhasAsync(companyId, null, cancellationToken);
        var modo = await loteAB.ObterModoAsync(companyId, cancellationToken);
        var divergencias = new List<FefoDivergenciaModel>();
        var lotesComSaldo = 0;
        var produtosComDivergencia = 0;
        var produtosSemCobertura = 0;

        foreach (var grupo in linhas.GroupBy(linha => linha.ProdutoId))
        {
            var doProduto = grupo.ToList();
            var estimados = LoteEstimador.Distribuir(doProduto);
            var divergiu = false;

            foreach (var linha in doProduto)
            {
                var estimado = estimados.GetValueOrDefault(linha.Id);
                var real = Math.Max(0m, linha.QtdAtual);
                if (real > 0) lotesComSaldo++;

                if (Math.Abs(real - estimado) <= tolerancia) continue;

                divergiu = true;
                divergencias.Add(new FefoDivergenciaModel
                {
                    ProdutoId = linha.ProdutoId,
                    ProductCode = linha.ProductCode,
                    ProductName = linha.ProductName,
                    LoteId = linha.Id,
                    NumeroLote = linha.NumeroLote,
                    DataValidade = linha.DataValidade.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture),
                    QtdEstimada = estimado,
                    QtdReal = real,
                    Diferenca = real - estimado,
                });
            }

            if (divergiu) produtosComDivergencia++;

            // Estoque do produto que a soma dos saldos reais dos lotes não cobre (entrada sem lote, ajuste, etc.).
            var estoque = Math.Max(0m, doProduto[0].Estoque);
            if (estoque - doProduto.Sum(linha => Math.Max(0m, linha.QtdAtual)) > tolerancia) produtosSemCobertura++;
        }

        return new FefoStatusModel
        {
            Modo = modo,
            LotesComSaldo = lotesComSaldo,
            LotesComDivergencia = divergencias.Count,
            ProdutosComDivergencia = produtosComDivergencia,
            ProdutosComEstoqueSemLote = produtosSemCobertura,
            Itens = divergencias.OrderByDescending(item => Math.Abs(item.Diferenca)).Take(100).ToList(),
        };
    }

    public async Task DefinirModoAsync(string companyId, string modo, CancellationToken cancellationToken = default)
    {
        var normalizado = modo?.Trim().ToLowerInvariant();
        if (!LoteAB.ModoValido(normalizado))
        {
            throw new InvalidOperationException("Modo inválido. Use 'desligado', 'sombra' ou 'ativo'.");
        }

        await loteAB.DefinirModoAsync(companyId, normalizado!, cancellationToken);
    }

    public Task<List<CategoriaValidadeModel>> ListarCategoriasAsync(string companyId, CancellationToken cancellationToken = default)
        => loteAB.ListarCategoriasValidadeAsync(companyId, cancellationToken);

    public async Task<bool> SalvarCategoriaAsync(string companyId, string categoriaId, CategoriaValidadeRequest request, CancellationToken cancellationToken = default)
    {
        if (request.PrazoPadraoDias is < 1 or > 3650)
        {
            throw new InvalidOperationException("O prazo padrão deve ficar entre 1 e 3650 dias.");
        }

        if (request.DiasAlerta is < 0 or > 3650)
        {
            throw new InvalidOperationException("A janela de alerta deve ficar entre 0 e 3650 dias.");
        }

        return await loteAB.SalvarCategoriaValidadeAsync(companyId, categoriaId, request.PrazoPadraoDias, request.DiasAlerta, cancellationToken);
    }
}
