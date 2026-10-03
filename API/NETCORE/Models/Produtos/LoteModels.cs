/**
 * Arquivo: API/NETCORE/Models/Produtos/LoteModels.cs
 * Objetivo: contratos de resposta do controle de validade por lote (alertas, lotes de um produto
 *           e configuração de prazo por categoria).
 * Entradas esperadas: preenchidos por LoteService a partir de ProdutoLotes + Produtos + Categorias.
 */
namespace HORUSPDV_API.Models.Produtos;

public class LoteModel
{
    public string Id { get; set; } = string.Empty;
    public string ProdutoId { get; set; } = string.Empty;
    public string NumeroLote { get; set; } = string.Empty;
    /// <summary>AAAA-MM-DD.</summary>
    public string DataValidade { get; set; } = string.Empty;
    public int DiasParaVencer { get; set; }
    public decimal QtdInicial { get; set; }
    /// <summary>Saldo estimado no consumo FEFO (estoque atual atribuído aos lotes de validade mais distante).</summary>
    public decimal QtdEstimada { get; set; }
    public string Origem { get; set; } = string.Empty;
    /// <summary>true quando a validade foi sugerida pelo prazo padrão da categoria, e não digitada.</summary>
    public bool ValidadePadrao { get; set; }
    public string CriadoEm { get; set; } = string.Empty;
}

public class LoteAlertaModel : LoteModel
{
    public string ProductCode { get; set; } = string.Empty;
    public string ProductName { get; set; } = string.Empty;
    public string CategoriaNome { get; set; } = string.Empty;
    public string CategoriaId { get; set; } = string.Empty;
    public string CategoriaPaiId { get; set; } = string.Empty;
    public int JanelaAlertaDias { get; set; }
    /// <summary>"vencido", "critico", "atencao", "ok" (fora da janela de alerta) ou "esgotado" (sem saldo estimado).</summary>
    public string Faixa { get; set; } = "atencao";
    public decimal CustoUnitario { get; set; }
    public decimal PrecoVenda { get; set; }
    /// <summary>QtdEstimada x custo unitário.</summary>
    public decimal ValorEmRisco { get; set; }
}

public class LoteAlertasResumoModel
{
    public int Vencidos { get; set; }
    public int Criticos { get; set; }
    public int Atencao { get; set; }
    public decimal ValorEmRisco { get; set; }
    /// <summary>Produtos que controlam validade mas ainda não têm nenhum lote com saldo.</summary>
    public int ProdutosSemLote { get; set; }
    public List<LoteAlertaModel> Itens { get; set; } = [];
}

public class LoteConsultaModel
{
    /// <summary>Total de lotes que atendem aos filtros (todas as páginas).</summary>
    public int Total { get; set; }
    public int Pagina { get; set; }
    public int TamanhoPagina { get; set; }
    /// <summary>Valor em risco (saldo estimado x custo) de todos os lotes filtrados.</summary>
    public decimal ValorEmRisco { get; set; }
    public List<LoteAlertaModel> Itens { get; set; } = [];
}

public class CategoriaValidadeModel
{
    public string CategoriaId { get; set; } = string.Empty;
    public string Nome { get; set; } = string.Empty;
    public string? CategoriaPaiId { get; set; }
    public int? PrazoPadraoDias { get; set; }
    public int? DiasAlerta { get; set; }
}
