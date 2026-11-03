package br.com.quack.pdv.empresa.web;
import br.com.quack.pdv.empresa.app.AdministradorGeral;
import br.com.quack.pdv.security.*;
import br.com.quack.pdv.shared.NegocioException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import org.springdoc.webmvc.api.OpenApiWebMvcResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("api/Admin/Empresas/documentacao-api")
public class ApiDocumentationController {
    private final OpenApiWebMvcResource documentos;private final ObjectMapper json;private final AdministradorGeral geral;
    public ApiDocumentationController(OpenApiWebMvcResource documentos,ObjectMapper json,AdministradorGeral geral) {this.documentos=documentos;this.json=json;this.geral=geral;}
    @GetMapping public ResponseEntity<byte[]> download(HttpServletRequest request) throws Exception {
        var usuario=(UsuarioAutenticado)request.getAttribute(AutenticacaoFilter.ATRIBUTO_USUARIO);
        if(!geral.ehAdministradorGeral(usuario))throw new NegocioException(HttpStatus.FORBIDDEN,"Documentação da API restrita ao Administrador Geral da Plataforma.");
        String caminho=request.getRequestURI();
        byte[] original=documentos.openapiJson(request,caminho,Locale.ROOT);
        ObjectNode documento=(ObjectNode)json.readTree(original);
        String servidor=request.getScheme()+"://"+request.getServerName();
        int porta=request.getServerPort();if(!(request.getScheme().equals("https")&&porta==443)&&!(request.getScheme().equals("http")&&porta==80))servidor+=":"+porta;
        servidor+=request.getContextPath();
        documento.putArray("servers").addObject().put("url",servidor);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/json; charset=utf-8"))
            .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=quack-pdv-api-openapi.json; filename*=UTF-8''quack-pdv-api-openapi.json")
            .header(HttpHeaders.CACHE_CONTROL,"no-store, no-cache").body(json.writeValueAsBytes(documento));
    }
}
