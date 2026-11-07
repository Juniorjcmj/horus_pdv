# Design

## Context

- Motivação em `proposal.md`; requisitos em `specs/plataforma/monitoramento/spec.md`.
- API Java em `JAVA/api` (Spring Boot 3.5.16, Java 21, `spring-boot-starter-actuator` já presente). Hoje só `health`
  é exposto (`management.endpoints.web.exposure.include: health`).
- Três filtros liberam explicitamente `/actuator/health`: `ModoNuvemFilter` (perfil `nuvem`: só `/api/loja-sync/**` e
  health), `AutenticacaoFilter` (JWT) e `RecuperacaoFilter`. Qualquer rota nova de actuator precisa passar por eles.
- O receptor (`pdv-api-sync`) roda na stack `pdv` do Portainer (Swarm, nó OrionDesigner, rede `OrionNet`, Traefik com
  `letsencrypt`), imagem `ghcr.io/juniorjcmj/quack-pdv-api-java:spring-api` (privada: o nó precisou de
  `docker login ghcr.io` + `docker service update --with-registry-auth`). A stack de produção é o compose antigo + o
  bloco do `pdv-api-sync` colado à mão (sem `profiles`). O `docker-compose.yml` do repositório é a referência.
- A nuvem já sabe por empresa: `SyncServidores` (Status, `UltimoContato`, Nome), `SyncEstadoNuvem`
  (`UltimaSincronizacaoEm`, `AplicadoAte`), `SyncComandos` (Status, `AtualizadoEm`), `SyncQuarentena` (`ResolvidoEm`),
  `SyncAlertas` (Motivo, `UltimaEm`). `SituacaoNuvem` já faz essas contagens para a rota de situação.
- Só a loja sabe: atraso do envio, `backup-estado.json` (`quack.sync.backup-estado`, lido por `EstadoBackup.ler`),
  NFC-e pendentes, pausa. `SituacaoLoja.consultar` já monta tudo isso em `SituacaoSync`.
- A loja fica atrás do roteador da loja (192.168.0.15, sem IP público): a nuvem não consegue raspar a loja.

## Goals / Non-Goals

**Goals:**
- Ver em um painel, por loja, se a sincronização e o backup estão em dia, com histórico de 30 dias.
- Medir P95/P99 das rotas, pool Hikari e threads do receptor (validar as virtual threads).
- Nenhum risco para a sincronização: métrica e relato nunca derrubam, atrasam ou travam envio/comandos.

**Non-Goals:**
- Regras de alerta no Grafana (os alertas por e-mail de `EnviadorAlertas` continuam sendo o canal de aviso). Pode vir
  numa change futura usando as mesmas métricas.
- Métricas da API .NET, do SQL Server (exportador) ou do host (node-exporter).
- Prometheus/Grafana dentro da loja.
- Rastreamento distribuído (tracing) e agregação de logs.

## Decisions

### M1. Micrometer + `micrometer-registry-prometheus`, versão gerenciada pelo Spring Boot
Já vem integrado ao Actuator: `http.server.requests`, JVM, GC, threads, Hikari, Tomcat sem código. Alternativa
(OpenTelemetry + coletor OTLP) traz mais uma peça para operar sem ganho para o objetivo atual.

Configuração em `application.yml` (vale para os dois perfis):
- `management.endpoints.web.exposure.include: health,prometheus`
- `management.metrics.tags.application: ${quack.modo}` (resulta `nuvem` ou `loja`; no perfil padrão, sem modo, usar
  `api`)
- `management.metrics.distribution.percentiles-histogram.http.server.requests: true`
- `management.metrics.distribution.slo.http.server.requests: 100ms,300ms,1s,3s` (buckets úteis para o painel)
- `management.observations.http.server.requests.name` padrão; nada de `uri` com valor concreto (o Spring já usa o
  modelo da rota; requisições 404 caem em `uri="/**"`/`UNKNOWN`, sem explodir cardinalidade).

