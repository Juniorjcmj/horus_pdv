# Arquivo: GATEWAY\uninstall-service.ps1
# Objetivo: remover o Servico do Windows do Quack Gateway (para e exclui). Mantem os dados
#           (gateway-data/) e os logs por padrao. Requer PowerShell como Administrador.
# Uso:      Clique direito > "Executar com o PowerShell" (como Administrador)
#           ou:  pwsh -File .\uninstall-service.ps1 [-RemoveFirewall] [-RemoveEventSource]
param(
    [string]$ServiceName = "HorusGateway",
    [switch]$RemoveFirewall,
    [switch]$RemoveEventSource
)

$ErrorActionPreference = "Stop"

# Precisa ser Administrador.
$isAdmin = ([Security.Principal.WindowsPrincipal] [Security.Principal.WindowsIdentity]::GetCurrent()
    ).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    Write-Host "[Quack Gateway] Rode este script como Administrador." -ForegroundColor Red
    exit 1
}

$svc = Get-Service -Name $ServiceName -ErrorAction SilentlyContinue
if (-not $svc) {
    Write-Host "[Quack Gateway] Servico '$ServiceName' nao encontrado. Nada a remover." -ForegroundColor Yellow
} else {
    # ATUALIZACAO CONTROLADA: avise se ainda ha eventos nao sincronizados antes de parar.
    try {
        $port = if ($env:GATEWAY_PORT) { $env:GATEWAY_PORT } else { 5080 }
        $scheme = if ($env:GATEWAY_HTTPS_CERT) { "https" } else { "http" }
        $r = Invoke-RestMethod -Uri "$scheme`://localhost:$port/health/update-readiness" -TimeoutSec 3
        if (-not $r.safeToUpdate) {
            Write-Host "[Quack Gateway] ATENCAO: ha $($r.pendingEvents) evento(s) ainda nao sincronizados." -ForegroundColor Red
            $ans = Read-Host "Remover mesmo assim? (digite SIM para continuar)"
            if ($ans -ne "SIM") { Write-Host "Cancelado."; exit 1 }
        }
    } catch {
        # Servico pode ja estar parado/sem responder — segue com a remocao.
    }

    Write-Host "[Quack Gateway] Parando e removendo o servico '$ServiceName'..." -ForegroundColor Cyan
    sc.exe stop $ServiceName | Out-Null
    Start-Sleep -Seconds 2
    sc.exe delete $ServiceName | Out-Null
    Start-Sleep -Seconds 1
    Write-Host "[Quack Gateway] Servico removido. Os dados em gateway-data\ foram preservados."
}

if ($RemoveFirewall) {
    $rule = Get-NetFirewallRule -DisplayName "HorusGateway" -ErrorAction SilentlyContinue
    if ($rule) { $rule | Remove-NetFirewallRule; Write-Host "[Quack Gateway] Regra de firewall removida." }
}

if ($RemoveEventSource) {
    try {
        if ([System.Diagnostics.EventLog]::SourceExists("HorusGateway")) {
            Remove-EventLog -Source "HorusGateway"
            Write-Host "[Quack Gateway] Fonte de Event Log removida."
        }
    } catch {
        Write-Host "[Quack Gateway] Nao foi possivel remover a fonte de Event Log." -ForegroundColor Yellow
    }
}

Write-Host "[Quack Gateway] Concluido." -ForegroundColor Green
