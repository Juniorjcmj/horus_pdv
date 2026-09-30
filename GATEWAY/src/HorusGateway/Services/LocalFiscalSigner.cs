/*
 * Arquivo: Services/LocalFiscalSigner.cs
 * Objetivo: serviço fiscal local do Gateway que monta o XML da NFC-e Modelo 65 com tpEmis = 9,
 *           assina digitalmente com Certificado A1 (.pfx) via SignedXml e calcula o QR-Code v2.0
 *           com cHashQRCode (digest value em hexadecimal + CSC) para impressão térmica imediata.
 */
using System.Globalization;
using System.Security.Cryptography;
using System.Security.Cryptography.Xml;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Xml;
using HorusGateway.Configuration;
using HorusGateway.Models;
using Microsoft.Extensions.Options;

namespace HorusGateway.Services;

public sealed class LocalFiscalSigner
{
    private readonly GatewayCertificateService _certificateService;
    private readonly GatewayOptions _options;
    private readonly ILogger<LocalFiscalSigner> _logger;

    public LocalFiscalSigner(
        GatewayCertificateService certificateService,
        IOptions<GatewayOptions> options,
        ILogger<LocalFiscalSigner> logger)
    {
        _certificateService = certificateService;
        _options = options.Value;
        _logger = logger;
    }

    /// <summary>
    /// Monta o XML da NFC-e modelo 65 com tpEmis = 9, assina digitalmente com certificado A1
    /// e calcula o QR Code oficial versão 2.0.
    /// </summary>
    public LocalNfceContingenciaResponse EmitirContingencia(
        LocalNfceContingenciaRequest request,
        int numeroNf,
        int serie,
        DateTimeOffset dhContingencia)
    {
        using var cert = _certificateService.CarregarCertificado();
        if (cert is null)
        {
            return new LocalNfceContingenciaResponse
            {
                Success = false,
                Message = "Certificado digital A1 (.pfx) não configurado ou inválido no Gateway local."
            };
        }

        var justificativa = !string.IsNullOrWhiteSpace(request.Justificativa) && request.Justificativa.Trim().Length >= 15
            ? request.Justificativa.Trim()
            : "EMISSAO EM CONTINGENCIA OFFLINE POR INDISPONIBILIDADE DE REDE";

        var cnf = RandomNumberGenerator.GetInt32(10000000, 99999999).ToString("D8");
        var chaveAcesso = GerarChaveAcesso(
            uf: _options.EmitenteCodigoUf,
            dataHora: dhContingencia,
            cnpj: _options.EmitenteCnpj,
            modelo: 65,
            serie: serie,
            numero: numeroNf,
            tipoEmissao: 9, // Contingência offline
            codigoNumerico: cnf);

        var xmlString = MontarXmlNfce(request, chaveAcesso, numeroNf, serie, dhContingencia, justificativa, cnf);
        var (xmlAssinado, digestValue) = AssinarXml(xmlString, chaveAcesso, cert);

        var qrCodeUrl = GerarQrCodeV2(chaveAcesso, digestValue);
        var xmlFinalComSupl = InserirInfNFeSupl(xmlAssinado, qrCodeUrl, ObterUrlConsultaChave());

        var subtotal = request.Itens.Sum(i => i.ValorTotal);
        var totalDesconto = request.Itens.Sum(i => i.Desconto);
        var totalLiquido = Math.Max(0, subtotal - totalDesconto);

        var danfeData = new DanfeThermalData
        {
            RazaoSocial = _options.EmitenteRazaoSocial,
            NomeFantasia = !string.IsNullOrWhiteSpace(_options.EmitenteNomeFantasia) ? _options.EmitenteNomeFantasia : _options.EmitenteRazaoSocial,
            Cnpj = _options.EmitenteCnpj,
            InscricaoEstadual = _options.EmitenteInscricaoEstadual,
            ChaveAcesso = chaveAcesso,
            Serie = serie,
            NumeroNf = numeroNf,
            DhEmissao = dhContingencia.ToString("dd/MM/yyyy HH:mm:ss"),
            EmissaoContingencia = true,
            MensagemContingencia = "EMITIDA EM CONTINGÊNCIA - Pendente de autorização",
            Justificativa = justificativa,
            Subtotal = subtotal,
            Desconto = totalDesconto,
            TotalLiquido = totalLiquido,
            ValorTroco = request.ValorTroco,
            CustomerCpf = request.CustomerCpf,
            CustomerName = request.CustomerName,
            Itens = request.Itens.Select(i => new DanfeItemData
            {
                Numero = i.Numero,
                Codigo = i.CodigoProduto,
                Descricao = i.Descricao,
                Quantidade = i.Quantidade,
                Unidade = i.UnidadeComercial,
                ValorUnitario = i.ValorUnitario,
                ValorTotal = i.ValorTotal
            }).ToList(),
            Pagamentos = request.Pagamentos.Select(p => new DanfePagamentoData
            {
                Forma = MapearDescricaoPagamento(p.Tipo),
                Valor = p.Valor
            }).ToList(),
            QrCodeUrl = qrCodeUrl,
            UrlConsultaChave = ObterUrlConsultaChave()
        };

        return new LocalNfceContingenciaResponse
        {
            Success = true,
            Message = "NFC-e gerada e assinada com sucesso em contingência offline pelo Local Gateway.",
            DocumentoId = $"gw-nfce-{DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()}",
            ChaveAcesso = chaveAcesso,
            Serie = serie,
            NumeroNf = numeroNf,
            DhContingencia = dhContingencia,
            XmlAssinado = xmlFinalComSupl,
            QrCodeUrl = qrCodeUrl,
            DigestValue = digestValue,
            DanfeData = danfeData
        };
    }

