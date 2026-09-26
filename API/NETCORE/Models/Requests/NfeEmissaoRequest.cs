/**
 * Arquivo: API/NETCORE/Models/Requests/NfeEmissaoRequest.cs
 * Objetivo: DTO de requisição para emissão de NF-e modelo 55 pelo frontend.
 */
namespace HORUSPDV_API.Models.Requests;

public class EmitirNfeHttpRequest
{
    /// <summary>Número da venda já registrada no sistema (ex.: "000001").</summary>
    public string SaleNumber { get; set; } = string.Empty;

    /// <summary>Dados do destinatário (empresa compradora).</summary>
    public NfeDestinatarioRequest Destinatario { get; set; } = new();

    /// <summary>Natureza da operação (ex.: VENDA DE MERCADORIA).</summary>
    public string NaturezaOperacao { get; set; } = "VENDA DE MERCADORIA";

    /// <summary>0 sem frete, 1 emitente, 2 destinatário, 9 sem transporte.</summary>
    public byte ModalidadeFrete { get; set; } = 9;
}

public class NfeDestinatarioRequest
{
    public string CpfCnpj { get; set; } = string.Empty;
    public string Nome { get; set; } = string.Empty;

    /// <summary>1 contribuinte ICMS, 2 isento, 9 não contribuinte.</summary>
    public byte IndIeDest { get; set; } = 9;
    public string? InscricaoEstadual { get; set; }

    // Endereço obrigatório para NF-e
    public string Logradouro { get; set; } = string.Empty;
    public string Numero { get; set; } = "S/N";
    public string? Complemento { get; set; }
    public string Bairro { get; set; } = string.Empty;
    public string CodigoMunicipioIbge { get; set; } = string.Empty;
    public string NomeMunicipio { get; set; } = string.Empty;
    public string Uf { get; set; } = string.Empty;
    public string Cep { get; set; } = string.Empty;
    public string? Fone { get; set; }
    public string? Email { get; set; }
}

public class DevolverNfceRequest
{
    /// <summary>ID do supervisor/gerente que autoriza a devolução.</summary>
    public string SupervisorId { get; set; } = string.Empty;

    /// <summary>Senha do supervisor/gerente.</summary>
    public string SupervisorPassword { get; set; } = string.Empty;

    /// <summary>Justificativa do estorno/devolução (mínimo 15 caracteres).</summary>
    public string Justificativa { get; set; } = string.Empty;

    /// <summary>Dados opcionais do consumidor. Se nulo ou vazio, emite como Entrada Própria.</summary>
    public NfeDestinatarioRequest? Destinatario { get; set; }
}

