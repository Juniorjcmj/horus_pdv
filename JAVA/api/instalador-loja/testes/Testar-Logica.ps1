<#
.SYNOPSIS
    Testa a logica pura do instalador (sem SQL Server, sem Administrador, sem tocar no sistema):
    segredos, loja.env, backup-estado.json, retencao de 7 backups, sub-rede, sintaxe dos scripts e -WhatIf.
.PARAMETER Temp
    Pasta de trabalho (criada se nao existir). Padrao: pasta temporaria nova.
#>
param([string]$Temp = (Join-Path ([IO.Path]::GetTempPath()) ('quack-teste-' + [guid]::NewGuid().ToString('N').Substring(0, 8))))
$ErrorActionPreference = 'Stop'
$raiz = Split-Path -Parent $PSScriptRoot
. (Join-Path $raiz 'lib\comum.ps1')
New-Item -ItemType Directory -Path $Temp -Force | Out-Null

$falhas = 0
function Confirmar([string]$Nome, [bool]$Condicao, [string]$Detalhe = '') {
    if ($Condicao) { Write-Host "[ OK ] $Nome" -ForegroundColor Green }
    else { Write-Host "[FALHA] $Nome $Detalhe" -ForegroundColor Red; $script:falhas++ }
}

# --- sintaxe de todos os .ps1 ---
foreach ($f in Get-ChildItem -Path $raiz -Recurse -Filter *.ps1) {
    $erros = $null
    [void][System.Management.Automation.Language.Parser]::ParseFile($f.FullName, [ref]$null, [ref]$erros)
    Confirmar "sintaxe $($f.Name)" ($erros.Count -eq 0) (($erros | ForEach-Object { $_.Message }) -join '; ')
    $bytes = [IO.File]::ReadAllBytes($f.FullName)
    Confirmar "ASCII puro $($f.Name) (PS 5.1 le .ps1 sem BOM como ANSI)" (-not ($bytes | Where-Object { $_ -gt 127 }))
}

# --- segredos ---
$s1 = New-SegredoAleatorio -Bytes 48; $s2 = New-SegredoAleatorio -Bytes 48
Confirmar 'segredo de 48 bytes tem 64 caracteres base64url' ($s1.Length -eq 64 -and $s1 -match '^[A-Za-z0-9_-]+$') $s1.Length
Confirmar 'dois segredos diferem' ($s1 -ne $s2)
$ok = $true
1..50 | ForEach-Object {
    $p = New-SenhaSql
    if ($p.Length -ne 24 -or $p -cnotmatch '[A-Z]' -or $p -cnotmatch '[a-z]' -or $p -notmatch '\d' -or $p -notmatch '[#%*+_-]' -or $p -match '[;''" ]') { $ok = $false }
}
Confirmar '50 senhas SQL atendem a politica (maiuscula, minuscula, digito, simbolo, sem aspas/;)' $ok

# --- loja.env ---
$env1 = [ordered]@{ A = 'x'; B = 'C:\pasta com\barra'; 'quack.sync.nuvem-url' = 'https://x'; C = " espaco" }
$conteudo = ConvertTo-ConteudoLojaEnv -Valores $env1 -Cabecalho "linha 1`nlinha 2"
$arqEnv = Join-Path $Temp 'config\loja.env'
Write-ArquivoTextoUtf8 -Caminho $arqEnv -Conteudo $conteudo
$bytes = [IO.File]::ReadAllBytes($arqEnv)
Confirmar 'loja.env sem BOM' (-not ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB))
Confirmar 'loja.env escapa barra invertida' ($conteudo -match [regex]::Escape('B=C:\\pasta com\\barra'))
Confirmar 'loja.env escapa espaco inicial' ($conteudo -match 'C=\\ espaco')
Confirmar 'sem arquivo .tmp sobrando' (-not (Test-Path "$arqEnv.tmp"))

# --- backup-estado.json (formato lido por EstadoBackup.java) ---
$est = Join-Path $Temp 'estado\backup-estado.json'
Write-EstadoBackup -Caminho $est -Sucesso $true -Quando ([datetimeoffset]'2026-11-04T02:00:05-03:00')
$bytes = [IO.File]::ReadAllBytes($est)
Confirmar 'estado sem BOM (o Jackson nao le BOM)' ($bytes[0] -eq [byte][char]'{')
$j = Get-Content -LiteralPath $est -Raw | ConvertFrom-Json
Confirmar 'estado ok: sucesso=true, erro nulo, ISO com fuso' ($j.sucesso -eq $true -and $null -eq $j.erro -and $j.ultimoBackupEm -match '^2026-11-04T02:00:05\.000-03:00$') (Get-Content -LiteralPath $est -Raw)
Write-EstadoBackup -Caminho $est -Sucesso $false -Erro 'disco cheio'
$j = Get-Content -LiteralPath $est -Raw | ConvertFrom-Json
Confirmar 'falha preserva ultimoBackupEm do ultimo sucesso' ($j.sucesso -eq $false -and $j.erro -eq 'disco cheio' -and $j.ultimoBackupEm -like '2026-11-04T02:00:05*') (Get-Content -LiteralPath $est -Raw)