### M2. Token próprio de métricas, conferido num filtro antes da autenticação
`quack.metrics.token` (variável `QUACK_METRICS_TOKEN`, padrão vazio). Filtro `MetricasTokenFilter` (ordem antes de
`ModoNuvemFilter`/`AutenticacaoFilter`) que só atua em `/actuator/prometheus`:
- token vazio → 404 com o corpo padrão de rota inexistente (`RespostaJson`);
- sem `Authorization: Bearer …` ou diferente → 401 (`MessageDigest.isEqual` sobre os bytes UTF-8);
- correto → segue a cadeia, e os outros três filtros liberam `/actuator/prometheus` como já liberam health.

Alternativas: (a) `management.server.port` separado sem rota no Traefik — exigiria mudar health check do Dockerfile,
do instalador e do Traefik, e na loja o porta ficaria aberto na rede sem senha; (b) usuário admin JWT — o Prometheus
não renova JWT de 8 h. Token fixo por ambiente é o padrão do Prometheus (`authorization.credentials_file`).

Só `health` e `prometheus` ficam expostos; `ModoNuvemFilter` continua devolvendo 404 para qualquer outro actuator.

### M3. Métricas de sincronização calculadas por agendamento, não na raspagem
`MetricasSyncNuvem` (perfil `nuvem`): um `@Scheduled(fixedDelay = 30 s)` executa as consultas abaixo numa única
leitura e troca um retrato imutável; os medidores são `MultiGauge` registrados com `overwrite=true`, então empresa que
some do resultado (servidor revogado) deixa de ter série no ciclo seguinte. Raspar nunca toca o banco. Erro de
consulta: log `WARN` uma vez por ciclo e o retrato anterior é mantido.

Consultas (só servidores `Status <> 'revogado'`; rótulos `empresa` = `CompanyId`, `loja` = `SyncServidores.Nome`):

| Métrica | Origem |
|---|---|
| `quack_sync_ultimo_contato_segundos` | agora − `SyncServidores.UltimoContato` (sem contato: série ausente) |
| `quack_sync_ultima_sincronizacao_segundos` | agora − `SyncEstadoNuvem.UltimaSincronizacaoEm` |
| `quack_sync_comandos{status}` | `pendente`, `parado` = contagem atual; `conflito_24h`, `falhou_24h` = `Status` com `AtualizadoEm` nas últimas 24 h |
| `quack_sync_quarentena_aberta` | `SyncQuarentena` com `ResolvidoEm IS NULL` |
| `quack_sync_alertas{motivo}` | `SyncAlertas` com `UltimaEm` nas últimas 24 h (motivos de `AlertasSync`) |
| `quack_sync_servidor_ativo` | 1 se `Status='ativo'`, 0 se `pendente` |

Contadores/temporizadores registrados no próprio código (sem consulta):
- `quack_sync_lotes_total{empresa,resultado}` em `ReceptorLotes` (`aplicado`, `recusado`, `parte`);
- `quack_sync_detector_ciclo_seconds` (Timer) em `DetectorNuvem.detectarTodas`;
- `quack_sync_comandos_entregues_total{empresa}` no long-poll de `LojaSyncComandosController`.

Reaproveitar o SQL de `SituacaoNuvem` onde der, mas numa consulta agrupada por empresa (não N consultas).

### M4. Relato de estado: `POST /api/loja-sync/estado`, último valor por empresa
Loja (perfil `loja`): `RelatorEstadoLoja` com `@Scheduled(initialDelay = 30 s, fixedDelay = 60 s)` monta o relato a
partir de `SituacaoLoja.consultar(agora)` e envia pelo mesmo cliente HTTP da loja (`ClienteNuvemHttp`, cabeçalho
`Authorization: LojaServidor <token>`), com tempo limite de 10 s. Só envia com a sincronização ativada. Resposta:
- 2xx → nada; 404 → log `INFO` no máximo 1×/hora (receptor antigo); outro erro ou sem rede → log `DEBUG`, descarta.
Nunca usa a fila de envio, nunca grava `SyncAlertas`, roda fora das travas do envio.

