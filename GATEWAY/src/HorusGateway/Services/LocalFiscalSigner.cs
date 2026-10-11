using System.Globalization;
using System.Security.Cryptography;
using System.Security.Cryptography.Xml;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Xml;
using System.Xml.Linq;
using HorusGateway.Configuration;
using HorusGateway.Models;
using Microsoft.Extensions.Options;
using static HorusGateway.Services.LocalFiscalTaxRules;

namespace HorusGateway.Services;

/// <summary>Builds and validates real fiscal data before returning a signed model 65 contingency.</summary>
public sealed class LocalFiscalSigner(GatewayCertificateService certificateService, IOptions<GatewayOptions> options,
    ILogger<LocalFiscalSigner> logger)
{
    private readonly GatewayOptions _options = options.Value;
    private const string HomologationDescription = "NOTA FISCAL EMITIDA EM AMBIENTE DE HOMOLOGACAO - SEM VALOR FISCAL";

    public LocalNfceContingenciaResponse EmitirContingencia(LocalNfceContingenciaRequest request, int numeroNf,
        int serie, DateTimeOffset dhContingencia)
    {
        try
        {
            // Access-key month, QR day and XML use the same RJ civil time.
            var emission = dhContingencia.ToOffset(TimeSpan.FromHours(-3));
            var issuer = ValidateRequest(request, numeroNf, serie);
            using var cert = certificateService.CarregarCertificado();
            if (cert is null || !cert.HasPrivateKey || DateTime.UtcNow < cert.NotBefore.ToUniversalTime() || DateTime.UtcNow > cert.NotAfter.ToUniversalTime())
                throw new InvalidOperationException("Certificado digital A1 não configurado, sem chave privada ou fora da vigência no Gateway.");
            var justification = request.Justificativa?.Trim();
            if (string.IsNullOrWhiteSpace(justification) || justification.Length < 15)
                justification = "EMISSAO EM CONTINGENCIA OFFLINE POR INDISPONIBILIDADE DE REDE";
            if (justification.Length > 256) throw new InvalidOperationException("Justificativa de contingência deve ter entre 15 e 256 caracteres.");
            string cnf;
            do { cnf = RandomNumberGenerator.GetInt32(1, 100000000).ToString("D8", CultureInfo.InvariantCulture); }
            while (cnf == numeroNf.ToString("D8", CultureInfo.InvariantCulture));
            var key = GerarChaveAcesso(_options.EmitenteCodigoUf, emission, issuer.Cnpj, 65, serie, numeroNf, 9, cnf);
            var xml = BuildXml(request, issuer, key, numeroNf, serie, emission, justification, cnf);
            var (signedXml, digest) = Sign(xml, key, cert);
            var total = request.Itens.Sum(i => i.ValorTotal - i.Desconto);
            var qr = GerarQrCodeV2(key, digest, emission, total, _options.AmbienteFiscal, _options.CscId, _options.Csc);
            var finalXml = AddSupplement(signedXml, qr);
            LocalFiscalSchemaValidator.Validate(finalXml);
            var parsed = XDocument.Parse(finalXml);
            var danfe = new DanfeThermalData
            {
                RazaoSocial = issuer.RazaoSocial, NomeFantasia = string.IsNullOrWhiteSpace(issuer.NomeFantasia) ? issuer.RazaoSocial : issuer.NomeFantasia,
                Cnpj = issuer.Cnpj, InscricaoEstadual = issuer.InscricaoEstadual,
                Endereco = $"{issuer.Logradouro}, {issuer.Numero}{(string.IsNullOrWhiteSpace(issuer.Complemento) ? "" : " " + issuer.Complemento)} - {issuer.Bairro}, {issuer.Municipio}/{issuer.Uf} - CEP {issuer.Cep}",
                ChaveAcesso = key, Serie = serie, NumeroNf = numeroNf, DhEmissao = emission.ToString("dd/MM/yyyy HH:mm:ss", CultureInfo.InvariantCulture),
                Justificativa = justification, Subtotal = request.Itens.Sum(i => i.ValorTotal), Desconto = request.Itens.Sum(i => i.Desconto),
                TotalLiquido = total, ValorTroco = request.ValorTroco, CustomerCpf = request.CustomerCpf, CustomerName = request.CustomerName,
                Itens = parsed.Descendants(Ns + "det").Select(d =>
                {
                    var prod = d.Element(Ns + "prod")!;
                    return new DanfeItemData { Numero = int.Parse(d.Attribute("nItem")!.Value, CultureInfo.InvariantCulture),
                        Codigo = prod.Element(Ns + "cProd")!.Value, Descricao = prod.Element(Ns + "xProd")!.Value,
                        Quantidade = Value(prod, "qCom"), Unidade = prod.Element(Ns + "uCom")!.Value,
                        ValorUnitario = Value(prod, "vUnCom"), ValorTotal = Value(prod, "vProd") };
                }).ToList(),
                Pagamentos = request.Pagamentos.Select(p => new DanfePagamentoData { Forma = PaymentDescription(p.Tipo), Valor = p.Valor }).ToList(),
                QrCodeUrl = qr, UrlConsultaChave = "http://www.fazenda.rj.gov.br/consultaNFCe"
            };
            return new LocalNfceContingenciaResponse
            {
                Success = true, Message = "NFC-e assinada e validada em contingência; autorização da SEFAZ pendente.",
                DocumentoId = $"gw-nfce-{Guid.NewGuid():N}", ChaveAcesso = key, Serie = serie, NumeroNf = numeroNf,
                DhContingencia = emission, Ambiente = _options.AmbienteFiscal, XmlAssinado = finalXml, QrCodeUrl = qr, DigestValue = digest, DanfeData = danfe
            };
        }
        catch (Exception ex) when (ex is InvalidOperationException or XmlException or System.Xml.Schema.XmlSchemaException or CryptographicException or IOException or System.Text.Json.JsonException)
        {
            logger.LogWarning("Emissão local da NFC-e interrompida: {Tipo}. Confira a configuração fiscal.", ex.GetType().Name);
            return new LocalNfceContingenciaResponse { Success = false, Message = ex is InvalidOperationException ? ex.Message : "Não foi possível assinar e validar a NFC-e. Confira o certificado e o pacote fiscal do Gateway." };
        }
    }

    private LocalEmitenteFiscal ValidateRequest(LocalNfceContingenciaRequest request, int numero, int serie)
    {
        if (_options.AmbienteFiscal is not (1 or 2) || _options.EmitenteCrt is not (1 or 2 or 3 or 4))
            throw new InvalidOperationException("Ambiente ou regime tributário do Gateway inválido.");
        if (_options.EmitenteCodigoUf != 33 || _options.EmitenteUf != "RJ")
            throw new InvalidOperationException("Esta configuração fiscal local atende somente emitentes do Rio de Janeiro (RJ/33).");
        if (!ValidDocument(Digits(_options.EmitenteCnpj), 14)) throw new InvalidOperationException("CNPJ vinculado ao Gateway não informado ou inválido.");
        if (serie is < 1 or > 999 || numero is < 1 or > 999999999)
            throw new InvalidOperationException("Série ou número da NFC-e fora do intervalo permitido.");
        if (string.IsNullOrWhiteSpace(_options.Csc) || !int.TryParse(_options.CscId, out var cscId) || cscId is < 1 or > 999999)
            throw new InvalidOperationException("CSC e identificador do CSC devem estar configurados no Gateway.");
        if (string.IsNullOrWhiteSpace(request.VendaId) || string.IsNullOrWhiteSpace(request.TerminalId) || request.Itens is null || request.Itens.Count is < 1 or > 990)
            throw new InvalidOperationException("Venda, terminal e entre 1 e 990 itens são obrigatórios.");
        var issuer = ResolveIssuer(request.Emitente);
        foreach (var item in request.Itens)
        {
            RequireText(item.CodigoProduto, "Código do produto", 60); RequireText(item.Descricao, "Descrição do produto", 120);
            if (!Code(item.Ncm, 8) || item.Ncm == "00000000") throw new InvalidOperationException($"{item.Descricao}: NCM real de oito dígitos é obrigatório.");
            if (!string.IsNullOrWhiteSpace(item.Cest) && !Code(item.Cest, 7)) throw new InvalidOperationException($"{item.Descricao}: CEST deve ter sete dígitos.");
            if (!Code(item.Cfop, 4) || !item.Cfop.StartsWith('5')) throw new InvalidOperationException($"{item.Descricao}: CFOP de venda interna deve ser informado.");
            RequireText(item.UnidadeComercial, "Unidade comercial", 6);
            if (!string.IsNullOrWhiteSpace(item.UnidadeTributavel) && item.UnidadeTributavel != item.UnidadeComercial)
                throw new InvalidOperationException($"{item.Descricao}: unidades comercial e tributável diferentes exigem conversão não disponível nesta emissão local.");
            ValidateGtin(item.Gtin);
            if (!string.IsNullOrWhiteSpace(item.GtinTributavel)) ValidateGtin(item.GtinTributavel);
            if (item.Quantidade <= 0 || decimal.Round(item.Quantidade, 4) != item.Quantidade || item.ValorUnitario < 0 ||
                decimal.Round(item.ValorUnitario, 10) != item.ValorUnitario || item.ValorTotal <= 0 || Money(item.ValorTotal) != item.ValorTotal ||
                Money(item.Quantidade * item.ValorUnitario) != item.ValorTotal || item.Desconto < 0 || item.Desconto > item.ValorTotal || Money(item.Desconto) != item.Desconto)
                throw new InvalidOperationException($"{item.Descricao}: quantidade, valor do item ou desconto inconsistente.");
        }
        if (!string.IsNullOrWhiteSpace(request.CustomerCpf) && !ValidDocument(Digits(request.CustomerCpf), Digits(request.CustomerCpf).Length))
            throw new InvalidOperationException("CPF/CNPJ do consumidor inválido.");
        if (request.CustomerName?.Length > 60) throw new InvalidOperationException("Nome do consumidor deve ter até 60 caracteres.");
        if (request.Pagamentos is null || request.Pagamentos.Count == 0) throw new InvalidOperationException("Informe os pagamentos reais da venda.");
        foreach (var p in request.Pagamentos)
            if (p.Tipo is not ("01" or "03" or "04" or "05" or "10" or "11" or "12" or "13" or "15" or "16" or "17" or "18" or "19" or "20" or "21") || p.Valor <= 0 || Money(p.Valor) != p.Valor)
                throw new InvalidOperationException("Pagamento ausente, inválido ou não suportado na contingência local.");
        var total = request.Itens.Sum(i => i.ValorTotal - i.Desconto);
        if (total <= 0 || request.ValorTroco < 0 || Money(request.ValorTroco) != request.ValorTroco ||
            request.ValorTroco > request.Pagamentos.Where(p => p.Tipo == "01").Sum(p => p.Valor) || request.Pagamentos.Sum(p => p.Valor) - request.ValorTroco != total)
            throw new InvalidOperationException("Pagamentos menos troco devem conferir com o total da venda; troco só pode sair do valor recebido em dinheiro.");
        return issuer;
    }

    private LocalEmitenteFiscal ResolveIssuer(LocalEmitenteFiscal? cached)
    {
        var configured = new LocalEmitenteFiscal
        {
            Cnpj = Digits(_options.EmitenteCnpj), RazaoSocial = _options.EmitenteRazaoSocial, NomeFantasia = _options.EmitenteNomeFantasia,
            InscricaoEstadual = Digits(_options.EmitenteInscricaoEstadual), Logradouro = _options.EmitenteLogradouro, Numero = _options.EmitenteNumero,
            Complemento = _options.EmitenteComplemento, Bairro = _options.EmitenteBairro, Municipio = _options.EmitenteMunicipio,
            CodigoMunicipioIbge = _options.EmitenteCodigoMunicipioIbge, Uf = _options.EmitenteUf, Cep = Digits(_options.EmitenteCep), Fone = Digits(_options.EmitenteFone)
        };
        if (cached is not null && Digits(cached.Cnpj) != configured.Cnpj) throw new InvalidOperationException("Emitente em cache pertence a outro CNPJ; não pode ser usado por este Gateway.");
        var complete = new[] { configured.RazaoSocial, configured.InscricaoEstadual, configured.Logradouro, configured.Numero,
            configured.Bairro, configured.Municipio, configured.CodigoMunicipioIbge, configured.Cep }.All(v => !string.IsNullOrWhiteSpace(v));
        // Choose a complete source; never splice addresses from separate company records.
        var issuer = complete ? configured : cached ?? throw new InvalidOperationException("Cadastro completo do emitente indisponível no Gateway e no cache do terminal.");
        if (Digits(issuer.Cnpj) != configured.Cnpj || issuer.Uf != _options.EmitenteUf || !Code(issuer.CodigoMunicipioIbge, 7) || !issuer.CodigoMunicipioIbge.StartsWith("33", StringComparison.Ordinal))
            throw new InvalidOperationException("CNPJ, UF ou município do emitente não conferem com o Gateway do RJ.");
        if (!complete && !string.IsNullOrWhiteSpace(configured.InscricaoEstadual) && configured.InscricaoEstadual != Digits(issuer.InscricaoEstadual))
            throw new InvalidOperationException("Inscrição Estadual do emitente em cache não confere com a configurada no Gateway.");
        RequireText(issuer.RazaoSocial, "Razão social", 60); RequireText(issuer.Logradouro, "Logradouro", 60);
        RequireText(issuer.Numero, "Número do endereço", 60); RequireText(issuer.Bairro, "Bairro", 60); RequireText(issuer.Municipio, "Município", 60);
        if (!Code(Digits(issuer.InscricaoEstadual), Digits(issuer.InscricaoEstadual).Length) || Digits(issuer.InscricaoEstadual).Length is < 2 or > 14 || !Code(Digits(issuer.Cep), 8))
            throw new InvalidOperationException("Inscrição Estadual e CEP reais do emitente são obrigatórios.");
        if (issuer.NomeFantasia?.Length > 60 || issuer.Complemento?.Length > 60 || (!string.IsNullOrWhiteSpace(issuer.Fone) && Digits(issuer.Fone).Length is < 6 or > 14))
            throw new InvalidOperationException("Nome fantasia, complemento ou telefone do emitente inválido.");
        return issuer;
    }

    private string BuildXml(LocalNfceContingenciaRequest req, LocalEmitenteFiscal issuer, string key, int number, int series,
        DateTimeOffset date, string justification, string cnf)
    {
        var details = req.Itens.Select((item, index) => new XElement(Ns + "det", new XAttribute("nItem", index + 1),
            new XElement(Ns + "prod", E("cProd", item.CodigoProduto), E("cEAN", item.Gtin),
                E("xProd", _options.AmbienteFiscal == 2 && index == 0 ? HomologationDescription : item.Descricao), E("NCM", item.Ncm),
                string.IsNullOrWhiteSpace(item.Cest) ? null : E("CEST", item.Cest), E("CFOP", item.Cfop), E("uCom", item.UnidadeComercial),
                E("qCom", item.Quantidade.ToString("F4", CultureInfo.InvariantCulture)), E("vUnCom", item.ValorUnitario.ToString("F10", CultureInfo.InvariantCulture)),
                E("vProd", MoneyText(item.ValorTotal)), E("cEANTrib", string.IsNullOrWhiteSpace(item.GtinTributavel) ? item.Gtin : item.GtinTributavel),
                E("uTrib", item.UnidadeComercial), E("qTrib", item.Quantidade.ToString("F4", CultureInfo.InvariantCulture)),
                E("vUnTrib", item.ValorUnitario.ToString("F10", CultureInfo.InvariantCulture)), item.Desconto > 0 ? E("vDesc", MoneyText(item.Desconto)) : null, E("indTot", 1)),
            Build(item, _options.EmitenteCrt, date))).ToArray();
        var taxes = details.Select(d => d.Element(Ns + "imposto")!).ToArray();
        var normalIcms = taxes.Select(t => t.Element(Ns + "ICMS")?.Element(Ns + "ICMS00")).OfType<XElement>().ToArray();
        var address = new XElement(Ns + "enderEmit", E("xLgr", issuer.Logradouro), E("nro", issuer.Numero),
            string.IsNullOrWhiteSpace(issuer.Complemento) ? null : E("xCpl", issuer.Complemento), E("xBairro", issuer.Bairro),
            E("cMun", issuer.CodigoMunicipioIbge), E("xMun", issuer.Municipio), E("UF", issuer.Uf), E("CEP", Digits(issuer.Cep)),
            E("cPais", 1058), E("xPais", "BRASIL"), string.IsNullOrWhiteSpace(issuer.Fone) ? null : E("fone", Digits(issuer.Fone)));
        XElement? dest = null;
        if (!string.IsNullOrWhiteSpace(req.CustomerCpf))
        {
            var document = Digits(req.CustomerCpf);
            dest = new XElement(Ns + "dest", E(document.Length == 11 ? "CPF" : "CNPJ", document),
                _options.AmbienteFiscal == 2 ? E("xNome", "NF-E EMITIDA EM AMBIENTE DE HOMOLOGACAO - SEM VALOR FISCAL") : string.IsNullOrWhiteSpace(req.CustomerName) ? null : E("xNome", req.CustomerName), E("indIEDest", 9));
        }
        var total = req.Itens.Sum(i => i.ValorTotal - i.Desconto);
        var icmsTotals = new XElement(Ns + "ICMSTot", E("vBC", MoneyText(normalIcms.Sum(i => Value(i, "vBC")))),
            E("vICMS", MoneyText(normalIcms.Sum(i => Value(i, "vICMS")))), E("vICMSDeson", "0.00"), E("vFCP", "0.00"),
            E("vBCST", "0.00"), E("vST", "0.00"), E("vFCPST", "0.00"), E("vFCPSTRet", "0.00"),
            E("vProd", MoneyText(req.Itens.Sum(i => i.ValorTotal))), E("vFrete", "0.00"), E("vSeg", "0.00"), E("vDesc", MoneyText(req.Itens.Sum(i => i.Desconto))),
            E("vII", "0.00"), E("vIPI", "0.00"), E("vIPIDevol", "0.00"), E("vPIS", "0.00"), E("vCOFINS", "0.00"), E("vOutro", "0.00"), E("vNF", MoneyText(total)));
        var inf = new XElement(Ns + "infNFe", new XAttribute("Id", "NFe" + key), new XAttribute("versao", "4.00"),
            new XElement(Ns + "ide", E("cUF", _options.EmitenteCodigoUf), E("cNF", cnf), E("natOp", "VENDA DE MERCADORIA"), E("mod", 65),
                E("serie", series), E("nNF", number), E("dhEmi", date.ToString("yyyy-MM-ddTHH:mm:sszzz", CultureInfo.InvariantCulture)),
                E("tpNF", 1), E("idDest", 1), E("cMunFG", issuer.CodigoMunicipioIbge), E("tpImp", 4), E("tpEmis", 9), E("cDV", key[^1].ToString()),
                E("tpAmb", _options.AmbienteFiscal), E("finNFe", 1), E("indFinal", 1), E("indPres", 1), E("procEmi", 0), E("verProc", "HorusGateway 1.0"),
                E("dhCont", date.ToString("yyyy-MM-ddTHH:mm:sszzz", CultureInfo.InvariantCulture)), E("xJust", justification)),
            new XElement(Ns + "emit", E("CNPJ", Digits(issuer.Cnpj)), E("xNome", issuer.RazaoSocial),
                string.IsNullOrWhiteSpace(issuer.NomeFantasia) ? null : E("xFant", issuer.NomeFantasia), address, E("IE", Digits(issuer.InscricaoEstadual)), E("CRT", _options.EmitenteCrt)),
            dest, details, new XElement(Ns + "total", icmsTotals, IbsTotals(taxes)), E("transp", E("modFrete", 9)),
            new XElement(Ns + "pag", req.Pagamentos.Select(p => new XElement(Ns + "detPag", E("tPag", p.Tipo), E("vPag", MoneyText(p.Valor)),
                p.Tipo is "03" or "04" or "17" ? E("card", E("tpIntegra", 2)) : null)), req.ValorTroco > 0 ? E("vTroco", MoneyText(req.ValorTroco)) : null));
        return new XDocument(new XElement(Ns + "NFe", inf)).ToString(SaveOptions.DisableFormatting);
    }

    private static (string Xml, string Digest) Sign(string xml, string key, X509Certificate2 cert)
    {
        var doc = new XmlDocument { PreserveWhitespace = true, XmlResolver = null }; doc.LoadXml(xml);
        var signed = new SignedXml(doc) { SigningKey = cert.GetRSAPrivateKey() ?? throw new InvalidOperationException("Chave privada RSA não acessível no certificado.") };
        var reference = new Reference { Uri = "#NFe" + key, DigestMethod = SignedXml.XmlDsigSHA1Url };
        reference.AddTransform(new XmlDsigEnvelopedSignatureTransform()); reference.AddTransform(new XmlDsigC14NTransform()); signed.AddReference(reference);
        signed.SignedInfo!.CanonicalizationMethod = SignedXml.XmlDsigC14NTransformUrl; signed.SignedInfo.SignatureMethod = SignedXml.XmlDsigRSASHA1Url;
        var info = new KeyInfo(); info.AddClause(new KeyInfoX509Data(cert)); signed.KeyInfo = info; signed.ComputeSignature();
        doc.DocumentElement!.AppendChild(doc.ImportNode(signed.GetXml(), true));
        return (doc.OuterXml, doc.GetElementsByTagName("DigestValue", SignedXml.XmlDsigNamespaceUrl)[0]!.InnerText);
    }

    /// <summary>Official v2 offline: key|2|environment|DD|total|hex(UTF8(base64Digest))|CSC id|SHA1.</summary>
    public static string GerarQrCodeV2(string key, string digest, DateTimeOffset emission, decimal total, byte environment, string cscId, string csc)
    {
        if (Convert.FromBase64String(digest).Length != 20 || digest.Length != 28) throw new InvalidOperationException("Digest SHA-1 da assinatura inválido.");
        if (!int.TryParse(cscId, out var token) || token is < 1 or > 999999 || string.IsNullOrWhiteSpace(csc)) throw new InvalidOperationException("CSC/identificador não configurados.");
        var digestHex = Convert.ToHexString(Encoding.UTF8.GetBytes(digest)).ToLowerInvariant();
        var parameters = $"{key}|2|{environment}|{emission:dd}|{MoneyText(total)}|{digestHex}|{token}";
        var hash = Convert.ToHexString(SHA1.HashData(Encoding.UTF8.GetBytes(parameters + csc)));
        return "http://www.fazenda.rj.gov.br/nfce/qrcode?p=" + parameters + "|" + hash;
    }

    private static string AddSupplement(string xml, string qr)
    {
        var doc = new XmlDocument { PreserveWhitespace = true, XmlResolver = null }; doc.LoadXml(xml);
        var supl = doc.CreateElement("infNFeSupl", Ns.NamespaceName);
        var code = doc.CreateElement("qrCode", Ns.NamespaceName); code.AppendChild(doc.CreateCDataSection(qr)); supl.AppendChild(code);
        var url = doc.CreateElement("urlChave", Ns.NamespaceName); url.InnerText = "http://www.fazenda.rj.gov.br/consultaNFCe"; supl.AppendChild(url);
        var signature = doc.GetElementsByTagName("Signature", SignedXml.XmlDsigNamespaceUrl)[0]!;
        doc.DocumentElement!.InsertBefore(supl, signature); return doc.OuterXml;
    }

    public static string GerarChaveAcesso(byte uf, DateTimeOffset dataHora, string cnpj, short modelo, int serie, int numero, byte tipoEmissao, string codigoNumerico)
    {
        var value = $"{uf:D2}{dataHora:yyMM}{Digits(cnpj)}{modelo:D2}{serie:D3}{numero:D9}{tipoEmissao}{codigoNumerico.PadLeft(8, '0')}";
        if (value.Length != 43 || !value.All(char.IsAsciiDigit)) throw new InvalidOperationException("Dados inválidos para compor a chave de acesso.");
        return value + CalcularDigitoVerificador(value);
    }
    public static int CalcularDigitoVerificador(string chave43)
    {
        if (chave43.Length != 43 || !chave43.All(char.IsAsciiDigit)) throw new InvalidOperationException("A chave sem dígito verificador deve ter 43 dígitos.");
        var sum = 0; var weight = 2;
        for (var i = chave43.Length - 1; i >= 0; i--) { sum += (chave43[i] - '0') * weight; weight = weight == 9 ? 2 : weight + 1; }
        return sum % 11 < 2 ? 0 : 11 - sum % 11;
    }
    private static string Digits(string? value) => new((value ?? "").Where(char.IsAsciiDigit).ToArray());
    private static bool Code(string? value, int length) => value?.Length == length && length > 0 && value.All(char.IsAsciiDigit);
    private static void RequireText(string? value, string field, int max)
    { if (string.IsNullOrWhiteSpace(value) || value.Length > max) throw new InvalidOperationException($"{field} é obrigatório e deve ter até {max} caracteres."); }
    private static void ValidateGtin(string? gtin)
    {
        if (gtin == "SEM GTIN") return;
        if (gtin is null || gtin.Length is not (8 or 12 or 13 or 14) || !gtin.All(char.IsAsciiDigit) || gtin.All(c => c == '0')) throw new InvalidOperationException("Informe GTIN válido ou SEM GTIN explicitamente no cadastro.");
        var sum = 0; var weight = 3;
        for (var i = gtin.Length - 2; i >= 0; i--) { sum += (gtin[i] - '0') * weight; weight = weight == 3 ? 1 : 3; }
        if ((10 - sum % 10) % 10 != gtin[^1] - '0') throw new InvalidOperationException("Dígito verificador do GTIN inválido.");
    }
    private static bool ValidDocument(string digits, int length)
    {
        if (length is not (11 or 14) || digits.Length != length || !digits.All(char.IsAsciiDigit) || digits.Distinct().Count() == 1) return false;
        for (var n = length - 2; n < length; n++)
        {
            var sum = 0; var weight = length == 11 ? n + 1 : n - 7;
            for (var i = 0; i < n; i++) { sum += (digits[i] - '0') * weight; weight--; if (length == 14 && weight == 1) weight = 9; }
            var digit = sum % 11 < 2 ? 0 : 11 - sum % 11;
            if (digit != digits[n] - '0') return false;
        }
        return true;
    }
    private static string PaymentDescription(string type) => type switch
    { "01" => "Dinheiro", "03" => "Cartão de Crédito", "04" => "Cartão de Débito", "17" or "20" => "PIX", "05" => "Crédito da loja", _ => "Outros" };
}
