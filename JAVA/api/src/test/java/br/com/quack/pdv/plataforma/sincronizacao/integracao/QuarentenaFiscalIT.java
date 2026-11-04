package br.com.quack.pdv.plataforma.sincronizacao.integracao;

import static br.com.quack.pdv.plataforma.sincronizacao.integracao.AmbienteSync.EMPRESA;
import static br.com.quack.pdv.plataforma.sincronizacao.integracao.AmbienteSync.esperar;
import static br.com.quack.pdv.plataforma.sincronizacao.integracao.SincronizacaoIT.contar;
import static br.com.quack.pdv.plataforma.sincronizacao.integracao.SincronizacaoIT.vender;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.quack.pdv.fiscal.fila.app.PendenciaFiscal;
import br.com.quack.pdv.plataforma.sincronizacao.catalogo.EsquemaJdbc;
import br.com.quack.pdv.venda.registro.app.VendaRegistroService;
import br.com.quack.pdv.venda.registro.domain.VendaRegistroResultado;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * Quarentena e reversao (4.3), resultado fiscal e Vendas.Status vindos da nuvem (4.4), rota de resolver. Edicoes "da
 * nuvem" sao SQL direto no banco da nuvem, sem contexto de tracking.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class QuarentenaFiscalIT {

    static AmbienteSync amb;
    static JdbcTemplate loja;
    static JdbcTemplate nuvem;

    @BeforeAll
    static void subir() {
        amb = AmbienteSync.iniciar("qua");
        loja = amb.jdbcLoja;
        nuvem = amb.jdbcNuvem;
        SincronizacaoIT.vendas = amb.loja.getBean(VendaRegistroService.class);
        loja.update("UPDATE Produtos SET ProductQnt = 1000 WHERE CompanyId = ? AND ProductCode IN ('CT001','CT002')", EMPRESA);
        amb.esperarConvergencia(60_000);
    }

    @AfterAll
    static void descer() {
        if (amb != null) amb.close();
    }

    /** INSERT ... SELECT da linha {@code idOrigem} de {@code j}, trocando as colunas de {@code sobrescreve} (nulo = NULL). */
    static void clonar(JdbcTemplate j, String tabela, String idOrigem, Map<String, Object> sobrescreve) {
        List<String> cols = new ArrayList<>(new EsquemaJdbc(j).colunas().get(tabela));
        List<Object> args = new ArrayList<>();
        StringBuilder sel = new StringBuilder();
        for (String c : cols) {
            if (!sel.isEmpty()) sel.append(", ");
            if (!sobrescreve.containsKey(c)) sel.append('[').append(c).append(']');
            else if (sobrescreve.get(c) == null) sel.append("NULL");
            else { sel.append('?'); args.add(sobrescreve.get(c)); }
        }
        args.add(idOrigem);
        j.update("INSERT INTO dbo.[" + tabela + "] (" + String.join(", ", cols.stream().map(c -> "[" + c + "]").toList())
                + ") SELECT " + sel + " FROM dbo.[" + tabela + "] WHERE Id = ?", args.toArray());
    }

    static int quarentenas(String tabela, String chave) {
        return contar(nuvem, "SELECT COUNT(*) FROM SyncQuarentena WHERE CompanyId = ? AND Tabela = ? AND Chave = ?", EMPRESA, tabela, chave);
    }

    // ------------------------------------------------------------------ 4.3

    @Test
    @Order(1)
    void vendaCriadaNaNuvemVaiParaQuarentenaENaoEExcluida() throws Exception {
        VendaRegistroResultado base = vender("CT001", "1", "3", "3,00");
        esperar("venda na nuvem", 40_000, () -> contar(nuvem, "SELECT COUNT(*) FROM Vendas WHERE Id = ?", base.vendaId()) == 1);
        amb.esperarConvergencia(60_000);
        Thread.sleep(3_000);
        clonar(nuvem, "Vendas", base.vendaId(), mapa("Id", "venda-nuvem-1", "SaleNumber", "NUV-1", "ClientSaleId", null,
                "OfflineReference", null));
        esperar("quarentena da venda", 20_000, () -> quarentenas("Vendas", "venda-nuvem-1") == 1);
        Map<String, Object> q = AmbienteSync.linha(nuvem, "SELECT * FROM SyncQuarentena WHERE Tabela = 'Vendas' AND Chave = 'venda-nuvem-1'");
        assertThat((String) q.get("LinhaJson")).contains("NUV-1");
        assertThat((String) q.get("Motivo")).contains("criada na nuvem");
        assertThat(contar(nuvem, "SELECT COUNT(*) FROM SyncAlertas WHERE Motivo = 'quarentena' AND CompanyId = ?", EMPRESA)).isEqualTo(1);
        Thread.sleep(6_000);
        assertThat(contar(nuvem, "SELECT COUNT(*) FROM Vendas WHERE Id = 'venda-nuvem-1'")).as("nao e excluida").isEqualTo(1);
        assertThat(contar(loja, "SELECT COUNT(*) FROM Vendas WHERE Id = 'venda-nuvem-1'")).isZero();
        assertThat(quarentenas("Vendas", "venda-nuvem-1")).as("sem entrada repetida").isEqualTo(1);
    }

    @Test
    @Order(2)
    void saldoAlteradoNaNuvemVoltaAoDaLojaComAlerta() {
        BigDecimal daLoja = loja.queryForObject("SELECT ProductQnt FROM Produtos WHERE CompanyId = ? AND ProductCode = 'CT002'",
                BigDecimal.class, EMPRESA);
        String id = nuvem.queryForObject("SELECT Id FROM Produtos WHERE CompanyId = ? AND ProductCode = 'CT002'", String.class, EMPRESA);
        nuvem.update("UPDATE Produtos SET ProductQnt = 5555 WHERE Id = ?", id);
        esperar("quarentena do saldo", 20_000, () -> quarentenas("Produtos", id) >= 1);
        String detalhe = nuvem.queryForObject("SELECT Detalhe FROM SyncAlertas WHERE Motivo = 'quarentena' AND CompanyId = ?",
                String.class, EMPRESA);
        assertThat(detalhe).contains("ProductQnt=5555");
        esperar("saldo de volta ao da loja", 40_000, () -> nuvem.queryForObject("SELECT ProductQnt FROM Produtos WHERE Id = ?",
                BigDecimal.class, id).compareTo(daLoja) == 0);
        assertThat(loja.queryForObject("SELECT ProductQnt FROM Produtos WHERE Id = ?", BigDecimal.class, id)).isEqualByComparingTo(daLoja);
        amb.esperarConvergencia(60_000);
    }

    @Test
    @Order(3)
    void exclusaoEmTabelaDaLojaNaoGeraComandoNemQuarentena_ficaParaAConferencia() throws Exception {
        VendaRegistroResultado v = vender("CT001", "1", "3", "3,00");
        esperar("venda na nuvem", 40_000, () -> contar(nuvem, "SELECT COUNT(*) FROM Vendas WHERE Id = ?", v.vendaId()) == 1);
        amb.esperarConvergencia(60_000);
        Thread.sleep(3_000);
        long antes = nuvem.queryForObject("SELECT ISNULL(MAX(Seq), 0) FROM SyncComandos", Long.class);
        nuvem.update("DELETE FROM VendaPagamentos WHERE VendaId = ?", v.vendaId());
        Thread.sleep(6_000);
        assertThat(nuvem.queryForObject("SELECT ISNULL(MAX(Seq), 0) FROM SyncComandos", Long.class))
                .as("empresa da linha excluida e desconhecida: sem comando (a conferencia do grupo 6 corrige)").isEqualTo(antes);
        assertThat(contar(loja, "SELECT COUNT(*) FROM VendaPagamentos WHERE VendaId = ?", v.vendaId())).isEqualTo(1);
    }

    @Test
    @Order(4)
    void resolverAQuarentenaExigeAdministradorDaEmpresa() throws Exception {
        long id = nuvem.queryForObject("SELECT TOP 1 Id FROM SyncQuarentena WHERE CompanyId = ? AND ResolvidoEm IS NULL ORDER BY Id",
                Long.class, EMPRESA);
        String admin = amb.tokenNuvem("usr-ct-admin", EMPRESA, "Ana Administradora", "admin@contrato.quack", "administrador");
        String gerente = amb.tokenNuvem("usr-ct-gerente", EMPRESA, "Gil Gerente", "gerente@contrato.quack", "gerente");
        assertThat(resolver(id, null).statusCode()).isEqualTo(401);
        assertThat(resolver(id, gerente).statusCode()).isEqualTo(403);
        assertThat(resolver(id, admin).statusCode()).isEqualTo(200);
        assertThat(nuvem.queryForObject("SELECT ResolvidoPor FROM SyncQuarentena WHERE Id = ?", String.class, id)).isEqualTo("usr-ct-admin");
        assertThat(resolver(id, admin).statusCode()).as("ja resolvida").isEqualTo(404);
        assertThat(resolver(987654321L, admin).statusCode()).isEqualTo(404);
    }

    static HttpResponse<String> resolver(long id, String jwt) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(amb.urlBaseNuvem() + "/api/loja-sync/quarentena/" + id + "/resolver"))
                .POST(HttpRequest.BodyPublishers.noBody());
        if (jwt != null) b.header("Authorization", "Bearer " + jwt);
        return amb.http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    // ------------------------------------------------------------------ Vendas.Status da nuvem

    @Test
    @Order(5)
    void statusCanceladaVindoDaNuvemChegaALojaSemQuarentena() throws Exception {
        VendaRegistroResultado v = vender("CT001", "1", "3", "3,00");
        esperar("venda na nuvem", 40_000, () -> contar(nuvem, "SELECT COUNT(*) FROM Vendas WHERE Id = ?", v.vendaId()) == 1);
        amb.esperarConvergencia(60_000);
        Thread.sleep(3_000);
        nuvem.update("UPDATE Vendas SET Status = 'cancelada', CanceladoEm = SYSDATETIMEOFFSET(), CanceladoJustificativa = N'nota cancelada na nuvem' "
                + "WHERE Id = ?", v.vendaId());
        esperar("status na loja", 40_000, () -> "cancelada".equals(loja.queryForObject("SELECT Status FROM Vendas WHERE Id = ?",
                String.class, v.vendaId())));
        assertThat(loja.queryForObject("SELECT CanceladoJustificativa FROM Vendas WHERE Id = ?", String.class, v.vendaId()))
                .isEqualTo("nota cancelada na nuvem");
        amb.esperarConvergencia(60_000);
        Thread.sleep(3_000);
        assertThat(nuvem.queryForObject("SELECT Status FROM Vendas WHERE Id = ?", String.class, v.vendaId())).isEqualTo("cancelada");
        assertThat(quarentenas("Vendas", v.vendaId())).isZero();
        assertThat(contar(nuvem, "SELECT COUNT(*) FROM SyncComandos WHERE Tabela = 'Vendas' AND Chave = ? AND Tipo = 'forcar' AND Status = 'aplicado'",
                v.vendaId())).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 4.4 fiscal

    @Test
    @Order(6)
    void resultadoFiscalDaNuvemChegaALojaMantendoNumeroEChave() throws Exception {
        PendenciaFiscal pendencia = amb.loja.getBean(PendenciaFiscal.class);
        TransactionTemplate tx = new TransactionTemplate(amb.loja.getBean(PlatformTransactionManager.class));
        VendaRegistroResultado v = vender("CT002", "1", "3", "3,00");
        tx.executeWithoutResult(s -> {
            loja.update("UPDATE Vendas SET EmitirNfce = 1 WHERE CompanyId = ? AND Id = ?", EMPRESA, v.vendaId());
            pendencia.enfileirar(EMPRESA, v.vendaId());
        });
        esperar("nota na nuvem", 40_000, () -> contar(nuvem, "SELECT COUNT(*) FROM DocumentosFiscais WHERE VendaId = ?", v.vendaId()) == 1);
        amb.esperarConvergencia(60_000);
        Thread.sleep(3_000);
        Map<String, Object> antes = AmbienteSync.linha(loja, "SELECT Id, NumeroNf, Serie, Modelo FROM DocumentosFiscais WHERE VendaId = ?", v.vendaId());
        String nota = (String) antes.get("Id");
        int numero = ((Number) antes.get("NumeroNf")).intValue();
        String chave = "35261112345678000199650010" + String.format("%09d", numero) + "100000001";
        assertThat(chave).hasSize(44);

        // o worker da .NET na nuvem autoriza a nota
        nuvem.update("""
                UPDATE DocumentosFiscais SET Status = 100, Protocolo = N'135260000000001', CodigoStatus = 100, ChaveAcesso = ?,
                       XmlProtocolado = N'<nfeProc/>', DhAutorizacao = SYSDATETIMEOFFSET(), AtualizadoEm = SYSDATETIMEOFFSET()
                 WHERE Id = ?
                """, chave, nota);
        esperar("resultado na loja", 40_000, () -> loja.queryForObject("SELECT Status FROM DocumentosFiscais WHERE Id = ?",
                Integer.class, nota) == 100);
        Map<String, Object> depois = AmbienteSync.linha(loja, "SELECT NumeroNf, Serie, ChaveAcesso, Protocolo, XmlProtocolado FROM DocumentosFiscais WHERE Id = ?", nota);
        assertThat(((Number) depois.get("NumeroNf")).intValue()).as("numero reservado pela loja").isEqualTo(numero);
        assertThat(((String) depois.get("ChaveAcesso")).trim()).isEqualTo(chave);
        assertThat(depois.get("Protocolo")).isEqualTo("135260000000001");
        assertThat(depois.get("XmlProtocolado")).isEqualTo("<nfeProc/>");
        amb.esperarConvergencia(60_000);
        Thread.sleep(3_000);
        assertThat(nuvem.queryForObject("SELECT Status FROM DocumentosFiscais WHERE Id = ?", Integer.class, nota))
                .as("o lote da loja nao desfez o resultado").isEqualTo(100);
        assertThat(nuvem.queryForObject("SELECT NumeroNf FROM DocumentosFiscais WHERE Id = ?", Integer.class, nota)).isEqualTo(numero);
        assertThat(quarentenas("DocumentosFiscais", nota)).isZero();
    }

    @Test
    @Order(7)
    void nfeModelo55CriadaNaNuvemDesceParaALoja() {
        String modelo65 = nuvem.queryForObject("SELECT TOP 1 Id FROM DocumentosFiscais WHERE CompanyId = ? AND Modelo = 65", String.class, EMPRESA);
        clonar(nuvem, "DocumentosFiscais", modelo65, mapa("Id", "nfe55-nuvem-1", "Modelo", 55, "NumeroNf", 9001, "ChaveAcesso", null,
                "VendaId", null, "Status", 100));
        esperar("NF-e 55 na loja", 40_000, () -> contar(loja, "SELECT COUNT(*) FROM DocumentosFiscais WHERE Id = 'nfe55-nuvem-1' AND Modelo = 55 "
                + "AND NumeroNf = 9001") == 1);
        assertThat(quarentenas("DocumentosFiscais", "nfe55-nuvem-1")).isZero();
        // a loja devolve a linha no lote e a nuvem continua como autora
        nuvem.update("UPDATE DocumentosFiscais SET Status = 101 WHERE Id = 'nfe55-nuvem-1'");
        esperar("novo status na loja", 40_000, () -> loja.queryForObject("SELECT Status FROM DocumentosFiscais WHERE Id = 'nfe55-nuvem-1'",
                Integer.class) == 101);
        amb.esperarConvergencia(60_000);
        assertThat(nuvem.queryForObject("SELECT Status FROM DocumentosFiscais WHERE Id = 'nfe55-nuvem-1'", Integer.class)).isEqualTo(101);
    }

    static Map<String, Object> mapa(Object... kv) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