Corpo (JSON, campos nulos permitidos):
`{ "geradoEm", "atrasoSegundos", "pausada", "ultimoBackupEm", "backupSucesso", "backupErro",
  "nfcePendentes", "versaoPrograma" }` — `versaoPrograma` = `Implementation-Version` do jar (via
`BuildProperties`, adicionar o goal `build-info` do `spring-boot-maven-plugin`). `backupErro` truncado em 500
caracteres.

Nuvem: rota em `LojaSyncNuvemController` (ou controller novo do pacote `situacao`), protegida pelo
`ServidorLojaFilter` (incluir `/api/loja-sync/estado` na lista que exige token; servidor `ativo`). Faz `MERGE` em
`SyncEstadoLoja` com `RecebidoEm = SYSDATETIMEOFFSET()`. Corpo acima de 4 KB → 413. O filtro já atualiza
`UltimoContato`.

Alternativa descartada: mandar o relato num cabeçalho do long-poll (como `X-Quack-Recursos`) — economiza uma chamada
por minuto mas mistura protocolo de comandos com observação, limita tamanho e complica teste. Um POST por minuto por
loja é desprezível.

### M5. Tabela `SyncEstadoLoja` (migração `db/nuvem/V3__estado_loja.sql`)
`CompanyId NVARCHAR(40) PK, RecebidoEm DATETIMEOFFSET(7) NOT NULL, GeradoEm DATETIMEOFFSET(7) NULL,
AtrasoSegundos BIGINT NULL, Pausada BIT NULL, UltimoBackupEm DATETIMEOFFSET(7) NULL, BackupSucesso BIT NULL,
BackupErro NVARCHAR(500) NULL, NfcePendentes INT NULL, VersaoPrograma NVARCHAR(40) NULL`.
Idempotente (`IF OBJECT_ID … IS NULL`), como V1/V2. Não entra no Change Tracking nem no catálogo de sincronização
(é tabela só da nuvem, como as outras `Sync*`). `MetricasSyncNuvem` lê dela e publica `quack_loja_relato_idade_segundos`,
`quack_loja_atraso_envio_segundos`, `quack_loja_backup_idade_segundos` (agora − `UltimoBackupEm`; nulo → ausente),
`quack_loja_backup_sucesso`, `quack_loja_nfce_pendentes`, `quack_loja_pausada`, e `quack_loja_info{versao}` = 1.

Semântica do backup: `backup-estado.json` traz o resultado da **última tentativa**. Conferir em
`instalador-loja/backup.ps1` se `ultimoBackupEm` guarda a última tentativa ou o último sucesso (no erro 1844 veio
nulo). O painel mostra as duas coisas: sucesso da última tentativa e idade do último backup conhecido.

### M6. Métricas locais da loja
`MetricasLoja` (perfil `loja`): mesmo padrão do M3 (agendado 30 s, retrato imutável), a partir de
`SituacaoLoja.consultar`, publicando `quack_loja_*` sem rótulo de empresa (a loja tem uma só). Compartilhar com o
`RelatorEstadoLoja` o mesmo retrato para não consultar duas vezes.

### M7. Prometheus e Grafana em imagens próprias, configuração no repositório
Pasta `monitoramento/`:
- `prometheus/prometheus.yml` (job `quack-sync`: `pdv-api-sync:8080`, `metrics_path: /actuator/prometheus`,
  `scrape_interval: 30s`, `authorization.credentials_file: /etc/prometheus/metrics-token`; job do próprio
  Prometheus) e `Dockerfile` (`FROM prom/prometheus:v3.x.y` com tag exata) com um `entrypoint.sh` que grava
  `$METRICS_TOKEN` no arquivo de credencial (modo 0400) e chama o Prometheus com `--storage.tsdb.retention.time=30d`.
- `grafana/provisioning/datasources/prometheus.yml` (URL `http://pdv-monitor-prometheus:9090`, `editable: false`),
  `grafana/provisioning/dashboards/painel.yml` (provider de arquivo) e `grafana/dashboards/*.json`, com `Dockerfile`
  (`FROM grafana/grafana-oss:<tag exata>`). `GF_AUTH_ANONYMOUS_ENABLED=false`, `GF_USERS_ALLOW_SIGN_UP=false`,
  `GF_SECURITY_ADMIN_PASSWORD` por variável da stack, `GF_SERVER_ROOT_URL` com o domínio.

