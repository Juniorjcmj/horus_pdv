package br.com.quack.pdv.empresa;
import br.com.quack.pdv.empresa.app.AdministradorGeral;
import br.com.quack.pdv.security.UsuarioAutenticado;
import br.com.quack.pdv.shared.Dinheiro;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class BaseCadastrosTest {
    private final AdministradorGeral geral = new AdministradorGeral();
    private UsuarioAutenticado usuario(String empresa, String perfil, String email) { return new UsuarioAutenticado("u",empresa,"s","Nome",email,perfil,List.of()); }
    @Test void administradorDaPrincipalGerencia() { var u=usuario(" EMPRESA-PRINCIPAL "," Administrador ","x"); assertThat(geral.ehAdministradorGeral(u)).isTrue(); assertThat(geral.podeGerenciarTreinamento(u)).isTrue(); }
    @Test void gerenteDaPrincipalNaoGerencia() { var u=usuario("empresa-principal","gerente","jotacfs2010@hotmail.com"); assertThat(geral.ehAdministradorGeral(u)).isFalse(); assertThat(geral.podeGerenciarTreinamento(u)).isFalse(); }
    @Test void administradorDaLojaNaoGerencia() { var u=usuario("loja","administrador","loja@x"); assertThat(geral.ehAdministradorGeral(u)).isFalse(); assertThat(geral.podeGerenciarTreinamento(u)).isFalse(); }
    @Test void donoDaLojaGerenciaSomenteTreinamento() { var u=usuario("loja","administrador"," JOTACFS2010@HOTMAIL.COM "); assertThat(geral.ehAdministradorGeral(u)).isFalse(); assertThat(geral.podeGerenciarTreinamento(u)).isTrue(); }
    @Test void anonimoNaoGerencia() { assertThat(geral.ehAdministradorGeral(null)).isFalse(); assertThat(geral.podeGerenciarTreinamento(null)).isFalse(); }
    @Test void dinheiroPtBr() { assertThat(Dinheiro.formatar(new BigDecimal("1234.56"))).isEqualTo("R$ 1.234,56"); assertThat(Dinheiro.formatar(new BigDecimal("10"))).isEqualTo("R$ 10,00"); assertThat(Dinheiro.formatar(BigDecimal.ZERO)).isEqualTo("R$ 0,00"); assertThat(Dinheiro.formatar(new BigDecimal("-1234.56"))).isEqualTo("R$ -1.234,56"); }
}
