<#
.SYNOPSIS
    Instala o servidor da loja (D12): SQL Server Express, banco, API Java como servico, segredos, firewall, backup,
    registro na nuvem e carga inicial.
.DESCRIPTION
    Rode como Administrador, na pasta do pacote (quack-servidor-loja). Passos, nesta ordem:
      1. pre-requisitos (Windows 64 bits, privilegio, arquivos do pacote, JRE, disco, porta, sub-rede);
      2. SQL Express (instancia QUACK), so TCP em 127.0.0.1:1433 - ou reaproveita a instancia existente;
      3. banco QuackLoja + login quack_app (db_owner), modelo de recuperacao SIMPLE;
      4. copia app\, jre\, scripts\, servico\ para o destino;
      5. config\loja.env com segredos proprios (QUACK_AUTH_JWT_SECRET e QUACK_SECURITY_ENCRYPTION_KEY, 48 bytes cada),
         ACL so para Administradores e SYSTEM. Reexecutar NAO troca segredos ja gerados (trocar a chave de cifra
         invalidaria os dados cifrados do banco);
      6. regras de firewall: porta da API so para a sub-rede local; porta do SQL bloqueada;
      7. servico QuackLojaApi (WinSW) com inicio automatico e reinicio em falha;
      8. tarefa agendada QuackLoja-Backup (SYSTEM, 02:00);
      9. registro na nuvem + carga inicial (java -jar ... --quack.sync.carga.executar=true), depois sobe o servico e
         acompanha o progresso pela rota GET /api/loja-sync/situacao.
    -SomenteValidar: so confere o ambiente e o pacote e imprime o plano; nao altera nada e nao exige Administrador.
    -WhatIf: executa a validacao e lista cada passo mutavel sem executa-lo.
.EXAMPLE
    .\instalar.ps1 -SomenteValidar
    .\instalar.ps1 -EmailAdmin admin@loja.com.br -NuvemUrl https://sync-pdv.quacksistemas.com.br
#>
[CmdletBinding(SupportsShouldProcess)]
param(
    [string]$Destino = 'C:\QuackLoja',
    [string]$NuvemUrl = 'https://sync-pdv.quacksistemas.com.br',
    [string]$EmailAdmin,
    [string]$NomeServidor = 'Servidor da loja',
    [string]$Instancia = 'QUACK',
    [string]$Banco = 'QuackLoja',
    [int]$PortaApi = 8080,
    [int]$PortaSql = 1433,
    [string]$Subrede,
    [string]$HoraBackup = '02:00',
    [int]$RetencaoBackups = 7,
    [string]$MidiaSql,
    [switch]$PularCarga,
    [switch]$SomenteValidar
)
$ErrorActionPreference = 'Stop'
$Pacote = $PSScriptRoot
. (Join-Path $Pacote 'lib\comum.ps1')

$SERVICO_API = 'QuackLojaApi'
$TAREFA_BACKUP = 'QuackLoja-Backup'
$REGRA_API = 'Quack Loja - API (somente sub-rede local)'
$REGRA_SQL = 'Quack Loja - SQL Server (bloqueado fora da maquina)'
$URL_SQL_EXPRESS = 'https://go.microsoft.com/fwlink/?linkid=2216019'   # bootstrapper do SQL Server 2022 Express
$SEM_ALTERAR = [bool]($SomenteValidar -or $WhatIfPreference)
$servicoSql = if ($Instancia -eq 'MSSQLSERVER') { 'MSSQLSERVER' } else { 'MSSQL$' + $Instancia }
$servidorSqlInstalacao = if ($Instancia -eq 'MSSQLSERVER') { 'lpc:localhost' } else { "lpc:localhost\$Instancia" }   # memoria compartilhada: nao depende de porta nem de SQL Browser
$caminhos = [ordered]@{
    App = Join-Path $Destino 'app'; Jre = Join-Path $Destino 'jre'; Servico = Join-Path $Destino 'servico'
    Config = Join-Path $Destino 'config'; Logs = Join-Path $Destino 'logs'; Scripts = Join-Path $Destino 'scripts'
    Backups = Join-Path $Destino 'backups'; Estado = Join-Path $Destino 'estado'
}
$arquivoEnv = Join-Path $caminhos.Config 'loja.env'
$arquivoEstadoBackup = Join-Path $caminhos.Estado 'backup-estado.json'

