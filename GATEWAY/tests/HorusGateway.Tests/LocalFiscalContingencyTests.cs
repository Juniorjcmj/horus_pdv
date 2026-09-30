/*
 * Arquivo: tests/HorusGateway.Tests/LocalFiscalContingencyTests.cs
 * Objetivo: testes unitários e de integração para a emissão de NFC-e em contingência offline
 *           (Modelo 65, tpEmis = 9), cálculo de chave de 44 dígitos com DV módulo 11,
 *           validação de justificativa >= 15 caracteres, persistência no SQLite e disparo de eventos.
 */
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using HorusGateway.Configuration;
using HorusGateway.Data;
using HorusGateway.Models;
using HorusGateway.Services;
using Microsoft.Data.Sqlite;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Options;
using Xunit;

namespace HorusGateway.Tests;

public sealed class LocalFiscalContingencyTests : IDisposable
{
    private sealed class FakeClock : IClock
    {
        public DateTimeOffset UtcNow { get; set; } = new(2026, 9, 30, 12, 0, 0, TimeSpan.Zero);
    }

    private readonly string _dbPath;
    private readonly FakeClock _clock = new();
    private readonly GatewayDatabase _database;
    private readonly SqliteEventStore _eventStore;
    private readonly LocalFiscalStore _fiscalStore;

    public LocalFiscalContingencyTests()
    {
        _dbPath = Path.Combine(Path.GetTempPath(), $"horus_fiscal_test_{Guid.NewGuid():N}.db");
        var options = Options.Create(new GatewayOptions { CompanyId = "empresa-1", DatabasePath = _dbPath });
        _database = new GatewayDatabase(options, NullLogger<GatewayDatabase>.Instance);
        _database.Initialize();
        _eventStore = new SqliteEventStore(_database, _clock, NullLogger<SqliteEventStore>.Instance);
        _fiscalStore = new LocalFiscalStore(_database, _eventStore, _clock, NullLogger<LocalFiscalStore>.Instance);
    }

    public void Dispose()
    {
        SqliteConnection.ClearAllPools();
        foreach (var suffix in new[] { "", "-wal", "-shm" })
        {
            var f = _dbPath + suffix;
            try { if (File.Exists(f)) File.Delete(f); } catch { }
        }
    }

    [Fact]
    public void GerarChaveAcesso_ComTipo9_RetornaChaveCom44Digitos_E_TpEmis9()
    {
        // Arrange
        byte uf = 33; // RJ
        var dataHora = new DateTimeOffset(2026, 9, 30, 10, 0, 0, TimeSpan.FromHours(-3));
        var cnpj = "12.345.678/0001-95";
        short modelo = 65; // NFC-e
        int serie = 900;
        int numero = 142;
        byte tipoEmissao = 9; // Contingência Offline
        var cnf = "12345678";

        // Act
        var chave = LocalFiscalSigner.GerarChaveAcesso(uf, dataHora, cnpj, modelo, serie, numero, tipoEmissao, cnf);

        // Assert
        Assert.NotNull(chave);
        Assert.Equal(44, chave.Length);
        Assert.All(chave, c => Assert.True(char.IsDigit(c)));

        // UF (pos 0-1)
        Assert.Equal("33", chave[..2]);
        // AAMM (pos 2-5)
        Assert.Equal("2609", chave[2..6]);
        // CNPJ 14 dígitos (pos 6-19)
        Assert.Equal("12345678000195", chave[6..20]);
        // Modelo (pos 20-21)
        Assert.Equal("65", chave[20..22]);
        // Série 3 dígitos (pos 22-24)
        Assert.Equal("900", chave[22..25]);
        // Número 9 dígitos (pos 25-33)
        Assert.Equal("000000142", chave[25..34]);
        // tpEmis (pos 34: 0-based) -> deve ser 9
        Assert.Equal('9', chave[34]);
        // cNF 8 dígitos (pos 35-42)
        Assert.Equal("12345678", chave[35..43]);

        // Valida dígito verificador módulo 11
        var chave43 = chave[..43];
        var dvCalculado = LocalFiscalSigner.CalcularDigitoVerificador(chave43);
        Assert.Equal(dvCalculado.ToString()[0], chave[43]);
    }

