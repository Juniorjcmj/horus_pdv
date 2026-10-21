/*
 * Arquivo: tests/HorusGateway.Tests/CloudEndpointSyncClientTests.cs
 * Objetivo: envio Gateway → nuvem pelos endpoints existentes (token por loja), com uma nuvem falsa:
 *   endpoint por tipo de evento; Bearer + X-Operator-Id; eventId/clientSaleId no corpo (idempotência);
 *   mapeamento de status (2xx/401/409/400/5xx); ORDER_* não vai à nuvem; sem operatorId → permanente.
 */
using System.Net;
using System.Text;
using System.Text.Json;
using HorusGateway.Configuration;
using HorusGateway.Models;
using HorusGateway.Services;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Options;
using Xunit;

namespace HorusGateway.Tests;

public sealed class CloudEndpointSyncClientTests
{
    [Fact]
    public async Task CommittedCreditWaitsForOriginalFiscalXmlAndPreservesEventSnapshot()
    {
        var path=Path.Combine(Path.GetTempPath(),Guid.NewGuid()+".db");
        var options=Options.Create(new GatewayOptions{CompanyId="empresa-1",DatabasePath=path,CloudApiBaseUrl="https://cloud.test",CloudSyncToken="qgw_test"});
        var database=new HorusGateway.Data.GatewayDatabase(options,NullLogger<HorusGateway.Data.GatewayDatabase>.Instance);database.Initialize();
        try
        {
            var cloud=new FakeCloud(HttpStatusCode.OK,"""{"data":{"saleNumber":"42"}}""");
            var client=new CloudEndpointSyncClient(new HttpClient(cloud),options,NullLogger<CloudEndpointSyncClient>.Instance,database);
            var ev=Event("SALE_CREATED","""{"operatorId":"operator","clientSaleId":"sale","creditAuthorization":{},"gatewayFiscalAttempt":{}}""");
            var original=ev.Payload;
            Assert.Equal(CloudSyncStatus.Transient,(await client.SendAsync(ev)).Status);Assert.Empty(cloud.Calls);
            using(var db=database.OpenConnection())using(var cmd=db.CreateCommand())
            {
                cmd.CommandText="""
                    INSERT INTO LocalNfceContingencias(Id,CompanyId,VendaId,TerminalId,Serie,NumeroNf,ChaveAcesso,DhContingencia,Justificativa,XmlAssinado,QrCodeUrl,DigestValue,TotalVenda,CreatedAt)
                    VALUES('doc','empresa-1','sale','t',900,1,'fixture','2026-10-20T00:00:00Z','fixture','<original/>','','',100,'2026-10-20T00:00:00Z');
                    """;cmd.ExecuteNonQuery();
            }
            Assert.Equal(CloudSyncStatus.Success,(await client.SendAsync(ev)).Status);
            using var body=JsonDocument.Parse(Assert.Single(cloud.Calls).Body);Assert.Equal("<original/>",body.RootElement.GetProperty("xmlNfceContingencia").GetString());Assert.Equal(original,ev.Payload);
        }
        finally{Microsoft.Data.Sqlite.SqliteConnection.ClearAllPools();foreach(var suffix in new[]{"","-wal","-shm"})File.Delete(path+suffix);}
    }
    private sealed class FakeCloud(HttpStatusCode status, string body = """{"success":true,"message":"ok"}""") : HttpMessageHandler
    {
        public List<(HttpRequestMessage Request, string Body)> Calls { get; } = new();

        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            var content = request.Content is null ? "" : await request.Content.ReadAsStringAsync(cancellationToken);
            Calls.Add((request, content));
            return new HttpResponseMessage(status) { Content = new StringContent(body, Encoding.UTF8, "application/json") };
        }
    }

    private static (CloudEndpointSyncClient Client, FakeCloud Cloud) Build(HttpStatusCode status, string? body = null)
    {
        var cloud = body is null ? new FakeCloud(status) : new FakeCloud(status, body);
        var options = Options.Create(new GatewayOptions
        {
            CompanyId = "empresa-1",
            CloudApiBaseUrl = "https://cloud.test",
            CloudSyncToken = "qgw_token-loja"
        });
        return (new CloudEndpointSyncClient(new HttpClient(cloud), options, NullLogger<CloudEndpointSyncClient>.Instance), cloud);
    }

    private static GatewayEvent Event(string type, string payload, string eventId = "ev-1", string? clientHash = null) => new()
    {
        Seq = 1,
        EventId = eventId,
        CompanyId = "empresa-1",
        TerminalId = "PDV-01",
        EventType = type,
        OccurredAt = "2026-10-06T12:00:00Z",
        Payload = payload,
        ClientPayloadHash = clientHash
    };

    [Theory]
    [InlineData("SALE_CREATED", "/api/HistoricoVendas")]
    [InlineData("CASH_OPEN", "/api/Caixa/abrir")]
    [InlineData("CASH_CLOSE", "/api/Caixa/fechar")]
    [InlineData("CASH_MOVEMENT", "/api/Caixa/movimento")]
    public async Task Envia_cada_tipo_para_o_endpoint_certo_com_token_e_operador(string type, string path)
    {
        var (client, cloud) = Build(HttpStatusCode.OK);

        var result = await client.SendAsync(Event(type, """{"operatorId":"usr-1","valor":"10,00"}"""));

        Assert.Equal(CloudSyncStatus.Success, result.Status);
        var (request, _) = Assert.Single(cloud.Calls);
        Assert.Equal(HttpMethod.Post, request.Method);
        Assert.Equal($"https://cloud.test{path}", request.RequestUri!.ToString());
        Assert.Equal("Bearer", request.Headers.Authorization!.Scheme);
        Assert.Equal("qgw_token-loja", request.Headers.Authorization.Parameter);
        Assert.Equal("usr-1", Assert.Single(request.Headers.GetValues(CloudEndpointSyncClient.OperatorHeader)));
    }

    [Fact]
    public async Task Venda_leva_eventId_clientSaleId_e_hash_do_evento_para_idempotencia()
    {
        var (client, cloud) = Build(HttpStatusCode.Created);

        await client.SendAsync(Event("SALE_CREATED", """{"operatorId":"usr-1","totalAmount":"25,90"}""", eventId: "ev-venda-9", clientHash: "hash-9"));

        using var body = JsonDocument.Parse(cloud.Calls[0].Body);
        Assert.Equal("ev-venda-9", body.RootElement.GetProperty("eventId").GetString());
        Assert.Equal("ev-venda-9", body.RootElement.GetProperty("clientSaleId").GetString());
        Assert.Equal("hash-9", body.RootElement.GetProperty("payloadHash").GetString());
        Assert.Equal("2026-10-06T12:00:00Z", body.RootElement.GetProperty("occurredAt").GetString());
        Assert.Equal("25,90", body.RootElement.GetProperty("totalAmount").GetString());
        Assert.True(body.RootElement.GetProperty("reenvioOffline").GetBoolean());
    }

    [Fact]
    public async Task Venda_mantem_clientSaleId_do_pdv_quando_informado()
    {
        var (client, cloud) = Build(HttpStatusCode.Created);

        await client.SendAsync(Event("SALE_CREATED", """{"operatorId":"usr-1","clientSaleId":"cs-do-pdv"}"""));

        using var body = JsonDocument.Parse(cloud.Calls[0].Body);
        Assert.Equal("cs-do-pdv", body.RootElement.GetProperty("clientSaleId").GetString());
    }

    [Fact]
    public async Task Fechamento_leva_clientSessionId_e_closedAt_do_payload_ou_do_horario_do_evento()
    {
        var (client, cloud) = Build(HttpStatusCode.OK);

        await client.SendAsync(Event("CASH_CLOSE", """{"operatorId":"usr-1","clientSessionId":"cx-offline-1","closedAt":"2026-10-19T19:03:00Z"}"""));
        await client.SendAsync(Event("CASH_CLOSE", """{"operatorId":"usr-1","clientSessionId":"cx-offline-1"}"""));
        await client.SendAsync(Event("CASH_OPEN", """{"operatorId":"usr-1","clientSessionId":"cx-offline-1"}"""));

        using var withClosedAt = JsonDocument.Parse(cloud.Calls[0].Body);
        Assert.Equal("cx-offline-1", withClosedAt.RootElement.GetProperty("clientSessionId").GetString());
        Assert.Equal("2026-10-19T19:03:00Z", withClosedAt.RootElement.GetProperty("closedAt").GetString());
        using var fallback = JsonDocument.Parse(cloud.Calls[1].Body);
        Assert.Equal("2026-10-06T12:00:00Z", fallback.RootElement.GetProperty("closedAt").GetString());
        using var open = JsonDocument.Parse(cloud.Calls[2].Body);
        Assert.Equal("cx-offline-1", open.RootElement.GetProperty("clientSessionId").GetString());
        Assert.Equal("2026-10-06T12:00:00Z", open.RootElement.GetProperty("openedAt").GetString());
    }

    [Theory]
    [InlineData(HttpStatusCode.Unauthorized, CloudSyncStatus.Transient)]   // token revogado: guarda, não descarta
    [InlineData(HttpStatusCode.Forbidden, CloudSyncStatus.Transient)]
    [InlineData(HttpStatusCode.InternalServerError, CloudSyncStatus.Transient)]
    [InlineData(HttpStatusCode.TooManyRequests, CloudSyncStatus.Transient)]
    [InlineData(HttpStatusCode.Conflict, CloudSyncStatus.Permanent)]       // mesmo EventId, dados diferentes
    [InlineData(HttpStatusCode.BadRequest, CloudSyncStatus.Permanent)]     // regra de negócio recusou
    public async Task Mapeia_resposta_da_nuvem(HttpStatusCode status, CloudSyncStatus expected)
    {
        var (client, _) = Build(status, """{"success":false,"message":"motivo da nuvem"}""");

        var result = await client.SendAsync(Event("CASH_MOVEMENT", """{"operatorId":"usr-1"}"""));

        Assert.Equal(expected, result.Status);
        Assert.Contains("motivo da nuvem", result.Message);
    }

    [Fact]
    public async Task Evento_de_pedido_nao_vai_a_nuvem_e_conta_como_enviado()
    {
        var (client, cloud) = Build(HttpStatusCode.OK);

        var result = await client.SendAsync(Event("ORDER_CREATED", """{"orderNumber":"P-1"}"""));

        Assert.Equal(CloudSyncStatus.Success, result.Status);
        Assert.Empty(cloud.Calls);
    }

    [Fact]
    public async Task Evento_sem_operador_e_falha_definitiva_sem_chamar_a_nuvem()
    {
        var (client, cloud) = Build(HttpStatusCode.OK);

        var result = await client.SendAsync(Event("SALE_CREATED", """{"totalAmount":"10,00"}"""));

        Assert.Equal(CloudSyncStatus.Permanent, result.Status);
        Assert.Contains("operatorId", result.Message);
        Assert.Empty(cloud.Calls);
    }
}
