package br.com.quack.pdv.produto.entrada.web;

import br.com.quack.pdv.produto.entrada.app.*;
import br.com.quack.pdv.security.*;
import br.com.quack.pdv.shared.*;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("api/NfeImport")
@PerfisPermitidos({"administrador", "gerente", "atendente", "estoquista"})
public class NfeImportController {
    private final NfeImportService service;
    private final NotaEntradaArquivoService arquivo;

    public NfeImportController(NfeImportService service, NotaEntradaArquivoService arquivo) {
        this.service = service;
        this.arquivo = arquivo;
    }

    private UsuarioAutenticado atual(HttpServletRequest req) {
        return (UsuarioAutenticado) req.getAttribute(AutenticacaoFilter.ATRIBUTO_USUARIO);
    }

    @PostMapping("preview")
    public ApiResponse<?> preview(
            @RequestBody NfeImportRequests.PreviewRequest r, HttpServletRequest req) {
        return ApiResponse.ok("XML lido com sucesso.", service.preview(atual(req).companyId(), r));
    }

    @PostMapping("confirmar")
    public ApiResponse<?> confirmar(
            @RequestBody NfeImportRequests.ConfirmRequest r, HttpServletRequest req) {
        var u = atual(req);
        return ApiResponse.ok(
                "Importação concluída com sucesso.",
                service.confirmar(u.companyId(), r, u.id(), u.name()));
    }

    @PostMapping("buscar-sefaz")
    public ApiResponse<?> sefaz(@RequestBody NfeImportRequests.ChaveRequest r) {
        NfeAccessKey.validateDistributionKey(r.chaveAcesso);
        throw new NegocioException(
                HttpStatus.BAD_REQUEST,
                "A busca da nota na SEFAZ ainda não está disponível nesta versão. Importe a nota"
                    + " pelo arquivo XML.");
    }

    @GetMapping("notas-entrada")
    public ApiResponse<?> listar(
            @RequestParam(required = false) String busca,
            @RequestParam(defaultValue = "1") int pagina,
            @RequestParam(defaultValue = "20") int tamanhoPagina,
            HttpServletRequest req) {
        return ApiResponse.ok(
                "", arquivo.listar(atual(req).companyId(), busca, pagina, tamanhoPagina));
    }

    @GetMapping("notas-entrada/{id}")
    public ResponseEntity<?> obter(@PathVariable String id, HttpServletRequest req) {
        return arquivo.obter(atual(req).companyId(), id)
                .<ResponseEntity<?>>map(n -> ResponseEntity.ok(ApiResponse.ok("", n)))
                .orElseGet(() -> problema(req, 404, null));
    }

    @GetMapping("notas-entrada/{id}/xml")
    public ResponseEntity<?> xml(
            @PathVariable String id, HttpServletRequest req, HttpServletResponse resp) {
        byte[] xml = arquivo.xml(atual(req).companyId(), id);
        return xml == null
                ? ResponseEntity.status(404)
                        .body(Map.of("message", "XML não disponível para esta entrada."))
                : download(xml, "application/xml", "nota-entrada-" + id + ".xml", resp);
    }

    @GetMapping("notas-entrada/{id}/comprovante")
    public ResponseEntity<?> comprovante(
            @PathVariable String id, HttpServletRequest req, HttpServletResponse resp) {
        return arquivo.comprovante(atual(req).companyId(), id)
                .<ResponseEntity<?>>map(a -> download(a.bytes(), a.tipo(), a.nome(), resp))
                .orElseGet(
                        () ->
                                ResponseEntity.status(404)
                                        .body(
                                                Map.of(
                                                        "message",
                                                        "Comprovante não disponível para esta"
                                                            + " entrada.")));
    }

    @DeleteMapping("notas-entrada/{id}")
    @PerfisPermitidos({"administrador", "gerente"})
    public ResponseEntity<?> estornar(
            @PathVariable String id,
            @RequestParam(required = false) String justificativa,
            HttpServletRequest req) {
        if (justificativa == null) return problema(req, 400, "justificativa");
        var u = atual(req);
        service.estornar(u.companyId(), id, u.id(), u.name(), justificativa);
        return ResponseEntity.ok(
                ApiResponse.ok("Nota de entrada estornada com sucesso. O estoque foi ajustado."));
    }

    @GetMapping("notas-entrada/exportar")
    @PerfisPermitidos({"administrador", "gerente"})
    public ResponseEntity<?> exportar(
            @RequestParam(defaultValue = "0") int ano,
            @RequestParam(defaultValue = "0") int mes,
            HttpServletRequest req,
            HttpServletResponse resp) {
        return download(
                arquivo.exportar(atual(req).companyId(), ano, mes),
                "application/zip",
                String.format("notas-entrada-%d-%02d.zip", ano, mes),
                resp);
    }

    @PostMapping("notas-entrada/{id}/xml")
    @PerfisPermitidos({"administrador", "gerente"})
    public ApiResponse<?> anexar(
            @PathVariable String id,
            @RequestBody NfeImportRequests.PreviewRequest r,
            HttpServletRequest req) {
        arquivo.anexar(atual(req).companyId(), id, r.xmlBase64);
        return ApiResponse.ok("XML vinculado à entrada. O estoque não foi alterado.");
    }

    private ResponseEntity<byte[]> download(
            byte[] bytes, String tipo, String nome, HttpServletResponse resp) {
        resp.setCharacterEncoding(null);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(tipo))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename="
                                + nome
                                + "; filename*=UTF-8''"
                                + java.net.URLEncoder.encode(
                                                nome, java.nio.charset.StandardCharsets.UTF_8)
                                        .replace("+", "%20"))
                .body(bytes);
    }

    private ResponseEntity<?> problema(HttpServletRequest req, int status, String campo) {
        String id = String.valueOf(req.getAttribute("quack.requestId"));
        Map<String, Object> corpo = new java.util.LinkedHashMap<>();
        corpo.put(
                "type",
                "https://tools.ietf.org/html/rfc9110#section-15.5." + (status == 404 ? "5" : "1"));
        corpo.put("title", status == 404 ? "Not Found" : "One or more validation errors occurred.");
        corpo.put("status", status);
        if (campo != null)
            corpo.put(
                    "errors",
                    Map.of(campo, java.util.List.of("The " + campo + " field is required.")));
        corpo.put("traceId", "00-" + id + "-" + id.substring(0, 16) + "-00");
        return ResponseEntity.status(status)
                .contentType(MediaType.parseMediaType("application/problem+json"))
                .body(corpo);
    }
}