    [Theory]
    [InlineData("3526091234567800019565001000000001912345678")]
    [InlineData("3326091234567800019565900000000142987654321")]
    public void CalcularDigitoVerificador_RetornaDigitoValido(string chave43)
    {
        var dv = LocalFiscalSigner.CalcularDigitoVerificador(chave43);
        Assert.InRange(dv, 0, 9);
    }

    [Fact]
    public void LocalFiscalStore_AlocarProximoNumero_IncrementaCorretamentePorSerie()
    {
        // Act
        var num1 = _fiscalStore.AlocarProximoNumero("empresa-1", 900);
        var num2 = _fiscalStore.AlocarProximoNumero("empresa-1", 900);
        var numSerieOutra = _fiscalStore.AlocarProximoNumero("empresa-1", 901);

        // Assert
        Assert.Equal(1, num1);
        Assert.Equal(2, num2);
        Assert.Equal(1, numSerieOutra);
    }

    [Fact]
    public async Task LocalFiscalStore_PersistirEDespacharAsync_GravaNoSQLiteEDispachaEventoNoEventBus()
    {
        // Arrange
        var request = new LocalNfceContingenciaRequest
        {
            VendaId = "VENDA-1002",
            TerminalId = "caixa-01",
            CustomerCpf = "12345678901",
            Justificativa = "EMISSAO OFFLINE POR QUEDA DA REDE LOCAL",
            Itens =
            [
                new LocalItemFiscal
                {
                    Numero = 1,
                    CodigoProduto = "P01",
                    Descricao = "PRODUTO TESTE",
                    Quantidade = 1,
                    ValorUnitario = 50.0m,
                    ValorTotal = 50.0m,
                    Desconto = 0m
                }
            ],
            Pagamentos =
            [
                new LocalPagamentoFiscal
                {
                    Tipo = "01",
                    Valor = 50.0m
                }
            ]
        };

        var chave = "33260912345678000195659000000000019123456785";
        var response = new LocalNfceContingenciaResponse
        {
            Success = true,
            DocumentoId = "gw-nfce-test-001",
            ChaveAcesso = chave,
            Serie = 900,
            NumeroNf = 1,
            DhContingencia = DateTimeOffset.UtcNow,
            XmlAssinado = "<NFe><infNFe></infNFe></NFe>",
            QrCodeUrl = "http://www.fazenda.rj.gov.br/nfce/qrcode?p=" + chave,
            DigestValue = "digest123",
            DanfeData = new DanfeThermalData
            {
                RazaoSocial = "EMPRESA TESTE",
                Cnpj = "12345678000195",
                Justificativa = "EMISSAO OFFLINE POR QUEDA DA REDE LOCAL"
            }
        };

        // Act
        await _fiscalStore.PersistirEDespacharAsync("empresa-1", response, request);

        // Assert - Verifica gravação no SQLite
        using (var conn = _database.OpenConnection())
        {
            using var cmd = conn.CreateCommand();
            cmd.CommandText = "SELECT COUNT(1) FROM LocalNfceContingencias WHERE Id = 'gw-nfce-test-001'";
            var count = Convert.ToInt32(cmd.ExecuteScalar());
            Assert.Equal(1, count);
        }

        // Assert - Verifica evento na fila de envio à nuvem
        var eventos = await _eventStore.GetDueForDispatchAsync("empresa-1", 10);
        var fiscalEvent = eventos.FirstOrDefault(e => e.EventType == "fiscal.nfce.contingencia");
        Assert.NotNull(fiscalEvent);
        Assert.Contains(chave, fiscalEvent.Payload);
        Assert.Contains("VENDA-1002", fiscalEvent.Payload);
    }

