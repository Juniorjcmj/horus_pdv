/*
 * Arquivo: Controllers/DashboardController.cs
 * Objetivo: API de monitoramento e Dashboard Web embutido do HorusGateway (CHANGE GATEWAY 08).
 *           Fornece visibilidade em tempo real sobre Internet, Gateway, Cloud, Terminais,
 *           Eventos pendentes/erro, sincronização e mecanismo de verificação pré-atualização segura.
 */
using System.Diagnostics;
using HorusGateway.Configuration;
using HorusGateway.Models;
using HorusGateway.Services;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Extensions.Options;

namespace HorusGateway.Controllers;

[ApiController]
public sealed class DashboardController : ControllerBase
{
    private static readonly DateTimeOffset ProcessStartTime = DateTimeOffset.UtcNow;

    private readonly GatewayIdentity _identity;
    private readonly IEventStore _eventStore;
    private readonly ITerminalStore _terminalStore;
    private readonly IOrderStore _orderStore;
    private readonly CloudSyncState _cloudSync;
    private readonly GatewayOptions _options;
    private readonly IClock _clock;
    private readonly ILogger<DashboardController> _logger;

    public DashboardController(
        GatewayIdentity identity,
        IEventStore eventStore,
        ITerminalStore terminalStore,
        IOrderStore orderStore,
        CloudSyncState cloudSync,
        IOptions<GatewayOptions> options,
        IClock clock,
        ILogger<DashboardController> logger)
    {
        _identity = identity;
        _eventStore = eventStore;
        _terminalStore = terminalStore;
        _orderStore = orderStore;
        _cloudSync = cloudSync;
        _options = options.Value;
        _clock = clock;
        _logger = logger;
    }

    /// <summary>Resumo agregado para monitoramento do Gateway e indicadores em tempo real.</summary>
    [HttpGet("api/gateway/dashboard")]
    public async Task<IActionResult> GetDashboardSummary(CancellationToken cancellationToken)
    {
        if (!_identity.IsBound)
        {
            return StatusCode(503, new { error = "Gateway não vinculado a uma empresa." });
        }

        var now = _clock.UtcNow;
        var uptimeSeconds = (long)(now - ProcessStartTime).TotalSeconds;

        // 1. Métricas de Eventos
        var metrics = await _eventStore.GetMetricsAsync(_identity.CompanyId, cancellationToken);

        // 2. Terminais
        var terminalsList = await _terminalStore.ListAsync(_identity.CompanyId, cancellationToken);
        var onlineCount = terminalsList.Count(t => string.Equals(t.Status, "ONLINE", StringComparison.OrdinalIgnoreCase));
        var offlineCount = terminalsList.Count - onlineCount;

        var terminalItems = terminalsList.Select(t =>
        {
            int secondsSince = -1;
            if (DateTimeOffset.TryParse(t.LastSeenAt, out var seen))
            {
                secondsSince = Math.Max(0, (int)(now - seen).TotalSeconds);
            }
            return new TerminalDashboardItem(t.TerminalId, t.TerminalType, t.Status, t.LastSeenAt, secondsSince);
        }).ToList();

        // 3. Pedidos
        var activeOrders = await _orderStore.ListAsync(_identity.CompanyId, null, cancellationToken);

        // 4. Cloud e Saúde
        var cloudEnabled = !string.IsNullOrWhiteSpace(_options.CloudSyncUrl);
        var cloudStatus = !cloudEnabled ? "disabled" : (_cloudSync.Online ? "online" : "offline");
        var internetStatus = cloudEnabled && _cloudSync.Online ? "online" : (cloudEnabled ? "unreachable" : "unknown");

        // 5. Update Readiness
        var isSafe = metrics.PendingCloudEvents == 0;
        var updateMessage = isSafe
            ? "Gateway seguro para reinicialização ou atualização. Todos os eventos locais estão sincronizados com a Cloud."
            : $"NÃO seguro para atualizar: existem {metrics.PendingCloudEvents} evento(s) pendente(s) de sincronização com a Cloud.";
        var action = isSafe ? "PROCEED" : "WAIT_FOR_SYNC";

        var response = new DashboardSummaryResponse(
            Identity: new DashboardIdentity(
                GatewayId: _identity.GatewayId,
                CompanyId: _identity.CompanyId,
                StoreId: _identity.StoreId,
                Bound: _identity.IsBound,
                ServerTime: now.ToString("o"),
                UptimeSeconds: uptimeSeconds),
            Health: new DashboardHealth(
                Gateway: "healthy",
                Storage: "healthy",
                Internet: internetStatus,
                Cloud: cloudStatus),
            Sync: new DashboardSync(
                CloudSyncUrl: _options.CloudSyncUrl,
                Online: _cloudSync.Online,
                LastSuccessAt: _cloudSync.LastSuccessAt?.ToString("o"),
                LastAttemptAt: _cloudSync.LastAttemptAt?.ToString("o"),
                LastError: _cloudSync.LastError),
            Events: new DashboardEventsSummary(
                Total: metrics.TotalEvents,
                PendingCloud: metrics.PendingCloudEvents,
                SyncedCloud: metrics.SyncedCloudEvents,
                Failed: metrics.FailedEvents,
                LastEventOccurredAt: metrics.LastEventOccurredAt,
                LastSyncedAt: metrics.LastSyncedAt),
            Terminals: new DashboardTerminalsSummary(
                Total: terminalsList.Count,
                Online: onlineCount,
                Offline: offlineCount,
                Items: terminalItems),
            ActiveOrdersCount: activeOrders.Count,
            UpdateReadiness: new UpdateReadiness(
                IsSafe: isSafe,
                PendingEvents: metrics.PendingCloudEvents,
                FailedEvents: metrics.FailedEvents,
                Message: updateMessage,
                ActionRecommended: action)
        );

        return Ok(response);
    }

