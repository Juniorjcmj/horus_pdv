@echo off
REM Arquivo: GATEWAY\run-gateway.bat
REM Objetivo: subir o HorusGateway na maquina local (Windows) com duplo-clique.
REM Variaveis opcionais: GATEWAY_COMPANY_ID, GATEWAY_STORE_ID, GATEWAY_PORT.
setlocal
set "SCRIPT_DIR=%~dp0"

where dotnet >nul 2>nul
if errorlevel 1 (
  echo [Quack Gateway] .NET SDK 8 nao encontrado.
  echo                Instale em: https://dotnet.microsoft.com/download/dotnet/8.0
  pause
  exit /b 1
)

if not defined GATEWAY_COMPANY_ID set "GATEWAY_COMPANY_ID=empresa-1"
if not defined GATEWAY_STORE_ID set "GATEWAY_STORE_ID=store-001"
if not defined GATEWAY_PORT set "GATEWAY_PORT=5080"

set "ASPNETCORE_ENVIRONMENT=Production"
set "ASPNETCORE_URLS=http://0.0.0.0:%GATEWAY_PORT%"
set "Gateway__CompanyId=%GATEWAY_COMPANY_ID%"
set "Gateway__StoreId=%GATEWAY_STORE_ID%"
set "Gateway__DatabasePath=%SCRIPT_DIR%gateway-data\horus-gateway.db"

echo ======================================================
echo  Quack Gateway
echo  Empresa : %Gateway__CompanyId%
echo  Loja    : %Gateway__StoreId%
echo  Porta   : %GATEWAY_PORT%
echo  Local   : http://localhost:%GATEWAY_PORT%/health/live
echo  LAN     : http://SEU-IP:%GATEWAY_PORT%  (libere a porta %GATEWAY_PORT% no firewall)
echo ======================================================

cd /d "%SCRIPT_DIR%src\HorusGateway"
dotnet run -c Release --no-launch-profile
pause
