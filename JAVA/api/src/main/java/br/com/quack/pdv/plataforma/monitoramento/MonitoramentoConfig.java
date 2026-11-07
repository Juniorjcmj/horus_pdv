package br.com.quack.pdv.plataforma.monitoramento;

import br.com.quack.pdv.config.QuackProperties;
import br.com.quack.pdv.security.RespostaJson;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** Registra o filtro do token de metricas antes de qualquer outro filtro da API (todos os perfis). */
@Configuration
public class MonitoramentoConfig {

    @Bean
    public FilterRegistrationBean<MetricasTokenFilter> metricasTokenFilter(QuackProperties props, RespostaJson resposta) {
        FilterRegistrationBean<MetricasTokenFilter> registro =
                new FilterRegistrationBean<>(new MetricasTokenFilter(props, resposta));
        registro.setOrder(Ordered.HIGHEST_PRECEDENCE + 5);
        return registro;
    }
}
