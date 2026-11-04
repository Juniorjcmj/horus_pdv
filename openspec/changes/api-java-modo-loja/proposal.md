# Proposal

## Why

A loja de material de construção (7 máquinas) precisa operar unida sem internet. Hoje cada caixa fala direto com a
nuvem e, quando a internet cai, fica isolado: não vê o estoque nem as vendas dos outros. A decisão de 03/11/2026
(`docs/migracao-api-springboot.md`, § 3.3) é ter um **servidor dedicado na loja**, rodando a API Java com SQL Server
Express e concentrando todas as transações, sincronizado com a nuvem.

A Fase 4 deixou a API Java pronta para o Caixa e o Quack Estoque e já com a numeração num ponto só. Falta a Fase 4b:
o servidor da loja, a sincronização com a nuvem e a instalação. A prioridade da loja é 4 → 4b → 7 → 6.

## What Changes

- **Dois perfis novos da API Java** (`quack.modo`):
  - `loja`: o servidor da loja. É a autoridade dos dados da empresa e envia as mudanças à nuvem.
  - `nuvem`: o **receptor**, um container novo ao lado da API .NET, no mesmo SQL Server da nuvem (decisão de
    04/11/2026). Ele só expõe as rotas de sincronização e não atende Caixa nem painel.
  - Sem perfil, a API continua como hoje.
- **Sincronização loja → nuvem por Change Tracking** (D9 de `api-java-vendas`):
  - lotes consistentes (leitura em snapshot até uma versão), na ordem das chaves estrangeiras;
  - a nuvem aplica de forma idempotente;
  - nova tentativa com backoff para erro temporário; pausa e alerta para erro definitivo;
  - ressincronização completa quando a loja passa da retenção.
- **Comandos nuvem → loja:**
  - o receptor detecta, pelo Change Tracking do banco da nuvem, o que o painel web e o MCP (API .NET) mudaram na
    empresa da loja;
  - **cadastros, preços e configurações** viram comandos que a loja aplica coluna a coluna, com conferência do valor
    de base. Se a loja mudou a mesma coluna nesse meio-tempo, vale a loja e o comando volta como conflito. Decisão de
    04/11/2026: produtos, clientes, fornecedores, categorias, promoções e usuários;
  - mudança feita na nuvem em tabela que só a loja grava (vendas, caixa, estoque, fiado) é desfeita com o estado da
    loja e gera alerta. A versão da nuvem é guardada em quarentena antes.
- **NFC-e durante a 4b:**
  - a emissão continua com a API .NET da nuvem, como hoje;
  - a venda sobe junto com a nota pendente e o número que a loja reservou;
  - o resultado da emissão (autorizada, rejeitada, erro) desce para a loja;
  - a Fase 7 passa a emissão para o servidor da loja.
- **Registro e carga inicial:**
  - o servidor da loja se registra na nuvem com o login de um administrador e recebe um token próprio;
  - baixa os dados da empresa (cópia em snapshot mais as mudanças até a ativação) e ativa o modo loja;
  - os segredos (certificado, CSC, SMTP) chegam cifrados com a chave da própria loja.
- **Reconciliação, quarentena e monitoramento** (lacunas apontadas pelo livro de referência):
  - conferência diária, por tabela, entre loja e nuvem, com ressincronização do que divergir;
  - limite de tentativas nos comandos;
  - situação da sincronização (atraso, comandos parados, quarentena, notas paradas) numa rota de status;
  - alertas por log e e-mail.
- **Instalador do servidor da loja** (Windows):
  - API Java como serviço do Windows, com SQL Server Express;
  - backup automático diário com retenção;
  - regra de firewall só para a rede local;
  - checklist (máquina dedicada, nobreak, IP fixo).
  - Os caixas e o Quack Estoque passam a apontar para o servidor pelo `apiBaseUrl`, sem mudança de código.
- **Fora desta fase:**
  - emissão de NFC-e no servidor da loja e rotas `api/Nfce/*` (Fase 7);
  - painel web servido pela loja (o dono usa o painel da nuvem);
  - HTTPS na rede local (a 4b usa HTTP restrito à rede da loja; registrado como risco);
  - troca da autoridade de volta para a nuvem (sair do modo loja).
- **Nada muda em `API/NETCORE` nem no `GATEWAY`.** No banco da nuvem entram só o Change Tracking, o isolamento
  snapshot e tabelas novas, todas da Java.

## Capabilities

### New Capabilities
- `plataforma/modo-loja`: perfis loja e nuvem, uma autoridade por empresa, registro do servidor, carga inicial,
  ativação e segredos da loja.
- `plataforma/sincronizacao-loja-nuvem`: envio das mudanças da loja para a nuvem por Change Tracking, aplicação
  idempotente, ordem, retenção, ressincronização e tratamento de erros.
- `plataforma/comandos-nuvem-loja`: detecção das edições feitas na nuvem, comandos com conferência de base, conflitos,
  resultado fiscal vindo da nuvem, desfazer edição indevida e quarentena.
- `plataforma/reconciliacao-sincronizacao`: conferência periódica entre loja e nuvem, situação da sincronização e
  alertas.
- `plataforma/instalacao-servidor-loja`: instalador do servidor da loja (serviço, SQL Server Express, backup, firewall,
  checklist) e ligação dos caixas.

### Modified Capabilities
- `fiscal/fila-nfce`: a fila fiscal mínima passa a depender do perfil. No `loja` só retoma vendas sem nota; no `nuvem`
  fica desligada, porque a emissão e a fila ficam com a API .NET da nuvem até a Fase 7.

## Impact

- **Código novo em `JAVA/api`:**
  - pacote `plataforma/sincronizacao` (catálogo das tabelas, envio, aplicação, comandos, carga inicial, reconciliação,
    status e alertas);
  - perfis `application-loja.yml` e `application-nuvem.yml`;
  - migrações Flyway das tabelas de sincronização (loja: `V5+`; nuvem: local e histórico próprios, sem tocar nas
    tabelas da .NET).
- **Instalador:** `JAVA/api/instalador-loja/` (scripts PowerShell, WinSW, checklist) e o script que gera o zip.
- **Nuvem:**
  - serviço novo `pdv-api-sync` no `docker-compose.yml` da raiz (imagem da API Java, perfil `nuvem`);
  - roteamento no Traefik;
  - Change Tracking e `ALLOW_SNAPSHOT_ISOLATION` ligados no banco de produção.
  - A API .NET e o frontend não mudam.
- **Clientes:** sem mudança de código. O Caixa e o Quack Estoque só trocam o `apiBaseUrl` para o servidor da loja.
- **Riscos:**
  - Change Tracking e versionamento de linha no banco de produção (custo de escrita e de `tempdb`);
  - mudança futura de chave primária na .NET exige desligar o tracking da tabela;
  - HTTP na rede local.
- **Branch:** `spring-api`.
