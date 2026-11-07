<#
.SYNOPSIS
    Teste de ponta a ponta do monitoramento (change api-java-monitoramento), tudo em Docker, sem tocar em nada de producao.
.DESCRIPTION
    Sobe numa rede Docker descartavel: SQL Server, a API .NET (so para criar o esquema do banco, como na producao), o
    receptor da nuvem (API Java, perfil "nuvem", com QUACK_METRICS_TOKEN), o Prometheus e o Grafana (imagens construidas
    de monitoramento/). Uma "loja de teste" (servidor registrado direto no banco) envia o relato de estado. Confere:
      - o arquivo de credencial do Prometheus tem modo 0400 e o alvo quack-sync fica up=1;
      - quack_loja_backup_sucesso (do relato) e quack_sync_ultimo_contato_segundos existem no Prometheus;
      - Grafana: /api/health 200, a raiz sem login redireciona para /login, /api/search anonimo = 401 e, autenticado,
        lista os paineis "Sincronizacao das lojas" e "API Java";
      - as consultas de cada painel, executadas pelo proprio Grafana (/api/ds/query), devolvem dados (os paineis de
        eventos raros - lotes, entregas de comandos, conflitos - podem vir vazios e so sao informados).
    Demora uns 4 a 6 minutos (cerca de 2 minutos so esperando as raspagens de 30 s do Prometheus).
.PARAMETER Jar
    Jar da API Java (padrao: JAVA\api\target\quack-pdv-api-*.jar; gere com "mvn -DskipTests package" em JAVA\api).
.PARAMETER ImagemDotnet
    Imagem da API .NET (padrao quack-api-dotnet:contrato; se faltar, rode JAVA\api\contrato\ambiente-dotnet\subir.sh).
.PARAMETER Manter
    Nao remove os containers no fim (para inspecionar). Remova com: docker rm -f $(docker ps -aqf name=qmon-)
#>
param(
    [string]$Jar,
    [string]$ImagemDotnet = 'quack-api-dotnet:contrato',
    [int]$PortaApi = 18081,
    [int]$PortaPrometheus = 19090,
    [int]$PortaGrafana = 13000,
    [int]$PortaDotnet = 15271,
    [switch]$Manter
)
$ErrorActionPreference = 'Stop'
$raiz = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$sufixo = [guid]::NewGuid().ToString('N').Substring(0, 6)
$rede = "qmon-net-$sufixo"
$nSql = "qmon-sql-$sufixo"; $nDotnet = "qmon-dotnet-$sufixo"; $nApi = "qmon-api-$sufixo"
$nProm = "qmon-prom-$sufixo"; $nGraf = "qmon-graf-$sufixo"
$imgApi = "qmon-api-teste:$sufixo"; $imgProm = "quack-monitor-prometheus:teste"; $imgGraf = "quack-monitor-grafana:teste"
$saPwd = 'Monit#Quack2026x'
$tokenMetricas = 'metricas-' + [guid]::NewGuid().ToString('N')
$tokenLoja = 'loja-' + [guid]::NewGuid().ToString('N')
$senhaGrafana = 'Gf-' + [guid]::NewGuid().ToString('N').Substring(0, 12)
$empresa = 'empresa-monitor'
$falhas = 0
function Confirmar([string]$Nome, [bool]$Condicao, [string]$Detalhe = '') {
    if ($Condicao) { Write-Host "[ OK ] $Nome" -ForegroundColor Green }
    else { Write-Host "[FALHA] $Nome $Detalhe" -ForegroundColor Red; $script:falhas++ }
}
function Aviso([string]$Texto) { Write-Host "[INFO] $Texto" -ForegroundColor Yellow }

