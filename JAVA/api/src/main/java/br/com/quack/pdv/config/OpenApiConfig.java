package br.com.quack.pdv.config;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.*;
@Configuration
public class OpenApiConfig {
    @Bean public OpenAPI documentoOpenAPI() {return new OpenAPI().info(new Info().title("Quack Sistemas — API do PDV").version("1"));}
}
