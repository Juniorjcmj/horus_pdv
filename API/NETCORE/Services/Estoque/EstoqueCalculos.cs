/**
 * Arquivo: API/NETCORE/Services/Estoque/EstoqueCalculos.cs
 * Objetivo: formulas puras (sem SQL) dos indicadores de estoque (D5 / spec indicadores-estoque): consumo, desvio, giro, DIO,
 *           cobertura, estoque de seguranca, ponto de pedido, sugestao de compra, ruptura, acuracidade, excesso, ABC e FSN.
 *           O SQL agrega (soma, soma dos quadrados, contagens); estas funcoes aplicam a regra. "Sem giro" e null, nunca infinito.
 */
namespace HORUSPDV_API.Services.Estoque;

/// <summary>Parametros de analise da empresa (valores padrao do spec quando nao configurado).</summary>
public sealed record EstoqueParametros(
    int JanelaConsumoDias = 30,
    decimal NivelServicoZ = 1.65m,
    decimal AbcLimiteA = 80m,
    decimal AbcLimiteB = 95m,
    decimal FsnLimiteFast = 50m,
    int DiasParado = 90,
    int DiasExcesso = 120,
    int CoberturaAlvoDias = 15,
    int LeadTimePadraoDias = 7)
{
    public static EstoqueParametros Padrao { get; } = new();
}

public sealed record AcuraciaResultado(decimal AcuraciaPercentual, decimal ItensExatosPercentual, int Itens);

public static class EstoqueCalculos
{
    public const int DiasAno = 365;

    /// <summary>Consumo medio diario = saidas liquidas (vendas - devolucoes) na janela / N dias.</summary>
    public static decimal ConsumoMedio(decimal saidasLiquidas, int diasJanela)
        => diasJanela <= 0 ? 0m : Math.Max(0m, saidasLiquidas) / diasJanela;

    /// <summary>
    /// Desvio-padrao populacional das N demandas diarias, com dias sem venda contando zero. Recebe so os totais
    /// (soma e soma dos quadrados dos dias COM venda) e N, que e o que o SQL consegue agregar.
    /// </summary>
    public static decimal DesvioPadraoDemanda(decimal somaDemanda, decimal somaQuadrados, int diasJanela)
    {
        if (diasJanela <= 0) return 0m;
        var media = (double)somaDemanda / diasJanela;
        var variancia = (double)somaQuadrados / diasJanela - media * media;
        return variancia <= 0 ? 0m : (decimal)Math.Sqrt(variancia);
    }

    /// <summary>Versao direta sobre a lista das N demandas diarias (usada nos testes e no detalhe do produto).</summary>
    public static decimal DesvioPadraoDemanda(IReadOnlyList<decimal> demandasDiarias)
    {
        if (demandasDiarias.Count == 0) return 0m;
        return DesvioPadraoDemanda(demandasDiarias.Sum(), demandasDiarias.Sum(d => d * d), demandasDiarias.Count);
    }

    /// <summary>Giro do periodo = CMV / estoque medio em valor. Null = "sem historico" (sem fotografias ou estoque medio zero).</summary>
    public static decimal? Giro(decimal cmv, decimal? estoqueMedioValor)
        => estoqueMedioValor is { } m && m > 0 ? cmv / m : null;

    public static decimal? GiroAnualizado(decimal? giroPeriodo, int diasPeriodo)
        => giroPeriodo is { } g && diasPeriodo > 0 ? g * DiasAno / diasPeriodo : null;

    /// <summary>DIO = 365 / giro anualizado. Null quando nao ha giro.</summary>
    public static decimal? Dio(decimal? giroAnualizado)
        => giroAnualizado is { } g && g > 0 ? DiasAno / g : null;

    /// <summary>Cobertura em dias = saldo / consumo. Null = "sem giro" (consumo zero).</summary>
    public static decimal? Cobertura(decimal saldo, decimal consumoMedio)
        => consumoMedio > 0 ? Math.Max(0m, saldo) / consumoMedio : null;

    /// <summary>Estoque de seguranca = Z x desvio x raiz(lead time).</summary>
    public static decimal EstoqueSeguranca(decimal z, decimal desvio, int leadTimeDias)
        => leadTimeDias <= 0 ? 0m : z * desvio * (decimal)Math.Sqrt(leadTimeDias);

    /// <summary>Ponto de pedido = consumo x lead time + seguranca.</summary>
    public static decimal PontoPedido(decimal consumo, int leadTimeDias, decimal seguranca)
        => consumo * leadTimeDias + seguranca;

    /// <summary>Alvo = consumo x (lead time + cobertura alvo) + seguranca.</summary>
    public static decimal EstoqueAlvo(decimal consumo, int leadTimeDias, int coberturaAlvoDias, decimal seguranca)
        => consumo * (leadTimeDias + coberturaAlvoDias) + seguranca;

