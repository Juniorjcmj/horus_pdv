package br.com.quack.pdv.config;

import br.com.quack.pdv.security.AutenticacaoFilter;
import br.com.quack.pdv.security.CabecalhosSegurancaFilter;
import br.com.quack.pdv.security.ClienteIpResolver;
import br.com.quack.pdv.security.JwtService;
import br.com.quack.pdv.security.LimiteCorpoFilter;
import br.com.quack.pdv.security.LimiteRequisicoesFilter;
import br.com.quack.pdv.security.PerfisInterceptor;
import br.com.quack.pdv.security.RespostaJson;
import br.com.quack.pdv.security.TelemetriaFilter;
import br.com.quack.pdv.sessao.app.ValidadorSessao;
import java.net.URI;
import java.time.Clock;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring Security so como infraestrutura: cadeia stateless, sem CSRF, sem cabecalhos proprios e com permitAll.
 * Quem autentica e o AutenticacaoFilter (espelho do HorusAuthMiddleware), na ordem do pipeline .NET:
 * CORS, cabecalhos, telemetria, limite de corpo, limite por IP e autenticacao.
 */
@Configuration
public class SegurancaConfig implements WebMvcConfigurer {

    private static final String DEFAULT_ORIGINS = "https://pdv.quacksistemas.com.br,http://pdv.quacksistemas.com.br,"
            + "https://pdv.wootchat.com.br,http://pdv.wootchat.com.br,http://localhost:5173,https://localhost:5173,"
            + "http://127.0.0.1:5173,https://127.0.0.1:5173,http://localhost:4173,https://localhost:4173,"
            + "http://127.0.0.1:4173,https://127.0.0.1:4173";

    private final RespostaJson resposta;

    public SegurancaConfig(RespostaJson resposta) {
        this.resposta = resposta;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new PerfisInterceptor(resposta));
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, QuackProperties props, ClienteIpResolver ipResolver,
                                           JwtService jwt, ValidadorSessao validador, Clock clock,
                                           org.springframework.beans.factory.ObjectProvider<org.springframework.jdbc.core.JdbcTemplate> jdbc) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .headers(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .cors(Customizer.withDefaults())
                .authorizeHttpRequests(a -> a.anyRequest().permitAll());

        http.addFilterAfter(new CabecalhosSegurancaFilter(), CorsFilter.class);
        http.addFilterAfter(new TelemetriaFilter(ipResolver), CabecalhosSegurancaFilter.class);
        http.addFilterAfter(new LimiteCorpoFilter(props, resposta), TelemetriaFilter.class);
        http.addFilterAfter(new LimiteRequisicoesFilter(props, ipResolver, resposta, clock), LimiteCorpoFilter.class);
        Class<? extends jakarta.servlet.Filter> antesDaAutenticacao = LimiteRequisicoesFilter.class;
        if ("nuvem".equals(props.getModo()) && jdbc.getIfAvailable() != null) { // token do servidor da loja (D9) so existe no receptor
            http.addFilterAfter(new br.com.quack.pdv.plataforma.sincronizacao.nuvem.ServidorLojaFilter(jdbc.getIfAvailable(), resposta),
                    LimiteRequisicoesFilter.class);
            antesDaAutenticacao = br.com.quack.pdv.plataforma.sincronizacao.nuvem.ServidorLojaFilter.class;
        }
        http.addFilterAfter(new AutenticacaoFilter(props, jwt, validador, resposta), antesDaAutenticacao);
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(QuackProperties props) {
        String configurado = props.getSecurity().getCorsOrigins();
        String bruto = configurado == null || configurado.isBlank() ? DEFAULT_ORIGINS : configurado;
        Set<String> explicitas = new HashSet<>();
        Arrays.stream(bruto.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .map(SegurancaConfig::semBarraFinal).map(s -> s.toLowerCase(Locale.ROOT)).forEach(explicitas::add);
        explicitas.add("https://pdv.quacksistemas.com.br");
        explicitas.add("http://pdv.quacksistemas.com.br");
        explicitas.add("https://pdv.wootchat.com.br");
        explicitas.add("http://pdv.wootchat.com.br");

        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOriginPatterns(List.of("*")); // a decisao real e do predicado abaixo
        cors.setAllowedMethods(List.of("*"));
        cors.setAllowedHeaders(List.of("*"));
        cors.setAllowCredentials(true);
        cors.setMaxAge(1800L);

        UrlBasedCorsConfigurationSource fonte = new UrlBasedCorsConfigurationSource() {
            @Override
            public CorsConfiguration getCorsConfiguration(jakarta.servlet.http.HttpServletRequest request) {
                String origem = request.getHeader("Origin");
                if (origem == null || !origemPermitida(origem, explicitas)) return null;
                CorsConfiguration c = new CorsConfiguration(cors);
                c.setAllowedOriginPatterns(null);
                c.setAllowedOrigins(List.of(origem));
                return c;
            }
        };
        return fonte;
    }

    static boolean origemPermitida(String origem, Set<String> explicitas) {
        if (origem == null || origem.isBlank()) return false;
        if (explicitas.contains(semBarraFinal(origem).toLowerCase(Locale.ROOT))) return true;
        try {
            String host = URI.create(origem).getHost();
            if (host == null) return false;
            String h = host.toLowerCase(Locale.ROOT);
            return h.equals("quacksistemas.com.br") || h.endsWith(".quacksistemas.com.br")
                    || h.equals("wootchat.com.br") || h.endsWith(".wootchat.com.br")
                    || h.equals("localhost") || h.equals("127.0.0.1");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String semBarraFinal(String s) {
        String t = s;
        while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        return t;
    }
}