$resultados = New-Object System.Collections.Generic.List[object]
function Add-Verificacao([string]$Item, [bool]$Ok, [string]$Detalhe = '', [switch]$Aviso) {
    $resultados.Add([pscustomobject]@{ Item = $Item; Ok = $Ok; Aviso = [bool]$Aviso; Detalhe = $Detalhe })
}
function Invoke-Passo([string]$Descricao, [scriptblock]$Acao) {
    Write-Host "==> $Descricao"
    if ($SEM_ALTERAR) { Write-Host '    (nao executado: simulacao)' -ForegroundColor DarkGray; return }
    & $Acao
}
function Test-Administrador {
    $p = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
    return $p.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Get-SubredeAutomatica {
    # Interface da rota padrao de menor custo que tenha um IPv4 de verdade (prefixo <= 30, sem link-local): VPN e
    # adaptadores virtuais costumam ter /32.
    $rotas = @(Get-NetRoute -DestinationPrefix '0.0.0.0/0' -ErrorAction Stop | Sort-Object RouteMetric)
    foreach ($rota in $rotas) {
        $ip = Get-NetIPAddress -InterfaceIndex $rota.ifIndex -AddressFamily IPv4 -ErrorAction SilentlyContinue |
            Where-Object { $_.PrefixLength -le 30 -and $_.IPAddress -notlike '169.254.*' } | Select-Object -First 1
        if ($ip) {
            return [pscustomobject]@{ Ip = $ip.IPAddress; Prefixo = $ip.PrefixLength; Cidr = (Get-SubredeCidr -Ip $ip.IPAddress -Prefixo $ip.PrefixLength); Dhcp = ($ip.PrefixOrigin -eq 'Dhcp') }
        }
    }
    throw 'Nenhuma interface com rota padrao e IPv4 de rede local (prefixo ate /30).'
}

# ---------------------------------------------------------------------------------------------------------------
# 1. Pre-requisitos
# ---------------------------------------------------------------------------------------------------------------
function Test-PreRequisitos {
    Add-Verificacao 'Windows 64 bits' ([Environment]::Is64BitOperatingSystem) ([Environment]::OSVersion.VersionString)
    Add-Verificacao 'PowerShell 5.1 ou superior' ($PSVersionTable.PSVersion -ge [version]'5.1') $PSVersionTable.PSVersion.ToString()
    $admin = Test-Administrador
    Add-Verificacao 'Executando como Administrador' $admin $(if ($admin) { '' } else { 'Obrigatorio para instalar; nao exigido em -SomenteValidar.' }) -Aviso:($SomenteValidar -or $WhatIfPreference)

    $arquivos = 'app\quack-pdv-api.jar', 'jre\bin\java.exe', 'winsw\WinSW-x64.exe', 'winsw\QuackLojaApi.xml.modelo', 'lib\comum.ps1',
                'backup.ps1', 'restaurar.ps1', 'desinstalar.ps1', 'CHECKLIST.md'
    foreach ($a in $arquivos) {
        $ok = Test-Path -LiteralPath (Join-Path $Pacote $a)
        Add-Verificacao "Pacote: $a" $ok $(if ($ok) { '' } else { 'ausente (gere o pacote com build-instalador.sh)' })
    }

    foreach ($s in 'instalar.ps1', 'backup.ps1', 'restaurar.ps1', 'desinstalar.ps1', 'lib\comum.ps1') {
        $f = Join-Path $Pacote $s
        if (-not (Test-Path -LiteralPath $f)) { continue }
        $erros = $null
        [void][System.Management.Automation.Language.Parser]::ParseFile($f, [ref]$null, [ref]$erros)
        $detalheSintaxe = (@($erros | ForEach-Object { $_.Message })) -join '; '
        Add-Verificacao "Sintaxe: $s" ($erros.Count -eq 0) $detalheSintaxe
    }

    $java = Join-Path $Pacote 'jre\bin\java.exe'
    if (Test-Path -LiteralPath $java) {
        $anterior = $ErrorActionPreference
        try {
            $ErrorActionPreference = 'Continue'   # o java -version escreve na saida de erro; no PS 5.1 isso viraria excecao
            $saidaJava = @(& $java -version 2>&1 | ForEach-Object { "$_" })
            $codigo = $LASTEXITCODE
            $v = $saidaJava | Select-Object -First 1
            Add-Verificacao 'JRE embutido executa' ($codigo -eq 0 -and $v -match '"2[1-9]') $v
        } catch { Add-Verificacao 'JRE embutido executa' $false $_.Exception.Message }
        finally { $ErrorActionPreference = $anterior }
    }

    $modelo = Join-Path $Pacote 'winsw\QuackLojaApi.xml.modelo'
    if (Test-Path -LiteralPath $modelo) {
        try { [void]([xml](Get-Content -LiteralPath $modelo -Raw -Encoding UTF8)); Add-Verificacao 'XML do WinSW valido' $true }
        catch { Add-Verificacao 'XML do WinSW valido' $false $_.Exception.Message }
    }

    $raizDisco = [System.IO.Path]::GetPathRoot([System.IO.Path]::GetFullPath($Destino))
    try {
        $livre = (New-Object System.IO.DriveInfo $raizDisco).AvailableFreeSpace / 1GB
        Add-Verificacao 'Espaco livre no destino (>= 10 GB)' ($livre -ge 10) ('{0:N1} GB em {1}' -f $livre, $raizDisco)
    } catch { Add-Verificacao 'Espaco livre no destino' $false $_.Exception.Message }

    $emUso = @(Get-NetTCPConnection -State Listen -LocalPort $PortaApi -ErrorAction SilentlyContinue)
    $servicoJa = [bool](Get-Service -Name $SERVICO_API -ErrorAction SilentlyContinue)
    Add-Verificacao "Porta $PortaApi livre (ou ja do servico)" ($emUso.Count -eq 0 -or $servicoJa) $(if ($emUso.Count) { "em uso por PID $($emUso[0].OwningProcess)" } else { '' })

    if ($Subrede) {
        Add-Verificacao 'Sub-rede informada' (Test-SubredeValida $Subrede) $Subrede
    } else {
        try {
            $auto = Get-SubredeAutomatica
            Add-Verificacao 'Sub-rede detectada' $true "$($auto.Cidr) (IP $($auto.Ip))"
            if ($auto.Dhcp) { Add-Verificacao 'IP fixo' $false 'O IP desta maquina e dinamico (DHCP). Fixe o IP antes de apontar os caixas.' -Aviso }
        } catch { Add-Verificacao 'Sub-rede detectada' $false "$($_.Exception.Message) - informe -Subrede 192.168.0.0/24" }
    }

    $temSql = [bool](Get-Service -Name $servicoSql -ErrorAction SilentlyContinue)
    if ($temSql) { Add-Verificacao "SQL Server ($servicoSql)" $true 'instancia existente sera reaproveitada (precisa de modo misto e do seu usuario como sysadmin)' }
    elseif ($MidiaSql) { Add-Verificacao 'Midia do SQL Server Express' (Test-Path -LiteralPath $MidiaSql) $MidiaSql }
    else { Add-Verificacao 'SQL Server Express' $true 'sera baixado do site da Microsoft (precisa de internet); use -MidiaSql para instalar offline' -Aviso }

    try {
        $r = Invoke-WebRequest -Uri ($NuvemUrl.TrimEnd('/') + '/actuator/health') -UseBasicParsing -TimeoutSec 15
        Add-Verificacao 'Nuvem acessivel' ($r.StatusCode -eq 200) $NuvemUrl
    } catch { Add-Verificacao 'Nuvem acessivel' $false "$NuvemUrl : $($_.Exception.Message)" -Aviso }
}

function Show-Verificacoes {
    Write-Host ''
    foreach ($r in $resultados) {
        $marca = if ($r.Ok) { '[ OK ]' } elseif ($r.Aviso) { '[AVISO]' } else { '[FALHA]' }
        $cor = if ($r.Ok) { 'Green' } elseif ($r.Aviso) { 'Yellow' } else { 'Red' }
        Write-Host ("{0} {1} {2}" -f $marca, $r.Item, $(if ($r.Detalhe) { "- $($r.Detalhe)" } else { '' })) -ForegroundColor $cor
    }
    Write-Host ''
}

# ---------------------------------------------------------------------------------------------------------------
# 2-3. SQL Server Express, rede do SQL e banco
# ---------------------------------------------------------------------------------------------------------------
function Install-SqlExpress([string]$SenhaSa) {
    $midia = $MidiaSql
    $temp = Join-Path $env:TEMP 'quack-sql-express'
    New-Item -ItemType Directory -Path $temp -Force | Out-Null
    if (-not $midia) {
        $boot = Join-Path $temp 'SQL-SSEI-Expr.exe'
        Write-Host "    baixando o instalador do SQL Server Express ($URL_SQL_EXPRESS) ..."
        Invoke-WebRequest -Uri $URL_SQL_EXPRESS -OutFile $boot -UseBasicParsing
        $p = Start-Process -FilePath $boot -ArgumentList '/ACTION=Download', "/MEDIAPATH=$temp", '/MEDIATYPE=Core', '/QUIET' -Wait -PassThru
        if ($p.ExitCode -ne 0) { throw "Download da midia do SQL Express falhou (codigo $($p.ExitCode))." }
        $midia = (Get-ChildItem -LiteralPath $temp -Filter 'SQLEXPR*.exe' | Select-Object -First 1).FullName
        if (-not $midia) { throw "Midia SQLEXPR*.exe nao encontrada em $temp." }
    }
    # A senha do sa vai por arquivo de configuracao (apagado depois), nao pela linha de comando.
    $ini = Join-Path $temp 'config-sql.ini'
    $conteudo = @"
[OPTIONS]
ACTION="Install"
QUIET="True"
IACCEPTSQLSERVERLICENSETERMS="True"
FEATURES=SQLENGINE
INSTANCENAME="$Instancia"
INSTANCEID="$Instancia"
SECURITYMODE="SQL"
SAPWD="$SenhaSa"
SQLSYSADMINACCOUNTS="BUILTIN\Administrators" "NT AUTHORITY\SYSTEM"
TCPENABLED="1"
NPENABLED="0"
UPDATEENABLED="False"
SQLSVCSTARTUPTYPE="Automatic"
"@
    [System.IO.File]::WriteAllText($ini, $conteudo, $script:Utf8SemBom)
    & icacls.exe $ini /inheritance:r /grant:r '*S-1-5-32-544:(F)' '*S-1-5-18:(F)' | Out-Null
    try {
        $p = Start-Process -FilePath $midia -ArgumentList "/ConfigurationFile=`"$ini`"", '/Q' -Wait -PassThru
        if ($p.ExitCode -notin 0, 3010) { throw "Instalacao do SQL Express falhou (codigo $($p.ExitCode)). Veja %ProgramFiles%\Microsoft SQL Server\*\Setup Bootstrap\Log." }
    } finally { Remove-Item -LiteralPath $ini -Force -ErrorAction SilentlyContinue }
}

function Set-SqlSoLocal {
    # TCP so em 127.0.0.1 na porta fixa; nenhum outro IP escuta.
    $mapa = Get-ItemProperty 'HKLM:\SOFTWARE\Microsoft\Microsoft SQL Server\Instance Names\SQL'
    $id = $mapa.$Instancia
    if (-not $id) { throw "Instancia $Instancia nao encontrada no registro." }
    $tcp = "HKLM:\SOFTWARE\Microsoft\Microsoft SQL Server\$id\MSSQLServer\SuperSocketNetLib\Tcp"
    Set-ItemProperty -Path $tcp -Name Enabled -Value 1
    Set-ItemProperty -Path $tcp -Name ListenOnAllIPs -Value 0 -ErrorAction SilentlyContinue
    foreach ($k in Get-ChildItem -Path $tcp | Where-Object { $_.PSChildName -like 'IP*' }) {
        $ehLoop = ($k.PSChildName -ne 'IPAll') -and ((Get-ItemProperty $k.PSPath).IPAddress -eq '127.0.0.1')
        if ($k.PSChildName -eq 'IPAll') {
            Set-ItemProperty -Path $k.PSPath -Name TcpPort -Value ([string]$PortaSql)
            Set-ItemProperty -Path $k.PSPath -Name TcpDynamicPorts -Value ''
        } else {
            Set-ItemProperty -Path $k.PSPath -Name Active -Value ([int]$ehLoop)
            Set-ItemProperty -Path $k.PSPath -Name Enabled -Value ([int]$ehLoop)
            if ($ehLoop) { Set-ItemProperty -Path $k.PSPath -Name TcpPort -Value ([string]$PortaSql); Set-ItemProperty -Path $k.PSPath -Name TcpDynamicPorts -Value '' }
        }
    }
}

function Initialize-Banco([string]$SenhaApp) {
    $con = Get-StringConexaoSql -Servidor $servidorSqlInstalacao -Banco 'master'
    $conBanco = Get-StringConexaoSql -Servidor $servidorSqlInstalacao -Banco $Banco
    Initialize-BancoLoja -StringConexaoMaster $con -StringConexaoBanco $conBanco -Banco $Banco -SenhaApp $SenhaApp
}

# ---------------------------------------------------------------------------------------------------------------
# 5. loja.env
# ---------------------------------------------------------------------------------------------------------------
function Get-ValorEnvExistente([string]$Chave) {
    if (-not (Test-Path -LiteralPath $arquivoEnv)) { return $null }
    $linha = Get-Content -LiteralPath $arquivoEnv -Encoding UTF8 | Where-Object { $_ -like "$Chave=*" } | Select-Object -First 1
    if (-not $linha) { return $null }
    return $linha.Substring($Chave.Length + 1).Replace('\\', '\')
}

function Write-LojaEnv {
    $jwt = Get-ValorEnvExistente 'QUACK_AUTH_JWT_SECRET'; if (-not $jwt) { $jwt = New-SegredoAleatorio -Bytes 48 }
    $cifra = Get-ValorEnvExistente 'QUACK_SECURITY_ENCRYPTION_KEY'; if (-not $cifra) { $cifra = New-SegredoAleatorio -Bytes 48 }
    $senhaApp = Get-ValorEnvExistente 'SPRING_DATASOURCE_PASSWORD'; if (-not $senhaApp) { $senhaApp = New-SenhaSql }
    $valores = [ordered]@{
        SPRING_DATASOURCE_URL          = "jdbc:sqlserver://localhost:$PortaSql;databaseName=$Banco;encrypt=true;trustServerCertificate=true"
        SPRING_DATASOURCE_USERNAME     = 'quack_app'
        SPRING_DATASOURCE_PASSWORD     = $senhaApp
        QUACK_AUTH_JWT_SECRET          = $jwt
        QUACK_SECURITY_ENCRYPTION_KEY  = $cifra
        'quack.sync.nuvem-url'         = $NuvemUrl.TrimEnd('/')
        'quack.sync.backup-estado'     = ($arquivoEstadoBackup -replace '\\', '/')
    }
    $cab = "Configuracao e SEGREDOS desta loja - gerado por instalar.ps1.`nNAO copie, NAO envie e NAO versione este arquivo. Trocar QUACK_SECURITY_ENCRYPTION_KEY invalida os dados cifrados do banco."
    Write-ArquivoTextoUtf8 -Caminho $arquivoEnv -Conteudo (ConvertTo-ConteudoLojaEnv -Valores $valores -Cabecalho $cab)
    & icacls.exe $caminhos.Config /inheritance:r /grant:r '*S-1-5-32-544:(OI)(CI)(F)' '*S-1-5-18:(OI)(CI)(F)' | Out-Null
    return $senhaApp
}

# ---------------------------------------------------------------------------------------------------------------
# 9. Carga inicial e acompanhamento
# ---------------------------------------------------------------------------------------------------------------
function ConvertTo-TextoSimples([securestring]$Segura) {
    $b = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Segura)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($b) } finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($b) }
}

