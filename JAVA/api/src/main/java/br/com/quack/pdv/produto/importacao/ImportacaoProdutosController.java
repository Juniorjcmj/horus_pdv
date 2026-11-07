package br.com.quack.pdv.produto.importacao;

import br.com.quack.pdv.security.AutenticacaoFilter;
import br.com.quack.pdv.security.PerfisPermitidos;
import br.com.quack.pdv.security.UsuarioAutenticado;
import br.com.quack.pdv.shared.ApiResponse;
import br.com.quack.pdv.shared.NegocioException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Importacao de produtos por planilha: simular (nao grava) e confirmar (grava tudo ou nada). */
@RestController
@RequestMapping({"api/Produto/importacao", "api/produtos/importacao"})
@PerfisPermitidos({"administrador", "gerente"})
public class ImportacaoProdutosController {

    private final ImportacaoProdutosService servico;

    public ImportacaoProdutosController(ImportacaoProdutosService servico) {
        this.servico = servico;
    }

    @PostMapping(value = "simular", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<RelatorioImportacao> simular(
            @RequestParam("arquivo") MultipartFile arquivo, HttpServletRequest req) {
        RelatorioImportacao r = servico.simular(atual(req).companyId(), bytes(arquivo));
        return ApiResponse.ok("Simulação concluída. Nada foi gravado.", r);
    }

    @PostMapping(value = "confirmar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<RelatorioImportacao> confirmar(
            @RequestParam("arquivo") MultipartFile arquivo, HttpServletRequest req) {
        UsuarioAutenticado u = atual(req);
        RelatorioImportacao r = servico.confirmar(u.companyId(), bytes(arquivo), u.id(), u.name());
        return ApiResponse.ok("Importação concluída.", r);
    }

    private static byte[] bytes(MultipartFile arquivo) {
        try {
            return arquivo.getBytes();
        } catch (IOException e) {
            throw new NegocioException(HttpStatus.BAD_REQUEST, "Não foi possível ler o arquivo enviado.");
        }
    }

    private static UsuarioAutenticado atual(HttpServletRequest req) {
        return (UsuarioAutenticado) req.getAttribute(AutenticacaoFilter.ATRIBUTO_USUARIO);
    }
}