    /// <summary>
    /// Sugestao de compra = max(0, alvo - saldo - em transito), arredondada PARA CIMA na unidade do produto:
    /// inteiro (unidades) ou 3 casas (kg/fracionado).
    /// </summary>
    public static decimal SugestaoCompra(decimal alvo, decimal saldo, decimal emTransito, bool unidadeFracionada)
    {
        var bruta = alvo - Math.Max(0m, saldo) - Math.Max(0m, emTransito);
        if (bruta <= 0) return 0m;
        return unidadeFracionada ? Math.Ceiling(bruta * 1000m) / 1000m : Math.Ceiling(bruta);
    }

    /// <summary>Lead time efetivo: do produto, senao real do fornecedor, senao prometido do fornecedor, senao padrao (7).</summary>
    public static int LeadTimeEfetivo(int? produto, decimal? fornecedorReal, int? fornecedorPrometido, int padrao = 7)
    {
        if (produto is > 0) return produto.Value;
        if (fornecedorReal is > 0) return (int)Math.Ceiling(fornecedorReal.Value);
        if (fornecedorPrometido is > 0) return fornecedorPrometido.Value;
        return padrao > 0 ? padrao : 7;
    }

    /// <summary>Taxa de ruptura = dias com saldo &lt;= 0 / dias do periodo, em %.</summary>
    public static decimal TaxaRupturaPercentual(int diasEmRuptura, int diasPeriodo)
        => diasPeriodo <= 0 ? 0m : Math.Min(100m, 100m * diasEmRuptura / diasPeriodo);

    /// <summary>Acuracidade = soma(min(contado, sistema)) / soma(sistema) x 100, mais o % de itens com contagem exata.</summary>
    public static AcuraciaResultado? Acuracidade(IReadOnlyCollection<(decimal Sistema, decimal Contado)> contagens)
    {
        if (contagens.Count == 0) return null;
        var somaSistema = contagens.Sum(c => Math.Max(0m, c.Sistema));
        var somaMin = contagens.Sum(c => Math.Min(Math.Max(0m, c.Contado), Math.Max(0m, c.Sistema)));
        var exatos = contagens.Count(c => c.Sistema == c.Contado);
        var acuracia = somaSistema > 0 ? 100m * somaMin / somaSistema : (exatos == contagens.Count ? 100m : 0m);
        return new AcuraciaResultado(acuracia, 100m * exatos / contagens.Count, contagens.Count);
    }

    /// <summary>Valor em estoque de um item: saldo x custo, com saldo negativo contando 0.</summary>
    public static decimal ValorEmEstoque(decimal saldo, decimal custo) => Math.Max(0m, saldo) * custo;

    /// <summary>Valor do excesso = (saldo - consumo x limite) x custo quando a cobertura passa do limite; senao 0.</summary>
    public static decimal ValorExcesso(decimal saldo, decimal consumoMedio, decimal custo, int diasExcesso)
    {
        if (consumoMedio <= 0 || saldo <= 0) return 0m;
        var cobertura = saldo / consumoMedio;
        return cobertura > diasExcesso ? (saldo - consumoMedio * diasExcesso) * custo : 0m;
    }

    /// <summary>
    /// Classifica A/B/C pelo valor acumulado (mesma regra da curva ABC dos relatorios): o item e A enquanto a participacao
    /// acumulada, contando ele, nao passa do limite A; o primeiro item e sempre A; itens sem valor sao C.
    /// A lista de entrada nao precisa estar ordenada.
    /// </summary>
    public static IReadOnlyDictionary<string, char> ClassificarAbc(IEnumerable<(string Id, decimal Valor)> itens, decimal limiteA = 80m, decimal limiteB = 95m)
    {
        var lista = itens.OrderByDescending(i => i.Valor).ThenBy(i => i.Id, StringComparer.Ordinal).ToList();
        var total = lista.Where(i => i.Valor > 0).Sum(i => i.Valor);
        var resultado = new Dictionary<string, char>(StringComparer.Ordinal);
        decimal acumulado = 0m;
        var primeiro = true;
        foreach (var (id, valor) in lista)
        {
            if (valor <= 0 || total <= 0) { resultado[id] = 'C'; continue; }
            acumulado += valor;
            var pct = 100m * acumulado / total;
            resultado[id] = primeiro || pct <= limiteA + 0.0001m ? 'A' : pct <= limiteB + 0.0001m ? 'B' : 'C';
            primeiro = false;
        }
        return resultado;
    }

    /// <summary>FSN: N sem saida no periodo; F com saida em pelo menos <paramref name="limiteFastPercentual"/>% das semanas; S nos demais.</summary>
    public static char ClassificarFsn(int semanasComSaida, int semanasNoPeriodo, decimal limiteFastPercentual = 50m)
    {
        if (semanasComSaida <= 0 || semanasNoPeriodo <= 0) return 'N';
        return 100m * semanasComSaida / semanasNoPeriodo >= limiteFastPercentual ? 'F' : 'S';
    }

    /// <summary>Variacao percentual entre o periodo e o anterior (null quando o anterior e zero).</summary>
    public static decimal? VariacaoPercentual(decimal atual, decimal anterior)
        => anterior == 0m ? null : 100m * (atual - anterior) / Math.Abs(anterior);
}
