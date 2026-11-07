package br.com.quack.pdv.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuracao da API (prefixo "quack"). Replica HorusSecurityOptions da API .NET: mesmos padroes e
 * mesmos limites (valores fora da faixa sao ajustados, nunca recusados).
 */
@ConfigurationProperties(prefix = "quack")
public class QuackProperties {

    /** Equivale ao ambiente Development do .NET (perfil Spring dev). */
    private boolean dev = false;
    /** Papel desta instancia: padrao, loja ou nuvem (perfis Spring de mesmo nome definem). */
    private String modo = "padrao";
    private final Auth auth = new Auth();
    private final Security security = new Security();
    private final Recaptcha recaptcha = new Recaptcha();
    private final Email email = new Email();
    private final Sync sync = new Sync();
    private final Metrics metrics = new Metrics();

    public Sync getSync() { return sync; }
    public Metrics getMetrics() { return metrics; }

    public String getModo() { return modo; }
    public void setModo(String modo) { this.modo = modo == null || modo.isBlank() ? "padrao" : modo; }
    public boolean isDev() { return dev; }
    public void setDev(boolean dev) { this.dev = dev; }
    public Auth getAuth() { return auth; }
    public Security getSecurity() { return security; }
    public Recaptcha getRecaptcha() { return recaptcha; }
    public Email getEmail() { return email; }

