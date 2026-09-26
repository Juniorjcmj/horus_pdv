## Context

O Horus PDV utiliza a biblioteca `Zeus.Net.NFe` encapsulada em `ZeusFiscalProvider.cs` para montagem, assinatura e transmissão de documentos fiscais perante a SEFAZ. Atualmente, a emissão de NF-e (Modelo 55) em `NfeController.cs` e `ZeusFiscalProvider.cs` está parametrizada exclusivamente para operações de saída padrão (`tpNF = TipoNFe.tnSaida`, `finNFe = FinalidadeNFe.fnNormal`).

Quando uma NFC-e (Modelo 65) autorizada no caixa ultrapassa a janela legal de 30 minutos, a SEFAZ bloqueia o cancelamento. A anulação legal dessa operação exige uma NF-e de Entrada (Modelo 55) com finalidade de devolução (`finNFe = 4`) referenciando a chave da NFC-e no nó `<NFref>`.

## Goals / Non-Goals

**Goals:**
- Estender `ZeusFiscalProvider.cs` e os modelos de requisição para suportar:
  - Tipo de documento: Entrada (`TipoNFe.tnEntrada`).
  - Finalidade: Devolução (`FinalidadeNFe.fnDevolucao`).
  - Grupo de Documentos Referenciados: `<NFref>` preenchido com a chave de 44 dígitos da NFC-e de origem (`refNFe`).
  - Conversão inteligente de CFOPs de saída para entrada (`5.102` -> `1.202`, `5.405` -> `1.411`).
- Suportar emissão com destinatário híbrido: se fornecido CPF/Nome do cliente, emite em nome do consumidor; se anônimo, emite como Entrada Própria (dados da própria loja).
- Exigir autorização supervisionada (Gerente/Supervisor com validação de senha no backend) para emissão da devolução a partir do caixa.
- Processar devolução total (100% dos itens da NFC-e original) com estorno de estoque em `Produtos` e atualização da venda para `estornada/devolvida`.
- Aprimorar o componente `PdvNfceCancelModal.tsx` com detecção de tempo decorrido (> 30 min), captura de rejeição da SEFAZ, e suporte a comprovante térmico de devolução + link para DANFE A4.

**Non-Goals:**
- Devolução de notas fiscais de fornecedores (compras de mercadorias B2B), que já possuem fluxo próprio de entrada.
- Seleção fracionada/parcial de itens nesta primeira versão (a devolução abrangerá a totalidade da NFC-e original).

## Decisions

### 1. Parâmetros Fiscais e Destinatário Híbrido
- **Decisão**: Configurar `ide.tpNF = TipoNFe.tnEntrada`, `ide.finNFe = FinalidadeNFe.fnDevolucao` e adicionar a lista `ide.NFref = new List<NFref> { new NFref { refNFe = chaveNfceOrigem } }`.
- **Destinatário/Remetente Híbrido**: Se o cliente fornecer CPF/Nome ou se a NFC-e original já tiver CPF identificado, utilizar esses dados como remetente com `indIEDest = IndicadorIEDest.NaoContribuinte`. Caso a venda seja para consumidor não identificado e nenhum CPF seja fornecido, o sistema emite automaticamente como Entrada Própria (usando os dados da própria loja emitente), respeitando a legislação tributária para anulação de cupom ao consumidor.
- **Alternativa descartada**: Exigir sempre CPF/endereço de cliente anônimo no balcão, o que travaria o operador de caixa.

### 2. Autorização de Supervisor no Caixa
- **Decisão**: A emissão da devolução no PDV exige a seleção de um supervisor/gerente e sua respectiva senha, aproveitando o método `ValidateSupervisorCredentials` do `HorusSecurityStore`.
- **Alternativa descartada**: Permitir emissão sem senha ou bloquear totalmente o caixa obrigando o operador a ir até o backoffice.

### 3. Conversão Automática de CFOP e Tributos (Devolução Total)
- **Decisão**: A devolução espelha 100% dos itens da NFC-e original com conversão de CFOP:
  - `5.102` -> `1.202` (Devolução de venda de mercadoria adquirida).
  - `5.405` -> `1.411` (Devolução de venda com ST).
  - Valores de produtos, descontos e tributos espelham a saída para zerar a apuração fiscal.

### 4. Duplo Formato de Comprovante e Impressão
- **Decisão**: O modal exibe duas opções após a homologação na SEFAZ:
  1. Comprovante térmico de 80mm/58mm formatado para o cliente assinar o estorno/devolução de mercadoria no caixa.
  2. Botão para visualizar/imprimir o DANFE padrão A4 da NF-e Modelo 55 gerada.

## Risks / Trade-offs

- **[Risco: SEFAZ rejeitar a NF-e de Entrada se a NFC-e original não estiver autorizada]** → *Mitigação*: O backend valida obrigatoriamente antes da transmissão se a NFC-e de origem está no status `Autorizado` (`cStat == 100`).
- **[Risco: Venda já ter sido cancelada ou estornada anteriormente]** → *Mitigação*: Validação no repositório `DocumentoFiscalAB` checando se já existe documento com `ChaveReferenciada == chaveNfce` ou venda já cancelada/estornada.
- **[Risco: Falha de conexão com a SEFAZ durante a emissão síncrona]** → *Mitigação*: Mensagem clara de retorno ao operador sem alterar o estoque caso a SEFAZ não homologue a nota.
