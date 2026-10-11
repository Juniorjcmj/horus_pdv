# Revisão do PDV: DANFE NFC-e, Gateway e CONFAZ

Data de referência: 10/10/2026. Empresa informada pelo usuário: Simples Nacional, Rio de Janeiro.

## Correções implementadas após a revisão

As correções técnicas estão implementadas no código local. A análise histórica abaixo descreve o estado anterior, de base `220e8ee`; suas referências a números de linha podem ter mudado. A versão publicada no servidor e o aplicativo da loja não foram alterados por esta execução.

- **Gateway:** XML 4.00 validado nos schemas oficiais, assinatura verificada, endereço real do emitente e campos fiscais reais dos produtos. Dados obrigatórios ausentes ou cálculo não suportado interrompem a emissão, sem inventar NCM, CSOSN ou grupos zerados. O QR offline usa oito parâmetros, inclusive dia civil, total e digest da assinatura.
- **Nota offline original:** a tentativa local é guardada antes da assinatura, com identificação idempotente. A sincronização fica retida enquanto a resposta for desconhecida. A API confere empresa, certificado, assinatura, ambiente, itens, consumidor, pagamentos e troco; importa o XML original e transmite a mesma chave. Os endereços oficiais RJ antigos e atuais são aceitos sem alterar um QR já emitido.
- **Resposta perdida e repetição:** XML e chave são persistidos antes de transmitir. A recuperação consulta a nota original; duplicidade não renumera automaticamente. A decisão fiscal e o contador de intervalo são confirmados juntos por venda. A fila retoma decisões pendentes após reinício, sem emitir nova nota para venda cancelada ou repetir numeração de documento rejeitado.
- **Autorização 120:** preserva protocolo e todos os alertas, arquiva/exporta o documento completo e mostra as mensagens na administração. O DANFE também conserva as mensagens fiscais do XML.
- **Dinheiro e troco:** `vPag` representa o recebido antes do troco. Pagamentos mistos e valores inconsistentes são conferidos antes da transmissão.
- **DANFE e impressão:** itens, unidades, descontos, consumidor, datas, tributos, protocolo e QR saem do XML original. Foram removidos a estimativa fixa de tributos e os dados fiscais fabricados a partir do recibo. Há duas marcações de contingência, QR de 30 mm com área de segurança de 4 mm e fila persistida de impressão. Venda que exige NFC-e aguarda o documento; não recebe recibo genérico como substituição automática. A confirmação do aplicativo instalado usa a impressora padrão.
- **CEST e IA:** referência versionada por vigência, restauração dos textos válidos em outubro e bloqueio de códigos do Convênio 107/26 antes de 01/11/2026. A IA não pode apagar CEST sem fundamento nem aplicar códigos futuros. O NCM isolado não comprova a descrição/embalagem nem a obrigação de ST. A cobertura histórica da base começa em 10/10/2026, explicitamente; não foi reconstruída a legislação anterior a essa data.
- **Banco e distribuição:** a migração idempotente `44_fiscal_qrcode.sql` cria o armazenamento do QR original e da decisão fiscal por venda. O kit Gateway foi regenerado; o instalador 1.3.8 inclui esse Gateway e o frontend atualizado.

Validação local: 107 testes Gateway, 54 verificações fiscais, 23 de CEST, 17 de importação fiscal, 30 de comunicação Gateway/API, 51 de persistência SQL descartável, 33 testes de navegador e 12 do aplicativo instalado. São 327 verificações distribuídas nessas suítes; testes de integração usam certificados fictícios, banco descartável e serviços simulados. API e frontend compilam sem erros. Nenhuma nota foi emitida na SEFAZ e nenhum dado da loja foi alterado.

**Publicação:** atualizar a API e suas migrações antes de instalar o aplicativo 1.3.8, pois a nova impressão depende do XML fiscal retornado pelo servidor. Preservar a configuração, os certificados, o CSC, os dados locais e o banco existentes. Ainda é necessário testar em homologação com a configuração da empresa e medir/ler o QR na impressora real. Isso não certifica o enquadramento fiscal de cada mercadoria: os 345 protocolos fornecidos não contêm os itens das notas, e a base de normas estaduais de benefícios/ST/FECP não foi ampliada para uma auditoria tributária integral. Nenhuma tributação de produto foi alterada automaticamente.

