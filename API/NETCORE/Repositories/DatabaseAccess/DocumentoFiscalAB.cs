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
    // Modelo 65 = NFC-e. A série fiscal é lida da configuração da empresa (Empresas.SerieNfce).

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

    public async Task<List<DocumentoFiscalPendente>> ObterPendentesAsync(int lote, CancellationToken ct = default)
    {
        const string sql = """
            SELECT TOP (@Lote) Id, CompanyId, VendaId, Serie, NumeroNf, TpEmis, Tentativas,
                   DhContingencia, JustContingencia
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
                Serie = ReadInt(reader, "Serie"),
                NumeroNf = ReadInt(reader, "NumeroNf"),
                TipoEmissao = (TipoEmissaoFiscal)ReadInt(reader, "TpEmis"),
                Tentativas = ReadInt(reader, "Tentativas"),
                DhContingencia = ReadNullableDateTimeOffset(reader, "DhContingencia"),
                JustContingencia = ReadNullableString(reader, "JustContingencia")
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
                ValorUnitario = HorusMoneyFormat.ParseDecimal(linha.UnitPrice),
                ValorTotal = HorusMoneyFormat.ParseDecimal(linha.ItemTotal),
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
                   d.MotivoStatus, d.DhAutorizacao, d.CriadoEm, d.Tentativas
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
                Tentativas = ReadInt(reader, "Tentativas")
            });
        }

        return rows;
    }

    /// <summary>Detalhe de um documento por número de venda — usado para montar o DANFE em tela.</summary>
    public async Task<DocumentoFiscalDetalhe?> ObterDetalhePorVendaAsync(string companyId, string saleNumber)
    {
        const string sql = """
            SELECT d.Id, v.SaleNumber, d.Serie, d.NumeroNf, d.Status, d.ChaveAcesso, d.Protocolo,
                   d.MotivoStatus, d.DhAutorizacao, d.CriadoEm, d.Tentativas, d.XmlProtocolado
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
            QrCodeUrl = ExtrairQrCode(xmlProtocolado)
        };
    }

    /// <summary>Reenfileira um documento rejeitado definitivamente. Se foi duplicidade (539) ou mudança de série, aloca nova numeração.</summary>
    public async Task<bool> ReemitirAsync(string companyId, string id)
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
        catch
        {
            await transaction.RollbackAsync();
            throw;
        }
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

    /// <summary>Extração leve do link do QR Code já embutido no XML autorizado (nfeProc/infNFeSupl).</summary>
    private static string? ExtrairQrCode(string? xmlProtocolado)
    {
        if (string.IsNullOrWhiteSpace(xmlProtocolado)) return null;
        var inicio = xmlProtocolado.IndexOf("<qrCode>", StringComparison.OrdinalIgnoreCase);
        if (inicio < 0) return null;
        inicio += "<qrCode>".Length;
        var fim = xmlProtocolado.IndexOf("</qrCode>", inicio, StringComparison.OrdinalIgnoreCase);
        if (fim < 0) return null;
        var conteudo = xmlProtocolado[inicio..fim];
        return conteudo.Replace("<![CDATA[", string.Empty).Replace("]]>", string.Empty).Trim();
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

    /// <summary>tPag: 01 dinheiro, 03 crédito, 04 débito, 17 PIX dinâmico.</summary>
    private static string MapearFormaPagamento(string paymentType)
    {
        var normalized = paymentType.Trim().ToLowerInvariant();
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
}

/// <summary>Detalhe de um documento fiscal, com o link do QR Code para montar o DANFE em tela.</summary>
public sealed record DocumentoFiscalDetalhe : DocumentoFiscalResumo
{
    public string? QrCodeUrl { get; init; }
}