Imagens publicadas pelo workflow novo `.github/workflows/monitoramento.yml` (push em `spring-api`/`main` com
`paths: monitoramento/**`), em matriz `prometheus`/`grafana`, tags `spring-api` (ou `latest` na main) e `${{ github.sha }}`,
cache `gha`, igual ao `api-java.yml`. Nomes: `ghcr.io/<ns>/quack-monitor-prometheus` e `quack-monitor-grafana`.

Por que imagem e não `configs:`/bind mount do Swarm: a stack é colada à mão no Portainer e o nó não tem o
repositório; bind mount aponta para o host. Imagem versionada é o que já funciona para a API.

Serviços na stack (bloco para `docker-compose.yml` e `DEPLOY.md`):
- `pdv-monitor-prometheus`: rede `OrionNet`, volume `horus-pdv-prometheus-data`, `METRICS_TOKEN`, **sem labels do
  Traefik**, limites de memória (512 MB).
- `pdv-monitor-grafana`: rede `OrionNet`, volume `horus-pdv-grafana-data`, Traefik em
  `monitor-pdv.quacksistemas.com.br` (websecure + letsencrypt, porta 3000), `GRAFANA_ADMIN_PASSWORD`.
- `pdv-api-sync`: ganha `QUACK_METRICS_TOKEN: "${METRICS_TOKEN:?…}"`.

### M8. Painéis
- **Sincronização das lojas**: tabela uma linha por loja com contato, última sincronização, atraso do envio, idade e
  sucesso do backup, comandos pendentes/parados, conflitos 24 h, quarentena, versão do programa; cores (verde/amarelo/
  vermelho) com limiares fixos: contato > 5 min amarelo / > 15 min vermelho; backup > 26 h vermelho ou sucesso 0;
  quarentena > 0 amarelo. Séries no tempo: atraso e comandos por loja.
- **API Java**: requisições/s por rota, P95/P99 por rota **excluindo** `/api/loja-sync/comandos` (long-poll de até
  25 s distorce), taxa de 4xx/5xx, Hikari (ativas, pendentes, tempo de espera), threads (incluindo virtual), heap e
  pausas de GC, duração do ciclo do detector, lotes recebidos.
Os JSON são exportados do Grafana com `uid` fixo e fonte de dados por `uid` do provisionamento.

## Risks / Trade-offs

- [Long-poll distorce latência] → painel exclui a rota de comandos; a métrica crua continua existindo.
- [Consulta de métricas pesando no banco da .NET] → uma leitura agrupada a cada 30 s, em `READ COMMITTED` simples sem
  travar, índices já existentes (`IX_SyncComandos_Fila`, `IX_SyncQuarentena_Abertas`); medir o tempo do ciclo
  (`quack_sync_metricas_ciclo_seconds`) e comparar com o detector.
- [Token de métricas vaza] → só dá leitura de métricas (sem dados de negócio além de contagens e nomes de lojas);
  trocar = mudar `METRICS_TOKEN` e reimplantar os dois serviços.
- [Nome da loja como rótulo muda] → série nova; aceitável (raro). `empresa` é o rótulo estável.
- [Grafana exposto na internet] → login obrigatório, sem cadastro, senha forte por variável; Prometheus nunca exposto.
- [Disco do nó] → 30 dias de retenção com poucas lojas fica em dezenas de MB; limite de memória nos dois serviços.
- [Relato duplicando carga do `SituacaoLoja`] → o retrato de M6 é compartilhado; `SituacaoLoja` são consultas leves.

## Migration Plan

1. Publicar o jar novo (push em `spring-api` gera a imagem). Receptor novo sem `QUACK_METRICS_TOKEN` se comporta como
   hoje (rota 404) e já aceita o relato.
