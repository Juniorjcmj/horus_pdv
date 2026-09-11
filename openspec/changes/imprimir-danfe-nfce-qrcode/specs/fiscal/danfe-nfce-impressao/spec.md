## Purpose

Define os requisitos e o comportamento esperado para a renderização, visualização e impressão térmica em bobina de 80mm do Documento Auxiliar da Nota Fiscal de Consumidor Eletrônica (DANFE NFC-e), incluindo QR Code oficial da SEFAZ, numeração fiscal, chave de acesso e protocolo de autorização.

## ADDED Requirements

### Requirement: Layout Térmico do DANFE NFC-e em Bobina de 80mm
O sistema DEVE disponibilizar um layout de impressão térmica em conformidade com o Manual de Padrões Técnicos do DANFE NFC-e da SEFAZ em largura de 80mm contendo:
1. Dados do emitente (Razão Social, Nome Fantasia, CNPJ, Inscrição Estadual e endereço completo);
2. Título oficial "DANFE NFC-e - Documento Auxiliar da Nota Fiscal de Consumidor Eletrônica" e aviso legal obrigatório "Não permite aproveitamento de crédito de ICMS";
3. Relação discriminada dos itens vendidos com código, descrição, quantidade, unidade comercial, valor unitário e valor total;
4. Resumo de totais, formas de pagamento discriminadas e valor estimado dos tributos (Lei Federal nº 12.741/2012);
5. Número da NFC-e (`NumeroNf`), Série, Data e Hora de emissão;
6. Protocolo de autorização e Data/Hora da SEFAZ;
7. Chave de acesso de 44 dígitos formatada em blocos de 4 dígitos e endereço oficial do portal de consulta estadual da SEFAZ;
8. Identificação do consumidor (CPF/CNPJ ou "CONSUMIDOR NÃO IDENTIFICADO");
9. QR Code gráfico bidimensional legível gerado a partir da URL oficial (`qrCodeUrl`).

#### Scenario: Impressão de NFC-e autorizada em bobina de 80mm
- **WHEN** o operador solicita a impressão de um documento fiscal com status "Autorizado"
- **THEN** o sistema gera a folha de impressão em padrão 80mm contendo todos os dados fiscais homologados e o QR Code gráfico renderizado para leitura por smartphone.

### Requirement: Integração da Emissão Fiscal com a Finalização de Venda no PDV
Ao concluir o registro da venda no PDV (`SalesStartPage`), o sistema DEVE buscar o documento fiscal gerado e disponibilizar a impressão do DANFE NFC-e oficial.

#### Scenario: Venda concluída com autorização ágil da SEFAZ
- **WHEN** o operador confirma o pagamento da venda no PDV
- **THEN** o sistema sincroniza com a emissão em segundo plano (polling de até 3 segundos) e, constatada a autorização da nota, abre a impressão do DANFE NFC-e com QR Code e número oficial.

#### Scenario: Venda concluída com lentidão ou contingência da SEFAZ
- **WHEN** a SEFAZ não autoriza o documento dentro do tempo limite ou o documento entra em contingência pendente
- **THEN** o sistema informa o operador e abre o comprovante gerencial/contingência da venda sem bloquear o caixa, mantendo a nota na fila de emissão para envio posterior.

### Requirement: Reimpressão do DANFE NFC-e no Histórico de Vendas e Módulo Fiscal
O sistema DEVE permitir a reimpressão térmica do DANFE NFC-e a qualquer momento através do Histórico de Vendas (`SalesHistoryPage`) e do Módulo Fiscal (`FiscalPage`) para qualquer venda que já tenha documento fiscal autorizado.

#### Scenario: Reimpressão solicitada a partir do Histórico de Vendas
- **WHEN** o operador clica na opção de impressão fiscal ou visualização de DANFE de uma venda autorizada
- **THEN** o sistema oferece o botão de imprimir o DANFE em 80mm e envia o documento diretamente para a rotina de impressão do navegador com o layout oficial e QR Code.