    static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
    static long clamp(long value, long min, long max) { return Math.max(min, Math.min(max, value)); }
    static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }

    /** Metricas Prometheus ({@code /actuator/prometheus}): sem token configurado a rota nao existe (404). */
    public static class Metrics {
        private String token = "";

        public String getToken() { return token; }
        public void setToken(String token) { this.token = token == null ? "" : token.trim(); }
    }

    public static class Auth {
        private String jwtSecret = "";
        private String issuer = "horus-pdv-api";
        private String audience = "horus-pdv-web";
        private int sessionHours = 8;
        private int passwordResetTokenMinutes = 30;

        public String getJwtSecret() { return jwtSecret; }
        public void setJwtSecret(String jwtSecret) { this.jwtSecret = jwtSecret == null ? "" : jwtSecret; }
        public String getIssuer() { return issuer; }
        public void setIssuer(String issuer) { this.issuer = issuer; }
        public String getAudience() { return audience; }
        public void setAudience(String audience) { this.audience = audience; }
        public int getSessionHours() { return clamp(sessionHours, 1, 24); }
        public void setSessionHours(int sessionHours) { this.sessionHours = sessionHours; }
        public int getPasswordResetTokenMinutes() { return clamp(passwordResetTokenMinutes, 5, 180); }
        public void setPasswordResetTokenMinutes(int minutes) { this.passwordResetTokenMinutes = minutes; }
    }

    public static class Security {
        private String encryptionKey = "";
        private String corsOrigins = "";
        private boolean trustForwardedHeaders = false;
        private long maxRequestBodyBytes = 2_000_000L;
        private final RateLimit rateLimit = new RateLimit();

        public String getEncryptionKey() { return encryptionKey; }
        public void setEncryptionKey(String encryptionKey) { this.encryptionKey = encryptionKey == null ? "" : encryptionKey; }
        public String getCorsOrigins() { return corsOrigins; }
        public void setCorsOrigins(String corsOrigins) { this.corsOrigins = corsOrigins == null ? "" : corsOrigins; }
        public boolean isTrustForwardedHeaders() { return trustForwardedHeaders; }
        public void setTrustForwardedHeaders(boolean trust) { this.trustForwardedHeaders = trust; }
        public long getMaxRequestBodyBytes() { return clamp(maxRequestBodyBytes, 32_768L, 25_000_000L); }
        public void setMaxRequestBodyBytes(long bytes) { this.maxRequestBodyBytes = bytes; }
        public RateLimit getRateLimit() { return rateLimit; }
    }

    public static class RateLimit {
        private int windowSeconds = 60;
        private int maxRequests = 240;

        public int getWindowSeconds() { return clamp(windowSeconds, 10, 3600); }
        public void setWindowSeconds(int windowSeconds) { this.windowSeconds = windowSeconds; }
        public int getMaxRequests() { return clamp(maxRequests, 10, 5000); }
        public void setMaxRequests(int maxRequests) { this.maxRequests = maxRequests; }
    }

    /** Sincronizacao loja/nuvem (modo loja). */
    public static class Sync {
        private String nuvemUrl = "";
        private boolean envioAtivo = true;
        private long maxLoteBytes = 16L * 1024 * 1024;

        public String getNuvemUrl() { return nuvemUrl; }
        public void setNuvemUrl(String nuvemUrl) { this.nuvemUrl = nuvemUrl == null ? "" : nuvemUrl; }
        public boolean isEnvioAtivo() { return envioAtivo; }
        public void setEnvioAtivo(boolean envioAtivo) { this.envioAtivo = envioAtivo; }
        private boolean detectorAtivo = true;
        private long comandosEsperaMs = 25_000L;
        private String recursos = "operacao-estoque";

        /** Loja: recursos anunciados a nuvem no long-poll de comandos (X-Quack-Recursos); vazio = nenhum (testes de jar antigo). */
        public String getRecursos() { return recursos == null ? "" : recursos; }
        public void setRecursos(String recursos) { this.recursos = recursos; }

        /** Nuvem: liga o detector periodico de edicoes (D6). */
        public boolean isDetectorAtivo() { return detectorAtivo; }
        public void setDetectorAtivo(boolean detectorAtivo) { this.detectorAtivo = detectorAtivo; }
        /** Nuvem: tempo maximo do long-poll de {@code GET comandos} (25 s; os testes encurtam). */
        public long getComandosEsperaMs() { return clamp(comandosEsperaMs, 0L, 60_000L); }
        public void setComandosEsperaMs(long comandosEsperaMs) { this.comandosEsperaMs = comandosEsperaMs; }
        public long getMaxLoteBytes() { return clamp(maxLoteBytes, 32_768L, 64_000_000L); }
        public void setMaxLoteBytes(long maxLoteBytes) { this.maxLoteBytes = maxLoteBytes; }

        private boolean alertasAtivo = true;
        private boolean conferenciaAtiva = true;
        private String backupEstado = "";

        /** Liga as rotinas periodicas de alerta (verificacao e envio de e-mail, D11); os testes as desligam. */
        public boolean isAlertasAtivo() { return alertasAtivo; }
        public void setAlertasAtivo(boolean alertasAtivo) { this.alertasAtivo = alertasAtivo; }
        /** Liga a conferencia diaria agendada (D10; o horario e {@code quack.sync.conferencia-cron}). */
        public boolean isConferenciaAtiva() { return conferenciaAtiva; }
        public void setConferenciaAtiva(boolean conferenciaAtiva) { this.conferenciaAtiva = conferenciaAtiva; }
        /** Arquivo {@code backup-estado.json} escrito pelo script de backup do instalador (D12); vazio = sem backup. */
        public String getBackupEstado() { return backupEstado; }
        public void setBackupEstado(String backupEstado) { this.backupEstado = backupEstado == null ? "" : backupEstado; }

        private boolean recuperacaoAtiva = true;
        private String recuperacaoMarcador = "config/recuperar.pendente";
        private long recuperacaoIntervaloMs = 15_000L;
        private long recuperacaoVerificacaoMs = 300_000L;

        /** Liga a rotina que executa a recuperacao pos-restauracao e confere se a nuvem esta a frente (tarefa 7.4). */
        public boolean isRecuperacaoAtiva() { return recuperacaoAtiva; }
        public void setRecuperacaoAtiva(boolean recuperacaoAtiva) { this.recuperacaoAtiva = recuperacaoAtiva; }
        /** Arquivo que o {@code restaurar.ps1} deixa para pedir a recuperacao; a API o consome ao subir. */
        public String getRecuperacaoMarcador() { return recuperacaoMarcador; }
        public void setRecuperacaoMarcador(String m) { this.recuperacaoMarcador = m == null ? "" : m; }
        /** Intervalo entre tentativas da recuperacao (sem internet fica pendente e repete). */
        public long getRecuperacaoIntervaloMs() { return clamp(recuperacaoIntervaloMs, 100L, 3_600_000L); }
        public void setRecuperacaoIntervaloMs(long v) { this.recuperacaoIntervaloMs = v; }
        /** De quanto em quanto tempo a loja pergunta a nuvem se ela esta a frente da versao local (restauracao sem marcador). */
        public long getRecuperacaoVerificacaoMs() { return clamp(recuperacaoVerificacaoMs, 100L, 86_400_000L); }
        public void setRecuperacaoVerificacaoMs(long v) { this.recuperacaoVerificacaoMs = v; }
    }

    public static class Recaptcha {
        private boolean enabled = false;
        private String secretKey = "";
        private double minScore = 0.5d;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getSecretKey() { return secretKey; }
        public void setSecretKey(String secretKey) { this.secretKey = secretKey == null ? "" : secretKey; }
        public double getMinScore() { return clamp(minScore, 0.1d, 1d); }
        public void setMinScore(double minScore) { this.minScore = minScore; }
    }

    public static class Email {
        private boolean enabled = false;
        private String host = "";
        private int port = 587;
        private boolean enableSsl = true;
        private String user = "";
        private String password = "";
        private String fromEmail = "";
        private String fromName = "Quack PDV";
        private String replyTo = "";
        private String frontendBaseUrl = "";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public boolean isEnableSsl() { return enableSsl; }
        public void setEnableSsl(boolean enableSsl) { this.enableSsl = enableSsl; }
        public String getUser() { return user; }
        public void setUser(String user) { this.user = user; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getFromEmail() { return fromEmail; }
        public void setFromEmail(String fromEmail) { this.fromEmail = fromEmail; }
        public String getFromName() { return fromName; }
        public void setFromName(String fromName) { this.fromName = fromName; }
        public String getReplyTo() { return replyTo; }
        public void setReplyTo(String replyTo) { this.replyTo = replyTo; }
        public String getFrontendBaseUrl() { return frontendBaseUrl; }
        public void setFrontendBaseUrl(String url) { this.frontendBaseUrl = url; }
    }
}