2. Atualizar `pdv-api-sync` no Portainer com `QUACK_METRICS_TOKEN` (gerar com `openssl rand -hex 32`).
3. Criar o DNS `monitor-pdv.quacksistemas.com.br` → IP do nó; adicionar os dois serviços e volumes na stack (bloco do
   `DEPLOY.md`), com `METRICS_TOKEN` e `GRAFANA_ADMIN_PASSWORD`; `docker login ghcr.io` no nó se as imagens forem
   privadas (mesmo procedimento da API).
4. Servidor da loja: na próxima atualização (`instalar.ps1 -EmailAdmin … -PularCarga`) o relato começa sozinho;
   `QUACK_METRICS_TOKEN` na loja é opcional.
5. Conferir: alvo `UP` em Prometheus (via `docker exec` no container, sem rota pública), painéis carregados, linha da
   loja com backup e atraso.

Rollback: remover os dois serviços da stack (dados ficam nos volumes) e/ou voltar a tag do `pdv-api-sync`; a tabela
`SyncEstadoLoja` fica sem uso e não interfere em nada.

## Open Questions

- Subdomínio do painel: assumido `monitor-pdv.quacksistemas.com.br`; trocar só muda uma label e `GF_SERVER_ROOT_URL`.
- Versões exatas de `prom/prometheus` (v3) e `grafana/grafana-oss`: fixar a última estável na implementação.

## Notas da implementação

### Resposta da tarefa 3.3 (significado de `ultimoBackupEm`)
`ultimoBackupEm` guarda o **último backup bem-sucedido**, não a última tentativa. Em `JAVA/api/instalador-loja/backup.ps1`
o sucesso chama `Write-EstadoBackup ... -Sucesso $true -Quando ([datetimeoffset]::Now)` (linha 70) e a falha chama
`Write-EstadoBackup ... -Sucesso $false -Erro $msg` (linha 76). Em `lib/comum.ps1` (função `Write-EstadoBackup`, linhas
79-94) o sucesso grava a hora atual em `ultimoBackupEm`; a falha **preserva** o `ultimoBackupEm` do arquivo anterior
(`Read-EstadoBackup`, linhas 88-91) e grava `sucesso=false` e `erro`. Se nunca houve sucesso (caso do erro 1844), vem
`null`. Por isso o painel mostra as duas coisas: `quack_loja_backup_sucesso` (última tentativa) e
`quack_loja_backup_idade_segundos` (idade do último sucesso; ausente se nunca houve).

### Desvios do design
- **`quack_loja_info{versao}` virou `quack_loja_versao_programa{versao}`.** O cliente Prometheus remove o sufixo `_info`
  do nome (a série saía como `quack_loja`). Mesmo conteúdo (valor 1, rótulo `versao`), nome novo.
- **Métricas de idade calculadas na raspagem.** `quack_sync_ultimo_contato_segundos`, `..._ultima_sincronizacao_...`,
  `quack_loja_relato_idade_segundos` e `quack_loja_backup_idade_segundos` guardam o instante lido do banco e calculam
  "agora − instante" a cada raspagem (o design dizia valor fixo por ciclo). A raspagem continua sem tocar no banco.
- **`quack_sync_alertas{motivo}`** vale 1 por (empresa, motivo) com `UltimaEm` nas últimas 24 h; alertas globais
  (`CompanyId` vazio) não entram.
- **Retrato da loja (M6):** `RetratoLoja` (cache de 25 s sobre `SituacaoLoja.consultar`) é compartilhado por `MetricasLoja`
  (a cada 30 s) e `RelatorEstadoLoja` (a cada 60 s). `ClienteJsonNuvem` ganhou `RespostaHttpException` (com o status) para
  distinguir o 404 do receptor antigo.
- **Intervalos configuráveis** só para teste: `quack.metrics.intervalo-ms` (30000) e `quack.metrics.relato-intervalo-ms` (60000).
- **Compose:** os serviços de monitoramento usam o mesmo `profiles: ["nuvem"]` do `pdv-api-sync`, e `METRICS_TOKEN` passou
  a ser obrigatório (`:?`) no compose do repositório; a stack de produção (colada à mão) não é afetada até alguém colar o bloco.
