/*
 * Arquivo: tests/HorusGateway.Tests/GatewayAppFactory.cs
 * Objetivo: sobe o HorusGateway em memória (WebApplicationFactory) apontando para um banco SQLite
 *           temporário e uma empresa configurável, permitindo simular reinicialização (mesmo arquivo).
 */
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.Hosting;

namespace HorusGateway.Tests;

public sealed class GatewayAppFactory : WebApplicationFactory<Program>
{
    private readonly string _databasePath;
    private readonly string _companyId;
    private readonly string _storeId;

    public GatewayAppFactory(string databasePath, string companyId = "empresa-1", string storeId = "store-001")
    {
        _databasePath = databasePath;
        _companyId = companyId;
        _storeId = storeId;
    }

    protected override IHost CreateHost(IHostBuilder builder)
    {
        builder.UseEnvironment("Production"); // evita depender do appsettings.Development.json
        builder.ConfigureHostConfiguration(config =>
        {
            config.AddInMemoryCollection(new Dictionary<string, string?>
            {
                ["Gateway:GatewayId"] = "gw_test",
                ["Gateway:CompanyId"] = _companyId,
                ["Gateway:StoreId"] = _storeId,
                ["Gateway:DatabasePath"] = _databasePath
            });
        });

        return base.CreateHost(builder);
    }
}
