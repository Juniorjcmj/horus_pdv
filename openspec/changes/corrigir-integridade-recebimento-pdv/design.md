## Context

Ver `proposal.md` para motivação e `specs/` para contratos observáveis. A investigação prévia do principal identificou os pontos abaixo; linhas são referências aproximadas do checkout auditado, não prova sobre a versão instalada na loja.

| Área | Evidência e consequência |
| --- | --- |
| `FRONTEND/src/pages/Admin/SalesHistoryPage.tsx:435,449` | `parseMoneyBr(... ) || unitPrice * quantity` substitui zero válido pelo bruto. Reproduzido com item gratuito R$ 5,14: soma impressa R$ 87,62 contra total R$ 82,48. |
| Mesma página, 474–477 | Reimpressão fixa recebido no total e troco em zero. |
| `FRONTEND/src/pages/Admin/SalesStartPage.tsx:504–531,1060,1146–1173` | Carrinho deriva de `applyPromotions`; remoção filtra `rawCart`; reabertura limpa pagamentos. Motor real removeu R$ 5,14 de R$ 87,62 corretamente. Não há bug de remoção comprovado. |
| Mesma página, 1217,1323,1342,1421–1422,1610–1614; `fiscalService.ts:404–413` | Troco com `Math.max` e somas sem normalização seguem para JSON. `10 - 8.80` produz `1.1999999999999993`. |
| `GATEWAY/src/HorusGateway/Services/LocalFiscalSigner.cs:119–120` | Rejeita dinheiro com precisão excedente e pagamentos menos troco diferentes do total. Manter rigor. |
| `API/NETCORE/Repositories/DatabaseAccess/HistoricoVendasAB.cs:205–219,535–551,609` | Valida pagamentos com tolerância de um centavo, não concilia itens com cabeçalho; grava recebido/troco sem invariantes; recalcula itens na inserção. |
| Mesma classe, 224,291,398,444+ | `FirstOrDefault` considera apenas primeira parcela fiado na venda e no pedido. |
| `FRONTEND/src/application/sales/SaleOutboxAdapter.ts:72+` | Enfileira cabeçalho, itens e pagamentos sem contrato de integridade. |
| `API/NETCORE/Services/Fiscal/FiscalPaymentRules.cs` e `DocumentoFiscalAB` | Commit `62b1a94`, de 10/10, já declara recebido bruto (`cashGiven` ou `amount + change`) e valida. Preservar essa correção, alinhar centavos e adicionar regressões; não apresentá-la como ausente. |

Cupom 1245 de 09/10/2026: fiado R$ 82,48, itens impressos R$ 87,62. NFC-e 354, venda 816, de 07/10: relato de rejeição por troco. A diferença do cupom coincide com a reprodução, mas banco, XML original e versão deploy não foram obtidos; nenhuma causa histórica é afirmada.

Baseline informado pelo principal: build da API com `--no-restore` passou sem avisos; `LocalFiscalComplianceTests` passou 14/14; `API/tests/FiscalReviewChecks` falha CS1705 por Hosting.Abstractions 8 versus 10. Não houve implementação nesta proposta.

## Goals / Non-Goals

**Goals:** uma representação canônica por venda, validação independente no servidor e equivalência entre impressão, fila e fiscal. Permitir implementação e validação completas usando fixtures e ambientes locais, sem depender de acesso à loja.

**Non-Goals:** alterar regras comerciais de promoção, promover migração ampla de runtime, recalcular registros históricos, emitir/cancelar notas existentes, operar produção, redesenhar o checkout ou implementar reconciliação financeira geral. Venda integralmente gratuita mantém a política de elegibilidade existente; esta change permite item gratuito, sem introduzir autorização nova para venda total zero.

## Decisions

### 1. Centavos como fronteira canônica

No frontend, usar funções monetárias compartilhadas com cálculo decimal determinístico e representação inteira de centavos para valores liquidados, parcelas, saldos e trocos. Quantidade e preço unitário podem conservar precisão prevista no domínio. Arredondar bruto do item e descontos monetários para centavos, obter líquido canônico e somar os líquidos já arredondados. Meio centavo arredonda para longe de zero; no .NET, explicitar `MidpointRounding.AwayFromZero` e usar `decimal`. Não usar epsilon genérico para aceitar divergência de R$ 0,01. Conversão decimal deve tratar corretamente casos como 1.005, não depender apenas de `Math.round(x * 100)` sobre intermediário binário.

