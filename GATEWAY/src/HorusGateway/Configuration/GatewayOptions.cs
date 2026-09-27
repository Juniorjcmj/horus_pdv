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
    /// A LAN é considerada NÃO confiável: sem este token, o registro de terminais fica indisponível.
    /// </summary>
    public string RegistrationToken { get; set; } = string.Empty;

    /// <summary>
    /// Quando true, ingestão/recuperação de eventos e heartbeat exigem credencial de terminal válida.
    /// Seguro por padrão; testes de semântica de evento podem desligar para focar no fluxo.
    /// </summary>
    public bool RequireTerminalAuth { get; set; } = true;

    /// <summary>Janela (segundos) sem heartbeat após a qual um terminal é considerado OFFLINE no dashboard.</summary>
    public int TerminalOnlineWindowSeconds { get; set; } = 60;
}