function Invoke-CargaInicial([string]$Email, [string]$Senha) {
    $java = Join-Path $caminhos.Jre 'bin\java.exe'
    $jar = Join-Path $caminhos.App 'quack-pdv-api.jar'
    $env:QUACK_SYNC_CARGA_SENHA = $Senha
    Push-Location $Destino
    try {
        & $java -Dfile.encoding=UTF-8 -jar $jar --spring.profiles.active=loja '--spring.config.import=file:./config/loja.env[.properties]' `
            --quack.sync.carga.executar=true "--quack.sync.carga.nuvem-url=$($NuvemUrl.TrimEnd('/'))" "--quack.sync.carga.email=$Email" `
            "--quack.sync.carga.nome-servidor=$NomeServidor" --quack.sync.alertas-ativo=false --quack.sync.conferencia-ativa=false --server.port=0
        if ($LASTEXITCODE -ne 0) { throw "A carga inicial falhou (codigo $LASTEXITCODE). Corrija a causa e rode instalar.ps1 de novo: a carga retoma de onde parou." }
    } finally { Pop-Location; Remove-Item Env:\QUACK_SYNC_CARGA_SENHA -ErrorAction SilentlyContinue }
}

function Get-SituacaoLocal([string]$Email, [string]$Senha) {
    $base = "http://localhost:$PortaApi"
    $corpo = @{ email = $Email; password = $Senha; rememberMe = $false; recaptchaToken = '' } | ConvertTo-Json
    $login = Invoke-WebRequest -Uri "$base/api/Auth/login" -Method Post -Body $corpo -ContentType 'application/json' -UseBasicParsing
    $cookie = @($login.Headers['Set-Cookie']) | Where-Object { $_ -like 'horuspdv.auth=*' } | Select-Object -First 1
    if (-not $cookie) { throw 'Login local sem cookie de sessao.' }
    $token = ($cookie -split ';')[0].Substring('horuspdv.auth='.Length)
    return Invoke-RestMethod -Uri "$base/api/loja-sync/situacao" -Headers @{ Authorization = "Bearer $token" } -TimeoutSec 30
}

