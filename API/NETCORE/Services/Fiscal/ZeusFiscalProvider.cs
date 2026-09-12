/**
 * Arquivo: API/NETCORE/Services/Fiscal/ZeusFiscalProvider.cs
 * Objetivo: implementa IFiscalProvider usando a biblioteca Hercules.NET.NFe.NFCe (fork ativo e
 *           mantido do extinto DFe.NET/Zeus, mesmos namespaces, compatibilidade preservada)
 *           in-process, montando, assinando e transmitindo a NFC-e modelo 65 para a SVRS.
 * Entradas esperadas: recebe EmissaoNfceRequest já validado e o certificado A1 do emitente.
 *
 * Notas de operação:
 *  - A API da lib é SÍNCRONA. Todos os métodos aqui rodam sob Task.Run e só devem ser
 *    chamados pelo NfceOutboxWorker, nunca na thread de requisição.
 *  - ConfiguracaoServico é criada por chamada, nunca via singleton estático, porque
 *    cada empresa tem certificado, CSC, ambiente e série próprios.
 *  - O RJ não tem autorizador próprio: Estado.RJ resolve para a SVRS
 *    (nfce.sefazrs.rs.gov.br / nfce-homologacao.sefazrs.rs.gov.br).
 *  - DiretorioSchemas precisa acompanhar o deploy e ser atualizado a cada Nota Técnica — ver
 *    API/NETCORE/DataBase/Schemas/README.md. Enquanto a pasta estiver vazia, a validação local
 *    de schema fica desligada (a SEFAZ ainda valida do lado dela; só perdemos a checagem que
 *    evita queimar numeração com erro de schema antes de transmitir).
 */
using System.Globalization;
using System.Security.Cryptography.X509Certificates;
using DFe.Classes.Entidades;
using DFe.Classes.Flags;
using DFe.Utils;
using NFe.Classes;
using NFe.Classes.Informacoes;
using NFe.Classes.Informacoes.Detalhe;
using NFe.Classes.Informacoes.Detalhe.Tributacao;
using NFe.Classes.Informacoes.Detalhe.Tributacao.Estadual;
using NFe.Classes.Informacoes.Detalhe.Tributacao.Estadual.Tipos;
using NFe.Classes.Informacoes.Detalhe.Tributacao.Federal;
using NFe.Classes.Informacoes.Detalhe.Tributacao.Federal.Tipos;
using NFe.Classes.Informacoes.Destinatario;
using NFe.Classes.Informacoes.Emitente;
using NFe.Classes.Informacoes.Identificacao;
using NFe.Classes.Informacoes.Identificacao.Tipos;
using NFe.Classes.Informacoes.Pagamento;
using NFe.Classes.Informacoes.Total;
using NFe.Classes.Informacoes.Transporte;
using NFe.Classes.Servicos.Tipos;
using NFe.Servicos;
using NFe.Utils;
using NFe.Utils.InformacoesSuplementares;
using NFe.Utils.NFe;
using Shared.NFe.Classes.Informacoes.InfRespTec;
using HORUSPDV_API.Services.Shared;

namespace HORUSPDV_API.Services.Fiscal;

