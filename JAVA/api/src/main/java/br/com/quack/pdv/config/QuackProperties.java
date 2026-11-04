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

    public Sync getSync() { return sync; }

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

        /** Nuvem: liga o detector periodico de edicoes (D6). */
        public boolean isDetectorAtivo() { return detectorAtivo; }
        public void setDetectorAtivo(boolean detectorAtivo) { this.detectorAtivo = detectorAtivo; }
        /** Nuvem: tempo maximo do long-poll de {@code GET comandos} (25 s; os testes encurtam). */
        public long getComandosEsperaMs() { return clamp(comandosEsperaMs, 0L, 60_000L); }
        public void setComandosEsperaMs(long comandosEsperaMs) { this.comandosEsperaMs = comandosEsperaMs; }
        public long getMaxLoteBytes() { return clamp(maxLoteBytes, 32_768L, 64_000_000L); }
        public void setMaxLoteBytes(long maxLoteBytes) { this.maxLoteBytes = maxLoteBytes; }
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
