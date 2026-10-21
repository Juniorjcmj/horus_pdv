/*
 * Arquivo: Services/CloudEndpointSyncClient.cs
 * Objetivo: envio Gateway → nuvem pelos ENDPOINTS EXISTENTES da API, com o token da loja
 *           (Authorization: Bearer qgw_...) e o operador do evento (X-Operator-Id).
 *
 *   SALE_CREATED  → POST /api/HistoricoVendas
 *   CASH_OPEN     → POST /api/Caixa/abrir
 *   CASH_CLOSE    → POST /api/Caixa/fechar
 *   CASH_MOVEMENT → POST /api/Caixa/movimento
 *   demais tipos (ex.: ORDER_*, só coordenação na LAN) → não vão à nuvem: contam como enviados.
 *
 * Idempotência ponta a ponta: o corpo enviado leva eventId = EventId do evento (e clientSaleId na venda),
 * então reenvio após timeout vira replay na nuvem, nunca duplicidade.
 *
 * Desfecho por status HTTP:
 *   2xx → Success | 401/403 (token revogado/errado) → Transient (o evento fica guardado, não se perde)
 *   408/429/5xx/rede/timeout → Transient | 409 (mesmo EventId com dados diferentes) → Permanent
 *   demais 4xx (regra de negócio recusou) → Permanent
 */
using System.Net;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using HorusGateway.Configuration;
using HorusGateway.Models;
using Microsoft.Extensions.Options;

namespace HorusGateway.Services;

public sealed class CloudEndpointSyncClient : ICloudSyncClient
{
    public const string OperatorHeader = "X-Operator-Id";

