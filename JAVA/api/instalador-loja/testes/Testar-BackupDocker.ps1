<#
.SYNOPSIS
    Prova backup.ps1 e restaurar.ps1 contra um SQL Server 2022 em container Docker (nada e instalado na maquina).
.DESCRIPTION
    Sobe o container com a pasta de backups montada, cria um banco de teste, roda o backup 9 vezes com retencao 7,
    confere o arquivo, o RESTORE VERIFYONLY, o backup-estado.json e a retencao; depois altera os dados, restaura e
    confere que os dados voltaram e que o marcador da recuperacao foi deixado (e a linha antiga que desligava a conferencia saiu do loja.env). Remove o container ao final.
#>
param(
    [string]$Temp = (Join-Path ([IO.Path]::GetTempPath()) ('quack-bkp-' + [guid]::NewGuid().ToString('N').Substring(0, 8))),
    [int]$Porta = 14331,
    [string]$Imagem = 'mcr.microsoft.com/mssql/server:2022-latest',
    # Edicao do SQL no container: Express e a da loja (nao aceita backup com compressao); Developer aceita.
    [string]$Edicao = 'Express'
)
$ErrorActionPreference = 'Stop'
$raiz = Split-Path -Parent $PSScriptRoot
. (Join-Path $raiz 'lib\comum.ps1')
$senha = New-SenhaSql
$nome = 'quack-teste-backup-' + [guid]::NewGuid().ToString('N').Substring(0, 6)
$pastaBackups = Join-Path $Temp 'backups'
New-Item -ItemType Directory -Path $pastaBackups -Force | Out-Null
$falhas = 0
function Confirmar([string]$Nome, [bool]$Condicao, [string]$Detalhe = '') {
    if ($Condicao) { Write-Host "[ OK ] $Nome" -ForegroundColor Green } else { Write-Host "[FALHA] $Nome $Detalhe" -ForegroundColor Red; $script:falhas++ }
}
function Invoke-Docker { $a = $ErrorActionPreference; $ErrorActionPreference = 'Continue'; & docker @args 2>&1 | Out-Null; $ErrorActionPreference = $a; if ($LASTEXITCODE -ne 0) { throw "docker $($args -join ' ') falhou ($LASTEXITCODE)" } }

