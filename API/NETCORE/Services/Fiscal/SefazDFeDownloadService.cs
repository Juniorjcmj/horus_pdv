/**
 * Arquivo: API/NETCORE/Services/Fiscal/SefazDFeDownloadService.cs
 * Objetivo: consulta e baixa o XML completo de NF-e diretamente do WebService da SEFAZ
 *           (Ambiente Nacional / NFeDistribuicaoDFe) a partir da chave de acesso de 44 dígitos,
 *           utilizando o Certificado Digital A1 da empresa emitente/destinatária.
 *           Caso a SEFAZ retorne apenas o resumo (resNFe), realiza automaticamente o evento de
 *           "Ciência da Operação" (Manifestação do Destinatário) para liberar e obter o XML
 *           integral (procNFe) com todos os itens de compra para entrada em estoque.
 */
using System.Security.Cryptography.X509Certificates;
using System.Text;
using DFe.Classes.Entidades;
using DFe.Classes.Flags;
using DFe.Utils;
using HORUSPDV_API.Repositories.DatabaseAccess;
using NFe.Classes.Informacoes.Identificacao.Tipos;
using NFe.Classes.Servicos.Tipos;
using NFe.Servicos;
using NFe.Utils;

namespace HORUSPDV_API.Services.Fiscal;

