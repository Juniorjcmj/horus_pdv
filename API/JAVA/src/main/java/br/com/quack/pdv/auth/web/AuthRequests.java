package br.com.quack.pdv.auth.web;

/** Corpos das rotas /api/Auth (campos ausentes viram texto vazio, como no .NET). */
public final class AuthRequests {

    private AuthRequests() {}

    static String nv(String s) {
        return s == null ? "" : s;
    }

    public record Login(String email, String password, boolean rememberMe, String recaptchaToken) {
        public Login {
            email = nv(email);
            password = nv(password);
            recaptchaToken = nv(recaptchaToken);
        }
    }

    public record ForgotPassword(String cnpj, String email, String recaptchaToken) {
        public ForgotPassword {
            cnpj = nv(cnpj);
            email = nv(email);
            recaptchaToken = nv(recaptchaToken);
        }
    }

    public record ResetPassword(String token, String nextPassword, String confirmPassword, String recaptchaToken) {
        public ResetPassword {
            token = nv(token);
            nextPassword = nv(nextPassword);
            confirmPassword = nv(confirmPassword);
            recaptchaToken = nv(recaptchaToken);
        }
    }

    public record Register(String cnpj, String name, String email, String phone, String password,
                           String confirmPassword, String recaptchaToken) {
        public Register {
            cnpj = nv(cnpj);
            name = nv(name);
            email = nv(email);
            phone = nv(phone);
            password = nv(password);
            confirmPassword = nv(confirmPassword);
            recaptchaToken = nv(recaptchaToken);
        }
    }

    public record ChangePassword(String currentPassword, String nextPassword) {
        public ChangePassword {
            currentPassword = nv(currentPassword);
            nextPassword = nv(nextPassword);
        }
    }

    public record UpdateProfile(String name, String email, String phone) {
        public UpdateProfile {
            name = nv(name);
            email = nv(email);
            phone = nv(phone);
        }
    }
}
