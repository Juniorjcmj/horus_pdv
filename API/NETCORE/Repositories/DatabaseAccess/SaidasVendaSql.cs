/**
 * Arquivo: API/NETCORE/Repositories/DatabaseAccess/SaidasVendaSql.cs
 * Objetivo: fonte única das saídas por venda usada pelos indicadores de estoque (consumo, giro, CMV, última venda, FSN,
 *           curva ABC por CMV, demanda do produto).
 *
 * As movimentações "saida_venda"/"devolucao" só existem a partir da publicação da migração 57 (corte = primeira
 * saida_venda da empresa). Antes do corte a demanda vem das vendas do caixa (VendaItens de vendas não canceladas nem
 * corrigidas), que têm todo o histórico da loja. Depois do corte vem só das movimentações (que já descontam
 * cancelamentos e correções). Assim não há contagem em dobro e a loja não "nasce" sem histórico.
 */
namespace HORUSPDV_API.Repositories.DatabaseAccess;

public static class SaidasVendaSql
{
    /// <summary>
    /// Tabela derivada (ProdutoId, Em DATETIME2 UTC, Qtd, Cmv) das saídas líquidas por venda entre os parâmetros
    /// <paramref name="ini"/> e <paramref name="fim"/> (nomes de parâmetros SQL UTC; null = sem limite). Exige @C e, se
    /// <paramref name="produto"/> for informado, filtra por esse parâmetro (ex.: "@P"). Qtd é negativa nas devoluções.
    /// </summary>
    public static string Derivada(string? ini, string? fim, string? produto = null)
    {
        var limM = (ini is null ? "" : $" AND m.CriadoEm >= {ini}") + (fim is null ? "" : $" AND m.CriadoEm < {fim}")
                   + (produto is null ? "" : $" AND m.ProdutoId = {produto}");
        var limV = (ini is null ? "" : $" AND v.SaleDate >= {ini}") + (fim is null ? "" : $" AND v.SaleDate < {fim}")
                   + (produto is null ? "" : $" AND pr.Id = {produto}");
        return $"""
            (
                SELECT m.ProdutoId,
                       m.CriadoEm AS Em,
                       CASE WHEN m.Tipo = 'saida_venda' THEN m.Quantidade ELSE -m.Quantidade END AS Qtd,
                       CASE WHEN m.Tipo = 'saida_venda' THEN m.Quantidade ELSE -m.Quantidade END
                           * ISNULL(m.CustoUnitario, pr.ProductUnitPrice) AS Cmv
                FROM EstoqueMovimentacoes m
                INNER JOIN Produtos pr ON pr.Id = m.ProdutoId
                WHERE m.CompanyId = @C AND m.Tipo IN ('saida_venda', 'devolucao'){limM}
                UNION ALL
                SELECT pr.Id AS ProdutoId,
                       CAST(SWITCHOFFSET(v.SaleDate, '+00:00') AS DATETIME2) AS Em,
                       vi.Quantity AS Qtd,
                       vi.Quantity * pr.ProductUnitPrice AS Cmv
                FROM VendaItens vi
                INNER JOIN Vendas v ON v.Id = vi.VendaId
                INNER JOIN Produtos pr ON pr.CompanyId = v.CompanyId AND pr.ProductCode = vi.ProductCode
                WHERE v.CompanyId = @C
                  AND ISNULL(v.Status, 'finalizada') NOT IN ('cancelada', 'corrigida')
                  AND v.SaleDate < (SELECT ISNULL(MIN(c.CriadoEm), CAST('9999-12-31' AS DATETIME2))
                                    FROM EstoqueMovimentacoes c
                                    WHERE c.CompanyId = @C AND c.Tipo = 'saida_venda'){limV}
            )
            """;
    }
}
