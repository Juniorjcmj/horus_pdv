/**
 * Arquivo: API/NETCORE/Repositories/DatabaseAccess/DocumentoFiscalAB.cs
 * Objetivo: concentra a fila de documentos fiscais (DocumentosFiscais) — enfileirar uma venda
 *           para emissão, alocar numeração (FiscalSequencias), montar a requisição para o
 *           IFiscalProvider e registrar o resultado devolvido pela SEFAZ.
 * Entradas esperadas: recebe conexão configurada e os dados já persistidos da venda.
 *
 * Esta tabela É a fila (ver DataBase/Migrations/02_estrutura_fiscal.sql) — não há fila em
 * memória, para que a contingência sobreviva a um restart do processo.
 */
using HORUSPDV_API.Services.Fiscal;
using HORUSPDV_API.Services.Shared;
using Microsoft.Data.SqlClient;

namespace HORUSPDV_API.Repositories.DatabaseAccess;

public class DocumentoFiscalAB(
    Connection connection,
    HistoricoVendasAB historicoVendasAB,
    ProdutoAB produtoAB,
    ClienteAB clienteAB)
{
    private const short ModeloNfce = 65;
    private const short ModeloNfe = 55;
    // Modelo 65 = NFC-e, Modelo 55 = NF-e. A série fiscal é lida da configuração da empresa.

    /// <summary>
    /// Aloca o próximo número fiscal e enfileira a venda para emissão. Roda fora da transação
    /// da venda — falha aqui não deve derrubar a venda já registrada (ver HistoricoVendasController).
    /// </summary>
    public async Task<string> EnfileirarAsync(string companyId, string vendaId, CancellationToken ct = default)
    {
        await using var db = await connection.OpenConnectionAsync(ct);
        await using var transaction = (SqlTransaction)await db.BeginTransactionAsync(ct);

        try
        {
            var (ambiente, serie) = await ObterConfigFiscalAsync(db, transaction, companyId, ct);
            var numero = await AlocarProximoNumeroAsync(db, transaction, companyId, ModeloNfce, serie, ambiente, ct);
            var id = $"doc-{DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()}";

            await using (var insert = new SqlCommand(
                             """
                             INSERT INTO DocumentosFiscais
                                 (Id, CompanyId, VendaId, Modelo, Serie, NumeroNf, Ambiente, Status, TpEmis)
                             VALUES
                                 (@Id, @CompanyId, @VendaId, @Modelo, @Serie, @NumeroNf, @Ambiente, 1, 1);
                             """,
                             db,
                             transaction))
            {
                insert.Parameters.AddWithValue("@Id", id);
                insert.Parameters.AddWithValue("@CompanyId", companyId);
                insert.Parameters.AddWithValue("@VendaId", vendaId);
                insert.Parameters.AddWithValue("@Modelo", ModeloNfce);
                insert.Parameters.AddWithValue("@Serie", serie);
                insert.Parameters.AddWithValue("@NumeroNf", numero);
                insert.Parameters.AddWithValue("@Ambiente", ambiente);
                await insert.ExecuteNonQueryAsync(ct);
            }

            await transaction.CommitAsync(ct);
            return id;
        }
        catch
        {
            await transaction.RollbackAsync(ct);
            throw;
        }
    }

    /// <summary>
    /// Enfileira uma NF-e modelo 55 para emissão. Recebe os dados do destinatário em JSON
    /// para que o worker possa montar a requisição sem depender de uma venda.
    /// </summary>
    public async Task<string> EnfileirarNfeAsync(
        string companyId, string vendaId,
        string destinatarioJson,
        string naturezaOperacao = "VENDA DE MERCADORIA",
        byte modalidadeFrete = 9,
        CancellationToken ct = default)
    {
        await using var db = await connection.OpenConnectionAsync(ct);
        await using var transaction = (SqlTransaction)await db.BeginTransactionAsync(ct);

        try
        {
            var (ambiente, serie) = await ObterConfigFiscalNfeAsync(db, transaction, companyId, ct);
            var numero = await AlocarProximoNumeroAsync(db, transaction, companyId, ModeloNfe, serie, ambiente, ct);
            var id = $"nfe-{DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()}";

            await using (var insert = new SqlCommand(
                             """
                             INSERT INTO DocumentosFiscais
                                 (Id, CompanyId, VendaId, Modelo, Serie, NumeroNf, Ambiente, Status, TpEmis,
                                  DestinatarioJson, NaturezaOperacao, ModalidadeFrete)
                             VALUES
                                 (@Id, @CompanyId, @VendaId, @Modelo, @Serie, @NumeroNf, @Ambiente, 1, 1,
                                  @DestinatarioJson, @NaturezaOperacao, @ModalidadeFrete);
                             """,
                             db,
                             transaction))
            {
                insert.Parameters.AddWithValue("@Id", id);
                insert.Parameters.AddWithValue("@CompanyId", companyId);
                insert.Parameters.AddWithValue("@VendaId", vendaId);
                insert.Parameters.AddWithValue("@Modelo", ModeloNfe);
                insert.Parameters.AddWithValue("@Serie", serie);
                insert.Parameters.AddWithValue("@NumeroNf", numero);
                insert.Parameters.AddWithValue("@Ambiente", ambiente);
                insert.Parameters.AddWithValue("@DestinatarioJson", destinatarioJson);
                insert.Parameters.AddWithValue("@NaturezaOperacao", naturezaOperacao);
                insert.Parameters.AddWithValue("@ModalidadeFrete", modalidadeFrete);
                await insert.ExecuteNonQueryAsync(ct);
            }

            await transaction.CommitAsync(ct);
            return id;
        }
        catch
        {
            await transaction.RollbackAsync(ct);
            throw;
        }
    }

    public async Task<EmissaoNfeRequest> MontarRequisicaoNfeAsync(
        DocumentoFiscalPendente doc, ContextoEmitente emitente, CancellationToken ct = default)
    {
        var linhas = await historicoVendasAB.ObterPorIdAsync(doc.CompanyId, doc.VendaId);
        if (linhas.Count == 0)
            throw new InvalidOperationException($"Venda {doc.VendaId} sem itens — não é possível montar a NF-e.");

        var itens = new List<ItemFiscal>();
        for (var index = 0; index < linhas.Count; index++)
        {
            var linha = linhas[index];
            var produto = await produtoAB.ObterPorCodigoAsync(doc.CompanyId, linha.ProductCode)
                ?? throw new InvalidOperationException(
                    $"Produto {linha.ProductCode} não encontrado — não é possível montar a NF-e da venda {doc.VendaId}.");

            var valorUnitario = HorusMoneyFormat.ParseDecimal(linha.UnitPrice);
            var valorBruto = Math.Round(valorUnitario * linha.Quantity, 2, MidpointRounding.AwayFromZero);

            // NF-e modelo 55 usa CFOP 5102 para venda interna ou 6102 para interestadual
            var cfop = produto.Cfop;

            itens.Add(new ItemFiscal
            {
                Numero = index + 1,
                CodigoProduto = produto.ProductCode,
                Descricao = produto.ProductName,
                Gtin = produto.Gtin,
                Ncm = produto.Ncm,
                Cest = produto.Cest,
                Cfop = cfop,
                Origem = produto.OrigemMercadoria,
                UnidadeComercial = produto.UnidadeComercial,
                Quantidade = linha.Quantity,
                ValorUnitario = valorUnitario,
                ValorTotal = valorBruto,
                Desconto = linha.Desconto,
                Csosn = produto.CsosnIcms,
                CstIcms = produto.CstIcms,
                AliquotaIcms = produto.AliquotaIcms,
                CstPis = produto.CstPis,
                CstCofins = produto.CstCofins,
                CstIbsCbs = produto.CstIbsCbs,
                CClassTrib = produto.CClassTrib
            });
        }

        var primeira = linhas[0];
        var totalAmount = HorusMoneyFormat.ParseDecimal(primeira.TotalAmount);

        var pagamentosCadastrados = await historicoVendasAB.ObterPagamentosVendaAsync(doc.CompanyId, doc.VendaId);
        List<PagamentoFiscal> pagamentosFiscais;
        decimal valorTrocoTotal = 0;

        if (pagamentosCadastrados.Count > 0)
        {
            pagamentosFiscais = pagamentosCadastrados.Select(p => new PagamentoFiscal
            {
                Tipo = MapearFormaPagamento(p.PaymentType),
                Valor = p.Amount
            }).ToList();
            valorTrocoTotal = pagamentosCadastrados.Sum(p => p.ChangeAmount);
        }
        else
        {
            pagamentosFiscais = [new PagamentoFiscal { Tipo = MapearFormaPagamento(primeira.PaymentType), Valor = totalAmount }];
        }

        // Deserializa o destinatário persistido no enfileiramento
        var destinatario = System.Text.Json.JsonSerializer.Deserialize<DestinatarioFiscal>(
            doc.DestinatarioJson ?? "{}",
            new System.Text.Json.JsonSerializerOptions { PropertyNameCaseInsensitive = true })
            ?? throw new InvalidOperationException("Dados do destinatário ausentes no documento fiscal.");

        return new EmissaoNfeRequest
        {
            Emitente = emitente,
            Serie = doc.Serie,
            NumeroNf = doc.NumeroNf,
            TipoEmissao = doc.TipoEmissao,
            NaturezaOperacao = doc.NaturezaOperacao ?? "VENDA DE MERCADORIA",
            Destinatario = destinatario,
            Itens = itens,
            Pagamentos = pagamentosFiscais,
            ValorTroco = valorTrocoTotal,
            ModalidadeFrete = doc.ModalidadeFrete
        };
    }

    private async Task<(byte Ambiente, int Serie)> ObterConfigFiscalNfeAsync(
        SqlConnection db, SqlTransaction transaction, string companyId, CancellationToken ct)
    {
        await using var command = new SqlCommand(
            "SELECT AmbienteFiscal, ISNULL(SerieNfe, 2) FROM Empresas WHERE Id = @CompanyId;", db, transaction);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        await using var reader = await command.ExecuteReaderAsync(ct);
        if (await reader.ReadAsync(ct))
        {
            var amb = reader.IsDBNull(0) ? (byte)2 : reader.GetByte(0);
            var serie = reader.IsDBNull(1) ? 2 : Convert.ToInt32(reader.GetValue(1));
            return (amb, serie);
        }
        return (2, 2);
    }

    public async Task<List<DocumentoFiscalPendente>> ObterPendentesAsync(int lote, CancellationToken ct = default)
    {
        const string sql = """
            SELECT TOP (@Lote) Id, CompanyId, VendaId, Modelo, Serie, NumeroNf, TpEmis, Tentativas,
                   DhContingencia, JustContingencia, DestinatarioJson, NaturezaOperacao, ModalidadeFrete
            FROM DocumentosFiscais
            WHERE Status IN (1, 2, 8)
              AND (ProximaTentativaEm IS NULL OR ProximaTentativaEm <= SYSDATETIMEOFFSET())
            ORDER BY CriadoEm;
            """;

        await using var db = await connection.OpenConnectionAsync(ct);
        await using var command = new SqlCommand(sql, db);
        command.Parameters.AddWithValue("@Lote", lote);
        await using var reader = await command.ExecuteReaderAsync(ct);
        var rows = new List<DocumentoFiscalPendente>();
        while (await reader.ReadAsync(ct))
        {
            rows.Add(new DocumentoFiscalPendente
            {
                Id = ReadString(reader, "Id"),
                CompanyId = ReadString(reader, "CompanyId"),
                VendaId = ReadString(reader, "VendaId"),
                Modelo = (short)ReadInt(reader, "Modelo"),
                Serie = ReadInt(reader, "Serie"),
                NumeroNf = ReadInt(reader, "NumeroNf"),
                TipoEmissao = (TipoEmissaoFiscal)ReadInt(reader, "TpEmis"),
                Tentativas = ReadInt(reader, "Tentativas"),
                DhContingencia = ReadNullableDateTimeOffset(reader, "DhContingencia"),
                JustContingencia = ReadNullableString(reader, "JustContingencia"),
                DestinatarioJson = ReadNullableString(reader, "DestinatarioJson"),
                NaturezaOperacao = ReadNullableString(reader, "NaturezaOperacao"),
                ModalidadeFrete = (byte)ReadInt(reader, "ModalidadeFrete")
            });
        }

        return rows;
    }

    public async Task<EmissaoNfceRequest> MontarRequisicaoAsync(
        DocumentoFiscalPendente doc, ContextoEmitente emitente, CancellationToken ct = default)
    {
        var linhas = await historicoVendasAB.ObterPorIdAsync(doc.CompanyId, doc.VendaId);
        if (linhas.Count == 0)
        {
            throw new InvalidOperationException($"Venda {doc.VendaId} sem itens — não é possível montar a NFC-e.");
        }

        var itens = new List<ItemFiscal>();
        for (var index = 0; index < linhas.Count; index++)
        {
            var linha = linhas[index];
            var produto = await produtoAB.ObterPorCodigoAsync(doc.CompanyId, linha.ProductCode)
                ?? throw new InvalidOperationException(
                    $"Produto {linha.ProductCode} não encontrado — não é possível montar a NFC-e da venda {doc.VendaId}.");

            var valorUnitario = HorusMoneyFormat.ParseDecimal(linha.UnitPrice);
            var valorBruto = Math.Round(valorUnitario * linha.Quantity, 2, MidpointRounding.AwayFromZero);

            itens.Add(new ItemFiscal
            {
                Numero = index + 1,
                CodigoProduto = produto.ProductCode,
                Descricao = produto.ProductName,
                Gtin = produto.Gtin,
                Ncm = produto.Ncm,
                Cest = produto.Cest,
                Cfop = produto.Cfop,
                Origem = produto.OrigemMercadoria,
                UnidadeComercial = produto.UnidadeComercial,
                Quantidade = linha.Quantity,
                ValorUnitario = valorUnitario,
                ValorTotal = valorBruto,
                Desconto = linha.Desconto,
                Csosn = produto.CsosnIcms,
                CstIcms = produto.CstIcms,
                AliquotaIcms = produto.AliquotaIcms,
                CstPis = produto.CstPis,
                CstCofins = produto.CstCofins,
                CstIbsCbs = produto.CstIbsCbs,
                CClassTrib = produto.CClassTrib
            });
        }

        var primeira = linhas[0];
        var destinatario = await MontarDestinatarioAsync(doc.CompanyId, primeira.CustomerCpf, primeira.CustomerName);
        var totalAmount = HorusMoneyFormat.ParseDecimal(primeira.TotalAmount);

        var pagamentosCadastrados = await historicoVendasAB.ObterPagamentosVendaAsync(doc.CompanyId, doc.VendaId);
        List<PagamentoFiscal> pagamentosFiscais;
        decimal valorTrocoTotal = 0;

        if (pagamentosCadastrados.Count > 0)
        {
            pagamentosFiscais = pagamentosCadastrados.Select(p => new PagamentoFiscal
            {
                Tipo = MapearFormaPagamento(p.PaymentType),
                Valor = p.Amount
            }).ToList();
            valorTrocoTotal = pagamentosCadastrados.Sum(p => p.ChangeAmount);
        }
        else
        {
            pagamentosFiscais = [new PagamentoFiscal { Tipo = MapearFormaPagamento(primeira.PaymentType), Valor = totalAmount }];
        }

        return new EmissaoNfceRequest
        {
            Emitente = emitente,
            Serie = doc.Serie,
            NumeroNf = doc.NumeroNf,
            TipoEmissao = doc.TipoEmissao,
            DhContingencia = doc.DhContingencia,
            JustificativaContingencia = doc.JustContingencia,
            Destinatario = destinatario,
            Itens = itens,
            Pagamentos = pagamentosFiscais,
            ValorTroco = valorTrocoTotal
        };
    }

    public async Task<List<DocumentoFiscalResumo>> ListarAsync(string companyId)
    {
        const string sql = """
            SELECT d.Id, v.SaleNumber, d.Serie, d.NumeroNf, d.Status, d.ChaveAcesso, d.Protocolo,
                   d.MotivoStatus, d.DhAutorizacao, d.CriadoEm, d.Tentativas,
                   v.TotalAmount, v.CustomerName, v.CustomerCpf, v.PaymentType,
                   CASE WHEN d.XmlProtocolado IS NOT NULL OR d.XmlAssinado IS NOT NULL THEN 1 ELSE 0 END AS HasXml,
                   CASE WHEN d.XmlCancelamento IS NOT NULL THEN 1 ELSE 0 END AS HasCancelXml
            FROM DocumentosFiscais d
            LEFT JOIN Vendas v ON v.Id = d.VendaId
            WHERE d.CompanyId = @CompanyId
            ORDER BY d.CriadoEm DESC;
            """;

        await using var db = await connection.OpenConnectionAsync();
        await using var command = new SqlCommand(sql, db);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        await using var reader = await command.ExecuteReaderAsync();
        var rows = new List<DocumentoFiscalResumo>();
        while (await reader.ReadAsync())
        {
            var totalOrdinal = reader.GetOrdinal("TotalAmount");
            var totalAmount = reader.IsDBNull(totalOrdinal) ? null : HorusMoneyFormat.Format(reader.GetDecimal(totalOrdinal));

            rows.Add(new DocumentoFiscalResumo
            {
                Id = ReadString(reader, "Id"),
                SaleNumber = ReadString(reader, "SaleNumber"),
                Serie = ReadInt(reader, "Serie"),
                NumeroNf = ReadInt(reader, "NumeroNf"),
                Status = (StatusDocumentoFiscal)ReadInt(reader, "Status"),
                ChaveAcesso = ReadNullableString(reader, "ChaveAcesso"),
                Protocolo = ReadNullableString(reader, "Protocolo"),
                MotivoStatus = ReadNullableString(reader, "MotivoStatus"),
                DhAutorizacao = ReadNullableDateTimeOffset(reader, "DhAutorizacao"),
                CriadoEm = reader.GetDateTimeOffset(reader.GetOrdinal("CriadoEm")),
                Tentativas = ReadInt(reader, "Tentativas"),
                TotalAmount = totalAmount,
                CustomerName = ReadNullableString(reader, "CustomerName"),
                CustomerCpf = ReadNullableString(reader, "CustomerCpf"),
                PaymentType = ReadNullableString(reader, "PaymentType"),
                HasXml = reader.GetInt32(reader.GetOrdinal("HasXml")) == 1,
                HasCancelXml = reader.GetInt32(reader.GetOrdinal("HasCancelXml")) == 1
            });
        }

        return rows;
    }

    /// <summary>Recupera o XML oficial (autorizado ou cancelamento) para download individual.</summary>
    public async Task<(string? Xml, string? ChaveAcesso, StatusDocumentoFiscal Status, string? XmlCancelamento)?> ObterXmlAsync(string companyId, string id)
    {
        const string sql = """
            SELECT ChaveAcesso, Status, XmlAssinado, XmlProtocolado, XmlCancelamento
            FROM DocumentosFiscais
            WHERE Id = @Id AND CompanyId = @CompanyId;
            """;

        await using var db = await connection.OpenConnectionAsync();
        await using var command = new SqlCommand(sql, db);
        command.Parameters.AddWithValue("@Id", id);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        await using var reader = await command.ExecuteReaderAsync();
        if (!await reader.ReadAsync()) return null;

        var chave = ReadNullableString(reader, "ChaveAcesso");
        var status = (StatusDocumentoFiscal)ReadInt(reader, "Status");
        var assinado = ReadNullableString(reader, "XmlAssinado");
        var protocolado = ReadNullableString(reader, "XmlProtocolado");
        var cancelamento = ReadNullableString(reader, "XmlCancelamento");

        return (protocolado ?? assinado, chave, status, cancelamento);
    }

    /// <summary>Obtém os itens detalhados da venda vinculada ao documento fiscal para a visão Raio-X.</summary>
    public async Task<List<DocumentoFiscalItemResumo>> ObterItensDocumentoAsync(string companyId, string id)
    {
        const string sql = """
            SELECT i.ProductCode, i.ProductName, i.Quantity, i.UnitPrice, i.ItemTotal,
                   p.Ncm, p.Cest, p.Cfop, p.UnidadeComercial
            FROM DocumentosFiscais d
            INNER JOIN VendaItens i ON i.VendaId = d.VendaId
            LEFT JOIN Produtos p ON p.CompanyId = d.CompanyId AND p.ProductCode = i.ProductCode
            WHERE d.Id = @Id AND d.CompanyId = @CompanyId
            ORDER BY i.Id;
            """;

        await using var db = await connection.OpenConnectionAsync();
        await using var command = new SqlCommand(sql, db);
        command.Parameters.AddWithValue("@Id", id);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        await using var reader = await command.ExecuteReaderAsync();
        var rows = new List<DocumentoFiscalItemResumo>();
        while (await reader.ReadAsync())
        {
            rows.Add(new DocumentoFiscalItemResumo
            {
                ProductCode = ReadString(reader, "ProductCode"),
                ProductName = ReadString(reader, "ProductName"),
                Quantity = reader.GetDecimal(reader.GetOrdinal("Quantity")),
                UnitPrice = HorusMoneyFormat.Format(reader.GetDecimal(reader.GetOrdinal("UnitPrice"))),
                ItemTotal = HorusMoneyFormat.Format(reader.GetDecimal(reader.GetOrdinal("ItemTotal"))),
                Ncm = ReadNullableString(reader, "Ncm"),
                Cest = ReadNullableString(reader, "Cest"),
                Cfop = ReadNullableString(reader, "Cfop"),
                UnidadeComercial = ReadNullableString(reader, "UnidadeComercial")
            });
        }

        return rows;
    }

    /// <summary>Detalhe de um documento por número de venda — usado para montar o DANFE em tela.</summary>
    public async Task<DocumentoFiscalDetalhe?> ObterDetalhePorVendaAsync(string companyId, string saleNumber)
    {
        const string sql = """
            SELECT d.Id, v.SaleNumber, d.Serie, d.NumeroNf, d.Status, d.ChaveAcesso, d.Protocolo,
                   d.MotivoStatus, d.DhAutorizacao, d.CriadoEm, d.Tentativas, d.XmlProtocolado, d.XmlAssinado
            FROM DocumentosFiscais d
            INNER JOIN Vendas v ON v.Id = d.VendaId
            WHERE d.CompanyId = @CompanyId AND v.SaleNumber = @SaleNumber
            ORDER BY d.CriadoEm DESC;
            """;

        await using var db = await connection.OpenConnectionAsync();
        await using var command = new SqlCommand(sql, db);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        command.Parameters.AddWithValue("@SaleNumber", saleNumber);
        await using var reader = await command.ExecuteReaderAsync();
        if (!await reader.ReadAsync()) return null;

        var xmlProtocolado = ReadNullableString(reader, "XmlProtocolado");
        var xmlAssinado = ReadNullableString(reader, "XmlAssinado");
        return new DocumentoFiscalDetalhe
        {
            Id = ReadString(reader, "Id"),
            SaleNumber = ReadString(reader, "SaleNumber"),
            Serie = ReadInt(reader, "Serie"),
            NumeroNf = ReadInt(reader, "NumeroNf"),
            Status = (StatusDocumentoFiscal)ReadInt(reader, "Status"),
            ChaveAcesso = ReadNullableString(reader, "ChaveAcesso"),
            Protocolo = ReadNullableString(reader, "Protocolo"),
            MotivoStatus = ReadNullableString(reader, "MotivoStatus"),
            DhAutorizacao = ReadNullableDateTimeOffset(reader, "DhAutorizacao"),
            CriadoEm = reader.GetDateTimeOffset(reader.GetOrdinal("CriadoEm")),
            Tentativas = ReadInt(reader, "Tentativas"),
            QrCodeUrl = ExtrairQrCode(xmlProtocolado) ?? ExtrairQrCode(xmlAssinado)
        };
    }

    /// <summary>
    /// Busca documento fiscal por código da venda, número da nota ou chave de 44 dígitos.
    /// Utilizado na frente de caixa para conferência rápida e cancelamento de NFC-e emitida.
    /// </summary>
    public async Task<DocumentoFiscalDetalhe?> ObterDetalhePorCodigoAsync(string companyId, string codigo)
    {
        if (string.IsNullOrWhiteSpace(codigo)) return null;

        var codigoTrim = codigo.Trim();
        var termoLimpo = codigoTrim.Replace(" ", "").Replace("-", "");
        var isNumero = int.TryParse(codigoTrim, out var numeroNf);

        const string sql = """
            SELECT TOP 1 d.Id, v.SaleNumber, d.Serie, d.NumeroNf, d.Status, d.ChaveAcesso, d.Protocolo,
                   d.MotivoStatus, d.DhAutorizacao, d.CriadoEm, d.Tentativas, d.XmlProtocolado, d.XmlAssinado,
                   v.TotalAmount, v.CustomerName, v.CustomerCpf, v.PaymentType,
                   CASE WHEN d.XmlProtocolado IS NOT NULL OR d.XmlAssinado IS NOT NULL THEN 1 ELSE 0 END AS HasXml,
                   CASE WHEN d.XmlCancelamento IS NOT NULL THEN 1 ELSE 0 END AS HasCancelXml
            FROM DocumentosFiscais d
            INNER JOIN Vendas v ON v.Id = d.VendaId
            WHERE d.CompanyId = @CompanyId
              AND (
                  v.SaleNumber = @Codigo
                  OR d.ChaveAcesso = @TermoLimpo
                  OR (@IsNumero = 1 AND d.NumeroNf = @NumeroNf)
                  OR d.Id = @Codigo
              )
            ORDER BY d.CriadoEm DESC;
            """;

        await using var db = await connection.OpenConnectionAsync();
        await using var command = new SqlCommand(sql, db);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        command.Parameters.AddWithValue("@Codigo", codigoTrim);
        command.Parameters.AddWithValue("@TermoLimpo", termoLimpo);
        command.Parameters.AddWithValue("@IsNumero", isNumero ? 1 : 0);
        command.Parameters.AddWithValue("@NumeroNf", isNumero ? numeroNf : 0);

        await using var reader = await command.ExecuteReaderAsync();
        if (!await reader.ReadAsync()) return null;

        var xmlProtocolado = ReadNullableString(reader, "XmlProtocolado");
        var xmlAssinado = ReadNullableString(reader, "XmlAssinado");
        var totalAmountRaw = reader.GetValue(reader.GetOrdinal("TotalAmount"));
        var totalAmount = totalAmountRaw switch
        {
            decimal dec => HorusMoneyFormat.Format(dec),
            string str when decimal.TryParse(str, out var dec) => HorusMoneyFormat.Format(dec),
            _ => totalAmountRaw?.ToString() ?? "0,00"
        };

        return new DocumentoFiscalDetalhe
        {
            Id = ReadString(reader, "Id"),
            SaleNumber = ReadString(reader, "SaleNumber"),
            Serie = ReadInt(reader, "Serie"),
            NumeroNf = ReadInt(reader, "NumeroNf"),
            Status = (StatusDocumentoFiscal)ReadInt(reader, "Status"),
            ChaveAcesso = ReadNullableString(reader, "ChaveAcesso"),
            Protocolo = ReadNullableString(reader, "Protocolo"),
            MotivoStatus = ReadNullableString(reader, "MotivoStatus"),
            DhAutorizacao = ReadNullableDateTimeOffset(reader, "DhAutorizacao"),
            CriadoEm = reader.GetDateTimeOffset(reader.GetOrdinal("CriadoEm")),
            Tentativas = ReadInt(reader, "Tentativas"),
            TotalAmount = totalAmount,
            CustomerName = ReadNullableString(reader, "CustomerName"),
            CustomerCpf = ReadNullableString(reader, "CustomerCpf"),
            PaymentType = ReadNullableString(reader, "PaymentType"),
            HasXml = reader.GetInt32(reader.GetOrdinal("HasXml")) == 1,
            HasCancelXml = reader.GetInt32(reader.GetOrdinal("HasCancelXml")) == 1,
            QrCodeUrl = ExtrairQrCode(xmlProtocolado) ?? ExtrairQrCode(xmlAssinado)
        };
    }

    /// <summary>Retorna os XMLs das notas autorizadas e canceladas de um mês para geração do pacote ZIP contábil.</summary>
    public async Task<List<DocumentoFiscalExportacaoXml>> ObterXmlsPorMesAsync(string companyId, int ano, int mes, CancellationToken ct = default)
    {
        const string sql = """
            SELECT d.Id, v.SaleNumber, d.Serie, d.NumeroNf, d.Status, d.ChaveAcesso, d.Protocolo,
                   d.DhAutorizacao, d.CriadoEm, d.XmlAssinado, d.XmlProtocolado, d.XmlCancelamento
            FROM DocumentosFiscais d
            INNER JOIN Vendas v ON v.Id = d.VendaId
            WHERE d.CompanyId = @CompanyId
              AND d.Status IN (3, 6, 8) -- Autorizado, Cancelado, ContingenciaPendente
              AND YEAR(COALESCE(d.DhAutorizacao, d.CriadoEm)) = @Ano
              AND MONTH(COALESCE(d.DhAutorizacao, d.CriadoEm)) = @Mes
            ORDER BY d.NumeroNf ASC;
            """;

        await using var db = await connection.OpenConnectionAsync(ct);
        await using var command = new SqlCommand(sql, db);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        command.Parameters.AddWithValue("@Ano", ano);
        command.Parameters.AddWithValue("@Mes", mes);

        await using var reader = await command.ExecuteReaderAsync(ct);
        var rows = new List<DocumentoFiscalExportacaoXml>();
        while (await reader.ReadAsync(ct))
        {
            rows.Add(new DocumentoFiscalExportacaoXml
            {
                Id = ReadString(reader, "Id"),
                SaleNumber = ReadString(reader, "SaleNumber"),
                Serie = ReadInt(reader, "Serie"),
                NumeroNf = ReadInt(reader, "NumeroNf"),
                Status = (StatusDocumentoFiscal)ReadInt(reader, "Status"),
                ChaveAcesso = ReadNullableString(reader, "ChaveAcesso"),
                Protocolo = ReadNullableString(reader, "Protocolo"),
                DhAutorizacao = ReadNullableDateTimeOffset(reader, "DhAutorizacao"),
                CriadoEm = reader.GetDateTimeOffset(reader.GetOrdinal("CriadoEm")),
                XmlAssinado = ReadNullableString(reader, "XmlAssinado"),
                XmlProtocolado = ReadNullableString(reader, "XmlProtocolado"),
                XmlCancelamento = ReadNullableString(reader, "XmlCancelamento")
            });
        }

        return rows;
    }

    /// <summary>Reenfileira um documento rejeitado definitivamente. Se foi duplicidade (539) ou mudança de série, aloca nova numeração.</summary>
    public async Task<bool> ReemitirAsync(string companyId, string id)
    {
        const int MaxRetries = 5;

        for (int attempt = 0; attempt < MaxRetries; attempt++)
        {
            await using var db = await connection.OpenConnectionAsync();
            await using var transaction = (SqlTransaction)await db.BeginTransactionAsync();
            try
            {
                int? codigoStatus;
                int serieAtual;
                await using (var checkCmd = new SqlCommand(
                    "SELECT CodigoStatus, Serie FROM DocumentosFiscais WHERE Id = @Id AND CompanyId = @CompanyId AND Status = 4;",
                    db, transaction))
                {
                    checkCmd.Parameters.AddWithValue("@Id", id);
                    checkCmd.Parameters.AddWithValue("@CompanyId", companyId);
                    await using var reader = await checkCmd.ExecuteReaderAsync();
                    if (!await reader.ReadAsync()) return false;
                    codigoStatus = reader.IsDBNull(0) ? null : Convert.ToInt32(reader.GetValue(0));
                    serieAtual = Convert.ToInt32(reader.GetValue(1));
                }

                var (ambiente, serieConfigurada) = await ObterConfigFiscalAsync(db, transaction, companyId, default);

                // Se a nota foi rejeitada por duplicidade (539) ou sua série for diferente da série ativa na empresa:
                // aloca nova numeração na série ativa para permitir que a SEFAZ autorize sem erro de duplicidade.
                var precisaNovaNumeracao = codigoStatus == 539 || serieAtual != serieConfigurada;

                int? novoNumero = null;
                if (precisaNovaNumeracao)
                {
                    novoNumero = await AlocarProximoNumeroAsync(db, transaction, companyId, ModeloNfce, serieConfigurada, ambiente, default);
                }

                var sql = precisaNovaNumeracao
                    ? """
                      UPDATE DocumentosFiscais
                         SET Status = 1,
                             Serie = @NovaSerie,
                             NumeroNf = @NovoNumero,
                             Tentativas = 0,
                             ProximaTentativaEm = NULL,
                             UltimoErro = NULL,
                             ChaveAcesso = NULL,
                             XmlAssinado = NULL,
                             XmlProtocolado = NULL,
                             AtualizadoEm = SYSDATETIMEOFFSET()
                       WHERE Id = @Id AND CompanyId = @CompanyId AND Status = 4;
                      """
                    : """
                      UPDATE DocumentosFiscais
                         SET Status = 1,
                             Tentativas = 0,
                             ProximaTentativaEm = NULL,
                             UltimoErro = NULL,
                             ChaveAcesso = NULL,
                             XmlAssinado = NULL,
                             XmlProtocolado = NULL,
                             AtualizadoEm = SYSDATETIMEOFFSET()
                       WHERE Id = @Id AND CompanyId = @CompanyId AND Status = 4;
                      """;

                await using (var updateCmd = new SqlCommand(sql, db, transaction))
                {
                    updateCmd.Parameters.AddWithValue("@Id", id);
                    updateCmd.Parameters.AddWithValue("@CompanyId", companyId);
                    if (precisaNovaNumeracao)
                    {
                        updateCmd.Parameters.AddWithValue("@NovaSerie", serieConfigurada);
                        updateCmd.Parameters.AddWithValue("@NovoNumero", novoNumero!.Value);
                    }
                    var rows = await updateCmd.ExecuteNonQueryAsync();
                    await transaction.CommitAsync();
                    return rows > 0;
                }
            }
            catch (SqlException ex) when (ex.Number == 2627 && attempt < MaxRetries - 1)
            {
                // Constraint UQ_DocFiscais_Numeracao — o número alocado já existe (sequência dessincronizada).
                // Rollback e tenta novamente; AlocarProximoNumeroAsync avançará para o próximo número.
                await transaction.RollbackAsync();
            }
            catch
            {
                await transaction.RollbackAsync();
                throw;
            }
        }

        return false;
    }

    /// <summary>Dados mínimos para montar um evento (cancelamento) sobre um documento autorizado.</summary>
    public async Task<(string ChaveAcesso, string Protocolo)?> ObterParaCancelamentoAsync(string companyId, string id)
    {
        const string sql = """
            SELECT ChaveAcesso, Protocolo
            FROM DocumentosFiscais
            WHERE Id = @Id AND CompanyId = @CompanyId AND Status = 3;
            """;

        await using var db = await connection.OpenConnectionAsync();
        await using var command = new SqlCommand(sql, db);
        command.Parameters.AddWithValue("@Id", id);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        await using var reader = await command.ExecuteReaderAsync();
        if (!await reader.ReadAsync()) return null;

        var chave = ReadNullableString(reader, "ChaveAcesso");
        var protocolo = ReadNullableString(reader, "Protocolo");
        return chave is null || protocolo is null ? null : (chave, protocolo);
    }

    /// <summary>
    /// Registra o cancelamento em XmlCancelamento — nunca em XmlProtocolado, que precisa
    /// continuar guardando o nfeProc original autorizado (retenção de 5 anos).
    /// </summary>
    public async Task MarcarCanceladoAsync(string id, ResultadoFiscal resultado, CancellationToken ct = default)
    {
        await using var db = await connection.OpenConnectionAsync(ct);
        await using var command = new SqlCommand(
            """
            UPDATE DocumentosFiscais
               SET Status = @Status,
                   CodigoStatus = @CodigoStatus,
                   MotivoStatus = @MotivoStatus,
                   XmlCancelamento = @XmlCancelamento,
                   AtualizadoEm = SYSDATETIMEOFFSET()
             WHERE Id = @Id;
            """,
            db);
        command.Parameters.AddWithValue("@Id", id);
        command.Parameters.AddWithValue("@Status", (int)StatusDocumentoFiscal.Cancelado);
        command.Parameters.AddWithValue("@CodigoStatus", resultado.CodigoStatus);
        command.Parameters.AddWithValue("@MotivoStatus", Truncar(resultado.MotivoStatus, 500));
        command.Parameters.AddWithValue("@XmlCancelamento", (object?)resultado.XmlProtocolado ?? DBNull.Value);
        await command.ExecuteNonQueryAsync(ct);
    }

    /// <summary>
    /// Marca o documento fiscal como cancelado com registro de auditoria do supervisor e operador,
    /// atualiza o status da venda para cancelada e estorna automaticamente as quantidades vendidas no estoque.
    /// Executado em transação atômica.
    /// </summary>
    public async Task MarcarCanceladoComAuditoriaAsync(
        string companyId,
        string id,
        ResultadoFiscal resultado,
        string supervisorId,
        string supervisorNome,
        string operadorNome,
        string justificativa,
        CancellationToken ct = default)
    {
        await using var db = await connection.OpenConnectionAsync(ct);
        await using var transaction = (SqlTransaction)await db.BeginTransactionAsync(ct);
        try
        {
            // 1. Atualiza DocumentosFiscais com dados da SEFAZ e auditoria de cancelamento
            await using (var cmdDoc = new SqlCommand(
                """
                UPDATE DocumentosFiscais
                   SET Status = @Status,
                       CodigoStatus = @CodigoStatus,
                       MotivoStatus = @MotivoStatus,
                       XmlCancelamento = @XmlCancelamento,
                       CanceladoPorSupervisorId = @SupervisorId,
                       CanceladoPorSupervisorNome = @SupervisorNome,
                       CanceladoPorOperador = @OperadorNome,
                       CanceladoJustificativa = @Justificativa,
                       AtualizadoEm = SYSDATETIMEOFFSET()
                 WHERE Id = @Id AND CompanyId = @CompanyId;
                """,
                db,
                transaction))
            {
                cmdDoc.Parameters.AddWithValue("@Id", id);
                cmdDoc.Parameters.AddWithValue("@CompanyId", companyId);
                cmdDoc.Parameters.AddWithValue("@Status", (int)StatusDocumentoFiscal.Cancelado);
                cmdDoc.Parameters.AddWithValue("@CodigoStatus", resultado.CodigoStatus);
                cmdDoc.Parameters.AddWithValue("@MotivoStatus", Truncar(resultado.MotivoStatus, 500));
                cmdDoc.Parameters.AddWithValue("@XmlCancelamento", (object?)resultado.XmlProtocolado ?? DBNull.Value);
                cmdDoc.Parameters.AddWithValue("@SupervisorId", (object?)supervisorId ?? DBNull.Value);
                cmdDoc.Parameters.AddWithValue("@SupervisorNome", (object?)supervisorNome ?? DBNull.Value);
                cmdDoc.Parameters.AddWithValue("@OperadorNome", (object?)operadorNome ?? DBNull.Value);
                cmdDoc.Parameters.AddWithValue("@Justificativa", (object?)justificativa ?? DBNull.Value);
                await cmdDoc.ExecuteNonQueryAsync(ct);
            }

            // 2. Recupera a Venda vinculada
            string? vendaId = null;
            string? saleNumber = null;
            await using (var cmdGetVenda = new SqlCommand(
                "SELECT d.VendaId, v.SaleNumber FROM DocumentosFiscais d LEFT JOIN Vendas v ON v.Id = d.VendaId WHERE d.Id = @Id AND d.CompanyId = @CompanyId;",
                db,
                transaction))
            {
                cmdGetVenda.Parameters.AddWithValue("@Id", id);
                cmdGetVenda.Parameters.AddWithValue("@CompanyId", companyId);
                await using var reader = await cmdGetVenda.ExecuteReaderAsync(ct);
                if (await reader.ReadAsync(ct))
                {
                    vendaId = ReadNullableString(reader, "VendaId");
                    saleNumber = ReadNullableString(reader, "SaleNumber");
                }
            }

            if (!string.IsNullOrWhiteSpace(vendaId))
            {
                // 3. Atualiza o status da Venda para cancelada
                await using (var cmdVenda = new SqlCommand(
                    "UPDATE Vendas SET Status = 'cancelada' WHERE Id = @VendaId AND CompanyId = @CompanyId;",
                    db,
                    transaction))
                {
                    cmdVenda.Parameters.AddWithValue("@VendaId", vendaId);
                    cmdVenda.Parameters.AddWithValue("@CompanyId", companyId);
                    await cmdVenda.ExecuteNonQueryAsync(ct);
                }

                // 4. Estorno automático de estoque dos itens vendidos
                await using (var cmdEstorno = new SqlCommand(
                    """
                    UPDATE p
                       SET p.ProductQnt = p.ProductQnt + vi.Quantity,
                           p.TotalPriceOnProduct = p.ProductUnitPrice * (p.ProductQnt + vi.Quantity)
                      FROM Produtos p
                      INNER JOIN VendaItens vi ON vi.ProductCode = p.ProductCode AND p.CompanyId = @CompanyId
                     WHERE vi.VendaId = @VendaId;
                    """,
                    db,
                    transaction))
                {
                    cmdEstorno.Parameters.AddWithValue("@VendaId", vendaId);
                    cmdEstorno.Parameters.AddWithValue("@CompanyId", companyId);
                    await cmdEstorno.ExecuteNonQueryAsync(ct);
                }

                // 5. Registra na trilha genérica AuditLog
                await using (var cmdAudit = new SqlCommand(
                    """
                    INSERT INTO AuditLog (CompanyId, UserId, UserName, EventType, EntityType, EntityId, Description, Ip)
                    VALUES (@CompanyId, @UserId, @UserName, 'NfceCancelamento', 'DocumentosFiscais', @EntityId, @Description, 'PDV');
                    """,
                    db,
                    transaction))
                {
                    cmdAudit.Parameters.AddWithValue("@CompanyId", companyId);
                    cmdAudit.Parameters.AddWithValue("@UserId", supervisorId);
                    cmdAudit.Parameters.AddWithValue("@UserName", supervisorNome);
                    cmdAudit.Parameters.AddWithValue("@EntityId", id);
                    cmdAudit.Parameters.AddWithValue("@Description", $"NFC-e cancelada no PDV para a venda {saleNumber ?? vendaId}. Caixa: {operadorNome}. Motivo: {justificativa}");
                    await cmdAudit.ExecuteNonQueryAsync(ct);
                }
            }

            await transaction.CommitAsync(ct);
        }
        catch
        {
            await transaction.RollbackAsync(ct);
            throw;
        }
    }

    /// <summary>
    /// Obtém dados da NFC-e original para emissão da NF-e de Entrada (Devolução),
    /// verificando se está autorizada e se não possui devolução anterior.
    /// </summary>
    public async Task<(string DocId, string VendaId, string ChaveAcesso, string Protocolo, List<ItemFiscal> Itens, decimal TotalVenda, string? CustomerCpf, string? CustomerName)?> ObterParaDevolucaoNfceAsync(
        string companyId, string idOrChave, CancellationToken ct = default)
    {
        const string sql = """
            SELECT TOP 1 d.Id, d.VendaId, d.ChaveAcesso, d.Protocolo, d.Status,
                   v.CustomerCpf, v.CustomerName, v.TotalAmount, v.Status AS VendaStatus
            FROM DocumentosFiscais d
            INNER JOIN Vendas v ON v.Id = d.VendaId
            WHERE d.CompanyId = @CompanyId
              AND (d.Id = @Termo OR d.ChaveAcesso = @Termo)
              AND d.Modelo = 65;
            """;

        await using var db = await connection.OpenConnectionAsync(ct);
        await using var command = new SqlCommand(sql, db);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        command.Parameters.AddWithValue("@Termo", idOrChave.Trim());

        string docId, vendaId, chave, protocolo;
        string? custCpf, custName;
        decimal totalVenda;
        StatusDocumentoFiscal status;
        string? vendaStatus;

        await using (var reader = await command.ExecuteReaderAsync(ct))
        {
            if (!await reader.ReadAsync(ct)) return null;

            docId = ReadString(reader, "Id");
            vendaId = ReadString(reader, "VendaId");
            chave = ReadString(reader, "ChaveAcesso");
            protocolo = ReadNullableString(reader, "Protocolo") ?? string.Empty;
            status = (StatusDocumentoFiscal)ReadInt(reader, "Status");
            custCpf = ReadNullableString(reader, "CustomerCpf");
            custName = ReadNullableString(reader, "CustomerName");
            var totalRaw = reader.GetValue(reader.GetOrdinal("TotalAmount"));
            totalVenda = totalRaw switch
            {
                decimal dec => dec,
                string str => HorusMoneyFormat.ParseDecimal(str),
                _ => 0m
            };
            vendaStatus = ReadNullableString(reader, "VendaStatus");
        }

        if (status != StatusDocumentoFiscal.Autorizado)
            throw new InvalidOperationException("Apenas NFC-e autorizada perante a SEFAZ pode ser objeto de devolução.");

        if (string.Equals(vendaStatus, "cancelada", StringComparison.OrdinalIgnoreCase))
            throw new InvalidOperationException("Esta venda já foi cancelada anteriormente.");

        // Verifica se já existe NF-e de devolução emitida ou em andamento para esta NFC-e
        const string checkDevolucaoSql = """
            SELECT TOP 1 NumeroNf, Serie, ChaveAcesso
            FROM DocumentosFiscais
            WHERE CompanyId = @CompanyId
              AND (DocumentoOrigemId = @DocId OR ChaveReferenciada = @Chave)
              AND Status IN (1, 2, 3);
            """;

        await using (var cmdCheck = new SqlCommand(checkDevolucaoSql, db))
        {
            cmdCheck.Parameters.AddWithValue("@CompanyId", companyId);
            cmdCheck.Parameters.AddWithValue("@DocId", docId);
            cmdCheck.Parameters.AddWithValue("@Chave", chave);
            await using var readerCheck = await cmdCheck.ExecuteReaderAsync(ct);
            if (await readerCheck.ReadAsync(ct))
            {
                var numNf = ReadInt(readerCheck, "NumeroNf");
                var serieNf = ReadInt(readerCheck, "Serie");
                throw new InvalidOperationException($"Já existe uma NF-e de Devolução (Nº {numNf}, Série {serieNf}) registrada para esta NFC-e.");
            }
        }

        // Carrega os itens da venda
        var linhas = await historicoVendasAB.ObterPorIdAsync(companyId, vendaId);
        if (linhas.Count == 0)
            throw new InvalidOperationException($"Nenhum item encontrado para a venda {vendaId}.");

        var itens = new List<ItemFiscal>();
        for (var index = 0; index < linhas.Count; index++)
        {
            var linha = linhas[index];
            var produto = await produtoAB.ObterPorCodigoAsync(companyId, linha.ProductCode)
                ?? throw new InvalidOperationException($"Produto {linha.ProductCode} não encontrado.");

            var valorUnitario = HorusMoneyFormat.ParseDecimal(linha.UnitPrice);
            var valorBruto = Math.Round(valorUnitario * linha.Quantity, 2, MidpointRounding.AwayFromZero);

            itens.Add(new ItemFiscal
            {
                Numero = index + 1,
                CodigoProduto = produto.ProductCode,
                Descricao = produto.ProductName,
                Gtin = produto.Gtin,
                Ncm = produto.Ncm,
                Cest = produto.Cest,
                Cfop = ConverterCfopParaDevolucaoEntrada(produto.Cfop),
                Origem = produto.OrigemMercadoria,
                UnidadeComercial = produto.UnidadeComercial,
                Quantidade = linha.Quantity,
                ValorUnitario = valorUnitario,
                ValorTotal = valorBruto,
                Desconto = linha.Desconto,
                Csosn = produto.CsosnIcms,
                CstIcms = produto.CstIcms,
                AliquotaIcms = produto.AliquotaIcms,
                CstPis = produto.CstPis,
                CstCofins = produto.CstCofins,
                CstIbsCbs = produto.CstIbsCbs,
                CClassTrib = produto.CClassTrib
            });
        }

        return (docId, vendaId, chave, protocolo, itens, totalVenda, custCpf, custName);
    }

    private static string ConverterCfopParaDevolucaoEntrada(string? cfop)
    {
        if (string.IsNullOrWhiteSpace(cfop)) return "1202";
        var digits = new string(cfop.Where(char.IsDigit).ToArray());
        return digits switch
        {
            "5102" => "1202",
            "5101" => "1201",
            "5405" => "1411",
            "5403" => "1410",
            "6102" => "2202",
            "6101" => "2201",
            "6405" => "2411",
            "6403" => "2410",
            var s when s.StartsWith("5") && s.Length == 4 => "1" + s[1..],
            var s when s.StartsWith("6") && s.Length == 4 => "2" + s[1..],
            _ => cfop
        };
    }

    /// <summary>Aloca o próximo número e série de NF-e (Modelo 55) para emissão imediata.</summary>
    public async Task<(int Numero, int Serie, byte Ambiente)> AlocarNumeroNfeAsync(string companyId, CancellationToken ct = default)
    {
        await using var db = await connection.OpenConnectionAsync(ct);
        await using var transaction = (SqlTransaction)await db.BeginTransactionAsync(ct);
        try
        {
            var (ambiente, serie) = await ObterConfigFiscalNfeAsync(db, transaction, companyId, ct);
            var numero = await AlocarProximoNumeroAsync(db, transaction, companyId, ModeloNfe, serie, ambiente, ct);
            await transaction.CommitAsync(ct);
            return (numero, serie, ambiente);
        }
        catch
        {
            await transaction.RollbackAsync(ct);
            throw;
        }
    }

    /// <summary>
    /// Grava a NF-e Modelo 55 autorizada de devolução, estorna os itens no estoque físico em Produtos,
    /// atualiza o status da venda e registra no AuditLog.
    /// </summary>
    public async Task<string> GravarDevolucaoNfeComAuditoriaAsync(
        string companyId,
        string vendaId,
        string nfceOrigemId,
        string chaveNfceOrigem,
        int numeroNf,
        int serie,
        byte ambiente,
        ResultadoFiscal resultado,
        string supervisorId,
        string supervisorNome,
        string operadorNome,
        string justificativa,
        CancellationToken ct = default)
    {
        var idNfe = $"nfe-{DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()}";

        await using var db = await connection.OpenConnectionAsync(ct);
        await using var transaction = (SqlTransaction)await db.BeginTransactionAsync(ct);
        try
        {
            // 1. Insere o documento fiscal Modelo 55 de devolução
            const string insertSql = """
                INSERT INTO DocumentosFiscais
                    (Id, CompanyId, VendaId, Modelo, Serie, NumeroNf, Ambiente, Status, TpEmis,
                     ChaveAcesso, Protocolo, DhAutorizacao, CodigoStatus, MotivoStatus,
                     XmlAssinado, XmlProtocolado, ChaveReferenciada, DocumentoOrigemId,
                     DevolvidoPorSupervisorId, DevolvidoPorSupervisorNome, DevolvidoPorOperador,
                     DevolvidoJustificativa, NaturezaOperacao, CriadoEm, AtualizadoEm)
                VALUES
                    (@Id, @CompanyId, @VendaId, 55, @Serie, @NumeroNf, @Ambiente, @Status, 1,
                     @ChaveAcesso, @Protocolo, @DhAutorizacao, @CodigoStatus, @MotivoStatus,
                     @XmlAssinado, @XmlProtocolado, @ChaveReferenciada, @DocumentoOrigemId,
                     @SupervisorId, @SupervisorNome, @OperadorNome,
                     @Justificativa, 'DEVOLUCAO DE VENDA', SYSDATETIMEOFFSET(), SYSDATETIMEOFFSET());
                """;

            await using (var cmdInsert = new SqlCommand(insertSql, db, transaction))
            {
                cmdInsert.Parameters.AddWithValue("@Id", idNfe);
                cmdInsert.Parameters.AddWithValue("@CompanyId", companyId);
                cmdInsert.Parameters.AddWithValue("@VendaId", vendaId);
                cmdInsert.Parameters.AddWithValue("@Serie", serie);
                cmdInsert.Parameters.AddWithValue("@NumeroNf", numeroNf);
                cmdInsert.Parameters.AddWithValue("@Ambiente", ambiente);
                cmdInsert.Parameters.AddWithValue("@Status", (int)resultado.Status);
                cmdInsert.Parameters.AddWithValue("@ChaveAcesso", (object?)resultado.ChaveAcesso ?? DBNull.Value);
                cmdInsert.Parameters.AddWithValue("@Protocolo", (object?)resultado.Protocolo ?? DBNull.Value);
                cmdInsert.Parameters.AddWithValue("@DhAutorizacao", (object?)resultado.DhAutorizacao ?? DateTimeOffset.UtcNow);
                cmdInsert.Parameters.AddWithValue("@CodigoStatus", resultado.CodigoStatus);
                cmdInsert.Parameters.AddWithValue("@MotivoStatus", Truncar(resultado.MotivoStatus, 500));
                cmdInsert.Parameters.AddWithValue("@XmlAssinado", (object?)resultado.XmlAssinado ?? DBNull.Value);
                cmdInsert.Parameters.AddWithValue("@XmlProtocolado", (object?)resultado.XmlProtocolado ?? DBNull.Value);
                cmdInsert.Parameters.AddWithValue("@ChaveReferenciada", chaveNfceOrigem);
                cmdInsert.Parameters.AddWithValue("@DocumentoOrigemId", nfceOrigemId);
                cmdInsert.Parameters.AddWithValue("@SupervisorId", supervisorId);
                cmdInsert.Parameters.AddWithValue("@SupervisorNome", supervisorNome);
                cmdInsert.Parameters.AddWithValue("@OperadorNome", operadorNome);
                cmdInsert.Parameters.AddWithValue("@Justificativa", justificativa);
                await cmdInsert.ExecuteNonQueryAsync(ct);
            }

            // 2. Atualiza status da venda para 'estornada'
            await using (var cmdVenda = new SqlCommand(
                "UPDATE Vendas SET Status = 'estornada' WHERE Id = @VendaId AND CompanyId = @CompanyId;",
                db,
                transaction))
            {
                cmdVenda.Parameters.AddWithValue("@VendaId", vendaId);
                cmdVenda.Parameters.AddWithValue("@CompanyId", companyId);
                await cmdVenda.ExecuteNonQueryAsync(ct);
            }

            // 3. Estorno automático de estoque dos produtos da venda
            await using (var cmdEstorno = new SqlCommand(
                """
                UPDATE p
                   SET p.ProductQnt = p.ProductQnt + vi.Quantity,
                       p.TotalPriceOnProduct = p.ProductUnitPrice * (p.ProductQnt + vi.Quantity)
                  FROM Produtos p
                  INNER JOIN VendaItens vi ON vi.ProductCode = p.ProductCode AND p.CompanyId = @CompanyId
                 WHERE vi.VendaId = @VendaId;
                """,
                db,
                transaction))
            {
                cmdEstorno.Parameters.AddWithValue("@VendaId", vendaId);
                cmdEstorno.Parameters.AddWithValue("@CompanyId", companyId);
                await cmdEstorno.ExecuteNonQueryAsync(ct);
            }

            // 4. Registra no AuditLog
            await using (var cmdAudit = new SqlCommand(
                """
                INSERT INTO AuditLog (CompanyId, UserId, UserName, EventType, EntityType, EntityId, Description, Ip)
                VALUES (@CompanyId, @UserId, @UserName, 'NfeDevolucaoEntrada', 'DocumentosFiscais', @EntityId, @Description, 'PDV');
                """,
                db,
                transaction))
            {
                cmdAudit.Parameters.AddWithValue("@CompanyId", companyId);
                cmdAudit.Parameters.AddWithValue("@UserId", supervisorId);
                cmdAudit.Parameters.AddWithValue("@UserName", supervisorNome);
                cmdAudit.Parameters.AddWithValue("@EntityId", idNfe);
                cmdAudit.Parameters.AddWithValue("@Description",
                    $"NF-e {numeroNf} Serie {serie} de Devolucao emitida perante a SEFAZ referenciando NFC-e {chaveNfceOrigem}. Operador: {operadorNome}. Justificativa: {justificativa}");
                await cmdAudit.ExecuteNonQueryAsync(ct);
            }

            await transaction.CommitAsync(ct);
            return idNfe;
        }
        catch
        {
            await transaction.RollbackAsync(ct);
            throw;
        }
    }

    /// <summary>Extração leve do link do QR Code já embutido no XML autorizado (nfeProc/infNFeSupl) ou assinado.</summary>
    private static string? ExtrairQrCode(string? xml)
    {
        if (string.IsNullOrWhiteSpace(xml)) return null;

        var match = System.Text.RegularExpressions.Regex.Match(
            xml,
            @"<qrCode[^>]*>(?:<!\[CDATA\[)?(.*?)(?:\]\]>)?<\/qrCode>",
            System.Text.RegularExpressions.RegexOptions.IgnoreCase | System.Text.RegularExpressions.RegexOptions.Singleline);

        if (!match.Success) return null;

        var conteudo = match.Groups[1].Value.Trim();
        if (string.IsNullOrWhiteSpace(conteudo)) return null;

        return System.Net.WebUtility.HtmlDecode(conteudo);
    }

    public Task MarcarAutorizadoAsync(string id, ResultadoFiscal resultado, CancellationToken ct = default)
        => AtualizarStatusAsync(
            id,
            (int)StatusDocumentoFiscal.Autorizado,
            resultado,
            ct,
            extra: "ChaveAcesso = @ChaveAcesso, Protocolo = @Protocolo, DhAutorizacao = @DhAutorizacao, " +
                   "XmlAssinado = @XmlAssinado, XmlProtocolado = @XmlProtocolado, ProximaTentativaEm = NULL");

    public Task MarcarRejeitadoAsync(string id, ResultadoFiscal resultado, CancellationToken ct = default)
        => AtualizarStatusAsync(
            id,
            (int)StatusDocumentoFiscal.Rejeitado,
            resultado,
            ct,
            extra: "ChaveAcesso = @ChaveAcesso, XmlAssinado = @XmlAssinado, ProximaTentativaEm = NULL");

    public Task MarcarDenegadoAsync(string id, ResultadoFiscal resultado, CancellationToken ct = default)
        => AtualizarStatusAsync(
            id,
            (int)StatusDocumentoFiscal.Denegado,
            resultado,
            ct,
            extra: "ChaveAcesso = @ChaveAcesso, XmlAssinado = @XmlAssinado, ProximaTentativaEm = NULL");

    public async Task MarcarErroAsync(string id, string motivo, DateTimeOffset? proximaTentativa, CancellationToken ct = default)
    {
        await using var db = await connection.OpenConnectionAsync(ct);
        await using var command = new SqlCommand(
            """
            UPDATE DocumentosFiscais
               SET Tentativas = Tentativas + 1,
                   UltimoErro = @UltimoErro,
                   ProximaTentativaEm = @ProximaTentativaEm,
                   AtualizadoEm = SYSDATETIMEOFFSET()
             WHERE Id = @Id;
            """,
            db);
        command.Parameters.AddWithValue("@Id", id);
        command.Parameters.AddWithValue("@UltimoErro", Truncar(motivo, 1000));
        command.Parameters.AddWithValue("@ProximaTentativaEm", proximaTentativa.HasValue ? proximaTentativa.Value : (object)DBNull.Value);
        await command.ExecuteNonQueryAsync(ct);
    }

    public async Task PromoverParaContingenciaAsync(string id, string justificativa, CancellationToken ct = default)
    {
        await using var db = await connection.OpenConnectionAsync(ct);
        await using var command = new SqlCommand(
            """
            UPDATE DocumentosFiscais
               SET Status = @Status,
                   TpEmis = 9,
                   DhContingencia = SYSDATETIMEOFFSET(),
                   JustContingencia = @Justificativa,
                   Tentativas = Tentativas + 1,
                   ProximaTentativaEm = NULL,
                   AtualizadoEm = SYSDATETIMEOFFSET()
             WHERE Id = @Id;
            """,
            db);
        command.Parameters.AddWithValue("@Id", id);
        command.Parameters.AddWithValue("@Status", (int)StatusDocumentoFiscal.ContingenciaPendente);
        command.Parameters.AddWithValue("@Justificativa", Truncar(justificativa, 256));
        await command.ExecuteNonQueryAsync(ct);
    }

    private async Task AtualizarStatusAsync(
        string id, int status, ResultadoFiscal resultado, CancellationToken ct, string extra)
    {
        await using var db = await connection.OpenConnectionAsync(ct);
        try
        {
            await ExecutarAtualizarStatusAsync(db, id, status, resultado, ct, extra, persistirChave: true);
        }
        catch (SqlException ex) when (ex.Number is 2601 or 2627)
        {
            // Em caso de colisão com chave única legada (ex.: chave sem randomização gerada em ambiente anterior),
            // salva o status, motivo e xml sem forçar a inserção da chave conflitante.
            await ExecutarAtualizarStatusAsync(db, id, status, resultado, ct, extra, persistirChave: false);
        }
    }

    private static async Task ExecutarAtualizarStatusAsync(
        SqlConnection db, string id, int status, ResultadoFiscal resultado, CancellationToken ct, string extra, bool persistirChave)
    {
        var extraFinal = extra;
        if (!persistirChave)
        {
            extraFinal = extraFinal.Replace("ChaveAcesso = @ChaveAcesso,", string.Empty)
                                   .Replace("ChaveAcesso = @ChaveAcesso", string.Empty)
                                   .Trim().TrimEnd(',');
        }

        await using var command = new SqlCommand(
            $"""
             UPDATE DocumentosFiscais
                SET Status = @Status,
                    CodigoStatus = @CodigoStatus,
                    MotivoStatus = @MotivoStatus,
                    {extraFinal},
                    AtualizadoEm = SYSDATETIMEOFFSET()
              WHERE Id = @Id;
             """,
            db);
        command.Parameters.AddWithValue("@Id", id);
        command.Parameters.AddWithValue("@Status", status);
        command.Parameters.AddWithValue("@CodigoStatus", resultado.CodigoStatus);
        command.Parameters.AddWithValue("@MotivoStatus", Truncar(resultado.MotivoStatus, 500));
        command.Parameters.AddWithValue("@ChaveAcesso", (object?)resultado.ChaveAcesso ?? DBNull.Value);
        command.Parameters.AddWithValue("@Protocolo", (object?)resultado.Protocolo ?? DBNull.Value);
        command.Parameters.AddWithValue("@DhAutorizacao", resultado.DhAutorizacao.HasValue ? resultado.DhAutorizacao.Value : (object)DBNull.Value);
        command.Parameters.AddWithValue("@XmlAssinado", (object?)resultado.XmlAssinado ?? DBNull.Value);
        command.Parameters.AddWithValue("@XmlProtocolado", (object?)resultado.XmlProtocolado ?? DBNull.Value);
        await command.ExecuteNonQueryAsync(ct);
    }

    private async Task<(byte Ambiente, int Serie)> ObterConfigFiscalAsync(
        SqlConnection db, SqlTransaction transaction, string companyId, CancellationToken ct)
    {
        await using var command = new SqlCommand(
            "SELECT AmbienteFiscal, ISNULL(SerieNfce, 2) FROM Empresas WHERE Id = @CompanyId;", db, transaction);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        await using var reader = await command.ExecuteReaderAsync(ct);
        if (await reader.ReadAsync(ct))
        {
            var amb = reader.IsDBNull(0) ? (byte)2 : reader.GetByte(0);
            var serie = reader.IsDBNull(1) ? 2 : Convert.ToInt32(reader.GetValue(1));
            return (amb, serie);
        }
        return (2, 2);
    }

    private static async Task<int> AlocarProximoNumeroAsync(
        SqlConnection db, SqlTransaction transaction, string companyId, short modelo, int serie, byte ambiente, CancellationToken ct)
    {
        await using (var ensure = new SqlCommand(
                         """
                         IF NOT EXISTS (
                             SELECT 1 FROM FiscalSequencias
                              WHERE CompanyId = @CompanyId AND Modelo = @Modelo AND Serie = @Serie AND Ambiente = @Ambiente)
                             INSERT INTO FiscalSequencias (CompanyId, Modelo, Serie, Ambiente, ProximoNumero)
                             VALUES (@CompanyId, @Modelo, @Serie, @Ambiente, 1);
                         """,
                         db,
                         transaction))
        {
            ensure.Parameters.AddWithValue("@CompanyId", companyId);
            ensure.Parameters.AddWithValue("@Modelo", modelo);
            ensure.Parameters.AddWithValue("@Serie", serie);
            ensure.Parameters.AddWithValue("@Ambiente", ambiente);
            await ensure.ExecuteNonQueryAsync(ct);
        }

        await using var update = new SqlCommand(
            """
            UPDATE FiscalSequencias WITH (UPDLOCK, HOLDLOCK)
               SET ProximoNumero = ProximoNumero + 1,
                   AtualizadoEm = SYSDATETIMEOFFSET()
             OUTPUT DELETED.ProximoNumero
             WHERE CompanyId = @CompanyId AND Modelo = @Modelo AND Serie = @Serie AND Ambiente = @Ambiente;
            """,
            db,
            transaction);
        update.Parameters.AddWithValue("@CompanyId", companyId);
        update.Parameters.AddWithValue("@Modelo", modelo);
        update.Parameters.AddWithValue("@Serie", serie);
        update.Parameters.AddWithValue("@Ambiente", ambiente);
        var alocado = await update.ExecuteScalarAsync(ct);
        return Convert.ToInt32(alocado);
    }

    private async Task<DestinatarioFiscal?> MontarDestinatarioAsync(string companyId, string customerCpf, string customerName)
    {
        var digits = new string(customerCpf.Where(char.IsDigit).ToArray());
        if (digits.Length is not (11 or 14)) return null;

        var cliente = await clienteAB.ObterPorDocumentoAsync(companyId, digits);
        return new DestinatarioFiscal
        {
            CpfCnpj = digits,
            Nome = customerName,
            IndIeDest = cliente?.IndIeDest ?? 9,
            InscricaoEstadual = cliente?.InscricaoEstadual
        };
    }

    /// <summary>tPag: 01 dinheiro, 03 crédito, 04 débito, 05 crédito loja / fiado, 17 PIX dinâmico.</summary>
    private static string MapearFormaPagamento(string paymentType)
    {
        var normalized = paymentType.Trim().ToLowerInvariant();
        if (normalized.Contains("fiado") || normalized.Contains("crediario") || normalized.Contains("crediário")) return "05";
        if (normalized.Contains("pix")) return "17";
        if (normalized.Contains("debit") || normalized.Contains("débit")) return "04";
        if (normalized.Contains("credit") || normalized.Contains("crédit")) return "03";
        return "01"; // dinheiro / fallback
    }

    private static string Truncar(string value, int max)
        => value.Length <= max ? value : value[..max];

    private static string ReadString(SqlDataReader reader, string name)
    {
        var ordinal = reader.GetOrdinal(name);
        return reader.IsDBNull(ordinal) ? string.Empty : reader.GetString(ordinal);
    }

    private static string? ReadNullableString(SqlDataReader reader, string name)
    {
        var ordinal = reader.GetOrdinal(name);
        return reader.IsDBNull(ordinal) ? null : reader.GetString(ordinal);
    }

    private static int ReadInt(SqlDataReader reader, string name)
    {
        var ordinal = reader.GetOrdinal(name);
        return reader.IsDBNull(ordinal) ? 0 : Convert.ToInt32(reader.GetValue(ordinal));
    }

    private static DateTimeOffset? ReadNullableDateTimeOffset(SqlDataReader reader, string name)
    {
        var ordinal = reader.GetOrdinal(name);
        return reader.IsDBNull(ordinal) ? null : reader.GetDateTimeOffset(ordinal);
    }
}