    /// <summary>
    /// Mecanismo de atualização controlada: valida se o Gateway pode ser desligado,
    /// reiniciado ou atualizado com segurança sem risco de perda ou retenção de dados pendentes.
    /// </summary>
    [HttpGet("api/gateway/system/update-check")]
    public async Task<IActionResult> CheckSafeUpdate(CancellationToken cancellationToken)
    {
        if (!_identity.IsBound)
        {
            return StatusCode(503, new { error = "Gateway não vinculado a uma empresa." });
        }

        var metrics = await _eventStore.GetMetricsAsync(_identity.CompanyId, cancellationToken);
        var isSafe = metrics.PendingCloudEvents == 0;

        var message = isSafe
            ? "SEGURO PARA ATUALIZAÇÃO: Todos os eventos locais foram sincronizados com a Cloud. O serviço pode ser atualizado ou reiniciado."
            : $"BLOQUEIO DE ATUALIZAÇÃO: Existem {metrics.PendingCloudEvents} evento(s) pendente(s) de envio à Cloud. Aguarde a sincronização.";

        var response = new SafeUpdateCheckResponse(
            Safe: isSafe,
            PendingEvents: metrics.PendingCloudEvents,
            FailedEvents: metrics.FailedEvents,
            Message: message,
            ActionRecommended: isSafe ? "PROCEED" : "WAIT_FOR_SYNC",
            Timestamp: _clock.UtcNow.ToString("o")
        );

        return Ok(response);
    }

    /// <summary>Dashboard Web local — acessível na LAN em / ou /dashboard.</summary>
    [HttpGet("/")]
    [HttpGet("/dashboard")]
    public IActionResult DashboardPage()
    {
        return Content(DashboardHtml, "text/html; charset=utf-8");
    }

