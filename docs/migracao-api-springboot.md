# Migração da API para Java (Spring Boot)

Planejamento da reescrita da API do Quack PDV (hoje em .NET 8, `API/NETCORE`) para **Java 21 + Spring Boot 3**,
rodando em **outra VPS**, sem convivência com a API atual por proxy. O Local Gateway também será migrado para Java,
mas **em um projeto posterior** (ver [§ 14](#14-gateway-migração-futura)).

| | |
| --- | --- |
| **Situação** | Planejamento aprovado para detalhar (nenhum código Java da API escrito ainda) |
| **API atual** | .NET 8, SQL escrito à mão (`SqlCommand`), 36 controllers, **227 rotas**, ~38,7 mil linhas de C# |
| **API nova** | Java 21, Spring Boot 3, Spring Data JPA, Flyway, springdoc-openapi, JUnit 5 + Mockito, biblioteca Java de NF-e/NFC-e |
| **Banco** | SQL Server (mesmo esquema), em instância própria na nova VPS |
| **Clientes** | Sistema web (React), Quack PDV desktop (Electron), Caixa JavaFX, Quack Estoque JavaFX, Local Gateway (.NET), agentes de IA via MCP |
| **Corte** | Único, por troca do endereço da API nos clientes, depois de homologação completa (ver [§ 12](#12-corte-go-live)) |

## Sumário

1. [Objetivo e decisões](#1-objetivo-e-decisões)
2. [Escopo: o que existe hoje](#2-escopo-o-que-existe-hoje)
3. [Arquitetura alvo](#3-arquitetura-alvo)
4. [Contrato com os clientes](#4-contrato-com-os-clientes)
5. [Banco de dados, Spring Data e Flyway](#5-banco-de-dados-spring-data-e-flyway)
6. [Segurança](#6-segurança)
7. [Fiscal (NFC-e / NF-e)](#7-fiscal-nfc-e--nf-e)
8. [Rotinas em segundo plano](#8-rotinas-em-segundo-plano)
9. [Servidor MCP](#9-servidor-mcp)
10. [Testes](#10-testes)
11. [Fases e cronograma](#11-fases-e-cronograma)
12. [Corte (go-live)](#12-corte-go-live)
13. [Infraestrutura da nova VPS](#13-infraestrutura-da-nova-vps)
14. [Gateway: migração futura](#14-gateway-migração-futura)
15. [Riscos](#15-riscos)
16. [Decisões em aberto](#16-decisões-em-aberto)
17. [Anexos](#17-anexos)

---

## 1. Objetivo e decisões

**Objetivo:** ter toda a API do Quack PDV em Java, com o mesmo comportamento da atual, para que os clientes troquem
apenas o endereço da API.

Decisões já tomadas:

| Decisão | Consequência |
| --- | --- |
| API nova em **outra VPS** | Ambiente, banco e deploy próprios. Nada é compartilhado com a VPS atual. |
| **Sem proxy / sem convivência** | Não existe rota "metade .NET, metade Java". A API Java só entra em produção **completa**, num corte único. Até lá, produção continua 100% no .NET. |
| **Spring Data** | Acesso a dados por repositórios Spring Data JPA (entidades + consultas derivadas + `@Query`, nativas onde o SQL é específico). |
| **Flyway** | Versionamento do esquema. Substitui o `HorusDatabaseInitializer` e os 57 scripts idempotentes atuais. |
| **springdoc-openapi** | Documentação OpenAPI/Swagger gerada pelo código. |
| **JUnit 5 + Mockito** | Testes de unidade dos serviços com dependências simuladas. |
| **Biblioteca Java de NF-e/NFC-e** | Substitui a biblioteca .NET (Zeus/Hercules) na emissão fiscal. |
| **Gateway migrado depois** | Durante e após o corte, o Gateway .NET atual conversa com a API Java. O contrato do Gateway não pode mudar. |

---

## 2. Escopo: o que existe hoje

### 2.1 Tamanho

| Camada | Arquivos | Linhas |
| --- | --- | --- |
| Controllers | 36 | 7,6 mil |
| Serviços | 60 | 9,9 mil (fiscal: 3,3 mil) |
| Repositórios (SQL à mão) | 59 | 19,0 mil |
| Middlewares | 5 | 0,5 mil |
| Modelos / DTOs | 42 | 1,5 mil |
| Migrações SQL | 57 | — |
| Tabelas | 59 | — |

### 2.2 Módulos e rotas

| Módulo | Controllers (rotas) | Base |
| --- | --- | --- |
| Autenticação e sessão | Auth (8), Sessao (3), Usuario (6) | `api/Auth`, `api/Sessao`, `api/Usuario` |
| Empresa e administração | Empresa (2), GerenciamentoEmpresas (10), DatabaseBackup (3), ApiDocumentation (1), Treinamento (7), CompanyTheme (2), Home (1) | `api/Empresa`, `api/Admin/Empresas`, `api/treinamento`, `api/company-theme` |
| Cadastros | Categoria (6), Fornecedor (4), Cliente (5), ModuloMercado (4) | `api/Categoria`, `api/Fornecedor`, `api/Cliente`, `api/ModuloMercado` |
| Produtos | Produto (13), Lote (8), EntradaManual (1), NfeImport (10), FiscalAi (9) | `api/Produto`, `api/Produto/lotes`, `api/Produto/entrada-manual`, `api/NfeImport`, `api/Produto/fiscal-ia` |
| Estoque | Inventario (11), EstoqueRelatorio (6), EstoqueAnalise (23) | `api/Inventario`, `api/Estoque/relatorios`, `api/Estoque/analise` |
| Compras e promoções | OrdemCompra (7), Promocao (8) | `api/OrdemCompra`, `api/Promocao` |
| Vendas | Caixa (6), HistoricoVendas (9), Pedido (5), Fiado (4) | `api/Caixa`, `api/HistoricoVendas`, `api/Pedido`, `api/Fiado` |
| Fiscal | Nfce (13), Nfe (7), RegrasEmissaoNfce (3) | `api/Nfce`, `api/Nfe`, `api/regras-emissao-nfce` |
| Gateway | GatewayToken (4), GatewayConfig (2), GatewayCredit (12) | `api/GatewayToken`, `api/gateway-config`, `api/gateway-credit` |
| Relatórios | Relatorio (1) | `api/Relatorio` |
| Integração (IA) | IntegracaoToken (3) + servidor MCP | `api/IntegracaoToken`, `/mcp` |

### 2.3 Rotinas em segundo plano

| Rotina | Função |
| --- | --- |
| `NfceOutboxWorker` | Fila de emissão da NFC-e: emite, reenvia, cancela ou inutiliza (inclusive vendas canceladas antes da autorização). |
| `FiscalAiWorker` | Revisão fiscal dos produtos por IA, em lote. |
| `EstoqueSnapshotWorker` | Foto diária do estoque (base da análise de estoque). |
| `DatabaseBackupService` | Backup do banco sob demanda/agendado. |

### 2.4 Pontos que exigem reprodução exata

- **Login:** JWT HS256 assinado com `Auth:JwtSecret`, enviado em **cookie HttpOnly** (web) ou no cabeçalho `Authorization` (apps), com sessão validada no banco a cada requisição.
- **Senhas:** PBKDF2-SHA256 (sal + iterações gravados no hash). Os hashes atuais precisam continuar válidos.
- **Segredos cifrados no banco:** AES-GCM com chave derivada de `Security:EncryptionKey` (certificado A1 e senhas de integrações). O formato de bytes precisa ser idêntico.
- **Tokens de integração e do Gateway:** prefixos (`qmcp_`, token do Gateway) e SHA-256 no banco.
- **Idempotência:** vendas e eventos offline (`ProcessedEvents`, `eventId` + `payloadHash`).
- **Protocolo de fiado do Gateway:** `Shared/CreditProtocol.cs` (concessões, autorizações, assinaturas).

---

## 3. Arquitetura alvo

### 3.1 Stack

| Necessidade | Escolha |
| --- | --- |
| Linguagem / runtime | Java 21 (LTS), virtual threads ligadas |
| Framework | Spring Boot 3.x (Spring Web MVC) |
| Dados | Spring Data JPA (Hibernate) + driver Microsoft SQL Server (`mssql-jdbc`) |
| Esquema | Flyway |
| Documentação | springdoc-openapi (Swagger UI só fora de produção, como hoje) |
| Segurança | Spring Security (filtro próprio para JWT/sessão, cookie e tokens de integração) |
| Validação | Jakarta Bean Validation |
| JSON | Jackson (configurado para o formato atual: ver [§ 4](#4-contrato-com-os-clientes)) |
| Cache | Caffeine (substitui o `IMemoryCache`) |
| Limite de requisições | Bucket4j |
| Agendamento | `@Scheduled` (filas e rotinas) |
| E-mail | Spring Mail |
| Fiscal | Biblioteca Java de NF-e/NFC-e (ver [§ 7](#7-fiscal-nfc-e--nf-e)) |
| MCP | SDK oficial de MCP para Java (servidor HTTP *stateless*) |
| Testes | JUnit 5, Mockito, AssertJ, Spring Boot Test (`@WebMvcTest`, `@DataJpaTest`) |
| Build | Maven, Docker (multi-stage) |

### 3.2 Organização do código

Pacotes por domínio (o mesmo recorte dos módulos do § 2.2), cada um com suas camadas:

```
JAVA/api/                              (repositório atual, pasta nova)
└── src/main/java/br/com/quack/pdv/
    ├── QuackPdvApiApplication.java
    ├── shared/            envelope ApiResponse, erros, formatação pt-BR, Jackson, paginação
    ├── security/          filtro JWT/sessão, perfis, PBKDF2, AES-GCM, rate limit, cabeçalhos
    ├── auth/              login, sessão, usuários, recuperação de senha
    ├── empresa/           empresa, tema, administração, treinamento, backup
    ├── cadastro/          categorias, fornecedores, clientes, módulo mercado
    ├── produto/           produtos, lotes/validade, entrada manual, importação de NF-e, fiscal com IA
    ├── estoque/           movimentações (registrador único), inventário, relatórios, análise
    ├── compras/           ordens de compra
    ├── promocao/          promoções e resultado
    ├── venda/             caixa, vendas, cancelamento/correção, pedidos, histórico, fiado
    ├── fiscal/            NFC-e, NF-e, regras de emissão, fila, DF-e
    ├── gateway/           tokens, configuração e fiado do Gateway
    ├── relatorio/         relatórios gerais
    └── mcp/               servidor MCP e tokens de integração
        (cada módulo: web/ controllers · app/ serviços · domain/ entidades · repo/ repositórios Spring Data · dto/)
```

Regras:

- **Controllers finos:** recebem/validam o DTO, chamam o serviço e devolvem o envelope. Nada de regra de negócio.
- **Serviços com as regras** (é o que os testes com Mockito cobrem).
- **Repositórios Spring Data** por agregado; consultas de relatório em `@Query(nativeQuery = true)` com *projections* (interfaces ou records).
- **Uma única porta de mudança de estoque** (equivalente ao `EstoqueMovimentacaoAB.AplicarMovimentoAsync`): todo serviço que mexe em saldo passa por ela, na mesma transação.
- **Transações** declarativas (`@Transactional`) no serviço; o comportamento de cada operação composta hoje (venda + estoque + caixa + fiscal) é mapeado na fase correspondente.

### 3.3 Modo loja (servidor local)

Decisão de 03/11/2026, para a loja de material de construção (7 máquinas). Lojas com vários terminais rodam a
**mesma API Java** num **servidor dedicado da loja**, com **SQL Server Express** local. Todos os caixas, o Quack
Estoque e a web da loja apontam para esse servidor pela rede local. Ele concentra os dados e as transações da loja e
sincroniza com a nuvem. Sem internet, a loja inteira continua operando junta: nenhum caixa fica isolado.

```
NUVEM: API Java + SQL Server (todas as empresas), painel web, MCP
          ▲  sincronização pela internet (sobe eventos, desce comandos)
LOJA:  servidor dedicado: API Java (modo loja) + SQL Server Express
          ▲  rede local
       caixas 1..7 · Quack Estoque · web da loja  (cada caixa mantém sua fila offline)
```

Regras:

1. **Uma autoridade por empresa.** Com a empresa em modo loja, o servidor da loja é a autoridade de todos os dados
   da loja (vendas, caixa, estoque, cadastros, preços, fiado). A nuvem é uma réplica. Edições feitas pela web na
   nuvem (o dono alterando preço ou cadastro de fora da loja) **descem como comandos** e são aplicadas pelo servidor
   da loja, que devolve o resultado. Não há mestre duplo nem resolução de conflito.
2. **Numeração única.** Número da venda (`SaleNumber`), numeração fiscal (`FiscalSequencias`) e número do inventário
   são gerados só pela autoridade da empresa, por um único serviço de numeração (Fase 4). Isso é obrigatório porque o
   ID da venda **deriva do número** (`sale-{empresa}-{número}`, assim como itens e pagamentos); os demais IDs são
   GUID em texto e não colidem.
3. **Sincronização loja → nuvem pelo Change Tracking do SQL Server** (disponível na Express; ligado só no banco da
   loja). Um processo lê, em snapshot, as linhas que mudaram até uma versão e as envia à nuvem em lotes, com backoff
   exponencial; separa erro temporário (rede, 408, 429, 5xx: tenta de novo) de erro definitivo (4xx: para e alerta).
   A nuvem aplica o estado das linhas de forma idempotente. Como há uma só autoridade, não há conflito. Escolhido em
   03/11/2026 no lugar de uma fila de saída gravada pela aplicação, porque cobre todas as tabelas sem mexer em cada
   gravação (ver design D9 de `api-java-vendas`).
4. **Caixas falam só com o servidor da loja.** Se ele cair, cada caixa usa a própria fila offline (Caixa JavaFX) e
   reenvia quando ele voltar. Os caixas não pulam para a nuvem (evita duas versões da verdade).
5. **NFC-e emitida pelo servidor da loja**, com o certificado lá; sem internet, contingência offline e transmissão
   depois.
6. **Gateway .NET aposentado** nas lojas com servidor: a API local absorve fiado compartilhado, assinatura fiscal
   local e fila. O Gateway continua para lojas pequenas sem servidor.
6A. **Operações de estoque lançadas na nuvem descem para a loja** (change `api-java-operacoes-estoque-nuvem`). Entrada
   de nota (NF-e, cupom, manual), estorno, ajuste e saldo pela edição do produto, gravados pela .NET na nuvem, chegam
   à loja como um comando `operacao`, aplicado numa transação só. A loja **nunca copia o saldo da nuvem**: soma a
   diferença de cada movimentação ao saldo dela (que tem as vendas que a nuvem ainda não viu) e devolve o resultado
   pela sincronização normal. A mesma NF-e já lançada na loja termina em conflito (estoque sobe uma vez e o dono é
   alertado). Inventário, ordens de compra, vendas e caixa continuam só na loja. O de-para de fornecedor é cadastro
   editável nas duas pontas. Implantação: **loja antes da nuvem** (a loja anuncia `operacao-estoque`; sem o anúncio a
   nuvem mantém a quarentena).
7. **Infra da loja:** máquina dedicada (não é um dos caixas), nobreak, IP fixo na rede, backup automático do banco
   local. SQL Server Express (10 GB por banco) basta para uma loja; o histórico completo fica na nuvem.

**Decisões de 04/11/2026 (Fase 4b, `openspec/changes/api-java-modo-loja`):**

- **Receptor Java na nuvem.** A nuvem recebe a sincronização em um container novo, a própria API Java em perfil `nuvem`
  (`pdv-api-sync`), no mesmo SQL Server e ao lado da API .NET. Ele expõe só `/api/loja-sync/**`; a .NET, o painel e o
  frontend não mudam. Só o Change Tracking, o isolamento snapshot e as tabelas `Sync*` entram no banco da nuvem.
- **Cadastros, preços e configurações descem por comando.** Produtos, clientes, fornecedores, categorias, promoções,
  usuários e configurações da empresa editados pelo painel ou pelo MCP viram comandos que a loja aplica coluna a coluna,
  conferindo o valor de base; se a loja mudou a mesma coluna, vale a loja e o comando volta como conflito. Edição da nuvem
  em tabela que só a loja grava (vendas, caixa, estoque, fiado) é desfeita com o estado da loja, gera alerta e fica em
  quarentena.
- **Fiscal com autoria dividida até a Fase 7.** Na 4b a loja reserva o número e a venda sobe com a nota pendente; a
  emissão continua com o worker da API .NET da nuvem e o resultado desce para a loja. A Fase 7 passa a emissão para o
  servidor da loja.
- **Conferência diária (03:00) e backup diário (02:00)**, confirmados pelo dono; depois de restaurar um backup a loja se
  recupera da nuvem antes de voltar a vender.

**Monitoramento do modo loja (change `api-java-monitoramento`):**

- **Métricas Prometheus** da API Java (perfis `nuvem` e `loja`) em `/actuator/prometheus`, **protegidas por token**
  (`QUACK_METRICS_TOKEN`; sem token a rota é 404). `/actuator/health` continua aberto. Tempo das requisições com histograma
  (P95/P99 por rota), JVM, threads, Hikari e, no receptor, a sincronização por loja: último contato, última sincronização,
  comandos por situação, quarentena e alertas.
- **Relato de estado da loja:** a cada minuto o servidor da loja envia à nuvem (`POST /api/loja-sync/estado`, token do
  servidor) o atraso do envio, o último backup (data do último **sucesso** e resultado da última tentativa), NFC-e
  pendentes, pausa e versão do programa. É observação, não sincronização: nunca entra na fila de envio, nunca gera alerta e
  é descartado se a nuvem não responde. A nuvem guarda só o último relato (`SyncEstadoLoja`, `db/nuvem/V3`) e o publica como
  `quack_loja_*`. Loja com jar antigo simplesmente não relata. Isso fecha o buraco do backup que falhou desde a instalação
  sem ninguém ver (erro 1844).
- **Painéis:** Prometheus (30 dias, sem rota pública) e Grafana (login obrigatório, `monitor-pdv.quacksistemas.com.br`) na
  stack `pdv`, em imagens próprias com configuração e painéis versionados em `monitoramento/` (**Sincronização das lojas** e
  **API Java**). Implantação, variáveis, DNS e conferência: seção "Monitoramento" do [`DEPLOY.md`](../DEPLOY.md). As
  métricas locais da loja (`MetricasLoja`) funcionam mesmo sem internet e só ficam ligadas com `QUACK_METRICS_TOKEN` no
  `loja.env`.

Enquanto o modo loja não estiver pronto, a loja pode começar com os caixas ligados direto à nuvem atual (cada caixa
com sua fila offline) e migrar para o servidor da loja quando as fases 4, 4b e 7 estiverem prontas.

---

## 4. Contrato com os clientes

A regra mais importante: **nenhum cliente pode precisar de mudança de código no corte**, só do endereço da API.

| Item | Como fica |
| --- | --- |
| Rotas | Mesmos caminhos e verbos, incluindo maiúsculas (`/api/Produto`, `/api/Estoque/analise`...). |
| Envelope | `{ success, message, details, data }` em todas as respostas, inclusive erros. |
| Códigos HTTP | Os mesmos de hoje (400 regra de negócio, 401 sessão, 403 perfil, 404, 409 conflito de preço/nota autorizada, 429 limite). |
| Nomes no JSON | camelCase, iguais aos atuais. |
| Números | Respeitar o que cada rota devolve hoje: strings pt-BR (`"1.234,56"`, `productQnt: "10"`) em produtos/relatórios e números JSON em outras. Formatação centralizada num utilitário equivalente ao `HorusMoneyFormat`. |
| Datas | `DateTimeOffset` → `OffsetDateTime` com fuso (`2026-11-01T22:30:00-03:00`); datas puras `AAAA-MM-DD`. |
| Cookie de sessão | Mesmo nome, `HttpOnly`, `Secure`, `SameSite` e validade. |
| Cabeçalhos do Gateway | `X-Operator-Id` e demais cabeçalhos aceitos exatamente como hoje. |
| CORS | Mesmas origens (`Security:CorsOrigins`). |

**Como garantir:** na Fase 0 o contrato é congelado (OpenAPI exportada do Swagger atual + respostas reais gravadas de
cada rota, com dados de teste). Cada rota Java só é dada como pronta quando a resposta dela bate com a gravação
(ver [§ 10](#10-testes)).

---

## 5. Banco de dados, Spring Data e Flyway

### 5.1 Banco

- SQL Server na nova VPS, **mesmo esquema** da produção atual (59 tabelas).
- Em desenvolvimento e homologação: cópia restaurada de um backup da produção (anonimizada quando possível).
- No corte: restauração do backup final da produção (ver [§ 12](#12-corte-go-live)).

### 5.2 Flyway

| Script | Conteúdo |
| --- | --- |
| `V1__esquema_inicial.sql` | Esquema completo atual, gerado a partir da produção (tabelas, índices, constraints, tipos), equivalente ao resultado das 57 migrações. |
| `V2__...` em diante | Toda mudança nova, só por Flyway. |

- Banco **restaurado da produção**: `baselineOnMigrate=true` com `baselineVersion=1` (o V1 não roda; o Flyway assume que o esquema já é o V1).
- Banco **novo** (dev/testes): o V1 cria tudo.
- A tabela de controle do inicializador .NET é mantida no V1 só por histórico; não é mais usada.
- Dados de referência fiscal hoje em `DataBase/FiscalTables/*.json` e os XSD em `DataBase/Schemas` vão para `src/main/resources`.

### 5.3 Spring Data

- **Entidades JPA** para as tabelas de cadastro e de movimento, com o mapeamento exato de nomes (`@Table`, `@Column`), tipos (`DECIMAL(p,s)` → `BigDecimal`, `DATETIMEOFFSET` → `OffsetDateTime`) e chaves (hoje são `NVARCHAR` geradas pela aplicação, como `pr-...`, `itk-...`: mantidas).
- **Repositórios** `JpaRepository` por agregado; filtros por empresa sempre presentes (`findByCompanyIdAnd...`). O isolamento por `CompanyId` é regra: nenhuma consulta sem ele.
- **Relatórios e análise de estoque** (SQL pesado com CTEs e agregações): `@Query(nativeQuery = true)` com projeções, reaproveitando o SQL atual, que já está escrito à mão.
- **Concorrência:** controles atuais por versão (ex.: preço com `versaoConsultada`, `EstoqueConsultado` relativo) mantidos; onde houver `UPDATE ... WHERE` condicional hoje, usar `@Modifying @Query` ou `@Version`.
- Configuração: `open-in-view=false`, `ddl-auto=validate` (o Flyway é quem muda o esquema; o Hibernate só confere).

---

## 6. Segurança

| Item atual (.NET) | Em Java |
| --- | --- |
| `HorusAuthMiddleware` | Filtro do Spring Security: lê o JWT (cookie ou `Authorization`), valida assinatura HS256, confere sessão/usuário/empresa no banco, carrega perfis adicionais. |
| Token do Gateway | Mesmo filtro, ramo do token do Gateway (age em nome do operador do cabeçalho). |
| Token de integração `qmcp_` | Aceito **só em `/mcp`**, como hoje. |
| `[HorusAuthorizeRoles]` | `@PreAuthorize` com os mesmos perfis (administrador, gerente, atendente, estoquista, caixa, integração) e perfis adicionais (financeiro). |
| PBKDF2-SHA256 | `javax.crypto` (`PBKDF2WithHmacSHA256`), mesmo formato de hash; teste com hashes reais gerados pelo .NET. |
| AES-GCM (`HorusSecretProtector`) | `javax.crypto` AES/GCM, mesma derivação de chave, mesmo layout (nonce + tag + texto). Teste: decifrar segredos cifrados pelo .NET. |
| `HorusRateLimitMiddleware` | Bucket4j por IP (`Security:RateLimit`). |
| `HorusSecurityHeadersMiddleware` | Filtro de cabeçalhos (mesmos valores). |
| `HorusRequestBodyLimitMiddleware` | `spring.servlet.multipart` + limite no Tomcat (`Security:MaxRequestBodyBytes`). |
| `HorusRequestTelemetryMiddleware` | Filtro de log/telemetria (tempo, rota, status). |
| reCAPTCHA no login/cadastro | Mesmo fluxo (`Recaptcha:*`). |
| Auditoria (`AuditLog`) | Serviço de auditoria com os mesmos tipos de evento e limite de 500 caracteres. |

As **mesmas chaves** (`JwtSecret`, `EncryptionKey`) da produção vão para a nova VPS: assim os usuários continuam logados,
as senhas valem e os certificados A1 continuam abrindo depois do corte.

---

## 7. Fiscal (NFC-e / NF-e)

É a parte de maior risco e fica para a fase mais longa ([§ 11](#11-fases-e-cronograma)).

- **Biblioteca:** biblioteca Java de NF-e/NFC-e (candidata principal: *Java_NFe*, `br.com.swconsultoria:java-nfe`). Na Fase 0 é feita uma prova de conceito para confirmar:
  - emissão NFC-e 4.00 com QR Code e CSC;
  - contingência offline (tpEmis 9) e transmissão posterior;
  - cancelamento, inutilização e consulta de status;
  - NF-e modelo 55 e download de DF-e (manifestação/distribuição);
  - suporte aos grupos da **reforma tributária (IBS/CBS)**, já usados no cadastro (`CstIbsCbs`, `cClassTrib`);
  - certificado A1 lido do banco (cifrado), sem arquivo em disco.
- **O que se porta:** `ZeusFiscalProvider` (montagem e envio), `FiscalTaxRules`, `FiscalPaymentRules`, `FiscalRetryRules`, `FiscalXmlArchive`, `SefazDFeDownloadService`, `NfceOutboxWorker`, `RegrasEmissaoNfce`, importação de NF-e de entrada (`NfeXmlParser`) e a revisão fiscal com IA.
- **Validação:** emissão em **homologação da SEFAZ** com certificado de teste, comparando o XML gerado pelo Java com o gerado pelo .NET para as mesmas vendas (mesmos grupos, totais e tributos).
- **Numeração:** as séries/números (`FiscalSequencias`) seguem do ponto em que a produção parar no corte, sem pular nem repetir.

---

## 8. Rotinas em segundo plano

| Atual | Java | Observação |
| --- | --- | --- |
| `NfceOutboxWorker` | `@Scheduled` + bloqueio por linha (`UPDLOCK, READPAST`) para não processar a mesma nota duas vezes | Só uma instância da API processa a fila. |
| `FiscalAiWorker` | `@Scheduled` | Mantém limites de lote e de custo da IA. |
| `EstoqueSnapshotWorker` | `@Scheduled(cron = ...)` no fuso `America/Sao_Paulo` | Foto diária. |
| `DatabaseBackupService` | Serviço + rotas de admin | Backup do SQL Server da nova VPS. |

---

## 9. Servidor MCP

- Endpoint `/mcp` (Streamable HTTP, *stateless*), com as **21 ferramentas** atuais (19 de leitura + `editar_produto` e `ajustar_estoque_produto`) e as mesmas instruções do servidor.
- Mesmas regras: token `qmcp_`, empresa sempre do token, escrita só com `PermiteEscritaProdutos`, simulação antes de aplicar, auditoria.
- `docs/MCP.md` continua valendo; só muda o endereço.

---

## 10. Testes

| Nível | Ferramenta | O que cobre |
| --- | --- | --- |
| Unidade | JUnit 5 + **Mockito** | Serviços com repositórios simulados: regras de venda, estoque (registrador único), preço, inventário, promoções, validade, fiscal (montagem de tributos), segurança (PBKDF2, AES-GCM, JWT). Meta: regras de negócio 100% cobertas por teste de serviço. |
| Web | `@WebMvcTest` + Mockito | Rotas, validação, envelope, códigos HTTP e perfis de acesso por rota. |
| Repositório | `@DataJpaTest` | Mapeamento das entidades e consultas nativas contra SQL Server de teste (o mesmo usado em Docker hoje). |
| Compatibilidade de formato | Testes de serialização | Strings pt-BR, datas com fuso, nulos e nomes de campo iguais à gravação do contrato (§ 4). |
| Contrato | Comparador de respostas | Para cada rota: chama a API .NET e a Java com o mesmo banco de teste e compara o JSON (ignorando ids e horários). |
| Ponta a ponta | Testes já existentes | Testes E2E do Caixa JavaFX e do Quack Estoque (`-Dquack.e2e.url=<API Java>`) e testes Playwright do sistema web apontados para a API Java em homologação. |
| Fiscal | Homologação SEFAZ | Emissão, cancelamento, inutilização e contingência com certificado de teste. |

---

## 11. Fases e cronograma

Estimativa para **1 desenvolvedor em tempo integral**. Com 2, as fases 2 a 7 podem andar em paralelo (cerca de 40% menos tempo).

| # | Fase | Entregas | Pronto quando | Prazo |
| --- | --- | --- | --- | --- |
| 0 | Preparação | Contrato congelado (OpenAPI + respostas gravadas); comparador de respostas; prova de conceito da biblioteca fiscal; VPS de homologação com SQL Server e cópia da base | PoC fiscal emite NFC-e em homologação; contrato gravado para as 227 rotas | 2 sem |
| 1 | Fundação | Projeto Spring Boot; Flyway (V1 + baseline); Jackson e envelope; erros; segurança completa (JWT/cookie/sessão, PBKDF2, AES-GCM, perfis, rate limit, cabeçalhos, CORS); auditoria; springdoc; Docker + CI | Login, sessão e troca de senha funcionando com usuários reais da cópia; segredos decifrados | 3 sem |
| 2 | Cadastros e administração | Empresa, tema, usuários, categorias, fornecedores, clientes, módulo mercado, treinamento, admin de empresas | Rotas iguais ao contrato; telas web de cadastro funcionando contra a API Java | 3 sem |
| 3 | Produtos e estoque | Produtos (inclusive preço com versão e estoque relativo), registrador único de movimentação, lotes/validade e FEFO, entrada manual, importação de NF-e, inventário, ordens de compra, promoções | E2E do Quack Estoque passando contra a API Java | 4 sem |
| 4 | Vendas (**concluída em 03/11/2026**) | Caixa (abertura, fechamento, sangria/reforço), vendas e itens, idempotência offline, cancelamento e correção com senha do gerente, pedidos, histórico, fiado, autorizações de preço | E2E do Caixa JavaFX (inclusive offline) e Playwright de vendas passando; volta ao aceite do Quack Estoque os 6 E2E de inventário que dependem de venda (excluídos na Fase 3, ver D10 de `api-java-produtos-estoque`) | 5 sem Concluída: caixa (6 rotas), HistoricoVendas (9), Pedido (5) e Fiado (4) com autorização de preço, numeração centralizada, fila fiscal mínima e retentativa da baixa de lotes; 24 grupos de contrato verdes; E2E do Caixa e do Quack Estoque (inclusive os 6 de inventário) verdes contra a API Java. Divergências: `xmlNfceContingencia` (Fase 7), `creditAuthorization`/canal do Gateway (Fase 5) e finalização de pedido, que na Java funciona apesar do 500 da .NET. |
| 4b | Modo loja e sincronização | Perfil `loja` da API; sincronização loja → nuvem por Change Tracking; comandos nuvem → loja; carga inicial da loja; instalador do servidor da loja (API + SQL Server Express + backup) | Loja de teste com 2+ caixas operando sem internet e convergindo com a nuvem ao voltar | 3–4 sem | **Implementada em 05/11/2026**, falta o checklist manual 8.4 (máquina Windows com 2 caixas). Receptor Java em perfil `nuvem`, comandos por Change Tracking, conferência diária, instalador, recuperação após restauração; aceite com o Caixa e o Quack Estoque JavaFX contra o servidor da loja e a .NET real. |
| 5 | Gateway (rotas da API) | Tokens do Gateway, configuração, fiado do Gateway (`gateway-credit`, protocolo do `Shared`) | Gateway .NET atual sincronizando com a API Java em homologação | 2 sem |
| 6 | Relatórios e análise | Relatórios gerais, relatórios de estoque, análise de estoque (23 rotas), foto diária | Relatórios iguais aos da API .NET sobre a mesma base | 3 sem |
| 7 | Fiscal | NFC-e e NF-e (emissão, fila, contingência, cancelamento, inutilização, DF-e, regras de emissão), revisão fiscal com IA | Bateria em homologação SEFAZ aprovada; XML equivalente ao do .NET | 6–8 sem |
| 8 | MCP e backup | Servidor MCP com as 21 ferramentas, tokens de integração, backup | Agente conectado à API Java respondendo igual | 1–2 sem |
| 9 | Homologação geral | Todos os clientes apontados para a VPS nova (homologação), uso assistido, correções, teste de carga | Checklist de corte (§ 12) completo | 2–3 sem |
| 10 | Corte | Migração dos dados e virada (§ 12) | Produção na VPS nova | 1 dia + 1 sem de acompanhamento |

**Prioridade da loja de material de construção (modo loja):** 4 → 4b → 7 → 6. As fases 5 e 8 seguem depois. A Fase 4 já nasce com a numeração centralizada (§ 3.3).

**Total estimado: 32 a 37 semanas (cerca de 8 a 9 meses) com 1 pessoa; 5 a 6 meses com 2.**

Cada fase vira uma proposta OpenSpec própria (`openspec/changes/api-java-faseN-...`), com `proposal.md`, `design.md`
e `tasks.md`, seguindo o fluxo do projeto (proposta em Opus, implementação em subagente).

---

## 12. Corte (go-live)

Como não há convivência, o corte é feito numa janela fora do horário de venda.

**Antes do dia**

- [ ] Todas as fases concluídas e a homologação geral aprovada (§ 11, fase 9).
- [ ] Chaves de produção (`JwtSecret`, `EncryptionKey`, e-mail, reCAPTCHA, IA) configuradas na VPS nova.
- [ ] Endereço novo pronto (DNS + HTTPS) e testado.
- [ ] Ensaio completo do corte em homologação (restaurar backup, Flyway baseline, apontar clientes, vender, emitir NFC-e).
- [ ] Clientes preparados para trocar de endereço: `.env` do web, configuração do app desktop, `apiUrl` dos apps JavaFX e do Gateway. Recomendado: o endereço público da API (`api-pdv.quacksistemas.com.br`) **passa a apontar para a VPS nova via DNS**, e nenhum cliente precisa mudar.

**No dia**

1. Avisar as lojas; garantir que os caixas sincronizaram tudo (filas offline vazias, incluindo o Gateway).
2. Parar a API .NET (modo manutenção) para não entrar dado novo.
3. Backup final do SQL Server de produção → restaurar na VPS nova.
4. Subir a API Java (Flyway faz o baseline; `ddl-auto=validate` confere o esquema).
5. Trocar o DNS da API para a VPS nova (TTL reduzido dias antes).
6. Teste rápido: login, venda, NFC-e em produção, sincronização do Gateway, relatório.
7. Liberar as lojas.

**Volta atrás (rollback)**

A VPS antiga fica intacta e desligada por pelo menos 2 semanas. Se algo crítico falhar nas primeiras horas: parar a
API Java, voltar o DNS e, se já houve vendas na nova, exportá-las (o mecanismo de idempotência permite reenviar).

---

## 13. Infraestrutura da nova VPS

- Docker: imagem `eclipse-temurin:21-jre` (multi-stage com Maven), publicada no GitHub Container Registry pelo GitHub Actions, como a atual.
- Serviços: API Java + SQL Server (contêiner ou instância dedicada), volume para backups e XMLs fiscais.
- Configuração por variáveis de ambiente, com nomes equivalentes aos atuais (anexo 17.3).
- Observabilidade: Spring Boot Actuator (health para o Portainer/monitor), logs estruturados.
- Fuso do contêiner `America/Sao_Paulo`.

---

## 14. Gateway: migração futura

Fora do escopo desta migração, mas condiciona decisões dela:

- A API Java mantém **exatamente** o contrato que o Gateway .NET usa hoje (eventos, tokens, `X-Operator-Id`, protocolo de fiado do `Shared`).
- O protocolo do `Shared/CreditProtocol.cs` é reescrito em Java dentro da API (módulo `gateway`), com testes que validam mensagens geradas pelo Gateway .NET real.
- Quando o Gateway for migrado, esse código Java do protocolo vira uma biblioteca compartilhada entre API e Gateway.
- Lembrete: enquanto o Gateway for .NET, qualquer mudança em `GATEWAY/src` exige regerar o instalador (`GATEWAY/build-installer.sh`).

---

## 15. Riscos

| Risco | Impacto | Mitigação |
| --- | --- | --- |
| Biblioteca fiscal Java não cobrir algo que a Zeus faz (ex.: IBS/CBS, contingência) | Alto | PoC na Fase 0, antes de qualquer outra fase; alternativa: montar o XML com JAXB a partir dos XSD oficiais e usar a biblioteca só para assinatura/transmissão. |
| Diferenças de formato no JSON (números pt-BR, datas, nulos) | Alto | Contrato gravado + comparador de respostas em todas as rotas. |
| Comportamento transacional diferente (venda + estoque + caixa) | Alto | Mapear cada operação composta na fase 4; testes de serviço com Mockito para a ordem das operações e E2E para o resultado no banco. |
| Hibernate gerando SQL menos eficiente que o atual nos relatórios | Médio | Relatórios e análise com SQL nativo reaproveitado; medir tempo das consultas na homologação. |
| Corte único sem convivência | Alto | Homologação geral com todos os clientes, ensaio do corte e rollback pronto. |
| Senhas ou segredos não batendo (PBKDF2/AES-GCM) | Alto | Testes com hashes e segredos reais gerados pelo .NET já na Fase 1. |
| Gateway .NET incompatível com a API Java | Alto | Fase 5 dedicada; teste com o Gateway real em homologação. |
| Prazo longo com produção evoluindo no .NET | Médio | Congelar novas funcionalidades na API .NET ou registrar cada mudança para portar; o contrato gravado é atualizado junto. |

---

## 16. Decisões em aberto

1. **Nome e local do projeto:** proposta `JAVA/api` neste repositório, pacote `br.com.quack.pdv`.
2. **Banco na VPS nova:** SQL Server em contêiner ou instância gerenciada? (Licença: a edição Express tem limite de 10 GB por banco.)
3. **Endereço no corte:** trocar o DNS do domínio atual (recomendado) ou publicar um domínio novo e reconfigurar os clientes.
4. **Congelamento da API .NET:** a partir de qual fase novas funcionalidades passam a ser feitas só no Java.
5. **Equipe:** 1 ou 2 desenvolvedores (muda o cronograma).
6. **Spring Data JPA x JDBC:** este plano usa JPA; Spring Data JDBC é alternativa mais próxima do SQL atual, se preferir menos "mágica" do Hibernate.

---

## 17. Anexos

### 17.1 Tabelas (59)

`AuditLog`, `AutorizacoesPreco`, `CaixaMovimentos`, `CaixaSessoes`, `Categorias`, `Clientes`,
`CustomerCreditPolicies`, `DocumentosFiscais`, `EmpresaTema`, `Empresas`, `EstoqueConfigAnalise`,
`EstoqueMovimentacoes`, `EstoqueSnapshotDiario`, `FiadoMovimentos`, `FiscalIaAnalises`, `FiscalIaConfig`,
`FiscalIaSolicitacoes`, `FiscalSequencias`, `Fornecedores`, `GatewayCreditBudgets`, `GatewayCreditConsumptions`,
`GatewayCreditGrants`, `GatewayCreditOperatorPermits`, `GatewayCreditStores`, `GatewayCreditTerminals`,
`GatewayTokens`, `IntegracaoTokens`, `InventarioContagens`, `InventarioEventos`, `InventarioItens`, `Inventarios`,
`LojaGatewayConfig`, `LoteConfig`, `LoteConsumos`, `MapeamentoProdutoFornecedor`, `ModuloMercadoRegistros`,
`ModulosMercado`, `NotasEntradaArquivo`, `OrdemCompra`, `OrdemCompraItens`, `PasswordResetTokens`, `PedidoItens`,
`Pedidos`, `ProcessedEvents`, `ProdutoClassificacao`, `ProdutoLotes`, `Produtos`, `PromocaoProdutos`, `Promocoes`,
`RegrasEmissaoNfce`, `Sessoes`, `TreinamentoSecoes`, `TreinamentoVideos`, `UserCreditStores`, `Usuarios`,
`VendaCancelamentos`, `VendaItens`, `VendaPagamentos`, `Vendas`.

### 17.2 Controllers (36) e quantidade de rotas

| Controller | Rotas | Controller | Rotas |
| --- | --- | --- | --- |
| EstoqueAnalise | 23 | Usuario | 6 |
| Produto | 13 | EstoqueRelatorio | 6 |
| Nfce | 13 | Caixa | 6 |
| GatewayCredit | 12 | Categoria | 6 |
| Inventario | 11 | Pedido | 5 |
| GerenciamentoEmpresas | 10 | Cliente | 5 |
| NfeImport | 10 | GatewayToken | 4 |
| HistoricoVendas | 9 | Fornecedor | 4 |
| FiscalAi | 9 | Fiado | 4 |
| Auth | 8 | ModuloMercado | 4 |
| Lote | 8 | Sessao | 3 |
| Promocao | 8 | RegrasEmissaoNfce | 3 |
| Treinamento | 7 | DatabaseBackup | 3 |
| OrdemCompra | 7 | IntegracaoToken | 3 |
| Nfe | 7 | Empresa, GatewayConfig, CompanyTheme | 2 cada |
| | | EntradaManual, Relatorio, Home, ApiDocumentation | 1 cada |

### 17.3 Configuração (.NET → Spring)

| .NET (`appsettings` / env) | Spring (`application.yml` / env) |
| --- | --- |
| `ConnectionStrings__HorusPdv` | `SPRING_DATASOURCE_URL`, `_USERNAME`, `_PASSWORD` |
| `Auth__JwtSecret`, `Auth__Issuer`, `Auth__Audience`, `Auth__SessionHours`, `Auth__PasswordResetTokenMinutes` | `QUACK_AUTH_JWT_SECRET`, `QUACK_AUTH_ISSUER`, `QUACK_AUTH_AUDIENCE`, `QUACK_AUTH_SESSION_HOURS`, `QUACK_AUTH_PASSWORD_RESET_TOKEN_MINUTES` |
| `Security__EncryptionKey` | `QUACK_SECURITY_ENCRYPTION_KEY` (**mesmo valor da produção**) |
| `Security__CorsOrigins`, `Security__TrustForwardedHeaders`, `Security__MaxRequestBodyBytes` | `QUACK_SECURITY_CORS_ORIGINS`, `SERVER_FORWARD_HEADERS_STRATEGY`, `QUACK_SECURITY_MAX_REQUEST_BODY_BYTES` |
| `Security__RateLimit__WindowSeconds`, `__MaxRequests` | `QUACK_SECURITY_RATE_LIMIT_WINDOW_SECONDS`, `QUACK_SECURITY_RATE_LIMIT_MAX_REQUESTS` |
| `Recaptcha__Enabled`, `__SecretKey`, `__MinScore` | `QUACK_RECAPTCHA_ENABLED`, `QUACK_RECAPTCHA_SECRET_KEY`, `QUACK_RECAPTCHA_MIN_SCORE` |
| `Email__*` (Host, Port, EnableSsl, User, Password, FromEmail, FromName, ReplyTo, FrontendBaseUrl) | `SPRING_MAIL_*` + `QUACK_EMAIL_FROM_*`, `QUACK_EMAIL_FRONTEND_BASE_URL` |
| `TZ=America/Sao_Paulo` | `TZ=America/Sao_Paulo` |

## 18. Importação de produtos por planilha (API Java)

Somente na API Java (nada muda em `API/NETCORE/` nem no Gateway). Change `api-java-importacao-produtos`.

- Rotas `POST /api/Produto/importacao/simular` e `/confirmar` (alias `/api/produtos/importacao/...`), `multipart/form-data` com o campo `arquivo`, perfis administrador e gerente. Simular lê e valida sem gravar; confirmar refaz o mesmo plano e grava numa transação (timeout de 300 s).
- Layout: CSV do sistema anterior da loja (separador `;`, ISO-8859-1 ou UTF-8 com BOM), colunas reconhecidas **pelo nome** do cabeçalho. Preço de venda = `Preço Venda Tab.3`; código do produto = `Código` do sistema anterior; GTIN só quando válido (senão `SEM GTIN`); fiscal importado como veio; categorias `Classe > Grupo > Sub-Grupo` (3 níveis); saldo inicial como movimentação `entrada` com origem `importacao`.
- Idempotência do estoque: tabela `ImportacoesProduto` (migração `V7`), classe `FORA` do catálogo de sincronização (não sobe para a nuvem). Reimportar atualiza cadastro e preço e não lança estoque de novo.
- Limites: `spring.servlet.multipart.max-file-size=15MB` / `max-request-size=16MB`; o filtro de corpo aceita 16 MB só nas duas rotas (as demais seguem em 2 MB).
- No modo loja, os produtos, categorias e movimentações sobem pela sincronização existente; importar no servidor da loja, antes de ligar os caixas (ver `JAVA/api/instalador-loja/CHECKLIST.md`, seção 2A).
- **O arquivo do cliente (`docs/planilha material/`) não é versionado**: tem custos, preços e CNPJ de fornecedores; os testes usam uma amostra sintética (`src/test/resources/importacao/amostra-legado.csv`).
