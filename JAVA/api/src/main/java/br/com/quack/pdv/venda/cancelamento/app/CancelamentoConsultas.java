package br.com.quack.pdv.venda.cancelamento.app;

import br.com.quack.pdv.venda.caixa.app.CaixaTempo;
import br.com.quack.pdv.venda.cancelamento.domain.TrilhaLinha;
import br.com.quack.pdv.venda.cancelamento.domain.VendaCanceladaResumo;
import br.com.quack.pdv.venda.cancelamento.repo.CancelamentoRepository;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Trilha completa e lista de cancelamentos. Filtros como no .NET: {@code de}/{@code ate} invalidos sao ignorados e
 * {@code ate} soma um dia (D12: o filtro de fim exclui o proprio dia informado so depois de 24 h).
 */
@Service
public class CancelamentoConsultas {

    private final CancelamentoRepository repo;

    public CancelamentoConsultas(CancelamentoRepository repo) {
        this.repo = repo;
    }

    public List<TrilhaLinha> trilha(String companyId, String de, String ate, String venda) {
        String numero = venda == null || venda.isBlank() ? null : venda.strip();
        return repo.listarTrilha(companyId, inicio(de), fim(ate), numero);
    }

    public List<VendaCanceladaResumo> cancelamentos(String companyId, String de, String ate) {
        return repo.listarCancelamentos(companyId, inicio(de), fim(ate));
    }

    private static OffsetDateTime inicio(String de) {
        return de == null || de.isBlank() ? null : CaixaTempo.lerDataHora(de).orElse(null);
    }

    private static OffsetDateTime fim(String ate) {
        OffsetDateTime d = ate == null || ate.isBlank() ? null : CaixaTempo.lerDataHora(ate).orElse(null);
        return d == null ? null : d.plusDays(1);
    }
}
