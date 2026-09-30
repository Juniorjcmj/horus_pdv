/*
 * Arquivo: Models/FiscalModels.cs
 * Objetivo: DTOs e contratos para emissão de NFC-e em contingência offline (tpEmis = 9) no Local Gateway.
 */
using System.Text.Json.Serialization;

namespace HorusGateway.Models;

public sealed class LocalNfceContingenciaRequest
{
    public required string VendaId { get; set; }
    public required string TerminalId { get; set; }
    public string? CustomerCpf { get; set; }
    public string? CustomerName { get; set; }
    public string? Justificativa { get; set; }
    public List<LocalItemFiscal> Itens { get; set; } = [];
    public List<LocalPagamentoFiscal> Pagamentos { get; set; } = [];
    public decimal ValorTroco { get; set; }
}

public sealed class LocalItemFiscal
{
    public int Numero { get; set; }
    public required string CodigoProduto { get; set; }
    public required string Descricao { get; set; }
    public string Ncm { get; set; } = "00000000";
    public string Cfop { get; set; } = "5102";
    public string UnidadeComercial { get; set; } = "UN";
    public decimal Quantidade { get; set; }
    public decimal ValorUnitario { get; set; }
    public decimal ValorTotal { get; set; }
    public decimal Desconto { get; set; }
}

public sealed class LocalPagamentoFiscal
{
    /// <summary>tPag: 01 Dinheiro, 03 Crédito, 04 Débito, 17 PIX</summary>
    public required string Tipo { get; set; }
    public required decimal Valor { get; set; }
}

public sealed class LocalNfceContingenciaResponse
{
    public bool Success { get; set; }
    public string Message { get; set; } = string.Empty;
    public string DocumentoId { get; set; } = string.Empty;
    public string ChaveAcesso { get; set; } = string.Empty;
    public int Serie { get; set; }
    public int NumeroNf { get; set; }
    public DateTimeOffset DhContingencia { get; set; }
    public string XmlAssinado { get; set; } = string.Empty;
    public string QrCodeUrl { get; set; } = string.Empty;
    public string DigestValue { get; set; } = string.Empty;
    public DanfeThermalData? DanfeData { get; set; }
}

public sealed class DanfeThermalData
{
    public string RazaoSocial { get; set; } = string.Empty;
    public string NomeFantasia { get; set; } = string.Empty;
    public string Cnpj { get; set; } = string.Empty;
    public string InscricaoEstadual { get; set; } = string.Empty;
    public string Endereco { get; set; } = string.Empty;
    public string ChaveAcesso { get; set; } = string.Empty;
    public int Serie { get; set; }
    public int NumeroNf { get; set; }
    public string DhEmissao { get; set; } = string.Empty;
    public bool EmissaoContingencia { get; set; } = true;
    public string MensagemContingencia { get; set; } = "EMITIDA EM CONTINGÊNCIA - Pendente de autorização";
    public string Justificativa { get; set; } = string.Empty;
    public decimal Subtotal { get; set; }
    public decimal Desconto { get; set; }
    public decimal TotalLiquido { get; set; }
    public decimal ValorTroco { get; set; }
    public string? CustomerCpf { get; set; }
    public string? CustomerName { get; set; }
    public List<DanfeItemData> Itens { get; set; } = [];
    public List<DanfePagamentoData> Pagamentos { get; set; } = [];
    public string QrCodeUrl { get; set; } = string.Empty;
    public string UrlConsultaChave { get; set; } = string.Empty;
}

public sealed class DanfeItemData
{
    public int Numero { get; set; }
    public string Codigo { get; set; } = string.Empty;
    public string Descricao { get; set; } = string.Empty;
    public decimal Quantidade { get; set; }
    public string Unidade { get; set; } = "UN";
    public decimal ValorUnitario { get; set; }
    public decimal ValorTotal { get; set; }
}

public sealed class DanfePagamentoData
{
    public string Forma { get; set; } = string.Empty;
    public decimal Valor { get; set; }
}

public sealed class GatewayFiscalStatusResponse
{
    public bool CertificadoConfigurado { get; set; }
    public bool CertificadoValido { get; set; }
    public string? CertificadoSubject { get; set; }
    public DateTime? CertificadoValidoAte { get; set; }
    public int DiasRestantesCertificado { get; set; }
    public bool CscConfigurado { get; set; }
    public int SerieContingencia { get; set; }
    public int UltimoNumeroEmitido { get; set; }
    public int TotalNotasEmitidasOffline { get; set; }
}