public sealed class ZeusFiscalProvider(
    IWebHostEnvironment environment,
    ILogger<ZeusFiscalProvider> logger) : IFiscalProvider
{
    private static readonly CultureInfo Inv = CultureInfo.InvariantCulture;

    private string DiretorioSchemas =>
        Path.Combine(environment.ContentRootPath, "DataBase", "Schemas");

    private bool SchemasDisponiveis
        => Directory.Exists(DiretorioSchemas) && Directory.EnumerateFiles(DiretorioSchemas, "*.xsd").Any();

    public Task<ResultadoFiscal> EmitirNfceAsync(EmissaoNfceRequest request, CancellationToken ct = default)
        => Task.Run(() => Emitir(request), ct);

    private ResultadoFiscal Emitir(EmissaoNfceRequest request)
    {
        var emitente = request.Emitente;

        if (string.IsNullOrWhiteSpace(emitente.InscricaoEstadual) ||
            string.IsNullOrWhiteSpace(emitente.Logradouro) ||
            string.IsNullOrWhiteSpace(emitente.Numero) ||
            string.IsNullOrWhiteSpace(emitente.Bairro) ||
            string.IsNullOrWhiteSpace(emitente.NomeMunicipio) ||
            string.IsNullOrWhiteSpace(emitente.Cep))
        {
            return new ResultadoFiscal
            {
                Status = StatusDocumentoFiscal.Rejeitado,
                CodigoStatus = 0,
                MotivoStatus = "Dados cadastrais da empresa incompletos em Minha Empresa: preencha Inscrição Estadual e o Endereço completo (Logradouro, Número, Bairro, Cidade e CEP).",
                Retentavel = false
            };
        }

        using var certificado = CarregarCertificado(emitente);
        var cfg = MontarConfiguracao(emitente, request.TipoEmissao);

        try
        {
            var nfe = MontarNfe(request);

            // Assina, gera o QR Code (infNFeSupl) e valida contra o XSD antes de transmitir.
            // Validar localmente evita queimar numeração com rejeição de schema.
            nfe.Assina(cfg, certificado);

            nfe.infNFeSupl = new infNFeSupl();
            // O cIdToken no QR-Code deve ter exatamente 6 dígitos com zeros à esquerda (spec NFC-e).
            // Se o cadastro veio sem padding (ex: "1"), formata para "000001".
            var cscIdPadded = int.TryParse(emitente.CscId, out var cscIdNum)
                ? cscIdNum.ToString("D6")
                : emitente.CscId.PadLeft(6, '0');

            nfe.infNFeSupl.qrCode = nfe.infNFeSupl.ObterUrlQrCode(
                nfe,
                VersaoQrCode.QrCodeVersao2,
                cscIdPadded,
                emitente.Csc,
                cfg.Certificado);
            nfe.infNFeSupl.urlChave = ObterUrlConsultaChave(emitente.Ambiente);

            if (cfg.ValidarSchemas)
            {
                nfe.Valida(cfg);
            }

            var xmlAssinado = nfe.ObterXmlString();
            var chave = nfe.infNFe.Id?.Replace("NFe", string.Empty, StringComparison.OrdinalIgnoreCase);

            using var servico = new ServicosNFe(cfg, certificado);
            var retorno = servico.NFeAutorizacao(
                idLote: 1,
                indSinc: IndicadorSincronizacao.Sincrono,
                nFes: [nfe],
                compactarMensagem: true);

            var protNfe = retorno.Retorno?.protNFe;
            var cStat = protNfe?.infProt?.cStat ?? retorno.Retorno?.cStat ?? 0;
            var xMotivo = protNfe?.infProt?.xMotivo ?? retorno.Retorno?.xMotivo ?? "Sem retorno da SEFAZ.";

            // 100 = Autorizado o uso da NF-e
            // 150 = Autorizado fora de prazo (contingência transmitida depois)
            if (cStat is 100 or 150)
            {
                return new ResultadoFiscal
                {
                    Status = StatusDocumentoFiscal.Autorizado,
                    CodigoStatus = cStat,
                    MotivoStatus = xMotivo,
                    ChaveAcesso = protNfe?.infProt?.chNFe ?? chave,
                    Protocolo = protNfe?.infProt?.nProt.ToString(),
                    DhAutorizacao = protNfe?.infProt?.dhRecbto,
                    XmlAssinado = xmlAssinado,
                    // Não há um "nfeProc" pronto na API desta versão da lib — guardamos a
                    // resposta completa da SEFAZ (RetornoCompletoStr) como registro de auditoria.
                    XmlProtocolado = retorno.RetornoCompletoStr
                };
            }

            // 110/301/302 = uso denegado. Numeração é consumida e não pode ser reaproveitada.
            if (cStat is 110 or 301 or 302 or 303)
            {
                return new ResultadoFiscal
                {
                    Status = StatusDocumentoFiscal.Denegado,
                    CodigoStatus = cStat,
                    MotivoStatus = xMotivo,
                    ChaveAcesso = chave,
                    XmlAssinado = xmlAssinado,
                    Retentavel = false
                };
            }

            logger.LogWarning(
                "NFC-e rejeitada. Empresa {CompanyId} serie {Serie} numero {Numero} cStat {CStat}: {Motivo}",
                emitente.CompanyId, request.Serie, request.NumeroNf, cStat, xMotivo);

            return new ResultadoFiscal
            {
                Status = StatusDocumentoFiscal.Rejeitado,
                CodigoStatus = cStat,
                MotivoStatus = xMotivo,
                ChaveAcesso = chave,
                XmlAssinado = xmlAssinado,
                Retentavel = EhRejeicaoTransitoria(cStat)
            };
        }
        catch (NFe.Utils.Excecoes.ValidacaoSchemaException ex)
        {
            logger.LogWarning(
                "NFC-e com erro de schema XML. Empresa {CompanyId} serie {Serie} numero {Numero}: {Motivo}",
                emitente.CompanyId, request.Serie, request.NumeroNf, ex.Message);

            return new ResultadoFiscal
            {
                Status = StatusDocumentoFiscal.Rejeitado,
                CodigoStatus = 0,
                MotivoStatus = "Erro de validação do XML contra o Schema da SEFAZ: " + ex.Message,
                Retentavel = false
            };
        }
        catch (Exception ex)
        {
            // Falha de rede, timeout ou SEFAZ fora. Não é rejeição: o documento
            // continua na fila e o worker decide se entra em contingência.
            logger.LogError(ex,
                "Falha ao transmitir NFC-e. Empresa {CompanyId} serie {Serie} numero {Numero}",
                emitente.CompanyId, request.Serie, request.NumeroNf);

            return new ResultadoFiscal
            {
                Status = StatusDocumentoFiscal.Assinado,
                CodigoStatus = 0,
                MotivoStatus = ex.Message,
                Retentavel = true
            };
        }
    }

    public Task<ResultadoFiscal> CancelarAsync(CancelamentoRequest request, CancellationToken ct = default)
        => Task.Run(() =>
        {
            var emitente = request.Emitente;
            using var certificado = CarregarCertificado(emitente);
            var cfg = MontarConfiguracao(emitente, TipoEmissaoFiscal.Normal);

            try
            {
                using var servico = new ServicosNFe(cfg, certificado);
                var retorno = servico.RecepcaoEventoCancelamento(
                    idlote: 1,
                    sequenciaEvento: request.SequenciaEvento,
                    protocoloAutorizacao: request.Protocolo,
                    chaveNFe: request.ChaveAcesso,
                    justificativa: request.Justificativa,
                    cpfcnpj: emitente.Cnpj);

                var infEvento = retorno.Retorno?.retEvento?.FirstOrDefault()?.infEvento;
                var cStat = infEvento?.cStat ?? 0;

                // 135 = evento registrado e vinculado; 155 = registrado fora de prazo
                var ok = cStat is 135 or 155;

                return new ResultadoFiscal
                {
                    Status = ok ? StatusDocumentoFiscal.Cancelado : StatusDocumentoFiscal.Rejeitado,
                    CodigoStatus = cStat,
                    MotivoStatus = infEvento?.xMotivo ?? "Sem retorno da SEFAZ.",
                    ChaveAcesso = request.ChaveAcesso,
                    Protocolo = infEvento?.nProt,
                    XmlProtocolado = retorno.RetornoCompletoStr
                };
            }
            catch (Exception ex)
            {
                logger.LogError(ex, "Falha ao cancelar NFC-e {Chave}", request.ChaveAcesso);
                return new ResultadoFiscal
                {
                    Status = StatusDocumentoFiscal.Rejeitado,
                    MotivoStatus = ex.Message,
                    Retentavel = true
                };
            }
        }, ct);

    public Task<ResultadoFiscal> InutilizarAsync(InutilizacaoRequest request, CancellationToken ct = default)
        => Task.Run(() =>
        {
            var emitente = request.Emitente;
            using var certificado = CarregarCertificado(emitente);
            var cfg = MontarConfiguracao(emitente, TipoEmissaoFiscal.Normal);

            using var servico = new ServicosNFe(cfg, certificado);
            var retorno = servico.NfeInutilizacao(
                cnpj: emitente.Cnpj,
                ano: HorusDateTime.Now.Year,
                modelo: ModeloDocumento.NFCe,
                serie: request.Serie,
                numeroInicial: request.NumeroInicial,
                numeroFinal: request.NumeroFinal,
                justificativa: request.Justificativa);

            var cStat = retorno.Retorno?.infInut?.cStat ?? 0;

            return new ResultadoFiscal
            {
                // 102 = inutilização homologada
                Status = cStat == 102 ? StatusDocumentoFiscal.Inutilizado : StatusDocumentoFiscal.Rejeitado,
                CodigoStatus = cStat,
                MotivoStatus = retorno.Retorno?.infInut?.xMotivo ?? "Sem retorno da SEFAZ."
            };
        }, ct);

    public Task<bool> ServicoDisponivelAsync(ContextoEmitente emitente, CancellationToken ct = default)
        => Task.Run(() =>
        {
            try
            {
                using var certificado = CarregarCertificado(emitente);
                var cfg = MontarConfiguracao(emitente, TipoEmissaoFiscal.Normal);
                cfg.TimeOut = 8000; // curto de propósito: é uma sonda, não uma emissão

                using var servico = new ServicosNFe(cfg, certificado);
                var retorno = servico.NfeStatusServico();

                // 107 = serviço em operação
                return retorno.Retorno?.cStat == 107;
            }
            catch
            {
                return false;
            }
        }, ct);

    /* --------------------------------------------------------------------- */
    /* Configuração                                                           */
    /* --------------------------------------------------------------------- */

    private ConfiguracaoServico MontarConfiguracao(ContextoEmitente emitente, TipoEmissaoFiscal tipoEmissao)
    {
        var schemasDisponiveis = SchemasDisponiveis;
        if (!schemasDisponiveis)
        {
            logger.LogWarning(
                "Validacao local de schema XSD desativada (DataBase/Schemas vazio) para a empresa {CompanyId}. " +
                "A SEFAZ ainda valida do lado dela; ver DataBase/Schemas/README.md.",
                emitente.CompanyId);
        }

        return new ConfiguracaoServico
        {
            cUF = Estado.RJ,                                   // resolve para a SVRS
            tpAmb = emitente.Ambiente == 1
                ? TipoAmbiente.Producao
                : TipoAmbiente.Homologacao,
            ModeloDocumento = ModeloDocumento.NFCe,
            VersaoLayout = VersaoServico.Versao400,
            tpEmis = tipoEmissao == TipoEmissaoFiscal.ContingenciaOffline
                ? TipoEmissao.teOffLine
                : TipoEmissao.teNormal,
            DefineVersaoServicosAutomaticamente = true,
            DiretorioSchemas = DiretorioSchemas,
            ValidarSchemas = schemasDisponiveis,
            TimeOut = 30000,
            SalvarXmlServicos = false,          // persistimos no banco, não em disco
            Certificado = new ConfiguracaoCertificado
            {
                TipoCertificado = TipoCertificado.A1ByteArray,
                ArrayBytesArquivo = emitente.CertificadoPfx,
                Senha = emitente.CertificadoSenha,
                ManterDadosEmCache = false
            }
        };
    }

    private static X509Certificate2 CarregarCertificado(ContextoEmitente emitente)
        => new(
            emitente.CertificadoPfx,
            emitente.CertificadoSenha,
            X509KeyStorageFlags.EphemeralKeySet | X509KeyStorageFlags.Exportable);

    private static string ObterUrlConsultaChave(byte ambiente)
        => ambiente == 1
            ? "https://www.fazenda.rj.gov.br/nfce/consulta"
            : "https://www4.fazenda.rj.gov.br/consultaNFCe/paginas/consultaChaveAcesso.faces";

    /// <summary>
    /// Rejeições que valem retentativa. Erro de conteúdo (NCM inválido, total
    /// divergente) nunca é retentável — reenviar só queima numeração.
    /// </summary>
    private static bool EhRejeicaoTransitoria(int cStat) => cStat is
        108 or   // serviço paralisado momentaneamente
        109 or   // serviço paralisado sem previsão
        999;     // erro não catalogado

    /* --------------------------------------------------------------------- */
    /* Montagem do documento                                                  */
    /* --------------------------------------------------------------------- */

    private static NFe.Classes.NFe MontarNfe(EmissaoNfceRequest request)
    {
        var e = request.Emitente;

        var ide = new ide
        {
            cUF = Estado.RJ,
            natOp = "VENDA AO CONSUMIDOR",
            mod = ModeloDocumento.NFCe,
            serie = request.Serie,
            nNF = request.NumeroNf,
            cNF = GerarCodigoNumerico(request.NumeroNf),
            dhEmi = HorusDateTime.Now,
            tpNF = TipoNFe.tnSaida,
            idDest = DestinoOperacao.doInterna,
            cMunFG = long.Parse(e.CodigoMunicipioIbge, Inv),
            tpImp = TipoImpressao.tiNFCe,
            tpEmis = request.TipoEmissao == TipoEmissaoFiscal.ContingenciaOffline
                ? TipoEmissao.teOffLine
                : TipoEmissao.teNormal,
            tpAmb = e.Ambiente == 1 ? TipoAmbiente.Producao : TipoAmbiente.Homologacao,
            finNFe = FinalidadeNFe.fnNormal,
            indFinal = ConsumidorFinal.cfConsumidorFinal,
            indPres = PresencaComprador.pcPresencial,
            procEmi = ProcessoEmissao.peAplicativoContribuinte,
            verProc = "HorusPDV/1.0"
        };

        if (request.TipoEmissao == TipoEmissaoFiscal.ContingenciaOffline)
        {
            ide.dhCont = request.DhContingencia.HasValue ? HorusDateTime.ToBrasilia(request.DhContingencia.Value) : HorusDateTime.Now;
            ide.xJust = request.JustificativaContingencia;
        }

        var emit = new emit
        {
            CNPJ = e.Cnpj,
            xNome = e.RazaoSocial,
            xFant = string.IsNullOrWhiteSpace(e.NomeFantasia) ? null : e.NomeFantasia,
            IE = e.InscricaoEstadual,
            CRT = (CRT)e.Crt,
            // CNAE no schema NF-e só é válido se acompanhado da Inscrição Municipal (IM).
            // Para varejo/NFC-e de mercadorias sem ISSQN, não deve ser gerado isolado.
            CNAE = null,
            enderEmit = new enderEmit
            {
                xLgr = e.Logradouro,
                nro = e.Numero,
                xCpl = string.IsNullOrWhiteSpace(e.Complemento) ? null : e.Complemento,
                xBairro = e.Bairro,
                cMun = long.Parse(e.CodigoMunicipioIbge, Inv),
                xMun = e.NomeMunicipio,
                UF = Estado.RJ,
                CEP = e.Cep,
                cPais = 1058,
                xPais = "BRASIL",
                fone = ParseFoneNumerico(e.Fone)
            }
        };

        dest? dest = null;
        if (request.Destinatario is { CpfCnpj.Length: > 0 } d)
        {
            dest = new dest(VersaoServico.Versao400)
            {
                indIEDest = (indIEDest)d.IndIeDest,
                xNome = d.Nome
            };

            if (d.CpfCnpj.Length == 11) dest.CPF = d.CpfCnpj;
            else dest.CNPJ = d.CpfCnpj;

            // Em homologação a SEFAZ exige esta razão social literal (rejeição 539)
            if (e.Ambiente == 2)
                dest.xNome = "NF-E EMITIDA EM AMBIENTE DE HOMOLOGACAO - SEM VALOR FISCAL";
        }

        var detalhes = request.Itens.Select(MontarItem).ToList();

        // Em homologação a SEFAZ exige que o xProd do 1º item seja esta literal (rejeição 373)
        if (e.Ambiente == 2 && detalhes.Count > 0)
            detalhes[0].prod.xProd = "NOTA FISCAL EMITIDA EM AMBIENTE DE HOMOLOGACAO - SEM VALOR FISCAL";

        var total = new total
        {
            ICMSTot = new ICMSTot
            {
                vBC = 0,
                vICMS = 0,
                vICMSDeson = 0,
                vFCP = 0,
                vBCST = 0,
                vST = 0,
                vFCPST = 0,
                vFCPSTRet = 0,
                vProd = request.Itens.Sum(i => i.ValorTotal),
                vFrete = 0,
                vSeg = 0,
                vDesc = request.Itens.Sum(i => i.Desconto),
                vII = 0,
                vIPI = 0,
                vIPIDevol = 0,
                vPIS = 0,
                vCOFINS = 0,
                vOutro = 0,
                // Em 2026 IBS/CBS/IS não compõem o total do documento (rejeição 1105)
                vNF = request.Itens.Sum(i => i.ValorTotal) - request.Itens.Sum(i => i.Desconto)
            }
        };

        var pagamento = new pag
        {
            detPag = request.Pagamentos.Select(p =>
            {
                var tPag = (FormaPagamento)int.Parse(p.Tipo, Inv);
                return new detPag
                {
                    tPag = tPag,
                    vPag = p.Valor,
                    card = MontarCard(p, tPag)
                };
            }).ToList(),
            vTroco = request.ValorTroco > 0 ? request.ValorTroco : null
        };

        var infNFe = new infNFe
        {
            versao = "4.00",
            ide = ide,
            emit = emit,
            dest = dest,
            det = detalhes,
            total = total,
            transp = new transp { modFrete = ModalidadeFrete.mfSemFrete },
            pag = [pagamento],
            infRespTec = string.IsNullOrWhiteSpace(e.RespTecCnpj) ? null : new infRespTec
            {
                CNPJ = e.RespTecCnpj,
                xContato = e.RespTecContato,
                email = e.RespTecEmail,
                fone = e.RespTecFone
            }
        };

        return new NFe.Classes.NFe { infNFe = infNFe };
    }

    private static det MontarItem(ItemFiscal item)
    {
        var prod = new prod
        {
            cProd = item.CodigoProduto,
            cEAN = item.Gtin,
            xProd = item.Descricao,
            NCM = item.Ncm,
            CEST = item.Cest,
            CFOP = int.Parse(item.Cfop, Inv),
            uCom = item.UnidadeComercial,
            qCom = item.Quantidade,
            vUnCom = item.ValorUnitario,
            vProd = item.ValorTotal,
            cEANTrib = item.Gtin,
            uTrib = item.UnidadeComercial,
            qTrib = item.Quantidade,
            vUnTrib = item.ValorUnitario,
            vDesc = item.Desconto > 0 ? item.Desconto : null,
            indTot = IndicadorTotal.ValorDoItemCompoeTotalNF
        };

        var imposto = new imposto
        {
            ICMS = new ICMS
            {
                // CRT 1/4 usa CSOSN; CRT 3 usa CST. O builder escolhe pelo cadastro.
                TipoICMS = item.Csosn is not null
                    ? MontarIcmsSn(item)
                    : MontarIcmsNormal(item)
            },
            PIS = new PIS
            {
                TipoPIS = MontarPis(item.CstPis)
            },
            COFINS = new COFINS
            {
                TipoCOFINS = MontarCofins(item.CstCofins)
            }
        };

        // NT 2025.002 — grupo UB. Só preenche quando o cadastro traz a classificação
        // completa (CST + cClassTrib); sem os dois, as regras de validação de IBS/CBS
        // não são executadas e o grupo fica de fora do XML.
        var cstIbsCbs = TryParseCstIbsCbs(item.CstIbsCbs);
        if (!string.IsNullOrWhiteSpace(item.CClassTrib) && cstIbsCbs is not null)
        {
            imposto.IBSCBS = new IBSCBS
            {
                CST = cstIbsCbs.Value,
                cClassTrib = item.CClassTrib,
                gIBSCBS = new gIBSCBS
                {
                    vBC = item.ValorTotal,
                    gIBSUF = new gIBSUF { pIBSUF = 0.0000m, vIBSUF = 0 },
                    gIBSMun = new gIBSMun { pIBSMun = 0.1000m, vIBSMun = 0 },
                    gCBS = new gCBS { pCBS = 0.9000m, vCBS = 0 }
                }
            };
        }

        return new det
        {
            nItem = item.Numero,
            prod = prod,
            imposto = imposto
        };
    }

    private static ICMSBasico MontarIcmsSn(ItemFiscal item) => item.Csosn switch
    {
        "102" or "103" or "300" or "400" => new ICMSSN102
        {
            orig = (OrigemMercadoria)item.Origem,
            CSOSN = Enum.Parse<Csosnicms>("Csosn" + item.Csosn)
        },
        "500" => new ICMSSN500
        {
            orig = (OrigemMercadoria)item.Origem,
            CSOSN = Csosnicms.Csosn500
        },
        _ => new ICMSSN102
        {
            orig = (OrigemMercadoria)item.Origem,
            CSOSN = Csosnicms.Csosn102
        }
    };

    private static ICMSBasico MontarIcmsNormal(ItemFiscal item) => item.CstIcms switch
    {
        "60" => new ICMS60
        {
            orig = (OrigemMercadoria)item.Origem,
            CST = Csticms.Cst60
        },
        _ => new ICMS00
        {
            orig = (OrigemMercadoria)item.Origem,
            CST = Csticms.Cst00,
            modBC = DeterminacaoBaseIcms.DbiValorOperacao,
            vBC = item.ValorTotal,
            pICMS = item.AliquotaIcms,
            vICMS = Math.Round(item.ValorTotal * item.AliquotaIcms / 100m, 2)
        }
    };

    private static PISBasico MontarPis(string? cstPis)
    {
        var cst = string.IsNullOrWhiteSpace(cstPis) ? "49" : cstPis.Trim().PadLeft(2, '0');
        var parsedCst = Enum.Parse<CSTPIS>("pis" + cst);

        return cst switch
        {
            "04" or "05" or "06" or "07" or "08" or "09" => new PISNT
            {
                CST = parsedCst
            },
            _ => new PISOutr
            {
                CST = parsedCst,
                vBC = 0,
                pPIS = 0,
                vPIS = 0
            }
        };
    }

    private static COFINSBasico MontarCofins(string? cstCofins)
    {
        var cst = string.IsNullOrWhiteSpace(cstCofins) ? "49" : cstCofins.Trim().PadLeft(2, '0');
        var parsedCst = Enum.Parse<CSTCOFINS>("cofins" + cst);

        return cst switch
        {
            "04" or "05" or "06" or "07" or "08" or "09" => new COFINSNT
            {
                CST = parsedCst
            },
            _ => new COFINSOutr
            {
                CST = parsedCst,
                vBC = 0,
                pCOFINS = 0,
                vCOFINS = 0
            }
        };
    }

    /// <summary>Telefone do emitente é numérico na lib (Int64?) — extrai só os dígitos.</summary>
    private static long? ParseFoneNumerico(string? fone)
    {
        if (string.IsNullOrWhiteSpace(fone)) return null;
        var digits = new string(fone.Where(char.IsDigit).ToArray());
        return long.TryParse(digits, out var numero) ? numero : null;
    }

    /// <summary>
    /// Gera o cNF (Código Numérico) de 8 dígitos aleatórios para compor a Chave de Acesso.
    /// A SEFAZ exige que seja não-nulo, diferente de zero e diferente do nNF (rejeição 897).
    /// </summary>
    private static string GerarCodigoNumerico(int nNF)
    {
        int cnf;
        do
        {
            cnf = Random.Shared.Next(10000000, 99999999);
        } while (cnf == nNF);

        return cnf.ToString("D8");
    }

    /// <summary>
    /// Mapeia o texto livre de bandeira (vindo do TEF/adquirente) para o enum da lib.
    /// Sem correspondência reconhecida, cai em "Outros" — não bloqueia a emissão por causa
    /// de uma bandeira nova/atípica.
    /// </summary>
    private static BandeiraCartao MapearBandeira(string? bandeira)
    {
        var normalizado = (bandeira ?? string.Empty).Trim().ToUpperInvariant();
        return normalizado switch
        {
            var b when b.Contains("VISA") => BandeiraCartao.bcVisa,
            var b when b.Contains("MASTER") => BandeiraCartao.bcMasterCard,
            var b when b.Contains("AMEX") || b.Contains("AMERICAN") => BandeiraCartao.bcAmericanExpress,
            var b when b.Contains("ELO") => BandeiraCartao.Elo,
            var b when b.Contains("HIPER") => BandeiraCartao.Hipercard,
            var b when b.Contains("DINERS") => BandeiraCartao.bcDinersClub,
            var b when b.Contains("SOROCRED") => BandeiraCartao.bcSorocred,
            _ => BandeiraCartao.bcOutros
        };
    }

    /// <summary>
    /// CClassTrib/CstIbsCbs ainda são opcionais (Simples/MEI não obrigados até 04/01/2027) —
    /// se o cadastro trouxer um código que não bate com o enum da NT vigente, prefere deixar
    /// nulo a travar a emissão do item inteiro.
    /// </summary>
    private static CSTIBSCBS? TryParseCstIbsCbs(string? cst)
    {
        if (string.IsNullOrWhiteSpace(cst)) return null;
        return Enum.TryParse<CSTIBSCBS>("cst" + cst, out var parsed) ? parsed : null;
    }

    /// <summary>
    /// Tag card é obrigatória para pagamentos com cartão (crédito/débito) — rejeição SEFAZ 391.
    /// Para POS avulso (sem TEF integrado), tpIntegra deve ser 2 (TipNaoIntegrado).
    /// </summary>
    private static card? MontarCard(PagamentoFiscal p, FormaPagamento tPag)
    {
        var ehCartao = tPag is FormaPagamento.fpCartaoCredito or FormaPagamento.fpCartaoDebito;
        var temIntegracao = !string.IsNullOrWhiteSpace(p.CnpjCredenciadora);

        if (!ehCartao && !temIntegracao)
        {
            return null;
        }

        if (temIntegracao)
        {
            return new card
            {
                tpIntegra = TipoIntegracaoPagamento.TipIntegradoAutomacao,
                CNPJ = p.CnpjCredenciadora,
                tBand = MapearBandeira(p.BandeiraCartao),
                cAut = p.AutorizacaoTef
            };
        }

        return new card
        {
            tpIntegra = TipoIntegracaoPagamento.TipNaoIntegrado,
            tBand = string.IsNullOrWhiteSpace(p.BandeiraCartao) ? BandeiraCartao.bcOutros : MapearBandeira(p.BandeiraCartao)
        };
    }
}
