<#
.SYNOPSIS
    Backup completo diario do banco da loja (D12). Roda como tarefa agendada (SYSTEM) as 02:00.
.DESCRIPTION
    BACKUP DATABASE ... WITH COMPRESSION, CHECKSUM; confere com RESTORE VERIFYONLY; apaga os mais antigos que a
    retencao (padrao 7) SO depois de um backup bom; grava estado\backup-estado.json, que a API le (propriedade
    quack.sync.backup-estado) para a rota de situacao e para o alerta de falha.
    Em falha: nada e apagado, o estado fica com sucesso=false e o script sai com codigo 1.
    -WhatIf mostra o que faria sem tocar no banco nem nos arquivos.
.PARAMETER Destino
    Pasta da instalacao (le config\instalacao.json). Os demais parametros sobrescrevem o que estiver la (usado nos testes).
#>
[CmdletBinding(SupportsShouldProcess)]
param(
    [string]$Destino = (Split-Path -Parent $PSScriptRoot),
    [string]$StringConexao,
    [string]$Banco,
    [string]$PastaBackup,
    [string]$CaminhoBackupNoSql,
    [string]$ArquivoEstado,
    [int]$Retencao = 0
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib\comum.ps1')

$inst = Read-InstalacaoLoja -Destino $Destino
if (-not $Banco)          { $Banco = if ($inst) { $inst.banco } else { 'QuackLoja' } }
if (-not $PastaBackup)    { $PastaBackup = if ($inst) { $inst.pastaBackup } else { Join-Path $Destino 'backups' } }
if (-not $ArquivoEstado)  { $ArquivoEstado = if ($inst) { $inst.arquivoEstadoBackup } else { Join-Path $Destino 'estado\backup-estado.json' } }
if ($Retencao -lt 1)      { $Retencao = if ($inst -and $inst.retencaoBackups) { [int]$inst.retencaoBackups } else { 7 } }
if (-not $StringConexao)  {
    $servidor = if ($inst) { $inst.servidorSql } else { 'localhost,1433' }
    $StringConexao = Get-StringConexaoSql -Servidor $servidor -Banco 'master'
}
if (-not $CaminhoBackupNoSql) { $CaminhoBackupNoSql = $PastaBackup }

$agora = Get-Date
$nome = Get-NomeBackup -Quando $agora -Banco $Banco
$arquivoLocal = Join-Path $PastaBackup $nome
$arquivoNoSql = if ($CaminhoBackupNoSql -match '^/') { "$($CaminhoBackupNoSql.TrimEnd('/'))/$nome" } else { Join-Path $CaminhoBackupNoSql $nome }
$bd = Format-IdentificadorSql $Banco
$arq = Format-LiteralSql $arquivoNoSql

if (-not $PSCmdlet.ShouldProcess($arquivoNoSql, "BACKUP DATABASE $Banco (retencao $Retencao)")) {
    Write-Host "[WhatIf] Gravaria $arquivoLocal, conferiria com RESTORE VERIFYONLY, manteria os $Retencao mais recentes e atualizaria $ArquivoEstado."
    foreach ($f in (Get-BackupsParaRemover -Pasta $PastaBackup -Manter ($Retencao - 1) -Banco $Banco)) {
        Write-Host "[WhatIf] Apagaria $($f.FullName) (alem da retencao)"
    }
    return
}

try {
    if (-not (Test-Path -LiteralPath $PastaBackup)) { New-Item -ItemType Directory -Path $PastaBackup -Force | Out-Null }
    Write-Host "Backup de $Banco em $arquivoNoSql ..."
    Invoke-SqlComando -StringConexao $StringConexao -TempoLimite 0 -Sql `
        "BACKUP DATABASE $bd TO DISK = $arq WITH COMPRESSION, CHECKSUM, INIT, STATS = 10"
    Invoke-SqlComando -StringConexao $StringConexao -TempoLimite 0 -Sql "RESTORE VERIFYONLY FROM DISK = $arq WITH CHECKSUM"
    if (-not (Test-Path -LiteralPath $arquivoLocal)) { throw "O SQL Server disse que gravou, mas o arquivo nao existe em $arquivoLocal." }
    $tamanho = (Get-Item -LiteralPath $arquivoLocal).Length
    if ($tamanho -le 0) { throw "Arquivo de backup vazio: $arquivoLocal" }

    # Retencao: depois do backup novo, mantem os $Retencao mais recentes (o novo ja esta na pasta).
    foreach ($f in (Get-BackupsParaRemover -Pasta $PastaBackup -Manter $Retencao -Banco $Banco)) {
        Write-Host "Removendo backup antigo: $($f.Name)"
        Remove-Item -LiteralPath $f.FullName -Force
    }
    Write-EstadoBackup -Caminho $ArquivoEstado -Sucesso $true -Quando ([datetimeoffset]::Now)
    Write-Host ("Backup concluido: {0} ({1:N1} MB)." -f $nome, ($tamanho / 1MB))
}
catch {
    $msg = Get-MensagemErro $_.Exception
    Write-Error "Falha no backup: $msg" -ErrorAction Continue
    try { Write-EstadoBackup -Caminho $ArquivoEstado -Sucesso $false -Erro $msg }
    catch { Write-Error "Alem disso, nao consegui gravar $ArquivoEstado : $($_.Exception.Message)" -ErrorAction Continue }
    exit 1
}
