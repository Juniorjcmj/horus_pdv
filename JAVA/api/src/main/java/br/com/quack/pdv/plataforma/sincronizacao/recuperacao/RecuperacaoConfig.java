package br.com.quack.pdv.plataforma.sincronizacao.recuperacao;

import br.com.quack.pdv.security.RespostaJson;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.Ordered;

/** Liga o bloqueio de vendas da recuperacao so quando {@code quack.modo=loja}. Roda antes da cadeia de seguranca. */
@Configuration
@ConditionalOnProperty(name = "quack.modo", havingValue = "loja")
public class RecuperacaoConfig {

    @Bean
    public FilterRegistrationBean<RecuperacaoFilter> recuperacaoFilter(@Lazy EstadoRecuperacao estado, RespostaJson resposta) {
        FilterRegistrationBean<RecuperacaoFilter> registro = new FilterRegistrationBean<>(new RecuperacaoFilter(estado, resposta));
        registro.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        return registro;
    }
}
