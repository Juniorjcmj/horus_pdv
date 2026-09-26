/**
 * Arquivo: API/NETCORE/Program.cs
 * Objetivo: configura serviços, middlewares, CORS, autenticação e inicialização da API do Hórus PDV.
 * Entradas esperadas: espera configurações de ambiente/appsettings e registra o pipeline HTTP da aplicação.
 */
using HORUSPDV_API.Middlewares;
using HORUSPDV_API.Repositories;
using HORUSPDV_API.Repositories.DatabaseAccess;
using HORUSPDV_API.Services.Caixa;
using HORUSPDV_API.Services.Categorias;
using HORUSPDV_API.Services.Clientes;
using HORUSPDV_API.Services.Email;
using HORUSPDV_API.Services.Fiado;
using HORUSPDV_API.Services.Fiscal;
using HORUSPDV_API.Services.Fornecedores;
using HORUSPDV_API.Services.Produtos;
using HORUSPDV_API.Services.Promocoes;
using HORUSPDV_API.Services.Security;
using Microsoft.AspNetCore.HttpOverrides;

var builder = WebApplication.CreateBuilder(args);

var configuredCors = builder.Configuration["Security:CorsOrigins"];
var rawCors = !string.IsNullOrWhiteSpace(configuredCors)
    ? configuredCors
    : "https://pdv.wootchat.com.br,http://pdv.wootchat.com.br,http://localhost:5173,https://localhost:5173,http://127.0.0.1:5173,https://127.0.0.1:5173,http://localhost:4173,https://localhost:4173,http://127.0.0.1:4173,https://127.0.0.1:4173";

var explicitOrigins = rawCors
    .Split(',', StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries)
    .Select(o => o.TrimEnd('/'))
    .ToHashSet(StringComparer.OrdinalIgnoreCase);

// Sempre assegura https://pdv.wootchat.com.br e variantes na lista
explicitOrigins.Add("https://pdv.wootchat.com.br");
explicitOrigins.Add("http://pdv.wootchat.com.br");

builder.Services.AddControllers();
builder.Services.AddEndpointsApiExplorer();
builder.Services.AddSwaggerGen();
builder.Services.Configure<HorusEmailOptions>(builder.Configuration.GetSection("Email"));

builder.Services.Configure<ForwardedHeadersOptions>(options =>
{
    options.ForwardedHeaders = ForwardedHeaders.XForwardedFor
                             | ForwardedHeaders.XForwardedProto
                             | ForwardedHeaders.XForwardedHost;
    options.KnownNetworks.Clear();
    options.KnownProxies.Clear();
});

builder.Services.AddCors(options =>
{
    options.AddPolicy("HorusPdvCorsPolicy", policyBuilder =>
    {
        policyBuilder
            .SetIsOriginAllowed(origin =>
            {
                if (string.IsNullOrWhiteSpace(origin)) return false;
                var trimmed = origin.TrimEnd('/');
                if (explicitOrigins.Contains(trimmed)) return true;

                if (Uri.TryCreate(origin, UriKind.Absolute, out var uri))
                {
                    var host = uri.Host;
                    if (host.Equals("wootchat.com.br", StringComparison.OrdinalIgnoreCase) ||
                        host.EndsWith(".wootchat.com.br", StringComparison.OrdinalIgnoreCase) ||
                        host.Equals("localhost", StringComparison.OrdinalIgnoreCase) ||
                        host.Equals("127.0.0.1", StringComparison.OrdinalIgnoreCase))
                    {
                        return true;
                    }
                }

                return false;
            })
            .AllowAnyHeader()
            .AllowAnyMethod()
            .AllowCredentials();
    });
});

builder.Services.AddSingleton<Connection>();
builder.Services.AddScoped<ProdutoAB>();
builder.Services.AddScoped<ClienteAB>();
builder.Services.AddScoped<FornecedorAB>();
builder.Services.AddScoped<EmpresaAB>();
builder.Services.AddScoped<HistoricoVendasAB>();
builder.Services.AddScoped<PedidoAB>();
builder.Services.AddScoped<OrdemCompraAB>();
builder.Services.AddScoped<ModuloMercadoAB>();
builder.Services.AddScoped<CaixaAB>();
builder.Services.AddScoped<AuditLogAB>();
builder.Services.AddScoped<HomeAB>();
builder.Services.AddScoped<RelatorioAB>();
builder.Services.AddScoped<HorusCaixaService>();
builder.Services.AddScoped<HorusSecurityStore>();
builder.Services.AddSingleton<HorusSecurityOptions>();
builder.Services.AddSingleton<HorusSecretProtector>();
builder.Services.AddSingleton<HorusJwtService>();
builder.Services.AddScoped<HorusEmailService>();
builder.Services.AddHttpClient<HorusRecaptchaService>();
builder.Services.AddScoped<CategoriaAB>();
builder.Services.AddScoped<ICategoriaService, CategoriaService>();
builder.Services.AddScoped<PromocaoAB>();
builder.Services.AddScoped<IPromocaoService, PromocaoService>();
builder.Services.AddScoped<FiadoAB>();
builder.Services.AddScoped<IFiadoService, FiadoService>();
builder.Services.AddScoped<IProdutoService, ProdutoService>();
builder.Services.AddScoped<IClienteService, ClienteService>();
builder.Services.AddScoped<IFornecedorService, FornecedorService>();
builder.Services.AddScoped<NfeImportService>();
builder.Services.AddScoped<SefazDFeDownloadService>();

// Módulo fiscal (NFC-e modelo 65) — ver API/NETCORE/DataBase/README-FISCAL.md
builder.Services.AddScoped<IFiscalProvider, ZeusFiscalProvider>();
builder.Services.AddScoped<DocumentoFiscalAB>();
builder.Services.AddScoped<EmitenteFiscalStore>();
builder.Services.AddHostedService<NfceOutboxWorker>();

var app = builder.Build();

app.Services.GetRequiredService<HorusSecurityOptions>().Validate();
await HorusDatabaseInitializer.InitializeAsync(app.Services);

if (app.Environment.IsDevelopment())
{
    app.UseDeveloperExceptionPage();
}
else
{
    app.UseExceptionHandler(errorApp =>
    {
        errorApp.Run(async context =>
        {
            context.Response.StatusCode = StatusCodes.Status500InternalServerError;
            context.Response.ContentType = "application/json";
            await context.Response.WriteAsJsonAsync(new
            {
                success = false,
                message = "Erro interno no servidor."
            });
        });
    });
    app.UseHsts();
}

app.UseForwardedHeaders();

app.UseRouting();
app.UseCors("HorusPdvCorsPolicy");

app.UseMiddleware<HorusSecurityHeadersMiddleware>();
app.UseMiddleware<HorusRequestTelemetryMiddleware>();
app.UseMiddleware<HorusRequestBodyLimitMiddleware>();
app.UseMiddleware<HorusRateLimitMiddleware>();
app.UseMiddleware<HorusAuthMiddleware>();
app.UseAuthorization();

if (app.Environment.IsDevelopment())
{
    app.UseSwagger();
    app.UseSwaggerUI();
}

app.MapControllers();

app.Run();