    private string MontarXmlNfce(
        LocalNfceContingenciaRequest req,
        string chave,
        int numero,
        int serie,
        DateTimeOffset dhEmi,
        string justificativa,
        string cnf)
    {
        var sb = new StringBuilder();
        var cDv = chave[^1].ToString();
        var dhIso = dhEmi.ToString("yyyy-MM-ddTHH:mm:sszzz");
        var cscCnpj = LimparNaoDigitos(_options.EmitenteCnpj);

        sb.Append($"""<NFe xmlns="http://www.portalfiscal.inf.br/nfe"><infNFe Id="NFe{chave}" versao="4.00">""");
        sb.Append("<ide>");
        sb.Append($"<cUF>{_options.EmitenteCodigoUf}</cUF>");
        sb.Append($"<cNF>{cnf}</cNF>");
        sb.Append("<natOp>VENDA DE MERCADORIA</natOp>");
        sb.Append("<mod>65</mod>");
        sb.Append($"<serie>{serie}</serie>");
        sb.Append($"<nNF>{numero}</nNF>");
        sb.Append($"<dhEmi>{dhIso}</dhEmi>");
        sb.Append("<tpNF>1</tpNF>");
        sb.Append("<idDest>1</idDest>");
        sb.Append($"<cMunFG>{_options.EmitenteCodigoMunicipioIbge}</cMunFG>");
        sb.Append("<tpImp>4</tpImp>");
        sb.Append("<tpEmis>9</tpEmis>"); // Contingência offline NFC-e
        sb.Append($"<cDV>{cDv}</cDV>");
        sb.Append($"<tpAmb>{_options.AmbienteFiscal}</tpAmb>");
        sb.Append("<finNFe>1</finNFe>");
        sb.Append("<indFinal>1</indFinal>");
        sb.Append("<indPres>1</indPres>");
        sb.Append("<procEmi>0</procEmi>");
        sb.Append("<verProc>HorusGateway 1.0</verProc>");
        sb.Append($"<dhCont>{dhIso}</dhCont>");
        sb.Append($"<xJust>{EscapeXml(justificativa)}</xJust>");
        sb.Append("</ide>");

        sb.Append("<emit>");
        sb.Append($"<CNPJ>{cscCnpj}</CNPJ>");
        sb.Append($"<xNome>{EscapeXml(_options.EmitenteRazaoSocial)}</xNome>");
        if (!string.IsNullOrWhiteSpace(_options.EmitenteNomeFantasia))
            sb.Append($"<xFant>{EscapeXml(_options.EmitenteNomeFantasia)}</xFant>");
        sb.Append("<enderEmit>");
        sb.Append("<xLgr>RUA PRINCIPAL</xLgr><nro>100</nro><xBairro>CENTRO</xBairro>");
        sb.Append($"<cMun>{_options.EmitenteCodigoMunicipioIbge}</cMun>");
        sb.Append($"<xMun>LOCAL</xMun><UF>{_options.EmitenteUf}</UF><CEP>20000000</CEP>");
        sb.Append("</enderEmit>");
        sb.Append($"<IE>{LimparNaoDigitos(_options.EmitenteInscricaoEstadual)}</IE>");
        sb.Append($"<CRT>{_options.EmitenteCrt}</CRT>");
        sb.Append("</emit>");

        if (!string.IsNullOrWhiteSpace(req.CustomerCpf))
        {
            var cpfLimpo = LimparNaoDigitos(req.CustomerCpf);
            if (cpfLimpo.Length == 11)
            {
                sb.Append("<dest>");
                sb.Append($"<CPF>{cpfLimpo}</CPF>");
                if (!string.IsNullOrWhiteSpace(req.CustomerName))
                    sb.Append($"<xNome>{EscapeXml(req.CustomerName)}</xNome>");
                sb.Append("<indIEDest>9</indIEDest>");
                sb.Append("</dest>");
            }
        }

        decimal vProdTotal = 0;
        decimal vDescTotal = 0;

        for (int i = 0; i < req.Itens.Count; i++)
        {
            var item = req.Itens[i];
            var nItem = i + 1;
            vProdTotal += item.ValorTotal;
            vDescTotal += item.Desconto;

            sb.Append($"<det nItem=\"{nItem}\">");
            sb.Append("<prod>");
            sb.Append($"<cProd>{EscapeXml(item.CodigoProduto)}</cProd>");
            sb.Append("<cEAN>SEM GTIN</cEAN>");
            sb.Append($"<xProd>{EscapeXml(item.Descricao)}</xProd>");
            sb.Append($"<NCM>{(string.IsNullOrWhiteSpace(item.Ncm) ? "00000000" : item.Ncm)}</NCM>");
            sb.Append($"<CFOP>{(string.IsNullOrWhiteSpace(item.Cfop) ? "5102" : item.Cfop)}</CFOP>");
            sb.Append($"<uCom>{EscapeXml(item.UnidadeComercial)}</uCom>");
            sb.Append($"<qCom>{item.Quantidade.ToString("F4", CultureInfo.InvariantCulture)}</qCom>");
            sb.Append($"<vUnCom>{item.ValorUnitario.ToString("F4", CultureInfo.InvariantCulture)}</vUnCom>");
            sb.Append($"<vProd>{item.ValorTotal.ToString("F2", CultureInfo.InvariantCulture)}</vProd>");
            sb.Append("<cEANTrib>SEM GTIN</cEANTrib>");
            sb.Append($"<uTrib>{EscapeXml(item.UnidadeComercial)}</uTrib>");
            sb.Append($"<qTrib>{item.Quantidade.ToString("F4", CultureInfo.InvariantCulture)}</qTrib>");
            sb.Append($"<vUnTrib>{item.ValorUnitario.ToString("F4", CultureInfo.InvariantCulture)}</vUnTrib>");
            if (item.Desconto > 0)
                sb.Append($"<vDesc>{item.Desconto.ToString("F2", CultureInfo.InvariantCulture)}</vDesc>");
            sb.Append("<indTot>1</indTot>");
            sb.Append("</prod>");

            sb.Append("<imposto>");
            sb.Append("<ICMS><ICMSSN102><orig>0</orig><CSOSN>102</CSOSN></ICMSSN102></ICMS>");
            sb.Append("<PIS><PISNT><CST>07</CST></PISNT></PIS>");
            sb.Append("<COFINS><COFINSNT><CST>07</CST></COFINSNT></COFINS>");
            sb.Append("</imposto>");
            sb.Append("</det>");
        }

        var vNfTotal = Math.Max(0, vProdTotal - vDescTotal);

        sb.Append("<total><ICMSTot>");
        sb.Append("<vBC>0.00</vBC><vICMS>0.00</vICMS><vICMSDeson>0.00</vICMSDeson><vFCP>0.00</vFCP>");
        sb.Append("<vBCST>0.00</vBCST><vST>0.00</vST><vFCPST>0.00</vFCPST><vFCPSTRet>0.00</vFCPSTRet>");
        sb.Append($"<vProd>{vProdTotal.ToString("F2", CultureInfo.InvariantCulture)}</vProd>");
        sb.Append("<vFrete>0.00</vFrete><vSeg>0.00</vSeg>");
        sb.Append($"<vDesc>{vDescTotal.ToString("F2", CultureInfo.InvariantCulture)}</vDesc>");
        sb.Append("<vII>0.00</vII><vIPI>0.00</vIPI><vIPIDevol>0.00</vIPIDevol><vPIS>0.00</vPIS><vCOFINS>0.00</vCOFINS>");
        sb.Append("<vOutro>0.00</vOutro>");
        sb.Append($"<vNF>{vNfTotal.ToString("F2", CultureInfo.InvariantCulture)}</vNF>");
        sb.Append("</ICMSTot></total>");

        sb.Append("<transp><modFrete>9</modFrete></transp>");

        sb.Append("<pag>");
        if (req.Pagamentos.Count > 0)
        {
            foreach (var pag in req.Pagamentos)
            {
                sb.Append("<detPag>");
                sb.Append($"<tPag>{pag.Tipo}</tPag>");
                sb.Append($"<vPag>{pag.Valor.ToString("F2", CultureInfo.InvariantCulture)}</vPag>");
                sb.Append("</detPag>");
            }
        }
        else
        {
            sb.Append("<detPag><tPag>01</tPag>");
            sb.Append($"<vPag>{vNfTotal.ToString("F2", CultureInfo.InvariantCulture)}</vPag></detPag>");
        }
        if (req.ValorTroco > 0)
            sb.Append($"<vTroco>{req.ValorTroco.ToString("F2", CultureInfo.InvariantCulture)}</vTroco>");
        sb.Append("</pag>");

        sb.Append("</infNFe></NFe>");
        return sb.ToString();
    }