function Wait-SincronizacaoEmDia([string]$Email, [string]$Senha, [int]$TimeoutMin = 15) {
    $limite = (Get-Date).AddMinutes($TimeoutMin)
    while ((Get-Date) -lt $limite) {
        try {
            $s = Get-SituacaoLocal -Email $Email -Senha $Senha
            $d = $s.data; if (-not $d) { $d = $s }
            $atraso = if ($null -ne $d.atrasoSegundos) { [int]$d.atrasoSegundos } else { 0 }
            Write-Host ("    versao local {0} / confirmada {1}; atraso {2}s; pausada: {3}" -f $d.versaoLocal, $d.versaoConfirmada, $atraso, $d.pausada)
            if ($d.pausada) { throw "Sincronizacao pausada: $($d.pausaMotivo)" }
            if ($atraso -eq 0) { return }
        } catch [System.Net.WebException] { Write-Host "    aguardando a API responder ($($_.Exception.Message)) ..." }
        Start-Sleep -Seconds 10
    }
    throw "A sincronizacao nao chegou a atraso zero em $TimeoutMin minutos. Veja GET /api/loja-sync/situacao e $($caminhos.Logs)."
}

# ===============================================================================================================
# Execucao
# ===============================================================================================================
Write-Host "Instalador do servidor da loja - destino $Destino" -ForegroundColor Cyan
Test-PreRequisitos
Show-Verificacoes
$falhas = @($resultados | Where-Object { -not $_.Ok -and -not $_.Aviso })
if ($falhas.Count -gt 0) {
    Write-Host "$($falhas.Count) verificacao(oes) falharam; corrija antes de instalar." -ForegroundColor Red
    exit 1
}

