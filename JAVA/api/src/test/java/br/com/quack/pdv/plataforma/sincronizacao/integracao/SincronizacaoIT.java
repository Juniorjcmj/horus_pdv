package br.com.quack.pdv.plataforma.sincronizacao.integracao;

import static br.com.quack.pdv.plataforma.sincronizacao.integracao.AmbienteSync.EMPRESA;
import static br.com.quack.pdv.plataforma.sincronizacao.integracao.AmbienteSync.esperar;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.quack.pdv.fiscal.fila.app.PendenciaFiscal;
import br.com.quack.pdv.plataforma.sincronizacao.envio.ClienteNuvemHttp;
import br.com.quack.pdv.plataforma.sincronizacao.protocolo.BlocoTabela;
import br.com.quack.pdv.plataforma.sincronizacao.protocolo.ColunaSync;
import br.com.quack.pdv.plataforma.sincronizacao.protocolo.ParteLote;
import br.com.quack.pdv.plataforma.sincronizacao.protocolo.SerializadorLote;
import br.com.quack.pdv.plataforma.sincronizacao.protocolo.TipoColuna;
import br.com.quack.pdv.venda.registro.app.VendaRegistroService;
import br.com.quack.pdv.venda.registro.domain.VendaRegistroResultado;
import br.com.quack.pdv.venda.registro.web.VendaRequests;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sincronizacao loja -> nuvem de ponta a ponta (tarefa 2.3): dois SQL Server, dois contextos Spring (loja e nuvem), os
 * cenarios da spec sincronizacao-loja-nuvem um a um. Os testes dependem da ordem (estado compartilhado).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SincronizacaoIT {

    static AmbienteSync amb;
    static JdbcTemplate loja;
    static JdbcTemplate nuvem;
    static volatile VendaRegistroService vendas;
    static ClienteNuvemHttp cliente;

    @BeforeAll
    static void subir() {
        amb = AmbienteSync.iniciar("a");
        loja = amb.jdbcLoja;
        nuvem = amb.jdbcNuvem;
        vendas = amb.loja.getBean(VendaRegistroService.class);
        cliente = amb.cliente();
        loja.update("UPDATE Produtos SET ProductQnt = 1000 WHERE CompanyId = ? AND ProductCode IN ('CT001','CT002')", EMPRESA);
        amb.esperarConvergencia(60_000);
    }

    @AfterAll
    static void descer() {
        if (amb != null) amb.close();
    }

    // ------------------------------------------------------------------ helpers

    static VendaRegistroResultado vender(String codigo, String qtd, String preco, String total) {
        VendaRequests.Item i = new VendaRequests.Item();
        i.productCode = codigo;
        i.productName = "";
        i.quantity = new BigDecimal(qtd);
        i.setUnitPrice(new BigDecimal(preco));
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
        return vendas.registrar(EMPRESA, v, null);
    }

    /** O Caixa real repete a venda quando o banco devolve deadlock/timeout de lock (7 caixas na mesma numeracao). */
    static void venderComRetentativa(String produto) {
        for (int tentativa = 1; ; tentativa++) {
            try {
                vender(produto, "1", "3", "3,00");
                return;
            } catch (org.springframework.dao.TransientDataAccessException e) {
                if (tentativa >= 8) throw e;
            }
        }
    }

    static int contar(JdbcTemplate j, String sql, Object... args) {
        Integer n = j.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    static boolean existeNaNuvem(String tabela, String id) {
        return contar(nuvem, "SELECT COUNT(*) FROM dbo.[" + tabela + "] WHERE Id = ?", id) > 0;
    }

    static long aplicadoAteNuvem() {
        return nuvem.queryForObject("SELECT AplicadoAte FROM SyncEstadoNuvem WHERE CompanyId = ?", Long.class, EMPRESA);
    }

    static ParteLote parteCategorias(String lote, long de, long ate, int numero, boolean ultima, String idCategoria, String empresa) {
        List<ColunaSync> cols = List.of(new ColunaSync("Id", TipoColuna.TEXTO), new ColunaSync("CompanyId", TipoColuna.TEXTO),
                new ColunaSync("Nome", TipoColuna.TEXTO), new ColunaSync("Ordem", TipoColuna.INTEIRO),
                new ColunaSync("Ativa", TipoColuna.BOOLEANO));
        BlocoTabela b = new BlocoTabela("Categorias", cols, List.of(List.of(idCategoria, empresa, "Parte " + numero, "50", "1")),
                List.of(), List.of());
        return new ParteLote(lote, empresa, de, ate, false, numero, ultima, List.of(b));
    }

    // ------------------------------------------------------------------ cenarios

    @Test
    @Order(1)
    void vendaCompletaChegaEmMenosDeUmMinutoEFicaIgual() {
        long inicio = System.currentTimeMillis();
        VendaRegistroResultado r = vender("CT001", "1", "3", "3,00");
        esperar("venda na nuvem", 60_000, () -> existeNaNuvem("Vendas", r.vendaId()));
        assertThat(System.currentTimeMillis() - inicio).isLessThan(60_000);
        esperar("itens, pagamentos, movimentacao e saldo", 30_000, () ->
                contar(nuvem, "SELECT COUNT(*) FROM VendaItens WHERE VendaId = ?", r.vendaId()) == 1
                        && contar(nuvem, "SELECT COUNT(*) FROM VendaPagamentos WHERE VendaId = ?", r.vendaId()) == 1
                        && contar(nuvem, "SELECT COUNT(*) FROM EstoqueMovimentacoes WHERE OrigemId = ?", r.saleNumber()) == 1);
        amb.esperarConvergencia(30_000);
        BigDecimal naLoja = loja.queryForObject("SELECT ProductQnt FROM Produtos WHERE CompanyId = ? AND ProductCode = 'CT001'", BigDecimal.class, EMPRESA);
        BigDecimal naNuvem = nuvem.queryForObject("SELECT ProductQnt FROM Produtos WHERE CompanyId = ? AND ProductCode = 'CT001'", BigDecimal.class, EMPRESA);
        assertThat(naNuvem).isEqualTo(naLoja);
        assertThat(amb.diferencas()).isEmpty();
    }

    @Test
    @Order(2)
    void exclusaoChegaANuvem() {
        loja.update("INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES ('cat-it-1', ?, 'IT', NULL, 99, 1)", EMPRESA);
        esperar("categoria criada na nuvem", 30_000, () -> existeNaNuvem("Categorias", "cat-it-1"));
        loja.update("DELETE FROM Categorias WHERE Id = 'cat-it-1'");
        esperar("categoria excluida na nuvem", 30_000, () -> !existeNaNuvem("Categorias", "cat-it-1"));
    }

    @Test
    @Order(3)
    void vendaENotaNaMesmaTransacaoNuncaChegamSeparadas() throws Exception {
        PendenciaFiscal pendencia = amb.loja.getBean(PendenciaFiscal.class);
        TransactionTemplate tx = new TransactionTemplate(amb.loja.getBean(PlatformTransactionManager.class));
        AtomicBoolean rodando = new AtomicBoolean(true);
        AtomicInteger violacoes = new AtomicInteger();
        Thread sonda = new Thread(() -> {
            try (Connection c = DriverManager.getConnection(amb.urlNuvem(), AmbienteSync.SQL_NUVEM.getUsername(), AmbienteSync.SQL_NUVEM.getPassword())) {
                c.setTransactionIsolation(4096);
                while (rodando.get()) {
                    try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(
                            "SELECT COUNT(*) FROM Vendas v WHERE v.EmitirNfce = 1 AND NOT EXISTS "
                                    + "(SELECT 1 FROM DocumentosFiscais d WHERE d.VendaId = v.Id)")) {
                        rs.next();
                        violacoes.addAndGet(rs.getInt(1));
                    }
                    Thread.sleep(20);
                }
            } catch (Exception e) {
                violacoes.addAndGet(1000);
            }
        });
        sonda.start();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            VendaRegistroResultado r = vender("CT002", "1", "3", "3,00");
            ids.add(r.vendaId());
            tx.executeWithoutResult(s -> {
                loja.update("UPDATE Vendas SET EmitirNfce = 1 WHERE CompanyId = ? AND Id = ?", EMPRESA, r.vendaId());
                pendencia.enfileirar(EMPRESA, r.vendaId());
            });
        }
        amb.esperarConvergencia(60_000);
        rodando.set(false);
        sonda.join();
        assertThat(violacoes.get()).as("venda com EmitirNfce sem nota vista na nuvem").isZero();
        for (String id : ids) {
            assertThat(contar(nuvem, "SELECT COUNT(*) FROM DocumentosFiscais WHERE VendaId = ?", id)).isEqualTo(1);
        }
        assertThat(amb.diferencas()).isEmpty();
    }

    @Test
    @Order(4)
    void respostaPerdidaEReenvioSemEfeito() {
        String loteAntes = nuvem.queryForObject("SELECT UltimoLoteId FROM SyncEstadoNuvem WHERE CompanyId = ?", String.class, EMPRESA);
        cliente.perderProximasRespostas(1);
        VendaRegistroResultado r = vender("CT001", "1", "3", "3,00");
        esperar("lote aplicado na nuvem", 30_000, () -> existeNaNuvem("Vendas", r.vendaId()));
        esperar("loja viu a falha", 30_000, () -> contar(loja, "SELECT TentativasSeguidas FROM SyncEstado WHERE Id = 1") >= 1);
        String loteAplicado = nuvem.queryForObject("SELECT UltimoLoteId FROM SyncEstadoNuvem WHERE CompanyId = ?", String.class, EMPRESA);
        assertThat(loteAplicado).isNotEqualTo(loteAntes);

        amb.zerarBackoff();
        amb.esperarConvergencia(60_000);
        // o reenvio foi confirmado sem aplicar de novo: o ultimo lote aplicado continua o mesmo e nada duplicou
        assertThat(nuvem.queryForObject("SELECT UltimoLoteId FROM SyncEstadoNuvem WHERE CompanyId = ?", String.class, EMPRESA))
                .isEqualTo(loteAplicado);
        assertThat(contar(nuvem, "SELECT COUNT(*) FROM Vendas WHERE Id = ?", r.vendaId())).isEqualTo(1);
        assertThat(contar(nuvem, "SELECT COUNT(*) FROM VendaItens WHERE VendaId = ?", r.vendaId())).isEqualTo(1);
        assertThat(amb.diferencas()).isEmpty();
    }

    @Test
    @Order(5)
    void auditLogSincronizadoDuasVezesViraUmaLinha() {
        for (int i = 1; i <= 3; i++) {
            loja.update("INSERT INTO AuditLog (CompanyId, OccurredAt, UserId, UserName, EventType, EntityType, EntityId, Description, Ip) "
                    + "VALUES (?, SYSDATETIMEOFFSET(), 'usr-ct-admin', 'Ana', 'TesteSync', 'Teste', ?, ?, NULL)",
                    EMPRESA, "e" + i, "auditoria de teste " + i);
        }
        esperar("auditoria na nuvem", 30_000, () ->
                contar(nuvem, "SELECT COUNT(*) FROM AuditLog WHERE EventType = 'TesteSync'") == 3);
        amb.esperarConvergencia(30_000);
        Long idLoja = loja.queryForObject("SELECT TOP 1 Id FROM AuditLog WHERE CompanyId = ? ORDER BY Id DESC", Long.class, EMPRESA);
        int antes = contar(nuvem, "SELECT COUNT(*) FROM AuditLog WHERE CompanyId = ?", EMPRESA);
        int mapaAntes = contar(nuvem, "SELECT COUNT(*) FROM SyncAuditoriaMapa WHERE CompanyId = ?", EMPRESA);
        assertThat(mapaAntes).isEqualTo(antes);
        assertThat(contar(nuvem, "SELECT COUNT(*) FROM SyncAuditoriaMapa WHERE CompanyId = ? AND IdLoja = ?", EMPRESA, idLoja)).isEqualTo(1);

        loja.update("INSERT INTO SyncReenvios (Tabela, Chave, Motivo) VALUES ('AuditLog', ?, 'it')", String.valueOf(idLoja));
        esperar("reenvio consumido", 30_000, () -> contar(loja, "SELECT COUNT(*) FROM SyncReenvios") == 0);
        assertThat(contar(nuvem, "SELECT COUNT(*) FROM AuditLog WHERE CompanyId = ?", EMPRESA)).isEqualTo(antes);
        assertThat(contar(nuvem, "SELECT COUNT(*) FROM SyncAuditoriaMapa WHERE CompanyId = ?", EMPRESA)).isEqualTo(mapaAntes);
        assertThat(amb.diferencas()).isEmpty();
    }

    @Test
    @Order(6)
    void classesDeColunaValemNoUpdate() {
        nuvem.update("UPDATE Usuarios SET LastLoginAt = 'NUVEM-ONTEM' WHERE Id = 'usr-ct-atend'");
        String smtpNuvem = nuvem.queryForObject("SELECT EmailSmtpPassword FROM Empresas WHERE Id = ?", String.class, EMPRESA);
        loja.update("UPDATE Usuarios SET Name = 'Ari Novo', LastLoginAt = 'LOJA-HOJE' WHERE Id = 'usr-ct-atend'");
        loja.update("UPDATE Empresas SET Website = 'https://novo.it', EmailSmtpPassword = 'enc:v1:segredo-da-loja' WHERE Id = ?", EMPRESA);
        esperar("usuario e empresa atualizados", 30_000, () ->
                "Ari Novo".equals(nuvem.queryForObject("SELECT Name FROM Usuarios WHERE Id = 'usr-ct-atend'", String.class))
                        && "https://novo.it".equals(nuvem.queryForObject("SELECT Website FROM Empresas WHERE Id = ?", String.class, EMPRESA)));
        amb.esperarConvergencia(30_000);
        assertThat(nuvem.queryForObject("SELECT LastLoginAt FROM Usuarios WHERE Id = 'usr-ct-atend'", String.class))
                .as("LOCAL nao e sobrescrita no UPDATE").isEqualTo("NUVEM-ONTEM");
        assertThat(nuvem.queryForObject("SELECT EmailSmtpPassword FROM Empresas WHERE Id = ?", String.class, EMPRESA))
                .as("CIFRADA nao e escrita pelo lote").isEqualTo(smtpNuvem);
        assertThat(amb.diferencas()).isEmpty();
    }

    @Test
    @Order(7)
    void filtroDoTokenDoServidor() throws Exception {
        long ate = aplicadoAteNuvem();
        byte[] corpo = SerializadorLote.paraGzip(parteCategorias("l-token", ate, ate, 1, true, "cat-tok", EMPRESA));

        assertThat(amb.postLote(null, corpo).statusCode()).isEqualTo(401);
        assertThat(amb.postLote("LojaServidor nao-existe", corpo).statusCode()).isEqualTo(401);
        assertThat(amb.postLote("Bearer " + amb.token, corpo).statusCode()).isEqualTo(401);

        nuvem.update("UPDATE SyncServidores SET Status = 'revogado' WHERE Id = 'srv-it'");
        assertThat(amb.postLote("LojaServidor " + amb.token, corpo).statusCode()).as("revogado").isEqualTo(401);
        nuvem.update("UPDATE SyncServidores SET Status = 'ativo' WHERE Id = 'srv-it'");

        amb.registrarServidor("srv-outra", "emp-contrato-aprovada", "tok-outra", "ativo");
        assertThat(amb.postLote("LojaServidor tok-outra", corpo).statusCode()).as("token de outra empresa").isEqualTo(403);
        assertThat(existeNaNuvem("Categorias", "cat-tok")).isFalse();
        assertThat(aplicadoAteNuvem()).isEqualTo(ate);

        HttpResponse<String> grande = amb.postLote("LojaServidor " + amb.token, new byte[17 * 1024 * 1024]);
        assertThat(grande.statusCode()).as("limite de 16 MB so nessa rota").isEqualTo(413);
    }

    @Test
    @Order(8)
    void loteEmTresPartesSoAplicaNaUltima() throws Exception {
        long ate = aplicadoAteNuvem();
        String auth = "LojaServidor " + amb.token;
        assertThat(amb.postLote(auth, SerializadorLote.paraGzip(parteCategorias("l-3p", ate, ate, 1, false, "cat-p1", EMPRESA))).statusCode()).isEqualTo(200);
        assertThat(amb.postLote(auth, SerializadorLote.paraGzip(parteCategorias("l-3p", ate, ate, 2, false, "cat-p2", EMPRESA))).statusCode()).isEqualTo(200);
        assertThat(contar(nuvem, "SELECT COUNT(*) FROM SyncLotePartes WHERE LoteId = 'l-3p'")).isEqualTo(2);
        assertThat(existeNaNuvem("Categorias", "cat-p1")).isFalse();

        HttpResponse<String> ultima = amb.postLote(auth, SerializadorLote.paraGzip(parteCategorias("l-3p", ate, ate, 3, true, "cat-p3", EMPRESA)));
        assertThat(ultima.statusCode()).isEqualTo(200);
        assertThat(existeNaNuvem("Categorias", "cat-p1") && existeNaNuvem("Categorias", "cat-p2") && existeNaNuvem("Categorias", "cat-p3")).isTrue();
        assertThat(contar(nuvem, "SELECT COUNT(*) FROM SyncLotePartes WHERE LoteId = 'l-3p'")).isZero();
        nuvem.update("DELETE FROM Categorias WHERE Id IN ('cat-p1','cat-p2','cat-p3')");
    }

    @Test
    @Order(9)
    void itemDeVendaDeOutraEmpresaRecusaOLoteInteiro() throws Exception {
        long ate = aplicadoAteNuvem();
        clonarVenda(nuvem, "venda-alheia", "emp-contrato-aprovada");
        List<ColunaSync> cols = List.of(new ColunaSync("Id", TipoColuna.TEXTO), new ColunaSync("VendaId", TipoColuna.TEXTO));
        BlocoTabela b = new BlocoTabela("VendaItens", cols, List.of(List.of("item-x", "venda-alheia")), List.of(), List.of());
        byte[] corpo = SerializadorLote.paraGzip(new ParteLote("l-alheio", EMPRESA, ate, ate, false, 1, true, List.of(b)));
        HttpResponse<String> r = amb.postLote("LojaServidor " + amb.token, corpo);
        assertThat(r.statusCode()).isEqualTo(422);
        assertThat(existeNaNuvem("VendaItens", "item-x")).isFalse();
        assertThat(aplicadoAteNuvem()).isEqualTo(ate);
        nuvem.update("DELETE FROM Vendas WHERE Id = 'venda-alheia'");
    }

    @Test
    @Order(10)
    void linhaDeOutraEmpresaPausaALojaSemAplicarNadaERetomaDepoisDeCorrigir() throws Exception {
        long ate = aplicadoAteNuvem();
        clonarVenda(nuvem, "venda-colide", "emp-contrato-aprovada");
        // categoria e venda no MESMO commit: o lote e recusado inteiro e a categoria nao pode aparecer
        new TransactionTemplate(amb.loja.getBean(PlatformTransactionManager.class)).executeWithoutResult(s -> {
            loja.update("INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES ('cat-it-2', ?, 'IT2', NULL, 98, 1)", EMPRESA);
            clonarVenda(loja, "venda-colide", EMPRESA);
        });
        esperar("loja pausada", 60_000, () -> loja.queryForObject("SELECT PausadaMotivo FROM SyncEstado WHERE Id = 1", String.class) != null);
        assertThat(loja.queryForObject("SELECT PausadaMotivo FROM SyncEstado WHERE Id = 1", String.class)).contains("422");
        assertThat(contar(loja, "SELECT COUNT(*) FROM SyncAlertas WHERE Motivo = 'sincronizacao-pausada'")).isEqualTo(1);
        assertThat(existeNaNuvem("Categorias", "cat-it-2")).as("rollback do lote inteiro").isFalse();
        assertThat(aplicadoAteNuvem()).isEqualTo(ate);

        // a loja continua vendendo durante a pausa
        VendaRegistroResultado durante = vender("CT001", "1", "3", "3,00");
        assertThat(contar(loja, "SELECT COUNT(*) FROM Vendas WHERE Id = ?", durante.vendaId())).isEqualTo(1);
        Thread.sleep(1500);
        assertThat(existeNaNuvem("Vendas", durante.vendaId())).isFalse();

        // corrige (remove a linha alheia da nuvem), caixa nao pode retomar, administrador pode
        nuvem.update("DELETE FROM Vendas WHERE Id = 'venda-colide'");
        assertThat(retomar("caixa@contrato.quack")).isEqualTo(403);
        assertThat(retomar("admin@contrato.quack")).isEqualTo(200);
        amb.esperarConvergencia(60_000);
        assertThat(existeNaNuvem("Categorias", "cat-it-2")).isTrue();
        assertThat(existeNaNuvem("Vendas", "venda-colide")).isTrue();
        assertThat(existeNaNuvem("Vendas", durante.vendaId())).isTrue();
        assertThat(loja.queryForObject("SELECT PausadaMotivo FROM SyncEstado WHERE Id = 1", String.class)).isNull();
        assertThat(amb.diferencas()).isEmpty();
    }

    @Test
    @Order(11)
    void umaHoraOfflineEm7CaixasConvergeNaOrdemSemIntervencao() throws Exception {
        cliente.setSemInternet(true);
        int caixas = 7;
        int vendasPorCaixa = 4;
        AtomicInteger feitas = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(caixas);
        CountDownLatch largada = new CountDownLatch(1);
        List<Future<?>> futuros = new ArrayList<>();
        for (int c = 0; c < caixas; c++) {
            String produto = c % 2 == 0 ? "CT001" : "CT002";
            futuros.add(pool.submit(() -> {
                largada.await();
                for (int i = 0; i < vendasPorCaixa; i++) {
                    venderComRetentativa(produto);
                    feitas.incrementAndGet();
                }
                return null;
            }));
        }
        int vendasNuvemAntes = contar(nuvem, "SELECT COUNT(*) FROM Vendas");
        largada.countDown();
        for (Future<?> f : futuros) f.get();
        pool.shutdown();
        assertThat(feitas.get()).isEqualTo(caixas * vendasPorCaixa);

        esperar("loja registrou falhas de rede", 30_000, () -> contar(loja, "SELECT TentativasSeguidas FROM SyncEstado WHERE Id = 1") >= 1);
        assertThat(contar(nuvem, "SELECT COUNT(*) FROM Vendas")).as("nada chegou sem internet").isEqualTo(vendasNuvemAntes);
        assertThat(loja.queryForObject("SELECT PausadaMotivo FROM SyncEstado WHERE Id = 1", String.class)).as("rede nao pausa").isNull();

        cliente.setSemInternet(false);
        amb.zerarBackoff();
        amb.esperarConvergencia(90_000);
        assertThat(contar(nuvem, "SELECT COUNT(*) FROM Vendas")).isEqualTo(vendasNuvemAntes + feitas.get());
        assertThat(amb.diferencas()).isEmpty();
    }

    // ------------------------------------------------------------------ apoio

    /** Copia uma venda existente com outro Id e empresa (SQL generico: copia todas as colunas). */
    static void clonarVenda(JdbcTemplate j, String novoId, String empresa) {
        String origem = j.queryForObject("SELECT TOP 1 Id FROM Vendas WHERE CompanyId = ? ORDER BY Id", String.class, EMPRESA);
        List<String> cols = j.queryForList(
                "SELECT name FROM sys.columns WHERE object_id = OBJECT_ID('dbo.Vendas') ORDER BY column_id", String.class);
        String lista = cols.stream().map(c -> "[" + c + "]").collect(Collectors.joining(", "));
        String sel = cols.stream().map(c -> c.equals("Id") ? "'" + novoId + "'" : c.equals("CompanyId") ? "'" + empresa + "'" : c.equals("SaleNumber") ? "'X-" + novoId + "'" : c.equals("ClientSaleId") ? "'cl-" + novoId + "'" : "[" + c + "]")
                .collect(Collectors.joining(", "));
        j.update("INSERT INTO dbo.Vendas (" + lista + ") SELECT " + sel + " FROM dbo.Vendas WHERE Id = ?", origem);
    }

    /** Login na loja (HTTP) e POST /api/loja-sync/retomar; devolve o status. */
    static int retomar(String email) throws Exception {
        HttpResponse<String> login = amb.http.send(HttpRequest.newBuilder(URI.create(amb.urlBaseLoja() + "/api/Auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"" + email + "\",\"password\":\"Senha#Quack2026\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).isEqualTo(200);
        String cookie = login.headers().allValues("Set-Cookie").stream().filter(c -> c.startsWith("horuspdv.auth="))
                .map(c -> c.substring(0, c.indexOf(';'))).findFirst().orElseThrow();
        return amb.http.send(HttpRequest.newBuilder(URI.create(amb.urlBaseLoja() + "/api/loja-sync/retomar"))
                .header("Cookie", cookie).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode();
    }
}
