/**
 * Arquivo: API/NETCORE/Repositories/DatabaseAccess/EstoqueConfigAnaliseAB.cs
 * Objetivo: configurações de análise de estoque por empresa (D4). Ausência de linha = padrões do spec.
 */
using HORUSPDV_API.Services.Estoque;
using Microsoft.Data.SqlClient;

namespace HORUSPDV_API.Repositories.DatabaseAccess;

public class EstoqueConfigAnaliseAB(Connection connection)
{
    public async Task<EstoqueParametros> ObterAsync(string companyId, CancellationToken ct = default)
    {
        await using var db = await connection.OpenConnectionAsync(ct);
        await using var cmd = new SqlCommand(
            """
            SELECT JanelaConsumoDias, NivelServicoZ, AbcLimiteA, AbcLimiteB, FsnLimiteFast, DiasParado, DiasExcesso,
                   CoberturaAlvoDias, LeadTimePadraoDias, AbcBase, ContagemDiasA, ContagemDiasB, ContagemDiasC,
                   ContagemItensDia, MetasJson
            FROM EstoqueConfigAnalise WHERE CompanyId = @C;
            """, db);
        cmd.Parameters.AddWithValue("@C", companyId);
        await using var r = await cmd.ExecuteReaderAsync(ct);
        if (!await r.ReadAsync(ct)) return EstoqueParametros.Padrao;
        return new EstoqueParametros(
            r.GetInt32(0), r.GetDecimal(1), r.GetDecimal(2), r.GetDecimal(3), r.GetDecimal(4), r.GetInt32(5), r.GetInt32(6),
            r.GetInt32(7), r.GetInt32(8), r.GetString(9), r.GetInt32(10), r.GetInt32(11), r.GetInt32(12), r.GetInt32(13),
            r.IsDBNull(14) ? null : r.GetString(14));
    }

    /// <summary>Valida os limites; devolve a mensagem do primeiro erro ou null.</summary>
    public static string? Validar(EstoqueParametros p)
    {
        if (p.JanelaConsumoDias is < 7 or > 365) return "A janela de consumo deve ficar entre 7 e 365 dias.";
        if (p.NivelServicoZ is < 0.5m or > 4m) return "O nível de serviço (Z) deve ficar entre 0,5 e 4.";
        if (p.AbcLimiteA is <= 0 or >= 100 || p.AbcLimiteB <= p.AbcLimiteA || p.AbcLimiteB > 100) return "Os limites da curva ABC devem ser crescentes (ex.: 80 e 95).";
        if (p.FsnLimiteFast is <= 0 or > 100) return "O limite FSN deve ficar entre 1 e 100%.";
        if (p.DiasParado is < 1 or > 1095) return "Os dias de parado devem ficar entre 1 e 1095.";
        if (p.DiasExcesso is < 1 or > 1095) return "Os dias de excesso devem ficar entre 1 e 1095.";
        if (p.CoberturaAlvoDias is < 1 or > 365) return "A cobertura alvo deve ficar entre 1 e 365 dias.";
        if (p.LeadTimePadraoDias is < 1 or > 180) return "O lead time padrão deve ficar entre 1 e 180 dias.";
        if (p.AbcBase is not ("faturamento" or "cmv")) return "A base da curva ABC deve ser faturamento ou cmv.";
        if (p.ContagemDiasA < 1 || p.ContagemDiasB < 1 || p.ContagemDiasC < 1) return "A periodicidade de contagem deve ser de pelo menos 1 dia.";
        if (p.ContagemItensDia is < 1 or > 500) return "Itens por dia de contagem deve ficar entre 1 e 500.";
        return null;
    }

    public async Task SalvarAsync(string companyId, EstoqueParametros p, string usuarioNome, CancellationToken ct = default)
    {
        await using var db = await connection.OpenConnectionAsync(ct);
        await using var cmd = new SqlCommand(
            """
            MERGE EstoqueConfigAnalise AS t USING (SELECT @C AS CompanyId) AS s ON t.CompanyId = s.CompanyId
            WHEN MATCHED THEN UPDATE SET JanelaConsumoDias=@Jan, NivelServicoZ=@Z, AbcLimiteA=@A, AbcLimiteB=@B, AbcBase=@Base,
                 FsnLimiteFast=@Fsn, DiasParado=@Par, DiasExcesso=@Exc, CoberturaAlvoDias=@Cob, LeadTimePadraoDias=@Lt,
                 ContagemDiasA=@CA, ContagemDiasB=@CB, ContagemDiasC=@CC, ContagemItensDia=@CI, MetasJson=@Metas,
                 AtualizadoPor=@Por, AtualizadoEm=SYSUTCDATETIME()
            WHEN NOT MATCHED THEN INSERT (CompanyId, JanelaConsumoDias, NivelServicoZ, AbcLimiteA, AbcLimiteB, AbcBase, FsnLimiteFast,
                 DiasParado, DiasExcesso, CoberturaAlvoDias, LeadTimePadraoDias, ContagemDiasA, ContagemDiasB, ContagemDiasC,
                 ContagemItensDia, MetasJson, AtualizadoPor)
                 VALUES (@C, @Jan, @Z, @A, @B, @Base, @Fsn, @Par, @Exc, @Cob, @Lt, @CA, @CB, @CC, @CI, @Metas, @Por);
            """, db);
        cmd.Parameters.AddWithValue("@C", companyId);
        cmd.Parameters.AddWithValue("@Jan", p.JanelaConsumoDias);
        cmd.Parameters.AddWithValue("@Z", p.NivelServicoZ);
        cmd.Parameters.AddWithValue("@A", p.AbcLimiteA);
        cmd.Parameters.AddWithValue("@B", p.AbcLimiteB);
        cmd.Parameters.AddWithValue("@Base", p.AbcBase);
        cmd.Parameters.AddWithValue("@Fsn", p.FsnLimiteFast);
        cmd.Parameters.AddWithValue("@Par", p.DiasParado);
        cmd.Parameters.AddWithValue("@Exc", p.DiasExcesso);
        cmd.Parameters.AddWithValue("@Cob", p.CoberturaAlvoDias);
        cmd.Parameters.AddWithValue("@Lt", p.LeadTimePadraoDias);
        cmd.Parameters.AddWithValue("@CA", p.ContagemDiasA);
        cmd.Parameters.AddWithValue("@CB", p.ContagemDiasB);
        cmd.Parameters.AddWithValue("@CC", p.ContagemDiasC);
        cmd.Parameters.AddWithValue("@CI", p.ContagemItensDia);
        cmd.Parameters.AddWithValue("@Metas", (object?)p.MetasJson ?? DBNull.Value);
        cmd.Parameters.AddWithValue("@Por", usuarioNome);
        await cmd.ExecuteNonQueryAsync(ct);
    }

    /// <summary>Volta aos padrões (apaga a linha da empresa).</summary>
    public async Task RestaurarPadraoAsync(string companyId, CancellationToken ct = default)
    {
        await using var db = await connection.OpenConnectionAsync(ct);
        await using var cmd = new SqlCommand("DELETE FROM EstoqueConfigAnalise WHERE CompanyId = @C;", db);
        cmd.Parameters.AddWithValue("@C", companyId);
        await cmd.ExecuteNonQueryAsync(ct);
    }
}
