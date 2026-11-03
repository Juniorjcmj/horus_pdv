package br.com.quack.pdv.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Derruba a subida em producao com segredos fracos (igual HorusSecurityOptions.Validate). */
@Component
public class ValidadorProducao implements ApplicationRunner {

    private final QuackProperties props;

    public ValidadorProducao(QuackProperties props) {
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        validar();
    }

    public void validar() {
        if (props.isDev()) return;

        String jwt = props.getAuth().getJwtSecret();
        if (jwt == null || jwt.isBlank() || jwt.length() < 32) {
            throw new IllegalStateException(
                    "quack.auth.jwt-secret invalido em producao. Defina um segredo forte com pelo menos 32 caracteres.");
        }
        String chave = props.getSecurity().getEncryptionKey();
        if (chave == null || chave.isBlank() || chave.length() < 32) {
            throw new IllegalStateException(
                    "quack.security.encryption-key invalido em producao. Defina uma chave forte com pelo menos 32 caracteres.");
        }
        if (props.getRecaptcha().isEnabled() && props.getRecaptcha().getSecretKey().isBlank()) {
            throw new IllegalStateException(
                    "quack.recaptcha.secret-key obrigatorio quando quack.recaptcha.enabled estiver ativo.");
        }
    }
}