    private (string XmlAssinado, string DigestValue) AssinarXml(string xmlString, string chave, X509Certificate2 cert)
    {
        var doc = new XmlDocument { PreserveWhitespace = true };
        doc.LoadXml(xmlString);

        var signedXml = new SignedXml(doc)
        {
            SigningKey = cert.GetRSAPrivateKey() ?? throw new InvalidOperationException("Chave privada RSA não acessível no certificado.")
        };

        var reference = new Reference { Uri = $"#NFe{chave}" };
        reference.AddTransform(new XmlDsigEnvelopedSignatureTransform());
        reference.AddTransform(new XmlDsigC14NTransform());
        reference.DigestMethod = SignedXml.XmlDsigSHA1Url;

        signedXml.AddReference(reference);
        signedXml.SignedInfo!.CanonicalizationMethod = SignedXml.XmlDsigC14NTransformUrl;
        signedXml.SignedInfo!.SignatureMethod = SignedXml.XmlDsigRSASHA1Url;

        var keyInfo = new KeyInfo();
        keyInfo.AddClause(new KeyInfoX509Data(cert));
        signedXml.KeyInfo = keyInfo;

        signedXml.ComputeSignature();

        var xmlSignature = signedXml.GetXml();
        doc.DocumentElement!.AppendChild(doc.ImportNode(xmlSignature, true));

        // Extrai o DigestValue gerado na referência
        var digestValueNode = doc.GetElementsByTagName("DigestValue")[0];
        var digestValue = digestValueNode?.InnerText ?? string.Empty;

        return (doc.OuterXml, digestValue);
    }

