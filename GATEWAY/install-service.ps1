# Arquivo: GATEWAY\install-service.ps1
# Objetivo: publicar o HorusGateway e registra-lo como Servico do Windows (inicia sozinho no boot).
#           Requer PowerShell como Administrador. Nao instala SQL Server/Node/Docker — apenas .NET.
# Uso:      Clique direito > "Executar com o PowerShell" (como Administrador)
#           ou:  pwsh -File .\install-service.ps1 -CompanyId minha-empresa -StoreId loja-01 -Port 5080
param(
    [string]$CompanyId   = $(if ($env:GATEWAY_COMPANY_ID) { $env:GATEWAY_COMPANY_ID } else { "empresa-1" }),
    [string]$StoreId     = $(if ($env:GATEWAY_STORE_ID)   { $env:GATEWAY_STORE_ID }   else { "store-001" }),
    [int]$Port           = $(if ($env:GATEWAY_PORT)        { [int]$env:GATEWAY_PORT }  else { 5080 }),
    [string]$ServiceName = "HorusGateway",
    [string]$CloudSyncUrl = $env:GATEWAY_CLOUD_SYNC_URL
)

$ErrorActionPreference = "Stop"
$ScriptDir  = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectDir = Join-Path $ScriptDir "src/HorusGateway"
$PublishDir = Join-Path $ScriptDir "publish-service"
$DataDir    = Join-Path $ScriptDir "gateway-data"
$Exe        = Join-Path $PublishDir "HorusGateway.exe"

# 1) Precisa ser Administrador para registrar o servico e liberar o firewall.
$isAdmin = ([Security.Principal.WindowsPrincipal] [Security.Principal.WindowsIdentity]::GetCurrent()
    ).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    Write-Host "[Quack Gateway] Rode este script como Administrador." -ForegroundColor Red
    exit 1
}

# 2) Precisa do .NET SDK 8 para publicar (self-contained: a maquina alvo nao precisa de runtime).
if (-not (Get-Command dotnet -ErrorAction SilentlyContinue)) {
    Write-Host "[Quack Gateway] .NET SDK 8 nao encontrado. Instale em:" -ForegroundColor Red
    Write-Host "               https://dotnet.microsoft.com/download/dotnet/8.0"
    exit 1
}

# 3) Publica self-contained (win-x64) — um .exe que roda sem instalar nada.
Write-Host "[Quack Gateway] Publicando (self-contained win-x64)..." -ForegroundColor Cyan
dotnet publish $ProjectDir -c Release -r win-x64 --self-contained true `
    -p:PublishSingleFile=false -o $PublishDir
if (-not (Test-Path $Exe)) { throw "Publicacao falhou: $Exe nao encontrado." }

New-Item -ItemType Directory -Force -Path $DataDir | Out-Null

# 4) Remove um servico anterior de mesmo nome (atualizacao controlada: pare-o pelo painel antes).
if (Get-Service -Name $ServiceName -ErrorAction SilentlyContinue) {
    Write-Host "[Quack Gateway] Removendo servico anterior '$ServiceName'..." -ForegroundColor Yellow
    sc.exe stop $ServiceName | Out-Null
    Start-Sleep -Seconds 2
    sc.exe delete $ServiceName | Out-Null
    Start-Sleep -Seconds 2
}

# 5) Cria o servico com start automatico. As configuracoes vao por variaveis de ambiente do processo.
Write-Host "[Quack Gateway] Registrando servico '$ServiceName'..." -ForegroundColor Cyan
New-Service -Name $ServiceName -BinaryPathName "`"$Exe`"" -DisplayName "Quack Gateway (LAN)" `
    -Description "Coordenador local da loja (offline-first) — Hórus PDV." -StartupType Automatic | Out-Null

# Variaveis de ambiente do servico (HKLM). O ASP.NET Core le a secao Gateway por Gateway__*.
$envLines = @(
    "ASPNETCORE_ENVIRONMENT=Production",
    "ASPNETCORE_URLS=http://0.0.0.0:$Port",
    "Gateway__CompanyId=$CompanyId",
    "Gateway__StoreId=$StoreId",
    "Gateway__DatabasePath=$([IO.Path]::Combine($DataDir,'horus-gateway.db'))"
)
if ($CloudSyncUrl) { $envLines += "Gateway__CloudSyncUrl=$CloudSyncUrl" }
$regPath = "HKLM:\SYSTEM\CurrentControlSet\Services\$ServiceName"
New-ItemProperty -Path $regPath -Name "Environment" -PropertyType MultiString -Value $envLines -Force | Out-Null

# 6) Libera a porta LAN no firewall.
if (-not (Get-NetFirewallRule -DisplayName "HorusGateway" -ErrorAction SilentlyContinue)) {
    New-NetFirewallRule -DisplayName "HorusGateway" -Direction Inbound -Protocol TCP -LocalPort $Port -Action Allow | Out-Null
    Write-Host "[Quack Gateway] Regra de firewall criada para a porta $Port."
}

# 7) Sobe o servico.
Start-Service -Name $ServiceName
Start-Sleep -Seconds 2
$svc = Get-Service -Name $ServiceName

Write-Host "======================================================" -ForegroundColor Green
Write-Host " Quack Gateway instalado como Servico do Windows"
Write-Host " Servico : $ServiceName ($($svc.Status))"
Write-Host " Empresa : $CompanyId    Loja: $StoreId    Porta: $Port"
Write-Host " Painel  : http://localhost:$Port/"
Write-Host " LAN     : http://<IP-desta-maquina>:$Port/"
Write-Host " Dados   : $DataDir\horus-gateway.db"
Write-Host "------------------------------------------------------"
Write-Host " Parar    : sc.exe stop $ServiceName"
Write-Host " Iniciar  : sc.exe start $ServiceName"
Write-Host " Remover  : sc.exe delete $ServiceName (pare antes)"
Write-Host "======================================================" -ForegroundColor Green
Write-Host " ATUALIZACAO CONTROLADA: antes de parar/atualizar, confira em" -ForegroundColor Yellow
Write-Host " http://localhost:$Port/  que 'Eventos pendentes' esteja ZERO." -ForegroundColor Yellow
