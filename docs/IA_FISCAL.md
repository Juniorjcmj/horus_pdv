# Revisão fiscal de produtos com OpenRouter

Na administração, abra **Tributação dos produtos → Configurar IA** com um usuário administrador, cadastre a chave OpenRouter e salve. Administradores e gerentes podem clicar em **Analisar com IA** na linha de um produto. A consulta é individual e cobrada pelo OpenRouter; abrir a tabela, filtrar NCM ou atualizar produtos não inicia análises pagas.

O relatório exibe pendências, dados atuais, sugestões, justificativas e referências. Para corrigir, selecione somente as sugestões habilitadas, confirme a revisão do enquadramento e clique em **Aplicar correções selecionadas**. O sistema registra usuário, produto, data, valores anteriores e novos. Não modifica XMLs, notas emitidas, preço ou estoque.

## Modelos e custos

- Análise: `google/gemini-2.5-flash-lite`, com saída JSON estruturada e fornecedor que suporte os parâmetros exigidos. Em 10/10/2026, o catálogo público informa US$ 0,10 por milhão de tokens de entrada e US$ 0,40 por milhão de saída. O gasto varia com o tamanho das referências e da resposta.
- Conferência opcional, habilitada por padrão: `typesafe/jev-1.13`, via API Alpha Decisions (`type: noul`). Jev avalia se as referências fornecidas sustentam a sugestão; não produz o relatório em texto nem comprova conformidade fiscal. Trata-se de uma segunda chamada paga. Não há fallback pago silencioso.
- Com Jev habilitado, sugestões com sustentação inferior a 0,90 ou falha na consulta não podem ser aplicadas. É possível desabilitar a conferência nas configurações; a revisão humana e as validações do servidor continuam obrigatórias.
- O relatório mostra `usage.cost` quando informado nas duas chamadas; caso contrário indica custo total desconhecido. Confira os valores efetivamente cobrados no painel OpenRouter.

Documentação oficial: [Gemini Flash-Lite](https://openrouter.ai/google/gemini-2.5-flash-lite), [saída estruturada](https://openrouter.ai/docs/guides/features/structured-outputs), [Jev](https://openrouter.ai/docs/guides/community/jev), [Alpha Decisions](https://openrouter.ai/docs/api/api-reference/alphadecisions/submit-a-decisions-request).

## Alcance fiscal

A integração contempla venda interna por NFC-e para empresa com UF RJ e CRT cadastrado. Usa candidatos de NCM e cClassTrib das tabelas oficiais já consultadas pelo sistema, além de uma referência local de CEST do Convênio ICMS 142/18 consultada em 10/10/2026. O recorte de candidatos limita o contexto enviado; não equivale à leitura integral de toda a legislação nem a uma consulta de classificação à SEFAZ.

Para NCM/CEST/classificação, códigos inexistentes, fora da vigência conhecida ou sem referência correspondente são bloqueados. Descrição ou GTIN isolados não comprovam composição, origem ou benefício. CEST não comprova incidência de substituição tributária na operação. A descrição deve permitir identificar corretamente a mercadoria e a embalagem, e o enquadramento precisa ser revisado antes da aplicação.

CFOP, origem, CSOSN/CST ICMS, alíquota ICMS e CST PIS/COFINS podem aparecer como sugestões para consulta, mas **não são habilitados para aplicação por este fluxo**: as referências fornecidas não comprovam todas as condições comerciais, documentais e legais necessárias. A revisão contábil deve preceder a edição desses campos no cadastro. A ferramenta não preenche automaticamente IBS/CBS para Simples/MEI em 2026. Não interpreta nota autorizada como prova de correção tributária.

Referências: [CONFAZ — Convênio 142/18](https://www.confaz.fazenda.gov.br/legislacao/convenios/2018/CV142_18), [LC 214, art. 348](https://www.planalto.gov.br/ccivil_03/leis/lcp/lcp214compilado.htm), [Decreto 8.442, arts. 17 e 22](https://www.planalto.gov.br/ccivil_03/_ato2015-2018/2015/decreto/d8442.htm). A base local de CEST precisa ser revisada quando a legislação mudar.

## Publicação e segurança

Publicar juntos a API e o frontend. O inicializador da API executa a migração idempotente `43_fiscal_ia.sql`, criando as tabelas de configuração e análise. Manter estável a chave de criptografia `Security:EncryptionKey` já usada pelo sistema e incluí-la no procedimento de recuperação: sem ela não é possível recuperar as credenciais protegidas após restauração.

A credencial OpenRouter é criptografada no servidor, segregada por empresa e nunca retornada pelo endpoint de configuração. O formulário usa campo de senha e limpa o valor após salvar. Não colocar a chave em variáveis `VITE_*`, repositório, logs ou no instalador. Uma chave vazia mantém a existente; **Remover chave** a exclui explicitamente da configuração.

São enviados ao OpenRouter descrição, identificação e dados fiscais do produto, regime, UF e referências. Não são enviados CNPJ/CPF, preços, certificado fiscal ou chave OpenRouter no conteúdo do prompt. A credencial trafega somente no cabeçalho de autenticação do provedor. A operação depende de internet e saldo/acesso ao modelo no OpenRouter; não interfere no funcionamento offline do caixa.

As alterações usam transação única com auditoria; não são aceitos valores arbitrários enviados pelo navegador. O servidor reaplica validações e compara o cadastro atual com o cadastro analisado, incluindo descrição, marca e GTIN. Análises expiram para aplicação em 24 horas e só podem ser aplicadas uma vez. Alteração de empresa, regime, período fiscal ou cadastro exige nova análise.

## Verificação local

`API/tests/FiscalAiChecks` testa criptografia, isolamento por empresa, resposta estruturada, rejeição de campos comerciais, transação e rollback da auditoria, aplicação restrita, repetição, cadastro alterado, sugestões inválidas, falha Jev e falta de saldo. O provedor é simulado: não há chamadas OpenRouter pagas nem emissão de documentos fiscais.

O teste SQL aceita exclusivamente um servidor descartável em `127.0.0.1,51460`. Recusa banco existente sem a marca `__FiscalAiOnly`. Execute depois de compilar a API, passando o diretório de saída, um arquivo de senha local e esse endereço. Não use banco da loja.

```powershell
dotnet build API/NETCORE
dotnet run --project API/tests/FiscalAiChecks -- "E:/PROJETOPDV/horus_pdv/API/NETCORE/bin/Debug/net8.0" "C:/caminho/local/senha-descartavel.txt" "127.0.0.1,51460"
```