    [Fact]
    public void LocalFiscalSigner_ComCertificadoA1_GeraXmlAssinadoComQRcodeV2()
    {
        // Arrange: Gera certificado digital A1 autoassinado para o teste
        using var rsa = RSA.Create(2048);
        var certReq = new CertificateRequest("CN=EMPRESA TESTE LTDA, C=BR", rsa, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
        using var cert = certReq.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddYears(1));
        var pfxBytes = cert.Export(X509ContentType.Pfx, "senha123");
        var pfxBase64 = Convert.ToBase64String(pfxBytes);

        var options = Options.Create(new GatewayOptions
        {
            CertificadoPfxBase64 = pfxBase64,
            CertificadoSenha = "senha123",
            EmitenteCnpj = "12345678000195",
            EmitenteRazaoSocial = "EMPRESA TESTE LTDA",
            EmitenteCodigoUf = 33,
            EmitenteCodigoMunicipioIbge = "3304557",
            Csc = "ABC123CSC456",
            CscId = "1",
            AmbienteFiscal = 2
        });

        var certService = new GatewayCertificateService(options, NullLogger<GatewayCertificateService>.Instance);
        var signer = new LocalFiscalSigner(certService, options, NullLogger<LocalFiscalSigner>.Instance);

        var request = new LocalNfceContingenciaRequest
        {
            VendaId = "V-100",
            TerminalId = "caixa-01",
            CustomerCpf = "12345678901",
            Justificativa = "EMISSAO EM CONTINGENCIA OFFLINE POR FALHA DE REDE",
            Itens =
            [
                new LocalItemFiscal
                {
                    Numero = 1,
                    CodigoProduto = "PROD01",
                    Descricao = "PRODUTO TESTE",
                    Quantidade = 2,
                    ValorUnitario = 10.50m,
                    ValorTotal = 21.00m,
                    Desconto = 0m
                }
            ],
            Pagamentos =
            [
                new LocalPagamentoFiscal
                {
                    Tipo = "01",
                    Valor = 21.00m
                }
            ],
            ValorTroco = 0m
        };

        // Act
        var response = signer.EmitirContingencia(request, 10, 900, DateTimeOffset.UtcNow);

        // Assert
        Assert.True(response.Success, response.Message);
        Assert.NotNull(response.ChaveAcesso);
        Assert.Equal(44, response.ChaveAcesso.Length);
        Assert.Equal('9', response.ChaveAcesso[34]); // tpEmis = 9
        Assert.NotNull(response.XmlAssinado);
        Assert.Contains("<Signature", response.XmlAssinado);
        Assert.Contains("<tpEmis>9</tpEmis>", response.XmlAssinado);
        Assert.Contains("<xJust>EMISSAO EM CONTINGENCIA OFFLINE POR FALHA DE REDE</xJust>", response.XmlAssinado);
        Assert.NotNull(response.QrCodeUrl);
        Assert.Contains("p=" + response.ChaveAcesso, response.QrCodeUrl);
        Assert.NotNull(response.DanfeData);
        Assert.True(response.DanfeData.EmissaoContingencia);
        Assert.Equal("EMITIDA EM CONTINGÊNCIA - Pendente de autorização", response.DanfeData.MensagemContingencia);
    }

    [Fact]
    public void LocalFiscalSigner_ComJustificativaCurta_AplicaJustificativaPadraoConformeSefaz()
    {
        // Arrange
        using var rsa = RSA.Create(2048);
        var certReq = new CertificateRequest("CN=EMPRESA TESTE LTDA, C=BR", rsa, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
        using var cert = certReq.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddYears(1));
        var pfxBytes = cert.Export(X509ContentType.Pfx, "senha123");

        var options = Options.Create(new GatewayOptions
        {
            CertificadoPfxBase64 = Convert.ToBase64String(pfxBytes),
            CertificadoSenha = "senha123",
            EmitenteCnpj = "12345678000195",
            EmitenteRazaoSocial = "EMPRESA TESTE LTDA",
            EmitenteCodigoUf = 33,
            EmitenteCodigoMunicipioIbge = "3304557",
            Csc = "ABC123CSC456",
            CscId = "1",
            AmbienteFiscal = 2
        });

        var certService = new GatewayCertificateService(options, NullLogger<GatewayCertificateService>.Instance);
        var signer = new LocalFiscalSigner(certService, options, NullLogger<LocalFiscalSigner>.Instance);

        var request = new LocalNfceContingenciaRequest
        {
            VendaId = "V-101",
            TerminalId = "caixa-01",
            Justificativa = "curto", // menos de 15 caracteres
            Itens =
            [
                new LocalItemFiscal
                {
                    Numero = 1,
                    CodigoProduto = "P1",
                    Descricao = "PRODUTO",
                    Quantidade = 1,
                    ValorUnitario = 5m,
                    ValorTotal = 5m
                }
            ],
            Pagamentos = [new LocalPagamentoFiscal { Tipo = "01", Valor = 5m }]
        };

        // Act
        var response = signer.EmitirContingencia(request, 11, 900, DateTimeOffset.UtcNow);

        // Assert
        Assert.True(response.Success);
        // Garante que o XML e DANFE contêm pelo menos 15 caracteres de justificativa
        Assert.NotNull(response.DanfeData?.Justificativa);
        Assert.True(response.DanfeData.Justificativa.Length >= 15);
        Assert.Contains("<xJust>EMISSAO EM CONTINGENCIA OFFLINE POR INDISPONIBILIDADE DE REDE</xJust>", response.XmlAssinado);
    }

