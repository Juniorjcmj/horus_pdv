# Funcoes compartilhadas pelos scripts do instalador do servidor da loja (D12).
# Uso: . "$PSScriptRoot\lib\comum.ps1"   (ou . "$PSScriptRoot\comum.ps1" dentro de lib\)
# Mantidas pequenas e sem efeito colateral por padrao, para serem testadas isoladas (testes\Testar-Logica.ps1).
# (sem Set-StrictMode: dot-source herdaria o modo estrito para o script chamador, e as respostas JSON omitem campos nulos)

$script:Utf8SemBom = New-Object System.Text.UTF8Encoding($false)

# --- Segredos ---------------------------------------------------------------------------------------------------
function New-SegredoAleatorio {
    <# Segredo base64url (sem +, / e =) com $Bytes bytes de entropia. 48 bytes = 64 caracteres. #>
    param([int]$Bytes = 48)
    $buf = New-Object byte[] $Bytes
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($buf) } finally { $rng.Dispose() }
    return ([Convert]::ToBase64String($buf)).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

function New-SenhaSql {
    <# Senha que atende a politica do SQL Server (maiuscula, minuscula, digito e simbolo), sem aspas nem ';'. #>
    param([int]$Tamanho = 24)
    $grupos = @('ABCDEFGHJKLMNPQRSTUVWXYZ', 'abcdefghijkmnopqrstuvwxyz', '23456789', '#%*+-_')
    $todos = ($grupos -join '')
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $sorteio = {
            param($alfabeto)
            $b = New-Object byte[] 4
            $rng.GetBytes($b)
            return $alfabeto[[int]([BitConverter]::ToUInt32($b, 0) % $alfabeto.Length)]
        }
        $chars = New-Object System.Collections.Generic.List[char]
        foreach ($g in $grupos) { $chars.Add((& $sorteio $g)) }
        while ($chars.Count -lt $Tamanho) { $chars.Add((& $sorteio $todos)) }
        for ($i = $chars.Count - 1; $i -gt 0; $i--) {
            $b = New-Object byte[] 4
            $rng.GetBytes($b)
            $j = [int]([BitConverter]::ToUInt32($b, 0) % ($i + 1))
            $t = $chars[$i]; $chars[$i] = $chars[$j]; $chars[$j] = $t
        }
    } finally { $rng.Dispose() }
    return -join $chars
}

