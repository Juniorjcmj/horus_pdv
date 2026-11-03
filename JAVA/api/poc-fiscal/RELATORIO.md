# Relatório da prova de conceito fiscal (NFC-e) — biblioteca Java_NFe

Biblioteca avaliada: `br.com.swconsultoria:java-nfe` **4.00.51** (a mais recente da linha 4.00; a 4.3.0 usa outro pacote de
esquemas e não foi avaliada), com `java_certificado` 3.14. Critérios: spec `fiscal/biblioteca-java-nfe`.
Projeto: `JAVA/api/poc-fiscal` (Maven separado; não é módulo da API). Venda de exemplo: `VendaExemplo.java`, espelhando o
`ZeusFiscalProvider.cs` (emitente Simples Nacional, 2 itens com NCM/CFOP/CSOSN/PIS/COFINS, desconto, consumidor sem CPF,
dinheiro com troco + PIX, IBS/CBS por item e no total).

## Como rodar

```bash
cd JAVA/api/poc-fiscal
mvn -B test        # 4 testes offline: assinatura + XSD (certificado autoassinado, em memória)
mvn -B -q exec:java   # idem, e o ciclo na SEFAZ se POC_* estiver definido
```

Variáveis para a parte da SEFAZ (certificado A1 e CSC de **homologação**): `POC_CERT_PFX_BASE64`, `POC_CERT_SENHA`,
`POC_CSC_ID`, `POC_CSC_TOKEN`, `POC_UF`, `POC_CNPJ` (o CNPJ do certificado). `POC_SCHEMAS` aponta a pasta de XSD (padrão: a
mesma `API/NETCORE/DataBase/Schemas` que a API .NET usa).

## Resultado por critério

Legenda: **ATENDIDO** (com evidência executada), **PENDENTE** (código pronto, falta certificado/CSC de homologação),
**NÃO ATENDIDO**.

| # | Critério (spec) | Situação | Evidência | Observação / recomendação |
| --- | --- | --- | --- | --- |
| 1 | NFC-e 4.00 válida: montar, assinar, QR Code (CSC) e validar nos XSD | **ATENDIDO** | `NfceOfflineTest.nfceNormalAssinadaComQrCodeValidaNosXsd`: o lote `enviNFe` assinado, com `infNFeSupl` (`qrCode` e `urlChave`), passa na validação da própria biblioteca (`Nfe.montaNfe(..., true)`) **e** na validação direta contra `enviNFe_v4.00.xsd` (`NotaFiscal.validarNosXsd`). Dois itens, desconto, dinheiro + PIX, consumidor sem CPF. | A biblioteca **não traz os XSD** no jar: precisa da pasta de esquemas. Usar a mesma pasta já versionada para a API .NET (257 arquivos, origem ZeusFiscal) evita divergência. O QR Code não é montado sozinho: `NFCeUtil.getCodeQRCode` (e `getCodeQRCodeContingencia`) são chamados à mão, com a URL de `WebServiceUtil.getUrl(..., URL_QRCODE)` por UF. |
| 2 | Reforma tributária: grupos IBS/CBS com CST e `cClassTrib` do cadastro | **ATENDIDO** | `NfceOfflineTest.itemTemOGrupoIbsCbsComCstECClassTrib`: o XML do item traz `<IBSCBS>` com `<CST>000</CST>`, `<cClassTrib>000001</cClassTrib>` e `<gIBSCBS>` (UF, município, CBS), e o total traz `<IBSCBSTot>`; validou no XSD com o leiaute IBS/CBS. | A 4.00.51 tem o modelo completo (`TTribNFe`, `TCIBS`, `TIBSCBSMonoTot`) e um utilitário `IbsCbsUtil` que decide grupos pela tabela de CST. As alíquotas da PoC são fixas; a regra real (`FiscalTaxRules`) tem de ser portada na Fase 7. Regra mantida: em 2026 IBS/CBS não compõem `vNF`. |
| 3 | Certificado A1 em memória (assinar e transmitir sem arquivo) | **ATENDIDO** para assinatura; **PENDENTE** para a conexão com a SEFAZ | `Certificados.doPfx` usa `CertificadoService.certificadoPfxBytes(byte[], senha)`: a NFC-e foi assinada sem arquivo de certificado (teste `certificadoNaoVaiParaDisco` confirma que nada fica no disco). O keytool só é usado para **fabricar** o certificado de teste. | Achado: com validação ligada, a biblioteca confere os 8 primeiros dígitos do CNPJ do **certificado** com o do emitente e extrai o CNPJ do campo ICP-Brasil (SAN otherName `2.16.76.1.3.3`). Um PFX sem esse campo falha com `StringIndexOutOfBounds`. O A1 real tem o campo; o de teste foi gerado com ele. A transmissão usa o mesmo `Certificado` em memória (`Nfe.enviarNfe`), mas só se comprova contra a SEFAZ. |
| 4 | Operações na SEFAZ de homologação: autorizar, consultar, cancelar, inutilizar, contingência offline (tpEmis 9) | **PENDENTE** (SEFAZ) / **ATENDIDO** (geração offline) | `OperacoesSefaz.cicloCompleto` implementa os 6 passos (`statusServico`, `enviarNfe`, `consultaXml`, `cancelarNfe` com `CancelamentoUtil`, `inutilizacao` com `InutilizacaoUtil`, contingência), compilado contra a API real da biblioteca e acionado por `POC_*`. Sem as variáveis, `PocFiscal` imprime `PENDENTE`. A parte offline da contingência foi executada: `NfceOfflineTest.contingenciaOfflineTpEmis9GeraQrCodeComDigestValue` gera a nota com `tpEmis=9`, `dhCont`, `xJust`, `tpEmis` na chave e QR Code de contingência (usa o `DigestValue` da assinatura). | **Falta rodar com certificado A1 e CSC de homologação** (questão aberta do design). Não dá para afirmar autorização, cancelamento ou inutilização sem isso. |
| 5 | Relatório de decisão | **ATENDIDO** | este documento | ver recomendação abaixo |

## Pontos de atenção da biblioteca

- **Pilha antiga**: Axis2 1.7.5, `commons-httpclient` 3.1 e JAXB 2.3 com pacote `javax.xml.bind` (54 artefatos transitivos).
  Convive com o Spring Boot 3 (`jakarta.*`) por serem pacotes diferentes e rodou no JDK 21, mas deve ficar **isolada num
  módulo `fiscal`** (como previsto), nunca misturada aos modelos da API.
- **Sem XSD no jar** (ver critério 1) e QR Code montado à mão.
- **Licença MIT** (a biblioteca .NET atual é LGPL): sem restrição para o uso.
- **Manutenção ativa** (publicações em 2026; linhas 4.00.x, 4.1.x, 4.2.x e 4.3.0). Fixar a versão exata e testar em homologação a
  cada Nota Técnica, como já se faz com o pacote NuGet.

## Recomendação

**Seguir com a `java-nfe` 4.00.51**: ela cobre, com evidência executada, o que depende de montagem, assinatura, QR Code, IBS/CBS
e XSD, que é o grosso do risco de leiaute. **Condição para a Fase 7**: rodar `PocFiscal` com certificado A1 e CSC de
homologação e registrar aqui o `cStat` dos 6 passos (autorizar 100, consultar 100, cancelar 135, inutilizar 102, contingência
transmitida). Se algum passo falhar, a alternativa prevista é **montar o XML pelos XSD (JAXB) e usar a biblioteca só para
assinar e transmitir** (`Nfe.enviarNfe` aceita o lote já montado).

Execução da parte da SEFAZ: **pendente de certificado de homologação**.
