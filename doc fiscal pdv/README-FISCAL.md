# Módulo Fiscal — NFC-e modelo 65 (SEFAZ-RJ / SVRS)

Implementação in-process com ZeusFiscal (DFe.NET). Custo zero por nota; em troca, a
conformidade com as Notas Técnicas é responsabilidade do projeto.

## Verificação da biblioteca

Feita em 06/09/2026 sobre `Hercules-NET/ZeusFiscal`, último commit 31/08/2026.

| Item | Situação |
|---|---|
| `TargetFrameworks` | `net462; netstandard2.0; net8.0; net9.0; net10.0` — compatível |
| Grupo UB (IBS/CBS por item) | `NFe.Classes/Informacoes/Detalhe/Tributacao/IBSCBS.cs` com `cClassTrib`, `gIBSUF`, `gIBSMun`, `gCBS`, `gIBSCBSMono` |
| Grupo W03 (totalização RTC) | `IBSCBSTot.cs`, `ISTot.cs` em `Informacoes/Total` |
| CST do RTC | `CSTIBSCBS.cs` |
| QR Code NFC-e | `infNFeSupl.ObterUrlQrCode(nfe, VersaoQrCode, cIdToken, csc, cfgCert)` |
| Multiempresa | `ServicosNFe(ConfiguracaoServico, X509Certificate2)` — configuração por instância, sem singleton estático |
| Licença | LGPL — compatível com o MIT do projeto enquanto for dependência dinâmica (NuGet), sem vendorizar o código |

Duas restrições que mudam o desenho:

**A API é inteiramente síncrona.** Não há sobrecarga `Async`. Emitir dentro do
request bloqueia thread do pool durante toda a conversa com a SEFAZ. Daí o
`NfceOutboxWorker` ser obrigatório, não opcional.

**Impressão do DANFE em Linux.** `NFe.Danfe.Nativo` compila para `net9.0-windows`
e `net10.0-windows`; `NFe.Danfe.Fast` só tem `net462`. Se a API rodar em container
Linux, use `NFe.Danfe.QuestPdf` (`ImpressaoNfce/DanfeNfceDocument.cs`, com
`TamanhoImpressao` para bobina 80mm), que compila para `net8.0` sem sufixo de
plataforma.

## Pacotes

```xml
<PackageReference Include="Hercules.NET.NFe.NFCe" Version="2026.8.31.*" />
<PackageReference Include="NFe.Danfe.QuestPdf" Version="2026.8.31.*" />
<PackageReference Include="System.Security.Cryptography.Xml" Version="8.0.*" />
```

Fixe a versão exata em produção. Atualizar a lib é o que traz a NT nova — deve ser
uma decisão consciente, com homologação antes, nunca um `dotnet restore` distraído.

## Ordem de aplicação

1. `sql/01_migracao_valores.sql` — com a API parada e após backup. O script aborta
   se alguma linha não converter; rode antes a seção de validação isolada.
2. `sql/02_estrutura_fiscal.sql`
3. Copiar os schemas XSD da NT vigente para `API/NETCORE/DataBase/Schemas/` e
   incluir a pasta no `.csproj` como `CopyToOutputDirectory`.
4. Registrar no `Program.cs`:

```csharp
builder.Services.AddScoped<IFiscalProvider, ZeusFiscalProvider>();
builder.Services.AddScoped<DocumentoFiscalAB>();
builder.Services.AddScoped<EmitenteFiscalStore>();
builder.Services.AddHostedService<NfceOutboxWorker>();
```

5. Ajustar os repositórios que ainda leem valor como texto. Depois da migração,
   `RelatorioAB` pode e deve agregar com `SUM()` no SQL em vez de carregar a
   tabela em memória e somar com LINQ.

## Credenciamento e credenciais (RJ)

