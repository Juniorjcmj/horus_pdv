# Gera o executável Windows do Quack Estoque (QuackEstoque.exe) com o Java embutido.
# A máquina da loja NÃO precisa ter Java instalado.
#
# Uso:   powershell -ExecutionPolicy Bypass -File build-exe.ps1 [-Dest <pasta>]
# Saída: dist\QuackEstoque\QuackEstoque.exe          (pasta do programa)
#        dist\QuackEstoque-<versao>-windows.zip     (para levar à loja: descompactar e abrir o .exe)
#
# Requisitos (só na máquina que gera): JDK 21 (jpackage) e Maven. Sem WiX Toolset gera a pasta do
# programa (app-image); com WiX instalado dá para trocar --type por "msi" e gerar um instalador.
param([string]$Dest = "")
$ErrorActionPreference = "Stop"
$dir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $dir

$version = "1.0.0"
$dist = if ($Dest) { [System.IO.Path]::GetFullPath($Dest) } else { Join-Path $dir "dist" }

# Com o aplicativo aberto a partir desta pasta os arquivos ficam presos: para antes de apagar qualquer coisa.
$alvo = Join-Path $dist "QuackEstoque\QuackEstoque.exe"
$aberto = Get-Process -Name QuackEstoque -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq $alvo }
if ($aberto) { throw "Feche o Quack Estoque ($alvo) antes de gerar o executavel, ou use -Dest <outra pasta>." }
$input = Join-Path $dir "target\jpackage-input"

Write-Output "1/4 Compilando e testando (mvn package)..."
& mvn -o -q -f pom.xml package
if ($LASTEXITCODE -ne 0) { throw "Falha no mvn package." }

Write-Output "2/4 Preparando a entrada do jpackage..."
if (Test-Path $input) { Remove-Item -Recurse -Force $input }
New-Item -ItemType Directory -Force $input | Out-Null
Copy-Item (Join-Path $dir "target\quack-estoque.jar") $input
if (Test-Path (Join-Path $dist "QuackEstoque")) { Remove-Item -Recurse -Force (Join-Path $dist "QuackEstoque") }
New-Item -ItemType Directory -Force $dist | Out-Null

# Módulos do Java embutido: os que o jdeps encontra no jar + os usados só em tempo de execução
# (pt-BR em datas/moeda, HTTPS com curvas elípticas, codificações e log).
$modules = "java.base,java.desktop,java.net.http,java.sql,java.xml,java.logging,jdk.jfr,jdk.unsupported," +
           "jdk.localedata,jdk.crypto.ec,jdk.charsets,jdk.zipfs"

Write-Output "3/4 Gerando o executável com jpackage..."
& jpackage --type app-image `
    --name QuackEstoque `
    --app-version $version `
    --vendor "Quack Sistemas" `
    --description "Quack Estoque - entradas, inventário, etiquetas e compras" `
    --icon (Join-Path $dir "quack-estoque.ico") `
    --input $input `
    --main-jar quack-estoque.jar `
    --main-class br.com.quack.estoque.Launcher `
    --add-modules $modules `
    --jlink-options "--strip-debug --no-man-pages --no-header-files --compress=zip-6" `
    --java-options "-Dfile.encoding=UTF-8" `
    --java-options "-Duser.language=pt -Duser.country=BR" `
    --dest $dist
if ($LASTEXITCODE -ne 0) { throw "Falha no jpackage." }

Write-Output "4/4 Compactando para distribuição..."
$zip = Join-Path $dist "QuackEstoque-$version-windows.zip"
if (Test-Path $zip) { Remove-Item -Force $zip }
Compress-Archive -Path (Join-Path $dist "QuackEstoque") -DestinationPath $zip

$exe = Join-Path $dist "QuackEstoque\QuackEstoque.exe"
$size = "{0:N1} MB" -f ((Get-Item $zip).Length / 1MB)
Write-Output "Pronto:"
Write-Output "  Executável: $exe"
Write-Output "  Pacote:     $zip ($size)"
