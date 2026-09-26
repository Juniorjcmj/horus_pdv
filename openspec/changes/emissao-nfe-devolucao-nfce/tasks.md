## 1. Backend: Modelos e Provedor Fiscal Zeus

- [x] 1.1 Estender `EmissaoNfeRequest` e DTOs fiscais com campos para tipo de operação (`tnEntrada`/`tnSaida`), finalidade (`fnNormal`/`fnDevolucao`), lista de chaves de acesso referenciadas (`NFref`), e suporte ao destinatário/remetente híbrido (cliente ou dados da própria empresa)
- [x] 1.2 Atualizar `ZeusFiscalProvider.cs` para suportar `ide.tpNF = TipoNFe.tnEntrada`, `ide.finNFe = FinalidadeNFe.fnDevolucao`, inclusão do nó `<NFref>` com a chave de 44 dígitos da NFC-e original e conversão automática de CFOP de saída para devolução total (`5.102` -> `1.202`, `5.405` -> `1.411`)
- [x] 1.3 Implementar método no `IFiscalProvider` e `ZeusFiscalProvider` para transmissão síncrona de NF-e Modelo 55 retornando chave, protocolo de homologação SEFAZ e XML autorizado

## 2. Backend: Repositório e Endpoints de Devolução Supervisionada

- [x] 2.1 Criar consulta em `DocumentoFiscalAB.cs` para recuperar os itens e totais da NFC-e autorizada original e verificar se já não existe devolução registrada para a mesma
- [x] 2.2 Criar método transacional em `DocumentoFiscalAB.cs` para persistir a NF-e Modelo 55 autorizada, estornar o saldo em estoque dos itens em `Produtos`, atualizar a venda e gravar log de auditoria com dados do supervisor autorizador
- [x] 2.3 Implementar endpoint `POST /api/nfe/devolver-nfce/{nfceId}` no `NfeController.cs` com validação de credenciais do supervisor via `HorusSecurityStore`, justificativa mínima de 15 caracteres e regra híbrida de destinatário

## 3. Frontend: Integração e Suporte Supervisionado no PDV

- [x] 3.1 Adicionar método `emitirDevolucaoNfce` e tipagens em `fiscalService.ts`
- [x] 3.2 Implementar alerta visual preventivo no `PdvNfceCancelModal.tsx` quando a NFC-e tiver sido emitida há mais de 30 minutos
- [x] 3.3 Adicionar no `PdvNfceCancelModal.tsx` tratamento da Rejeição SEFAZ por prazo de cancelamento expirado com fluxo de emissão de NF-e de Devolução (supervisor + senha + campos opcionais de CPF/Nome do cliente)
- [x] 3.4 Implementar comprovante térmico de devolução para assinatura do cliente no caixa + botão para abrir/imprimir o DANFE padrão A4 em PDF

## 4. Validação e Fechamento

- [x] 4.1 Validar compilação do backend C# com `dotnet build`
- [x] 4.2 Validar integridade e compilação do frontend TypeScript com `npm run build`
- [x] 4.3 Atualizar grafo de conhecimento do projeto com `graphify update .`
