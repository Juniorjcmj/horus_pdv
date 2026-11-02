package br.com.quack.pdv.email.app;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.springframework.web.util.HtmlUtils;

/** Modelos de e-mail (HTML e texto), portados do HorusEmailTemplate da API .NET. */
public final class EmailTemplate {

    private static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter DATA_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private EmailTemplate() {}

    private static final String CABECALHO = """
            <div style="margin:0;padding:24px;background:#f8fafc;font-family:Inter,Arial,Helvetica,sans-serif;color:#1e293b;line-height:1.55;">
              <div style="max-width:620px;margin:0 auto;border:1px solid #e5e7eb;border-radius:16px;overflow:hidden;background:#ffffff;">
                <div style="padding:16px 24px;background:linear-gradient(135deg,#4f46e5 0%,#2563eb 52%,#0f766e 100%);">
                  <p style="margin:0;font-size:12px;font-weight:700;letter-spacing:.14em;text-transform:uppercase;color:#ffffff;opacity:.94;">
                    Quack PDV
                  </p>
                </div>

                <div style="padding:24px;">
            """;

    private static final String RODAPE = """
                </div>

                <div style="padding:12px 24px;border-top:1px solid #e5e7eb;background:#f8fafc;font-size:12px;color:#64748b;">
                  Mensagem automática enviada por Quack PDV. Não é necessário responder.
                </div>
              </div>
            </div>
            """;

    public static String redefinicaoHtml(String companyName, String resetUrl, OffsetDateTime expiresAt) {
        String empresa = HtmlUtils.htmlEscape(vazio(companyName) ? "Quack PDV" : companyName.trim());
        String url = HtmlUtils.htmlEscape(resetUrl);
        String expira = formatarDataHora(expiresAt);
        return CABECALHO + """
                      <h1 style="margin:0 0 10px;font-size:24px;line-height:1.25;color:#111827;">
                        Recuperação de senha
                      </h1>
                      <p style="margin:0 0 14px;color:#475569;">
                        Recebemos uma solicitação para redefinir a senha de acesso da conta vinculada à empresa
                        <strong style="color:#111827;">%s</strong>.
                      </p>
                      <p style="margin:0 0 14px;color:#475569;">
                        Clique no botão abaixo para criar uma nova senha:
                      </p>

                      <p style="margin:0 0 16px;">
                        <a
                          href="%s"
                          style="display:inline-block;padding:11px 18px;border-radius:10px;background:#4f46e5;color:#ffffff;text-decoration:none;font-weight:700;"
                        >
                          Redefinir senha
                        </a>
                      </p>

                      <p style="margin:0 0 10px;color:#475569;">
                        Se o botão não abrir, copie e cole este link no navegador:
                      </p>
                      <p style="margin:0 0 14px;word-break:break-all;">
                        <a href="%s" style="color:#4f46e5;text-decoration:none;">%s</a>
                      </p>

                      <p style="margin:0 0 6px;font-size:14px;color:#475569;">
                        Este link expira em <strong style="color:#111827;">%s</strong> (horário de Brasília).
                      </p>
                      <p style="margin:0;font-size:13px;color:#64748b;">
                        Se você não solicitou a recuperação, ignore este e-mail.
                      </p>
                """.formatted(empresa, url, url, url, expira) + RODAPE;
    }

    public static String redefinicaoTexto(String companyName, String resetUrl, OffsetDateTime expiresAt) {
        return String.join(System.lineSeparator(),
                "Quack PDV - Recuperação de senha",
                "",
                "Empresa: " + normalizar(companyName, "Quack PDV"),
                "Recebemos uma solicitação para redefinir sua senha.",
                "Link: " + resetUrl,
                "Expira em: " + formatarDataHora(expiresAt) + " (horário de Brasília)",
                "",
                "Se você não solicitou a recuperação, ignore este e-mail.");
    }

    public static String boasVindasHtml(String companyName, String loginUrl) {
        String empresa = HtmlUtils.htmlEscape(vazio(companyName) ? "sua empresa" : companyName.trim());
        String url = HtmlUtils.htmlEscape(loginUrl);
        return CABECALHO + """
                      <h1 style="margin:0 0 10px;font-size:24px;line-height:1.25;color:#111827;">
                        Cadastro criado com sucesso
                      </h1>
                      <p style="margin:0 0 14px;color:#475569;">
                        A conta da empresa <strong style="color:#111827;">%s</strong> foi criada no Quack PDV.
                      </p>
                      <p style="margin:0 0 14px;color:#475569;">
                        Você já pode acessar o painel usando o e-mail e a senha cadastrados.
                      </p>

                      <p style="margin:0 0 16px;">
                        <a
                          href="%s"
                          style="display:inline-block;padding:11px 18px;border-radius:10px;background:#4f46e5;color:#ffffff;text-decoration:none;font-weight:700;"
                        >
                          Acessar o Quack PDV
                        </a>
                      </p>

                      <p style="margin:0;font-size:13px;color:#64748b;">
                        Se você não solicitou este cadastro, ignore este e-mail.
                      </p>
                """.formatted(empresa, url) + RODAPE;
    }

    public static String boasVindasTexto(String companyName, String loginUrl) {
        return String.join(System.lineSeparator(),
                "Quack PDV - Cadastro criado",
                "",
                "Empresa: " + normalizar(companyName, "sua empresa"),
                "Sua conta foi criada com sucesso.",
                "Acesso: " + loginUrl,
                "",
                "Se você não solicitou este cadastro, ignore este e-mail.");
    }

    static String formatarDataHora(OffsetDateTime valor) {
        return valor.atZoneSameInstant(BRASILIA).format(DATA_HORA);
    }

    private static boolean vazio(String s) {
        return s == null || s.isBlank();
    }

    private static String normalizar(String valor, String padrao) {
        return vazio(valor) ? padrao : valor.trim();
    }
}
