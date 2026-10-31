# Spec Delta

## Purpose

Frente de caixa desktop nativa (JavaFX) que permite ao operador abrir o caixa, vender, receber e fechar o turno usando a mesma API do Hórus PDV, com fila offline local e as mesmas regras de valores do caixa web.

## ADDED Requirements

### Requirement: Configuração e autenticação do operador
O caixa desktop DEVE pedir o endereço da API na primeira execução, guardá-lo localmente e autenticar o operador com e-mail e senha na rota de login existente, mantendo a sessão enquanto o aplicativo estiver aberto.

#### Scenario: Primeiro uso sem configuração
- **WHEN** o aplicativo é aberto sem endereço de API salvo
- **THEN** o aplicativo mostra o campo de endereço da API (padrão `http://localhost:5260`) junto do login e salva o valor após um login bem-sucedido.

#### Scenario: Login válido
- **WHEN** o operador informa e-mail e senha corretos
- **THEN** o aplicativo abre a frente de caixa mostrando o nome do operador e a empresa.

#### Scenario: Login recusado
- **WHEN** a API responde 401 ou 429 ao login
- **THEN** o aplicativo mostra a mensagem da API e permanece na tela de login.

#### Scenario: Sessão expirada durante o uso
- **WHEN** qualquer chamada à API responde 401 depois do login
- **THEN** o aplicativo volta para a tela de login sem perder as vendas que estão na fila offline.

### Requirement: Bloqueio de venda sem caixa aberto
O caixa desktop DEVE consultar o status do caixa e só permitir confirmar pagamento quando o status indicar que a venda é permitida.

#### Scenario: Caixa fechado
- **WHEN** o operador tenta abrir o pagamento com o caixa fechado ou expirado
- **THEN** o aplicativo bloqueia, mostra o motivo informado pela API e abre o painel de caixa.

#### Scenario: Abertura de caixa
- **WHEN** o operador informa o valor inicial e confirma a abertura
- **THEN** o aplicativo envia a abertura com identificador de evento e hash do payload, e passa a exibir "Caixa aberto" com o tempo decorrido.

#### Scenario: Fechamento de caixa
- **WHEN** o operador informa o valor contado e confirma o fechamento
- **THEN** o aplicativo envia o fechamento, mostra o esperado, o contado e a diferença retornados pela API, e volta a bloquear vendas.

#### Scenario: Sangria e reforço
- **WHEN** com o caixa aberto o operador lança sangria (F3) ou reforço (F1) com valor e motivo
- **THEN** o aplicativo registra o movimento com identificador de evento e atualiza o status do caixa.

#### Scenario: Operação de caixa sem conexão
- **WHEN** a API está inacessível e o operador tenta abrir, fechar ou movimentar o caixa
- **THEN** o aplicativo informa que a operação de caixa exige conexão e não altera o estado local.

### Requirement: Montagem do carrinho
O caixa desktop DEVE permitir adicionar produtos por nome, código interno ou código de barras, respeitar estoque disponível e aceitar quantidade fracionada apenas para unidades diferentes de `UN`.

#### Scenario: Busca por código de barras
- **WHEN** o operador lê ou digita um código igual ao código interno ou ao GTIN de um produto e pressiona Enter
- **THEN** o produto entra no carrinho com a quantidade informada (padrão 1) e o foco volta para a busca.

#### Scenario: Etiqueta de balança
- **WHEN** o código lido tem 13 dígitos, começa com `2`, tem dígito verificador EAN-13 válido e o PLU corresponde a um produto cadastrado
- **THEN** o produto entra no carrinho com o peso (ou o peso derivado do preço total) da etiqueta.

#### Scenario: Estoque insuficiente
- **WHEN** a quantidade somada no carrinho passa do estoque do produto
- **THEN** o item não é adicionado e o aplicativo mostra o estoque disponível.

#### Scenario: Quantidade fracionada em produto unitário
- **WHEN** o operador informa quantidade não inteira para um produto com unidade `UN`
- **THEN** o aplicativo recusa a quantidade.

#### Scenario: Cancelar venda em andamento
- **WHEN** o operador pressiona F8 e confirma
- **THEN** o carrinho e os pagamentos em andamento são descartados.

### Requirement: Promoções ativas
O caixa desktop DEVE aplicar as promoções vigentes com as mesmas regras do caixa web: por produto ou categoria, tipos `desconto_percentual`, `desconto_valor`, `preco_fixo`, `leve_x_pague_y`, `combo_quantidade` e `preco_atacado`, escolhendo para cada item a promoção de maior desconto em reais, sem empilhar.

#### Scenario: Leve 3 pague 2
- **WHEN** há promoção "leve 3 pague 2" vigente para um produto de R$ 5,00 e o carrinho tem 3 unidades
- **THEN** o item mostra desconto de R$ 5,00, total de R$ 10,00 e o identificador da promoção vai no item da venda.

#### Scenario: Promoção fora da vigência
- **WHEN** a promoção está inativa ou fora do intervalo de vigência
- **THEN** nenhum desconto é aplicado.

