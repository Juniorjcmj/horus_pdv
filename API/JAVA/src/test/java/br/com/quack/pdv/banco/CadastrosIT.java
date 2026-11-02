package br.com.quack.pdv.banco;
import br.com.quack.pdv.BancoIT;
import br.com.quack.pdv.contrato.EstadoDoBanco;
import br.com.quack.pdv.cadastro.fornecedor.repo.FornecedorRepository;
import br.com.quack.pdv.cadastro.categoria.repo.CategoriaRepository;
import br.com.quack.pdv.cadastro.cliente.app.ClienteTransacao;
import br.com.quack.pdv.empresa.repo.GerenciamentoEmpresasRepository;
import br.com.quack.pdv.empresa.app.GerenciamentoEmpresasService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.io.ClassPathResource;
import static org.assertj.core.api.Assertions.*;
/** SQL Server tests use a dedicated database so seed/reset cannot change phase-1 fixtures. */
@SpringBootTest @ActiveProfiles("it")
class CadastrosIT {
    static {BancoIT.criarBanco("cadastros");}
    @DynamicPropertySource static void propriedades(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->BancoIT.urlDoBanco("cadastros"));r.add("spring.datasource.username",BancoIT.SQL::getUsername);r.add("spring.datasource.password",BancoIT.SQL::getPassword);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired FornecedorRepository fornecedores;
    @Autowired CategoriaRepository categorias;
    @Autowired ClienteTransacao clientes;
    @Autowired GerenciamentoEmpresasRepository admin;
    @Autowired GerenciamentoEmpresasService gerenciamento;
    @Autowired br.com.quack.pdv.cadastro.cliente.app.ClienteService clienteService;
    @Autowired br.com.quack.pdv.sessao.repo.SessaoRepository sessoes;
    @BeforeEach void seed() {EstadoDoBanco.por(BancoIT.urlDoBanco("cadastros"),BancoIT.SQL.getUsername(),BancoIT.SQL.getPassword()).restaurar();}
    @Test void seedResetRepetidosNaoDuplicam() {seed();seed();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM Clientes WHERE CompanyId='emp-contrato-aprovada'",Integer.class)).isEqualTo(2);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM Produtos WHERE CompanyId='emp-contrato-aprovada'",Integer.class)).isEqualTo(2);}
    @Test void referenciaV3ReaplicadaNaoDuplica() throws Exception {
        long categoriasAntes=jdbc.queryForObject("SELECT COUNT(*) FROM Categorias",Long.class),secoesAntes=jdbc.queryForObject("SELECT COUNT(*) FROM TreinamentoSecoes",Long.class),videosAntes=jdbc.queryForObject("SELECT COUNT(*) FROM TreinamentoVideos",Long.class);
        String sql=new ClassPathResource("db/migration/V3__cadastros_dados_referencia.sql").getContentAsString(StandardCharsets.UTF_8);jdbc.execute(sql);jdbc.execute(sql);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM Categorias",Long.class)).isEqualTo(categoriasAntes);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM TreinamentoSecoes",Long.class)).isEqualTo(secoesAntes);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM TreinamentoVideos",Long.class)).isEqualTo(videosAntes);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM Categorias WHERE CompanyId='empresa-principal' AND Id NOT LIKE 'cat-ct-%'",Integer.class)).isEqualTo(55);assertThat(videosAntes).isEqualTo(9);
    }
    @Test void fornecedorExisteCaseInsensitiveEIsolado() {assertThat(fornecedores.produtosVinculados("empresa-principal","distribuidora sol","razao nao usada")).isEqualTo(1);assertThat(fornecedores.produtosVinculados("sem-produtos","Distribuidora Sol","Sol SA")).isZero();}
    @Test void categoriaContaProdutosDaEmpresa() {assertThat(categorias.obter("empresa-principal","cat-ct-sub").orElseThrow().getQuantidadeProdutos()).isEqualTo(1);assertThat(categorias.obter("emp-contrato-aprovada","cat-ct-sub").orElseThrow().getQuantidadeProdutos()).isZero();assertThat(categorias.obter("empresa-principal","cat-ct-sub-outra")).isEmpty();}
    @Test void snapshotUsaOpenJsonEIsolaEmpresa() {var s=clientes.snapshot("empresa-principal",List.of("client-sale-ct","client-sale-ct-outra","desconhecida"));assertThat(s.customers()).hasSize(2);assertThat(s.acceptedClientSaleIds()).containsExactly("client-sale-ct");}
    @Test void consultaDelegadaECompromissosNaMesmaTransacao() {var r=new br.com.quack.pdv.cadastro.cliente.web.ClienteRequest();r.customerName="Devedora";r.telephone="11999990000";r.limiteCredito=new java.math.BigDecimal("60");assertThatThrownBy(()->clientes.atualizar("empresa-principal","cl-ct-devedor",r,true)).hasMessage("Recolha e reconcilie os orçamentos antes de reduzir o limite abaixo dos compromissos.");assertThat(jdbc.queryForObject("SELECT LimiteCredito FROM Clientes WHERE Id='cl-ct-devedor'",java.math.BigDecimal.class)).isEqualByComparingTo("100");}
    @Test void adminOrdenaPrincipalPendentesENovas() {var lista=admin.listar(null,null,1,100);assertThat(lista.items().getFirst().id).isEqualTo("empresa-principal");assertThat(lista.items().get(1).id).isEqualTo("emp-contrato-pendente");assertThat(lista.totalCount()).isEqualTo(4);}
    @Test void exclusaoRecusaPrincipal() {assertThat(admin.excluir("empresa-principal")).isFalse();}
    @Test void exclusaoRemoveSoAlvoSemDadosForaDaLista() {
        // The literal .NET batch intentionally does not delete GatewayCreditGrants.
        assertThat(admin.excluir("emp-contrato-aprovada")).isTrue();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ModuloMercadoRegistros WHERE CompanyId='emp-contrato-aprovada'",Integer.class)).isZero();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM Empresas WHERE Id='emp-contrato-aprovada'",Integer.class)).isZero();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM Usuarios WHERE CompanyId='emp-contrato-aprovada'",Integer.class)).isZero();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM Clientes WHERE CompanyId='empresa-principal'",Integer.class)).isEqualTo(2);
    }
    private void sessao(String id,String usuario) {
        var sessao=new br.com.quack.pdv.sessao.domain.Sessao();sessao.setId(id);sessao.setUserId(usuario);sessao.setCreatedAt(java.time.OffsetDateTime.now());sessoes.saveAndFlush(sessao);
    }
    @Test void credenciaisReaisCiframSenhaERevogamSomenteUsuarioAlvo() {
        sessao("sess-ct-aprovada","usr-ct-apr-adm");sessao("sess-ct-principal","usr-ct-admin");
        var resultado=admin.credenciais("emp-contrato-aprovada"," NOVO@CONTRATO.QUACK ","NovaSenha#2026");assertThat(resultado.success()).isTrue();
        String hash=jdbc.queryForObject("SELECT PasswordHash FROM Usuarios WHERE Id='usr-ct-apr-adm'",String.class);
        assertThat(br.com.quack.pdv.security.PasswordHasher.verify("NovaSenha#2026",hash)).isTrue();assertThat(jdbc.queryForObject("SELECT Email FROM Usuarios WHERE Id='usr-ct-apr-adm'",String.class)).isEqualTo("novo@contrato.quack");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM Sessoes WHERE Id='sess-ct-aprovada'",Integer.class)).isZero();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM Sessoes WHERE Id='sess-ct-principal'",Integer.class)).isEqualTo(1);
    }
    @Test void bloquearEmpresaRevogaSessaoNaMesmaTransacao() {
        sessao("sess-ct-aprovada","usr-ct-apr-adm");assertThat(admin.situacao("bloquear","emp-contrato-aprovada","Revisor"," Motivo ")).isTrue();
        assertThat(jdbc.queryForObject("SELECT Status FROM Empresas WHERE Id='emp-contrato-aprovada'",String.class)).isEqualTo("bloqueada");assertThat(jdbc.queryForObject("SELECT RejectionReason FROM Empresas WHERE Id='emp-contrato-aprovada'",String.class)).isEqualTo("Motivo");assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM Sessoes WHERE Id='sess-ct-aprovada'",Integer.class)).isZero();
    }
    @Test void falhaAuditoriaEmTransacaoRealNaoDesfazCliente() {
        jdbc.execute("CREATE TRIGGER CT_FalharAuditoria ON AuditLog INSTEAD OF INSERT AS THROW 51001, 'Auditoria forcada pelo IT', 1;");
        try {
            var usuario=new br.com.quack.pdv.security.UsuarioAutenticado("usr-ct-admin","empresa-principal","s","Admin","admin@contrato.quack","administrador",List.of());
            var r=new br.com.quack.pdv.cadastro.cliente.web.ClienteRequest();r.customerName="Cliente Confirmado";r.telephone="11999998888";
            var salvo=clienteService.criar(usuario,r);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM Clientes WHERE Id=?",Integer.class,salvo.id)).isEqualTo(1);
        } finally {jdbc.execute("DROP TRIGGER CT_FalharAuditoria");}
    }
    @Test void idDaRotaNaoPodeSerTruncadoParaEmpresaExistente() {
        String prefixo = "e".repeat(40);
        jdbc.update("INSERT INTO Empresas (Id,FantasyName,CorporateName,Cnpj) VALUES (?,?,?,?)",prefixo,"Prefixo","Prefixo","00000000000000");
        assertThat(admin.excluir(prefixo+"-outro-id")).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM Empresas WHERE Id=?",Integer.class,prefixo)).isEqualTo(1);
        jdbc.update("DELETE FROM Empresas WHERE Id=?",prefixo);
    }
    @Test void falhaNoMeioDaExclusaoDesfazTudo() {
        jdbc.execute("CREATE TRIGGER CT_FalharExclusao ON Empresas INSTEAD OF DELETE AS THROW 51000, 'Falha forcada pelo IT', 1;");
        try{assertThatThrownBy(()->gerenciamento.excluir("emp-contrato-aprovada")).hasMessage("Erro interno ao excluir empresa.");assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM Clientes WHERE CompanyId='emp-contrato-aprovada'",Integer.class)).isEqualTo(2);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM Usuarios WHERE CompanyId='emp-contrato-aprovada'",Integer.class)).isEqualTo(1);}finally{jdbc.execute("DROP TRIGGER CT_FalharExclusao");}
    }
}