/// <summary>Linha da lista de documentos fiscais (tela Fiscal).</summary>
public record DocumentoFiscalResumo
{
    public required string Id { get; init; }
    public required string SaleNumber { get; init; }
    public required int Serie { get; init; }
    public required int NumeroNf { get; init; }
    public required StatusDocumentoFiscal Status { get; init; }
    public string? ChaveAcesso { get; init; }
    public string? Protocolo { get; init; }
    public string? MotivoStatus { get; init; }
    public DateTimeOffset? DhAutorizacao { get; init; }
    public required DateTimeOffset CriadoEm { get; init; }
    public required int Tentativas { get; init; }
    public string? TotalAmount { get; init; }
    public string? CustomerName { get; init; }
    public string? CustomerCpf { get; init; }
    public string? PaymentType { get; init; }
    public bool HasXml { get; init; }
    public bool HasCancelXml { get; init; }
}

/// <summary>Item de produto vinculado ao documento fiscal.</summary>
public sealed record DocumentoFiscalItemResumo
{
    public required string ProductCode { get; init; }
    public required string ProductName { get; init; }
    public required decimal Quantity { get; init; }
    public required string UnitPrice { get; init; }
    public required string ItemTotal { get; init; }
    public string? Ncm { get; init; }
    public string? Cest { get; init; }
    public string? Cfop { get; init; }
    public string? UnidadeComercial { get; init; }
}

/// <summary>Detalhe de um documento fiscal, com o link do QR Code para montar o DANFE em tela.</summary>
public sealed record DocumentoFiscalDetalhe : DocumentoFiscalResumo
{
    public string? QrCodeUrl { get; init; }
}

/// <summary>Registro com XMLs completos para exportação mensal de contabilidade em arquivo ZIP.</summary>
public sealed record DocumentoFiscalExportacaoXml
{
    public required string Id { get; init; }
    public required string SaleNumber { get; init; }
    public required int Serie { get; init; }
    public required int NumeroNf { get; init; }
    public required StatusDocumentoFiscal Status { get; init; }
    public string? ChaveAcesso { get; init; }
    public string? Protocolo { get; init; }
    public DateTimeOffset? DhAutorizacao { get; init; }
    public required DateTimeOffset CriadoEm { get; init; }
    public string? XmlAssinado { get; init; }
    public string? XmlProtocolado { get; init; }
    public string? XmlCancelamento { get; init; }
}