public class SefazDFeDownloadService(
    EmpresaAB empresaAB,
    IWebHostEnvironment environment,
    ILogger<SefazDFeDownloadService> logger)
{
    private string DiretorioSchemas =>
        Path.Combine(environment.ContentRootPath, "DataBase", "Schemas");

    private bool SchemasDisponiveis =>
        Directory.Exists(DiretorioSchemas) && Directory.EnumerateFiles(DiretorioSchemas, "*.xsd").Any();

    /// <summary>
    /// Consulta o Ambiente Nacional da SEFAZ pela chave de acesso e retorna os bytes do XML completo (procNFe).
    /// </summary>
    public async Task<byte[]> BuscarXmlNfePorChaveAsync(string companyId, string chaveAcesso, CancellationToken ct = default)
    {
        var chaveLimpa = new string(chaveAcesso.Where(char.IsDigit).ToArray());
        if (chaveLimpa.Length != 44)
        {
            throw new InvalidOperationException("Chave de acesso inválida. A chave da NF-e deve conter exatamente 44 dígitos numéricos.");
        }

        var empresa = await empresaAB.ObterAsync(companyId);
        if (empresa is null)
        {
            throw new InvalidOperationException("Empresa não encontrada no sistema.");
        }

        if (string.IsNullOrWhiteSpace(empresa.CertificadoPfxBase64) || string.IsNullOrWhiteSpace(empresa.CertificadoSenha))
        {
            throw new InvalidOperationException(
                "A empresa não possui Certificado Digital A1 configurado no sistema. " +
                "Cadastre o certificado A1 em 'Minha Empresa' para realizar a consulta e download direto na SEFAZ.");
        }

        byte[] certBytes;
        try
        {
            certBytes = Convert.FromBase64String(empresa.CertificadoPfxBase64);
        }
        catch (FormatException ex)
        {
            throw new InvalidOperationException("O Certificado Digital A1 armazenado para a empresa está em formato inválido.", ex);
        }

        var cnpjNumeros = new string(empresa.Cnpj.Where(char.IsDigit).ToArray());
        if (cnpjNumeros.Length != 14)
        {
            throw new InvalidOperationException("CNPJ da empresa é inválido para consulta perante a SEFAZ.");
        }

        return await Task.Run(async () =>
        {
            using var certificado = new X509Certificate2(
                certBytes,
                empresa.CertificadoSenha,
                X509KeyStorageFlags.EphemeralKeySet | X509KeyStorageFlags.Exportable);

            var cUf = Enum.TryParse<Estado>(empresa.Uf?.Trim(), true, out var estadoParsed)
                ? estadoParsed
                : Estado.RJ;

            var cfg = new ConfiguracaoServico
            {
                cUF = cUf,
                tpAmb = empresa.AmbienteFiscal == 1 ? TipoAmbiente.Producao : TipoAmbiente.Homologacao,
                ModeloDocumento = ModeloDocumento.NFe,
                VersaoLayout = VersaoServico.Versao400,
                tpEmis = TipoEmissao.teNormal,
                DefineVersaoServicosAutomaticamente = true,
                DiretorioSchemas = DiretorioSchemas,
                ValidarSchemas = SchemasDisponiveis,
                TimeOut = 35000,
                SalvarXmlServicos = false,
                Certificado = new ConfiguracaoCertificado
                {
                    TipoCertificado = TipoCertificado.A1ByteArray,
                    ArrayBytesArquivo = certBytes,
                    Senha = empresa.CertificadoSenha,
                    ManterDadosEmCache = false
                }
            };

            using var servico = new ServicosNFe(cfg, certificado);
            var ufAutor = empresa.Uf?.Trim().ToUpperInvariant() ?? "AN";

            logger.LogInformation(
                "Consultando SEFAZ DFe para empresa {Cnpj} (UF: {Uf}, Amb: {Ambiente}) - Chave: {Chave}",
                cnpjNumeros,
                ufAutor,
                cfg.tpAmb,
                chaveLimpa);

            var retorno = servico.NfeDistDFeInteresse(
                ufAutor: ufAutor,
                documento: cnpjNumeros,
                ultNSU: "0",
                nSU: "0",
                chNFE: chaveLimpa);

            var retDist = retorno?.Retorno;
            if (retDist is null)
            {
                throw new InvalidOperationException("A SEFAZ não retornou resposta para a consulta de distribuição de DF-e.");
            }

            // 137 = Nenhum documento localizado
            if (retDist.cStat == 137)
            {
                throw new InvalidOperationException(
                    $"Nenhum documento localizado na SEFAZ para a chave informada ({chaveLimpa}). " +
                    $"Verifique se a chave de 44 dígitos está correta e se a nota fiscal foi emitida tendo o CNPJ desta empresa ({empresa.Cnpj}) como destinatário.");
            }

            // 656 = Consumo Indevido
            if (retDist.cStat == 656)
            {
                throw new InvalidOperationException(
                    "A SEFAZ bloqueou temporariamente a consulta por 'Consumo Indevido' (cStat 656). " +
                    "Aguarde cerca de 1 hora para efetuar novas consultas de chave para este CNPJ.");
            }

            // 138 = Documento(s) localizado(s)
            if (retDist.cStat != 138)
            {
                throw new InvalidOperationException(
                    $"A SEFAZ retornou código {retDist.cStat}: {retDist.xMotivo}");
            }

            var lote = retDist.loteDistDFeInt ?? Array.Empty<NFe.Classes.Servicos.DistribuicaoDFe.loteDistDFeInt>();
            if (lote.Length == 0)
            {
                throw new InvalidOperationException("A SEFAZ respondeu que o documento foi localizado, porém o lote retornado está vazio.");
            }

            // Verifica se já veio o XML completo (procNFe)
            var xmlCompleto = ExtrairXmlCompleto(lote);
            if (xmlCompleto is { Length: > 0 })
            {
                logger.LogInformation("XML completo de NF-e obtido diretamente da SEFAZ para a chave {Chave}.", chaveLimpa);
                return xmlCompleto;
            }

            // Se veio apenas o resumo (resNFe), precisamos manifestar Ciência da Operação para que a SEFAZ libere o XML completo
            var temResumo = lote.Any(l => l.ResNFe is not null || (l.schema?.StartsWith("resNFe", StringComparison.OrdinalIgnoreCase) ?? false));
            if (temResumo)
            {
                logger.LogInformation(
                    "SEFAZ retornou apenas o resumo (resNFe) para a chave {Chave}. Registrando Ciência da Operação...",
                    chaveLimpa);

                var retornoEvento = servico.RecepcaoEventoManifestacaoDestinatario(
                    idlote: 1,
                    sequenciaEvento: 1,
                    chaveNFe: chaveLimpa,
                    nFeTipoEventoManifestacaoDestinatario: NFeTipoEvento.TeMdCienciaDaOperacao,
                    cpfcnpj: cnpjNumeros,
                    justificativa: null,
                    dhEvento: DateTimeOffset.Now);

                var retEventoEnv = retornoEvento?.Retorno;
                var retEventoItem = retEventoEnv?.retEvento?.FirstOrDefault();
                var cStatEvento = retEventoItem?.infEvento?.cStat ?? retEventoEnv?.cStat ?? 0;
                var xMotivoEvento = retEventoItem?.infEvento?.xMotivo ?? retEventoEnv?.xMotivo ?? "Sem motivo informado";

                logger.LogInformation(
                    "Resultado da Ciência da Operação para a chave {Chave}: cStat={CStat}, xMotivo={Motivo}",
                    chaveLimpa,
                    cStatEvento,
                    xMotivoEvento);

                // cStat 135 = Evento registrado e vinculado
                // cStat 136 = Evento registrado, não vinculado
                // cStat 573 = Duplicidade de evento (já manifestado anteriormente)
                if (cStatEvento is not (135 or 136 or 573))
                {
                    throw new InvalidOperationException(
                        $"Falha ao registrar Ciência da Operação na SEFAZ para liberar o XML (cStat {cStatEvento}): {xMotivoEvento}");
                }

                // Aguarda 2 segundos e tenta nova consulta para obter o procNFe liberado
                for (var tentativa = 1; tentativa <= 2; tentativa++)
                {
                    await Task.Delay(2000, ct);

                    logger.LogInformation(
                        "Nova tentativa ({Tentativa}/2) de obtenção do XML completo na SEFAZ após Ciência da Operação...",
                        tentativa);

                    var retornoAposManifestacao = servico.NfeDistDFeInteresse(
                        ufAutor: ufAutor,
                        documento: cnpjNumeros,
                        ultNSU: "0",
                        nSU: "0",
                        chNFE: chaveLimpa);

                    var loteApos = retornoAposManifestacao?.Retorno?.loteDistDFeInt ?? Array.Empty<NFe.Classes.Servicos.DistribuicaoDFe.loteDistDFeInt>();
                    xmlCompleto = ExtrairXmlCompleto(loteApos);
                    if (xmlCompleto is { Length: > 0 })
                    {
                        logger.LogInformation(
                            "XML completo de NF-e obtido com sucesso na SEFAZ após Ciência da Operação (chave {Chave}).",
                            chaveLimpa);
                        return xmlCompleto;
                    }
                }

                throw new InvalidOperationException(
                    "A Ciência da Operação foi registrada com sucesso perante a SEFAZ, mas o Ambiente Nacional ainda está processando a liberação do XML completo da NF-e. Aguarde cerca de 1 a 2 minutos e tente consultar novamente.");
            }

            throw new InvalidOperationException(
                "A SEFAZ localizou registros para a chave informada, mas nenhum XML completo (procNFe) ou resumo (resNFe) pôde ser extraído.");
        }, ct);
    }

    private static byte[]? ExtrairXmlCompleto(NFe.Classes.Servicos.DistribuicaoDFe.loteDistDFeInt[] lote)
    {
        foreach (var item in lote)
        {
            if (item.XmlNfe is { Length: > 0 })
            {
                var xmlTexto = Encoding.UTF8.GetString(item.XmlNfe);
                if (xmlTexto.Contains("<infNFe", StringComparison.OrdinalIgnoreCase))
                {
                    return item.XmlNfe;
                }
            }

            if (item.NfeProc is not null)
            {
                var xmlTexto = FuncoesXml.ClasseParaXmlString(item.NfeProc);
                if (!string.IsNullOrWhiteSpace(xmlTexto))
                {
                    return Encoding.UTF8.GetBytes(xmlTexto);
                }
            }
        }

        return null;
    }
}