$cidr = if ($Subrede) { $Subrede } else { try { (Get-SubredeAutomatica).Cidr } catch { $null } }
Write-Host 'Plano:' -ForegroundColor Cyan
@(
    "SQL Server Express instancia $Instancia (so 127.0.0.1:$PortaSql) e banco $Banco",
    "API como servico $SERVICO_API (inicio automatico, reinicio em falha) em $Destino",
    "Segredos novos em $arquivoEnv (ACL: Administradores e SYSTEM)",
    "Firewall: porta $PortaApi liberada so para $cidr; porta $PortaSql bloqueada",
    "Backup diario as $HoraBackup, retencao $RetencaoBackups, estado em $arquivoEstadoBackup",
    $(if ($PularCarga) { 'Registro e carga inicial: PULADOS' } else { "Registro em $NuvemUrl e carga inicial" })
) | ForEach-Object { Write-Host "  - $_" }
Write-Host ''
if ($SomenteValidar) { Write-Host 'Validacao concluida: nada foi alterado.' -ForegroundColor Green; exit 0 }
if ($WhatIfPreference) { Write-Host 'Simulacao: cada passo abaixo seria executado.' }
elseif (-not (Test-Administrador)) { throw 'Execute o PowerShell como Administrador.' }

$senhaAdmin = $null
if (-not $PularCarga -and -not $WhatIfPreference) {
    if (-not $EmailAdmin) { $EmailAdmin = Read-Host 'E-mail do administrador da empresa na nuvem' }
    $senhaAdmin = if ($env:QUACK_SYNC_CARGA_SENHA) { $env:QUACK_SYNC_CARGA_SENHA } else { ConvertTo-TextoSimples (Read-Host 'Senha do administrador' -AsSecureString) }
}
$senhaApp = $null

