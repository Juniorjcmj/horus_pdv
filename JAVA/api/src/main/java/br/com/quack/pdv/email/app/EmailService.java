package br.com.quack.pdv.email.app;

import br.com.quack.pdv.config.QuackProperties;
import br.com.quack.pdv.empresa.domain.Empresa;
import br.com.quack.pdv.empresa.repo.EmpresaRepository;
import br.com.quack.pdv.security.SecretProtector;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * Envio de e-mail (equivale ao HorusEmailService). A configuracao SMTP e resolvida a cada envio: a da empresa
 * (senha decifrada) quando informada e habilitada, senao a global (quack.email.*).
 */
@Service
public class EmailService {

    private static final Logger LOG = LoggerFactory.getLogger(EmailService.class);

    record SmtpSettings(boolean enabled, String host, int port, boolean enableSsl, String user, String password,
                        String fromEmail, String fromName, String replyTo) {}

    private final QuackProperties props;
    private final EmpresaRepository empresas;
    private final SecretProtector protector;

    public EmailService(QuackProperties props, EmpresaRepository empresas, SecretProtector protector) {
        this.props = props;
        this.empresas = empresas;
        this.protector = protector;
    }

    public boolean habilitado() {
        return habilitado(null);
    }

    public boolean habilitado(String companyId) {
        return resolver(companyId).enabled();
    }

    public void enviarRedefinicaoSenha(String toEmail, String companyName, String resetUrl, OffsetDateTime expiresAt) {
        enviarRedefinicaoSenha(toEmail, companyName, resetUrl, expiresAt, null);
    }

    public void enviarRedefinicaoSenha(String toEmail, String companyName, String resetUrl, OffsetDateTime expiresAt,
                                       String companyId) {
        if (!habilitado(companyId)) {
            LOG.info("Envio de e-mail desabilitado. Link de recuperação gerado para {}.", toEmail);
            return;
        }
        enviar(toEmail, "Recuperação de senha - Quack PDV",
                EmailTemplate.redefinicaoHtml(companyName, resetUrl, expiresAt),
                EmailTemplate.redefinicaoTexto(companyName, resetUrl, expiresAt), companyId);
    }

    public void enviarBoasVindas(String toEmail, String companyName) {
        if (!habilitado(null)) {
            LOG.info("Envio de e-mail desabilitado. E-mail de cadastro não enviado para {}.", toEmail);
            return;
        }
        String loginUrl = urlBaseFrontend();
        enviar(toEmail, "Cadastro criado - Quack PDV",
                EmailTemplate.boasVindasHtml(companyName, loginUrl),
                EmailTemplate.boasVindasTexto(companyName, loginUrl), null);
    }

    public String montarUrlRedefinicao(String token) {
        return urlBaseFrontend() + "/?resetToken=" + escaparDado(token.trim());
    }

    private String urlBaseFrontend() {
        String url = props.getEmail().getFrontendBaseUrl();
        if (url == null || url.isBlank()) return "http://localhost:5173";
        String t = url.trim();
        while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        return t;
    }

    /** Uri.EscapeDataString: tudo que nao for A-Z a-z 0-9 - _ . ~ vira %XX. */
    static String escaparDado(String valor) {
        StringBuilder sb = new StringBuilder();
        for (byte b : valor.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xFF);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append(c);
            } else {
                sb.append('%').append(String.format(Locale.ROOT, "%02X", b & 0xFF));
            }
        }
        return sb.toString();
    }

    private void enviar(String toEmail, String assunto, String html, String texto, String companyId) {
        if (toEmail == null || toEmail.isBlank() || !toEmail.contains("@")) {
            throw new IllegalStateException("E-mail de destino inválido.");
        }
        SmtpSettings s = resolver(companyId);
        validar(s);

        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(s.host());
        sender.setPort(s.port());
        sender.setUsername(s.user());
        sender.setPassword(s.password());
        sender.setDefaultEncoding("UTF-8");
        Properties p = sender.getJavaMailProperties();
        p.put("mail.smtp.auth", "true");
        p.put("mail.smtp.starttls.enable", Boolean.toString(s.enableSsl()));
        p.put("mail.smtp.starttls.required", Boolean.toString(s.enableSsl()));
        p.put("mail.smtp.connectiontimeout", "15000");
        p.put("mail.smtp.timeout", "30000");
        p.put("mail.smtp.writetimeout", "30000");

        try {
            MimeMessage msg = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
            helper.setFrom(new InternetAddress(s.fromEmail(), s.fromName(), "UTF-8"));
            helper.setTo(toEmail.trim().toLowerCase(Locale.ROOT));
            helper.setSubject(assunto);
            helper.setText(texto, html);
            if (s.replyTo() != null && !s.replyTo().isBlank()) helper.setReplyTo(s.replyTo());
            sender.send(msg);
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao enviar e-mail: " + e.getMessage(), e);
        }
    }

    SmtpSettings resolver(String companyId) {
        if (companyId != null && !companyId.isBlank()) {
            Optional<Empresa> empresa = empresas.findById(companyId);
            if (empresa.isPresent() && empresa.get().isEmailSmtpEnabled()) {
                Empresa e = empresa.get();
                String nome = e.getEmailSmtpFromName() == null || e.getEmailSmtpFromName().isBlank()
                        ? e.getFantasyName() : e.getEmailSmtpFromName();
                return new SmtpSettings(true, e.getEmailSmtpHost(), e.getEmailSmtpPort(), e.isEmailSmtpEnableSsl(),
                        e.getEmailSmtpUser(), protector.unprotect(e.getEmailSmtpPassword()), e.getEmailSmtpFromEmail(),
                        nome, e.getEmailSmtpReplyTo());
            }
        }
        QuackProperties.Email g = props.getEmail();
        return new SmtpSettings(g.isEnabled(), g.getHost(), g.getPort(), g.isEnableSsl(), g.getUser(), g.getPassword(),
                g.getFromEmail(), g.getFromName(), g.getReplyTo());
    }

    private static void validar(SmtpSettings s) {
        if (!s.enabled() || vazio(s.host()) || s.port() <= 0 || vazio(s.user()) || vazio(s.password())
                || vazio(s.fromEmail())) {
            throw new IllegalStateException("Serviço de e-mail não configurado. Configure o SMTP em Minha Empresa.");
        }
    }

    private static boolean vazio(String s) {
        return s == null || s.isBlank();
    }
}
