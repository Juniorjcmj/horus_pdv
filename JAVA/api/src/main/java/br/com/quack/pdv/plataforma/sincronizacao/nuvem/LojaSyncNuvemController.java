package br.com.quack.pdv.plataforma.sincronizacao.nuvem;

import br.com.quack.pdv.config.QuackProperties;
import br.com.quack.pdv.shared.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Rotas de maquina da nuvem (D9): autenticadas pelo {@link ServidorLojaFilter}. */
@RestController
@RequestMapping("api/loja-sync")
@Profile("nuvem")
public class LojaSyncNuvemController {

    /** Corpo de resposta de {@code POST lotes}. */
    public record RespostaLote(long aplicadoAte, boolean aplicado) {}

    private final ReceptorLotes receptor;
    private final QuackProperties props;

    public LojaSyncNuvemController(@org.springframework.context.annotation.Lazy ReceptorLotes receptor, QuackProperties props) {
        this.receptor = receptor;
        this.props = props;
    }

    @PostMapping("lotes")
    public ResponseEntity<ApiResponse<RespostaLote>> lotes(@RequestBody byte[] corpo, HttpServletRequest req) {
        if (!(req.getAttribute(ServidorLoja.ATRIBUTO) instanceof ServidorLoja servidor)) {
            return ResponseEntity.status(401).body(ApiResponse.erro("Token do servidor invalido ou revogado."));
        }
        if (corpo.length > props.getSync().getMaxLoteBytes()) {
            return ResponseEntity.status(413).body(ApiResponse.erro("Lote maior que o permitido."));
        }
        ReceptorLotes.Resposta r = receptor.receber(servidor, corpo);
        RespostaLote dados = new RespostaLote(r.aplicadoAte(), r.aplicado());
        if (r.status() == 200) return ResponseEntity.ok(ApiResponse.ok(r.mensagem(), dados));
        return ResponseEntity.status(r.status()).body(ApiResponse.erro(r.mensagem(), null, dados));
    }
}
