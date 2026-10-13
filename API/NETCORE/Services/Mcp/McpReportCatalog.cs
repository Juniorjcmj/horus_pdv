/**
 * Arquivo: API/NETCORE/Services/Mcp/McpReportCatalog.cs
 * Objetivo: catálogo dos relatórios expostos pelo servidor MCP (id, nome, descrição e filtros aceitos),
 *           devolvido pela ferramenta listar_relatorios e usado para validar gerar_relatorio.
 * Entradas esperadas: os ids são os mesmos de RelatorioAB.GerarAsync — ao criar relatório lá, inclua aqui.
 */
namespace HORUSPDV_API.Services.Mcp;

public sealed record McpReport(string Id, string Nome, string Descricao, string[] Filtros);

public static class McpReportCatalog
{
    private static readonly string[] Periodo = ["dataInicio", "dataFim"];
    private static readonly string[] Vendas = ["dataInicio", "dataFim", "formaPagamento", "categoriaId"];

    public static readonly IReadOnlyList<McpReport> Reports =
    [
        new("vendas-periodo", "Vendas por período",
            "Quantidade de vendas, faturamento e ticket médio por dia, semana ou mês.", [.. Vendas, "agruparPor"]),
        new("historico-vendas", "Histórico de vendas",
            "Cada item vendido: número da venda, data, cliente, forma de pagamento, produto, quantidade e total.", Vendas),
        new("produtos-mais-vendidos", "Produtos mais vendidos",
            "Ranking de produtos por quantidade vendida, com faturamento.", Vendas),
        new("produtos-por-valor", "Produtos por valor",
            "Ranking de produtos por faturamento, com custo (CMV), lucro e margem.", Vendas),
        new("produtos-por-quantidade", "Produtos por quantidade",
            "Ranking de produtos por quantidade vendida, com faturamento, custo, lucro e margem.", Vendas),
        new("produtos-por-lucro", "Produtos por lucro",
            "Ranking de produtos por lucro em reais.", Vendas),
        new("produtos-por-margem", "Produtos por margem",
            "Ranking de produtos por margem percentual (só produtos com receita no período).", Vendas),
        new("lucro-markup-produtos", "Lucro e markup por produto",
            "Compra e venda médias por unidade, lucro bruto, markup, quantidade, custo total e total vendido por " +
            "produto, com linha de totais, resumo e notas sobre custo estimado.", [.. Periodo, "categoriaId", "buscaProduto"]),
        new("vendas-por-horario", "Vendas por horário",
            "Vendas, faturamento e ticket médio por hora do dia.", Vendas),
        new("vendas-por-pagamento", "Vendas por forma de pagamento",
            "Vendas, faturamento, ticket médio e participação (%) de cada forma de pagamento.", Vendas),
        new("clientes-frequentes", "Clientes mais frequentes",
            "Ranking de clientes por número de compras, com gasto total e ticket médio.", [.. Vendas, "horaInicio", "horaFim"]),
        new("estoque-critico", "Estoque crítico",
            "Produtos com estoque até 5 unidades (ou só os sem estoque), com categoria e preço de venda.",
            ["somenteSemEstoque", "categoriaId"]),
        new("compras-fornecedor", "Estoque por fornecedor",
            "Por fornecedor: quantidade de produtos, unidades em estoque e custo do estoque.", []),
        new("movimento-estoque", "Posição de estoque",
            "Estoque atual, custo unitário e valor em estoque de cada produto.", ["categoriaId"]),
        new("margem-por-categoria", "Margem por departamento",
            "Por departamento: quantidade vendida, receita, custo (CMV), margem em reais e em %.", Vendas),
        new("vencimentos", "Vencimentos",
            "Produtos com controle de validade: data de validade, dias restantes, estoque e custo em estoque.",
            ["faixa", "categoriaId"]),
        new("inadimplencia", "Inadimplência (fiado)",
            "Clientes devedores: limite, saldo devedor, última compra e dias sem pagamento.", ["faixa"]),
        new("desempenho-caixa", "Desempenho de caixa",
            "Sessões de caixa: abertura, fechamento, operador e valores de abertura e fechamento.", Periodo),
        new("log-atividades", "Log de atividades",
            "Auditoria: data/hora, usuário, evento, entidade, descrição e IP.", [.. Periodo, "tipoEvento"]),
        new("cancelamentos", "Cancelamentos de venda",
            "Vendas canceladas: valor, cliente, operador, supervisor que autorizou, motivo e itens.", Periodo),
    ];
}
