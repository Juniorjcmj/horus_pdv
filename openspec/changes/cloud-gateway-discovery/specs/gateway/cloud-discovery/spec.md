# Spec: Descoberta do Local Gateway via Cloud (endereço por loja)

## Overview

A Cloud é a **autoridade** do endereço LAN do Local Gateway de cada loja. O administrador cadastra esse
endereço no painel da Cloud; cada terminal daquela loja o **aprende automaticamente enquanto online** e o
mantém em **cache local**. Quando a internet cai, o terminal usa o endereço em cache para localizar o Gateway
na LAN (fluxo `probeGateway → identify` já existente), sem qualquer configuração no terminal. Camada
**aditiva**: em condição normal o terminal continua falando direto com a Cloud; a ausência de endereço apenas
faz o terminal cair no comportamento offline-first atual (IndexedDB/Outbox).

## Requirements

### REQ-1: Endereço do Gateway é dado por loja, na Cloud
- A Cloud persiste, por `(CompanyId, StoreId)`: `GatewayUrl` (ex.: `https://quack-gateway.local:5443` ou
  `http://192.168.0.10:5080`), `Enabled` (bool) e `UpdatedAt`. É a **única fonte da verdade** do endereço.
- O dado é **operacional/local**, não fiscal nem financeiro; não cria segunda verdade de negócio.

### REQ-2: Escrita pelo administrador (painel da Cloud)
- O admin define/edita/desabilita o endereço do Gateway da loja em uma tela do painel (área Admin).
- Toda escrita valida `CompanyId`/`StoreId` do usuário autenticado (isolamento multi-tenant) e a boa-formação
  da URL (esquema `http`/`https`, host, porta). Mudança reflete para os terminais no próximo aprendizado.

### REQ-3: Leitura pelo terminal (enquanto online)
- Existe um endpoint da Cloud que devolve, para o terminal autenticado, o endereço do Gateway da **sua** loja.
- O terminal chama esse endpoint em condição normal (online) e **nunca** depende dele para operar: se a Cloud
  não responder ou não houver endereço, o terminal segue o fluxo atual sem erro visível.

### REQ-4: Aprendizado e cache no terminal (zero config)
- Ao obter o endereço, o terminal grava em `gatewayConfig` (`enabled`, `url`, `companyId`, `storeId`) via a API
  local já existente (`saveGatewayConfig`), protegida contra ausência de `localStorage`.
- O cache tem **carimbo de validade** (`updatedAt`/TTL) e é revalidado a cada janela online; um endereço
  desabilitado na Cloud (`Enabled=false`) **limpa/desliga** o uso do Gateway no terminal.
- O endereço aprendido da Cloud tem **precedência** sobre uma URL manual antiga, preservando compatibilidade
  (a URL manual continua válida como *fallback* quando não há endereço vindo da Cloud).

### REQ-5: Uso na queda de internet (fallback já existente)
- Sem internet, o terminal lê o endereço em cache e segue `probeGateway` (confirma `bound` + `companyId`) →
  `identify` (por IP de origem ou token) → credencial local. Nenhuma etapa nova de descoberta é necessária.
- O isolamento por empresa é validado no `probe` (o `status.companyId` deve bater com o do terminal).

### REQ-6: Mixed content (HTTPS → HTTP na LAN)
- Como o app é servido por HTTPS, o navegador bloqueia `fetch` para `http://<ip-lan>`. A arquitetura deve
  oferecer um caminho suportado: (a) Gateway servindo **HTTPS** com certificado válido para um **host**
  (ex.: `quack-gateway.local` ou host interno), ou (b) uso de exceção documentada para rede privada. O
  endereço cadastrado na Cloud deve poder ser um **host** (não só IP) para viabilizar (a).
- A escolha é de **rede/infra**; a change apenas garante que o modelo de dado e o cliente suportam host+porta
  e esquema `https`, e documenta o procedimento.

### REQ-7: Compatibilidade e preservação do homologado
- Em condição normal (online), o terminal continua falando **direto com a Cloud**; vendas/caixa **não** passam
  a rotear pelo Gateway por causa desta change.
- Nada em IndexedDB/Outbox/`SyncEngine`/`SyncCoordinator`/Web Locks/idempotência/precisão monetária/PWA/auth
  offline/isolamento é alterado. Os **71 testes de arquitetura** permanecem verdes.
- As mudanças na API/Cloud são **aditivas** (novo endpoint + nova persistência), sem alterar contratos
  existentes.

### REQ-8: Segurança e privacidade do endereço
- O endpoint de leitura exige terminal/usuário autenticado e só devolve o endereço da **própria** loja.
- O endereço LAN não é segredo sensível, mas nunca é servido para outra empresa/loja; escrita é restrita a
  perfis administrativos.

## Acceptance Criteria (arquitetura)

- **AC-1:** O endereço do Gateway existe **por loja** na Cloud e só é lido/escrito dentro do `(CompanyId,
  StoreId)` do autenticado.
- **AC-2:** Um terminal recém-instalado, **sem nenhuma configuração local**, estando online, aprende o endereço
  do Gateway automaticamente e passa a ter `gatewayConfig` preenchido.
- **AC-3:** Ao cair a internet logo depois, o mesmo terminal localiza o Gateway pelo endereço em cache e conclui
  `identify` sem intervenção humana.
- **AC-4:** Alterar o endereço na Cloud reflete em todos os terminais da loja na próxima janela online;
  desabilitar (`Enabled=false`) faz os terminais pararem de usar o Gateway.
- **AC-5:** Com a Cloud indisponível e sem endereço em cache, o terminal opera no modo ISOLADO atual, sem erro
  visível e sem travar vendas.
- **AC-6:** O modelo de dado e o cliente aceitam **host + porta + esquema https**, viabilizando o caminho sem
  mixed content.
- **AC-7:** Nenhum fluxo homologado muda em condição normal; **71/71** testes de arquitetura verdes e os testes
  do Gateway continuam verdes.
- **AC-8:** Leitura/escrita do endereço nunca cruza empresas/lojas (isolamento comprovado por teste).

## Regra absoluta desta fase
Esta change é **proposta/arquitetura**. Nenhuma linha de código, banco, API ou frontend é alterada aqui. A
implementação (que toca a API/Cloud homologada de forma aditiva) só começa após aprovação explícita, com
preservação comprovada dos 71 testes de arquitetura.