    private static readonly string DashboardHtml = """
<!DOCTYPE html>
<html lang="pt-BR">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
  <title>Hórus Gateway — Monitoramento Local</title>
  <link rel="preconnect" href="https://fonts.googleapis.com">
  <link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&display=swap" rel="stylesheet">
  <style>
    :root {
      --bg-dark: #0f172a;
      --card-bg: #1e293b;
      --card-border: #334155;
      --text-main: #f8fafc;
      --text-muted: #94a3b8;
      --accent-blue: #38bdf8;
      --green: #10b981;
      --green-glow: rgba(16, 185, 129, 0.25);
      --red: #ef4444;
      --red-glow: rgba(239, 68, 68, 0.25);
      --yellow: #f59e0b;
      --yellow-glow: rgba(245, 158, 11, 0.25);
    }
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body {
      font-family: 'Inter', -apple-system, sans-serif;
      background: var(--bg-dark);
      color: var(--text-main);
      padding: 1.5rem;
      min-height: 100vh;
    }
    .container { max-width: 1200px; margin: 0 auto; }
    header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 2rem;
      padding-bottom: 1rem;
      border-bottom: 1px solid var(--card-border);
      flex-wrap: wrap;
      gap: 1rem;
    }
    .title-group h1 { font-size: 1.5rem; font-weight: 700; color: #fff; display: flex; align-items: center; gap: 0.5rem; }
    .badge {
      font-size: 0.75rem;
      padding: 0.25rem 0.6rem;
      border-radius: 9999px;
      font-weight: 600;
      text-transform: uppercase;
      letter-spacing: 0.05em;
    }
    .badge-lan { background: #0284c7; color: #fff; }
    .badge-online { background: #065f46; color: #34d399; border: 1px solid #10b981; }
    .badge-offline { background: #7f1d1d; color: #f87171; border: 1px solid #ef4444; }
    .badge-disabled { background: #374151; color: #9ca3af; }
    
    .header-actions { display: flex; align-items: center; gap: 0.75rem; }
    button.btn {
      background: #2563eb;
      color: white;
      border: none;
      padding: 0.5rem 1rem;
      border-radius: 6px;
      font-weight: 600;
      cursor: pointer;
      font-size: 0.875rem;
      transition: all 0.2s;
    }
    button.btn:hover { background: #1d4ed8; }
    button.btn-secondary { background: var(--card-bg); border: 1px solid var(--card-border); color: var(--text-main); }
    button.btn-secondary:hover { background: #334155; }

    .grid-kpis {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
      gap: 1.25rem;
      margin-bottom: 2rem;
    }
    .card {
      background: var(--card-bg);
      border: 1px solid var(--card-border);
      border-radius: 12px;
      padding: 1.25rem;
      box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.2);
    }
    .card-title {
      font-size: 0.85rem;
      color: var(--text-muted);
      font-weight: 600;
      text-transform: uppercase;
      letter-spacing: 0.05em;
      margin-bottom: 0.5rem;
      display: flex;
      justify-content: space-between;
      align-items: center;
    }
    .kpi-value { font-size: 2rem; font-weight: 700; margin-bottom: 0.25rem; }
    .kpi-sub { font-size: 0.8rem; color: var(--text-muted); }

    .indicator-dot {
      width: 10px;
      height: 10px;
      border-radius: 50%;
      display: inline-block;
      margin-right: 6px;
    }
    .dot-green { background: var(--green); box-shadow: 0 0 10px var(--green-glow); animation: pulse 2s infinite; }
    .dot-red { background: var(--red); box-shadow: 0 0 10px var(--red-glow); }
    .dot-yellow { background: var(--yellow); }

    @keyframes pulse {
      0% { transform: scale(0.95); opacity: 0.8; }
      50% { transform: scale(1.15); opacity: 1; }
      100% { transform: scale(0.95); opacity: 0.8; }
    }

    .section-title { font-size: 1.15rem; font-weight: 600; margin-bottom: 1rem; color: #fff; }

    /* Controlled Update Banner */
    .update-box {
      border-radius: 10px;
      padding: 1.25rem;
      margin-bottom: 2rem;
      display: flex;
      justify-content: space-between;
      align-items: center;
      flex-wrap: wrap;
      gap: 1rem;
      border-width: 1px;
      border-style: solid;
    }
    .update-box.safe {
      background: rgba(16, 185, 129, 0.08);
      border-color: #059669;
    }
    .update-box.unsafe {
      background: rgba(239, 68, 68, 0.08);
      border-color: #b91c1c;
    }
    .update-status-title { font-weight: 700; font-size: 1rem; margin-bottom: 0.25rem; }
    .update-status-desc { font-size: 0.875rem; color: var(--text-muted); }

    /* Tables */
    table { width: 100%; border-collapse: collapse; text-align: left; }
    th {
      font-size: 0.75rem;
      color: var(--text-muted);
      text-transform: uppercase;
      letter-spacing: 0.05em;
      padding: 0.75rem 1rem;
      border-bottom: 1px solid var(--card-border);
    }
    td {
      padding: 0.75rem 1rem;
      border-bottom: 1px solid var(--card-border);
      font-size: 0.875rem;
    }
    tr:last-child td { border-bottom: none; }
    .badge-term-order { background: #312e81; color: #a5b4fc; }
    .badge-term-cash { background: #064e3b; color: #6ee7b7; }

    footer {
      margin-top: 3rem;
      padding-top: 1rem;
      border-top: 1px solid var(--card-border);
      text-align: center;
      font-size: 0.75rem;
      color: var(--text-muted);
    }
  </style>
</head>
<body>
  <div class="container">
    <header>
      <div class="title-group">
        <h1>Hórus Gateway <span class="badge badge-lan">LAN Coordenador</span></h1>
        <div style="font-size: 0.85rem; color: var(--text-muted); margin-top: 4px;">
          Empresa: <strong id="lbl-company" style="color: #fff;">...</strong> |
          Loja: <strong id="lbl-store" style="color: #fff;">...</strong> |
          Gateway ID: <strong id="lbl-gateway-id" style="color: #fff;">...</strong>
        </div>
      </div>
      <div class="header-actions">
        <span id="auto-refresh-status" style="font-size: 0.75rem; color: var(--text-muted);">Atualizando em tempo real...</span>
        <button class="btn btn-secondary" onclick="fetchData()">Atualizar Agora</button>
        <a href="/swagger" target="_blank" style="text-decoration:none;"><button class="btn btn-secondary">API Swagger</button></a>
      </div>
    </header>

    <!-- Controlled Update Check Banner -->
    <div id="update-banner" class="update-box safe">
      <div>
        <div id="update-title" class="update-status-title">Verificando prontidão de atualização...</div>
        <div id="update-desc" class="update-status-desc">Aguardando dados...</div>
      </div>
      <button class="btn btn-secondary" onclick="checkUpdateReadiness()">Checar Novamente</button>
    </div>

    <!-- KPIs -->
    <div class="grid-kpis">
      <div class="card">
        <div class="card-title">
          <span>Saúde do Gateway</span>
          <span id="dot-gw" class="indicator-dot dot-green"></span>
        </div>
        <div class="kpi-value" id="kpi-gw-status">Operacional</div>
        <div class="kpi-sub" id="kpi-uptime">Uptime: 0s</div>
      </div>

      <div class="card">
        <div class="card-title">
          <span>Sincronização Cloud</span>
          <span id="dot-cloud" class="indicator-dot dot-green"></span>
        </div>
        <div class="kpi-value" id="kpi-cloud-status">Online</div>
        <div class="kpi-sub" id="kpi-sync-details">Último envio com sucesso: --</div>
      </div>

      <div class="card">
        <div class="card-title">
          <span>Fila de Eventos (LAN)</span>
          <span id="badge-pending" class="badge badge-online">0 Pendentes</span>
        </div>
        <div class="kpi-value" id="kpi-total-events">0</div>
        <div class="kpi-sub" id="kpi-events-breakdown">0 sincronizados | 0 falhas</div>
      </div>

      <div class="card">
        <div class="card-title">
          <span>Terminais na LAN</span>
          <span id="badge-terminals" class="badge badge-online">0 Online</span>
        </div>
        <div class="kpi-value" id="kpi-terminals-count">0 / 0</div>
        <div class="kpi-sub" id="kpi-orders-count">0 pedidos em andamento</div>
      </div>
    </div>

    <!-- Terminals List -->
    <div class="card" style="margin-bottom: 2rem;">
      <div class="card-title" style="padding-bottom: 0.75rem; border-bottom: 1px solid var(--card-border);">
        <span style="font-size: 1rem; color: #fff;">Terminais Conectados (Heartbeat em Tempo Real)</span>
        <span id="lbl-terminals-summary" style="font-size: 0.8rem;">0 terminais detectados</span>
      </div>
      <div style="overflow-x: auto;">
        <table>
          <thead>
            <tr>
              <th>Status</th>
              <th>Terminal ID</th>
              <th>Tipo</th>
              <th>Último Heartbeat</th>
              <th>Decorrido</th>
            </tr>
          </thead>
          <tbody id="terminals-table-body">
            <tr>
              <td colspan="5" style="text-align: center; color: var(--text-muted); padding: 2rem;">
                Nenhum terminal registrado até o momento.
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <footer>
      Hórus PDV &bull; Local Gateway &bull; Padrão Resiliente e Desacoplado &bull; <span id="lbl-server-time">--</span>
    </footer>
  </div>

  <script>
    async function fetchData() {
      try {
        const res = await fetch('/api/gateway/dashboard');
        if (!res.ok) throw new Error('HTTP ' + res.status);
        const data = await res.json();
        renderDashboard(data);
      } catch (err) {
        document.getElementById('auto-refresh-status').textContent = 'Erro ao conectar: ' + err.message;
        document.getElementById('dot-gw').className = 'indicator-dot dot-red';
        document.getElementById('kpi-gw-status').textContent = 'Indisponível';
      }
    }

    function renderDashboard(data) {
      document.getElementById('lbl-company').textContent = data.identity.companyId || 'Não vinculado';
      document.getElementById('lbl-store').textContent = data.identity.storeId || '--';
      document.getElementById('lbl-gateway-id').textContent = data.identity.gatewayId || '--';
      document.getElementById('lbl-server-time').textContent = new Date(data.identity.serverTime).toLocaleString('pt-BR');

      // Uptime
      const h = Math.floor(data.identity.uptimeSeconds / 3600);
      const m = Math.floor((data.identity.uptimeSeconds % 3600) / 60);
      const s = data.identity.uptimeSeconds % 60;
      document.getElementById('kpi-uptime').textContent = `Uptime: ${h}h ${m}m ${s}s`;

      // Gateway Health
      document.getElementById('dot-gw').className = 'indicator-dot ' + (data.health.gateway === 'healthy' ? 'dot-green' : 'dot-red');
      document.getElementById('kpi-gw-status').textContent = data.health.gateway === 'healthy' ? 'Operacional' : 'Falha';

      // Cloud Health
      const cloudOnline = data.health.cloud === 'online';
      const cloudDisabled = data.health.cloud === 'disabled';
      const dotCloud = document.getElementById('dot-cloud');
      const kpiCloud = document.getElementById('kpi-cloud-status');
      if (cloudDisabled) {
        dotCloud.className = 'indicator-dot dot-yellow';
        kpiCloud.textContent = 'LAN Only';
      } else if (cloudOnline) {
        dotCloud.className = 'indicator-dot dot-green';
        kpiCloud.textContent = 'Online';
      } else {
        dotCloud.className = 'indicator-dot dot-red';
        kpiCloud.textContent = 'Offline';
      }

      const lastSuccess = data.sync.lastSuccessAt ? new Date(data.sync.lastSuccessAt).toLocaleTimeString('pt-BR') : 'nunca';
      document.getElementById('kpi-sync-details').textContent = `Último envio: ${lastSuccess}`;

      // Events
      document.getElementById('kpi-total-events').textContent = data.events.total;
      document.getElementById('kpi-events-breakdown').textContent = `${data.events.syncedCloud} enviados | ${data.events.failed} falhas`;
      const badgePending = document.getElementById('badge-pending');
      badgePending.textContent = `${data.events.pendingCloud} Pendentes`;
      badgePending.className = 'badge ' + (data.events.pendingCloud > 0 ? 'badge-offline' : 'badge-online');

      // Terminals
      document.getElementById('kpi-terminals-count').textContent = `${data.terminals.online} / ${data.terminals.total}`;
      document.getElementById('kpi-orders-count').textContent = `${data.activeOrdersCount} pedidos ativos`;
      const badgeTerminals = document.getElementById('badge-terminals');
      badgeTerminals.textContent = `${data.terminals.online} Online`;
      badgeTerminals.className = 'badge ' + (data.terminals.online > 0 ? 'badge-online' : 'badge-offline');

      // Controlled Update Readiness Banner
      const banner = document.getElementById('update-banner');
      const title = document.getElementById('update-title');
      const desc = document.getElementById('update-desc');

      if (data.updateReadiness.isSafe) {
        banner.className = 'update-box safe';
        title.innerHTML = '&#9989; Seguro para Atualização ou Reinicialização';
        desc.textContent = data.updateReadiness.message;
      } else {
        banner.className = 'update-box unsafe';
        title.innerHTML = '&#9888;&#65039; ATENÇÃO: NÃO Atualizar Agora';
        desc.textContent = `${data.updateReadiness.message} Aguarde a sincronização com a Cloud para evitar retenção de dados.`;
      }

      // Terminals Table
      const tbody = document.getElementById('terminals-table-body');
      if (!data.terminals.items || data.terminals.items.length === 0) {
        tbody.innerHTML = '<tr><td colspan="5" style="text-align: center; color: var(--text-muted); padding: 2rem;">Nenhum terminal registrado.</td></tr>';
      } else {
        tbody.innerHTML = data.terminals.items.map(t => {
          const isOnline = t.status === 'ONLINE';
          const badgeClass = isOnline ? 'badge-online' : 'badge-offline';
          const dotClass = isOnline ? 'dot-green' : 'dot-red';
          const typeBadge = t.terminalType === 'CASH' ? 'badge-term-cash' : 'badge-term-order';
          const lastSeen = t.lastSeenAt ? new Date(t.lastSeenAt).toLocaleString('pt-BR') : '--';
          const elapsed = t.secondsSinceLastSeen >= 0 ? `${t.secondsSinceLastSeen}s atrás` : '--';

          return `
            <tr>
              <td>
                <span class="indicator-dot ${dotClass}"></span>
                <span class="badge ${badgeClass}">${t.status}</span>
              </td>
              <td><strong>${escapeHtml(t.terminalId)}</strong></td>
              <td><span class="badge ${typeBadge}">${escapeHtml(t.terminalType)}</span></td>
              <td>${lastSeen}</td>
              <td style="color: var(--text-muted);">${elapsed}</td>
            </tr>
          `;
        }).join('');
      }

      document.getElementById('lbl-terminals-summary').textContent = `${data.terminals.total} terminal(is) cadastrado(s)`;
      document.getElementById('auto-refresh-status').textContent = 'Conectado em tempo real (' + new Date().toLocaleTimeString('pt-BR') + ')';
    }

    async function checkUpdateReadiness() {
      try {
        const res = await fetch('/api/gateway/system/update-check');
        const data = await res.json();
        alert(data.message);
        fetchData();
      } catch (err) {
        alert('Erro ao verificar atualização: ' + err.message);
      }
    }

    function escapeHtml(str) {
      if (!str) return '';
      return String(str).replace(/[&<>"']/g, m => ({
        '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
      })[m]);
    }

    // Auto-refresh a cada 3 segundos
    setInterval(fetchData, 3000);
    fetchData();
  </script>
</body>
</html>
""";
}
