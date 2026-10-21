# Arquivo: API/NETCORE/Dockerfile
# Objetivo: build multi-stage da API .NET 8 do Hórus PDV para produção (GitHub Actions -> ghcr.io -> Portainer).
# Contexto de build esperado: raiz do repo (inclui Shared/). Ver .github/workflows/docker-publish.yml.

FROM mcr.microsoft.com/dotnet/sdk:8.0 AS build
WORKDIR /src

# Restaura em camada separada para cachear entre builds quando só o código muda, não o csproj.
COPY API/NETCORE/HORUSPDV-API.csproj ./
RUN dotnet restore "HORUSPDV-API.csproj"

COPY Shared/ /Shared/
COPY API/NETCORE/ .
RUN dotnet publish "HORUSPDV-API.csproj" \
    --configuration Release \
    --no-restore \
    --output /app/publish

FROM mcr.microsoft.com/dotnet/aspnet:8.0 AS final
WORKDIR /app

# Kestrel escuta em 8080 (sem TLS) — a terminação HTTPS é feita pelo Traefik na frente.
ENV ASPNETCORE_URLS=http://+:8080 \
    ASPNETCORE_ENVIRONMENT=Production \
    DOTNET_EnableDiagnostics=0 \
    TZ=America/Sao_Paulo

EXPOSE 8080

# Roda como usuário não-root (imagem base já cria "app" com esse propósito).
USER app

COPY --from=build /app/publish .

ENTRYPOINT ["dotnet", "HORUSPDV-API.dll"]
