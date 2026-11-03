/*
 * Arquivo: JAVA/api/contrato/vetores-dotnet/VetoresHash.cs
 * Objetivo: gerar vetores do HorusPayloadHash (venda, abertura, fechamento e movimento de caixa) com o codigo REAL
 *           da API .NET. O CompatibilidadeDotnetTest/HashPayloadTest da API Java confere cada hash.
 * Uso: dotnet run -- gerar-hash ../../src/test/resources/contrato/vetores-hash.json
 */
using System.Text;
using System.Text.Json;
using HORUSPDV_API.Models.Requests;
using HORUSPDV_API.Services.Shared;

internal static class VetoresHash
{
    private static readonly JsonSerializerOptions Saida = new(JsonSerializerDefaults.Web)
    {
        WriteIndented = true,
        Encoder = System.Text.Encodings.Web.JavaScriptEncoder.UnsafeRelaxedJsonEscaping
    };

    public static int Gerar(string caminho)
    {
        var casos = new List<object>();

        void Venda(string nome, VendaRequest r) => casos.Add(new
        {
            nome,
            tipo = "venda",
            entrada = new
            {
                customerName = r.CustomerName,
                customerCpf = r.CustomerCpf,
                paymentType = r.PaymentType,
                totalAmount = r.TotalAmount,
                operatorName = r.OperatorName,
                items = r.Items.Select(i => new
                {
                    productCode = i.ProductCode,
                    quantity = i.Quantity,
                    unitPrice = i.UnitPrice,
                    desconto = i.Desconto,
                    promocaoId = i.PromocaoId
                }),
                payments = r.Payments.Select(p => new
                {
                    paymentType = p.PaymentType,
                    amount = p.Amount,
                    cashGiven = p.CashGiven,
                    changeAmount = p.ChangeAmount
                }),
                xmlNfceContingencia = r.XmlNfceContingencia
            },
            hash = HorusPayloadHash.ComputeHash(r)
        });

        static VendaItemRequest Item(string codigo, decimal qtd, decimal preco, decimal desconto = 0m, string? promo = null)
            => new() { ProductCode = codigo, Quantity = qtd, UnitPrice = preco, Desconto = desconto, PromocaoId = promo };

        static VendaPagamentoRequest Pag(string tipo, decimal valor, decimal? dado = null, decimal? troco = null)
            => new() { PaymentType = tipo, Amount = valor, CashGiven = dado, ChangeAmount = troco };

        Venda("simples-ascii", new VendaRequest
        {
            CustomerName = "Consumidor", CustomerCpf = "", PaymentType = "Dinheiro", TotalAmount = "10,00",
            OperatorName = "Caixa 1",
            Items = [Item("7891000100103", 1m, 10.00m)],
            Payments = [Pag("Dinheiro", 10.00m, 10.00m, 0.00m)]
        });

        Venda("acentos-e-simbolos", new VendaRequest
        {
            CustomerName = "  José da Conceição & Filhos <ltda> + \"Café\" 'Açaí' `x` \\ / ✓ 😀  ",
            CustomerCpf = " 123.456.789-09 ", PaymentType = "Cartão de Crédito", TotalAmount = "R$ 1.234,56",
            OperatorName = "Maria\tÂngela\nLinha",
            Items = [Item("PÃO-01", 2m, 617.28m, 0m, "promo-ç")],
            Payments = [Pag("Cartão de Crédito", 1234.56m)]
        });

        Venda("com-desconto-e-promocao", new VendaRequest
        {
            CustomerName = "Ana", CustomerCpf = "52998224725", PaymentType = "Pix", TotalAmount = "89,90",
            OperatorName = "Caixa 2",
            Items = [Item("A1", 3m, 33.30m, 10.00m, "promo-1"), Item("B2", 1m, 0.00m, 0m)],
            Payments = [Pag("Pix", 89.90m)]
        });

        Venda("varios-pagamentos-ordem-e-caixa-mista", new VendaRequest
        {
            CustomerName = "Cliente", CustomerCpf = "", PaymentType = "Múltiplo", TotalAmount = "150,00",
            OperatorName = "Caixa 1",
            Items = [Item("zz", 1m, 100m), Item("AA", 1m, 50m), Item("aa", 2m, 25m), Item("Mm", 1m, 5m)],
            Payments = [Pag("pix", 50m), Pag("Dinheiro", 100m, 120m, 20m), Pag("Cartão", 0.5m), Pag("DINHEIRO", 1m)]
        });

        Venda("decimais-zeros-a-direita", new VendaRequest
        {
            CustomerName = "Zeros", CustomerCpf = "", PaymentType = "Dinheiro", TotalAmount = "10,50",
            OperatorName = "Caixa",
            Items = [Item("P1", 2.500m, 3.0m, 0.10m), Item("P2", 1.00000m, 2.50m, 0.000m), Item("P3", 10m, 1m)],
            Payments = [Pag("Dinheiro", 10.50m, 20.00m, 9.50m)]
        });

        Venda("arredondamento-para-par", new VendaRequest
        {
            CustomerName = "Banqueiro", CustomerCpf = "", PaymentType = "Dinheiro", TotalAmount = "1.000,500",
            OperatorName = "Caixa",
            Items =
            [
                Item("R1", 1.00005m, 2.505m, 0.125m), Item("R2", 1.00015m, 2.515m, 0.135m),
                Item("R3", 0.45249m, 2.525m, 0.145m), Item("R4", 12.3456789m, 0.005m, 0.015m)
            ],
            Payments = [Pag("Dinheiro", 0.125m, 0.135m, 0.145m), Pag("Pix", 2.675m, 3.0m, 0.325m)]
        });

        Venda("total-invalido-e-sem-itens", new VendaRequest
        {
            CustomerName = "", CustomerCpf = "", PaymentType = "", TotalAmount = "abc", OperatorName = "",
            Items = [], Payments = []
        });

        Venda("total-formatos", new VendaRequest
        {
            CustomerName = "T", CustomerCpf = "", PaymentType = "Dinheiro", TotalAmount = " 1.234.567,8 ",
            OperatorName = "T", Items = [Item("X", 1m, 1m)], Payments = [Pag("Dinheiro", 1m)]
        });

        Venda("xml-nfce-contingencia", new VendaRequest
        {
            CustomerName = "Contingência", CustomerCpf = "", PaymentType = "Dinheiro", TotalAmount = "10,00",
            OperatorName = "Caixa",
            Items = [Item("7891000100103", 1m, 10.00m)],
            Payments = [Pag("Dinheiro", 10.00m, 10.00m, 0.00m)],
            XmlNfceContingencia = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><NFe><infNFe Id=\"NFe35\">Açaí</infNFe></NFe>"
        });

        void Abertura(string nome, string valor) => casos.Add(new
        {
            nome, tipo = "abertura", entrada = new { openingAmount = valor },
            hash = HorusPayloadHash.ComputeHash(new AbrirCaixaRequest { OpeningAmount = valor })
        });
        Abertura("abertura-simples", "100,00");
        Abertura("abertura-zero", "0,00");
        Abertura("abertura-milhar", "R$ 1.500,5");
        Abertura("abertura-invalida", "xyz");

        void Fechamento(string nome, string valor, string nota, string? motivo) => casos.Add(new
        {
            nome, tipo = "fechamento", entrada = new { closingAmount = valor, note = nota, differenceReason = motivo },
            hash = HorusPayloadHash.ComputeHash(new FecharCaixaRequest { ClosingAmount = valor, Note = nota, DifferenceReason = motivo })
        });
        Fechamento("fechamento-sem-motivo", "250,00", "", null);
        Fechamento("fechamento-com-diferenca-acentuada", "249,90", "  Conferência feita às 18h  ", " Troco errado: R$0,10 + \"cliente\" ");

        void Movimento(string nome, string tipo, string valor, string motivo) => casos.Add(new
        {
            nome, tipo = "movimento", entrada = new { tipo, valor, motivo },
            hash = HorusPayloadHash.ComputeHash(new RegistrarMovimentoCaixaRequest { Tipo = tipo, Valor = valor, Motivo = motivo })
        });
        Movimento("sangria", "Sangria", "50,00", "Pagamento fornecedor");
        Movimento("reforco-acentuado", " REFORÇO ", "1.000,5", "Troco p/ o dia & café");
        Movimento("reforco-ascii-maiusculo", "REFORCO", "20", "");

        // Casos em que o hash do .NET coincide com o do PayloadHash do Caixa JavaFX (ASCII, decimais sem zeros finais).
        Venda("caixa-compat-dinheiro", new VendaRequest
        {
            CustomerName = "Consumidor", CustomerCpf = "", PaymentType = "Dinheiro", TotalAmount = "25",
            OperatorName = "Caixa 1",
            Items = [Item("7891", 2m, 12.5m), Item("7892", 1m, 0m)],
            Payments = [Pag("Dinheiro", 25m, 30m, 5m)]
        });

        Venda("caixa-compat-multiplo", new VendaRequest
        {
            CustomerName = "Ana Souza", CustomerCpf = "52998224725", PaymentType = "Multiplo", TotalAmount = "40.5",
            OperatorName = "Caixa 2",
            Items = [Item("B2", 3m, 10m, 1.5m, "promo-1"), Item("a1", 0.452m, 20m)],
            Payments = [Pag("Pix", 20m), Pag("Dinheiro", 20.5m, 20.5m, 0m)]
        });

        casos.Add(new { nome = "caixa-compat-abertura", tipo = "abertura", entrada = new { openingAmount = "100" },
            hash = HorusPayloadHash.ComputeHash(new AbrirCaixaRequest { OpeningAmount = "100" }) });
        casos.Add(new { nome = "caixa-compat-fechamento", tipo = "fechamento",
            entrada = new { closingAmount = "250.5", note = "Conferido", differenceReason = "Troco" },
            hash = HorusPayloadHash.ComputeHash(new FecharCaixaRequest { ClosingAmount = "250.5", Note = "Conferido", DifferenceReason = "Troco" }) });
        casos.Add(new { nome = "caixa-compat-movimento", tipo = "movimento",
            entrada = new { tipo = "Sangria", valor = "50", motivo = "Fornecedor" },
            hash = HorusPayloadHash.ComputeHash(new RegistrarMovimentoCaixaRequest { Tipo = "Sangria", Valor = "50", Motivo = "Fornecedor" }) });

        File.WriteAllText(caminho, JsonSerializer.Serialize(new { casos }, Saida), new UTF8Encoding(false));
        Console.WriteLine($"Vetores de hash ({casos.Count}) gerados em {Path.GetFullPath(caminho)}");
        return 0;
    }
}