# docker escreve avisos em stderr; com $ErrorActionPreference='Stop' isso viraria excecao no Windows PowerShell 5.1
function Dk {
    $anterior = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try { $saida = & docker.exe @args 2>&1; if ($LASTEXITCODE -ne 0) { throw "docker $($args -join ' ') falhou ($LASTEXITCODE): $saida" }; return $saida }
    finally { $ErrorActionPreference = $anterior }
}
function Sql([string]$Comando) {
    $anterior = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try { return (& docker.exe exec $nSql /opt/mssql-tools18/bin/sqlcmd -C -I -h -1 -W -S localhost -U sa -P $saPwd -Q $Comando 2>&1) }
    finally { $ErrorActionPreference = $anterior }
}
function SqlNum([string]$Comando) {
    $linha = @(Sql $Comando) | ForEach-Object { "$_".Trim() } | Where-Object { $_ -match '^\d+$' } | Select-Object -First 1
    if ($null -eq $linha) { return -1 }
    return [int]$linha
}
function Esperar([string]$O, [int]$Segundos, [scriptblock]$Condicao) {
    $fim = (Get-Date).AddSeconds($Segundos)
    while ((Get-Date) -lt $fim) { try { if (& $Condicao) { return $true } } catch { } ; Start-Sleep -Seconds 3 }
    Write-Host "[FALHA] Tempo esgotado esperando: $O" -ForegroundColor Red
    return $false
}
function Get-Sha256Hex([string]$Texto) {
    $sha = [Security.Cryptography.SHA256]::Create()
    return (($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($Texto)) | ForEach-Object { $_.ToString('x2') }) -join '')
}
function Http([string]$Metodo, [string]$Url, $Cabecalhos = @{}, $Corpo = $null, [switch]$SemRedirecionar) {
    $p = @{ Method = $Metodo; Uri = $Url; Headers = $Cabecalhos; UseBasicParsing = $true; TimeoutSec = 30 }
    if ($null -ne $Corpo) { $p.Body = $Corpo; $p.ContentType = 'application/json' }
    if ($SemRedirecionar) { $p.MaximumRedirection = 0 }
    try {
        $r = Invoke-WebRequest @p
        $c = $r.Content; if ($c -is [byte[]]) { $c = [Text.Encoding]::UTF8.GetString($c) }
        return @{ Status = [int]$r.StatusCode; Corpo = $c; Local = $r.Headers['Location'] }
    }
    catch {
        $resp = $_.Exception.Response
        if ($null -eq $resp) { return @{ Status = 0; Corpo = $_.Exception.Message; Local = $null } }
        $texto = ''
        try { $texto = (New-Object IO.StreamReader($resp.GetResponseStream())).ReadToEnd() } catch { }
        return @{ Status = [int]$resp.StatusCode; Corpo = $texto; Local = $resp.Headers['Location'] }
    }
}
function Prom([string]$Consulta) {
    $r = Http 'GET' ("http://localhost:$PortaPrometheus/api/v1/query?query=" + [uri]::EscapeDataString($Consulta))
    if ($r.Status -ne 200) { return @() }
    # virgula: sem ela o PowerShell 5.1 desembrulha a lista de 1 item e .Count deixa de funcionar
    return ,@(($r.Corpo | ConvertFrom-Json).data.result)
}

# --- pre-requisitos -------------------------------------------------------------------------------------------------
if (-not $Jar) {
    $Jar = (Get-ChildItem -Path (Join-Path $raiz 'JAVA\api\target') -Filter 'quack-pdv-api-*.jar' | Where-Object { $_.Name -notlike '*.original' } | Select-Object -First 1).FullName
}
if (-not $Jar -or -not (Test-Path $Jar)) { throw 'Jar da API Java nao encontrado. Rode "mvn -DskipTests package" em JAVA\api ou informe -Jar.' }
Dk image inspect $ImagemDotnet | Out-Null

