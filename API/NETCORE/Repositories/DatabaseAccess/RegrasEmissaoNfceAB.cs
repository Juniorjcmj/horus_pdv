/**
 * Arquivo: API/NETCORE/Repositories/DatabaseAccess/RegrasEmissaoNfceAB.cs
 * Objetivo: gerencia a persistência das regras de emissão de NFC-e por forma de pagamento e intervalo de vendas.
 *           Permite configurar quais formas geram NFC-e e a frequência (ex.: a cada 30 vendas emitir 1 NFC-e).
 * Isolamento: multi-tenant por CompanyId.
 */
using HORUSPDV_API.Repositories;
using Microsoft.Data.SqlClient;

namespace HORUSPDV_API.Repositories.DatabaseAccess;

public sealed class RegrasEmissaoNfceDto
{
    public string CompanyId { get; set; } = string.Empty;
    public bool Habilitado { get; set; } = true;
    public string FormasPagamentoHabilitadas { get; set; } = "dinheiro,credito,debito,pix,fiado";
    public int IntervaloNotas { get; set; } = 1;
    public bool EmitirSempreComCpf { get; set; } = true;
    public int ContadorVendas { get; set; } = 0;
    public DateTime UpdatedAt { get; set; } = DateTime.UtcNow;
}

public class RegrasEmissaoNfceAB(Connection connection)
{
    public async Task<RegrasEmissaoNfceDto> GetByCompanyAsync(string companyId, CancellationToken cancellationToken = default)
    {
        const string sql = @"
            SELECT CompanyId, Habilitado, FormasPagamentoHabilitadas, IntervaloNotas, EmitirSempreComCpf, ContadorVendas, UpdatedAt
            FROM RegrasEmissaoNfce
            WHERE CompanyId = @CompanyId;";

        await using var conn = await connection.OpenConnectionAsync(cancellationToken);
        await using var cmd = new SqlCommand(sql, conn);
        cmd.Parameters.AddWithValue("@CompanyId", companyId);

        await using var reader = await cmd.ExecuteReaderAsync(cancellationToken);
        if (!await reader.ReadAsync(cancellationToken))
        {
            return new RegrasEmissaoNfceDto
            {
                CompanyId = companyId,
                Habilitado = true,
                FormasPagamentoHabilitadas = "dinheiro,credito,debito,pix,fiado",
                IntervaloNotas = 1,
                EmitirSempreComCpf = true,
                ContadorVendas = 0,
                UpdatedAt = DateTime.UtcNow
            };
        }

        return new RegrasEmissaoNfceDto
        {
            CompanyId = reader.GetString(0),
            Habilitado = reader.GetBoolean(1),
            FormasPagamentoHabilitadas = reader.GetString(2),
            IntervaloNotas = reader.GetInt32(3),
            EmitirSempreComCpf = reader.GetBoolean(4),
            ContadorVendas = reader.GetInt32(5),
            UpdatedAt = reader.GetDateTime(6)
        };
    }

    public async Task<RegrasEmissaoNfceDto> UpsertAsync(
        string companyId,
        bool habilitado,
        string formasPagamentoHabilitadas,
        int intervaloNotas,
        bool emitirSempreComCpf,
        string? updatedBy,
        CancellationToken cancellationToken = default)
    {
        if (intervaloNotas < 1) intervaloNotas = 1;
        // Lista vazia é válida: nenhuma forma "sempre emite" e todas seguem o intervalo. Só o valor nulo
        // (campo ausente) volta ao padrão.
        formasPagamentoHabilitadas = formasPagamentoHabilitadas is null
            ? "dinheiro,credito,debito,pix,fiado"
            : formasPagamentoHabilitadas.Trim().ToLowerInvariant();

        const string sql = @"
            MERGE RegrasEmissaoNfce AS target
            USING (SELECT @CompanyId AS CompanyId) AS source
            ON target.CompanyId = source.CompanyId
            WHEN MATCHED THEN
                UPDATE SET Habilitado = @Habilitado,
                           FormasPagamentoHabilitadas = @FormasPagamentoHabilitadas,
                           IntervaloNotas = @IntervaloNotas,
                           EmitirSempreComCpf = @EmitirSempreComCpf,
                           UpdatedAt = SYSUTCDATETIME(),
                           UpdatedBy = @UpdatedBy
            WHEN NOT MATCHED THEN
                INSERT (CompanyId, Habilitado, FormasPagamentoHabilitadas, IntervaloNotas, EmitirSempreComCpf, ContadorVendas, UpdatedAt, UpdatedBy)
                VALUES (@CompanyId, @Habilitado, @FormasPagamentoHabilitadas, @IntervaloNotas, @EmitirSempreComCpf, 0, SYSUTCDATETIME(), @UpdatedBy);";

        await using (var conn = await connection.OpenConnectionAsync(cancellationToken))
        await using (var cmd = new SqlCommand(sql, conn))
        {
            cmd.Parameters.AddWithValue("@CompanyId", companyId);
            cmd.Parameters.AddWithValue("@Habilitado", habilitado);
            cmd.Parameters.AddWithValue("@FormasPagamentoHabilitadas", formasPagamentoHabilitadas);
            cmd.Parameters.AddWithValue("@IntervaloNotas", intervaloNotas);
            cmd.Parameters.AddWithValue("@EmitirSempreComCpf", emitirSempreComCpf);
            cmd.Parameters.AddWithValue("@UpdatedBy", (object?)updatedBy ?? DBNull.Value);
            await cmd.ExecuteNonQueryAsync(cancellationToken);
        }

        return await GetByCompanyAsync(companyId, cancellationToken);
    }

