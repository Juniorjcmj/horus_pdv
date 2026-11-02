package br.com.quack.pdv.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.UrlHandlerFilter;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.util.pattern.PathPatternParser;

/** Rotas sem diferenciar maiusculas e com barra final opcional, como o ASP.NET. */
@Configuration
public class RoteamentoConfig implements WebMvcConfigurer {

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        PathPatternParser parser = new PathPatternParser();
        parser.setCaseSensitive(false);
        configurer.setPatternParser(parser);
    }

    @Bean
    public FilterRegistrationBean<UrlHandlerFilter> barraFinalFilter() {
        UrlHandlerFilter filtro = UrlHandlerFilter.trailingSlashHandler("/**").wrapRequest().build();
        FilterRegistrationBean<UrlHandlerFilter> registro = new FilterRegistrationBean<>(filtro);
        registro.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registro;
    }
}