Invoke-Passo "Instalar/configurar o SQL Server Express ($Instancia)" {
    if (-not (Get-Service -Name $servicoSql -ErrorAction SilentlyContinue)) {
        Install-SqlExpress -SenhaSa (New-SenhaSql -Tamanho 28)   # a senha do sa nao e guardada: o acesso e pelo Windows (Administradores)
    }
    Set-SqlSoLocal
    Restart-Service -Name $servicoSql -Force
}

Invoke-Passo 'Criar pastas e copiar app, JRE, scripts e servico' {
    foreach ($p in $caminhos.Values) { New-Item -ItemType Directory -Path $p -Force | Out-Null }
    Copy-Item -LiteralPath (Join-Path $Pacote 'app\quack-pdv-api.jar') -Destination $caminhos.App -Force
    if (-not (Test-Path -LiteralPath (Join-Path $caminhos.Jre 'bin\java.exe'))) {
        Copy-Item -Path (Join-Path $Pacote 'jre\*') -Destination $caminhos.Jre -Recurse -Force
    }
    New-Item -ItemType Directory -Path (Join-Path $caminhos.Scripts 'lib') -Force | Out-Null
    foreach ($s in 'backup.ps1', 'restaurar.ps1', 'desinstalar.ps1', 'CHECKLIST.md') { Copy-Item -LiteralPath (Join-Path $Pacote $s) -Destination $caminhos.Scripts -Force }
    Copy-Item -LiteralPath (Join-Path $Pacote 'lib\comum.ps1') -Destination (Join-Path $caminhos.Scripts 'lib') -Force
    # SQL Server precisa escrever na pasta de backups.
    & icacls.exe $caminhos.Backups /grant "NT SERVICE\$servicoSql`:(OI)(CI)M" | Out-Null
}

