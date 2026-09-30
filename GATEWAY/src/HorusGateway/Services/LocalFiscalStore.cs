/*
 * Arquivo: Services/LocalFiscalStore.cs
 * Objetivo: gerencia a persistência das notas emitidas em contingência no SQLite local,
 *           a alocação atômica de numeração por série fiscal e a ingestão automática no
 *           Event Bus local (IEventStore) para sincronização posterior com a Cloud.
 */
using System.Text.Json;
using HorusGateway.Data;
using HorusGateway.Models;
using Microsoft.Data.Sqlite;

namespace HorusGateway.Services;

public sealed class LocalFiscalStore
{
    private readonly GatewayDatabase _database;
    private readonly IEventStore _eventStore;
    private readonly IClock _clock;
    private readonly ILogger<LocalFiscalStore> _logger;

    public LocalFiscalStore(
        GatewayDatabase database,
        IEventStore eventStore,
        IClock clock,
        ILogger<LocalFiscalStore> logger)
    {
        _database = database;
        _eventStore = eventStore;
        _clock = clock;
        _logger = logger;
    }

    /// <summary>Aloca atimicamente o próximo número sequencial de NFC-e para a série da empresa.</summary>
    public int AlocarProximoNumero(string companyId, int serie)
    {
        using var connection = _database.OpenConnection();
        using var transaction = connection.BeginTransaction();

        using var ensureCmd = connection.CreateCommand();
        ensureCmd.Transaction = transaction;
        ensureCmd.CommandText = """
            INSERT INTO LocalFiscalSequencias (CompanyId, Serie, ProximoNumero)
            VALUES ($companyId, $serie, 1)
            ON CONFLICT(CompanyId, Serie) DO NOTHING;
            """;
        ensureCmd.Parameters.AddWithValue("$companyId", companyId);
        ensureCmd.Parameters.AddWithValue("$serie", serie);
        ensureCmd.ExecuteNonQuery();

        using var selectCmd = connection.CreateCommand();
        selectCmd.Transaction = transaction;
        selectCmd.CommandText = """
            SELECT ProximoNumero FROM LocalFiscalSequencias
            WHERE CompanyId = $companyId AND Serie = $serie;
            """;
        selectCmd.Parameters.AddWithValue("$companyId", companyId);
        selectCmd.Parameters.AddWithValue("$serie", serie);
        var proximo = Convert.ToInt32(selectCmd.ExecuteScalar());

        using var updateCmd = connection.CreateCommand();
        updateCmd.Transaction = transaction;
        updateCmd.CommandText = """
            UPDATE LocalFiscalSequencias
               SET ProximoNumero = ProximoNumero + 1
             WHERE CompanyId = $companyId AND Serie = $serie;
            """;
        updateCmd.Parameters.AddWithValue("$companyId", companyId);
        updateCmd.Parameters.AddWithValue("$serie", serie);
        updateCmd.ExecuteNonQuery();

        transaction.Commit();
        return proximo;
    }

