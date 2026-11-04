package br.com.quack.pdv.plataforma.sincronizacao.modo;

import br.com.quack.pdv.security.RespostaJson;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** Liga o filtro de 404 so quando {@code quack.modo=nuvem}. Roda antes da cadeia de seguranca. */
@Configuration
@ConditionalOnProperty(name = "quack.modo", havingValue = "nuvem")
public class ModoNuvemConfig {

    @Bean
    public FilterRegistrationBean<ModoNuvemFilter> modoNuvemFilter(RespostaJson resposta) {
        FilterRegistrationBean<ModoNuvemFilter> registro = new FilterRegistrationBean<>(new ModoNuvemFilter(resposta));
        registro.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registro;
    }
}
