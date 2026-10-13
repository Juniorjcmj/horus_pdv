using Microsoft.OpenApi;

namespace HORUSPDV_API.Services.Admin;

public static class ApiDocumentation
{
    public const string DocumentName = "v1";
    public const string FileName = "quack-pdv-api-openapi.json";

    public static IServiceCollection AddHorusApiDocumentation(this IServiceCollection services)
    {
        services.AddEndpointsApiExplorer();
        services.AddSwaggerGen(options =>
        {
            options.SwaggerDoc(DocumentName, new OpenApiInfo
            {
                Title = "Quack Sistemas — API do PDV",
                Version = DocumentName,
                Description = "Contrato da API gerado a partir dos endpoints e modelos desta versão. " +
                    "Requisições autenticadas usam o cookie horuspdv.auth ou o cabeçalho Authorization: Bearer. " +
                    "O acesso depende da sessão, empresa e perfil do usuário. " +
                    "Não inclui dados do banco, senhas, certificados nem valores das configurações do servidor."
            });
            options.AddSecurityDefinition("Bearer", new OpenApiSecurityScheme
            {
                Type = SecuritySchemeType.Http, Scheme = "bearer", BearerFormat = "JWT",
                Description = "Token de uma sessão ativa, enviado no cabeçalho Authorization."
            });
            options.AddSecurityDefinition("SessionCookie", new OpenApiSecurityScheme
            {
                Type = SecuritySchemeType.ApiKey, In = ParameterLocation.Cookie, Name = "horuspdv.auth",
                Description = "Cookie de sessão emitido pelo login."
            });
            var xml = Path.Combine(AppContext.BaseDirectory, "HORUSPDV-API.xml");
            if (File.Exists(xml)) options.IncludeXmlComments(xml, includeControllerXmlComments: true);
            options.SchemaFilter<ApiContractSchemaFilter>();
            options.OperationFilter<ApiContractOperationFilter>();
            options.DocumentFilter<ApiContractSecurityFilter>();
        });
        return services;
    }
}
