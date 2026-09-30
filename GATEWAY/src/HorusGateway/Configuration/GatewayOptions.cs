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
}
