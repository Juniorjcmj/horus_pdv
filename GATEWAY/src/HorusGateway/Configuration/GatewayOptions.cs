/*
 * Arquivo: Configuration/GatewayOptions.cs
 * Objetivo: opções de configuração do Gateway (identidade da loja/gateway e caminho do banco local),
 *           carregadas da seção "Gateway" do appsettings/variáveis de ambiente.
 */
namespace HorusGateway.Configuration;

public sealed class GatewayOptions
{
    public const string SectionName = "Gateway";

    /// <summary>Identidade estável do Gateway (ex.: "gw_01JABC..."). Gerada no boot se vazia.</summary>
    public string GatewayId { get; set; } = string.Empty;

    /// <summary>Empresa à qual este Gateway está estritamente vinculado. Eventos de outra empresa são rejeitados.</summary>
    public string CompanyId { get; set; } = string.Empty;

    /// <summary>Loja atendida por este Gateway.</summary>
    public string StoreId { get; set; } = string.Empty;

    /// <summary>Caminho do arquivo SQLite de persistência local durável.</summary>
    public string DatabasePath { get; set; } = "gateway-data/horus-gateway.db";

    /// <summary>
    /// Segredo compartilhado que o administrador provisiona nos terminais para o registro inicial.
    /// Usado apenas quando OpenRegistration = false. Sem ele (e sem registro aberto), o registro fica indisponível.
    /// </summary>
    public string RegistrationToken { get; set; } = string.Empty;

    /// <summary>
    /// Registro aberto: qualquer terminal na LAN que informe o CompanyId correto se registra e recebe
    /// a credencial na hora, sem token pré-compartilhado. Facilita conectar N terminais sem provisionamento
    /// manual. Continua isolado por empresa e cada terminal recebe credencial própria (auditável/revogável).
    /// </summary>
    public bool OpenRegistration { get; set; } = true;

    /// <summary>
    /// Quando true, ingestão/recuperação de eventos e heartbeat exigem credencial de terminal válida.
    /// Seguro por padrão; testes de semântica de evento podem desligar para focar no fluxo.
    /// </summary>
    public bool RequireTerminalAuth { get; set; } = true;

    /// <summary>Janela (segundos) sem heartbeat após a qual um terminal é considerado OFFLINE no dashboard.</summary>
    public int TerminalOnlineWindowSeconds { get; set; } = 60;

    /// <summary>
    /// URL do endpoint de ingestão em lote da cloud (Gateway → Cloud). Vazio desliga a sincronização:
    /// o Gateway opera LAN-only e os eventos acumulam PENDING_CLOUD até a URL ser configurada.
    /// </summary>
    public string CloudSyncUrl { get; set; } = string.Empty;

    /// <summary>Intervalo (segundos) entre ciclos do dispatcher Gateway → Cloud.</summary>
    public int CloudSyncIntervalSeconds { get; set; } = 15;

    /// <summary>Máximo de eventos enviados por ciclo.</summary>
    public int CloudSyncBatchSize { get; set; } = 50;

    /// <summary>Token opcional enviado no header Authorization para a cloud (Bearer).</summary>
    public string CloudSyncToken { get; set; } = string.Empty;

    /* --------------------------------------------------------------------- */
    /* Configurações Fiscais de Contingência Offline (LAN)                   */
    /* --------------------------------------------------------------------- */

    /// <summary>Caminho do arquivo do Certificado Digital A1 (.pfx) para contingência offline no Gateway.</summary>
    public string CertificadoPfxPath { get; set; } = string.Empty;

    /// <summary>Certificado A1 em base64 (opcional, para provisionamento via config/env).</summary>
    public string CertificadoPfxBase64 { get; set; } = string.Empty;

    /// <summary>Senha do Certificado Digital A1.</summary>
    public string CertificadoSenha { get; set; } = string.Empty;

    /// <summary>CSC / Token de QR Code da NFC-e.</summary>
    public string Csc { get; set; } = string.Empty;

    /// <summary>Id do Token do CSC (ex: "1" ou "000001").</summary>
    public string CscId { get; set; } = string.Empty;

    /// <summary>Série dedicada para emissão no Gateway local (ex: 900).</summary>
    public int SerieNfceContingencia { get; set; } = 900;

    /// <summary>Ambiente fiscal: 1 Produção, 2 Homologação.</summary>
    public byte AmbienteFiscal { get; set; } = 2;

    /// <summary>CNPJ do emitente.</summary>
    public string EmitenteCnpj { get; set; } = string.Empty;

    /// <summary>Razão social do emitente.</summary>
    public string EmitenteRazaoSocial { get; set; } = string.Empty;

    /// <summary>Nome fantasia do emitente.</summary>
    public string EmitenteNomeFantasia { get; set; } = string.Empty;

    /// <summary>Inscrição Estadual do emitente.</summary>
    public string EmitenteInscricaoEstadual { get; set; } = string.Empty;

    /// <summary>Código do município IBGE do emitente (ex: 3304557 para Rio de Janeiro).</summary>
    public string EmitenteCodigoMunicipioIbge { get; set; } = "3304557";

    /// <summary>Código da UF do emitente (ex: 33 para RJ, 35 para SP).</summary>
    public byte EmitenteCodigoUf { get; set; } = 33;

    /// <summary>UF do emitente (ex: RJ, SP).</summary>
    public string EmitenteUf { get; set; } = "RJ";

    /// <summary>Regime tributário: 1 Simples, 3 Normal.</summary>
    public byte EmitenteCrt { get; set; } = 1;
}