    [Fact]
    public async Task GatewayFiscalController_EmitirContingencia_RetornaOkComXmlAssinadoEDanfe()
    {
        // Arrange
        using var rsa = RSA.Create(2048);
        var certReq = new CertificateRequest("CN=EMPRESA TESTE, C=BR", rsa, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
        using var cert = certReq.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddYears(1));
        var pfxBytes = cert.Export(X509ContentType.Pfx, "pass123");

        var options = Options.Create(new GatewayOptions
        {
            CompanyId = "empresa-1",
            CertificadoPfxBase64 = Convert.ToBase64String(pfxBytes),
            CertificadoSenha = "pass123",
            EmitenteCnpj = "12345678000195",
            EmitenteRazaoSocial = "EMPRESA TESTE LTDA",
            EmitenteCodigoUf = 33,
            EmitenteCodigoMunicipioIbge = "3304557",
            Csc = "CSC123",
            CscId = "1",
            AmbienteFiscal = 2,
            SerieNfceContingencia = 900,
            RequireTerminalAuth = false
        });

        var certService = new GatewayCertificateService(options, NullLogger<GatewayCertificateService>.Instance);
        var signer = new LocalFiscalSigner(certService, options, NullLogger<LocalFiscalSigner>.Instance);
        var identity = new GatewayIdentity(options);
        var termStore = new SqliteTerminalStore(_database, identity, options, NullLogger<SqliteTerminalStore>.Instance);

        var controller = new HorusGateway.Controllers.GatewayFiscalController(
            signer,
            _fiscalStore,
            certService,
            termStore,
            options,
            NullLogger<HorusGateway.Controllers.GatewayFiscalController>.Instance);

        var request = new LocalNfceContingenciaRequest
        {
            VendaId = "VENDA-API-99",
            TerminalId = "caixa-01",
            CustomerCpf = "12345678901",
            Justificativa = "QUEDA DE LINK DE INTERNET NO CAIXA LOCAL",
            Itens =
            [
                new LocalItemFiscal
                {
                    Numero = 1,
                    CodigoProduto = "PROD99",
                    Descricao = "PRODUTO TESTE API",
                    Quantidade = 1,
                    ValorUnitario = 15m,
                    ValorTotal = 15m
                }
            ],
            Pagamentos =
            [
                new LocalPagamentoFiscal
                {
                    Tipo = "01",
                    Valor = 15m
                }
            ]
        };

        // Act
        var result = await controller.EmitirContingencia(request);

        // Assert
        var okResult = Assert.IsType<Microsoft.AspNetCore.Mvc.OkObjectResult>(result);
        var resp = Assert.IsType<LocalNfceContingenciaResponse>(okResult.Value);
        Assert.True(resp.Success);
        Assert.Equal(44, resp.ChaveAcesso.Length);
        Assert.Equal('9', resp.ChaveAcesso[34]);
        Assert.NotNull(resp.XmlAssinado);
        Assert.Contains("<tpEmis>9</tpEmis>", resp.XmlAssinado);
        Assert.NotNull(resp.DanfeData);

        // Status endpoint
        var statusResult = controller.ObterStatus();
        var okStatus = Assert.IsType<Microsoft.AspNetCore.Mvc.OkObjectResult>(statusResult);
        var statusResp = Assert.IsType<GatewayFiscalStatusResponse>(okStatus.Value);
        Assert.True(statusResp.CertificadoConfigurado);
        Assert.True(statusResp.CscConfigurado);
        Assert.Equal(900, statusResp.SerieContingencia);
    }
}
