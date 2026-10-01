# Arquivo: GATEWAY\setup-https.ps1
# Objetivo: gerar um certificado para o Quack Gateway servir em HTTPS na LAN, evitando o bloqueio de
#           "conteudo misto" quando o sistema do cliente roda em HTTPS. Gera:
#             - certs\quack-gateway.pfx  -> usado pelo Gateway (Kestrel) para servir HTTPS
#             - certs\quack-gateway.cer  -> instalar nos TERMINAIS (Autoridades Confiaveis) p/ confiar no cert
# Uso (como Administrador, no PC do Gateway):
#   pwsh -File .\setup-https.ps1 -IpAddress 192.168.0.10 -PfxPassword "umaSenhaForte"
#   (opcional) -DnsName quack-gateway.local  -Years 5
param(
    [string]$IpAddress = "",
    [string]$DnsName   = "quack-gateway.local",
    [string]$PfxPassword = "",
    [int]$Years = 5,
    [string]$OutDir = "$PSScriptRoot\certs"
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($PfxPassword)) {
    Write-Host "[Quack Gateway] Informe -PfxPassword (a mesma senha vai no GATEWAY_HTTPS_CERT_PASSWORD)." -ForegroundColor Red
    exit 1
}

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

# Monta o SAN (Subject Alternative Name) com o DNS e, se informado, o IP — assim o cert vale tanto para
# https://quack-gateway.local:5443 quanto para https://<IP>:5443.
$sanParts = @("DNS=$DnsName")
if ($IpAddress) { $sanParts += "IPAddress=$IpAddress" }
$san = "2.5.29.17={text}&" + ($sanParts -join "&")

Write-Host "[Quack Gateway] Gerando certificado para: $($sanParts -join ', ')" -ForegroundColor Cyan
$cert = New-SelfSignedCertificate `
    -Subject "CN=Quack Gateway" `
    -TextExtension @($san) `
    -KeyUsage DigitalSignature, KeyEncipherment `
    -KeyAlgorithm RSA -KeyLength 2048 `
    -NotAfter (Get-Date).AddYears($Years) `
    -CertStoreLocation "Cert:\CurrentUser\My" `
    -FriendlyName "Quack Gateway LAN"

$pfxPath = Join-Path $OutDir "quack-gateway.pfx"
$cerPath = Join-Path $OutDir "quack-gateway.cer"
$secure  = ConvertTo-SecureString -String $PfxPassword -Force -AsPlainText

Export-PfxCertificate -Cert $cert -FilePath $pfxPath -Password $secure | Out-Null
Export-Certificate   -Cert $cert -FilePath $cerPath | Out-Null

# Limpa o cert do repositorio pessoal (o Gateway usa o .pfx em arquivo, nao o store).
Remove-Item "Cert:\CurrentUser\My\$($cert.Thumbprint)" -Force -ErrorAction SilentlyContinue

Write-Host "======================================================" -ForegroundColor Green
Write-Host " Certificado gerado:"
Write-Host "  PFX (Gateway) : $pfxPath"
Write-Host "  CER (terminais): $cerPath"
Write-Host "------------------------------------------------------"
Write-Host " 1) Instale o Gateway em HTTPS (como Administrador):"
Write-Host "      `$env:GATEWAY_HTTPS_CERT='$pfxPath'"
Write-Host "      `$env:GATEWAY_HTTPS_CERT_PASSWORD='<sua senha>'"
Write-Host "      `$env:GATEWAY_PORT=5443"
Write-Host "      .\install-service.ps1 -SkipPublish -CompanyId <EMPRESA> -StoreId loja-01"
Write-Host " 2) Em CADA terminal, confie no certificado (duplo-clique no .cer >"
Write-Host "    Instalar > Maquina Local > 'Autoridades de Certificacao Raiz Confiaveis')."
if ($IpAddress) {
    Write-Host " 3) Cadastre na Cloud (Configuracoes > Local Gateway): https://$IpAddress`:5443"
} else {
    Write-Host " 3) Garanta que '$DnsName' resolve para o IP do Gateway (DNS/hosts) e cadastre"
    Write-Host "    na Cloud: https://$DnsName`:5443"
}
Write-Host "======================================================" -ForegroundColor Green