    /// <summary>Tipos de evento enviados à nuvem e o endpoint de cada um.</summary>
    public static readonly IReadOnlyDictionary<string, string> EndpointByEventType =
        new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase)
        {
            ["SALE_CREATED"] = "/api/HistoricoVendas",
            ["CASH_OPEN"] = "/api/Caixa/abrir",
            ["CASH_CLOSE"] = "/api/Caixa/fechar",
            ["CASH_MOVEMENT"] = "/api/Caixa/movimento",
        };

    private readonly HttpClient _http;
    private readonly GatewayOptions _options;
    private readonly ILogger<CloudEndpointSyncClient> _logger;
    private readonly HorusGateway.Data.GatewayDatabase? _database;

    public CloudEndpointSyncClient(HttpClient http, IOptions<GatewayOptions> options, ILogger<CloudEndpointSyncClient> logger, HorusGateway.Data.GatewayDatabase? database = null)
    {
        _http = http;
        _options = options.Value;
        _logger = logger;
        _database = database;
    }

    public bool Enabled => _options.UsesCloudEndpoints;

    /// <summary>Evento cujo tipo é enviado à nuvem (e por isso exige operatorId no payload).</summary>
    public static bool IsCloudEvent(string? eventType)
        => !string.IsNullOrWhiteSpace(eventType) && EndpointByEventType.ContainsKey(eventType);

    /// <summary>Lê o operatorId do payload do evento (null se ausente).</summary>
    public static string? ReadOperatorId(string? payloadJson)
    {
        if (string.IsNullOrWhiteSpace(payloadJson)) return null;
        try
        {
            using var doc = JsonDocument.Parse(payloadJson);
            return doc.RootElement.ValueKind == JsonValueKind.Object &&
                   doc.RootElement.TryGetProperty("operatorId", out var op) &&
                   op.ValueKind == JsonValueKind.String &&
                   !string.IsNullOrWhiteSpace(op.GetString())
                ? op.GetString()!.Trim()
                : null;
        }
        catch (JsonException)
        {
            return null;
        }
    }

    public async Task<CloudSyncResult> SendAsync(GatewayEvent ev, CancellationToken cancellationToken = default)
    {
        if (!Enabled)
        {
            return CloudSyncResult.Of(CloudSyncStatus.Transient, "Envio à nuvem não configurado (CloudApiBaseUrl/CloudSyncToken).");
        }

        if (!EndpointByEventType.TryGetValue(ev.EventType, out var path))
        {
            // Coordenação só da LAN (ex.: pedidos): não há destino na nuvem — não deve ficar pendente para sempre.
            return CloudSyncResult.Of(CloudSyncStatus.Success, "Evento local (não enviado à nuvem).");
        }

        JsonObject body;
        try
        {
            body = JsonNode.Parse(string.IsNullOrWhiteSpace(ev.Payload) ? "{}" : ev.Payload) as JsonObject
                   ?? throw new JsonException("payload não é um objeto");
        }
        catch (JsonException ex)
        {
            return CloudSyncResult.Of(CloudSyncStatus.Permanent, $"Payload inválido: {ex.Message}");
        }

        var operatorId = ReadOperatorId(ev.Payload);
        if (operatorId is null)
        {
            return CloudSyncResult.Of(CloudSyncStatus.Permanent, "Evento sem operatorId: a nuvem não sabe em nome de quem registrar.");
        }

        // Mesmo EventId do Gateway na nuvem → reenvio vira replay idempotente.
        body["eventId"] = ev.EventId;
        if (!string.IsNullOrWhiteSpace(ev.ClientPayloadHash) && body["payloadHash"] is null)
        {
            body["payloadHash"] = ev.ClientPayloadHash;
        }
        if (string.Equals(ev.EventType, "SALE_CREATED", StringComparison.OrdinalIgnoreCase))
        {
            if(body["creditAuthorization"] is not null && body["gatewayFiscalAttempt"] is not null && _database is not null)
            {
                // Credit has already committed. Preserve the event snapshot and wait for the original
                // local invoice instead of racing the terminal with a second cloud invoice.
                using var fiscalDb=_database.OpenConnection();using var fiscal=fiscalDb.CreateCommand();
                fiscal.CommandText="SELECT XmlAssinado FROM LocalNfceContingencias WHERE CompanyId=$company AND VendaId=$sale";
                fiscal.Parameters.AddWithValue("$company",ev.CompanyId);fiscal.Parameters.AddWithValue("$sale",body["clientSaleId"]?.GetValue<string>()??ev.EventId);
                if(fiscal.ExecuteScalar() is not string xml)return CloudSyncResult.Of(CloudSyncStatus.Transient,"Venda fiado confirmada; aguardando XML original da contingência local.");
                body["xmlNfceContingencia"]=xml;
            }
            body["reenvioOffline"] = true;
            if (body["clientSaleId"] is null) body["clientSaleId"] = ev.EventId;
            if (body["occurredAt"] is null && !string.IsNullOrWhiteSpace(ev.OccurredAt)) body["occurredAt"] = ev.OccurredAt;
        }

        // Caixa feito offline: a nuvem precisa do id da sessão do cliente e do horário real do fato para
        // fechar/abrir exatamente a sessão certa (ver API HorusCaixaService). Campos já presentes no payload têm prioridade.
        if (string.Equals(ev.EventType, "CASH_OPEN", StringComparison.OrdinalIgnoreCase) &&
            body["openedAt"] is null && !string.IsNullOrWhiteSpace(ev.OccurredAt))
        {
            body["openedAt"] = ev.OccurredAt;
        }
        if (string.Equals(ev.EventType, "CASH_CLOSE", StringComparison.OrdinalIgnoreCase) &&
            body["closedAt"] is null && !string.IsNullOrWhiteSpace(ev.OccurredAt))
        {
            body["closedAt"] = ev.OccurredAt;
        }

        try
        {
            using var request = new HttpRequestMessage(HttpMethod.Post, $"{_options.CloudApiBaseUrl.TrimEnd('/')}{path}")
            {
                Content = new StringContent(body.ToJsonString(), Encoding.UTF8, "application/json")
            };
            request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", _options.CloudSyncToken.Trim());
            request.Headers.TryAddWithoutValidation(OperatorHeader, operatorId);

            using var response = await _http.SendAsync(request, cancellationToken);
            var status = (int)response.StatusCode;
            if (response.IsSuccessStatusCode)
            {
                if(_database is not null && body["creditAuthorization"] is not null)
                {
                    using var result=JsonDocument.Parse(await response.Content.ReadAsStringAsync(cancellationToken));
                    var number=result.RootElement.GetProperty("data").GetProperty("saleNumber").GetString();
                    using var db=_database.OpenConnection();using var update=db.CreateCommand();
                    update.CommandText="UPDATE GatewayEvents SET CloudSaleNumber=$number WHERE CompanyId=$company AND EventId=$event";
                    update.Parameters.AddWithValue("$number",(object?)number??DBNull.Value);update.Parameters.AddWithValue("$company",ev.CompanyId);update.Parameters.AddWithValue("$event",ev.EventId);update.ExecuteNonQuery();
                }
                return CloudSyncResult.Of(CloudSyncStatus.Success);
            }

            var message = await ReadMessageAsync(response, cancellationToken);
            if (response.StatusCode is HttpStatusCode.Unauthorized or HttpStatusCode.Forbidden)
            {
                // Token revogado/errado ou operador inválido: guarda o evento e tenta de novo (não descarta venda).
                return CloudSyncResult.Of(CloudSyncStatus.Transient, $"Nuvem recusou o acesso (HTTP {status}): {message}");
            }
            if (response.StatusCode is HttpStatusCode.RequestTimeout or HttpStatusCode.TooManyRequests || status >= 500)
            {
                return CloudSyncResult.Of(CloudSyncStatus.Transient, $"HTTP {status}: {message}");
            }
            // 409 = mesmo EventId com dados diferentes; demais 4xx = regra de negócio recusou.
            return CloudSyncResult.Of(CloudSyncStatus.Permanent, $"HTTP {status}: {message}");
        }
        catch (TaskCanceledException) when (!cancellationToken.IsCancellationRequested)
        {
            return CloudSyncResult.Of(CloudSyncStatus.Transient, "timeout");
        }
        catch (HttpRequestException ex)
        {
            _logger.LogDebug(ex, "Falha de rede ao enviar evento {EventId} à nuvem.", ev.EventId);
            return CloudSyncResult.Of(CloudSyncStatus.Transient, "rede indisponível");
        }
    }

    private static async Task<string> ReadMessageAsync(HttpResponseMessage response, CancellationToken cancellationToken)
    {
        try
        {
            var text = await response.Content.ReadAsStringAsync(cancellationToken);
            using var doc = JsonDocument.Parse(text);
            return doc.RootElement.TryGetProperty("message", out var m) ? m.GetString() ?? "" : text;
        }
        catch
        {
            return "sem detalhe";
        }
    }
}
