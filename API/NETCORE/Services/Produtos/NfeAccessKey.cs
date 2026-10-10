namespace HORUSPDV_API.Services.Produtos;

public static class NfeAccessKey
{
    public static string ValidateInvoiceKey(string value)
    {
        var key = new string((value ?? string.Empty).Where(c => c is >= '0' and <= '9').ToArray());
        if (key.Length != 44)
            throw new InvalidOperationException("Chave de acesso inválida. Informe os 44 dígitos da nota.");
        var sum = 0;
        for (int i = 42, weight = 2; i >= 0; i--, weight = weight == 9 ? 2 : weight + 1)
            sum += (key[i] - '0') * weight;
        var remainder = sum % 11;
        if (key[43] - '0' != (remainder < 2 ? 0 : 11 - remainder))
            throw new InvalidOperationException("Chave de acesso inválida. Confira os números e o dígito verificador.");
        if (key.Substring(20, 2) is not ("55" or "65"))
            throw new InvalidOperationException("A entrada aceita somente notas dos modelos 55 e 65.");
        return key;
    }

    public static string ValidateDistributionKey(string value)
    {
        var key = ValidateInvoiceKey(value);
        if (key.Substring(20, 2) == "65")
            throw new InvalidOperationException("Esta chave é de uma NFC-e (modelo 65). Para dar entrada no estoque, envie o XML do cupom ou use 'Digitar itens do cupom'. A consulta de NF-e no Ambiente Nacional não baixa este modelo.");
        if (key.Substring(20, 2) != "55")
            throw new InvalidOperationException("A consulta no Ambiente Nacional aceita somente chaves de NF-e (modelo 55).");
        return key;
    }
}
