# Proposal

## Why

Hoje a única forma de saber se a sincronização de uma loja está saudável é abrir `/api/loja-sync/situacao`, ler
`backup-estado.json` no servidor da loja ou esperar um e-mail de alerta. Foi assim que o backup diário da loja de
material de construção falhou desde a instalação sem ninguém ver (erro 1844, corrigido em 10c4f8b7). Com as virtual
threads ligadas no receptor (993de431) também não temos como confirmar que o pool de conexões não virou gargalo.
Precisamos de métricas contínuas, com histórico, num painel que o dono e o suporte consultem antes de o cliente ligar.

## What Changes

- A API Java passa a expor métricas no formato Prometheus em `/actuator/prometheus`, nos perfis `nuvem` e `loja`,
  **protegidas por token** (sem token configurado, a rota não existe). `/actuator/health` continua aberto como hoje.
- Tempo das requisições HTTP com histograma, para P95/P99 por rota; métricas de JVM, threads, GC e pool de conexões
  (Hikari) que o Spring já mede.
- Métricas próprias da sincronização no receptor da nuvem, por empresa: tempo desde o último contato do servidor da
  loja, tempo desde a última sincronização aplicada, comandos por situação (pendente, parado, conflito/falhou nas
  últimas 24 h), quarentena aberta, alertas abertos por motivo, lotes recebidos e duração do ciclo do detector.
- **Relato de estado da loja**: o servidor da loja envia à nuvem, a cada minuto, um resumo do que só ele sabe
  (atraso do envio, último backup e se deu certo, NFC-e pendentes, sincronização pausada, versão do programa). A nuvem
  guarda o último relato e o expõe como métricas. Loja com jar antigo simplesmente não relata (métrica ausente).
- Prometheus e Grafana como serviços da stack `pdv` no Portainer, com configuração e painéis versionados no
  repositório e embutidos em imagens próprias publicadas pelo GitHub Actions. Prometheus sem rota pública; Grafana
  atrás do Traefik com login (sem acesso anônimo).
- Painéis: "Sincronização das lojas" (uma linha por loja: contato, atraso, backup, comandos, quarentena) e
  "API Java" (requisições, P95/P99, erros, JVM, threads, Hikari).
- Nada muda na API .NET (`API/NETCORE/`) nem no Gateway (`GATEWAY/`).

## Capabilities

### New Capabilities
- `plataforma/monitoramento`: exposição protegida de métricas da API Java, métricas da sincronização por loja,
  relato periódico de estado da loja à nuvem e os painéis de observação.

### Modified Capabilities
<!-- Nenhuma: as specs do modo loja ainda estão na change api-java-modo-loja (não arquivada); o relato de estado é
     uma rota nova e não muda requisito existente. -->

## Impact

- Código Java (`JAVA/api`): dependência `micrometer-registry-prometheus`; configuração `management.*` em
  `application.yml`; filtro do token de métricas; `ModoNuvemFilter`, `AutenticacaoFilter` e `RecuperacaoFilter`
  liberam `/actuator/prometheus` (o token é conferido no filtro novo); coletor das métricas de sincronização (perfil
  `nuvem`) e da loja (perfil `loja`); rota `POST /api/loja-sync/estado` (nuvem) e o envio periódico (loja).
- Banco da nuvem: migração `db/nuvem/V3__estado_loja.sql` (tabela `SyncEstadoLoja`). Banco da loja: nenhuma mudança.
- Infra: pasta nova `monitoramento/` (Prometheus, Grafana, painéis), workflow que publica as duas imagens em ghcr.io,
  serviços novos no `docker-compose.yml` e passo a passo em `DEPLOY.md`. Precisa de um subdomínio novo para o Grafana
  e de variáveis novas na stack (`METRICS_TOKEN`, senha do admin do Grafana).
- Instalador da loja: `loja.env` ganha `QUACK_METRICS_TOKEN` opcional (vazio = rota desligada na loja).
- Ordem de implantação indiferente: receptor novo aceita loja antiga (sem relato) e loja nova tolera receptor antigo
  (relato recebe 404 e é ignorado sem alerta).
