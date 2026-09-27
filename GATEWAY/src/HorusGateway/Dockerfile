# Arquivo: GATEWAY/src/HorusGateway/Dockerfile
# Objetivo: build multi-stage do HorusGateway (.NET 8). Contexto de build esperado: GATEWAY/.
# Kestrel escuta em 5080 (HTTP). Em produção na loja, preferir HTTPS local (decisão de implantação).

FROM mcr.microsoft.com/dotnet/sdk:8.0 AS build
WORKDIR /src

COPY src/HorusGateway/HorusGateway.csproj src/HorusGateway/
RUN dotnet restore "src/HorusGateway/HorusGateway.csproj"

COPY src/HorusGateway/ src/HorusGateway/
RUN dotnet publish "src/HorusGateway/HorusGateway.csproj" \
    --configuration Release \
    --no-restore \
    --output /app/publish

FROM mcr.microsoft.com/dotnet/aspnet:8.0 AS final
WORKDIR /app

ENV ASPNETCORE_URLS=http://+:5080 \
    ASPNETCORE_ENVIRONMENT=Production \
    TZ=America/Sao_Paulo \
    Gateway__DatabasePath=/app/gateway-data/horus-gateway.db

EXPOSE 5080

# Volume para persistência durável dos eventos (sobrevive a reinicialização do contêiner).
VOLUME ["/app/gateway-data"]

COPY --from=build /app/publish .

ENTRYPOINT ["dotnet", "HorusGateway.dll"]