try {
    Write-Host "== Construindo as imagens do monitoramento e da API de teste ==" -ForegroundColor Cyan
    Dk build -q -t $imgProm (Join-Path $raiz 'monitoramento\prometheus') | Out-Null
    Dk build -q -t $imgGraf (Join-Path $raiz 'monitoramento\grafana') | Out-Null
    $ctx = Join-Path ([IO.Path]::GetTempPath()) "qmon-ctx-$sufixo"
    New-Item -ItemType Directory -Path $ctx -Force | Out-Null
    Copy-Item $Jar (Join-Path $ctx 'app.jar')
    Set-Content -Path (Join-Path $ctx 'Dockerfile') -Encoding ASCII -Value @(
        'FROM eclipse-temurin:21-jre', 'COPY app.jar /app/app.jar', 'WORKDIR /app',
        'ENTRYPOINT ["java","-jar","/app/app.jar"]')
    Dk build -q -t $imgApi $ctx | Out-Null

    Write-Host "== Subindo SQL Server e a API .NET (cria o esquema) ==" -ForegroundColor Cyan
    Dk network create $rede | Out-Null
    Dk run -d --name $nSql --network $rede -e ACCEPT_EULA=Y -e "MSSQL_SA_PASSWORD=$saPwd" mcr.microsoft.com/mssql/server:2022-latest | Out-Null
    if (-not (Esperar 'SQL Server' 120 { (SqlNum 'SELECT 1') -eq 1 })) { throw 'SQL Server nao subiu.' }
    $cs = "Server=$nSql,1433;Database=HorusPdv;User Id=sa;Password=$saPwd;TrustServerCertificate=True;Encrypt=True;MultipleActiveResultSets=True"
    Dk run -d --name $nDotnet --network $rede -p "${PortaDotnet}:8080" -e ASPNETCORE_ENVIRONMENT=Production `
        -e "ConnectionStrings__HorusPdv=$cs" -e 'Auth__JwtSecret=segredo-jwt-do-contrato-0123456789-abcdefghijkl' `
        -e 'Security__EncryptionKey=chave-de-cifra-do-contrato-0123456789-abcdefgh' -e Recaptcha__Enabled=false `
        -e Email__Enabled=false $ImagemDotnet | Out-Null
    if (-not (Esperar 'API .NET' 180 { (Http 'GET' "http://localhost:$PortaDotnet/api/Auth/me").Status -eq 401 })) { throw 'API .NET nao subiu.' }
    # a .NET cria as tabelas depois de abrir a porta: espera o esquema estabilizar (>= 38 tabelas sincronizadas + migracoes)
    if (-not (Esperar 'esquema do banco' 180 { (SqlNum "SELECT COUNT(*) FROM HorusPdv.sys.tables") -ge 40 })) { throw 'Esquema nao foi criado.' }
    Start-Sleep -Seconds 10

    Write-Host "== Subindo o receptor (nuvem), o Prometheus e o Grafana ==" -ForegroundColor Cyan
    Dk run -d --name $nApi --network $rede --network-alias pdv-api-sync -p "${PortaApi}:8080" `
        -e SPRING_PROFILES_ACTIVE=nuvem -e "SPRING_DATASOURCE_URL=jdbc:sqlserver://${nSql}:1433;databaseName=HorusPdv;encrypt=true;trustServerCertificate=true" `
        -e SPRING_DATASOURCE_USERNAME=sa -e "SPRING_DATASOURCE_PASSWORD=$saPwd" `
        -e 'QUACK_AUTH_JWT_SECRET=segredo-jwt-do-contrato-0123456789-abcdefghijkl' `
        -e 'QUACK_SECURITY_ENCRYPTION_KEY=chave-de-cifra-do-contrato-0123456789-abcdefgh' `
        -e "QUACK_METRICS_TOKEN=$tokenMetricas" -e QUACK_SYNC_COMANDOS_ESPERA_MS=1000 -e QUACK_SYNC_ALERTAS_ATIVO=false `
        $imgApi | Out-Null
    $apiOk = Esperar 'receptor (health UP)' 180 { (Http 'GET' "http://localhost:$PortaApi/actuator/health").Corpo -match '"status":"UP"' }
    Confirmar 'receptor sobe no perfil nuvem (health UP, sem autenticacao)' $apiOk (Dk logs --tail 30 $nApi | Out-String)
    if (-not $apiOk) { throw 'Receptor nao subiu.' }

    Dk run -d --name $nProm --network $rede --network-alias pdv-monitor-prometheus -p "${PortaPrometheus}:9090" `
        -e "METRICS_TOKEN=$tokenMetricas" $imgProm | Out-Null
    Dk run -d --name $nGraf --network $rede --network-alias pdv-monitor-grafana -p "${PortaGrafana}:3000" `
        -e "GF_SECURITY_ADMIN_PASSWORD=$senhaGrafana" $imgGraf | Out-Null

    # --- a rota de metricas e protegida ----------------------------------------------------------------------------
    $semToken = Http 'GET' "http://localhost:$PortaApi/actuator/prometheus"
    Confirmar 'metricas sem token = 401' ($semToken.Status -eq 401)
    $comToken = Http 'GET' "http://localhost:$PortaApi/actuator/prometheus" @{ Authorization = "Bearer $tokenMetricas" }
    Confirmar 'metricas com token = 200 e jvm_memory_used_bytes' (($comToken.Status -eq 200) -and ($comToken.Corpo -match 'jvm_memory_used_bytes'))
    Confirmar 'actuator/env = 404 no perfil nuvem' ((Http 'GET' "http://localhost:$PortaApi/actuator/env").Status -eq 404)

    # --- a loja de teste: servidor registrado direto no banco e relato de estado ---------------------------------------
    $hash = Get-Sha256Hex $tokenLoja
    Sql "INSERT INTO HorusPdv.dbo.SyncServidores (Id, CompanyId, Nome, TokenHash, Status, AtivadoEm) VALUES (N'srv-monitor', N'$empresa', N'Loja de Teste', N'$hash', N'ativo', SYSDATETIMEOFFSET())" | Out-Null
    $cabLoja = @{ Authorization = "LojaServidor $tokenLoja" }
    $backupEm = (Get-Date).AddHours(-3).ToString("yyyy-MM-dd'T'HH:mm:ss.fffzzz")
    $relato = @{ geradoEm = (Get-Date).ToUniversalTime().ToString("yyyy-MM-dd'T'HH:mm:ss'Z'"); atrasoSegundos = 7; pausada = $false
        ultimoBackupEm = $backupEm; backupSucesso = $true; backupErro = $null; nfcePendentes = 1; versaoPrograma = '9.9.9-teste' } | ConvertTo-Json
    function Gerar-Trafego {
        $r = Http 'POST' "http://localhost:$PortaApi/api/loja-sync/estado" $cabLoja $relato
        if ($r.Status -ne 200) { Write-Host "relato respondeu $($r.Status): $($r.Corpo)" -ForegroundColor Yellow }
        Http 'GET' "http://localhost:$PortaApi/api/loja-sync/comandos?apos=0" $cabLoja | Out-Null
        Http 'POST' "http://localhost:$PortaApi/api/loja-sync/estado" @{ Authorization = 'LojaServidor invalido' } $relato | Out-Null   # 401
        Http 'GET' "http://localhost:$PortaApi/rota-que-nao-existe" | Out-Null                                                           # 404
        Http 'GET' "http://localhost:$PortaApi/actuator/health" | Out-Null
        return $r
    }
    $r1 = Gerar-Trafego
    Confirmar 'relato da loja de teste aceito (200)' ($r1.Status -eq 200) "status $($r1.Status): $($r1.Corpo)"
    $linha = Sql "SELECT BackupSucesso, AtrasoSegundos, VersaoPrograma FROM HorusPdv.dbo.SyncEstadoLoja WHERE CompanyId = N'$empresa'"
    Confirmar 'SyncEstadoLoja guardou o relato' (($linha -join ' ') -match '9\.9\.9-teste')

    # --- Prometheus ---------------------------------------------------------------------------------------------------
    $modo = (Dk exec $nProm ls -l /etc/prometheus/metrics-token) -join ''
    Confirmar 'credencial do Prometheus com modo 0400' ($modo -match '^-r--------')
    $up = Esperar 'alvo quack-sync up=1' 150 { $null = Gerar-Trafego; $q = Prom 'up{job="quack-sync"}'; ($q.Count -eq 1) -and ($q[0].value[1] -eq '1') }
    Confirmar 'Prometheus raspa o receptor: up{job="quack-sync"} = 1' $up
    Write-Host "Esperando mais raspagens para os paineis com rate() terem pontos suficientes..." -ForegroundColor Cyan
    foreach ($i in 1..8) { $null = Gerar-Trafego; Start-Sleep -Seconds 12 }
    Confirmar 'quack_loja_backup_sucesso existe (vindo do relato)' ((Prom "quack_loja_backup_sucesso{empresa=`"$empresa`"}").Count -eq 1)
    Confirmar 'quack_sync_ultimo_contato_segundos existe' ((Prom "quack_sync_ultimo_contato_segundos{empresa=`"$empresa`"}").Count -eq 1)
    Confirmar 'quack_loja_versao_programa traz a versao do relato' ((Prom 'quack_loja_versao_programa{versao="9.9.9-teste"}').Count -eq 1)
    Confirmar 'histograma de requisicoes com application="nuvem"' ((Prom 'http_server_requests_seconds_bucket{application="nuvem",le="+Inf"}').Count -ge 1)
    $composeTxt = Get-Content (Join-Path $raiz 'docker-compose.yml') -Raw
    $blocoProm = ''
    if ($composeTxt -match '(?s)\n  pdv-monitor-prometheus:.*?(?=\r?\n  # Grafana|\r?\n  pdv-monitor-grafana:)') { $blocoProm = $Matches[0] }
    $semComentarios = ($blocoProm -split "`n" | Where-Object { $_ -notmatch '^\s*#' }) -join "`n"
    Confirmar 'docker-compose: Prometheus sem labels do Traefik' (($blocoProm.Length -gt 50) -and ($semComentarios -notmatch 'traefik'))

    # --- Grafana ------------------------------------------------------------------------------------------------------
    $gOk = Esperar 'Grafana /api/health' 120 { (Http 'GET' "http://localhost:$PortaGrafana/api/health").Status -eq 200 }
    Confirmar 'Grafana /api/health = 200 (volume vazio)' $gOk
    # curl.exe: o Invoke-WebRequest do PowerShell 5.1 trata o 302 com MaximumRedirection 0 como erro e perde o status
    $saidaCurl = (& curl.exe -s -o NUL -w '%{http_code} %{redirect_url}' "http://localhost:$PortaGrafana/") -split ' ', 2
    $raizG = @{ Status = [int]$saidaCurl[0]; Local = $(if ($saidaCurl.Count -gt 1) { $saidaCurl[1] } else { '' }) }
    Confirmar 'Grafana sem login redireciona para /login' (($raizG.Status -eq 302) -and ("$($raizG.Local)" -match '/login')) "(status $($raizG.Status), Location '$($raizG.Local)')"
    Confirmar 'Grafana /api/search anonimo = 401' ((Http 'GET' "http://localhost:$PortaGrafana/api/search").Status -eq 401)
    $auth = @{ Authorization = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("admin:$senhaGrafana")) }
    $busca = Http 'GET' "http://localhost:$PortaGrafana/api/search?type=dash-db" $auth
    $titulos = @(($busca.Corpo | ConvertFrom-Json) | ForEach-Object { $_.title })
    Confirmar 'Grafana autenticado lista os dois paineis' (($titulos -contains 'Sincronizacao das lojas') -and ($titulos -contains 'API Java')) ($titulos -join ', ')
    $ds = ((Http 'GET' "http://localhost:$PortaGrafana/api/datasources" $auth).Corpo | ConvertFrom-Json)
    Confirmar 'fonte de dados Prometheus provisionada (somente leitura)' (($ds.Count -ge 1) -and ($ds[0].uid -eq 'prometheus-quack') -and $ds[0].readOnly)

    $obrigatorios = @{
        'quack-sync-lojas' = @('Lojas', 'Lojas com servidor ativo', 'Atraso do envio (relatado pela loja)', 'Segundos desde o ultimo contato', 'Idade do backup (ultimo bem-sucedido)')
        'quack-api-java'   = @('Requisicoes por segundo por rota', 'P95 por rota (sem o long-poll de comandos)', 'P99 por rota (sem o long-poll de comandos)',
            'Respostas 4xx e 5xx por segundo', 'Pool de conexoes (Hikari)', 'Threads da JVM (inclui virtuais)', 'Memoria heap', 'Ciclo do detector de edicoes (duracao media)')
    }
    foreach ($uid in $obrigatorios.Keys) {
        $d = ((Http 'GET' "http://localhost:$PortaGrafana/api/dashboards/uid/$uid" $auth).Corpo | ConvertFrom-Json).dashboard
        Confirmar "painel $uid carregado do provisionamento" ($null -ne $d)
        foreach ($painel in $d.panels) {
            $consultas = @()
            foreach ($t in $painel.targets) {
                $q = @{ refId = $t.refId; datasource = @{ uid = 'prometheus-quack'; type = 'prometheus' }; expr = $t.expr
                    instant = [bool]$t.instant; range = (-not [bool]$t.instant); intervalMs = 30000; maxDataPoints = 300; format = $(if ($t.format) { $t.format } else { 'time_series' }) }
                $consultas += $q
            }
            $corpo = @{ queries = $consultas; from = 'now-15m'; to = 'now' } | ConvertTo-Json -Depth 8
            $resp = Http 'POST' "http://localhost:$PortaGrafana/api/ds/query" $auth $corpo
            $comDados = $false
            if ($resp.Status -eq 200) {
                $res = ($resp.Corpo | ConvertFrom-Json).results
                foreach ($ref in $res.PSObject.Properties) {
                    if ($ref.Value.error) { Write-Host "  erro na consulta $($ref.Name) de '$($painel.title)': $($ref.Value.error)" -ForegroundColor Red }
                    foreach ($f in @($ref.Value.frames)) { if ($f.data.values -and $f.data.values[0].Count -gt 0) { $comDados = $true } }
                }
            }
            if ($obrigatorios[$uid] -contains $painel.title) { Confirmar "[$uid] '$($painel.title)' devolve dados" $comDados "(HTTP $($resp.Status))" }
            elseif ($comDados) { Write-Host "[ OK ] [$uid] '$($painel.title)' devolve dados" -ForegroundColor Green }
            else { Aviso "[$uid] '$($painel.title)' sem dados neste cenario (evento que o teste nao provoca)" }
        }
    }
}
catch {
    Write-Host "[FALHA] $($_.Exception.Message)" -ForegroundColor Red
    $falhas++
}
finally {
    if (-not $Manter) {
        $anterior = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
        foreach ($c in @($nGraf, $nProm, $nApi, $nDotnet, $nSql)) { & docker.exe rm -f $c 2>&1 | Out-Null }
        & docker.exe network rm $rede 2>&1 | Out-Null
        & docker.exe rmi $imgApi 2>&1 | Out-Null
        $ErrorActionPreference = $anterior
        if ($ctx -and (Test-Path $ctx)) { Remove-Item -Recurse -Force $ctx }
    } else { Aviso "Containers mantidos (sufixo $sufixo). Remova com: docker rm -f $nGraf $nProm $nApi $nDotnet $nSql ; docker network rm $rede" }
}
Write-Host ''
if ($falhas -gt 0) { Write-Host "$falhas falha(s)." -ForegroundColor Red; exit 1 }
Write-Host 'Monitoramento verificado de ponta a ponta.' -ForegroundColor Green
exit 0
