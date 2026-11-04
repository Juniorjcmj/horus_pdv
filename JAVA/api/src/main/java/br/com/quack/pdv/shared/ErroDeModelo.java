package br.com.quack.pdv.shared;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** Validacao de modelo do ASP.NET: 400 application/problem+json com os campos obrigatorios ausentes. */
public final class ErroDeModelo {

    private static final MediaType PROBLEMA = MediaType.parseMediaType("application/problem+json");

    private ErroDeModelo() {}

    /** Devolve a resposta 400 se ha campos ausentes; senao null. */
    public static ResponseEntity<?> validar(Map<String, String> ausentes, HttpServletRequest req) {
        if (ausentes.isEmpty()) return null;
        Map<String, Object> erros = new LinkedHashMap<>();
        ausentes.forEach((campo, msg) -> erros.put(campo, List.of(msg)));
        String id = String.valueOf(req.getAttribute("quack.requestId"));
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("type", "https://tools.ietf.org/html/rfc9110#section-15.5.1");
        corpo.put("title", "One or more validation errors occurred.");
        corpo.put("status", 400);
        corpo.put("errors", erros);
        corpo.put("traceId", "00-" + id + "-" + id.substring(0, Math.min(16, id.length())) + "-00");
        return ResponseEntity.badRequest().contentType(PROBLEMA).body(corpo);
    }
}