try {
    Write-Host "Subindo $Imagem ($Edicao) como $nome na porta $Porta ..."
    Invoke-Docker run -d --name $nome -e ACCEPT_EULA=Y -e "MSSQL_PID=$Edicao" -e "MSSQL_SA_PASSWORD=$senha" -p "${Porta}:1433" -v "${pastaBackups}:/var/opt/mssql/backups" $Imagem
    $con = "Server=127.0.0.1,$Porta;Database=master;User Id=sa;Password=$senha;TrustServerCertificate=True;Encrypt=True;Connect Timeout=5"
    $pronto = $false
    foreach ($i in 1..40) { try { Invoke-SqlComando -StringConexao $con -Sql 'SELECT 1' -TempoLimite 5; $pronto = $true; break } catch { Start-Sleep -Seconds 3 } }
    if (-not $pronto) { throw 'SQL Server nao ficou pronto.' }
    # o container escreve como mssql: a pasta montada precisa aceitar escrita
    Invoke-Docker exec -u 0 $nome chmod 777 /var/opt/mssql/backups

    Invoke-SqlComando -StringConexao $con -Sql 'CREATE DATABASE QuackLoja'
    $conBd = $con.Replace('Database=master', 'Database=QuackLoja')
    Invoke-SqlComando -StringConexao $conBd -Sql 'CREATE TABLE Vendas(Id INT PRIMARY KEY, Total DECIMAL(10,2)); INSERT Vendas VALUES (1,10.50),(2,20.00),(3,30.25);'

    $estado = Join-Path $Temp 'estado\backup-estado.json'
    $env = Join-Path $Temp 'config\loja.env'
    # loja.env como a versao antiga do restaurar.ps1 o deixava: a linha de legado tem de sair na restauracao
    Write-ArquivoTextoUtf8 -Caminho $env -Conteudo "A=1`r`nquack.sync.conferencia-ativa=false`r`n"
    $marcador = Join-Path $Temp 'config\recuperar.pendente'

    foreach ($i in 1..9) {
        & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $raiz 'backup.ps1') -Destino $Temp -StringConexao $con -Banco QuackLoja `
            -PastaBackup $pastaBackups -CaminhoBackupNoSql '/var/opt/mssql/backups' -ArquivoEstado $estado -Retencao 7 | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "backup.ps1 saiu com $LASTEXITCODE na rodada $i" }
        Start-Sleep -Seconds 1   # o nome do arquivo tem resolucao de segundo
    }
    $arqs = @(Get-ChildItem $pastaBackups -Filter 'quack-quackloja-*.bak' | Sort-Object Name)
    Confirmar '9 backups com retencao 7 deixam exatamente 7' ($arqs.Count -eq 7) $arqs.Count
    Confirmar 'arquivos de backup nao vazios' (-not ($arqs | Where-Object { $_.Length -le 0 }))
    $j = Get-Content $estado -Raw | ConvertFrom-Json
    Confirmar 'backup-estado.json: sucesso=true e ultimoBackupEm preenchido' ($j.sucesso -eq $true -and $j.ultimoBackupEm)
    Write-Host ("    estado: " + (Get-Content $estado -Raw).Trim())
    Write-Host ("    tamanho do ultimo backup: {0:N0} bytes" -f $arqs[-1].Length)

    # Falha real: banco inexistente -> codigo 1, estado sucesso=false, retencao intacta
    $ErrorActionPreference = 'Continue'   # o backup que falha escreve na saida de erro
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $raiz 'backup.ps1') -Destino $Temp -StringConexao $con -Banco BancoQueNaoExiste `
        -PastaBackup $pastaBackups -CaminhoBackupNoSql '/var/opt/mssql/backups' -ArquivoEstado $estado -Retencao 7 2>&1 | Out-Null
    $codigoFalha = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    $jf = Get-Content $estado -Raw | ConvertFrom-Json
    Confirmar 'backup de banco inexistente: codigo 1, sucesso=false, preserva ultimoBackupEm anterior' ($codigoFalha -eq 1 -and $jf.sucesso -eq $false -and $jf.erro -and $jf.ultimoBackupEm -eq $j.ultimoBackupEm)
    Confirmar 'falha nao apagou backups' (@(Get-ChildItem $pastaBackups -Filter 'quack-quackloja-*.bak').Count -eq 7)

    # Restauracao
    Invoke-SqlComando -StringConexao $conBd -Sql 'DELETE Vendas WHERE Id = 2; INSERT Vendas VALUES (99, 999.99);'
    $antes = Invoke-SqlComando -StringConexao $conBd -Escalar -Sql 'SELECT SUM(Total) FROM Vendas'
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $raiz 'restaurar.ps1') -Destino $Temp -StringConexao $con -Banco QuackLoja `
        -CaminhoBackupNoSql '/var/opt/mssql/backups' -SemServico -Forcar | Out-Null
    Confirmar 'restaurar.ps1 saiu com 0' ($LASTEXITCODE -eq 0) $LASTEXITCODE
    $depois = Invoke-SqlComando -StringConexao $conBd -Escalar -Sql 'SELECT SUM(Total) FROM Vendas'
    $n = Invoke-SqlComando -StringConexao $conBd -Escalar -Sql 'SELECT COUNT(*) FROM Vendas'
    Confirmar "dados voltaram ao backup (3 linhas, soma 60.75; antes da restauracao a soma era $antes)" ($n -eq 3 -and [decimal]$depois -eq [decimal]60.75) "n=$n soma=$depois"
    $modo = Invoke-SqlComando -StringConexao $con -Escalar -Sql "SELECT user_access_desc FROM sys.databases WHERE name = 'QuackLoja'"
    Confirmar 'banco voltou a MULTI_USER' ($modo -eq 'MULTI_USER') $modo
    $textoEnv = Get-Content $env -Raw
    Confirmar 'loja.env sem desligamento da conferencia (a API a religa apos a recuperacao) e com A=1' ($textoEnv -notmatch 'conferencia-ativa' -and $textoEnv -match 'A=1') $textoEnv
    Confirmar 'marcador da recuperacao deixado em config\recuperar.pendente' ((Test-Path $marcador) -and ((Get-Content $marcador -Raw) -match 'restaurado'))

    # Excecao -SemRecuperacao: restaura e nao deixa o marcador
    Remove-Item $marcador -Force
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $raiz 'restaurar.ps1') -Destino $Temp -StringConexao $con -Banco QuackLoja `
        -CaminhoBackupNoSql '/var/opt/mssql/backups' -SemServico -Forcar -SemRecuperacao | Out-Null
    Confirmar '-SemRecuperacao: restaurou e nao deixou o marcador' ($LASTEXITCODE -eq 0 -and -not (Test-Path $marcador))
}
finally {
    $a = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    & docker rm -f $nome 2>&1 | Out-Null
    $ErrorActionPreference = $a
}
Write-Host ''
if ($falhas -gt 0) { Write-Host "$falhas falha(s)." -ForegroundColor Red; exit 1 }
Write-Host 'Backup e restauracao provados contra SQL Server em container.' -ForegroundColor Green
