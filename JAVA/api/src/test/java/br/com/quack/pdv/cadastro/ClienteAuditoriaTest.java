package br.com.quack.pdv.cadastro;
import br.com.quack.pdv.cadastro.cliente.app.*;
import br.com.quack.pdv.cadastro.cliente.web.*;
import br.com.quack.pdv.auditoria.app.AuditoriaService;
import br.com.quack.pdv.security.UsuarioAutenticado;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class ClienteAuditoriaTest {
    private final ClienteTransacao tx=mock(ClienteTransacao.class);private final AuditoriaService audit=mock(AuditoriaService.class);private final ClienteService service=new ClienteService(tx,audit);
    private final UsuarioAutenticado u=new UsuarioAutenticado("u","loja","s","Nome","u@x","atendente",List.of());
    @Test void limiteAuditaAntesEDepoisComoDotnet() {var r=new ClienteRequest();r.limiteCredito=new BigDecimal("1234.56");var d=new ClienteDto();d.customerName="Novo";when(tx.atualizar("loja","cl",r,true)).thenReturn(new ClienteTransacao.Alteracao(d,"Anterior",new BigDecimal("100"),BigDecimal.ZERO));service.atualizar(u,"cl",r);verify(audit).registrar("loja","u","Nome","FiadoLimiteAlterado","Alterou limite de crédito de Anterior: 100,00 → 1.234,56.","Cliente","cl",null);verify(audit).registrar("loja","u","Nome","ClienteAtualizado","Atualizou cadastro do cliente Novo.","Cliente","cl",null);}
    @Test void falhaAuditoriaNaoQuebraResultadoConfirmado() {var r=new ClienteRequest();var d=new ClienteDto();d.id="cl";d.customerName="Cliente";when(tx.criar("loja",r)).thenReturn(new ClienteTransacao.Alteracao(d,null,BigDecimal.ZERO,BigDecimal.ZERO));doThrow(new RuntimeException("audit fora" )).when(audit).registrar(any(),any(),any(),any(),any(),any(),any(),any());assertThat(service.criar(u,r)).isSameAs(d);var ordem=inOrder(tx,audit);ordem.verify(tx).criar("loja",r);ordem.verify(audit).registrar(eq("loja"),any(),any(),eq("ClienteCriado"),eq("Cadastrou cliente Cliente."),any(),any(),any());}
}