# --- retencao de 7 ---
$pasta = Join-Path $Temp 'backups'
New-Item -ItemType Directory -Path $pasta -Force | Out-Null
1..10 | ForEach-Object {
    $n = Get-NomeBackup -Quando ([datetime]'2026-10-01 02:00:00').AddDays($_) -Banco 'QuackLoja'
    Set-Content -LiteralPath (Join-Path $pasta $n) -Value 'x'
}
Set-Content -LiteralPath (Join-Path $pasta 'outro-arquivo.bak') -Value 'x'
Set-Content -LiteralPath (Join-Path $pasta 'quack-outrobanco-20200101-020000.bak') -Value 'x'
$rem = @(Get-BackupsParaRemover -Pasta $pasta -Manter 7 -Banco 'QuackLoja')
Confirmar 'retencao 7: 10 arquivos -> remove 3' ($rem.Count -eq 3) $rem.Count
Confirmar 'remove os 3 MAIS ANTIGOS' ((($rem.Name | Sort-Object) -join ',') -eq ((1..3 | ForEach-Object { Get-NomeBackup -Quando ([datetime]'2026-10-01 02:00:00').AddDays($_) }) -join ','))
$rem | Remove-Item -Force
$restam = @(Get-ChildItem $pasta -Filter 'quack-quackloja-*.bak')
Confirmar 'restam exatamente 7' ($restam.Count -eq 7)
Confirmar 'arquivos de outro nome/banco nao sao tocados' ((Test-Path "$pasta\outro-arquivo.bak") -and (Test-Path "$pasta\quack-outrobanco-20200101-020000.bak"))
Confirmar 'com 7 ou menos nao remove nada' (@(Get-BackupsParaRemover -Pasta $pasta -Manter 7).Count -eq 0)

# --- sub-rede ---
Confirmar 'sub-rede 192.168.1.37/24' ((Get-SubredeCidr -Ip '192.168.1.37' -Prefixo 24) -eq '192.168.1.0/24')
Confirmar 'sub-rede 10.20.30.200/22' ((Get-SubredeCidr -Ip '10.20.30.200' -Prefixo 22) -eq '10.20.28.0/22')
Confirmar 'sub-rede 172.16.5.9/12' ((Get-SubredeCidr -Ip '172.16.5.9' -Prefixo 12) -eq '172.16.0.0/12')
Confirmar 'Test-SubredeValida' ((Test-SubredeValida '192.168.0.0/24') -and -not (Test-SubredeValida '192.168.0.0') -and -not (Test-SubredeValida '300.1.1.0/24') -and -not (Test-SubredeValida '10.0.0.0/31'))

# --- escapes SQL ---
Confirmar 'literal SQL escapa aspas' ((Format-LiteralSql "a'b") -eq "N'a''b'")
Confirmar 'identificador SQL escapa colchete' ((Format-IdentificadorSql 'a]b') -eq '[a]]b]')

# --- -WhatIf dos scripts (nao pode tocar em nada nem conectar) ---
$dest = Join-Path $Temp 'destino-whatif'
$saida = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $raiz 'backup.ps1') -Destino $dest -PastaBackup $pasta -ArquivoEstado $est -Banco QuackLoja -WhatIf 2>&1 | Out-String
Confirmar 'backup.ps1 -WhatIf descreve e nao conecta' ($saida -match 'WhatIf' -and $LASTEXITCODE -eq 0) $saida
Confirmar 'backup.ps1 -WhatIf nao apagou nada' (@(Get-ChildItem $pasta -Filter 'quack-quackloja-*.bak').Count -eq 7)
$saida = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $raiz 'restaurar.ps1') -Destino $dest -Arquivo (Get-ChildItem $pasta -Filter 'quack-quackloja-*.bak')[0].FullName -WhatIf 2>&1 | Out-String
Confirmar 'restaurar.ps1 -WhatIf descreve e nao conecta' ($saida -match 'WhatIf' -and $LASTEXITCODE -eq 0) $saida
$saida = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $raiz 'desinstalar.ps1') -Destino $dest -RemoverBanco -RemoverPasta -WhatIf 2>&1 | Out-String
Confirmar 'desinstalar.ps1 -WhatIf nao altera' ($saida -match 'What if' -and $LASTEXITCODE -eq 0) $saida

# --- backup com banco inacessivel: falha, grava estado, nada apagado ---
$estF = Join-Path $Temp 'estado-falha.json'
$ErrorActionPreference = 'Continue'   # o backup que falha escreve na saida de erro
$null = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $raiz 'backup.ps1') -Destino $dest -PastaBackup $pasta -ArquivoEstado $estF `
    -StringConexao 'Server=127.0.0.1,1;Database=master;User Id=x;Password=y;Connect Timeout=3;TrustServerCertificate=True' 2>&1
$codigo = $LASTEXITCODE
$ErrorActionPreference = 'Stop'
$jf = if (Test-Path $estF) { Get-Content $estF -Raw | ConvertFrom-Json } else { $null }
Confirmar 'backup sem SQL: codigo 1, estado sucesso=false com erro, nada apagado' ($codigo -eq 1 -and $jf -and $jf.sucesso -eq $false -and $jf.erro -and @(Get-ChildItem $pasta -Filter 'quack-quackloja-*.bak').Count -eq 7)

Write-Host ''
if ($falhas -gt 0) { Write-Host "$falhas falha(s)." -ForegroundColor Red; exit 1 }
Write-Host "Tudo certo (pasta de trabalho: $Temp)." -ForegroundColor Green
