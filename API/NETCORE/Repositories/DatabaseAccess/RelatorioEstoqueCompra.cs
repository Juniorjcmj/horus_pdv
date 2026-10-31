/**
 * Arquivo: API/NETCORE/Repositories/DatabaseAccess/RelatorioEstoqueCompra.cs
 * Objetivo: expõe na tela web de relatórios os mesmos geradores do EstoqueRelatorioAB (ids reposicao-giro,
 *           curva-abc, estoque-parado e perdas), convertendo os registros em colunas/linhas.
 * Entradas esperadas: filtros da tela (dias, cobertura, startDate, endDate, categoriaId); restrictToUserId != null
 *           significa usuário sem perfil de gerência (sem acesso a valores).
 */
using System.Globalization;
using System.Text.Json;

namespace HORUSPDV_API.Repositories.DatabaseAccess;

public partial class RelatorioAB
{
    private static void ExigirGerencia(string? restrictToUserId)
    {
        if (restrictToUserId is not null)
            throw new InvalidOperationException("Este relatório é restrito a gerente e administrador.");
    }

    private static int GetInt(Dictionary<string, JsonElement> filters, string key, int fallback)
        => int.TryParse(GetString(filters, key), NumberStyles.Integer, CultureInfo.InvariantCulture, out var n) && n > 0 ? n : fallback;

    private static string Qtd(decimal value) => value.ToString("0.###", PtBr);

    private async Task<object> GerarReposicaoGiroAsync(string companyId, Dictionary<string, JsonElement> filters, string? restrictToUserId)
    {
        ExigirGerencia(restrictToUserId);
        var r = await estoqueRelatorioAB.ReposicaoAsync(companyId, GetInt(filters, "dias", 30), GetInt(filters, "cobertura", 15),
            GetString(filters, "categoriaId"), true);
        var rows = r.Itens.Select(i => Row(
            ("fornecedor", i.Fornecedor), ("codigo", i.Codigo), ("produto", i.Nome), ("estoque", Qtd(i.Saldo)),
            ("minimo", Qtd(i.Minimo)), ("maximo", Qtd(i.Maximo)), ("vendido", Qtd(i.VendaPeriodo)),
            ("cobertura", i.CoberturaDias is null ? "-" : Qtd(i.CoberturaDias.Value)), ("sugerido", Qtd(i.Sugerido)),
            ("motivo", i.Motivo), ("custoEstimado", FormatMoney(i.CustoEstimado ?? 0)))).ToList();
        return new
        {
            columns = Columns(("fornecedor", "Fornecedor"), ("codigo", "Código"), ("produto", "Produto"), ("estoque", "Estoque"),
                ("minimo", "Mínimo"), ("maximo", "Máximo"), ("vendido", $"Vendido ({r.Dias} dias)"), ("cobertura", "Cobertura (dias)"),
                ("sugerido", "Sugerido"), ("motivo", "Motivo"), ("custoEstimado", "Custo estimado")),
            rows,
            totalRow = Row(("fornecedor", "Total"), ("custoEstimado", FormatMoney(r.CustoTotal ?? 0))),
        };
    }

    private async Task<object> GerarCurvaAbcAsync(string companyId, Dictionary<string, JsonElement> filters, string? restrictToUserId)
    {
        ExigirGerencia(restrictToUserId);
        var (r, erro) = await estoqueRelatorioAB.CurvaAbcAsync(companyId, GetDate(filters, "startDate")?.DateTime,
            GetDate(filters, "endDate")?.DateTime, GetString(filters, "categoriaId"), 80, 95);
        if (erro is not null) throw new InvalidOperationException(erro);
        var rows = r!.Itens.Select(i => Row(
            ("classe", i.Classe), ("codigo", i.Codigo), ("produto", i.Nome), ("quantidade", Qtd(i.Quantidade)),
            ("faturamento", FormatMoney(i.Faturamento)), ("participacao", $"{i.Participacao.ToString("0.##", PtBr)}%"),
            ("acumulada", $"{i.ParticipacaoAcumulada.ToString("0.##", PtBr)}%"), ("estoque", Qtd(i.Saldo)),
            ("cobertura", i.CoberturaDias is null ? "-" : Qtd(i.CoberturaDias.Value)))).ToList();
        return new
        {
            columns = Columns(("classe", "Classe"), ("codigo", "Código"), ("produto", "Produto"), ("quantidade", "Qtd. vendida"),
                ("faturamento", "Faturamento"), ("participacao", "Participação"), ("acumulada", "Acumulada"),
                ("estoque", "Estoque"), ("cobertura", "Cobertura (dias)")),
            rows,
            totalRow = Row(("classe", "Total"), ("faturamento", FormatMoney(r.FaturamentoTotal))),
        };
    }

    private async Task<object> GerarEstoqueParadoAsync(string companyId, Dictionary<string, JsonElement> filters, string? restrictToUserId)
    {
        ExigirGerencia(restrictToUserId);
        var itens = await estoqueRelatorioAB.ParadoAsync(companyId, GetInt(filters, "dias", 60), true);
        var rows = itens.Select(i => Row(
            ("codigo", i.Codigo), ("produto", i.Nome), ("estoque", Qtd(i.Saldo)),
            ("ultimaVenda", i.UltimaVenda is null ? "nunca vendido" : i.UltimaVenda.Value.ToLocalTime().ToString("dd/MM/yyyy")),
            ("diasSemVenda", i.DiasSemVenda is null ? "-" : i.DiasSemVenda.Value.ToString(PtBr)),
            ("ultimaEntrada", i.UltimaEntrada is null ? "-" : i.UltimaEntrada.Value.ToLocalTime().ToString("dd/MM/yyyy")),
            ("valorParado", FormatMoney(i.ValorParado ?? 0)))).ToList();
        return new
        {
            columns = Columns(("codigo", "Código"), ("produto", "Produto"), ("estoque", "Estoque"), ("ultimaVenda", "Última venda"),
                ("diasSemVenda", "Dias sem venda"), ("ultimaEntrada", "Última entrada"), ("valorParado", "Valor parado (custo)")),
            rows,
            totalRow = Row(("codigo", "Total"), ("valorParado", FormatMoney(itens.Sum(i => i.ValorParado ?? 0)))),
        };
    }

    private async Task<object> GerarPerdasAsync(string companyId, Dictionary<string, JsonElement> filters, string? restrictToUserId)
    {
        ExigirGerencia(restrictToUserId);
        var r = await estoqueRelatorioAB.PerdasAsync(companyId, GetDate(filters, "startDate")?.DateTime,
            GetDate(filters, "endDate")?.DateTime, true);
        var rows = r.Itens.Select(i => Row(
            ("data", i.Data.ToString("dd/MM/yyyy HH:mm")), ("codigo", i.Codigo), ("produto", i.Nome), ("motivo", i.Motivo),
            ("usuario", i.Usuario), ("quantidade", Qtd(i.Quantidade)), ("valor", FormatMoney(i.Valor ?? 0)))).ToList();
        return new
        {
            columns = Columns(("data", "Data"), ("codigo", "Código"), ("produto", "Produto"), ("motivo", "Motivo"),
                ("usuario", "Usuário"), ("quantidade", "Quantidade"), ("valor", "Valor (custo)")),
            rows,
            totalRow = Row(("data", "Total"), ("quantidade", Qtd(r.QuantidadeTotal)), ("valor", FormatMoney(r.ValorTotal ?? 0))),
        };
    }
}