Invoke-Passo 'Gerar config\loja.env com segredos proprios' { $script:senhaApp = Write-LojaEnv }

Invoke-Passo "Criar o banco $Banco e o login quack_app" { Initialize-Banco -SenhaApp $script:senhaApp }

Invoke-Passo 'Gravar config\instalacao.json' {
    $json = [ordered]@{
        destino = $Destino; banco = $Banco; instancia = $Instancia; servicoSql = $servicoSql; servicoApi = $SERVICO_API
        servidorSql = "localhost,$PortaSql"; pastaBackup = $caminhos.Backups; arquivoEstadoBackup = $arquivoEstadoBackup
        retencaoBackups = $RetencaoBackups; portaApi = $PortaApi; subrede = $cidr; horaBackup = $HoraBackup
    } | ConvertTo-Json
    Write-ArquivoTextoUtf8 -Caminho (Join-Path $caminhos.Config 'instalacao.json') -Conteudo $json
}

Invoke-Passo "Firewall: API so para $cidr; SQL bloqueado" {
    foreach ($n in $REGRA_API, $REGRA_SQL) { Get-NetFirewallRule -DisplayName $n -ErrorAction SilentlyContinue | Remove-NetFirewallRule }
    [void](New-NetFirewallRule -DisplayName $REGRA_API -Direction Inbound -Protocol TCP -LocalPort $PortaApi -RemoteAddress $cidr -Action Allow -Profile Any)
    [void](New-NetFirewallRule -DisplayName $REGRA_SQL -Direction Inbound -Protocol TCP -LocalPort $PortaSql -Action Block -Profile Any)
}

