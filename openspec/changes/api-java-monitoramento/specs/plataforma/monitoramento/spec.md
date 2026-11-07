# Spec Delta

## Purpose

Permite observar continuamente a API Java (receptor da nuvem e servidor da loja) e a saúde da sincronização de cada
loja, com histórico e painéis, sem depender de alguém abrir a situação ou ler arquivos no servidor da loja.

## ADDED Requirements

### Requirement: Métricas protegidas por token
A API Java SHALL expor métricas no formato de texto do Prometheus em `GET /actuator/prometheus`, nos perfis `nuvem` e
`loja`, somente quando um token de métricas estiver configurado, e SHALL exigir `Authorization: Bearer <token>`
comparado em tempo constante. `/actuator/health` MUST continuar respondendo sem autenticação.

#### Scenario: Token correto
- **WHEN** o token de métricas está configurado e a requisição envia `Authorization: Bearer <token>` igual
- **THEN** a resposta é 200 com `Content-Type` do formato de texto do Prometheus e contém a métrica `jvm_memory_used_bytes`

#### Scenario: Sem token ou token errado
- **WHEN** a requisição não envia o cabeçalho ou envia outro token
- **THEN** a resposta é 401 e o corpo não contém nenhuma métrica

#### Scenario: Token não configurado
- **WHEN** o token de métricas está vazio na configuração
- **THEN** `GET /actuator/prometheus` responde 404 como qualquer rota inexistente, com ou sem cabeçalho

#### Scenario: Health continua aberto
- **WHEN** `GET /actuator/health` é chamado sem autenticação no perfil `nuvem`
- **THEN** a resposta é 200 com `"status":"UP"`, como antes desta mudança

#### Scenario: Receptor não abre outras rotas
- **WHEN** no perfil `nuvem` se chama `GET /actuator/metrics` ou `GET /actuator/env`
- **THEN** a resposta é 404

### Requirement: Tempo das requisições com percentis
As métricas SHALL incluir o tempo das requisições HTTP com histograma, separado por método, rota (modelo, não a URL
concreta) e status, de forma que P95 e P99 possam ser calculados por rota. Todas as métricas SHALL levar o rótulo
`application` com o perfil (`nuvem` ou `loja`).

#### Scenario: Histograma por rota
- **WHEN** a API atendeu `GET /api/loja-sync/comandos?apos=10` e `?apos=20`
- **THEN** existe uma única série `http_server_requests_seconds_bucket` com `uri="/api/loja-sync/comandos"` (sem os parâmetros) e rótulo `application="nuvem"`

### Requirement: Métricas da sincronização por loja na nuvem
No perfil `nuvem`, as métricas SHALL incluir, para cada empresa com servidor de loja não revogado (rótulos `empresa`
e `loja`): segundos desde o último contato, segundos desde a última sincronização aplicada, comandos por situação,
quarentena aberta e alertas abertos por motivo. Os valores MUST ter no máximo 60 s de defasagem.

#### Scenario: Loja sem contato
- **WHEN** o servidor da loja de uma empresa não chama a nuvem há 10 minutos
- **THEN** `quack_sync_ultimo_contato_segundos{empresa="<id>"}` é pelo menos 600

#### Scenario: Comandos e quarentena
- **WHEN** a empresa tem 2 comandos pendentes, 1 parado e 3 entradas abertas na quarentena
- **THEN** `quack_sync_comandos{status="pendente"}` vale 2, `quack_sync_comandos{status="parado"}` vale 1 e `quack_sync_quarentena_aberta` vale 3 para essa empresa

#### Scenario: Conflitos recentes
- **WHEN** um comando da empresa terminou em `conflito` há 2 horas e outro há 30 horas
- **THEN** `quack_sync_comandos{status="conflito_24h"}` vale 1

#### Scenario: Servidor revogado
- **WHEN** o servidor da loja de uma empresa é revogado
- **THEN** em até 60 s as séries dessa empresa deixam de ser publicadas

### Requirement: Relato periódico de estado da loja
O servidor da loja SHALL enviar à nuvem, a cada 60 s e com o token do servidor, o relato com atraso do envio, último
backup (data e sucesso), NFC-e pendentes há mais de 10 min, se a sincronização está pausada e a versão do programa. A
nuvem SHALL guardar só o último relato por empresa. Falha no envio MUST NOT afetar a sincronização nem gerar alerta.

#### Scenario: Relato aceito
- **WHEN** o servidor da loja envia o relato com o token válido
- **THEN** a nuvem responde 200 e guarda o relato com a hora em que o recebeu, substituindo o anterior da mesma empresa

#### Scenario: Token inválido
- **WHEN** o relato chega sem token ou com token revogado
- **THEN** a nuvem responde 401 e nada é gravado

#### Scenario: Receptor antigo
- **WHEN** a nuvem ainda não tem a rota de relato e responde 404
- **THEN** a loja registra uma linha de log no máximo uma vez por hora, segue sincronizando normalmente e não cria alerta

#### Scenario: Sem internet
- **WHEN** a nuvem está inacessível
- **THEN** o relato daquele minuto é descartado (não acumula fila) e o próximo é tentado no minuto seguinte

### Requirement: Métricas do relato da loja na nuvem
No perfil `nuvem`, as métricas SHALL publicar o último relato de cada loja: idade do relato, atraso do envio, idade
do último backup, sucesso do último backup (1/0), NFC-e pendentes e sincronização pausada (1/0). Sem relato
recebido, essas séries MUST ficar ausentes.

#### Scenario: Backup atrasado visível
- **WHEN** o último relato da loja informa o último backup bem-sucedido há 30 horas
- **THEN** `quack_loja_backup_idade_segundos{empresa="<id>"}` é pelo menos 108000 e `quack_loja_backup_sucesso` vale 1

#### Scenario: Backup falhou
- **WHEN** o relato informa `sucesso=false` no último backup
- **THEN** `quack_loja_backup_sucesso` vale 0 para essa empresa

#### Scenario: Loja com programa antigo
- **WHEN** a empresa nunca enviou relato
- **THEN** não há série `quack_loja_*` para ela, mas as séries `quack_sync_*` continuam publicadas

### Requirement: Métricas locais da loja
No perfil `loja`, as métricas SHALL incluir os mesmos valores do relato (atraso do envio, idade e sucesso do último
backup, NFC-e pendentes, pausada), calculados localmente, para leitura na rede da loja mesmo sem internet.

#### Scenario: Sem internet
- **WHEN** a loja está sem internet há 15 minutos e tem mudanças locais não enviadas
- **THEN** `GET /actuator/prometheus` na loja (com o token) mostra `quack_loja_atraso_envio_segundos` de pelo menos 900

### Requirement: Painéis e coleta na nuvem
A stack da nuvem SHALL ter um coletor de métricas que lê o receptor pela rede interna a cada 30 s, com 30 dias de
histórico, sem rota pública, e um painel com login obrigatório (sem acesso anônimo) que carrega por arquivo do
repositório os painéis "Sincronização das lojas" e "API Java".

#### Scenario: Painéis carregados na subida
- **WHEN** o serviço de painéis sobe com um volume vazio
- **THEN** os painéis "Sincronização das lojas" e "API Java" e a fonte de dados do coletor já existem, sem configuração manual

#### Scenario: Acesso sem login
- **WHEN** alguém abre o endereço do painel sem estar autenticado
- **THEN** é redirecionado para a tela de login e não vê nenhum painel

#### Scenario: Coletor não exposto
- **WHEN** se tenta acessar o coletor pelo domínio público da stack
- **THEN** não existe rota do Traefik para ele
