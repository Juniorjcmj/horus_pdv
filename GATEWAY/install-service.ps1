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
    [string]$CloudSyncUrl = $env:GATEWAY_CLOUD_SYNC_URL,
    # Token da loja (Configuracoes > Token do Gateway, formato qgw_...) e endereco da API da nuvem.
    [string]$CloudToken  = $env:GATEWAY_CLOUD_TOKEN,
    [string]$CloudApiUrl = $(if ($env:GATEWAY_CLOUD_API_URL) { $env:GATEWAY_CLOUD_API_URL } else { "https://api-pdv.quacksistemas.com.br" }),
    # Use -SkipPublish quando a pasta publish-service\ ja vem pronta (ex.: pendrive na visita ao cliente):
    # dispensa o .NET SDK e a internet na maquina do cliente — so registra o servico.
    [switch]$SkipPublish
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

# 2+3) Publicacao self-contained (win-x64). Com -SkipPublish, usa a pasta publish-service\ que ja veio
# pronta (pendrive) — nao precisa de .NET SDK nem internet na maquina do cliente.
if ($SkipPublish) {
    if (-not (Test-Path $Exe)) {
        Write-Host "[Quack Gateway] -SkipPublish: '$Exe' nao encontrado." -ForegroundColor Red
        Write-Host "               Copie a pasta 'publish-service' (gerada no seu PC) para junto deste script."
        exit 1
    }
    Write-Host "[Quack Gateway] Usando build pronto em publish-service\ (sem publicar)." -ForegroundColor Cyan
} else {
    if (-not (Get-Command dotnet -ErrorAction SilentlyContinue)) {
        Write-Host "[Quack Gateway] .NET SDK 8 nao encontrado. Instale em:" -ForegroundColor Red
        Write-Host "               https://dotnet.microsoft.com/download/dotnet/8.0"
        Write-Host "               (ou gere a pasta publish-service no seu PC e rode com -SkipPublish)"
        exit 1
    }
    Write-Host "[Quack Gateway] Publicando (self-contained win-x64)..." -ForegroundColor Cyan
    dotnet publish $ProjectDir -c Release -r win-x64 --self-contained true `
        -p:PublishSingleFile=false -o $PublishDir
    if (-not (Test-Path $Exe)) { throw "Publicacao falhou: $Exe nao encontrado." }
}

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

# HTTPS opcional (recomendado com um host, ex.: quack-gateway.local) para evitar bloqueio de
# conteudo misto quando o terminal roda em HTTPS. Defina GATEWAY_HTTPS_CERT (.pfx) antes de instalar.
$Scheme = "http"
if ($env:GATEWAY_HTTPS_CERT) { $Scheme = "https" }

# Variaveis de ambiente do servico (HKLM). O ASP.NET Core le a secao Gateway por Gateway__*.
$envLines = @(
    "ASPNETCORE_ENVIRONMENT=Production",
    "ASPNETCORE_URLS=$Scheme`://0.0.0.0:$Port",
    "Gateway__CompanyId=$CompanyId",
    "Gateway__StoreId=$StoreId",
    "Gateway__DatabasePath=$([IO.Path]::Combine($DataDir,'horus-gateway.db'))"
)
if ($CloudSyncUrl) { $envLines += "Gateway__CloudSyncUrl=$CloudSyncUrl" }
if ($CloudToken) {
    if (-not $CloudToken.StartsWith("qgw_")) {
        Write-Host "[Quack Gateway] -CloudToken deve comecar com 'qgw_' (gere em Configuracoes > Token do Gateway)." -ForegroundColor Red
        exit 1
    }
    $envLines += "Gateway__CloudSyncToken=$CloudToken"
    $envLines += "Gateway__CloudApiBaseUrl=$CloudApiUrl"
} else {
    Write-Host "[Quack Gateway] Sem -CloudToken: o Gateway funciona na LAN, mas nao conversa com a nuvem." -ForegroundColor Yellow
}
if ($env:GATEWAY_HTTPS_CERT) {
    $envLines += "ASPNETCORE_Kestrel__Certificates__Default__Path=$($env:GATEWAY_HTTPS_CERT)"
    if ($env:GATEWAY_HTTPS_CERT_PASSWORD) {
        $envLines += "ASPNETCORE_Kestrel__Certificates__Default__Password=$($env:GATEWAY_HTTPS_CERT_PASSWORD)"
    }
}
$regPath = "HKLM:\SYSTEM\CurrentControlSet\Services\$ServiceName"
New-ItemProperty -Path $regPath -Name "Environment" -PropertyType MultiString -Value $envLines -Force | Out-Null

# Recuperacao automatica: reinicia o servico em caso de falha (5s, 10s, 30s); zera o contador por dia.
# failureflag=1 tambem aciona a recuperacao quando o processo sai com codigo != 0.
sc.exe failure $ServiceName reset= 86400 actions= restart/5000/restart/10000/restart/30000 | Out-Null
sc.exe failureflag $ServiceName 1 | Out-Null

# Fonte do Visualizador de Eventos (precisa de admin — por isso e criada aqui, na instalacao).
try {
    if (-not [System.Diagnostics.EventLog]::SourceExists("HorusGateway")) {
        New-EventLog -LogName Application -Source "HorusGateway" -ErrorAction Stop
        Write-Host "[Quack Gateway] Fonte de Event Log 'HorusGateway' criada."
    }
} catch {
    Write-Host "[Quack Gateway] Nao foi possivel criar a fonte de Event Log (logs irao para arquivo)." -ForegroundColor Yellow
}

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
Write-Host " Servico : $ServiceName ($($svc.Status)) — inicio automatico + recuperacao no crash"
Write-Host " Empresa : $CompanyId    Loja: $StoreId    Porta: $Port ($Scheme)"
Write-Host " Painel  : $Scheme`://localhost:$Port/"
Write-Host " LAN     : $Scheme`://<IP-desta-maquina>:$Port/"
Write-Host " Dados   : $DataDir\horus-gateway.db"
Write-Host " Logs    : $PublishDir\logs\gateway-YYYY-MM-DD.log (+ Visualizador de Eventos)"
Write-Host "------------------------------------------------------"
Write-Host " Parar    : sc.exe stop $ServiceName"
Write-Host " Iniciar  : sc.exe start $ServiceName"
Write-Host " Remover  : .\uninstall-service.ps1 (como Administrador)"
Write-Host "======================================================" -ForegroundColor Green
Write-Host " ATUALIZACAO CONTROLADA: antes de parar/atualizar, confira em" -ForegroundColor Yellow
Write-Host " http://localhost:$Port/  que 'Eventos pendentes' esteja ZERO." -ForegroundColor Yellow
