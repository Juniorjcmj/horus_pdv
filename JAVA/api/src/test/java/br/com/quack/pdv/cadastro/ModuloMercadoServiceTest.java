package br.com.quack.pdv.cadastro;
import br.com.quack.pdv.cadastro.mercado.app.ModuloMercadoService;
import br.com.quack.pdv.cadastro.mercado.domain.ModuloMercadoRegistro;
import br.com.quack.pdv.cadastro.mercado.repo.*;
import br.com.quack.pdv.cadastro.mercado.web.RegistroRequest;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class ModuloMercadoServiceTest {
    private final ModuloMercadoRepository modulos=mock(ModuloMercadoRepository.class);
    private final ModuloMercadoRegistroRepository registros=mock(ModuloMercadoRegistroRepository.class);
    private final ModuloMercadoService service=new ModuloMercadoService(modulos,registros,Clock.systemUTC());
    private ModuloMercadoRegistro registro(String status) {var r=new ModuloMercadoRegistro();r.status=status;return r;}
    @ParameterizedTest @ValueSource(strings={"fiscal","pagamentos","estoque","caixa","compras","devolucoes","crm-fidelidade","omnichannel"}) void moduloVazio(String id) {var painel=service.obter("loja",id);assertThat(painel.kpis().get(2).value()).isEqualTo("0%");assertThat(painel.kpis().get(3).value()).isEqualTo("1");assertThat(painel.alerts().getFirst()).isEqualTo("Nenhuma pendência crítica no momento.");}
    @Test void meioArredondaParaPar() {var lista=new ArrayList<ModuloMercadoRegistro>();lista.add(registro("CONCLUIDO"));for(int i=0;i<7;i++)lista.add(registro("Em andamento"));when(registros.findByCompanyIdAndModuleIdOrderById("loja","compras")).thenReturn(lista);assertThat(service.obter("loja","compras").kpis().get(2).value()).isEqualTo("12%");}
    @Test void pendenciasContamTermos() {when(registros.findByCompanyIdAndModuleIdOrderById("loja","compras")).thenReturn(List.of(registro("Pendente"),registro("AGUARDANDO"),registro("Concluido"),registro("Em andamento")));var p=service.obter("loja","compras");assertThat(p.kpis().get(0).value()).isEqualTo("4");assertThat(p.kpis().get(1).value()).isEqualTo("2");assertThat(p.kpis().get(2).value()).isEqualTo("25%");assertThat(p.alerts().getFirst()).isEqualTo("Existem 2 pendência(s) aguardando conferência.");}
    @Test void moduloDesconhecido() {assertThatThrownBy(()->service.obter("loja","financeiro")).hasMessage("Módulo não encontrado.");verifyNoInteractions(registros);}
    @Test void tituloCurto() {var r=new RegistroRequest();r.title="ab";assertThatThrownBy(()->service.criar("loja","caixa",r)).hasMessage("Titulo deve ter no minimo 3 caracteres.");verify(registros,never()).saveAndFlush(any());}
    @Test void statusObrigatorio() {var r=new RegistroRequest();r.title="Titulo";assertThatThrownBy(()->service.criar("loja","caixa",r)).hasMessage("Status e obrigatorio.");}
    @Test void registroDeOutraEmpresaNaoAltera() {var r=new RegistroRequest();r.title="Titulo";r.status="Pendente";assertThatThrownBy(()->service.atualizar("loja","caixa","outro",r)).hasMessage("Registro não encontrado.");verify(registros,never()).saveAndFlush(any());}
    @Test void criarGaranteModulo() {var r=new RegistroRequest();r.title="Titulo";r.status="Ativo";service.criar("loja","caixa",r);verify(modulos).garantir("caixa","Abertura e Fechamento de Caixa");var cap=org.mockito.ArgumentCaptor.forClass(ModuloMercadoRegistro.class);verify(registros).saveAndFlush(cap.capture());assertThat(cap.getValue().companyId).isEqualTo("loja");assertThat(cap.getValue().id).startsWith("mm-");}
}
