/**
 * Arquivo: API/NETCORE/Models/Requests/LoteRequests.cs
 * Objetivo: contratos de entrada do controle de validade por lote.
 * Entradas esperadas: recebe dados serializados do frontend (registro manual de lote e prazos por categoria).
 */
namespace HORUSPDV_API.Models.Requests;

public class RegistrarLoteRequest
{
    public string ProdutoId { get; set; } = string.Empty;
    /// <summary>AAAA-MM-DD (data impressa na embalagem).</summary>
    public string DataValidade { get; set; } = string.Empty;
    /// <summary>Quantidade do lote. Não altera o estoque: a entrada de estoque é feita pela nota/compra.</summary>
    public decimal Quantidade { get; set; }
    public string? NumeroLote { get; set; }
}

/// <summary>Filtros da consulta de lotes (query string de GET api/Produto/lotes/consulta).</summary>
public class LoteConsultaFiltro
{
    /// <summary>Texto livre: nome do produto, código do produto ou número do lote.</summary>
    public string? Busca { get; set; }
    /// <summary>Validade a partir de (AAAA-MM-DD, inclusive).</summary>
    public string? De { get; set; }
    /// <summary>Validade até (AAAA-MM-DD, inclusive).</summary>
    public string? Ate { get; set; }
    /// <summary>Departamento ou subcategoria (um departamento inclui suas subcategorias).</summary>
    public string? CategoriaId { get; set; }
    /// <summary>"vencido", "critico", "atencao", "ok" ou "esgotado". Vazio/"todas" = qualquer.</summary>
    public string? Faixa { get; set; }
    /// <summary>true = só lotes com saldo estimado maior que zero.</summary>
    public bool? ComSaldo { get; set; }
    /// <summary>"nfe", "compra", "ajuste", "cadastro", "manual" ou "inicial".</summary>
    public string? Origem { get; set; }
    public int Pagina { get; set; } = 1;
    public int TamanhoPagina { get; set; } = 25;
}

public class FefoModoRequest
{
    /// <summary>"desligado", "sombra" ou "ativo".</summary>
    public string Modo { get; set; } = string.Empty;
}

public class CategoriaValidadeRequest
{
    /// <summary>Dias de validade sugeridos na entrada (null = sem prazo padrão).</summary>
    public int? PrazoPadraoDias { get; set; }
    /// <summary>Quantos dias antes do vencimento o lote entra em alerta (null = usa o alerta do produto).</summary>
    public int? DiasAlerta { get; set; }
}
