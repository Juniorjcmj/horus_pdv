package br.com.quack.pdv.security;

import br.com.quack.pdv.shared.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

/** Escreve o envelope de erro direto na resposta (para filtros, que ficam antes do MVC). */
@Component
public class RespostaJson {

    private final ObjectMapper mapper;

    public RespostaJson(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public void erro(HttpServletResponse response, int status, String mensagem) throws IOException {
        byte[] corpo = mapper.writeValueAsBytes(ApiResponse.erro(mensagem));
        response.setStatus(status);
        response.setContentType("application/json; charset=utf-8");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentLength(corpo.length);
        response.getOutputStream().write(corpo);
    }
}
