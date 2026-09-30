# Arquivo: GATEWAY\run-gateway.ps1
# Objetivo: subir o HorusGateway na maquina local (Windows/PowerShell), liberando a porta no firewall.
# Uso:      pwsh ./run-gateway.ps1   (ou clique com o direito > "Executar com o PowerShell")
# Parametros opcionais: -CompanyId, -StoreId, -Port
param(
    [string]$CompanyId = $(if ($env:GATEWAY_COMPANY_ID) { $env:GATEWAY_COMPANY_ID } else { "empresa-1" }),
    [string]$StoreId   = $(if ($env:GATEWAY_STORE_ID)   { $env:GATEWAY_STORE_ID }   else { "store-001" }),
    [int]$Port         = $(if ($env:GATEWAY_PORT)        { [int]$env:GATEWAY_PORT }  else { 5080 })
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectDir = Join-Path $ScriptDir "src/HorusGateway"

if (-not (Get-Command dotnet -ErrorAction SilentlyContinue)) {
    Write-Host "[Quack Gateway] .NET SDK 8 nao encontrado." -ForegroundColor Red
    Write-Host "               Instale em: https://dotnet.microsoft.com/download/dotnet/8.0"
    exit 1
}

# Tenta liberar a porta no firewall (ignora se nao for admin).
try {
    if (-not (Get-NetFirewallRule -DisplayName "HorusGateway" -ErrorAction SilentlyContinue)) {
        New-NetFirewallRule -DisplayName "HorusGateway" -Direction Inbound -Protocol TCP -LocalPort $Port -Action Allow | Out-Null
        Write-Host "[Quack Gateway] Regra de firewall criada para a porta $Port."
    }
} catch {
    Write-Host "[Quack Gateway] Nao foi possivel criar a regra de firewall (rode como Administrador para liberar a porta $Port)." -ForegroundColor Yellow
}

$env:ASPNETCORE_ENVIRONMENT = "Production"
$env:ASPNETCORE_URLS = "http://0.0.0.0:$Port"
$env:Gateway__CompanyId = $CompanyId
$env:Gateway__StoreId = $StoreId
$env:Gateway__DatabasePath = Join-Path $ScriptDir "gateway-data/horus-gateway.db"

Write-Host "======================================================"
Write-Host " Quack Gateway"
Write-Host " Empresa : $CompanyId"
Write-Host " Loja    : $StoreId"
Write-Host " Porta   : $Port"
Write-Host " Local   : http://localhost:$Port/health/live"
Write-Host " LAN     : http://<IP-desta-maquina>:$Port"
Write-Host "======================================================"

Set-Location $ProjectDir
dotnet run -c Release --no-launch-profile
