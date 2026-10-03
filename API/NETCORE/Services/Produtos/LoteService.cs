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

    public async Task<LoteAlertasResumoModel> ListarAlertasAsync(string companyId, CancellationToken cancellationToken = default)
    {
        var linhas = await loteAB.ListarLinhasAsync(companyId, null, cancellationToken);
        var hoje = HorusDateTime.NowDateTime.Date;
        var itens = new List<LoteAlertaModel>();

        foreach (var grupo in linhas.GroupBy(linha => linha.ProdutoId))
        {
            var doProduto = grupo.ToList();
            var saldos = LoteEstimador.Distribuir(doProduto);

            foreach (var linha in doProduto)
            {
                var saldo = saldos.GetValueOrDefault(linha.Id);
                if (saldo <= 0) continue;

                var dias = (linha.DataValidade.Date - hoje).Days;
                var janela = linha.DiasAlertaCategoria
                    ?? (linha.DiasAlertaProduto > 0 ? linha.DiasAlertaProduto : JanelaAlertaPadraoDias);
                if (dias > janela) continue;

                var faixa = dias < 0 ? "vencido" : dias <= Math.Max(2, janela / 3) ? "critico" : "atencao";
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
                    JanelaAlertaDias = janela,
                    Faixa = faixa,
                    CustoUnitario = linha.CustoUnitario,
                    PrecoVenda = linha.PrecoVenda,
                    ValorEmRisco = Math.Round(saldo * linha.CustoUnitario, 2),
                });
            }
        }

        itens = itens.OrderBy(item => item.DiasParaVencer).ThenBy(item => item.ProductName).ToList();

        return new LoteAlertasResumoModel
        {
            Vencidos = itens.Count(item => item.Faixa == "vencido"),
            Criticos = itens.Count(item => item.Faixa == "critico"),
            Atencao = itens.Count(item => item.Faixa == "atencao"),
            ValorEmRisco = itens.Sum(item => item.ValorEmRisco),
            ProdutosSemLote = await loteAB.ContarProdutosSemLoteAsync(companyId, cancellationToken),
            Itens = itens,
        };
    }

    public async Task<List<LoteModel>> ListarPorProdutoAsync(string companyId, string produtoId, CancellationToken cancellationToken = default)
    {
        var linhas = await loteAB.ListarLinhasAsync(companyId, produtoId, cancellationToken);
        var saldos = LoteEstimador.Distribuir(linhas);
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