    /// <summary>
    /// Persiste o documento assinado no SQLite local e despacha um evento de contingência
    /// para a fila durável do Gateway (sincronizada via CloudSyncDispatcher quando a rede voltar).
    /// </summary>
    public async Task PersistirEDespacharAsync(
        string companyId,
        LocalNfceContingenciaResponse resp,
        LocalNfceContingenciaRequest req,
        CancellationToken ct = default)
    {
        var totalVenda = req.Itens.Sum(i => i.ValorTotal) - req.Itens.Sum(i => i.Desconto);
        var agora = _clock.UtcNow.ToString("O");

        // 1. Grava no banco SQLite local (LocalNfceContingencias)
        using (var connection = _database.OpenConnection())
        {
            using var insertCmd = connection.CreateCommand();
            insertCmd.CommandText = """
                INSERT INTO LocalNfceContingencias
                    (Id, CompanyId, VendaId, TerminalId, Serie, NumeroNf, ChaveAcesso,
                     DhContingencia, Justificativa, XmlAssinado, QrCodeUrl, DigestValue,
                     TotalVenda, StatusSync, CreatedAt)
                VALUES
                    ($id, $companyId, $vendaId, $terminalId, $serie, $numeroNf, $chaveAcesso,
                     $dhContingencia, $justificativa, $xmlAssinado, $qrCodeUrl, $digestValue,
                     $totalVenda, 'PENDING', $createdAt)
                ON CONFLICT(Id) DO NOTHING;
                """;
            insertCmd.Parameters.AddWithValue("$id", resp.DocumentoId);
            insertCmd.Parameters.AddWithValue("$companyId", companyId);
            insertCmd.Parameters.AddWithValue("$vendaId", req.VendaId);
            insertCmd.Parameters.AddWithValue("$terminalId", req.TerminalId);
            insertCmd.Parameters.AddWithValue("$serie", resp.Serie);
            insertCmd.Parameters.AddWithValue("$numeroNf", resp.NumeroNf);
            insertCmd.Parameters.AddWithValue("$chaveAcesso", resp.ChaveAcesso);
            insertCmd.Parameters.AddWithValue("$dhContingencia", resp.DhContingencia.ToString("O"));
            insertCmd.Parameters.AddWithValue("$justificativa", resp.DanfeData?.Justificativa ?? string.Empty);
            insertCmd.Parameters.AddWithValue("$xmlAssinado", resp.XmlAssinado);
            insertCmd.Parameters.AddWithValue("$qrCodeUrl", resp.QrCodeUrl);
            insertCmd.Parameters.AddWithValue("$digestValue", resp.DigestValue);
            insertCmd.Parameters.AddWithValue("$totalVenda", (double)totalVenda);
            insertCmd.Parameters.AddWithValue("$createdAt", agora);

            insertCmd.ExecuteNonQuery();
        }

        // 2. Cria e ingere evento idempotente no EventStore local
        var payloadObj = new
        {
            documentoId = resp.DocumentoId,
            companyId,
            vendaId = req.VendaId,
            terminalId = req.TerminalId,
            serie = resp.Serie,
            numeroNf = resp.NumeroNf,
            chaveAcesso = resp.ChaveAcesso,
            dhContingencia = resp.DhContingencia,
            justificativa = resp.DanfeData?.Justificativa,
            xmlAssinado = resp.XmlAssinado,
            qrCodeUrl = resp.QrCodeUrl,
            digestValue = resp.DigestValue,
            totalVenda,
            customerCpf = req.CustomerCpf,
            customerName = req.CustomerName
        };

        var payloadJson = JsonSerializer.Serialize(payloadObj);
        using var jsonDoc = JsonDocument.Parse(payloadJson);

        var ingestReq = new IngestEventRequest
        {
            EventId = $"evt-nfce-cont-{resp.ChaveAcesso}",
            CompanyId = companyId,
            TerminalId = req.TerminalId,
            EventType = "fiscal.nfce.contingencia",
            OccurredAt = resp.DhContingencia.ToString("O"),
            Payload = jsonDoc.RootElement.Clone()
        };

        var resultadoIngest = await _eventStore.AppendAsync(ingestReq, ct);
        _logger.LogInformation(
            "NFC-e contingência {Chave} ingerida no Event Bus local (Outcome: {Outcome}). Sincronização agendada para Cloud.",
            resp.ChaveAcesso, resultadoIngest.Outcome);
    }

    /// <summary>Retorna estatísticas para o status fiscal do Gateway.</summary>
    public (int UltimoNumero, int TotalNotas) ObterEstatisticas(string companyId, int serie)
    {
        using var connection = _database.OpenConnection();

        using var seqCmd = connection.CreateCommand();
        seqCmd.CommandText = """
            SELECT ProximoNumero - 1 FROM LocalFiscalSequencias
            WHERE CompanyId = $companyId AND Serie = $serie;
            """;
        seqCmd.Parameters.AddWithValue("$companyId", companyId);
        seqCmd.Parameters.AddWithValue("$serie", serie);
        var ultimoNumero = Convert.ToInt32(seqCmd.ExecuteScalar() ?? 0);

        using var countCmd = connection.CreateCommand();
        countCmd.CommandText = """
            SELECT COUNT(1) FROM LocalNfceContingencias
            WHERE CompanyId = $companyId;
            """;
        countCmd.Parameters.AddWithValue("$companyId", companyId);
        var total = Convert.ToInt32(countCmd.ExecuteScalar() ?? 0);

        return (ultimoNumero, total);
    }
}
