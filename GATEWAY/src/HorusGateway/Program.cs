/*
 * Arquivo: Program.cs
 * Objetivo: compõe o HorusGateway — API HTTP local, SQLite durável, SignalR (tempo real),
 *           health checks, logs estruturados (JSON) e inicialização/recuperação do schema no boot.
 *
 * O Gateway é uma CAMADA ADICIONAL de comunicação LAN. Ele nunca substitui o mecanismo
 * offline-first dos terminais (IndexedDB/Outbox → Cloud), que continua funcionando quando
 * o Gateway estiver indisponível.
 */
using HorusGateway.Configuration;
using HorusGateway.Data;
using HorusGateway.Hubs;
using HorusGateway.Logging;
using HorusGateway.Services;
using Microsoft.Extensions.Hosting.WindowsServices;

var builder = WebApplication.CreateBuilder(args);

// Execução como Serviço do Windows (CHANGE GATEWAY 08). No-op fora do Windows/quando não é serviço,
// então é seguro em qualquer plataforma. Permite `sc create` + partida automática no boot da máquina.
builder.Host.UseWindowsService(options => options.ServiceName = "HorusGateway");

// Logs estruturados (JSON) — prontos para coleta/observabilidade (console: dev/Docker).
builder.Logging.ClearProviders();
builder.Logging.AddJsonConsole(options =>
{
    options.IncludeScopes = true;
    options.UseUtcTimestamp = true;
});

// Log em arquivo (sempre): como Serviço do Windows não há console, os logs iriam para o nada.
// Grava em <dir-do-exe>/logs/gateway-YYYY-MM-DD.log, com rotação/retenção.
builder.Logging.AddProvider(new FileLoggerProvider(new FileLoggerOptions(), AppContext.BaseDirectory));

// Quando rodando como Serviço do Windows, também registra no Visualizador de Eventos do Windows.
if (OperatingSystem.IsWindows() && WindowsServiceHelpers.IsWindowsService())
{
#pragma warning disable CA1416 // Guardado por OperatingSystem.IsWindows(); só executa no Windows.
    builder.Logging.AddEventLog(options => options.SourceName = "HorusGateway");
#pragma warning restore CA1416
}

// Configuração por ambiente: seção "Gateway" (appsettings + variáveis de ambiente).
builder.Services.Configure<GatewayOptions>(builder.Configuration.GetSection(GatewayOptions.SectionName));

// CORS liberado para a LAN local (terminais PWA da loja em portas variadas).
const string LanCorsPolicy = "HorusGatewayLanCors";
builder.Services.AddCors(options =>
{
    options.AddPolicy(LanCorsPolicy, policy => policy
        .SetIsOriginAllowed(_ => true) // rede local; autenticação por terminal chega no CHANGE GATEWAY 03
        .AllowAnyHeader()
        .AllowAnyMethod()
        .AllowCredentials());
});

builder.Services.AddControllers();
builder.Services.AddEndpointsApiExplorer();
builder.Services.AddSwaggerGen();
builder.Services.AddSignalR();

// Identidade e persistência do Gateway.
builder.Services.AddSingleton<IClock, SystemClock>();
builder.Services.AddSingleton<GatewayIdentity>();
builder.Services.AddSingleton<GatewayDatabase>();
builder.Services.AddSingleton<IEventStore, SqliteEventStore>();
builder.Services.AddSingleton<ITerminalStore, SqliteTerminalStore>();
builder.Services.AddSingleton<IOrderStore, SqliteOrderStore>();

// Emissão e Contingência Fiscal Offline na LAN
builder.Services.AddSingleton<GatewayCertificateService>();
builder.Services.AddSingleton<LocalFiscalSigner>();
builder.Services.AddSingleton<LocalFiscalStore>();

// Sincronização Gateway → Cloud (CHANGE GATEWAY 06).
builder.Services.AddSingleton<CloudSyncState>();
// Dois destinos possíveis: endpoints da API com token por loja (preferido) ou a URL de lote legada.
builder.Services.AddHttpClient<HttpCloudSyncClient>(client =>
{
    client.Timeout = TimeSpan.FromSeconds(15);
});
builder.Services.AddHttpClient<CloudEndpointSyncClient>(client =>
{
    // Venda/caixa podem demorar na nuvem; o replay idempotente cobre um timeout aqui.
    client.Timeout = TimeSpan.FromSeconds(30);
});
builder.Services.AddTransient<ICloudSyncClient>(sp =>
    sp.GetRequiredService<Microsoft.Extensions.Options.IOptions<GatewayOptions>>().Value.UsesCloudEndpoints
        ? sp.GetRequiredService<CloudEndpointSyncClient>()
        : sp.GetRequiredService<HttpCloudSyncClient>());
builder.Services.AddScoped<CloudSyncDispatcher>();
builder.Services.AddHostedService<CloudSyncBackgroundService>();

// Token por loja: testa a conexão com a API da nuvem (ping) e expõe o estado em /api/gateway/cloud.
builder.Services.AddSingleton<CloudConnectionState>();
builder.Services.AddHttpClient(CloudConnectionMonitor.HttpClientName, client =>
{
    client.Timeout = TimeSpan.FromSeconds(20);
});
builder.Services.AddHostedService<CloudConnectionMonitor>();

var app = builder.Build();

// Inicializa o schema local (idempotente) e registra a identidade + recuperação no boot.
using (var scope = app.Services.CreateScope())
{
    var database = scope.ServiceProvider.GetRequiredService<GatewayDatabase>();
    var identity = scope.ServiceProvider.GetRequiredService<GatewayIdentity>();
    var store = scope.ServiceProvider.GetRequiredService<IEventStore>();
    var logger = scope.ServiceProvider.GetRequiredService<ILogger<Program>>();

    database.Initialize();

    if (identity.IsBound)
    {
        // Recuperação após reinicialização/queda de energia: os eventos vivem em disco (SQLite).
        var total = await store.CountAsync(identity.CompanyId);
        var pending = await store.CountPendingCloudAsync(identity.CompanyId);
        logger.LogInformation(
            "Quack Gateway iniciado. GatewayId={GatewayId} CompanyId={CompanyId} StoreId={StoreId} | eventos recuperados={Total} pendentes-cloud={Pending}",
            identity.GatewayId, identity.CompanyId, identity.StoreId, total, pending);
    }
    else
    {
        logger.LogWarning(
            "Quack Gateway iniciado SEM CompanyId configurado (GatewayId={GatewayId}). Ingestão de eventos ficará indisponível até configurar Gateway:CompanyId.",
            identity.GatewayId);
    }
}

if (app.Environment.IsDevelopment())
{
    app.UseSwagger();
    app.UseSwaggerUI();
}

app.UseCors(LanCorsPolicy);

// Dashboard local de monitoramento/administração (CHANGE GATEWAY 08): página estática servida pelo
// próprio Gateway em `/`. Zero build/npm — consome os endpoints existentes (/health, /status,
// /terminals, /terminals/provision, /update-readiness). Não afeta o frontend homologado (que fala
// com a Cloud); é apenas a UI de quem opera a máquina do Gateway na LAN.
app.UseDefaultFiles();
app.UseStaticFiles();

app.MapControllers();
app.MapHub<EventsHub>("/hubs/events");

app.Run();

// Necessário para o WebApplicationFactory nos testes de integração.
public partial class Program { }
