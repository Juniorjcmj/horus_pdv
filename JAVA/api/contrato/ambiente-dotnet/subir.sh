#!/usr/bin/env bash
# Sobe o ambiente .NET usado para GRAVAR o contrato (e para gerar o esquema base):
#   - contrato-sql          SQL Server 2022 na porta 14334 (sa / Contrato#Quack2026)
#   - contrato-api-dotnet   API .NET atual na porta 5270 (Production, reCAPTCHA e e-mail desligados)
# Depois aplica contrato/seed/seed-contrato.sql (empresas e usuarios do D6).
# Nao mexe nos conteineres pdv-teste-*.
#
# Uso: contrato/ambiente-dotnet/subir.sh            (constroi a imagem .NET se ainda nao existir)
#      REBUILD=1 contrato/ambiente-dotnet/subir.sh  (reconstroi a imagem)
# No Git Bash: o script ja usa MSYS_NO_PATHCONV=1 nos docker exec/cp.
set -euo pipefail

AQUI="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$AQUI/../../../.." && pwd)"
IMAGEM="quack-api-dotnet:contrato"
REDE="contrato-net"
SA_PWD='Contrato#Quack2026'
export MSYS_NO_PATHCONV=1

docker network create "$REDE" >/dev/null 2>&1 || true
docker rm -f contrato-sql contrato-api-dotnet >/dev/null 2>&1 || true

if [ -n "${REBUILD:-}" ] || ! docker image inspect "$IMAGEM" >/dev/null 2>&1; then
  echo ">> Construindo a imagem $IMAGEM (contexto limpo, sem bin/obj)"
  CTX="${TMPDIR:-/tmp}/contrato-dotnet-ctx"
  rm -rf "$CTX" && mkdir -p "$CTX"
  (cd "$REPO" && tar --exclude=bin --exclude=obj -cf - API/NETCORE Shared) | tar -xf - -C "$CTX"
  (cd "$CTX" && docker build -q -f API/NETCORE/Dockerfile -t "$IMAGEM" .)
fi

echo ">> Subindo o SQL Server (contrato-sql, porta 14334)"
docker run -d --name contrato-sql --network "$REDE" -p 14334:1433 \
  -e ACCEPT_EULA=Y -e MSSQL_SA_PASSWORD="$SA_PWD" \
  mcr.microsoft.com/mssql/server:2022-latest >/dev/null

for i in $(seq 1 60); do
  if docker exec contrato-sql /opt/mssql-tools18/bin/sqlcmd -C -I -S localhost -U sa -P "$SA_PWD" -Q "SELECT 1" >/dev/null 2>&1; then break; fi
  sleep 2
done

echo ">> Subindo a API .NET (contrato-api-dotnet, porta 5270)"
docker run -d --name contrato-api-dotnet --network "$REDE" -p 5270:8080 \
  -e ASPNETCORE_ENVIRONMENT=Production \
  -e "ConnectionStrings__HorusPdv=Server=contrato-sql,1433;Database=HorusPdv;User Id=sa;Password=$SA_PWD;TrustServerCertificate=True;Encrypt=True;MultipleActiveResultSets=True" \
  -e Auth__JwtSecret=segredo-jwt-do-contrato-0123456789-abcdefghijkl \
  -e Security__EncryptionKey=chave-de-cifra-do-contrato-0123456789-abcdefgh \
  -e Recaptcha__Enabled=false \
  -e Security__RateLimit__MaxRequests=5000 \
  -e Email__Enabled=false \
  "$IMAGEM" >/dev/null

echo ">> Esperando a API criar o banco e responder"
for i in $(seq 1 90); do
  codigo="$(curl -s -o /dev/null -w '%{http_code}' http://localhost:5270/api/Auth/me || true)"
  if [ "$codigo" = "401" ]; then break; fi
  sleep 2
done
[ "$codigo" = "401" ] || { echo "API .NET nao subiu (ultimo codigo: $codigo)"; docker logs --tail 40 contrato-api-dotnet; exit 1; }

echo ">> Aplicando o seed do contrato"
SEED="$(cd "$AQUI/../seed" && (pwd -W 2>/dev/null || pwd))/seed-contrato.sql"
docker cp "$SEED" contrato-sql:/tmp/seed-contrato.sql
docker exec contrato-sql /opt/mssql-tools18/bin/sqlcmd -C -I -S localhost -U sa -P "$SA_PWD" -d HorusPdv -b -i /tmp/seed-contrato.sql

echo ">> Pronto: API .NET em http://localhost:5270, SQL Server em localhost:14334 (sa / $SA_PWD)"
