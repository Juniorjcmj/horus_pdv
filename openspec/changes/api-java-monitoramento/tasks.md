# Tasks

Contexto obrigatório: `proposal.md`, `design.md` (M1–M8) e `specs/plataforma/monitoramento/spec.md`. Código em
`JAVA/api/src/main/java/br/com/quack/pdv/` (`plataforma/sincronizacao/**`, `security/`, `config/`). **Nada em
`API/NETCORE/` nem `GATEWAY/` pode mudar.** Testes de integração da sincronização usam `AmbienteSync` (como
`ComandosIT`, `SituacaoAlertasIT`).

## 1. Base de métricas protegida

- [x] 1.1 Adicionar `micrometer-registry-prometheus` (versão do BOM do Spring Boot) e o goal `build-info` do `spring-boot-maven-plugin` no `pom.xml`. Verificação: `mvn -q -DskipTests package` passa e o jar contém `META-INF/build-info.properties`.
- [x] 1.2 Configurar `management.*` em `application.yml` conforme M1 (exposição `health,prometheus`, rótulo `application`, histograma e SLOs de `http.server.requests`) e `quack.metrics.token` em `QuackProperties` (`QUACK_METRICS_TOKEN`, padrão vazio). Verificação: teste de contexto lê o valor do token da propriedade.
- [x] 1.3 Criar `MetricasTokenFilter` (M2) e liberar `/actuator/prometheus` em `ModoNuvemFilter`, `AutenticacaoFilter` e `RecuperacaoFilter`. Verificação: teste MockMvc/IT cobre os cinco cenários do requisito "Métricas protegidas por token" (200 com `jvm_memory_used_bytes`, 401 sem/errado, 404 sem token configurado, health aberto, `/actuator/metrics` e `/actuator/env` = 404 no perfil `nuvem`).
- [x] 1.4 Teste do requisito "Tempo das requisições com percentis": duas chamadas a `/api/loja-sync/comandos` com `apos` diferentes geram uma única série `http_server_requests_seconds_bucket` com `uri="/api/loja-sync/comandos"` e `application="nuvem"`. Verificação: IT novo `MetricasNuvemIT` passa.

## 2. Métricas da sincronização na nuvem

- [x] 2.1 `MetricasSyncNuvem` (perfil `nuvem`, M3): agendamento de 30 s, retrato imutável, `MultiGauge` com `overwrite=true`, consultas agrupadas por empresa para `quack_sync_ultimo_contato_segundos`, `quack_sync_ultima_sincronizacao_segundos`, `quack_sync_comandos{status}`, `quack_sync_quarentena_aberta`, `quack_sync_alertas{motivo}`, `quack_sync_servidor_ativo` e `quack_sync_metricas_ciclo_seconds`; método público `atualizar()` para os testes. Verificação: `MetricasNuvemIT` cobre "Loja sem contato", "Comandos e quarentena", "Conflitos recentes" (um conflito com `AtualizadoEm` −2 h e outro −30 h → `conflito_24h` = 1) e "Servidor revogado".
- [x] 2.2 Contadores e temporizadores no código: `quack_sync_lotes_total{empresa,resultado}` em `ReceptorLotes`, `quack_sync_detector_ciclo_seconds` em `DetectorNuvem.detectarTodas`, `quack_sync_comandos_entregues_total{empresa}` em `LojaSyncComandosController`. Verificação: `MetricasNuvemIT` envia um lote e confirma o contador com `resultado="aplicado"`; suíte `ComandosIT` e `SincronizacaoIT` continua passando.
- [x] 2.3 Erro de consulta não derruba nada: com a consulta falhando (tabela renomeada no teste ou `JdbcTemplate` que lança), o retrato anterior é mantido e `/actuator/prometheus` responde 200. Verificação: teste unitário de `MetricasSyncNuvem`.

## 3. Relato de estado da loja

- [x] 3.1 Migração `db/nuvem/V3__estado_loja.sql` com `SyncEstadoLoja` (M5), idempotente. Verificação: subida do perfil `nuvem` em `CatalogoSchemaIT`/`AmbienteSync` aplica sem erro e uma segunda execução não falha.
- [x] 3.2 Nuvem: rota `POST /api/loja-sync/estado` (M4) exigindo token de servidor `ativo` no `ServidorLojaFilter`, `MERGE` em `SyncEstadoLoja`, limite de 4 KB (413), `backupErro` truncado em 500. Verificação: `RelatoEstadoIT` cobre "Relato aceito" (substitui o anterior), "Token inválido" (401, nada gravado) e corpo grande (413).
- [x] 3.3 Conferir em `JAVA/api/instalador-loja/backup.ps1` o que `ultimoBackupEm` significa em sucesso e em falha e registrar a resposta em "Notas da implementação" no fim do `design.md`. Verificação: a nota cita as linhas do script.
- [x] 3.4 Loja: `RelatorEstadoLoja` (perfil `loja`, M4) a cada 60 s a partir do retrato de `SituacaoLoja`, via `ClienteNuvemHttp`, tempo limite 10 s, só com a sincronização ativada; 404 → log `INFO` no máximo 1×/hora; sem rede → descarta; nunca grava `SyncAlertas`. Verificação: `RelatoEstadoIT` — loja de teste relata e a nuvem guarda `ultimoBackupEm`, `backupSucesso`, `atrasoSegundos` e `versaoPrograma`; com o receptor parado, o envio de lotes continua e não há linha nova em `SyncAlertas`; com rota inexistente (404 simulado), só um log no intervalo.
- [x] 3.5 Nuvem publica as métricas do relato (`quack_loja_relato_idade_segundos`, `quack_loja_atraso_envio_segundos`, `quack_loja_backup_idade_segundos`, `quack_loja_backup_sucesso`, `quack_loja_nfce_pendentes`, `quack_loja_pausada`, `quack_loja_info{versao}`) em `MetricasSyncNuvem`. Verificação: `MetricasNuvemIT` cobre "Backup atrasado visível" (30 h → ≥ 108000 e sucesso 1), "Backup falhou" (sucesso 0) e "Loja com programa antigo" (sem relato: nenhuma `quack_loja_*`, `quack_sync_*` presentes).

