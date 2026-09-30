/*
 * Arquivo: Services/GatewayCertificateService.cs
 * Objetivo: gerencia o ciclo de vida, carregamento e validação de Certificado Digital A1 (.pfx)
 *           utilizado pelo Local Gateway para assinatura de NFC-e em contingência offline (tpEmis = 9).
 */
using System.Security.Cryptography.X509Certificates;
using HorusGateway.Configuration;
using HorusGateway.Models;
using Microsoft.Extensions.Options;

namespace HorusGateway.Services;

public sealed class GatewayCertificateService
{
    private readonly GatewayOptions _options;
    private readonly ILogger<GatewayCertificateService> _logger;

    public GatewayCertificateService(IOptions<GatewayOptions> options, ILogger<GatewayCertificateService> logger)
    {
        _options = options.Value;
        _logger = logger;
    }

    /// <summary>Carrega uma instância do certificado digital A1 com chave privada exportável.</summary>
    public X509Certificate2? CarregarCertificado()
    {
        byte[]? rawBytes = null;

        if (!string.IsNullOrWhiteSpace(_options.CertificadoPfxBase64))
        {
            try
            {
                rawBytes = Convert.FromBase64String(_options.CertificadoPfxBase64);
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, "Falha ao decodificar CertificadoPfxBase64 configurado no Gateway.");
            }
        }
        else if (!string.IsNullOrWhiteSpace(_options.CertificadoPfxPath))
        {
            var path = Path.GetFullPath(_options.CertificadoPfxPath);
            if (File.Exists(path))
            {
                try
                {
                    rawBytes = File.ReadAllBytes(path);
                }
                catch (Exception ex)
                {
                    _logger.LogError(ex, "Falha ao ler arquivo de certificado digital A1 em {Path}.", path);
                }
            }
            else
            {
                _logger.LogWarning("Arquivo de certificado digital A1 não encontrado no caminho: {Path}.", path);
            }
        }

        if (rawBytes is null || rawBytes.Length == 0)
        {
            return null;
        }

        try
        {
            var flags = X509KeyStorageFlags.Exportable;
            if (OperatingSystem.IsWindows())
            {
                flags |= X509KeyStorageFlags.UserKeySet;
            }
            else
            {
                flags |= X509KeyStorageFlags.MachineKeySet;
            }

            var senha = _options.CertificadoSenha ?? string.Empty;
            return new X509Certificate2(rawBytes, senha, flags);
        }
        catch (Exception exUser)
        {
            try
            {
                var senha = _options.CertificadoSenha ?? string.Empty;
                return new X509Certificate2(rawBytes, senha, X509KeyStorageFlags.Exportable);
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, "Erro ao instanciar certificado digital A1 (senha incorreta ou formato inválido). Falha inicial: {Initial}", exUser.Message);
                return null;
            }
        }
    }

    /// <summary>Valida o status do certificado e retorna detalhes de vigência para monitoramento.</summary>
    public GatewayFiscalStatusResponse ObterStatus(int ultimoNumeroEmitido, int totalNotasOffline)
    {
        var response = new GatewayFiscalStatusResponse
        {
            CertificadoConfigurado = !string.IsNullOrWhiteSpace(_options.CertificadoPfxBase64) ||
                                     !string.IsNullOrWhiteSpace(_options.CertificadoPfxPath),
            CscConfigurado = !string.IsNullOrWhiteSpace(_options.Csc) && !string.IsNullOrWhiteSpace(_options.CscId),
            SerieContingencia = _options.SerieNfceContingencia,
            UltimoNumeroEmitido = ultimoNumeroEmitido,
            TotalNotasEmitidasOffline = totalNotasOffline
        };

        try
        {
            using var cert = CarregarCertificado();
            if (cert is not null)
            {
                var agora = DateTime.Now;
                response.CertificadoSubject = cert.Subject;
                response.CertificadoValidoAte = cert.NotAfter;
                response.DiasRestantesCertificado = (int)(cert.NotAfter - agora).TotalDays;
                response.CertificadoValido = cert.HasPrivateKey && agora >= cert.NotBefore && agora <= cert.NotAfter;
            }
        }
        catch
        {
            response.CertificadoValido = false;
        }

        return response;
    }
}
