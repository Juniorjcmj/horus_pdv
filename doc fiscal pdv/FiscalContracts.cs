/**
 * Arquivo: API/NETCORE/Services/Fiscal/FiscalContracts.cs
 * Objetivo: define o contrato de emissão de documento fiscal e os tipos de apoio,
 *           isolando o resto da aplicação da biblioteca emissora escolhida.
 * Entradas esperadas: recebe dados já consolidados da venda e devolve o resultado da SEFAZ.
 *
 * A implementação atual é ZeusFiscalProvider (ZeusFiscal / DFe.NET, in-process).
 * Nada fora desta pasta deve referenciar tipos da biblioteca — se um dia trocarmos
 * por um gateway SaaS, só muda o registro no Program.cs.
 */

namespace HORUSPDV_API.Services.Fiscal;

public enum StatusDocumentoFiscal
{
    Rascunho = 0,
    Assinado = 1,
    Transmitindo = 2,
    Autorizado = 3,
    Rejeitado = 4,
    Denegado = 5,
    Cancelado = 6,
    Inutilizado = 7,
    ContingenciaPendente = 8
}

public enum TipoEmissaoFiscal
{
    Normal = 1,
    ContingenciaOffline = 9
}

public interface IFiscalProvider
{
    /// <summary>
    /// Monta, assina e transmite a NFC-e. Bloqueante por natureza — a biblioteca
    /// não expõe API assíncrona. Chame apenas a partir do NfceOutboxWorker,
    /// nunca da thread de requisição HTTP.
    /// </summary>
    Task<ResultadoFiscal> EmitirNfceAsync(EmissaoNfceRequest request, CancellationToken ct = default);

    Task<ResultadoFiscal> CancelarAsync(CancelamentoRequest request, CancellationToken ct = default);

    Task<ResultadoFiscal> InutilizarAsync(InutilizacaoRequest request, CancellationToken ct = default);

    /// <summary>Consulta o status do serviço da SEFAZ. Usado para decidir entrada e saída de contingência.</summary>
    Task<bool> ServicoDisponivelAsync(ContextoEmitente emitente, CancellationToken ct = default);
}

/// <summary>Resultado normalizado. Nunca vaza tipo da biblioteca emissora.</summary>
public sealed record ResultadoFiscal
{
    public required StatusDocumentoFiscal Status { get; init; }
    public int CodigoStatus { get; init; }
    public string MotivoStatus { get; init; } = string.Empty;
    public string? ChaveAcesso { get; init; }
    public string? Protocolo { get; init; }
    public DateTimeOffset? DhAutorizacao { get; init; }
    public string? XmlAssinado { get; init; }
    public string? XmlProtocolado { get; init; }

    /// <summary>
    /// True quando o erro é transitório (timeout, serviço fora, rejeição 108/109).
    /// O worker reagenda; rejeição de conteúdo não é retentável e vai para revisão manual.
    /// </summary>
    public bool Retentavel { get; init; }
}

/// <summary>Dados do emitente já descriptografados e prontos para uso.</summary>
public sealed record ContextoEmitente
{
    public required string CompanyId { get; init; }
    public required string Cnpj { get; init; }
    public required string InscricaoEstadual { get; init; }
    public required string RazaoSocial { get; init; }
    public required string NomeFantasia { get; init; }
    public required byte Crt { get; init; }
    public required string Cnae { get; init; }

    public required string Logradouro { get; init; }
    public required string Numero { get; init; }
    public string Complemento { get; init; } = string.Empty;
    public required string Bairro { get; init; }
    public required string CodigoMunicipioIbge { get; init; }
    public required string NomeMunicipio { get; init; }
    public required string Uf { get; init; }
    public required string Cep { get; init; }
    public string Fone { get; init; } = string.Empty;

    /// <summary>1 = Produção, 2 = Homologação.</summary>
    public required byte Ambiente { get; init; }
    public required string CscId { get; init; }
    public required string Csc { get; init; }

    public required byte[] CertificadoPfx { get; init; }
    public required string CertificadoSenha { get; init; }

    public string RespTecCnpj { get; init; } = string.Empty;
    public string RespTecContato { get; init; } = string.Empty;
    public string RespTecEmail { get; init; } = string.Empty;
    public string RespTecFone { get; init; } = string.Empty;
}

public sealed record EmissaoNfceRequest
{
    public required ContextoEmitente Emitente { get; init; }
    public required int Serie { get; init; }
    public required int NumeroNf { get; init; }
    public required TipoEmissaoFiscal TipoEmissao { get; init; }
    public DateTimeOffset? DhContingencia { get; init; }
    public string? JustificativaContingencia { get; init; }

    public DestinatarioFiscal? Destinatario { get; init; }
    public required IReadOnlyList<ItemFiscal> Itens { get; init; }
    public required IReadOnlyList<PagamentoFiscal> Pagamentos { get; init; }
    public decimal ValorTroco { get; init; }
}

public sealed record DestinatarioFiscal
{
    public string? CpfCnpj { get; init; }
    public string? Nome { get; init; }
    /// <summary>1 contribuinte, 2 isento, 9 não contribuinte.</summary>
    public byte IndIeDest { get; init; } = 9;
    public string? InscricaoEstadual { get; init; }
}

public sealed record ItemFiscal
{
    public required int Numero { get; init; }
    public required string CodigoProduto { get; init; }
    public required string Descricao { get; init; }
    public required string Gtin { get; init; }
    public required string Ncm { get; init; }
    public string? Cest { get; init; }
    public required string Cfop { get; init; }
    public required byte Origem { get; init; }

    public required string UnidadeComercial { get; init; }
    public required decimal Quantidade { get; init; }
    public required decimal ValorUnitario { get; init; }
    public required decimal ValorTotal { get; init; }
    public decimal Desconto { get; init; }

    public string? Csosn { get; init; }
    public string? CstIcms { get; init; }
    public decimal AliquotaIcms { get; init; }
    public required string CstPis { get; init; }
    public required string CstCofins { get; init; }

    // NT 2025.002 — grupo UB. Preenchido só quando o emitente está obrigado
    // (CRT 3 desde 03/08/2026; CRT 1 e 4 a partir de 04/01/2027).
    public string? CstIbsCbs { get; init; }
    public string? CClassTrib { get; init; }
}

public sealed record PagamentoFiscal
{
    /// <summary>tPag: 01 dinheiro, 03 crédito, 04 débito, 17 PIX dinâmico, 20 PIX estático.</summary>
    public required string Tipo { get; init; }
    public required decimal Valor { get; init; }
    public string? BandeiraCartao { get; init; }
    public string? CnpjCredenciadora { get; init; }
    public string? AutorizacaoTef { get; init; }
}

public sealed record CancelamentoRequest
{
    public required ContextoEmitente Emitente { get; init; }
    public required string ChaveAcesso { get; init; }
    public required string Protocolo { get; init; }
    public required string Justificativa { get; init; }  // mínimo 15 caracteres
    public required int SequenciaEvento { get; init; }
}

public sealed record InutilizacaoRequest
{
    public required ContextoEmitente Emitente { get; init; }
    public required int Serie { get; init; }
    public required int NumeroInicial { get; init; }
    public required int NumeroFinal { get; init; }
    public required string Justificativa { get; init; }  // mínimo 15 caracteres
}
