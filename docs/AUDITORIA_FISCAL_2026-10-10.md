# Conferência fiscal — 10/10/2026

## Arquivo fornecido pela loja

Arquivo analisado: **NFCe_XMLs_2026_10 (1).zip**, sem alterar o original.

| Verificação | Resultado |
| --- | --- |
| Arquivos XML | 345 |
| Chaves diferentes | 345 |
| Modelo | 65, NFC-e, em todos os arquivos |
| UF da chave e protocolo | 33, Rio de Janeiro |
| Ambiente declarado | 1, produção |
| Status do protocolo | 100, autorização de uso, em todos os arquivos |
| Dígito verificador da chave | Válido nas 345 chaves |
| Primeiro protocolo | 02/10/2026, 15:29:20, horário de Brasília |
| Último protocolo | 10/10/2026, 15:24:38, horário de Brasília |
| Conteúdo | Apenas `retEnviNFe` e `protNFe`, sem `NFe/infNFe` |

Distribuição de protocolos: 02/10: 5; 03/10: 22; 05/10: 47; 06/10: 25; 07/10: 63; 08/10: 67; 09/10: 86; 10/10: 30.

**Não é possível verificar NCM, CEST, CFOP, valores, CST, itens ou tributos dessas notas a partir deste ZIP.** O arquivo contém as respostas de autorização da SEFAZ e não os XMLs completos. Não houve consulta posterior à SEFAZ para conferir cancelamentos; a autorização aqui é o status registrado nos protocolos fornecidos. A análise não comprova que o ZIP inclui toda a movimentação fiscal da loja.

## Problemas encontrados no sistema e correções

1. **Exportação incompleta:** a emissão armazenava a resposta da autorização em `XmlProtocolado`. Os downloads davam preferência a ela e descartavam o XML assinado que contém os produtos. A emissão agora reúne o XML assinado e o protocolo em `nfeProc`; a exportação também recupera registros antigos sem mudar os originais. Confere chave, código de autorização e digest da assinatura. Sem os registros originais necessários, informa o problema em vez de oferecer um protocolo como nota completa. Não recria produtos ou valores a partir da venda atual.
2. **Exportação mensal:** notas sem vínculo com uma venda eram omitidas por uma junção obrigatória. A consulta agora preserva esses documentos e restringe a junção à mesma empresa.
3. **ICMS:** o grupo passava a ser escolhido pela presença de CSOSN, mesmo em empresa de regime normal. A escolha agora usa o CRT; CST 40/41/50 e 60 são preservados; ICMS 00 calcula a base após desconto e os totais somam os itens. Códigos sem cálculo implementado geram pendência explícita, sem substituição por 102 ou 00.
4. **IBS/CBS de 2026:** o XML antigo colocava 0,1% no município, deixava o IBS estadual e os valores dos tributos zerados e não agregava os totais. O cálculo comum do regime normal agora aplica 0,1% estadual, zero municipal e 0,9% CBS, com base líquida dos valores suportados e redução conforme a classificação informada. Isenção/não incidência não recebem grupo tributável indevido. Não infere a classificação pela descrição. Não aplica as alíquotas do regime normal ao Simples/MEI/excesso de sublimite em 2026 nem as reutiliza automaticamente em 2027. Alíquotas de 2026: [LC 214 compilada, artigos 343, 346 e 348](https://www.presidencia.gov.br/ccivil_03/leis/lcp/lcp214compilado.htm).
5. **PIS/COFINS:** CST tributável 01/02/03 não pode gerar um grupo genérico com valores zerados. O emissor sinaliza os enquadramentos que exigem cálculo ainda não disponível no cadastro.

A ausência de rejeição por falta de campos IBS/CBS não comprova conformidade tributária. O adiamento das validações não altera automaticamente as demais obrigações: [esclarecimento oficial da Receita Federal e do CGIBS](https://cgibs.gov.br/receita-federal-e-comite-gestor-do-ibs-esclarecem-adiamento-das-regras-de-validacao-dos-documentos-fiscais-eletronicos).

## Ferramenta criada

**Cadastro de Produto → ações do produto → Verificar situação fiscal.** Confere os dados salvos, usando o regime da própria empresa, para venda interna por NFC-e no RJ:

- Existência e vigência do NCM, com descrição oficial hierárquica.
- Existência, vigência, modelo permitido e correspondência entre CST IBS/CBS e cClassTrib.
- Compatibilidade do CRT com CSOSN/CST e códigos suportados pelo emissor.
- Formato de CFOP/CEST, origem, unidades e alíquota ICMS; verificador do GTIN.
- Pontos que precisam de contabilidade: composição/uso do produto, ST, monofasia, benefícios, FECP e enquadramento legal.

Pode consultar as fontes oficiais sem enviar os dados da loja; usa referência datada em indisponibilidade e deixa isso visível. A consulta não grava alterações nem altera os três campos obrigatórios do cadastro mínimo. Os campos CST IBS/CBS e cClassTrib agora podem ser editados na área fiscal do produto.

Um NCM existir na tabela não significa que seja adequado ao produto. A ferramenta não verifica registro do GTIN na GS1, vínculo real do código ao produto, enquadramento de benefícios ou credenciamento/certificado/CSC junto à SEFAZ. Não certifica conformidade integral e não implementa cálculos complexos de ST, FECP, créditos, diferimento ou monofasia. Os schemas locais foram usados nos testes; não foi emitida nota de teste no ambiente da loja.

Fontes: [NCM/Siscomex](https://portalunico.siscomex.gov.br/classif/), [classificações IBS/CBS/SVRS](https://dfe-portal.svrs.rs.gov.br/CFF/ClassificacaoTributaria), [NT 2025.002 v1.52 e IT 2025.002 v1.70](https://dfe-portal.svrs.rs.gov.br/Nfe/Documentos), [orientações NFC-e da SEFAZ/RJ](https://portal.fazenda.rj.gov.br/dfe/wp-content/uploads/sites/17/2023/01/DF-e_NFC-e.pdf).

## Validação e disponibilização

- 33 verificações fiscais: cálculos, códigos, vigência, referência offline, protocolos, assinatura criptográfica preservada e NFC-e completa assinada validada nos schemas locais.
- Consulta real das duas fontes públicas também passou, sem dados da loja.
- 26 verificações com SQL isolado: cadastro mínimo, consulta HTTP, separação entre empresas, ausência de credenciais na resposta e recuperação/exportação de XML legado, sem alterar os registros.
- Aplicativo Windows 1.3.7: botão fiscal, mensagem de referência datada, erro/repetição e acesso à edição passaram; testes existentes de leitor, impressão, fechamento, entradas, backup e preservação do perfil também passaram.
- Compilação da API e frontend passaram. Nenhuma chamada de emissão ou alteração foi feita na API ou no banco da loja.

**A API atualizada precisa ser publicada para ativar a conferência e corrigir a exportação em produção.** Depois, exportar outubro novamente para analisar os NCMs e tributos dos XMLs completos. Se o banco não tiver o XML assinado original, o protocolo do ZIP não permite reconstruí-lo.

Instalador gerado: `C:/Users/USUARIO/Downloads/quack-pdv-setup-1.3.7.exe` (155.420.904 bytes), SHA-256 `AA00D7855038B66755731944F5D948E12005A0733EBE7C39E2269E244370F883`. O aplicativo empacotado foi testado em perfil isolado. O instalador não foi executado sobre o perfil de trabalho da loja.