## 4. Métricas locais da loja

- [x] 4.1 `MetricasLoja` (perfil `loja`, M6) com o mesmo retrato usado pelo relato. Verificação: IT com a loja sem acesso à nuvem há mais que o tempo do cenário (relógio do `AmbienteSync` ou `UltimaSincronizacaoEm` ajustada) mostra `quack_loja_atraso_envio_segundos` ≥ 900 em `/actuator/prometheus` da loja com o token.
- [x] 4.2 Instalador da loja: `QUACK_METRICS_TOKEN` opcional no `loja.env` (comentado, vazio por padrão) e linha no `CHECKLIST.md` explicando que a rota fica desligada sem ele. Verificação: `instalar.ps1 -SomenteValidar` continua passando com e sem a variável; `Testar-InstaladorDocker.ps1` (ou o teste do instalador existente) passa.

## 5. Prometheus, Grafana e painéis

- [x] 5.1 `monitoramento/prometheus/` (`prometheus.yml`, `entrypoint.sh`, `Dockerfile` com tag exata de `prom/prometheus` v3) conforme M7. Verificação: `docker build` local e `promtool check config` dentro da imagem passam; o container sobe com `METRICS_TOKEN` e o arquivo de credencial tem modo 0400.
- [x] 5.2 `monitoramento/grafana/` (provisionamento da fonte de dados e dos painéis, `Dockerfile` com tag exata de `grafana/grafana-oss`, sem acesso anônimo) conforme M7. Verificação: container sobe com volume vazio, `GET /api/health` = 200, `GET /` sem login redireciona para `/login`, e `GET /api/search` autenticado lista os dois painéis.
- [x] 5.3 Painéis "Sincronização das lojas" e "API Java" (M8) em `monitoramento/grafana/dashboards/*.json` com `uid` fixo, P95/P99 excluindo `/api/loja-sync/comandos`. Verificação: script `monitoramento/testes/Testar-MonitoramentoDocker.ps1` sobe SQL Server + receptor (`nuvem`, token) + Prometheus + Grafana numa rede Docker, faz uma loja de teste relatar, e confere via API do Prometheus que `up{job="quack-sync"}` = 1 e `quack_loja_backup_sucesso` existe, e via API do Grafana que as consultas dos painéis retornam dados.
- [ ] 5.4 Workflow `.github/workflows/monitoramento.yml` (matriz `prometheus`/`grafana`, `paths: monitoramento/**`, tags de branch e `github.sha`, cache `gha`), no padrão do `api-java.yml`. Verificação: `actionlint` (se disponível) ou revisão do YAML; após o push, as duas imagens aparecem em ghcr.io.
- [x] 5.5 Serviços `pdv-monitor-prometheus` e `pdv-monitor-grafana`, volumes e `QUACK_METRICS_TOKEN` no `pdv-api-sync` em `docker-compose.yml`; seção "Monitoramento" em `DEPLOY.md` com o bloco para colar na stack, variáveis (`METRICS_TOKEN`, `GRAFANA_ADMIN_PASSWORD`), DNS, `docker login ghcr.io` e como conferir o alvo sem rota pública. Verificação: `docker compose --profile nuvem config` valida o arquivo; o `DEPLOY.md` cita a ordem do Migration Plan.

## 6. Integração

- [x] 6.1 Rodar a suíte de sincronização (`*IT` de `plataforma/sincronizacao/integracao`) mais os testes novos, e os testes de catálogo. Verificação: saída do Maven sem falhas.
- [x] 6.2 Gerar o pacote da loja (`bash JAVA/api/instalador-loja/build-instalador.sh`) e o kit de testes. Verificação: `quack-servidor-loja.zip` novo e, numa loja de teste, uma linha em `SyncEstadoLoja` na nuvem de teste em até 2 minutos.
- [x] 6.3 Atualizar `docs/migracao-api-springboot.md` (§ 3.3, monitoramento do modo loja) com o relato e os painéis. Verificação: a seção existe e aponta para o `DEPLOY.md`.

## Workflow follow-up

- Implantar conforme o Migration Plan do design (receptor com token → DNS e serviços na stack → loja na próxima atualização).
- Conferir na loja de material de construção: linha da loja no painel com backup das 02:00 e atraso perto de zero.
- Arquivar esta change depois de `api-java-modo-loja` e `api-java-operacoes-estoque-nuvem`.
