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
using HorusGateway.Services;

var builder = WebApplication.CreateBuilder(args);

// Logs estruturados (JSON) — prontos para coleta/observabilidade.
builder.Logging.ClearProviders();
builder.Logging.AddJsonConsole(options =>
{
    options.IncludeScopes = true;
    options.UseUtcTimestamp = true;
});

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

// Sincronização Gateway → Cloud (CHANGE GATEWAY 06).
builder.Services.AddSingleton<CloudSyncState>();
builder.Services.AddHttpClient<ICloudSyncClient, HttpCloudSyncClient>(client =>
{
    client.Timeout = TimeSpan.FromSeconds(15);
});
builder.Services.AddScoped<CloudSyncDispatcher>();
builder.Services.AddHostedService<CloudSyncBackgroundService>();

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
            "HorusGateway iniciado. GatewayId={GatewayId} CompanyId={CompanyId} StoreId={StoreId} | eventos recuperados={Total} pendentes-cloud={Pending}",
            identity.GatewayId, identity.CompanyId, identity.StoreId, total, pending);
    }
    else
    {
        logger.LogWarning(
            "HorusGateway iniciado SEM CompanyId configurado (GatewayId={GatewayId}). Ingestão de eventos ficará indisponível até configurar Gateway:CompanyId.",
            identity.GatewayId);
    }
}

if (app.Environment.IsDevelopment())
{
    app.UseSwagger();
    app.UseSwaggerUI();
}

app.UseCors(LanCorsPolicy);
app.MapControllers();
app.MapHub<EventsHub>("/hubs/events");

app.Run();

// Necessário para o WebApplicationFactory nos testes de integração.
public partial class Program { }