    private string GerarQrCodeV2(string chaveAcesso, string digestValue)
    {
        var csc = _options.Csc ?? string.Empty;
        var cscIdNum = int.TryParse(_options.CscId, out var id) ? id.ToString() : "1";
        var tpAmb = _options.AmbienteFiscal.ToString();

        // Converte o DigestValue em hexadecimal
        byte[] digestBytes;
        try
        {
            digestBytes = Convert.FromBase64String(digestValue);
        }
        catch
        {
            digestBytes = Encoding.UTF8.GetBytes(digestValue);
        }
        var digestHex = Convert.ToHexString(digestBytes).ToLowerInvariant();

        // Parâmetros do QR-Code versão 2.0 em contingência offline:
        // p = chNFe|2|tpAmb|cIdToken|cHashQRCode
        // onde cHashQRCode = SHA1Hex(chNFe|2|tpAmb|cIdToken|digValHex + CSC)
        var stringParaHash = $"{chaveAcesso}|2|{tpAmb}|{cscIdNum}|{digestHex}{csc}";
        var hashBytes = SHA1.HashData(Encoding.UTF8.GetBytes(stringParaHash));
        var cHashQrCode = Convert.ToHexString(hashBytes).ToLowerInvariant();

        var baseUrl = ObterUrlQrCodeBase();
        return $"{baseUrl}?p={chaveAcesso}|2|{tpAmb}|{cscIdNum}|{cHashQrCode}";
    }