## Conclusão da análise anterior

O projeto está no caminho certo na emissão online de NFC-e, na guarda do XML, na escolha dos grupos fiscais pelo regime da empresa e na revisão de produtos. Entretanto, **a implementação atual ainda tem falhas concretas e não pode ser considerada integralmente conforme**. As prioridades são o Gateway fiscal offline, reconhecimento de autorização com alerta, pagamentos com troco, fidelidade do DANFE ao XML e vigência das referências de CEST da ferramenta de IA.

Esta revisão compara o código do projeto com documentos oficiais e amostras fornecidas. Não comprova a versão publicada na loja, não valida o credenciamento/certificado/CSC reais e não altera produtos, notas emitidas, banco de produção ou regras fiscais do programa.

## Documentos e alcance

- Manual fornecido: [DANFE NFC-e e QR Code, versão 5.0, fevereiro/2018](C:/Users/USUARIO/Downloads/manual_de_especificacoes_tecnicas_do_danfe_nfc-e_qr_code-versao-5-0.pdf), 37 páginas. Leitura do texto e inspeção visual das páginas relevantes, incluindo itens, contingência e dimensões.
- O portal oficial já lista [manual versão 6.0, março/2025](https://www.nfe.fazenda.gov.br/portal/exibirArquivo.aspx?conteudo=k%2FIuuaW4YiY%3D). Também foi examinado. A [NT 2025.001 v1.02](https://www.nfe.fazenda.gov.br/Portal/exibirArquivo.aspx?conteudo=3NLMgy80wTE%3D), regra ZX02-220, trata a aceitação do QR Code 3 por UF e mantém regras para versões 2 e 3. A existência da versão 3 não prova, por si, que o QR Code 2 seja inválido para esta empresa com CNPJ no RJ.
- [Índice CONFAZ 2026](https://www.confaz.fazenda.gov.br/legislacao/convenios/2026/2026): triagem das ementas de 114 convênios e leitura aprofundada de atos pertinentes. A triagem não equivale à auditoria integral de todos os atos e respectivos anexos. O endereço é do CONFAZ; a aplicação no RJ também exige conferir a legislação estadual, vigência e condições do benefício.
- ZIP de outubro anteriormente fornecido: 345 arquivos, todos protocolos de autorização `retEnviNFe`, com status 100, modelo 65 e UF 33. Não contêm `NFe/infNFe`, itens nem assinatura. Comprovam o conteúdo dos protocolos apresentados, mas não permitem conferir NCM, tributos, QR Code ou assinatura das notas. Também não comprovam ausência de cancelamento posterior.

## Achados prioritários

| Prioridade | Parte | Resultado |
| --- | --- | --- |
| Alta | Autorização com alerta | Código 120 da NT 2026.002 não é reconhecido como autorizado; arquivamento também o rejeita |
| Crítica | Gateway offline | QR Code de contingência incompleto; XML e dados fiscais locais precisam ser corrigidos e validados |
| Alta | Venda com troco | Valor aplicado à venda é usado como valor pago no XML, apesar do troco separado |
| Alta | DANFE | Tributos fixos em 31,45%, unidade fixa UN e valores/datas reconstruídos do recibo |
| Alta | IA fiscal / CEST | Referência inclui códigos e redações com efeitos futuros, sem controle de vigência |
| Alta | CEST em branco | Formato é conferido, mas ausência ou limpeza não é confrontada com a obrigação aplicável à mercadoria |
| Média | Impressão em contingência | Aviso obrigatório não é repetido nos dois locais exigidos |
| Média | Entrega do documento | Fallback para cupom não fiscal continua possível quando nenhum documento fiscal está disponível |
| Acompanhar | Atualizações de 2026/2027 | Convênios, Ajustes SINIEF e regras do Simples precisam de revisão por data e operação |

### 1. Gateway offline: QR Code e dados fiscais

O manual fornecido, seção 4.2.2, página 22, exige oito componentes no QR Code 2 de contingência: chave, versão, ambiente, dia de emissão, valor da nota, digest em hexadecimal, identificador do CSC e hash. O método local gera cinco componentes e calcula o hash com outra sequência. Além disso, decodifica o digest Base64 antes da conversão, em vez de produzir o hexadecimal de 56 caracteres ilustrado pelo manual. O resultado não corresponde ao leiaute offline documentado.

Evidência: [LocalFiscalSigner.cs](E:/PROJETOPDV/horus_pdv/GATEWAY/src/HorusGateway/Services/LocalFiscalSigner.cs:317). O mesmo arquivo acrescenta `infNFeSupl` depois de `Signature`, usa endereço de emitente fictício e fixa origem 0, CSOSN 102 e PIS/COFINS 07. [Montagem do XML local](E:/PROJETOPDV/horus_pdv/GATEWAY/src/HorusGateway/Services/LocalFiscalSigner.cs:185). Esses valores não representam automaticamente o cadastro real ou o regime de cada produto.

Correção necessária: transportar os dados reais do emitente e dos produtos ao Gateway, compartilhar as regras fiscais suportadas com a API e validar XML, assinatura, QR Code e posterior transmissão. Um teste que apenas verifica a presença de tags ou a assinatura não comprova conformidade do documento.

O probe isolado confirmou dois erros independentes: o schema rejeitou primeiro a ordem de `infNFeSupl`; reordenando somente a amostra fictícia, rejeitou o formato do QR offline. A assinatura criptográfica era válida, demonstrando que assinatura válida não basta para validar o documento.

### 2. Pagamentos com troco

No caixa, `amount` representa a parte da venda quitada; `cashGiven` representa o dinheiro recebido. O montador fiscal usa `p.Amount` como `vPag`, enquanto também envia o troco.

Exemplo determinístico: venda de R$ 7,45, recebimento de R$ 10,00 e troco de R$ 2,55. O fluxo atual produz pagamento fiscal de R$ 7,45 e troco de R$ 2,55; a diferença entre pagamentos e total da nota seria zero. Para esse cenário, o pagamento fiscal deve representar R$ 10,00. A regra YA09-10 exige que o troco corresponda ao somatório dos pagamentos menos o total da nota, sob rejeição 869. [NT 2016.002, regra do troco](https://www.nfe.fazenda.gov.br/portal/exibirArquivo.aspx?conteudo=mqf2EDdt9Ug%3D).

Evidência: [pagamentos do documento fiscal](E:/PROJETOPDV/horus_pdv/API/NETCORE/Repositories/DatabaseAccess/DocumentoFiscalAB.cs:332), [pagamento no caixa](E:/PROJETOPDV/horus_pdv/FRONTEND/src/pages/Admin/SalesStartPage.tsx:1301) e [serialização NFC-e](E:/PROJETOPDV/horus_pdv/API/NETCORE/Services/Fiscal/ZeusFiscalProvider.cs:1110). Isso demonstra uma incompatibilidade no cenário com troco; não prova que as 345 notas do ZIP tenham esse problema, pois faltam os XMLs completos.

### 3. DANFE deve representar o XML

O manual fornecido, página 6, estabelece a correspondência entre documento impresso e XML. O HTML atual utiliza dados do recibo e da empresa carregada na tela, em vez de um retrato do documento fiscal efetivamente emitido.

Falhas comprovadas por inspeção e fixture local, sem impressão em produção:

- `subtotal * 0.3145` gera tributos aproximados fixos para qualquer produto. Isso não substitui informação fundamentada por mercadoria, origem e fonte aplicável. [Cálculo fixo](E:/PROJETOPDV/horus_pdv/FRONTEND/src/utils/danfePrint.ts:136).
- A unidade impressa é sempre `UN`, embora o XML online use a unidade cadastrada. [Item impresso](E:/PROJETOPDV/horus_pdv/FRONTEND/src/utils/danfePrint.ts:120).
- Produto de 0,5 unidade a R$ 20,00, com desconto de R$ 2,00, aparece com total líquido de R$ 8,00, sem a linha de desconto ou valor a pagar. O XML discrimina valor bruto e desconto. Manual fornecido, páginas 8 e 9. [Totais](E:/PROJETOPDV/horus_pdv/FRONTEND/src/utils/danfePrint.ts:330).
- A data de emissão vem do relógio do cliente, e a autorização pode usar a mesma data como fallback quando a data oficial falta. [Data de autorização](E:/PROJETOPDV/horus_pdv/FRONTEND/src/utils/danfePrint.ts:134), [recibo](E:/PROJETOPDV/horus_pdv/FRONTEND/src/pages/Admin/SalesStartPage.tsx:1661).
- Um documento de 14 dígitos é rotulado `CPF`. O rótulo precisa acompanhar o documento fiscal e o modelo aplicável. [Consumidor](E:/PROJETOPDV/horus_pdv/FRONTEND/src/utils/danfePrint.ts:417).
- Se faltar a URL oficial do QR Code, o HTML inventa `?p=chave`, sem os demais parâmetros exigidos. A chave pode servir para consulta manual, mas essa URL não deve ser apresentada como QR Code fiscal válido. [Fallback](E:/PROJETOPDV/horus_pdv/FRONTEND/src/utils/danfePrint.ts:125).

Correção necessária: obter os campos de impressão do XML original e protocolo. Não inventar tributos, datas ou QR Code. A mudança deve preservar a impressão automática pela impressora padrão.

### 4. Contingência, papel e recibo

O manual fornecido, página 12, exige o aviso de contingência abaixo do cabeçalho e abaixo da identificação da NFC-e. O HTML atual contém apenas uma ocorrência completa. [Aviso](E:/PROJETOPDV/horus_pdv/FRONTEND/src/utils/danfePrint.ts:306).

A bobina de 80 mm e margens laterais de 3 mm atendem nominalmente ao mínimo documentado. O QR Code impresso usa nível M e 140 px, cerca de 37 mm a 100%. Entretanto, a dimensão física, área de segurança e legibilidade devem ser medidas na impressora real, pois o driver define o papel e pode ajustar a escala. Essa revisão não incluiu teste físico de durabilidade do papel ou leitura em celular.

O caixa aguarda brevemente o documento fiscal, tenta contingência e, se não receber um documento, imprime **cupom não fiscal**. [Fluxo](E:/PROJETOPDV/horus_pdv/FRONTEND/src/pages/Admin/SalesStartPage.tsx:1478), [impressão](E:/PROJETOPDV/horus_pdv/FRONTEND/src/pages/Admin/SalesStartPage.tsx:1723). O cupom pode servir como comprovante da venda, mas não encerra a obrigação de gerar e disponibilizar a NFC-e quando devida. Portanto, não é correto afirmar que todas as vendas já entregam DANFE automaticamente.

### 5. CEST: vigência futura incorporada antecipadamente

O [Convênio ICMS 107/26](https://www.confaz.fazenda.gov.br/legislacao/convenios/2026/CV107_26) altera o anexo de eletrodomésticos do Convênio 142/18 com efeitos em **01/11/2026**. A base local já contém novos CEST 2104601, 2104701 e 2105101 e redações alteradas de códigos anteriores, sem início ou fim de vigência.

A extração da página consolidada eliminou textos anteriores destacados em verde, mesmo quando ainda eram aplicáveis em 10/10/2026. Essa é uma falha na referência que incluí na nova ferramenta de IA. [Base](E:/PROJETOPDV/horus_pdv/API/NETCORE/DataBase/FiscalTables/cest.json), [validação](E:/PROJETOPDV/horus_pdv/API/NETCORE/Services/Fiscal/FiscalAiRules.cs:54).

Correção necessária: conservar versões das descrições e códigos com vigência e filtrar pela data da operação. Jev e revisão humana não corrigem uma referência temporalmente incorreta. O exemplo comprova o problema da base; não demonstra que a loja venda esses eletrodomésticos ou tenha emitido nota incorreta com eles.

### 6. CEST não depende apenas de haver ST na venda

O [Convênio 142/18](https://www.confaz.fazenda.gov.br/legislacao/convenios/2018/CV142_18), cláusula vigésima, exige informar CEST para mercadorias relacionadas nos anexos pertinentes, mesmo quando a operação não estiver sujeita à ST. A cláusula terceira inclui optantes pelo Simples.

Hoje a conferência de produto verifica o formato de CEST preenchido, e a aplicação de IA permite limpar esse campo sem demonstrar que a mercadoria esteja fora da lista. [Conferência](E:/PROJETOPDV/horus_pdv/API/NETCORE/Services/Fiscal/ProductFiscalReview.cs:36), [validação da correção](E:/PROJETOPDV/horus_pdv/API/NETCORE/Services/Fiscal/FiscalAiRules.cs:54).

Correção necessária: apontar ausência como pendência quando NCM, descrição e embalagem identifiquem mercadoria abrangida; bloquear limpeza sem fundamento. CEST existente e prefixo de NCM compatível também não provam, sozinhos, o enquadramento da mercadoria.

### 7. Autorização com alerta: código 120

A [NT 2026.002 v1.11](https://dfe-portal.svrs.rs.gov.br/NFE/DownloadArquivoEstatico/?sistema=NFE&tipoArquivo=3&nomeArquivo=NT_2026.002_v1.11_DANFE_Simpl_Tp2_Emi_Offline_Autoriz_Alerta.pdf), páginas 3 a 6, prevê `cStat=120` para autorização com alerta, sem exigir retransmissão para solucionar a mensagem. O cronograma da estrutura de alertas e da regra 5E17-65 indica produção em 05/10/2026. O adiamento para 14/12/2026 na mesma versão refere-se a outras regras especificadas, não a todos os itens da NT.

O emissor aceita como autorização apenas 100 e 150 em emissão normal, transmissão de contingência e consulta. [Tratamento do retorno](E:/PROJETOPDV/horus_pdv/API/NETCORE/Services/Fiscal/ZeusFiscalProvider.cs:135). A [guarda do XML](E:/PROJETOPDV/horus_pdv/API/NETCORE/Services/Fiscal/FiscalXmlArchive.cs:44) também recusa 120. Assim, uma autorização com alerta pode ser classificada como rejeitada e ter a exportação impedida. Não há retorno 120 no ZIP analisado; o achado é incompatibilidade do código com o retorno agora previsto.

Correção necessária: reconhecer 120 como autorizado em todos os caminhos, preservar protocolo e mensagens `cMsg/xMsg`, arquivar e exportar o documento e exibir alertas ao responsável na administração. Isso não exige recolocar o status fiscal na tela da operadora. Tratar alerta como motivo para emitir outra nota seria inadequado.

### 8. Convênios e Ajustes SINIEF: aplicar por produto, operação, UF e data

Entre os atos examinados, o Convênio 107/26 é diretamente relevante à manutenção da referência de CEST. Benefícios para produtos específicos também merecem triagem, como o [Convênio 13/26, preservativos](https://www.confaz.fazenda.gov.br/legislacao/convenios/2026/CV013_26). A aplicação na empresa depende da operação, do regime e da norma estadual; não se pode transformar uma ementa em isenção automática.

Exemplo concreto de adoção estadual: a [ALERJ informa que a Lei RJ 11.243/26 internalizou os Convênios 10, 11, 13, 19, 20 e 21/26](https://www.alerj.rj.gov.br/Visualizar/Noticia/86111), com efeitos indicados a partir de 01/05/2026. Isso demonstra por que a leitura deve incluir o RJ, não apenas a lista nacional.

A ferramenta de IA recebe referências de NCM, CEST, cClassTrib e duas orientações federais, mas não recebe uma base completa das regras estaduais de ICMS/ST/FECP do RJ, das condições de benefícios ou do recolhimento anterior. [Fontes fornecidas à IA](E:/PROJETOPDV/horus_pdv/API/NETCORE/Services/Fiscal/FiscalAiRules.cs:80). O bloqueio de aplicação de CFOP, origem, ICMS e PIS/COFINS é adequado para esse alcance. [Proteção](E:/PROJETOPDV/horus_pdv/API/NETCORE/Services/Fiscal/FiscalAiService.cs:82). Jev verifica sustentação nas referências recebidas; não supre normas ausentes nem equivale a uma validação perante a SEFAZ. Para ampliar a ferramenta, a base deve registrar UF, vigência, regime, operação, condições e fonte estadual.

Também é necessário conferir Ajustes SINIEF, que não estão dentro do índice de convênios apresentado. O [Ajuste 12/26](https://www.confaz.fazenda.gov.br/legislacao/ajustes/2026/AJ012_26) revogou o Ajuste 11/25; não se deve usar esse texto revogado para concluir que todo destinatário CNPJ obrigatoriamente exige modelo 55. A opção de DANFE Simplificado Tipo 2 e os alertas recentes são assuntos separados. A escolha do modelo deve seguir a operação e a legislação atual, sem migrar automaticamente todo o caixa para NF-e.

### 9. Recuperação de timeout: risco de duplicidade a testar

Na emissão normal, cada tentativa recompõe código numérico e data; certos resultados de timeout não preservam XML e chave. O worker pode renumerar após retorno 539. Se uma primeira transmissão for autorizada e apenas sua resposta se perder, há risco de outra nota para a mesma venda. Essa é uma conclusão sobre o fluxo, não uma duplicidade comprovada na loja.

Evidência: [retorno após falha de transmissão](E:/PROJETOPDV/horus_pdv/API/NETCORE/Services/Fiscal/ZeusFiscalProvider.cs:197), [geração de código numérico](E:/PROJETOPDV/horus_pdv/API/NETCORE/Services/Fiscal/ZeusFiscalProvider.cs:1317) e [renumeração da emissão normal](E:/PROJETOPDV/horus_pdv/API/NETCORE/Services/Fiscal/NfceOutboxWorker.cs:128). O worker já preserva a chave da contingência nesse retorno; o risco apontado refere-se à emissão normal.

Critério de correção: persistir XML/chave antes do envio, consultar o resultado da nota original depois de um retorno incerto e só tomar outra ação com resultado confirmado. O teste precisa simular autorização com perda da resposta e provar ausência de nova nota e de nova cobrança.

## O que está no caminho certo

- A API emite modelo 65, XML 4.00, usa certificado, distingue autorização/rejeição/contingência e guarda XML assinado e protocolo. A exportação corrigida compõe `nfeProc` quando os originais necessários existem.
- Os grupos ICMS usam CRT; códigos sem cálculo implementado geram pendência explícita. PIS/COFINS tributáveis não são substituídos automaticamente por grupos zerados.
- Simples Nacional em 2026 não recebe alíquotas do regime regular de IBS/CBS. A orientação é consistente com o [comunicado oficial do CGIBS](https://cgibs.gov.br/reforma-tributaria-comeca-em-2026-com-periodo-de-adaptacao-destaque-informativo-dos-novos-tributos-e-dispensa-de-penalidades). Isso não dispensa ICMS/PIS/COFINS e demais regras aplicáveis.
- A IA fornece referências, comparação de valores, revisão explícita, isolamento por empresa e auditoria. CFOP, origem, ICMS e PIS/COFINS sem fundamento operacional suficiente ficam disponíveis para consulta e bloqueados para aplicação. O controle é adequado, mas sua base de CEST precisa do ajuste de vigência descrito acima.

## Validação realizada e próximos critérios de aceite

Os 36 testes locais de `FiscalReviewChecks` passaram, incluindo cálculos suportados, vigência NCM/classificação, composição do XML completo e validação de uma NFC-e assinada em schema local. Não houve transmissão para SEFAZ, uso de certificado real ou acesso ao banco da loja. Esse resultado não cobre as falhas de impressão, troco e Gateway descritas neste relatório.

Os oito testes existentes do Gateway também passaram, mas não verificam schema e a sequência oficial dos parâmetros offline. Probes adicionais com certificado efêmero reproduziram as duas reprovações do Gateway e o conflito aritmético de troco. O XML da API com QR online de cinco parâmetros e QR offline de oito passou nos schemas locais. Nenhum desses certificados de teste é o certificado ICP-Brasil da loja.

Para encerrar a adequação, os critérios são: mesma mercadoria e tributação online/offline; XML local válido no schema e assinado; QR Code verificável segundo exemplos oficiais; autorização 120 reconhecida e arquivada com seus alertas; pagamento e troco coerentes; DANFE fiel ao XML inclusive desconto/unidade/datas; duas marcações de contingência; CEST escolhido pela data; transmissão posterior da nota original e recuperação de resposta perdida sem duplicar a venda; exportação completa de outubro para conferir os produtos efetivamente emitidos.

A versão corrigida deve passar primeiro por testes isolados e homologação, preservando documentos antigos. Em 2027, a regra de IBS/CBS do Simples precisa ser revisada conforme opção da empresa e cronograma oficial; não reutilizar automaticamente as regras de transição de 2026.
