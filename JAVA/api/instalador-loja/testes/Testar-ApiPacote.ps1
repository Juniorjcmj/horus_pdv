<#
.SYNOPSIS
    Smoke do pacote: sobe o jar com o JRE do jlink e um loja.env gerado pelo instalador contra um SQL Server em Docker.
.DESCRIPTION
    Prova (sem instalar nada no sistema): Initialize-BancoLoja (login quack_app + db_owner), loja.env no formato lido por
    spring.config.import, os segredos chegando na API, o JRE minimo (jlink) com todos os modulos, o Flyway criando o
    esquema da loja e o /actuator/health em UP no perfil "loja".
.PARAMETER Pacote
    Pasta do pacote extraido (quack-servidor-loja).
#>
param(
    [Parameter(Mandatory)][string]$Pacote,
    [string]$Temp = (Join-Path ([IO.Path]::GetTempPath()) ('quack-api-' + [guid]::NewGuid().ToString('N').Substring(0, 8))),
    [int]$PortaSql = 14332,
    [int]$PortaApi = 18080,
    [string]$Imagem = 'mcr.microsoft.com/mssql/server:2022-latest'
)
$ErrorActionPreference = 'Stop'
. (Join-Path $Pacote 'lib\comum.ps1')
New-Item -ItemType Directory -Path $Temp -Force | Out-Null
$nome = 'quack-teste-api-' + [guid]::NewGuid().ToString('N').Substring(0, 6)
$saPwd = New-SenhaSql
$appPwd = New-SenhaSql
$falhas = 0
function Confirmar([string]$Nome, [bool]$Condicao, [string]$Detalhe = '') {
    if ($Condicao) { Write-Host "[ OK ] $Nome" -ForegroundColor Green } else { Write-Host "[FALHA] $Nome $Detalhe" -ForegroundColor Red; $script:falhas++ }
}
$proc = $null
try {
    $a = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    & docker run -d --name $nome -e ACCEPT_EULA=Y -e "MSSQL_SA_PASSWORD=$saPwd" -p "${PortaSql}:1433" $Imagem 2>&1 | Out-Null
    $ErrorActionPreference = $a
    $conSa = Get-StringConexaoSql -Servidor "127.0.0.1,$PortaSql" -Banco 'master' -Usuario 'sa' -Senha $saPwd
    $pronto = $false
    foreach ($i in 1..40) { try { Invoke-SqlComando -StringConexao $conSa -Sql 'SELECT 1' -TempoLimite 5; $pronto = $true; break } catch { Start-Sleep -Seconds 3 } }
    if (-not $pronto) { throw 'SQL Server nao ficou pronto.' }

    $conBd = Get-StringConexaoSql -Servidor "127.0.0.1,$PortaSql" -Banco 'QuackLoja' -Usuario 'sa' -Senha $saPwd
    Initialize-BancoLoja -StringConexaoMaster $conSa -StringConexaoBanco $conBd -Banco 'QuackLoja' -SenhaApp $appPwd
    Initialize-BancoLoja -StringConexaoMaster $conSa -StringConexaoBanco $conBd -Banco 'QuackLoja' -SenhaApp $appPwd   # idempotente
    $rec = Invoke-SqlComando -StringConexao $conSa -Escalar -Sql "SELECT recovery_model_desc FROM sys.databases WHERE name = 'QuackLoja'"
    Confirmar 'banco criado com recuperacao SIMPLE; repetir a criacao nao falha' ($rec -eq 'SIMPLE') $rec

    $jwt = New-SegredoAleatorio; $cifra = New-SegredoAleatorio
    $estado = Join-Path $Temp 'estado\backup-estado.json'
    Write-EstadoBackup -Caminho $estado -Sucesso $true -Quando ([datetimeoffset]::Now)
    $env = [ordered]@{
        SPRING_DATASOURCE_URL = "jdbc:sqlserver://127.0.0.1:$PortaSql;databaseName=QuackLoja;encrypt=true;trustServerCertificate=true"
        SPRING_DATASOURCE_USERNAME = 'quack_app'; SPRING_DATASOURCE_PASSWORD = $appPwd
        QUACK_AUTH_JWT_SECRET = $jwt; QUACK_SECURITY_ENCRYPTION_KEY = $cifra
        'quack.sync.nuvem-url' = 'http://127.0.0.1:9'
        'quack.sync.backup-estado' = ($estado -replace '\\', '/')
    }
    Write-ArquivoTextoUtf8 -Caminho (Join-Path $Temp 'config\loja.env') -Conteudo (ConvertTo-ConteudoLojaEnv -Valores $env -Cabecalho 'teste')

    $java = Join-Path $Pacote 'jre\bin\java.exe'
    $jar = Join-Path $Pacote 'app\quack-pdv-api.jar'
    $log = Join-Path $Temp 'api.log'
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $java
    $psi.Arguments = "-Xmx512m -Dfile.encoding=UTF-8 -jar `"$jar`" --spring.profiles.active=loja `"--spring.config.import=file:./config/loja.env[.properties]`""
    $psi.WorkingDirectory = $Temp
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true; $psi.RedirectStandardError = $true
    $psi.EnvironmentVariables['PORT'] = [string]$PortaApi
    $proc = [System.Diagnostics.Process]::Start($psi)
    $saida = New-Object System.Text.StringBuilder
    $null = Register-ObjectEvent -InputObject $proc -EventName OutputDataReceived -Action { [void]$Event.MessageData.AppendLine($EventArgs.Data) } -MessageData $saida
    $null = Register-ObjectEvent -InputObject $proc -EventName ErrorDataReceived -Action { [void]$Event.MessageData.AppendLine($EventArgs.Data) } -MessageData $saida
    $proc.BeginOutputReadLine(); $proc.BeginErrorReadLine()

    $up = $false
    foreach ($i in 1..60) {
        if ($proc.HasExited) { break }
        try { $r = Invoke-RestMethod -Uri "http://127.0.0.1:$PortaApi/actuator/health" -TimeoutSec 3; if ($r.status -eq 'UP') { $up = $true; break } } catch { }
        Start-Sleep -Seconds 3
    }
    Set-Content -LiteralPath $log -Value $saida.ToString() -Encoding UTF8
    Confirmar '/actuator/health UP no perfil loja (JRE do jlink + loja.env + segredos)' $up ("exit=" + $(if ($proc.HasExited) { $proc.ExitCode } else { 'rodando' }) + " log=$log")
    $tabelas = Invoke-SqlComando -StringConexao $conBd -Escalar -Sql "SELECT COUNT(*) FROM sys.tables WHERE name IN ('Vendas','Produtos','SyncEstado')"
    Confirmar 'Flyway (login quack_app como db_owner) criou o esquema da loja' ($tabelas -eq 3) $tabelas
    $dono = Invoke-SqlComando -StringConexao $conBd -Escalar -Sql "SELECT COUNT(*) FROM sys.database_role_members rm JOIN sys.database_principals r ON r.principal_id = rm.role_principal_id JOIN sys.database_principals m ON m.principal_id = rm.member_principal_id WHERE r.name = 'db_owner' AND m.name = 'quack_app'"
    Confirmar 'quack_app e db_owner so no banco da loja' ($dono -eq 1) $dono
    if (-not $up) { Write-Host ($saida.ToString() | Select-Object -First 1); Get-Content $log -Tail 40 }
}
finally {
    if ($proc -and -not $proc.HasExited) { $proc.Kill() }
    Get-EventSubscriber -ErrorAction SilentlyContinue | Unregister-Event -ErrorAction SilentlyContinue
    $a = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    & docker rm -f $nome 2>&1 | Out-Null
    $ErrorActionPreference = $a
}
Write-Host ''
if ($falhas -gt 0) { Write-Host "$falhas falha(s)." -ForegroundColor Red; exit 1 }
Write-Host 'Pacote sobe e responde em UP.' -ForegroundColor Green
