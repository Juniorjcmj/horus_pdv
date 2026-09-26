## Purpose

Permite a emissão de Nota Fiscal Eletrônica (NF-e Modelo 55) de Entrada por Devolução referenciando a chave de acesso da NFC-e original para anulação fiscal e estorno mercantil após expiração do prazo de cancelamento.

## ADDED Requirements

### Requirement: Emissão de NF-e de Entrada com finalidade de devolução
O sistema DEVE permitir emitir uma NF-e (Modelo 55) configurada com Tipo de Operação Entrada (`tpNF = 0`), Finalidade de Devolução (`finNFe = 4`) e com a chave de 44 dígitos da NFC-e de origem inserida no grupo de notas fiscais referenciadas (`<NFref>`).

#### Scenario: Transmissão bem-sucedida de NF-e de devolução referenciando NFC-e
- **WHEN** o usuário solicitar a devolução de uma NFC-e autorizada e informar a justificativa
- **THEN** o sistema SHALL gerar e assinar o XML da NF-e com `tpNF = 0`, `finNFe = 4`, tag `<NFref>` preenchida com a chave da NFC-e, transmitir à SEFAZ e registrar o protocolo de autorização retornado

#### Scenario: Rejeição de emissão quando a NFC-e de origem não existir ou não estiver autorizada
- **WHEN** for solicitada a devolução para uma NFC-e inexistente, já cancelada ou rejeitada
- **THEN** o sistema SHALL recusar a operação e retornar erro informando que apenas NFC-e com status Autorizado podem ser objeto de devolução de venda

### Requirement: Regra híbrida para remetente em devolução ao consumidor
O sistema DEVE preencher os dados do remetente da NF-e de devolução de forma híbrida: se o cliente fornecer CPF/Nome ou se a NFC-e original contiver esses dados, preenche como destinatário pessoa física; caso contrário, emite como Entrada Própria com os dados cadastrais da própria loja emitente.

#### Scenario: Devolução com cliente identificado
- **WHEN** a devolução for solicitada com CPF/Nome do consumidor fornecido
- **THEN** o sistema SHALL preencher o remetente com o CPF, Nome e `indIEDest = 9` (Não Contribuinte)

#### Scenario: Devolução de consumidor não identificado (Entrada Própria)
- **WHEN** a NFC-e original não tiver CPF e nenhum documento for informado pelo consumidor
- **THEN** o sistema SHALL preencher o remetente/destinatário com os dados cadastrais da própria empresa emitente (Entrada Própria amparada pela legislação fiscal)

### Requirement: Autorização supervisionada no caixa para emissão da devolução
A emissão da NF-e de Devolução originada a partir do caixa (PDV) DEVE exigir a seleção de um supervisor/gerente ativo e a validação de sua senha cadastrada no servidor.

#### Scenario: Autorização de supervisor válida no PDV
- **WHEN** o operador selecionar um gerente válido e informar a senha correta
- **THEN** o sistema SHALL autorizar a emissão da NF-e de devolução e registrar o ID e nome do supervisor no log de auditoria

#### Scenario: Bloqueio por senha de supervisor incorreta
- **WHEN** a senha do supervisor for inválida ou o supervisor estiver inativo
- **THEN** o sistema SHALL recusar a solicitação com status HTTP 403 Forbidden e impedir a emissão do documento fiscal

### Requirement: Validação de dados fiscais e conversão de CFOP
O sistema DEVE mapear automaticamente a totalidade dos itens da NFC-e original com seus respectivos CFOPs de devolução de venda (`1.202` para tributação normal ou `1.411` para Substituição Tributária).

#### Scenario: Mapeamento de CFOP para devolução total
- **WHEN** os itens da NFC-e original forem carregados para geração da NF-e de devolução
- **THEN** o sistema SHALL converter itens com CFOP `5.102` para `1.202` e itens com CFOP `5.405` para `1.411` e calcular os totais espelhados da operação

#### Scenario: Exigência de justificativa mínima
- **WHEN** o usuário submeter a emissão da devolução sem justificativa ou com justificativa menor que 15 caracteres
- **THEN** o sistema SHALL bloquear a emissão e solicitar a justificativa com no mínimo 15 caracteres

### Requirement: Estorno de estoque e integridade de auditoria
O sistema DEVE reintroduzir as quantidades devolvidas no estoque de produtos e registrar o log de auditoria relacionando a NF-e de entrada à NFC-e de saída original.

#### Scenario: Incremento do estoque e vínculo documental
- **WHEN** a NF-e de devolução for autorizada com sucesso pela SEFAZ
- **THEN** o sistema SHALL incrementar o saldo em estoque dos produtos devolvidos, marcar a venda original com status de estorno/devolução e registrar na trilha de auditoria os dados da NF-e gerada

### Requirement: Orientação inteligente e duplo formato de impressão no PDV
O modal do PDV DEVE orientar o operador quanto ao prazo de 30 minutos e, após autorização da NF-e de devolução, disponibilizar tanto a impressão de comprovante térmico para assinatura do cliente quanto a visualização do DANFE A4 em PDF.

#### Scenario: Alerta preventivo de prazo de 30 minutos expirado
- **WHEN** o operador pesquisar no PDV uma NFC-e autorizada emitida há mais de 30 minutos
- **THEN** o sistema SHALL exibir aviso informando que o prazo legal de 30 minutos expirou e disponibilizar a ação de solicitar a emissão de NF-e de devolução

#### Scenario: Impressão de comprovante térmico e visualização do DANFE
- **WHEN** a NF-e de devolução for autorizada pela SEFAZ
- **THEN** o sistema SHALL exibir modal com as opções de impressão do comprovante térmico (para assinatura física da devolução) e botão para abrir o DANFE em PDF A4
