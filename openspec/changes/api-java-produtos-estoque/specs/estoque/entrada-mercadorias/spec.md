# Spec Delta

## Purpose

Entrada de mercadorias no estoque pela API Java: entrada manual, importação por arquivo XML de NF-e e NFC-e, o
histórico de notas de entrada com estorno, exportação e comprovantes, com o mesmo contrato da API .NET.

## ADDED Requirements

### Requirement: Acesso às entradas
As rotas de `api/NfeImport` e `api/Produto/entrada-manual` SHALL aceitar administrador, gerente, atendente e
estoquista. Estornar, exportar e vincular XML a uma nota SHALL exigir administrador ou gerente. Toda operação SHALL
ficar restrita à empresa do usuário logado.

#### Scenario: Estoquista tenta estornar
- **WHEN** um estoquista chama `DELETE /api/NfeImport/notas-entrada/{id}`
- **THEN** a resposta é 403

#### Scenario: Caixa sem acesso
- **WHEN** um caixa chama `POST /api/NfeImport/preview`
- **THEN** a resposta é 403

### Requirement: Pré-visualizar XML de nota
`POST /api/NfeImport/preview` SHALL ler o XML enviado (NF-e modelo 55 ou NFC-e modelo 65, até 10 MB), conferir chave,
modelo, número e série, identificar o fornecedor pelo CNPJ do emitente e sugerir o produto de cada item, respondendo
"XML lido com sucesso." sem alterar o estoque.

#### Scenario: Modelo não aceito
- **WHEN** o XML é de um documento que não é modelo 55 nem 65
- **THEN** a resposta é 400 com "A entrada aceita somente notas dos modelos 55 e 65."

#### Scenario: XML sem itens
- **WHEN** o XML não tem nenhum `<det>`
- **THEN** a resposta é 400 com "XML não tem nenhum item (<det>) para importar."

#### Scenario: Pré-visualização não mexe no estoque
- **WHEN** um XML válido é pré-visualizado
- **THEN** nenhum saldo, lote ou nota de entrada é gravado

### Requirement: Confirmar entrada por XML
`POST /api/NfeImport/confirmar` SHALL criar ou vincular os produtos de cada item e dar entrada pelo registrador único,
com custo convertido por unidade e lote. SHALL guardar a nota de entrada com o XML e responder "Importação concluída
com sucesso.". A mesma nota MUST NOT dar entrada duas vezes.

#### Scenario: Nota repetida
- **WHEN** a mesma chave de acesso é confirmada de novo
- **THEN** a resposta é 400 com "Esta nota já deu entrada no sistema. Consulte Notas de entrada para ver os itens; o
  estoque não foi alterado." e o saldo não muda

#### Scenario: Código já cadastrado
- **WHEN** um item manda criar produto com um código que já existe na empresa
- **THEN** a resposta é 400 com "Código <código> já cadastrado ou repetido. Selecione o produto existente." e nada é gravado

### Requirement: Entrada manual
`POST /api/Produto/entrada-manual` SHALL dar entrada de 1 a 200 itens sem XML, com fornecedor opcional da empresa,
validade e lote por item, e responder "Entrada registrada no estoque.". A entrada SHALL ser idempotente pelo
identificador da operação.

#### Scenario: Reenvio da mesma operação
- **WHEN** o cliente reenvia a mesma operação com os mesmos dados (por exemplo, depois de cair a conexão)
- **THEN** a resposta repete o resultado da primeira vez e o estoque não muda de novo

#### Scenario: Mesma operação com outros dados
- **WHEN** o cliente reenvia o identificador de uma operação já confirmada com itens diferentes
- **THEN** a resposta é 400 com "Esta entrada já foi confirmada com outros dados. Consulte o histórico e inicie uma
  nova entrada."

#### Scenario: Itens demais
- **WHEN** a entrada traz 201 itens
- **THEN** a resposta é 400 com "Adicione entre 1 e 200 produtos à entrada."

### Requirement: Notas de entrada
`GET /api/NfeImport/notas-entrada` SHALL listar as notas de entrada da empresa. Também SHALL devolver o detalhe de uma
nota (`/{id}`), o XML guardado (`/{id}/xml`) e o comprovante (`/{id}/comprovante`) com o tipo de conteúdo e o nome de
arquivo da API .NET.

#### Scenario: Nota de outra empresa
- **WHEN** o id é de uma nota de outra empresa
- **THEN** a resposta é 404

### Requirement: Estornar nota de entrada
`DELETE /api/NfeImport/notas-entrada/{id}?justificativa=` SHALL exigir justificativa com 5 ou mais caracteres, tirar do
estoque, pelo registrador único, o que a nota deu de entrada e responder "Nota de entrada estornada com sucesso. O
estoque foi ajustado.". Uma nota estornada MUST NOT ser estornada de novo.

#### Scenario: Justificativa curta
- **WHEN** a justificativa é "erro"
- **THEN** a resposta é 400 com "Informe uma justificativa com pelo menos 5 caracteres para estornar a nota."

#### Scenario: Estorno repetido
- **WHEN** a nota já foi estornada
- **THEN** a resposta é 400 com "Nota de entrada não encontrada ou já foi estornada." e o estoque não muda

### Requirement: Exportar notas do mês
`GET /api/NfeImport/notas-entrada/exportar?ano=&mes=` SHALL devolver um ZIP `notas-entrada-<ano>-<mês com 2 dígitos>.zip`
com os XML das notas do mês e um `resumo.csv`.

#### Scenario: Mês inválido
- **WHEN** o mês pedido é 13
- **THEN** a resposta é 400 com "Mês inválido."

### Requirement: Vincular XML a uma nota
`POST /api/NfeImport/notas-entrada/{id}/xml` SHALL guardar o XML de uma nota que entrou sem ele, conferindo que a chave
e o emitente batem com a entrada, e responder "XML vinculado à entrada. O estoque não foi alterado.".

#### Scenario: Emitente diferente
- **WHEN** o XML enviado é de outro emitente
- **THEN** a resposta é 400 com "O fornecedor da entrada deve ser o emitente do XML."

### Requirement: Busca na SEFAZ fora desta versão
`POST /api/NfeImport/buscar-sefaz` SHALL validar a chave de acesso com as mesmas mensagens da API .NET (44 dígitos,
dígito verificador, modelo 65 não aceito) e, com a chave válida, SHALL responder 400 dizendo que a busca na SEFAZ ainda
não está disponível e que a nota pode ser importada pelo XML.

#### Scenario: Chave com 43 dígitos
- **WHEN** a chave enviada tem 43 dígitos
- **THEN** a resposta é 400 com "Chave de acesso inválida. Informe os 44 dígitos da nota."

#### Scenario: Chave válida
- **WHEN** a chave enviada é uma chave válida de NF-e
- **THEN** a resposta é 400, a mensagem orienta a importar pelo XML, e nada é gravado
