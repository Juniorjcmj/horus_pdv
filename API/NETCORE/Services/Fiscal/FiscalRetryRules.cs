namespace HORUSPDV_API.Services.Fiscal;

/// <summary>A lost response is not proof of rejection. Only 217 permits resending the same signed invoice.</summary>
public static class FiscalRetryRules
{
    public static ResultadoFiscal RecoverAndTransmit(Func<ResultadoFiscal> consult, Func<ResultadoFiscal> transmit)
    {
        var existing = consult();
        if (existing.Status is StatusDocumentoFiscal.Autorizado or StatusDocumentoFiscal.Denegado or StatusDocumentoFiscal.Cancelado)
            return existing;
        if (existing.CodigoStatus != 217)
            return existing with { Status = StatusDocumentoFiscal.Assinado, Retentavel = true,
                MotivoStatus = "Situação fiscal ainda não confirmada; nenhuma nova nota foi enviada. " + existing.MotivoStatus };

        var sent = transmit();
        if (sent.CodigoStatus is not (204 or 539)) return sent;
        var recovered = consult();
        if (recovered.Status is StatusDocumentoFiscal.Autorizado or StatusDocumentoFiscal.Denegado or StatusDocumentoFiscal.Cancelado)
            return recovered;
        // A different key may already consume the numbering. Never generate a new invoice silently.
        return sent with { Status = StatusDocumentoFiscal.Rejeitado, Retentavel = false,
            MotivoStatus = "Duplicidade fiscal: não foi possível recuperar a autorização da chave original. Confira a SEFAZ antes de emitir outro documento." };
    }
}
