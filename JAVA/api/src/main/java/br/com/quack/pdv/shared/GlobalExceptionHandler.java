package br.com.quack.pdv.shared;

import br.com.quack.pdv.config.QuackProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Traduz excecoes no mesmo formato de erro da API .NET. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final MediaType PROBLEM_JSON = MediaType.parseMediaType("application/problem+json");

    private final QuackProperties props;

    public GlobalExceptionHandler(QuackProperties props) {
        this.props = props;
    }

    @ExceptionHandler(NegocioException.class)
    public ResponseEntity<ApiResponse<Object>> negocio(NegocioException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(new ApiResponse<>(false, ex.getMessage(), ex.getDetails(), ex.getData()));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    public ResponseEntity<Map<String, Object>> corpoInvalido(Exception ex, HttpServletRequest request) {
        // Mesmo formato do ASP.NET: "$" com a causa do parser e "request" porque o corpo nao virou o objeto esperado.
        Map<String, Object> erros = new LinkedHashMap<>();
        if (ex instanceof HttpMessageNotReadableException) {
            erros.put("$", List.of(causaDoParser(ex)));
            erros.put("request", List.of("The request field is required."));
        } else {
            String campo = ex instanceof MethodArgumentTypeMismatchException m ? m.getName() : "request";
            erros.put(campo, List.of("The value provided is not valid."));
        }
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("type", "https://tools.ietf.org/html/rfc9110#section-15.5.1");
        corpo.put("title", "One or more validation errors occurred.");
        corpo.put("status", 400);
        corpo.put("errors", erros);
        Object requestId = request.getAttribute("quack.requestId");
        String hex = requestId != null ? requestId.toString() : UUID.randomUUID().toString().replace("-", "");
        corpo.put("traceId", "00-" + hex + "-" + hex.substring(0, 16) + "-00");
        return ResponseEntity.badRequest().contentType(PROBLEM_JSON).body(corpo);
    }

    private static String causaDoParser(Exception ex) {
        Throwable causa = ex.getCause() != null ? ex.getCause() : ex;
        String msg = causa.getMessage() == null ? "" : causa.getMessage();
        int quebra = msg.indexOf('\n');
        return quebra > 0 ? msg.substring(0, quebra) : msg;
    }

    /** Upload acima do limite do multipart (importacao de produtos): 400 com mensagem clara. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Object>> uploadGrande() {
        return ResponseEntity.badRequest().body(ApiResponse.erro("Arquivo maior que o permitido (15 MB)."));
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiResponse<Object>> parteAusente(MissingServletRequestPartException ex) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.erro("Envie o arquivo no campo '" + ex.getRequestPartName() + "'."));
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<Void> naoEncontrado() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Void> metodoNaoPermitido() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Void> tipoNaoSuportado() {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).build();
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> inesperado(Exception ex) {
        if (props.isDev()) {
            LOG.error("Erro nao tratado", ex);
        } else {
            LOG.error("Erro nao tratado: {}", ex.toString());
        }
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("success", false);
        corpo.put("message", "Erro interno no servidor.");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(corpo);
    }
}
