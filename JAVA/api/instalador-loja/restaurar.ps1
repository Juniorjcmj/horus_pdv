<#
.SYNOPSIS
    Restaura um backup do banco da loja (D12). Para o servico, restaura, pede a recuperacao e sobe o servico.
.DESCRIPTION
    1. confere o arquivo (RESTORE VERIFYONLY WITH CHECKSUM);
    2. para o servico QuackLojaApi;
    3. poe o banco em SINGLE_USER, RESTORE DATABASE ... WITH REPLACE, volta a MULTI_USER;
    4. deixa o marcador config\recuperar.pendente e sobe o servico. A API, ao subir, executa a RECUPERACAO sozinha:
       puxa da nuvem tudo o que o backup nao tem (vendas, notas, movimentos e cadastros feitos depois dele), realinha as
       versoes da sincronizacao, avanca a numeracao e SO ENTAO volta a aceitar vendas, a enviar e a conferir.

    POR QUE: o backup e mais antigo que a nuvem. A conferencia trata a nuvem como reflexo da loja nas tabelas "LOJA" e
    APAGARIA da nuvem as vendas posteriores ao backup. Com a recuperacao nao e preciso desligar a conferencia nem decidir
    nada: ela e religada pela propria API. Sem internet, a recuperacao fica pendente (o servidor nao vende nem confere)
    e repete ate conseguir. Acompanhe por GET /api/loja-sync/situacao ("recuperando").
    Pela linha de comando (alternativa ao marcador): java -jar quack-pdv-api.jar --spring.profiles.active=loja
    --quack.sync.recuperacao.executar=true (codigo 0 = concluida).
.PARAMETER Forcar
    Nao pede confirmacao (o RESTORE substitui o banco atual).
.PARAMETER SemRecuperacao
    EXCECAO: nao deixa o marcador. Use so quando se sabe que a nuvem NAO tem nada alem do backup (ex.: ambiente de teste).
    A conferencia diaria pode apagar da nuvem o que o backup nao tem.
.PARAMETER ReativarConferencia
    Legado: lojas restauradas pela versao antiga do script tem quack.sync.conferencia-ativa=false no loja.env. Este
    parametro remove essa linha (a conferencia volta ao padrao, ligada) e nao restaura nada.
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
    [switch]$SemRecuperacao,
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

$marcador = Join-Path $Destino 'config\recuperar.pendente'

# Legado: tira do loja.env a linha que a versao antiga do script gravava (a conferencia agora espera a recuperacao).
function Remove-ConferenciaDesligada {
    if (-not (Test-Path -LiteralPath $envArq)) { return }
    $linhas = @(Get-Content -LiteralPath $envArq -Encoding UTF8)
    $novas = @($linhas | Where-Object { $_ -notmatch "^quack[.]sync[.]conferencia-ativa=" })
    if ($novas.Count -ne $linhas.Count) { Write-ArquivoTextoUtf8 -Caminho $envArq -Conteudo (($novas -join "`r`n") + "`r`n") }
}

if ($ReativarConferencia) {
    if ($PSCmdlet.ShouldProcess($envArq, 'Remover o desligamento antigo da conferencia diaria')) {
        Remove-ConferenciaDesligada
        Write-Host 'Conferencia no padrao (ligada). Reinicie o servico para valer: Restart-Service QuackLojaApi'
    }
    return
}

if (-not $PSCmdlet.ShouldProcess("$Banco <- $Arquivo", 'RESTORE DATABASE (substitui o banco atual)')) {
    Write-Host "[WhatIf] Conferiria $Arquivo, pararia o servico $Servico, restauraria $Banco, deixaria o marcador da recuperacao e subiria o servico."
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
    Remove-ConferenciaDesligada
    if ($SemRecuperacao) {
        Write-Warning 'Banco restaurado SEM recuperacao (-SemRecuperacao): a conferencia diaria pode apagar da nuvem o que o backup nao tem.'
    } else {
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $marcador) | Out-Null
        Write-ArquivoTextoUtf8 -Caminho $marcador -Conteudo "restaurado em $((Get-Date).ToString('o')) de $nomeArq`r`n"
        Write-Host 'Banco restaurado. RECUPERACAO pedida: ao subir, a API puxa da nuvem o que falta; so depois vende, envia e confere.'
    }
}
finally {
    if ($servicoExiste) {
        Write-Host "Subindo o servico $Servico ..."
        Start-Service -Name $Servico
    }
}