- **Versões:** `prom/prometheus:v3.5.1` (LTS) e `grafana/grafana-oss:12.1.1`, confirmadas com `docker manifest inspect`.
- **Testes:** o cenário "token não configurado = 404" e os 401 estão em `MetricasTokenFilterTest` (unitário); o IT
  `MetricasNuvemIT` cobre token correto, 401, health aberto, `/actuator/metrics|env` = 404 e as métricas. O cenário
  "erro de consulta mantém o retrato" usa um `JdbcTemplate` que falha sob demanda dentro do `MetricasNuvemIT`. O 404 do
  receptor antigo (um único log por hora) é testado em `RelatorEstadoLojaTest` com relógio manual.

### Como cada verificação foi feita (e o que ficou pendente)
- **1.x a 3.x e 4.1:** `MetricasTokenFilterTest`, `RelatorEstadoLojaTest` (unitários), `MetricasNuvemIT` (18 cenários) e
  `RelatoEstadoIT` (10 cenários, inclui a V3 reexecutada duas vezes) passam com Testcontainers.
- **4.2:** `instalar.ps1 -SomenteValidar` não é verificação útil nesta máquina (falha por espaço livre em `C:\`, nada a ver com
  a mudança; sintaxe e pacote `[ OK ]`). Valeu `testes/Testar-Logica.ps1`: os 3 testes novos do token passam; a única falha
  (`ASCII puro Testar-BackupDocker.ps1`) já existia antes. Os testes `Testar-ApiPacote.ps1`/`Testar-BackupDocker.ps1` exigem
  Docker e não foram repetidos.
- **5.1/5.2:** feitos à mão com `docker build`/`docker run`: `promtool check config` ok, credencial `-r--------` (0400), reinício
  do mesmo container regrava o arquivo, sem `METRICS_TOKEN` o container falha com mensagem clara; Grafana sobe com volume
  vazio, `/api/health` 200, `/` sem login 302, `/api/search` anônimo 401, autenticado lista os 2 painéis e a fonte de dados
  `prometheus-quack` (somente leitura).
- **5.3 (pendente):** o script `monitoramento/testes/Testar-MonitoramentoDocker.ps1` está escrito e uma execução parcial
  confirmou: receptor sobe no perfil `nuvem`, metrica 401/200, credencial 0400, os dois painéis provisionados e **todas as
  consultas dos painéis "API Java" devolvem dados** pelo Grafana. Naquela execução o relato da loja de teste recebeu 401
  (o `sqlcmd` rodava sem `-I`, e o índice único filtrado de `SyncServidores` exige `QUOTED_IDENTIFIER`; corrigido no script) e
  por isso os itens dependentes do relato falharam. A rodada seguinte não pôde ser feita porque o engine do Docker Desktop
  travou (nenhum comando `docker` responde). A tarefa continua desmarcada até uma execução completa do script.
- **5.4 (pendente):** `actionlint` (imagem `rhysd/actionlint`) não apontou erro; falta confirmar as imagens em ghcr.io após o push.
- **5.5:** `docker compose --profile nuvem config` valida (com `METRICS_TOKEN` e `GRAFANA_ADMIN_PASSWORD` definidos); sem
  o perfil, só os 4 serviços antigos sobem.
- **6.1 (pendente):** a suíte completa de sincronização não foi repetida depois das mudanças (o Docker travou); só os novos ITs
  e os unitários rodaram.
- **6.2:** `target/quack-servidor-loja.zip` gerado (114 MB) com o jar novo (classes de monitoramento, `V3`, `build-info`),
  `CHECKLIST.md` com a linha 1.6 e `instalar.ps1` com o token opcional. "Linha em `SyncEstadoLoja` em até 2 min" foi coberto
  por `RelatoEstadoIT` (a loja de teste relata e a nuvem guarda backup, atraso e versão); o disparo pelo agendador (30 s
  de atraso inicial, depois 60 s) não foi cronometrado.
