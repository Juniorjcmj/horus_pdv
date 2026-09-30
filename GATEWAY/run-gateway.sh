#!/usr/bin/env bash
# Arquivo: GATEWAY/run-gateway.sh
# Objetivo: subir o HorusGateway na máquina local (Linux/macOS) com um comando só.
# Uso:      ./run-gateway.sh
# Variáveis opcionais (antes do comando): GATEWAY_COMPANY_ID, GATEWAY_STORE_ID, GATEWAY_PORT.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$SCRIPT_DIR/src/HorusGateway"

if ! command -v dotnet >/dev/null 2>&1; then
  echo "[Quack Gateway] .NET SDK 8 não encontrado."
  echo "               Instale em: https://dotnet.microsoft.com/download/dotnet/8.0"
  exit 1
fi

PORT="${GATEWAY_PORT:-5080}"
export ASPNETCORE_ENVIRONMENT="${ASPNETCORE_ENVIRONMENT:-Production}"
export ASPNETCORE_URLS="http://0.0.0.0:${PORT}"
export Gateway__CompanyId="${GATEWAY_COMPANY_ID:-empresa-1}"
export Gateway__StoreId="${GATEWAY_STORE_ID:-store-001}"
export Gateway__DatabasePath="${GATEWAY_DB_PATH:-$SCRIPT_DIR/gateway-data/horus-gateway.db}"

echo "======================================================"
echo " Quack Gateway"
echo " Empresa : $Gateway__CompanyId"
echo " Loja    : $Gateway__StoreId"
echo " Porta   : $PORT"
echo " Local   : http://localhost:$PORT/health/live"
echo " LAN     : http://<IP-desta-maquina>:$PORT  (libere a porta $PORT no firewall)"
echo " Dados   : $Gateway__DatabasePath"
echo "======================================================"

cd "$PROJECT_DIR"
exec dotnet run -c Release --no-launch-profile