Payloads existentes continuam com valores monetários decimais em reais, mas são gerados a partir de centavos; não mudar contratos públicos para inteiros. Artefatos de ponto flutuante são removidos na origem e em adaptadores de entrada controlados. API normaliza escala e compara centavos derivados, rejeitando divergências reais; Gateway mantém exigência de escala e igualdade antes de assinar. Alternativa rejeitada: tolerância maior no Gateway, que ocultaria dados incorretos.

Invariantes: `sum(item.netCents) == totalCents == sum(payment.amountCents)`; para dinheiro, `cashGivenCents == amountCents + changeCents`; recebido suficiente, troco não negativo; demais meios sem troco. Nenhum NaN/infinito ou desconto acima do bruto. Parcela zero é desnecessária em venda positiva e deve ser rejeitada, não descartada silenciosamente.

### 2. Validar snapshot antes de qualquer efeito

Construir um snapshot imutável do fechamento com itens, descontos, total, pagamentos e identidade da operação. Reutilizá-lo para persistência, impressão e payload fiscal. Conferir no frontend antes de enfileirar ou enviar; repetir na API em venda direta, pedido e sync antes de escrita. A API deve persistir os mesmos valores validados, eliminando diferença entre cálculo de validação e `InserirVendaAsync`.

Usar transação existente para venda, itens, estoque, caixa, saldo e movimento fiado; checagem de limite e atualização do saldo devem ser protegidas da concorrência por estratégia transacional já suportada. Preservar chave de idempotência da venda offline/online, sem criar nova identidade em retry. Falha de validação é erro de domínio descritivo, não retry automático infinito nem remoção da fila. Alternativa rejeitada: confiar apenas na UI, pois clientes legados e filas antigas a contornam.

Compatibilidade: ausência da coleção de pagamentos segue fallback existente de parcela única, submetida aos mesmos invariantes. Ausência de recebido/troco no contrato legado não significa conhecimento histórico desses valores; quando necessário ao novo fechamento, aplicar semântica existente de pagamento exato com ausência explícita de informação preservada para impressão. Não preencher retrospectivamente vendas existentes. Campos opcionais no DTO de leitura distinguem ausência de zero.

### 3. Invalidar pagamentos quando o conteúdo financeiro mudar

Recalcular promoções ao alterar carrinho. Comparar revisão/conteúdo financeiro de itens, descontos e total contra snapshot do pagamento. Mudança durante checkout (incluindo polling de promoções a cada 60 segundos e focus) invalida a confirmação das parcelas, informa atualização e exige nova conferência. Manter valores anteriormente informados como referência para reconferência; não apagar silenciosamente evidências de recebimentos efetivados nem estornar ou cobrar novamente automaticamente. Mudança sem efeito financeiro não limpa pagamentos. Proteger também o clique de confirmação contra snapshot vencido; após iniciar submissão, usar snapshot estável e impedir segunda submissão. Isso evita pagar total antigo sem congelar promoções contra a regra atual de vigência.

### 4. Agregar fiado integralmente

Somar todas as parcelas fiado em centavos em todos os caminhos e usar o agregado tanto para limite quanto para um único débito e auditoria. Meios não fiado ficam fora dessa soma. Exigir cliente. Por decisão expressa do usuário, limite zero bloqueia qualquer nova compra com parcela fiado, inclusive em pagamento misto, venda direta, pedido e offline. Essa decisão substitui o cenário antigo de limite zero ilimitado da spec principal; o delta de substituição (REMOVED/ADDED) desta change registra a substituição para a futura sincronização. O bloqueio deve ser explícito, independentemente do saldo devedor; compras integralmente em outros meios continuam sujeitas às suas regras normais. Alternativas rejeitadas: proibir parcelas fiado repetidas (quebraria contrato de split) ou criar débito por parcela (aumentaria superfície de duplicação).

O crédito disponível é `max(0, limite - saldoDevedor)`. Conferir o agregado fiado contra esse disponível em toda operação, incluindo dívida local pendente ainda não refletida no saldo, sem dupla contagem. Para compra totalmente fiado, comparar o total integral. Revalidar após mudanças no carrinho/pagamentos e imediatamente antes de confirmar; no servidor, conferir saldo vigente dentro da mesma transação do débito. Nunca presumir liberação por dados ausentes. Aceites obrigatórios: disponível40/compra100 bloqueia e informa insuficiência60; disponível40/fiado40 permite; disponível40/fiado40,01 bloqueia. Em pagamento misto, preservar o contrato de comparar o valor efetivamente financiado, somando todas as parcelas fiado.

