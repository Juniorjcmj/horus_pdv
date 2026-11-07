package br.com.quack.pdv.plataforma.sincronizacao.integracao;

import static br.com.quack.pdv.plataforma.sincronizacao.integracao.AmbienteSync.EMPRESA;
import static br.com.quack.pdv.plataforma.sincronizacao.integracao.AmbienteSync.esperar;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.quack.pdv.plataforma.sincronizacao.carga.CargaInicialLoja;
import br.com.quack.pdv.plataforma.sincronizacao.carga.CargaPagina;
import br.com.quack.pdv.plataforma.sincronizacao.tracking.ChangeTrackingAdmin;
import br.com.quack.pdv.security.SecretProtector;
import br.com.quack.pdv.venda.registro.app.VendaRegistroService;
import br.com.quack.pdv.venda.registro.domain.VendaRegistroResultado;
import br.com.quack.pdv.venda.registro.web.VendaRequests;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Carga inicial e ativacao (tarefa 3.2): a nuvem tem o seed; a loja e um banco novo com chaves proprias. Os testes
 * dependem da ordem (a carga acontece uma vez): queda e retomada, delta, segredos recifrados, autenticacao local e o
 * ciclo "venda na loja chega a nuvem".
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CargaInicialIT {

    static final String SENHA_CERTIFICADO = "senha-do-certificado-123";
    static final String SENHA_ADMIN = "Senha#Quack2026";
    static final String OUTRA_EMPRESA = "empresa-ct-fora";
    static final ObjectMapper JSON = new ObjectMapper();

    static AmbienteSync amb;
    static JdbcTemplate loja;
    static JdbcTemplate nuvem;
    static CargaInicialLoja carga;
    static CargaInicialLoja.Parametros parametros;
    static String ultimoUsuarioAntesDaQueda;
    static int paginasDaRetomada;
    static String tabelaDaQueda;
    static List<String> cursorDaQueda;

    @BeforeAll
    static void subir() {
        amb = AmbienteSync.iniciarParaCarga("carga");
        loja = amb.jdbcLoja;
        nuvem = amb.jdbcNuvem;
        carga = amb.loja.getBean(CargaInicialLoja.class);
        carga.definirLimitePagina(3);
        SecretProtector cifraNuvem = amb.nuvem.getBean(SecretProtector.class);
        // o seed traz segredos cifrados com a chave do ambiente .NET; aqui valem os da chave da nuvem do teste
        nuvem.update("UPDATE Empresas SET CertificadoSenhaCifrada = ?, CscCifrado = ?, EmailSmtpPassword = ? WHERE Id = ?",
                cifraNuvem.protect(SENHA_CERTIFICADO), cifraNuvem.protect("csc-secreto"), cifraNuvem.protect("smtp-secreta"), EMPRESA);
        parametros = new CargaInicialLoja.Parametros(amb.urlBaseNuvem(), "admin@contrato.quack", SENHA_ADMIN, "Loja IT");
        // produto da empresa ligado a categoria de OUTRA empresa (como as categorias padrao na nuvem real), com mae
        // tambem de fora; e uma categoria de fora que ninguem da empresa usa
        nuvem.update("INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES "
                + "('cat-ct-fora-pai', ?, N'Fora pai', NULL, 1, 1), ('cat-ct-fora-filha', ?, N'Fora filha', 'cat-ct-fora-pai', 1, 1), "
                + "('cat-ct-fora-solta', ?, N'Fora solta', NULL, 2, 1)", OUTRA_EMPRESA, OUTRA_EMPRESA, OUTRA_EMPRESA);
        nuvem.update("UPDATE Produtos SET CategoriaId = 'cat-ct-fora-filha' WHERE CompanyId = ? AND ProductCode = 'CT001'", EMPRESA);
    }

    @AfterAll
    static void descer() {
        if (amb != null) amb.close();
    }

    // ------------------------------------------------------------------ cenarios

    @Test
    @Order(1)
    void quedaNoMeioDaCargaEntraEmPartesPeloProgressoSemAtivar() {
        AtomicInteger paginas = new AtomicInteger();
        carga.aoGravarPagina(p -> {
            // cai numa pagina que nao fecha a tabela: sobra exatamente uma tabela em andamento
            if (paginas.incrementAndGet() >= 3 && !p.fim()) throw new IllegalStateException("queda simulada");
        });
        assertThatThrownBy(() -> carga.executar(parametros)).hasMessageContaining("queda simulada");

        assertThat(contar(loja, "SELECT COUNT(*) FROM SyncEstado WHERE Ativada = 1")).isZero();
        assertThat(contar(loja, "SELECT COUNT(*) FROM SyncCarga WHERE Concluida = 0")).as("tabela interrompida").isEqualTo(1);
        assertThat(contar(loja, "SELECT COUNT(*) FROM SyncCarga WHERE Concluida = 1")).isGreaterThanOrEqualTo(1);
        assertThat(new ChangeTrackingAdmin(loja).tabelasComTracking()).as("tracking so na ativacao").isEmpty();
        assertThat(contar(nuvem, "SELECT COUNT(*) FROM SyncServidores WHERE Status = 'pendente'")).isEqualTo(1);
        tabelaDaQueda = loja.queryForObject("SELECT Tabela FROM SyncCarga WHERE Concluida = 0", String.class);
        cursorDaQueda = lerCursor(loja.queryForObject("SELECT UltimaChave FROM SyncCarga WHERE Concluida = 0", String.class));
        assertThat(cursorDaQueda).isNotEmpty();
        // o servidor da loja guarda o token cifrado com a chave DELA
        String token = loja.queryForObject("SELECT TokenCifrado FROM SyncEstado WHERE Id = 1", String.class);
        assertThat(new SecretProtector(AmbienteSync.CIFRA_LOJA, AmbienteSync.JWT_LOJA).unprotect(token)).isNotBlank();
    }

    @Test
    @Order(2)
    void retomadaContinuaDeOndeParouProdutoAlteradoNaNuvemChegaAntesDaAtivacao() {
        AtomicInteger paginas = new AtomicInteger();
        List<String> primeiraPagina = new ArrayList<>();
        boolean[] mexeu = {false};
        carga.aoGravarPagina((CargaPagina p) -> {
            if (paginas.incrementAndGet() == 1) {
                primeiraPagina.add(p.tabela());
                primeiraPagina.addAll(p.linhas().stream().map(l -> l.get(0)).toList());
            }
            // depois que Produtos foi inteiro para a loja, a nuvem muda: so o delta pode trazer isso
            if (!mexeu[0] && p.tabela().equals("Produtos") && p.fim()) {
                mexeu[0] = true;
                nuvem.update("UPDATE Produtos SET ProductSalePrice = 9.99 WHERE CompanyId = ? AND ProductCode = 'CT001'", EMPRESA);
                nuvem.update("DELETE FROM Produtos WHERE Id = 'pr-ct-excluir'");
            }
        });

        CargaInicialLoja.Resultado r = carga.executar(parametros);
        paginasDaRetomada = paginas.get();

        assertThat(primeiraPagina.getFirst()).as("retoma pela tabela interrompida").isEqualTo(tabelaDaQueda);
        assertThat(primeiraPagina.subList(1, primeiraPagina.size())).as("sem repetir a chave ja gravada")
                .doesNotContain(cursorDaQueda.getFirst());
        assertThat(mexeu[0]).isTrue();
        assertThat(loja.queryForObject("SELECT ProductSalePrice FROM Produtos WHERE ProductCode = 'CT001'", BigDecimal.class))
                .isEqualByComparingTo("9.99");
        assertThat(contar(loja, "SELECT COUNT(*) FROM Produtos WHERE Id = 'pr-ct-excluir'")).as("exclusao via delta").isZero();

        assertThat(r.companyId()).isEqualTo(EMPRESA);
        assertThat(loja.queryForList("SELECT Id FROM Categorias WHERE CompanyId = ? ORDER BY Id", String.class, OUTRA_EMPRESA))
                .as("categorias de fora: so as usadas pela empresa e a mae delas").containsExactly("cat-ct-fora-filha", "cat-ct-fora-pai");
        assertThat(loja.queryForObject("SELECT CategoriaId FROM Produtos WHERE ProductCode = 'CT001'", String.class))
                .isEqualTo("cat-ct-fora-filha");
        assertThat(amb.diferencas(true)).as("contagens e linhas iguais, sem duplicar").isEmpty();
        assertThat(contar(loja, "SELECT COUNT(*) FROM Vendas")).isEqualTo(contar(nuvem, "SELECT COUNT(*) FROM Vendas WHERE CompanyId = ?", EMPRESA));
        assertThat(contar(loja, "SELECT COUNT(*) FROM VendaItens")).isPositive();
    }

    @Test
    @Order(3)
    void ativacaoCriaEstadoDasDuasPontasELigaOTrackingDaLoja() {
        Long versao = loja.queryForObject("SELECT CHANGE_TRACKING_CURRENT_VERSION()", Long.class);
        assertThat(loja.queryForObject("SELECT Ativada FROM SyncEstado WHERE Id = 1", Boolean.class)).isTrue();
        assertThat(loja.queryForObject("SELECT VersaoDe FROM SyncEstado WHERE Id = 1", Long.class)).isEqualTo(versao);
        assertThat(loja.queryForObject("SELECT AplicadoAteNuvem FROM SyncEstado WHERE Id = 1", Long.class)).isEqualTo(versao);
        assertThat(new ChangeTrackingAdmin(loja).tabelasComTracking())
                .containsAll(ChangeTrackingAdmin.tabelasEsperadas());
        assertThat(nuvem.queryForObject("SELECT AplicadoAte FROM SyncEstadoNuvem WHERE CompanyId = ?", Long.class, EMPRESA))
                .isEqualTo(versao);
        assertThat(nuvem.queryForObject("SELECT DetectorDesde FROM SyncEstadoNuvem WHERE CompanyId = ?", Long.class, EMPRESA))
                .isNotNull();
        assertThat(nuvem.queryForObject("SELECT Status FROM SyncServidores WHERE CompanyId = ? AND Status <> 'revogado'",
                String.class, EMPRESA)).isEqualTo("ativo");
        assertThat(nuvem.queryForObject("SELECT Status FROM SyncCargas WHERE CompanyId = ?", String.class, EMPRESA))
                .isEqualTo("ativada");
        assertThatThrownBy(() -> carga.executar(parametros)).hasMessageContaining("ja foi ativada");
    }

    @Test
    @Order(4)
    void senhaDoCertificadoDecifraComAChaveDaLojaEDifereDaNuvem() {
        String naLoja = loja.queryForObject("SELECT CertificadoSenhaCifrada FROM Empresas WHERE Id = ?", String.class, EMPRESA);
        String naNuvem = nuvem.queryForObject("SELECT CertificadoSenhaCifrada FROM Empresas WHERE Id = ?", String.class, EMPRESA);
        SecretProtector cifraLoja = amb.loja.getBean(SecretProtector.class);
        SecretProtector cifraNuvem = amb.nuvem.getBean(SecretProtector.class);

        assertThat(naLoja).startsWith("enc:v1:").isNotEqualTo(naNuvem);
        assertThat(cifraLoja.unprotect(naLoja)).isEqualTo(SENHA_CERTIFICADO);
        assertThat(cifraNuvem.unprotect(naNuvem)).isEqualTo(SENHA_CERTIFICADO);
        assertThatThrownBy(() -> cifraNuvem.unprotect(naLoja)).as("a chave da nuvem nao abre o segredo da loja")
                .isInstanceOf(IllegalStateException.class);
        assertThat(cifraLoja.unprotect(loja.queryForObject("SELECT CscCifrado FROM Empresas WHERE Id = ?", String.class, EMPRESA)))
                .isEqualTo("csc-secreto");
    }

    @Test
    @Order(5)
    void tokenDeSessaoDaNuvemERecusadoNaLojaELoginUsaASenhaDaNuvem() throws Exception {
        String tokenNuvem = amb.tokenNuvem("usr-ct-admin", EMPRESA, "Ana Administradora", "admin@contrato.quack", "administrador");
        HttpResponse<String> recusado = amb.http.send(HttpRequest.newBuilder(URI.create(amb.urlBaseLoja() + "/api/loja-sync/retomar"))
                .header("Authorization", "Bearer " + tokenNuvem).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(recusado.statusCode()).isEqualTo(401);

        HttpResponse<String> login = amb.http.send(HttpRequest.newBuilder(URI.create(amb.urlBaseLoja() + "/api/Auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"admin@contrato.quack\",\"password\":\"" + SENHA_ADMIN + "\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).as(login.body()).isEqualTo(200);
        String cookie = login.headers().allValues("Set-Cookie").stream().filter(c -> c.startsWith("horuspdv.auth="))
                .map(c -> c.substring(0, c.indexOf(';'))).findFirst().orElseThrow();
        HttpResponse<String> aceito = amb.http.send(HttpRequest.newBuilder(URI.create(amb.urlBaseLoja() + "/api/loja-sync/retomar"))
                .header("Cookie", cookie).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(aceito.statusCode()).as("o token da loja vale na loja").isEqualTo(200);
    }

    @Test
    @Order(6)
    void vendaNaLojaDepoisDaAtivacaoChegaANuvemEOEstadoConverge() {
        amb.esperarConvergencia(60_000);
        VendaRegistroResultado r = vender("CT002", "3,00");
        esperar("venda na nuvem", 60_000, () -> contar(nuvem, "SELECT COUNT(*) FROM Vendas WHERE Id = ?", r.vendaId()) == 1);
        esperar("itens e movimentacao na nuvem", 30_000, () ->
                contar(nuvem, "SELECT COUNT(*) FROM VendaItens WHERE VendaId = ?", r.vendaId()) == 1
                        && contar(nuvem, "SELECT COUNT(*) FROM EstoqueMovimentacoes WHERE OrigemId = ?", r.saleNumber()) == 1);
        amb.esperarConvergencia(60_000);
        assertThat(amb.diferencas(true)).isEmpty();
    }

    // ------------------------------------------------------------------ apoio

    static VendaRegistroResultado vender(String codigo, String total) {
        VendaRequests.Item i = new VendaRequests.Item();
        i.productCode = codigo;
        i.productName = "";
        i.quantity = BigDecimal.ONE;
        i.setUnitPrice(new BigDecimal("3"));
        VendaRequests.Pagamento p = new VendaRequests.Pagamento();
        p.paymentType = "dinheiro";
        p.amount = new BigDecimal(total.replace(',', '.'));
        VendaRequests.Venda v = new VendaRequests.Venda();
        v.customerName = "Consumidor";
        v.paymentType = "dinheiro";
        v.totalAmount = total;
        v.operatorName = "Cai Caixa";
        v.items = new ArrayList<>(List.of(i));
        v.payments = new ArrayList<>(List.of(p));
        return amb.loja.getBean(VendaRegistroService.class).registrar(EMPRESA, v, null);
    }

    static int contar(JdbcTemplate j, String sql, Object... args) {
        Integer n = j.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    @SuppressWarnings("unchecked")
    static List<String> lerCursor(String json) {
        try {
            return JSON.readValue(json, List.class);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
