<#
.SYNOPSIS
    Restaura um backup do banco da loja (D12). Para o servico, restaura, conferencia desligada, sobe o servico.
.DESCRIPTION
    1. confere o arquivo (RESTORE VERIFYONLY WITH CHECKSUM);
    2. para o servico QuackLojaApi;
    3. poe o banco em SINGLE_USER, RESTORE DATABASE ... WITH REPLACE, volta a MULTI_USER;
    4. DESLIGA a conferencia diaria (quack.sync.conferencia-ativa=false em config\loja.env) e sobe o servico.

    POR QUE DESLIGAR A CONFERENCIA: o backup e mais antigo que a nuvem. A nuvem ja recebeu vendas feitas depois dele;
    como a conferencia trata a nuvem como reflexo da loja nas tabelas "LOJA", ela APAGARIA essas vendas da nuvem.
    Depois de restaurar, siga a secao "Restauracao" do CHECKLIST.md antes de religar (-ReativarConferencia).
.PARAMETER Forcar
    Nao pede confirmacao (o RESTORE substitui o banco atual).
.PARAMETER Arquivo
    Backup a restaurar. Sem o parametro, usa o mais recente da pasta de backups.
#>
[CmdletBinding(SupportsShouldProcess, ConfirmImpact = 'High')]
param(
    [string]$Destino = (Split-Path -Parent $PSScriptRoot),
    [string]$Arquivo,
    [string]$StringConexao,
    [string]$Banco,
    [string]$Servico = 'QuackLojaApi',
    [string]$CaminhoBackupNoSql,
    [switch]$SemServico,
    [switch]$ReativarConferencia,
    [switch]$Forcar
)
$ErrorActionPreference = 'Stop'
if ($Forcar) { $ConfirmPreference = 'None' }   # sem a pergunta de confirmacao (uso em teste/automacao)
. (Join-Path $PSScriptRoot 'lib\comum.ps1')

$inst = Read-InstalacaoLoja -Destino $Destino
if (-not $Banco)         { $Banco = if ($inst) { $inst.banco } else { 'QuackLoja' } }
$pasta = if ($inst) { $inst.pastaBackup } else { Join-Path $Destino 'backups' }
if (-not $StringConexao) {
    $servidor = if ($inst) { $inst.servidorSql } else { 'localhost,1433' }
    $StringConexao = Get-StringConexaoSql -Servidor $servidor -Banco 'master'
}
if (-not $CaminhoBackupNoSql) { $CaminhoBackupNoSql = $pasta }

if (-not $Arquivo) {
    $ultimo = Get-ChildItem -LiteralPath $pasta -Filter ('quack-{0}-*.bak' -f $Banco.ToLowerInvariant()) -File -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending | Select-Object -First 1
    if (-not $ultimo) { throw "Nenhum backup encontrado em $pasta. Informe -Arquivo." }
    $Arquivo = $ultimo.FullName
}
if (-not (Test-Path -LiteralPath $Arquivo)) { throw "Arquivo de backup nao encontrado: $Arquivo" }
$nomeArq = Split-Path -Leaf $Arquivo
$arquivoNoSql = if ($CaminhoBackupNoSql -match '^/') { "$($CaminhoBackupNoSql.TrimEnd('/'))/$nomeArq" } else { Join-Path $CaminhoBackupNoSql $nomeArq }
$bd = Format-IdentificadorSql $Banco
$arq = Format-LiteralSql $arquivoNoSql
$envArq = Join-Path $Destino 'config\loja.env'

function Set-ConferenciaAtiva([bool]$ativa) {
    if (-not (Test-Path -LiteralPath $envArq)) { Write-Warning "loja.env nao encontrado em $envArq; ajuste quack.sync.conferencia-ativa na mao."; return }
    $linhas = @(Get-Content -LiteralPath $envArq -Encoding UTF8 | Where-Object { $_ -notmatch '^quack\.sync\.conferencia-ativa=' })
    $linhas += "quack.sync.conferencia-ativa=$($ativa.ToString().ToLowerInvariant())"
    Write-ArquivoTextoUtf8 -Caminho $envArq -Conteudo (($linhas -join "`r`n") + "`r`n")
}

if ($ReativarConferencia) {
    if ($PSCmdlet.ShouldProcess($envArq, 'Religar a conferencia diaria')) {
        Set-ConferenciaAtiva $true
        Write-Host 'Conferencia religada. Reinicie o servico para valer: Restart-Service QuackLojaApi'
    }
    return
}

if (-not $PSCmdlet.ShouldProcess("$Banco <- $Arquivo", 'RESTORE DATABASE (substitui o banco atual)')) {
    Write-Host "[WhatIf] Conferiria $Arquivo, pararia o servico $Servico, restauraria $Banco, desligaria a conferencia e subiria o servico."
    return
}

Write-Host "Conferindo $Arquivo ..."
Invoke-SqlComando -StringConexao $StringConexao -TempoLimite 0 -Sql "RESTORE VERIFYONLY FROM DISK = $arq WITH CHECKSUM"

$servicoExiste = -not $SemServico -and [bool](Get-Service -Name $Servico -ErrorAction SilentlyContinue)
if ($servicoExiste) {
    Write-Host "Parando o servico $Servico ..."
    Stop-Service -Name $Servico -Force
}
try {
    Write-Host "Restaurando $Banco ..."
    $existe = Invoke-SqlComando -StringConexao $StringConexao -Escalar -Sql "SELECT DB_ID($(Format-LiteralSql $Banco))"
    if ($existe -isnot [DBNull] -and $null -ne $existe) {
        Invoke-SqlComando -StringConexao $StringConexao -Sql "ALTER DATABASE $bd SET SINGLE_USER WITH ROLLBACK IMMEDIATE"
    }
    try {
        Invoke-SqlComando -StringConexao $StringConexao -TempoLimite 0 -Sql "RESTORE DATABASE $bd FROM DISK = $arq WITH REPLACE, CHECKSUM, STATS = 10"
    } finally {
        Invoke-SqlComando -StringConexao $StringConexao -Sql "IF DB_ID($(Format-LiteralSql $Banco)) IS NOT NULL ALTER DATABASE $bd SET MULTI_USER"
    }
    Set-ConferenciaAtiva $false
    Write-Host 'Banco restaurado. Conferencia diaria DESLIGADA (veja o CHECKLIST.md antes de religar).'
}
finally {
    if ($servicoExiste) {
        Write-Host "Subindo o servico $Servico ..."
        Start-Service -Name $Servico
    }
}