# --- Arquivo loja.env (formato .properties lido pelo Spring: spring.config.import=...loja.env[.properties]) ---------
function Format-ValorEnv {
    <# Escapa barra invertida, quebra de linha e o espaco inicial, como exige o formato .properties. #>
    param([string]$Valor)
    $v = $Valor.Replace('\', '\\').Replace("`r", '\r').Replace("`n", '\n')
    if ($v.StartsWith(' ')) { $v = '\' + $v }
    return $v
}

function Write-ArquivoTextoUtf8 {
    <# Grava UTF-8 SEM BOM (o Jackson da API nao le BOM) de forma atomica: escreve num .tmp e troca. #>
    param([Parameter(Mandatory)][string]$Caminho, [Parameter(Mandatory)][string]$Conteudo)
    $pasta = Split-Path -Parent $Caminho
    if ($pasta -and -not (Test-Path -LiteralPath $pasta)) { New-Item -ItemType Directory -Path $pasta -Force | Out-Null }
    $tmp = "$Caminho.tmp"
    [System.IO.File]::WriteAllText($tmp, $Conteudo, $script:Utf8SemBom)
    if (Test-Path -LiteralPath $Caminho) { Remove-Item -LiteralPath $Caminho -Force }
    Move-Item -LiteralPath $tmp -Destination $Caminho -Force
}

function ConvertTo-ConteudoLojaEnv {
    param([Parameter(Mandatory)][System.Collections.IDictionary]$Valores, [string]$Cabecalho = '')
    $linhas = New-Object System.Collections.Generic.List[string]
    if ($Cabecalho) { foreach ($l in $Cabecalho -split "`n") { $linhas.Add("# $l") } }
    foreach ($k in $Valores.Keys) { $linhas.Add("$k=$(Format-ValorEnv ([string]$Valores[$k]))") }
    return (($linhas -join "`r`n") + "`r`n")
}

# --- Estado e retencao do backup --------------------------------------------------------------------------------
function Read-EstadoBackup {
    param([Parameter(Mandatory)][string]$Caminho)
    if (-not (Test-Path -LiteralPath $Caminho)) { return $null }
    try { return (Get-Content -LiteralPath $Caminho -Raw -Encoding UTF8 | ConvertFrom-Json) } catch { return $null }
}

function Write-EstadoBackup {
    <# Formato combinado com o Java (EstadoBackup): {"ultimoBackupEm":"<ISO>","sucesso":bool,"erro":"<texto>|null"}.
       Em falha, preserva o ultimoBackupEm do ultimo sucesso (para a situacao mostrar ha quanto tempo nao ha backup). #>
    param([Parameter(Mandatory)][string]$Caminho, [Parameter(Mandatory)][bool]$Sucesso,
          [Nullable[datetimeoffset]]$Quando = $null, [string]$Erro = $null)
    $ultimo = $null
    if ($Sucesso) {
        $q = if ($Quando) { [datetimeoffset]$Quando } else { [datetimeoffset]::Now }
        $ultimo = $q.ToString('yyyy-MM-ddTHH:mm:ss.fffzzz')
    } else {
        $anterior = Read-EstadoBackup -Caminho $Caminho
        if ($anterior -and $anterior.PSObject.Properties['ultimoBackupEm']) { $ultimo = $anterior.ultimoBackupEm }
    }
    $obj = [ordered]@{ ultimoBackupEm = $ultimo; sucesso = $Sucesso; erro = $(if ($Sucesso) { $null } else { $Erro }) }
    Write-ArquivoTextoUtf8 -Caminho $Caminho -Conteudo ($obj | ConvertTo-Json -Compress)
}

function Get-NomeBackup {
    param([datetime]$Quando = (Get-Date), [string]$Banco = 'QuackLoja')
    return ('quack-{0}-{1}.bak' -f $Banco.ToLowerInvariant(), $Quando.ToString('yyyyMMdd-HHmmss'))
}

function Get-BackupsParaRemover {
    <# Devolve os arquivos alem dos $Manter mais recentes (o nome ordena por data). Nao apaga nada. #>
    param([Parameter(Mandatory)][string]$Pasta, [int]$Manter = 7, [string]$Banco = 'QuackLoja')
    if ($Manter -lt 1) { throw 'A retencao minima e 1 backup.' }
    if (-not (Test-Path -LiteralPath $Pasta)) { return @() }
    $padrao = 'quack-{0}-*.bak' -f $Banco.ToLowerInvariant()
    $todos = @(Get-ChildItem -LiteralPath $Pasta -Filter $padrao -File | Sort-Object Name -Descending)
    if ($todos.Count -le $Manter) { return @() }
    return @($todos | Select-Object -Skip $Manter)
}

# --- Rede -------------------------------------------------------------------------------------------------------
function Get-SubredeCidr {
    <# Endereco de rede em CIDR a partir de IP + prefixo, ex.: 192.168.1.37/24 -> 192.168.1.0/24. #>
    param([Parameter(Mandatory)][string]$Ip, [Parameter(Mandatory)][ValidateRange(8, 30)][int]$Prefixo)
    $ipb = [System.Net.IPAddress]::Parse($Ip).GetAddressBytes()
    if ($ipb.Length -ne 4) { throw 'Somente IPv4.' }
    $mascara = [uint32]([uint64]4294967296 - [uint64][math]::Pow(2, 32 - $Prefixo))
    $ipn = ([uint32]$ipb[0] -shl 24) -bor ([uint32]$ipb[1] -shl 16) -bor ([uint32]$ipb[2] -shl 8) -bor [uint32]$ipb[3]
    $rede = $ipn -band $mascara
    $b = @((($rede -shr 24) -band 255), (($rede -shr 16) -band 255), (($rede -shr 8) -band 255), ($rede -band 255))
    return ('{0}/{1}' -f ($b -join '.'), $Prefixo)
}

function Test-SubredeValida {
    param([string]$Cidr)
    if ($Cidr -notmatch '^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})/(\d{1,2})$') { return $false }
    foreach ($i in 1..4) { if ([int]$Matches[$i] -gt 255) { return $false } }
    $p = [int]$Matches[5]
    return ($p -ge 8 -and $p -le 30)
}

# --- SQL Server via .NET (sem depender do sqlcmd) -----------------------------------------------------------------
function Invoke-SqlComando {
    <# Executa um comando (ou escalar com -Escalar). Tempo limite 0 = sem limite (BACKUP/RESTORE). #>
    param([Parameter(Mandatory)][string]$StringConexao, [Parameter(Mandatory)][string]$Sql,
          [int]$TempoLimite = 0, [switch]$Escalar)
    $con = New-Object System.Data.SqlClient.SqlConnection $StringConexao
    try {
        $con.Open()
        $cmd = $con.CreateCommand()
        $cmd.CommandText = $Sql
        $cmd.CommandTimeout = $TempoLimite
        if ($Escalar) { return $cmd.ExecuteScalar() }
        [void]$cmd.ExecuteNonQuery()
    } finally { $con.Dispose() }
}

function Get-MensagemErro {
    <# Mensagem da causa raiz: o PowerShell embrulha erros de metodo .NET em "Excecao ao chamar ...". #>
    param([Parameter(Mandatory)][System.Exception]$Excecao)
    $e = $Excecao
    while ($e.InnerException) { $e = $e.InnerException }
    return $e.Message
}

function Format-LiteralSql {
    <# Literal N'...' seguro para caminho de arquivo e nome de banco. #>
    param([string]$Texto)
    return "N'" + $Texto.Replace("'", "''") + "'"
}

function Format-IdentificadorSql {
    param([string]$Nome)
    return '[' + $Nome.Replace(']', ']]') + ']'
}

function Get-StringConexaoSql {
    param([string]$Servidor = 'localhost,1433', [string]$Banco = 'master', [string]$Usuario = '', [string]$Senha = '')
    $b = "Server=$Servidor;Database=$Banco;TrustServerCertificate=True;Encrypt=True;Connect Timeout=30;"
    if ($Usuario) { return $b + "User Id=$Usuario;Password=$Senha;" }
    return $b + 'Integrated Security=SSPI;'
}

function Initialize-BancoLoja {
    <# Cria o banco (recuperacao SIMPLE: ha backup completo diario e nao ha backup de log), o login quack_app e o poe
       como db_owner (o Flyway da API cria o esquema). Idempotente: repetir reajusta a senha do login. #>
    param([Parameter(Mandatory)][string]$StringConexaoMaster, [Parameter(Mandatory)][string]$StringConexaoBanco,
          [Parameter(Mandatory)][string]$Banco, [Parameter(Mandatory)][string]$SenhaApp)
    $bd = Format-IdentificadorSql $Banco
    $bdLit = Format-LiteralSql $Banco
    $pw = Format-LiteralSql $SenhaApp
    Invoke-SqlComando -StringConexao $StringConexaoMaster -Sql "IF DB_ID($bdLit) IS NULL CREATE DATABASE $bd; ALTER DATABASE $bd SET RECOVERY SIMPLE;"
    Invoke-SqlComando -StringConexao $StringConexaoMaster -Sql @"
IF SUSER_ID(N'quack_app') IS NULL CREATE LOGIN [quack_app] WITH PASSWORD = $pw, CHECK_POLICY = OFF
ELSE ALTER LOGIN [quack_app] WITH PASSWORD = $pw;
"@
    Invoke-SqlComando -StringConexao $StringConexaoBanco -Sql @"
IF USER_ID(N'quack_app') IS NULL CREATE USER [quack_app] FOR LOGIN [quack_app];
ALTER ROLE db_owner ADD MEMBER [quack_app];
"@
}

# --- instalacao.json: caminhos e nomes que o instalador decidiu, lidos pelos demais scripts -----------------------
function Read-InstalacaoLoja {
    param([Parameter(Mandatory)][string]$Destino)
    $arq = Join-Path $Destino 'config\instalacao.json'
    if (-not (Test-Path -LiteralPath $arq)) { return $null }
    return (Get-Content -LiteralPath $arq -Raw -Encoding UTF8 | ConvertFrom-Json)
}
