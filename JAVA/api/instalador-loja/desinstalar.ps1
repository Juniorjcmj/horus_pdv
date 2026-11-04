<#
.SYNOPSIS
    Remove o servico, a tarefa de backup e as regras de firewall do servidor da loja (D12).
.DESCRIPTION
    Por padrao MANTEM o banco, os backups e a pasta de instalacao (os dados da loja). Para apagar:
      -RemoverBanco  : DROP DATABASE (o SQL Server Express e a instancia continuam instalados);
      -RemoverPasta  : apaga a pasta de instalacao, inclusive config\loja.env e os backups.
    Peca confirmacao ao dono antes: sem o banco e sem o backup nao ha como voltar. -WhatIf lista o que seria feito.
#>
[CmdletBinding(SupportsShouldProcess, ConfirmImpact = 'High')]
param(
    [string]$Destino = (Split-Path -Parent $PSScriptRoot),
    [string]$Servico = 'QuackLojaApi',
    [string]$Tarefa = 'QuackLoja-Backup',
    [switch]$RemoverBanco,
    [switch]$RemoverPasta
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib\comum.ps1')

$inst = Read-InstalacaoLoja -Destino $Destino
$banco = if ($inst) { $inst.banco } else { 'QuackLoja' }
$servidor = if ($inst) { $inst.servidorSql } else { 'localhost,1433' }
$regras = 'Quack Loja - API (somente sub-rede local)', 'Quack Loja - SQL Server (bloqueado fora da maquina)'

if ($PSCmdlet.ShouldProcess($Servico, 'Parar e remover o servico')) {
    $svc = Get-Service -Name $Servico -ErrorAction SilentlyContinue
    if ($svc) {
        Stop-Service -Name $Servico -Force -ErrorAction SilentlyContinue
        $exe = Join-Path $Destino "servico\$Servico.exe"
        if (Test-Path -LiteralPath $exe) { & $exe uninstall } else { & sc.exe delete $Servico | Out-Null }
    } else { Write-Host "Servico $Servico nao existe." }
}
if ($PSCmdlet.ShouldProcess($Tarefa, 'Remover a tarefa agendada de backup')) {
    Unregister-ScheduledTask -TaskName $Tarefa -Confirm:$false -ErrorAction SilentlyContinue
}
if ($PSCmdlet.ShouldProcess(($regras -join '; '), 'Remover as regras de firewall')) {
    foreach ($r in $regras) { Get-NetFirewallRule -DisplayName $r -ErrorAction SilentlyContinue | Remove-NetFirewallRule }
}
if ($RemoverBanco -and $PSCmdlet.ShouldProcess($banco, 'DROP DATABASE (apaga os dados da loja)')) {
    $con = Get-StringConexaoSql -Servidor $servidor -Banco 'master'
    $bd = Format-IdentificadorSql $banco
    Invoke-SqlComando -StringConexao $con -Sql "IF DB_ID($(Format-LiteralSql $banco)) IS NOT NULL BEGIN ALTER DATABASE $bd SET SINGLE_USER WITH ROLLBACK IMMEDIATE; DROP DATABASE $bd; END"
}
if ($RemoverPasta -and $PSCmdlet.ShouldProcess($Destino, 'Apagar a pasta de instalacao (segredos e backups inclusive)')) {
    # O script esta dentro da pasta: a remocao e adiada para um processo filho.
    Start-Process -WindowStyle Hidden -FilePath powershell.exe -ArgumentList "-NoProfile -Command Start-Sleep 3; Remove-Item -LiteralPath '$Destino' -Recurse -Force"
}
Write-Host 'Desinstalacao concluida. O SQL Server Express nao foi removido (use Aplicativos e Recursos se quiser).'
