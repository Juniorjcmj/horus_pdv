package br.com.quack.pdv.venda.registro.app;

import br.com.quack.pdv.fiscal.fila.repo.DocumentoFiscalConsulta;
import br.com.quack.pdv.security.UsuarioAutenticado;
import br.com.quack.pdv.shared.NegocioException;
import br.com.quack.pdv.venda.caixa.app.CaixaService;
import br.com.quack.pdv.venda.caixa.app.CaixaTempo;
import br.com.quack.pdv.venda.registro.domain.VendaDetalheCompleto;
import br.com.quack.pdv.venda.registro.domain.VendaHistoricoDto;
import br.com.quack.pdv.venda.registro.repo.VendaRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Historico, detalhe e impressao de vendas (HistoricoVendasController: Listar, ObterDetalhes, Imprimir). */
@Service
public class VendaConsultaService {

    private static final DateTimeFormatter DATA_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    private final VendaRepository repo;
    private final CaixaService caixa;
    private final DocumentoFiscalConsulta documentos;
    private final Clock clock;

    public VendaConsultaService(VendaRepository repo, CaixaService caixa, DocumentoFiscalConsulta documentos,
                                Clock clock) {
        this.repo = repo;
        this.caixa = caixa;
        this.documentos = documentos;
        this.clock = clock;
    }

    /** Lista as vendas; com turno, so o proprio (gerente e administrador veem qualquer um). */
    public List<VendaHistoricoDto> listar(UsuarioAutenticado usuario, String desde, String caixaSessaoId) {
        OffsetDateTime dtDesde = desde == null || desde.isBlank() ? null : CaixaTempo.lerDataHora(desde).orElse(null);
        if (caixaSessaoId != null && !caixaSessaoId.isBlank()
                && caixa.sessao(usuario, caixaSessaoId.trim()).isEmpty()) {
            throw new NegocioException(HttpStatus.FORBIDDEN,
                    "Turno de caixa não encontrado ou pertencente a outro operador.");
        }
        return repo.listar(usuario.companyId(), null, dtDesde, caixaSessaoId);
    }

    public Optional<VendaDetalheCompleto> detalheCompleto(String companyId, String saleNumber) {
        List<VendaHistoricoDto> rows = repo.listar(companyId, saleNumber, null, null);
        if (rows.isEmpty()) return Optional.empty();
        VendaHistoricoDto first = rows.getFirst();
        String vendaId = repo.vendaIdPorNumero(companyId, saleNumber);
        var pagamentos = vendaId.isEmpty() ? List.<br.com.quack.pdv.venda.registro.domain.VendaPagamentoDto>of()
                : repo.pagamentos(companyId, vendaId);
        return Optional.of(new VendaDetalheCompleto(vendaId, first.saleNumber(), first.status(),
                first.customerName(), first.customerCpf(), first.paymentType(), first.totalAmount(),
                first.operatorName(), first.saleDate(), first.clientSaleId(), first.offlineReference(), rows,
                pagamentos));
    }

    /** Corpo de data do detalhe (ObterDetalhes). */
    public Optional<Map<String, Object>> detalhe(String companyId, String saleNumber) {
        return detalheCompleto(companyId, saleNumber).map(v -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("vendaId", v.vendaId());
            m.put("saleNumber", v.saleNumber());
            m.put("customerName", v.customerName());
            m.put("customerCpf", v.customerCpf());
            m.put("paymentType", v.paymentType());
            m.put("totalAmount", v.totalAmount());
            m.put("operatorName", v.operatorName());
            m.put("saleDate", v.saleDate());
            m.put("clientSaleId", v.clientSaleId());
            m.put("offlineReference", v.offlineReference());
            m.put("items", v.items());
            m.put("payments", v.payments());
            m.put("documentoFiscal", documentos.obterDetalhePorCodigo(companyId, saleNumber).orElse(null));
            return m;
        });
    }

    /** Corpo de data da impressao (DateTime.Now local do servidor, como o .NET). */
    public Optional<Map<String, Object>> imprimir(String companyId, String saleNumber) {
        List<VendaHistoricoDto> rows = repo.listar(companyId, saleNumber, null, null);
        if (rows.isEmpty()) return Optional.empty();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("saleNumber", saleNumber);
        m.put("printedAt", LocalDateTime.now(clock.withZone(ZoneId.systemDefault())).format(DATA_HORA));
        m.put("items", rows.size());
        m.put("rows", rows);
        m.put("payments", detalheCompleto(companyId, saleNumber).map(VendaDetalheCompleto::payments).orElse(null));
        return Optional.of(m);
    }
}
