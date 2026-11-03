package br.com.quack.pdv.promocao.promocao.web;

import br.com.quack.pdv.shared.GlobalExceptionHandler;

import com.fasterxml.jackson.databind.exc.InvalidNullException;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Binding dos campos não anuláveis do DTO ASP.NET; escopo limitado a promoções. */
@RestControllerAdvice(assignableTypes = PromocaoController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PromocaoBindingAdvice {
    private final GlobalExceptionHandler padrao;

    public PromocaoBindingAdvice(GlobalExceptionHandler padrao) {
        this.padrao = padrao;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> invalido(
            HttpMessageNotReadableException ex, HttpServletRequest request) {
        Throwable causa = ex.getMostSpecificCause();
        if (!(causa instanceof InvalidNullException nulo)) return padrao.corpoInvalido(ex, request);
        String campo = nulo.getPropertyName().getSimpleName();
        Map<String, Object> erros = new LinkedHashMap<>();
        if (campo.equals("produtoIds"))
            erros.put("ProdutoIds", List.of("The ProdutoIds field is required."));
        else if (campo.equals("inicioVigencia") || campo.equals("fimVigencia")) {
            erros.put("request", List.of("The request field is required."));
            String caminho = "$." + campo;
            // InvalidNullException aponta ao início do literal ASCII null; o
            // System.Text.Json aponta após seus 4 bytes, dentro da mesma linha.
            long posicao = nulo.getLocation().getColumnNr() - 1L + 4;
            erros.put(
                    caminho,
                    List.of(
                            "The JSON value could not be converted to System.DateTimeOffset. Path: "
                                    + caminho
                                    + " | LineNumber: "
                                    + (nulo.getLocation().getLineNr() - 1)
                                    + " | BytePositionInLine: "
                                    + posicao
                                    + "."));
        } else return padrao.corpoInvalido(ex, request);
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("type", "https://tools.ietf.org/html/rfc9110#section-15.5.1");
        corpo.put("title", "One or more validation errors occurred.");
        corpo.put("status", 400);
        corpo.put("errors", erros);
        String id = String.valueOf(request.getAttribute("quack.requestId"));
        corpo.put("traceId", "00-" + id + "-" + id.substring(0, 16) + "-00");
        return ResponseEntity.badRequest()
                .contentType(MediaType.parseMediaType("application/problem+json"))
                .body(corpo);
    }
}