    private static string InserirInfNFeSupl(string xmlAssinado, string qrCodeUrl, string urlConsultaChave)
    {
        var doc = new XmlDocument { PreserveWhitespace = true };
        doc.LoadXml(xmlAssinado);

        var supl = doc.CreateElement("infNFeSupl", "http://www.portalfiscal.inf.br/nfe");
        var qrCodeElem = doc.CreateElement("qrCode", "http://www.portalfiscal.inf.br/nfe");
        var cdata = doc.CreateCDataSection(qrCodeUrl);
        qrCodeElem.AppendChild(cdata);
        supl.AppendChild(qrCodeElem);

        var urlChaveElem = doc.CreateElement("urlChave", "http://www.portalfiscal.inf.br/nfe");
        urlChaveElem.InnerText = urlConsultaChave;
        supl.AppendChild(urlChaveElem);

        doc.DocumentElement!.AppendChild(supl);
        return doc.OuterXml;
    }

    private string ObterUrlQrCodeBase()
    {
        // Padrão RJ (homologação vs produção); adaptável por UF
        return _options.AmbienteFiscal == 1
            ? "http://www.fazenda.rj.gov.br/nfce/qrcode"
            : "http://www.fazenda.rj.gov.br/nfce/qrcode";
    }

    private string ObterUrlConsultaChave()
    {
        return _options.AmbienteFiscal == 1
            ? "http://www.fazenda.rj.gov.br/consultaNFCe"
            : "http://www.fazenda.rj.gov.br/consultaNFCe";
    }

    public static string GerarChaveAcesso(
        byte uf, DateTimeOffset dataHora, string cnpj, short modelo, int serie, int numero, byte tipoEmissao, string codigoNumerico)
    {
        var cUfStr = uf.ToString("D2");
        var aamm = dataHora.ToString("yyMM");
        var cnpjLimpo = LimparNaoDigitos(cnpj).PadLeft(14, '0');
        var modStr = modelo.ToString("D2");
        var serieStr = serie.ToString("D3");
        var numStr = numero.ToString("D9");
        var tpEmisStr = tipoEmissao.ToString();
        var cNfStr = codigoNumerico.PadLeft(8, '0');

        var chaveSemDv = $"{cUfStr}{aamm}{cnpjLimpo}{modStr}{serieStr}{numStr}{tpEmisStr}{cNfStr}";
        var dv = CalcularDigitoVerificador(chaveSemDv);
        return $"{chaveSemDv}{dv}";
    }

    public static int CalcularDigitoVerificador(string chave43)
    {
        var peso = 2;
        var soma = 0;
        for (var i = chave43.Length - 1; i >= 0; i--)
        {
            soma += (chave43[i] - '0') * peso;
            peso = peso == 9 ? 2 : peso + 1;
        }

        var resto = soma % 11;
        return resto < 2 ? 0 : 11 - resto;
    }

    private static string LimparNaoDigitos(string? valor)
    {
        if (string.IsNullOrWhiteSpace(valor)) return string.Empty;
        var sb = new StringBuilder(valor.Length);
        foreach (var c in valor)
        {
            if (char.IsDigit(c)) sb.Append(c);
        }
        return sb.ToString();
    }

    private static string EscapeXml(string? texto)
    {
        if (string.IsNullOrWhiteSpace(texto)) return string.Empty;
        return texto.Replace("&", "&amp;")
                    .Replace("<", "&lt;")
                    .Replace(">", "&gt;")
                    .Replace("\"", "&quot;")
                    .Replace("'", "&apos;");
    }

    private static string MapearDescricaoPagamento(string tipo)
    {
        return tipo switch
        {
            "01" => "Dinheiro",
            "03" => "Cartão de Crédito",
            "04" => "Cartão de Débito",
            "17" or "20" => "PIX",
            _ => "Outros"
        };
    }
}