### 5. Impressão usa fatos registrados

Auditar DTO/endpoint de impressão e fonte local: propagar detalhes de pagamentos existentes até o renderer. Trocar fallback por verificação explícita de ausência/valor inválido, preservando zero. Se líquido estiver ausente, calcular somente com preço, quantidade e desconto suficientes; não reaplicar promoções atuais. Omitir recebido/troco não informados ou rotular ausência. Se os dados históricos divergem, mostrar indicação de inconsistência sem ajustar total/itens para forçar igualdade. Alternativa rejeitada: imprimir bruto no lugar de zero ou reconstruir recebido pelo total.

### 6. Testar contrato e efeitos, não somente helpers

Fixtures devem passar por construção real do payload, serialização JSON e validação cloud/Gateway. Cobrir persistência/outbox e atomicidade com repositório ou banco de teste apropriado, além de helpers. Compatibilizar minimamente `FiscalReviewChecks` ou criar suíte isolada no runtime compatível com a API, registrando decisão; não expandir para migração geral.

| Aceite | Evidência mínima |
| --- | --- |
| Precisão | 8,80/10,00 resulta em 1,20 após JSON e passa validadores; erro de 0,01 falha; midpoint 1,005 tem resultado 1,01. |
| Itens | 0,125 × 10,00 = 1,25; item 5,14 totalmente descontado permanece zero; soma 82,48 permanece igual em tela, gravação e cupom. |
| Checkout | Adicionar/remover item e perder combo, voltar do pagamento e atualização de promoções nunca enviam parcelas/snapshot vencidos. |
| Persistência | Divergência entre itens/cabeçalho/parcelas e dinheiro incoerente não produz efeitos online, pedido ou outbox; sync antigo mantém erro rastreável. |
| Fiado | Parcelas 30 + 50 geram débito 80 uma vez; saldo 440/limite500 rejeita; limite zero bloqueia qualquer parcela fiado, inclusive misto e offline; retries e concorrência não duplicam nem excedem limite. |
| Fiscal | PIX50 + dinheiro líquido50/recebido100/troco50 declara150 e deduz50, cloud e local; não regredir `62b1a94`. |
| Impressão | Dados disponíveis preservados; desconhecidos não fabricados; divergência histórica indicada e nenhuma gravação decorrente. |

## Risks / Trade-offs

- [Filas antigas inconsistentes passam a falhar explicitamente] → preservar conteúdo e identidade com erro acionável; tratamento operacional posterior, sem autoajuste.
- [Arredondamentos anteriores diferentes] → fixtures de midpoint, descontos distribuídos, combos e quantidades fracionadas; reutilizar algoritmo comercial de distribuição e garantir que resto de centavos tenha destino determinístico.
- [Mudança de promoção surpreende operador] → mensagem clara e reconferência somente quando composição financeira mudar.
- [Contratos de leitura insuficientes] → ampliar campos opcionalmente e testar ausência; não inventar dados legados.
- [Dependências de testes incompatíveis] → ajuste mínimo do harness ou suíte isolada; documentar comandos e resultados reproduzíveis.
- [Trabalho concorrente em Gateway e graphify] → preservar changes existentes e arquivos sujos; após implementação, atualizar grafo conforme AGENTS.md.

## Migration Plan

Implementação deve ser revisada e validada localmente antes de qualquer publicação. Não se prevê migração corretiva de dados; extensões de leitura devem ser aditivas. Entregar UI normalizando valores e servidor validando contrato como pacote coordenado, testando cliente legado e fila existente. Rollback do código segue release anterior, preservando vendas e filas; não reverter registros nem XMLs para acompanhar versão. Publicação não está autorizada por esta proposta.

Checklist operacional separado, não bloqueante do apply: obter versão instalada da loja e presença de `62b1a94`; consultar venda/cupom1245 e venda816/NFC-e354, itens, descontos e pagamentos; obter XML original e resposta de rejeição; comparar com fixtures e registrar causa somente com evidência. Essa consulta depende de acesso autorizado e não inclui correção, reemissão ou cancelamento.

## Open Questions

- Qual era a versão efetivamente instalada nas datas dos incidentes e quais são os valores originais no banco/XML? Pendência diagnóstica operacional; não altera o contrato nem impede finalizar código/testes.
- O endpoint de impressão atual já expõe todos os detalhes necessários? O apply deverá verificar e estender opcionalmente o DTO apenas se necessário, conforme decisão 5.
