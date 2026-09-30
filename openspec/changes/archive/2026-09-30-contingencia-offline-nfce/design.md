## Context

Atualmente, o sistema Hórus PDV emite NFC-e através do [`ZeusFiscalProvider.cs`](file:///e:/PROJETOPDV/horus_pdv/API/NETCORE/Services/Fiscal/ZeusFiscalProvider.cs), orquestrado pelo [`NfceOutboxWorker.cs`](file:///e:/PROJETOPDV/horus_pdv/API/NETCORE/Services/Fiscal/NfceOutboxWorker.cs) e persistido na tabela `DocumentosFiscais` via [`DocumentoFiscalAB.cs`](file:///e:/PROJETOPDV/horus_pdv/API/NETCORE/Repositories/DatabaseAccess/DocumentoFiscalAB.cs).

A base de dados já possui colunas estruturais para suporte fiscal (`TpEmis`, `DhContingencia`, `JustContingencia`, `Status`), porém o fluxo de contingência offline (`tpEmis = 9`) não está conectado de ponta a ponta:
1. Quando a SEFAZ está inoperante ou sofre timeout, a nota fica travada na fila ou vai para erro definitivo sem gerar o DANFE térmico com a tarja de contingência e QR Code offline para o cliente levar.
2. Quando a loja perde a internet, o terminal não possui um meio local de gerar o XML assinado com certificado A1 e QR Code para impressão fiscal imediata.
3. Não há no PDV nem no painel fiscal uma interface clara de monitoramento e controle do prazo regulatório de 24 horas da SEFAZ para envio das notas emitidas em contingência.

## Goals / Non-Goals

**Goals:**
- Garantir a geração de XML de NFC-e em contingência (`tpEmis = 9`) com cálculo correto de chave de 44 dígitos, `dhCont`, `xJust` ($\ge 15$ caracteres) e QR Code versão 2.0 com `cHashQRCode` (digest value).
- Suportar emissão imediata em contingência pela Cloud quando a SEFAZ estiver fora do ar ou apresentar timeout.
- Suportar emissão em contingência pela LAN através do `HorusGateway` com o certificado digital A1 provisionado localmente quando a loja estiver sem internet.
- Renderizar o DANFE de 80mm com a tarja oficial "EMITIDA EM CONTINGÊNCIA - Pendente de autorização" e QR Code legível.
- Transmitir automaticamente os lotes pendentes assim que a conexão for restabelecida e tratar retornos de homologação e duplicidade (`cStat 539`).
- Exibir alertas no PDV e Painel Fiscal sobre notas em contingência e contagem regressiva do prazo legal de 24h.

**Non-Goals:**
- Contingência EPEC (usada para NF-e Modelo 55 interestadual); o escopo aqui é NFC-e Modelo 65 (`tpEmis = 9`).
- Assinatura digital no navegador Web (PWA): a chave privada do certificado digital A1 permanece protegida nos servidores (Cloud ou Local Gateway).
- Inutilização automática de notas que foram autorizadas com sucesso.

## Decisions

### Decisão 1: Arquitetura Híbrida para Emissão Offline
- **Escolha**:
  - *Internet ativa + SEFAZ fora do ar*: Backend Cloud assume a contingência (`tpEmis = 9`), assina o XML e devolve imediatamente ao PDV com status `ContingenciaPendente`.
  - *Loja sem internet*: O terminal do PDV envia o payload da venda ao `HorusGateway` na LAN (`POST /api/gateway/fiscal/nfce/contingencia`), que assina o XML localmente com o certificado A1 local e devolve o XML assinado + dados do DANFE térmico.
- **Alternativa descartada**: Assinar o XML diretamente no navegador via JavaScript/Web Crypto. Descartada pois violaria a segurança do certificado A1 (exporia arquivo `.pfx` e senha no cliente web) e a especificação XMLDSig Enveloped com Canonicalization C14N é propensa a incompatibilidades em runtimes de navegador.

### Decisão 2: Geração de QR Code versão 2.0 em Contingência
- **Escolha**:
  - Em contingência offline (`tpEmis = 9`), a URL do QR Code da SEFAZ exige parâmetros específicos: Chave, Versão (`2`), Tipo de Ambiente (`tpAmb`), Data/Hora de Emissão em Hexadecimal (`dhEmi`), Valor Total da Nota, Digest Value da nota em Hexadecimal (`digVal`), IdToken do CSC e Hash SHA-1 (`cHashQRCode`).
  - O cálculo do hash será centralizado no provedor fiscal e utilizado tanto na Cloud quanto no Gateway para garantir total compatibilidade com o leitor da SEFAZ.

### Decisão 3: Imutabilidade do XML Assinado na Transmissão
- **Escolha**:
  - O XML gerado e assinado no momento da contingência é **imutável**. Quando a SEFAZ voltar, o `NfceOutboxWorker` envia o mesmo XML assinado, sem regerar datas, chaves ou hashes.
  - A SEFAZ valida se o XML transmitido bate com o impresso pelo consumidor no momento da contingência.

### Decisão 4: Resolução de Duplicidades e Conflitos (cStat 539)
- **Escolha**:
  - Se a SEFAZ responder com `cStat 539` (Duplicidade de NF-e com diferença na Chave de Acesso), o sistema executa uma consulta de situação da chave (`consSitNFe`). Se a nota já constar como autorizada na SEFAZ, o protocolo retornado é vinculado e o documento é marcado como `Autorizado`.

## Risks / Trade-offs

| Risco | Mitigação |
|---|---|
| Prazo de 24h da SEFAZ estourado | Alertas visuais com badges coloridas (Verde $< 12h$, Amarelo $12h-20h$, Vermelho $> 20h$) no PDV e na tela Fiscal, além de disparo de worker em background a cada 1 minuto. |
| Divergência de numeração entre caixa e cloud | Cada terminal possui série fiscal dedicada quando operando no Gateway local, evitando colisão de numeração com a Cloud. |
| Certificado digital vencido ou corrompido no Gateway | Health check de certificado no Gateway (`/api/gateway/fiscal/status`) que valida validade e senha do certificado A1 no boot do Gateway. |
| SEFAZ rejeitar lote por erro de cadastro | Notas rejeitadas definitivamente saem da fila automática e são marcadas como `Rejeitado` para intervenção do operador, permitindo correção ou emissão de NF-e substitutiva. |

## Migration Plan

1. **Banco de Dados**: As colunas `TpEmis`, `DhContingencia`, `JustContingencia` e status `ContingenciaPendente` já existem na tabela `DocumentosFiscais`. Criar índice `IX_DocumentosFiscais_Contingencia` para agilizar busca de notas pendentes de envio.
2. **Deploy do Backend e Gateway**: Compatível com versões anteriores — notas normais continuam com `tpEmis = 1`.
3. **Rollback**: Caso necessário, o modo contingência pode ser forçado para inativo via flag `Fiscal:AllowContingency = false` no `appsettings.json`.