    public async Task ResetContadorAsync(string companyId, CancellationToken cancellationToken = default)
    {
        const string sql = @"
            UPDATE RegrasEmissaoNfce
            SET ContadorVendas = 0, UpdatedAt = SYSUTCDATETIME()
            WHERE CompanyId = @CompanyId;";

        await using var conn = await connection.OpenConnectionAsync(cancellationToken);
        await using var cmd = new SqlCommand(sql, conn);
        cmd.Parameters.AddWithValue("@CompanyId", companyId);
        await cmd.ExecuteNonQueryAsync(cancellationToken);
    }

    /// <summary>
    /// Avalia atomicamente se a venda atual deve emitir NFC-e. Regras, em ordem:
    ///  1. controle desativado: emite todas;
    ///  2. cliente pediu CPF/CNPJ na nota (e a opção está ligada): emite;
    ///  3. alguma forma de pagamento da venda está entre as "SEMPRE emitir" (ex.: PIX): emite, sem entrar
    ///     na contagem do intervalo;
    ///  4. demais vendas (qualquer outra forma: dinheiro, cartão, fiado...): 1 NFC-e a cada N vendas, com
    ///     contador único para todas elas (ex.: 30 = a 30ª venda dessas formas emite e a contagem reinicia).
    /// Intervalo 1 emite todas.
    /// </summary>
    public async Task<bool> AvaliarEmissaoEIncrementarAsync(
        string companyId,
        string? primaryPaymentType,
        IEnumerable<string>? paymentTypes,
        string? customerCpf,
        CancellationToken cancellationToken = default)
    {
        var config = await GetByCompanyAsync(companyId, cancellationToken);

        // 1. Se o controle de regras estiver desativado, emite todas normalmente
        if (!config.Habilitado)
        {
            return true;
        }

        // 2. Se o cliente informou CPF/CNPJ na nota e a regra exige emissão, sempre emite
        if (config.EmitirSempreComCpf && !string.IsNullOrWhiteSpace(customerCpf))
        {
            var digitsCount = customerCpf.Count(char.IsDigit);
            if (digitsCount is 11 or 14)
            {
                return true;
            }
        }

        // 3. Formas marcadas como "sempre emitir" (ex.: PIX): se a venda tem alguma delas, emite sempre,
        //    sem entrar na contagem do intervalo.
        var formasConfiguradas = (config.FormasPagamentoHabilitadas ?? "")
            .Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
            .Select(NormalizarFormaPagamento)
            .ToHashSet(StringComparer.OrdinalIgnoreCase);

        var formasVenda = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        if (!string.IsNullOrWhiteSpace(primaryPaymentType))
        {
            formasVenda.Add(NormalizarFormaPagamento(primaryPaymentType));
        }

        if (paymentTypes != null)
        {
            foreach (var p in paymentTypes)
            {
                if (!string.IsNullOrWhiteSpace(p))
                {
                    formasVenda.Add(NormalizarFormaPagamento(p));
                }
            }
        }

        if (formasVenda.Any(f => formasConfiguradas.Contains(f)))
        {
            return true;
        }

        // 4. Demais formas de pagamento: com intervalo 1 (padrão), emite todas
        if (config.IntervaloNotas <= 1)
        {
            return true;
        }

        // 5. Com intervalo (ex.: a cada 30), incrementa atomicamente o contador único dessas vendas
        const string sqlIncrement = @"
            -- Garante que a linha existe
            IF NOT EXISTS (SELECT 1 FROM RegrasEmissaoNfce WHERE CompanyId = @CompanyId)
            BEGIN
                INSERT INTO RegrasEmissaoNfce (CompanyId, Habilitado, FormasPagamentoHabilitadas, IntervaloNotas, EmitirSempreComCpf, ContadorVendas)
                VALUES (@CompanyId, 1, 'dinheiro,credito,debito,pix,fiado', @IntervaloNotas, 1, 0);
            END

            UPDATE RegrasEmissaoNfce
            SET ContadorVendas = ContadorVendas + 1,
                UpdatedAt = SYSUTCDATETIME()
            OUTPUT INSERTED.ContadorVendas, INSERTED.IntervaloNotas
            WHERE CompanyId = @CompanyId;";

        await using var conn = await connection.OpenConnectionAsync(cancellationToken);
        await using var cmd = new SqlCommand(sqlIncrement, conn);
        cmd.Parameters.AddWithValue("@CompanyId", companyId);
        cmd.Parameters.AddWithValue("@IntervaloNotas", config.IntervaloNotas);

        await using var reader = await cmd.ExecuteReaderAsync(cancellationToken);
        if (await reader.ReadAsync(cancellationToken))
        {
            var contador = reader.GetInt32(0);
            var intervalo = reader.GetInt32(1);

            if (contador >= intervalo)
            {
                // Atingiu o intervalo (ex.: 30ª nota). Reseta o contador e autoriza a emissão!
                await reader.CloseAsync();
                await ResetContadorAsync(companyId, cancellationToken);
                return true;
            }

            // Ainda está acumulando até o intervalo (ex.: 1ª até 29ª nota) -> não emite
            return false;
        }

        return true;
    }

    private static string NormalizarFormaPagamento(string paymentType)
    {
        var raw = paymentType.Trim().ToLowerInvariant();
        if (raw.Contains("dinheiro") || raw == "01") return "dinheiro";
        if (raw.Contains("credito") || raw.Contains("crédito") || raw == "03") return "credito";
        if (raw.Contains("debito") || raw.Contains("débito") || raw == "04") return "debito";
        if (raw.Contains("pix") || raw == "17") return "pix";
        if (raw.Contains("fiado") || raw.Contains("prazo") || raw == "05") return "fiado";
        return raw;
    }
}
