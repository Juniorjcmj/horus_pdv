package br.com.quack.pdv.cadastro.mercado.app;
import br.com.quack.pdv.cadastro.mercado.domain.ModuloMercadoRegistro;
import br.com.quack.pdv.cadastro.mercado.repo.*;
import br.com.quack.pdv.cadastro.mercado.web.RegistroRequest;
import br.com.quack.pdv.shared.NegocioException;
import java.time.Clock;
import java.math.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
@Service
public class ModuloMercadoService {
    public record Registro(String id,String moduleId,String title,String description,String status,String amount,String meta) {}
    public record Kpi(String label,String value,String hint,String tone) {}
    public record Painel(String id,String title,String description,String primaryAction,String statusLabel,String statusValue,List<Kpi> kpis,String recordsTitle,List<Registro> records,String workflowTitle,List<String> workflow,List<String> alerts) {}
    private static final Map<String,String> TITULOS=Map.ofEntries(Map.entry("fiscal","Fiscal NFC-e / NF-e"),Map.entry("pagamentos","Pagamentos Integrados"),Map.entry("estoque","Estoque e Inventário"),Map.entry("caixa","Abertura e Fechamento de Caixa"),Map.entry("compras","Compras e Reposição"),Map.entry("devolucoes","Trocas e Devoluções"),Map.entry("crm-fidelidade","CRM e Fidelidade"),Map.entry("omnichannel","Omnichannel e Integrações"));
    private static final Map<String,String> ACOES=Map.ofEntries(Map.entry("fiscal","Nova NFC-e"),Map.entry("pagamentos","Nova cobrança"),Map.entry("estoque","Ajustar estoque"),Map.entry("caixa","Abrir caixa"),Map.entry("compras","Novo pedido"),Map.entry("devolucoes","Nova devolução"),Map.entry("crm-fidelidade","Nova campanha"),Map.entry("omnichannel","Conectar canal"));
    private final ModuloMercadoRepository modulos;private final ModuloMercadoRegistroRepository registros;private final Clock clock;
    public ModuloMercadoService(ModuloMercadoRepository modulos,ModuloMercadoRegistroRepository registros,Clock clock) {this.modulos=modulos;this.registros=registros;this.clock=clock;}
    public Painel obter(String companyId,String id) {
        String title=titulo(id);
        var rows=registros.findByCompanyIdAndModuleIdOrderById(companyId,id).stream().map(e->new Registro(e.id,e.moduleId,e.title,e.description,e.status,e.amount,e.meta)).toList();
        int pendentes=(int)rows.stream().filter(r->contem(r.status(),"pend","aguard")).count();
        int concluidos=(int)rows.stream().filter(r->contem(r.status(),"conclu","audit","fech","ativo")).count();
        int taxa=rows.isEmpty()?0:BigDecimal.valueOf(concluidos*100L).divide(BigDecimal.valueOf(rows.size()),0,RoundingMode.HALF_EVEN).intValue();
        return new Painel(id,title,"Gestão operacional do módulo "+title+".",ACOES.get(id),"Status do módulo","Operação ativa",
            List.of(new Kpi("Volume",""+rows.size(),"Movimentações registradas","secondary"),new Kpi("Pendências",""+pendentes,"Aguardando ação","accent"),new Kpi("Concluídos",taxa+"%","Fluxos processados","success"),new Kpi("Alertas",""+Math.max(1,pendentes),"Pontos de atenção","primary")),
            "Registros operacionais",rows,"Fluxo operacional",List.of("Consultar os dados atualizados do módulo.","Validar informações e regras operacionais.","Executar ação operacional no frontend.","Registrar movimentação e acompanhar status."),
            List.of(pendentes>0?"Existem "+pendentes+" pendência(s) aguardando conferência.":"Nenhuma pendência crítica no momento.","Revise os registros com prioridade operacional.","Acompanhe os indicadores antes do fechamento do período."));
    }
    public Painel criar(String companyId,String id,RegistroRequest r) {
        String title=titulo(id);modulos.garantir(id,title);validar(r);var e=new ModuloMercadoRegistro();e.id="mm-"+clock.millis();e.companyId=companyId;e.moduleId=id;copiar(r,e);registros.saveAndFlush(e);return obter(companyId,id);
    }
    @Transactional public Painel atualizar(String companyId,String id,String recordId,RegistroRequest r) {titulo(id);validar(r);var e=registro(companyId,id,recordId);copiar(r,e);registros.saveAndFlush(e);return obter(companyId,id);}
    @Transactional public Painel excluir(String companyId,String id,String recordId) {titulo(id);registros.delete(registro(companyId,id,recordId));registros.flush();return obter(companyId,id);}
    private ModuloMercadoRegistro registro(String companyId,String id,String recordId) {return registros.findByCompanyIdAndModuleIdAndId(companyId,id,recordId).orElseThrow(()->new NegocioException(HttpStatus.NOT_FOUND,"Registro não encontrado."));}
    private static String titulo(String id) {String t=TITULOS.get(id);if(t==null)throw new NegocioException(HttpStatus.NOT_FOUND,"Módulo não encontrado.");return t;}
    private static boolean contem(String v,String... termos) {String s=v.toLowerCase(Locale.ROOT);return Arrays.stream(termos).anyMatch(s::contains);}
    private static void validar(RegistroRequest r) {if(r.title==null||r.title.trim().length()<3)throw new NegocioException(HttpStatus.BAD_REQUEST,"Titulo deve ter no minimo 3 caracteres.");if(r.status==null||r.status.isBlank())throw new NegocioException(HttpStatus.BAD_REQUEST,"Status e obrigatorio.");}
    private static void copiar(RegistroRequest r,ModuloMercadoRegistro e) {e.title=r.title.trim();e.description=r.description.trim();e.status=r.status.trim();e.amount=r.amount.trim();e.meta=r.meta.trim();}
}