### Requirement: Pagamento com múltiplas formas
O caixa desktop DEVE permitir liquidar a venda com uma ou mais parcelas de dinheiro, PIX, débito, crédito ou fiado, com troco somente em dinheiro, e só confirmar quando a soma das parcelas for exatamente igual ao total em centavos.

#### Scenario: Pagamento único rápido
- **WHEN** o operador abre o pagamento (F12), mantém Dinheiro, informa o valor recebido maior ou igual ao total e pressiona Enter
- **THEN** a venda é confirmada com uma parcela em dinheiro e o troco é exibido.

#### Scenario: Parcela acima do saldo
- **WHEN** o operador adiciona uma parcela maior que o saldo restante
- **THEN** a parcela é recusada com o saldo restante na mensagem.

#### Scenario: Fiado sem cliente
- **WHEN** o operador escolhe fiado sem cliente selecionado
- **THEN** o aplicativo exige a seleção de um cliente cadastrado.

#### Scenario: Fiado acima do limite
- **WHEN** a parcela fiado passa de limite de crédito menos saldo devedor do cliente
- **THEN** a parcela é recusada mostrando limite, saldo e disponível.

#### Scenario: Atalhos de forma de pagamento
- **WHEN** com o pagamento aberto o operador pressiona Alt+1 a Alt+5
- **THEN** a forma selecionada passa a ser Dinheiro, PIX, Débito, Crédito ou Fiado, nessa ordem.

### Requirement: Registro idempotente da venda
O caixa desktop DEVE enviar a venda à API com `clientSaleId`, `eventId`, `occurredAt`, `offlineReference` e `payloadHash` gerados uma única vez por venda, com valores monetários validados (soma dos itens líquidos = total = soma das parcelas).

#### Scenario: Venda online aceita
- **WHEN** a API responde sucesso ao registro
- **THEN** o aplicativo mostra o número da venda, imprime o cupom, baixa o estoque no cache local e limpa o carrinho.

#### Scenario: Venda recusada pela API
- **WHEN** a API responde 4xx (exceto 408 e 429)
- **THEN** o aplicativo mostra a mensagem da API, mantém o carrinho e não grava a venda na fila offline.

#### Scenario: Reenvio da mesma venda
- **WHEN** a mesma venda é reenviada (após falha de rede ou pela fila)
- **THEN** o reenvio usa os mesmos `clientSaleId`, `eventId` e `payloadHash`, e a API devolve o mesmo número de venda sem duplicar.

### Requirement: Fila offline de vendas
O caixa desktop DEVE gravar em disco, de forma durável, toda venda que não puder ser entregue por falha de rede, timeout ou erro 5xx/408/429, e reenviá-la automaticamente quando a API voltar.

#### Scenario: Venda sem conexão
- **WHEN** a API está inacessível ao confirmar uma venda com caixa aberto no último status conhecido
- **THEN** a venda é gravada na fila local com referência `OFF-…`, o cupom é impresso com essa referência e o carrinho é limpo.

#### Scenario: Reenvio automático
- **WHEN** existe venda na fila e a API volta a responder
- **THEN** o aplicativo reenvia as vendas em ordem de criação, remove da fila as aceitas e mantém as que falharam por rede.

#### Scenario: Venda da fila recusada definitivamente
- **WHEN** a API recusa com 4xx uma venda da fila
- **THEN** a venda sai da fila de reenvio, vai para a lista de falhas com a mensagem da API e o indicador de pendências mostra a falha.

#### Scenario: Aplicativo fechado com pendências
- **WHEN** o aplicativo é fechado e reaberto com vendas na fila
- **THEN** as vendas continuam na fila e são reenviadas após o login.

### Requirement: Cache local para operação offline
O caixa desktop DEVE guardar localmente a última lista de produtos, clientes, promoções ativas, dados da empresa e status do caixa, usando o cache quando a API estiver inacessível e sincronizando com F10.

#### Scenario: Abrir sem internet com cache
- **WHEN** o aplicativo inicia a frente de caixa sem conexão e há cache de uma sessão anterior
- **THEN** a busca de produtos funciona com o cache e um aviso "Modo offline" fica visível.

#### Scenario: Sincronização manual
- **WHEN** o operador pressiona F10 com conexão
- **THEN** produtos, clientes e promoções são baixados novamente e o aplicativo mostra quantos registros foram atualizados.

### Requirement: Cupom da venda
O caixa desktop DEVE imprimir um cupom não fiscal na impressora padrão com empresa, itens, descontos, total, parcelas, troco, operador e número da venda, e permitir reimprimir a última venda.

#### Scenario: Impressão após a venda
- **WHEN** a venda é confirmada (online ou offline)
- **THEN** o cupom é enviado à impressora padrão; se a impressão falhar, a venda continua salva e o aplicativo avisa.

#### Scenario: Reimpressão
- **WHEN** o operador pede "Imprimir última venda"
- **THEN** o último cupom é impresso novamente.
