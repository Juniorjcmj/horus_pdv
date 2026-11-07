# Gera o executável Windows do Quack Estoque (QuackEstoque.exe) com o Java embutido.
# A máquina da loja NÃO precisa ter Java instalado.
#
# Uso:   powershell -ExecutionPolicy Bypass -File build-exe.ps1
# Saída (sempre em dist\):
#        dist\QuackEstoque-<versao>-windows.zip     (para levar à loja: descompactar e abrir o .exe)
#        dist\QuackEstoque\QuackEstoque.exe          (pasta do programa; não é trocada se estiver aberto)
# Cada geração sobe a versão (último número) gravada em versao.txt; commite o versao.txt junto.
#
# Requisitos (só na máquina que gera): JDK 21 (jpackage) e Maven. Sem WiX Toolset gera a pasta do
# programa (app-image); com WiX instalado dá para trocar --type por "msi" e gerar um instalador.
$ErrorActionPreference = "Stop"
$dir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $dir

# Versão: a última gerada fica em versao.txt; esta geração usa a seguinte (1.0.4 -> 1.0.5).
$arquivoVersao = Join-Path $dir "versao.txt"
$anterior = if (Test-Path $arquivoVersao) { (Get-Content $arquivoVersao -Raw).Trim() } else { "1.0.0" }
if ($anterior -notmatch '^(\d+)\.(\d+)\.(\d+)$') { throw "versao.txt invalido: '$anterior' (esperado 1.0.0)." }
$version = "$($Matches[1]).$($Matches[2]).$([int]$Matches[3] + 1)"
Write-Output "Versao $anterior -> $version"

$dist = Join-Path $dir "dist"
$saida = Join-Path $dir "target\jpackage-out"
$input = Join-Path $dir "target\jpackage-input"

Write-Output "1/4 Compilando e testando (mvn package)..."
& mvn -o -q -f pom.xml package
if ($LASTEXITCODE -ne 0) { throw "Falha no mvn package." }

Write-Output "2/4 Preparando a entrada do jpackage..."
if (Test-Path $input) { Remove-Item -Recurse -Force $input }
New-Item -ItemType Directory -Force $input | Out-Null
Copy-Item (Join-Path $dir "target\quack-estoque.jar") $input
if (Test-Path $saida) { Remove-Item -Recurse -Force $saida }
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
    --dest $saida
if ($LASTEXITCODE -ne 0) { throw "Falha no jpackage." }

Write-Output "4/4 Compactando para distribuição..."
$zip = Join-Path $dist "QuackEstoque-$version-windows.zip"
if (Test-Path $zip) { Remove-Item -Force $zip }
Compress-Archive -Path (Join-Path $saida "QuackEstoque") -DestinationPath $zip
Set-Content -Path $arquivoVersao -Value $version -Encoding ascii

# A pasta do programa em dist\ só é trocada se não estiver aberta (arquivos presos); o zip já está pronto.
$pasta = Join-Path $dist "QuackEstoque"
$exe = Join-Path $pasta "QuackEstoque.exe"
$aberto = Get-Process -Name QuackEstoque -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq $exe }
if ($aberto) {
    Write-Output "Aviso: o Quack Estoque esta aberto a partir de $pasta; essa pasta ficou na versao anterior."
} else {
    if (Test-Path $pasta) { Remove-Item -Recurse -Force $pasta }
    Move-Item (Join-Path $saida "QuackEstoque") $pasta
}

$size = "{0:N1} MB" -f ((Get-Item $zip).Length / 1MB)
Write-Output "Pronto (versao $version):"
Write-Output "  Pacote:     $zip ($size)"
if (-not $aberto) { Write-Output "  Executável: $exe" }