O RJ não tem autorizador próprio: delega para a SVRS. `Estado.RJ` na
`ConfiguracaoServico` já resolve os endpoints corretos, e o acesso deve ser sempre
por nome DNS — a SEFAZ-RJ orienta explicitamente a não fixar IP, para não perder a
contingência ativa automática.

Credenciamento é automático. Pelo Anexo II-A da Parte II da Resolução SEFAZ nº
720/14, contribuinte regularmente habilitado no CAD-ICMS já está autorizado a
emitir em produção e em testes. Se a IE estiver impedida, o credenciamento só volta
após a regularização cadastral.

O que precisa ser providenciado por empresa:

- **Certificado A1** (`.pfx`). Nunca A3 em servidor. Armazenado cifrado em
  `Empresas.CertificadoPfx` + `CertificadoSenhaCifrada`, usando o
  `HorusSecretProtector` que já existe no projeto (AES-GCM).
- **CSC e CSCid**, gerados no portal da SEFAZ-RJ, separados por ambiente. Também
  cifrados em repouso.
- **Série por terminal de caixa.** Dois caixas na mesma série competem pelo mesmo
  `nNF` e a SEFAZ rejeita duplicidade. Uma série por PDV.

## Reforma tributária

A rejeição 1115 já está ativa desde 03/08/2026 para o regime regular (CRT 3): sem
os campos de IBS e CBS, a nota é rejeitada. Para Simples Nacional (CRT 1) e MEI
(CRT 4), a exigência em produção começa em 04/01/2027, conforme o art. 348, III,
"c", da LC 214/2025.

Como o público do Hórus é majoritariamente Simples e MEI, `Produtos.CClassTrib`
pode ficar nulo por enquanto — o `ZeusFiscalProvider` só monta o grupo `IBSCBS`
quando a classificação está preenchida, e as regras de validação de IBS/CBS não
são executadas sobre campos não preenchidos.

Duas armadilhas de 2026:

- IBS, CBS e IS **não** entram na totalização do documento neste ano (rejeição
  1105). O `vNF` montado no provider já reflete isso.
- Alíquotas de teste do Ato Conjunto RFB/CGIBS nº 1/2025: 0,9% CBS e 0,1% IBS,
  hoje fixas no provider. Quando virarem parametrizáveis, movê-las para
  configuração por empresa.

## Homologação

Ambiente 2 (`Empresas.AmbienteFiscal = 2`) é o default proposital do script 02.
Em homologação a razão social do destinatário precisa ser literalmente
`NF-E EMITIDA EM AMBIENTE DE HOMOLOGACAO - SEM VALOR FISCAL`, senão vem rejeição
539 — já tratado no `MontarNfe`.

Roteiro mínimo antes de virar para produção:

1. Status do serviço (`NfeStatusServico`, cStat 107).
2. Emissão autorizada com um item, pagamento em dinheiro, sem destinatário.
3. Emissão com CPF no destinatário.
4. Emissão com quantidade fracionada (ex.: 0,4520 kg) — valida a migração para
   `DECIMAL(15,4)`.
5. Cancelamento dentro do prazo. **Confirme o prazo no manual da SEFAZ-RJ antes de
   codificar a regra**; na maioria das UFs são 30 minutos, mas não assuma.
6. Inutilização de uma faixa de numeração.
7. Contingência: derrube a rede, emita, restabeleça e confirme a transmissão
   posterior com cStat 150.

## O que ainda falta

- `DocumentoFiscalAB` e `EmitenteFiscalStore` — os repositórios referenciados pelo
  worker. São mecânicos, seguem o padrão `AB`/`AD` do projeto.
- `NfceController` expondo emitir, cancelar, inutilizar e consultar.
- DANFE 80mm via QuestPdf e envio para a impressora térmica.
- Frontend: substituir o placeholder "Em desenvolvimento" da `FiscalPage.tsx`.
- Guarda de 5 anos do XML autorizado. Hoje ele vai para
  `DocumentosFiscais.XmlProtocolado`; avaliar exportação periódica para fora do banco.