Invoke-Passo "Registrar o servico $SERVICO_API (WinSW)" {
    $exe = Join-Path $caminhos.Servico "$SERVICO_API.exe"
    if (Get-Service -Name $SERVICO_API -ErrorAction SilentlyContinue) {
        Stop-Service -Name $SERVICO_API -Force -ErrorAction SilentlyContinue
        & $exe uninstall | Out-Null
    }
    Copy-Item -LiteralPath (Join-Path $Pacote 'winsw\WinSW-x64.exe') -Destination $exe -Force
    $xml = (Get-Content -LiteralPath (Join-Path $Pacote 'winsw\QuackLojaApi.xml.modelo') -Raw -Encoding UTF8).
        Replace('@DESTINO@', $Destino).Replace('@SERVICO_SQL@', $servicoSql).Replace('@PORTA@', [string]$PortaApi)
    Write-ArquivoTextoUtf8 -Caminho (Join-Path $caminhos.Servico "$SERVICO_API.xml") -Conteudo $xml
    & $exe install
    if ($LASTEXITCODE -ne 0) { throw "WinSW install falhou (codigo $LASTEXITCODE)." }
}

Invoke-Passo "Agendar o backup diario ($TAREFA_BACKUP as $HoraBackup)" {
    $script = Join-Path $caminhos.Scripts 'backup.ps1'
    $acao = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument "-NoProfile -ExecutionPolicy Bypass -File `"$script`" -Destino `"$Destino`""
    $gatilho = New-ScheduledTaskTrigger -Daily -At $HoraBackup
    $principal = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest
    $cfg = New-ScheduledTaskSettingsSet -StartWhenAvailable -ExecutionTimeLimit (New-TimeSpan -Hours 2)
    [void](Register-ScheduledTask -TaskName $TAREFA_BACKUP -Action $acao -Trigger $gatilho -Principal $principal -Settings $cfg -Force)
}

if (-not $PularCarga) {
    Invoke-Passo "Registrar na nuvem e fazer a carga inicial ($NuvemUrl)" { Invoke-CargaInicial -Email $EmailAdmin -Senha $senhaAdmin }
}

Invoke-Passo "Iniciar o servico $SERVICO_API" { Start-Service -Name $SERVICO_API }

if (-not $PularCarga) {
    Invoke-Passo 'Acompanhar a sincronizacao (atraso zero)' { Wait-SincronizacaoEmDia -Email $EmailAdmin -Senha $senhaAdmin }
}

Invoke-Passo 'Primeiro backup (valida o caminho e as permissoes)' {
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $caminhos.Scripts 'backup.ps1') -Destino $Destino
    if ($LASTEXITCODE -ne 0) { Write-Warning 'O primeiro backup falhou; veja a mensagem acima antes de liberar a loja.' }
}

$senhaAdmin = $null
if (-not $SEM_ALTERAR) {
    try { $ip = (Get-SubredeAutomatica).Ip } catch { $ip = '<IP desta maquina>' }
    Write-Host ''
    Write-Host "Instalacao concluida. Endereco para os caixas: http://${ip}:$PortaApi" -ForegroundColor Green
    Write-Host "Proximo passo: siga $($caminhos.Scripts)\CHECKLIST.md (troca do endereco nos caixas e testes de queda)."
}
