package br.com.quack.pdv.plataforma.sincronizacao.integracao;

import static br.com.quack.pdv.plataforma.sincronizacao.integracao.AmbienteSync.EMPRESA;
import static br.com.quack.pdv.plataforma.sincronizacao.integracao.AmbienteSync.esperar;
import static br.com.quack.pdv.plataforma.sincronizacao.integracao.SincronizacaoIT.contar;
import static br.com.quack.pdv.plataforma.sincronizacao.integracao.SincronizacaoIT.vender;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.quack.pdv.plataforma.numeracao.NumeradorDocumentos;
import br.com.quack.pdv.plataforma.sincronizacao.carga.ClienteCarga;
import br.com.quack.pdv.plataforma.sincronizacao.conferencia.ConferenciaSincronizacao;
import br.com.quack.pdv.plataforma.sincronizacao.envio.EnvioSincronizacao;
import br.com.quack.pdv.plataforma.sincronizacao.recuperacao.RecuperacaoLoja;
import br.com.quack.pdv.venda.registro.app.VendaRegistroService;
import br.com.quack.pdv.venda.registro.domain.VendaRegistroResultado;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
 * Tarefa 7.4: recuperacao do servidor da loja depois de restaurar um backup. A restauracao e feita de verdade (BACKUP e
 * RESTORE no SQL Server do container, com a API da loja parada, como o restaurar.ps1). O marcador do script e um arquivo
 * temporario. Depende da ordem: cada teste parte do estado em que o anterior deixou as duas pontas.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RecuperacaoLojaIT {

    static final ObjectMapper JSON = new ObjectMapper();
    static final String[] EXECUTOR_LIGADO = {"--quack.sync.recuperacao-ativa=true", "--quack.sync.recuperacao-intervalo-ms=500",
            "--quack.sync.recuperacao-verificacao-ms=500"};

    static AmbienteSync amb;
    static Path marcador;
    static String categoriaEditada;
    static int vendasNaNuvemAntes;

    @BeforeAll
    static void subir() throws Exception {
        amb = AmbienteSync.iniciar("rec");
        marcador = Files.createTempDirectory("quack-rec").resolve("recuperar.pendente");
        SincronizacaoIT.vendas = amb.loja.getBean(VendaRegistroService.class);
        // o seed da nuvem tem segredos que a chave de cifra do teste nao le; a carga (que decifra) precisa de segredos validos
        var cifra = amb.nuvem.getBean(br.com.quack.pdv.security.SecretProtector.class);
        amb.jdbcNuvem.update("UPDATE Empresas SET CertificadoSenhaCifrada = ?, CscCifrado = ?, EmailSmtpPassword = ? WHERE Id = ?",
                cifra.protect("senha-cert"), cifra.protect("csc"), cifra.protect("smtp"), EMPRESA);
        amb.jdbcLoja.update("UPDATE Empresas SET CertificadoSenhaCifrada = ?, CscCifrado = ?, EmailSmtpPassword = ? WHERE Id = ?",
                cifra.protect("senha-cert"), cifra.protect("csc"), cifra.protect("smtp"), EMPRESA);
        amb.jdbcLoja.update("UPDATE Produtos SET ProductQnt = 1000 WHERE CompanyId = ? AND ProductCode IN ('CT001','CT002')", EMPRESA);
        amb.esperarConvergencia(60_000);
    }

    @AfterAll
    static void descer() {
        if (amb != null) amb.close();
    }

    // ------------------------------------------------------------------ apoio

    private static String[] argumentos(boolean executorLigado) {
        List<String> a = new ArrayList<>();
        a.add("--quack.sync.recuperacao-marcador=" + marcador);
        if (executorLigado) a.addAll(List.of(EXECUTOR_LIGADO));
        return a.toArray(String[]::new);
    }

    private static void reatribuirServico() {
        SincronizacaoIT.vendas = amb.loja.getBean(VendaRegistroService.class);
    }

    private static String estadoRecuperacao() {
        return amb.jdbcLoja.queryForObject("SELECT Estado FROM SyncRecuperacao WHERE Id = 1", String.class);
    }

    private static HttpResponse<String> get(String caminho, String autorizacao) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(amb.urlBaseLoja() + caminho)).GET();
        if (autorizacao != null) b.header("Authorization", autorizacao);
        return amb.http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String admin() {
        return "Bearer " + amb.tokenLoja("usr-ct-admin", EMPRESA, "Ana Administradora", "admin@contrato.quack", "administrador");
    }

    private static String maiorNumeroDeVenda(JdbcTemplate j) {
        return j.queryForObject("SELECT CONVERT(NVARCHAR(30), MAX(TRY_CONVERT(INT, SaleNumber))) FROM Vendas WHERE CompanyId = ?",
                String.class, EMPRESA);
    }

    private static int numeroFiscal() {
        TransactionTemplate t = new TransactionTemplate(amb.loja.getBean(PlatformTransactionManager.class));
        return t.execute(s -> amb.loja.getBean(NumeradorDocumentos.class).proximoNumeroFiscal(EMPRESA, (short) 65, 1, (byte) 2));
    }

    private static int proximoNumeroFiscalGravado(JdbcTemplate j) {
        return j.queryForObject("SELECT ProximoNumero FROM FiscalSequencias WHERE CompanyId = ? AND Modelo = 65 AND Serie = 1 AND Ambiente = 2",
                Integer.class, EMPRESA);
    }

    // ------------------------------------------------------------------ cenarios

    @Test
    @Order(1)
    void lojaRestauradaDeBackupAnteriorRecuperaTodasAsVendasEANumeracaoContinuaSemRepetir() throws Exception {
        numeroFiscal(); // cria a sequencia fiscal antes do backup
        amb.esperarConvergencia(60_000);
        String backup = amb.backupLoja("rec-1");

        // depois do backup: vendas, numeros fiscais e dois cadastros (um novo e um editado) feitos na nuvem
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) ids.add(vender(i % 2 == 0 ? "CT001" : "CT002", "1", "3", "3,00").vendaId());
        int ultimoFiscal = 0;
        for (int i = 0; i < 3; i++) ultimoFiscal = numeroFiscal();
        categoriaEditada = amb.jdbcLoja.queryForObject("SELECT TOP 1 Id FROM Categorias WHERE CompanyId = ? ORDER BY Id", String.class, EMPRESA);
        amb.jdbcNuvem.update("UPDATE Categorias SET Nome = 'Editada na nuvem apos o backup' WHERE Id = ?", categoriaEditada);
        amb.jdbcNuvem.update("INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES ('cat-rec-nuv', ?, 'Nova na nuvem', NULL, 90, 1)", EMPRESA);
        esperar("comandos da nuvem aplicados na loja", 60_000, () ->
                contar(amb.jdbcLoja, "SELECT COUNT(*) FROM Categorias WHERE Id = 'cat-rec-nuv'") == 1
                        && "Editada na nuvem apos o backup".equals(amb.jdbcLoja.queryForObject(
                                "SELECT Nome FROM Categorias WHERE Id = ?", String.class, categoriaEditada)));
        amb.esperarConvergencia(60_000);
        for (String id : ids) assertThat(contar(amb.jdbcNuvem, "SELECT COUNT(*) FROM Vendas WHERE Id = ?", id)).isEqualTo(1);
        String maiorAntes = maiorNumeroDeVenda(amb.jdbcLoja);
        vendasNaNuvemAntes = contar(amb.jdbcNuvem, "SELECT COUNT(*) FROM Vendas WHERE CompanyId = ?", EMPRESA);
        int movimentosAntes = contar(amb.jdbcNuvem, "SELECT COUNT(*) FROM EstoqueMovimentacoes WHERE CompanyId = ?", EMPRESA);
        int qtdAntes = amb.jdbcNuvem.queryForObject("SELECT ProductQnt FROM Produtos WHERE CompanyId = ? AND ProductCode = 'CT001'", Integer.class, EMPRESA);

        // a restauracao: o marcador do restaurar.ps1 existe antes de a API subir
        Files.writeString(marcador, "restaurado");
        amb.restaurarLoja(backup, argumentos(true));
        reatribuirServico();
        assertThat(contar(amb.jdbcLoja, "SELECT COUNT(*) FROM Vendas WHERE CompanyId = ?", EMPRESA))
                .as("o banco restaurado nao tem as vendas posteriores ao backup").isEqualTo(vendasNaNuvemAntes - 5);

        esperar("recuperacao concluida", 120_000, () -> "concluida".equals(estadoRecuperacao()));
        assertThat(Files.exists(marcador)).as("o marcador foi consumido").isFalse();

        // tudo voltou
        for (String id : ids) assertThat(contar(amb.jdbcLoja, "SELECT COUNT(*) FROM Vendas WHERE Id = ?", id)).isEqualTo(1);
        assertThat(contar(amb.jdbcLoja, "SELECT COUNT(*) FROM Vendas WHERE CompanyId = ?", EMPRESA)).isEqualTo(vendasNaNuvemAntes);
        assertThat(contar(amb.jdbcLoja, "SELECT COUNT(*) FROM EstoqueMovimentacoes WHERE CompanyId = ?", EMPRESA)).isEqualTo(movimentosAntes);
        assertThat(amb.jdbcLoja.queryForObject("SELECT ProductQnt FROM Produtos WHERE CompanyId = ? AND ProductCode = 'CT001'", Integer.class, EMPRESA))
                .isEqualTo(qtdAntes);
        // cadastros alterados so na nuvem depois do backup
        assertThat(amb.jdbcLoja.queryForObject("SELECT Nome FROM Categorias WHERE Id = ?", String.class, categoriaEditada))
                .isEqualTo("Editada na nuvem apos o backup");
        assertThat(contar(amb.jdbcLoja, "SELECT COUNT(*) FROM Categorias WHERE Id = 'cat-rec-nuv'")).isEqualTo(1);

        // numeracao: fiscal e de venda continuam acima do que ja existia
        assertThat(proximoNumeroFiscalGravado(amb.jdbcLoja)).isEqualTo(ultimoFiscal + 1);
        assertThat(numeroFiscal()).isEqualTo(ultimoFiscal + 1);
        VendaRegistroResultado nova = vender("CT001", "1", "3", "3,00");
        assertThat(amb.jdbcLoja.queryForObject("SELECT SaleNumber FROM Vendas WHERE Id = ?", String.class, nova.vendaId()))
                .isEqualTo(Integer.toString(Integer.parseInt(maiorAntes) + 1));
        assertThat(contar(amb.jdbcLoja, "SELECT COUNT(*) - COUNT(DISTINCT SaleNumber) FROM Vendas WHERE CompanyId = ?", EMPRESA)).isZero();

        // versoes realinhadas: a loja volta a enviar e a nuvem a aceitar, sem 409 nem pausa
        amb.esperarConvergencia(60_000);
        assertThat(amb.jdbcLoja.queryForObject("SELECT PausadaMotivo FROM SyncEstado WHERE Id = 1", String.class)).isNull();
        esperar("venda nova na nuvem", 60_000, () -> contar(amb.jdbcNuvem, "SELECT COUNT(*) FROM Vendas WHERE Id = ?", nova.vendaId()) == 1);
        assertThat(contar(amb.jdbcNuvem, "SELECT COUNT(*) FROM Vendas WHERE CompanyId = ?", EMPRESA)).isEqualTo(vendasNaNuvemAntes + 1);
        assertThat(proximoNumeroFiscalGravado(amb.jdbcNuvem)).isEqualTo(proximoNumeroFiscalGravado(amb.jdbcLoja));
        vendasNaNuvemAntes++;
    }

    @Test
    @Order(2)
    void depoisDaRecuperacaoAConferenciaFechaOkENaoApagaNadaDaNuvem() throws Exception {
        int vendas = contar(amb.jdbcNuvem, "SELECT COUNT(*) FROM Vendas WHERE CompanyId = ?", EMPRESA);
        int itens = contar(amb.jdbcNuvem, "SELECT COUNT(*) FROM VendaItens");
        int movimentos = contar(amb.jdbcNuvem, "SELECT COUNT(*) FROM EstoqueMovimentacoes WHERE CompanyId = ?", EMPRESA);
        amb.esperarConvergencia(60_000);

        ConferenciaSincronizacao.Relatorio r = amb.loja.getBean(ConferenciaSincronizacao.class).executar("manual");

        assertThat(r.resultado()).as(r.detalhe().toString()).isEqualTo("ok");
        assertThat(contar(amb.jdbcNuvem, "SELECT COUNT(*) FROM Vendas WHERE CompanyId = ?", EMPRESA)).isEqualTo(vendas);
        assertThat(contar(amb.jdbcNuvem, "SELECT COUNT(*) FROM VendaItens")).isEqualTo(itens);
        assertThat(contar(amb.jdbcNuvem, "SELECT COUNT(*) FROM EstoqueMovimentacoes WHERE CompanyId = ?", EMPRESA)).isEqualTo(movimentos);
    }

    @Test
    @Order(3)
    void repetirARecuperacaoNaoDuplicaNemMudaNada() {
        int auditoria = contar(amb.jdbcLoja, "SELECT COUNT(*) FROM AuditLog WHERE CompanyId = ?", EMPRESA);
        int vendas = contar(amb.jdbcLoja, "SELECT COUNT(*) FROM Vendas WHERE CompanyId = ?", EMPRESA);
        var estado = amb.loja.getBean(br.com.quack.pdv.plataforma.sincronizacao.recuperacao.EstadoRecuperacao.class);
        estado.solicitar("repeticao-do-teste");

        amb.loja.getBean(RecuperacaoLoja.class).executar();
        esperar("recuperacao repetida concluida", 120_000, () -> "concluida".equals(estadoRecuperacao()));
        assertThat(contar(amb.jdbcLoja, "SELECT COUNT(*) FROM AuditLog WHERE CompanyId = ?", EMPRESA)).isEqualTo(auditoria);
        assertThat(contar(amb.jdbcLoja, "SELECT COUNT(*) FROM Vendas WHERE CompanyId = ?", EMPRESA)).isEqualTo(vendas);
        amb.esperarConvergencia(60_000);
        assertThat(amb.loja.getBean(ConferenciaSincronizacao.class).executar("manual").resultado()).isEqualTo("ok");
    }

    @Test
    @Order(4)
    void semInternetARecuperacaoFicaPendenteNaoVendeEAConferenciaContinuaParada() throws Exception {
        amb.esperarConvergencia(60_000);
        String backup = amb.backupLoja("rec-2");
        for (int i = 0; i < 3; i++) vender("CT001", "1", "3", "3,00");
        amb.esperarConvergencia(60_000);
        int vendasNuvem = contar(amb.jdbcNuvem, "SELECT COUNT(*) FROM Vendas WHERE CompanyId = ?", EMPRESA);

        Files.writeString(marcador, "restaurado");
        amb.restaurarLoja(backup, argumentos(false)); // a rotina fica desligada: o teste a chama
        reatribuirServico();
        amb.loja.getBean(ClienteCarga.class).setSemInternet(true);

        // so o marcador ja bloqueia: a API subiu e o banco ainda nem sabe da recuperacao
        String adm = admin();
        assertThat(get("/api/Categoria", adm).statusCode()).as("marcador presente bloqueia").isEqualTo(503);
        assertThat(get("/actuator/health", null).statusCode()).isEqualTo(200);

        var recuperacao = amb.loja.getBean(RecuperacaoLoja.class);
        assertThat(recuperacao.executar()).isEqualTo(RecuperacaoLoja.Resultado.SEM_INTERNET);
        assertThat(estadoRecuperacao()).isEqualTo("pendente");
        assertThat(Files.exists(marcador)).isFalse();

        HttpResponse<String> negado = get("/api/Categoria", adm);
        assertThat(negado.statusCode()).isEqualTo(503);
        assertThat(negado.headers().firstValue("Retry-After")).isPresent();
        JsonNode situacao = JSON.readTree(get("/api/loja-sync/situacao", adm).body()).path("data");
        assertThat(situacao.path("recuperando").asBoolean()).isTrue();
        assertThat(situacao.path("recuperacao").path("estado").asText()).isEqualTo("pendente");
        assertThat(situacao.path("recuperacao").path("ultimoErro").asText()).contains("Sem comunicacao");

        // conferencia (manual e agendada) e envio parados; nada saiu da nuvem
        ConferenciaSincronizacao.Relatorio c = amb.loja.getBean(ConferenciaSincronizacao.class).executar("manual");
        assertThat(c.resultado()).isEqualTo("erro");
        assertThat(c.detalhe().path("erro").asText()).contains("recuperacao");
        assertThat(amb.loja.getBean(EnvioSincronizacao.class).ciclo()).isEqualTo(EnvioSincronizacao.Resultado.RECUPERANDO);
        assertThat(contar(amb.jdbcNuvem, "SELECT COUNT(*) FROM Vendas WHERE CompanyId = ?", EMPRESA)).isEqualTo(vendasNuvem);
        assertThat(contar(amb.jdbcLoja, "SELECT COUNT(*) FROM Vendas WHERE CompanyId = ?", EMPRESA)).isEqualTo(vendasNuvem - 3);

        // a internet volta: retoma e termina
        amb.loja.getBean(ClienteCarga.class).setSemInternet(false);
        assertThat(recuperacao.executar()).isEqualTo(RecuperacaoLoja.Resultado.CONCLUIDA);
        assertThat(estadoRecuperacao()).isEqualTo("concluida");
        assertThat(get("/api/Categoria", adm).statusCode()).isEqualTo(200);
        assertThat(contar(amb.jdbcLoja, "SELECT COUNT(*) FROM Vendas WHERE CompanyId = ?", EMPRESA)).isEqualTo(vendasNuvem);
        amb.esperarConvergencia(60_000);
        assertThat(amb.loja.getBean(ConferenciaSincronizacao.class).executar("manual").resultado()).isEqualTo("ok");
        assertThat(contar(amb.jdbcNuvem, "SELECT COUNT(*) FROM Vendas WHERE CompanyId = ?", EMPRESA)).isEqualTo(vendasNuvem);
    }

    @Test
    @Order(5)
    void quedaNoMeioDaPuxadaRetomaDeOndeParouSemDuplicar() throws Exception {
        amb.esperarConvergencia(60_000);
        String backup = amb.backupLoja("rec-3");
        for (int i = 0; i < 3; i++) vender("CT002", "1", "3", "3,00");
        amb.esperarConvergencia(60_000);
        int vendasNuvem = contar(amb.jdbcNuvem, "SELECT COUNT(*) FROM Vendas WHERE CompanyId = ?", EMPRESA);

        Files.writeString(marcador, "restaurado");
        amb.restaurarLoja(backup, argumentos(false));
        reatribuirServico();
        var recuperacao = amb.loja.getBean(RecuperacaoLoja.class);
        recuperacao.definirLimitePagina(2);
        int[] paginas = {0};
        recuperacao.aoGravarPagina(p -> {
            if (++paginas[0] == 7) throw new IllegalStateException("queda simulada");
        });
        assertThat(recuperacao.executar()).isEqualTo(RecuperacaoLoja.Resultado.FALHOU);
        assertThat(estadoRecuperacao()).isEqualTo("pendente");
        assertThat(amb.jdbcLoja.queryForObject("SELECT Fase FROM SyncRecuperacao WHERE Id = 1", String.class)).isEqualTo("puxando");
        assertThat(contar(amb.jdbcLoja, "SELECT COUNT(*) FROM SyncRecuperacaoTabelas")).isPositive();

        recuperacao.aoGravarPagina(null);
        assertThat(recuperacao.executar()).isEqualTo(RecuperacaoLoja.Resultado.CONCLUIDA);
        assertThat(contar(amb.jdbcLoja, "SELECT COUNT(*) FROM Vendas WHERE CompanyId = ?", EMPRESA)).isEqualTo(vendasNuvem);
        amb.esperarConvergencia(60_000);
        assertThat(amb.loja.getBean(ConferenciaSincronizacao.class).executar("manual").resultado()).isEqualTo("ok");
        assertThat(contar(amb.jdbcNuvem, "SELECT COUNT(*) FROM Vendas WHERE CompanyId = ?", EMPRESA)).isEqualTo(vendasNuvem);
    }

    @Test
    @Order(6)
    void vendaQueSoExisteNoBackupNaoSeperdeESobeDepoisDaRecuperacao() throws Exception {
        amb.esperarConvergencia(60_000);
        // sem internet: a venda existe so na loja e entra no backup sem ter subido
        amb.cliente().setSemInternet(true);
        VendaRegistroResultado soNaLoja = vender("CT001", "1", "3", "3,00");
        String backup = amb.backupLoja("rec-4");
        assertThat(contar(amb.jdbcNuvem, "SELECT COUNT(*) FROM Vendas WHERE Id = ?", soNaLoja.vendaId())).isZero();

        Files.writeString(marcador, "restaurado");
        amb.restaurarLoja(backup, argumentos(true)); // o contexto novo tem a internet "ligada"
        reatribuirServico();
        esperar("recuperacao concluida", 120_000, () -> "concluida".equals(estadoRecuperacao()));

        assertThat(contar(amb.jdbcLoja, "SELECT COUNT(*) FROM Vendas WHERE Id = ?", soNaLoja.vendaId())).isEqualTo(1);
        esperar("venda so do backup subiu", 60_000, () -> contar(amb.jdbcNuvem, "SELECT COUNT(*) FROM Vendas WHERE Id = ?", soNaLoja.vendaId()) == 1);
        amb.esperarConvergencia(60_000);
        assertThat(amb.loja.getBean(ConferenciaSincronizacao.class).executar("manual").resultado()).isEqualTo("ok");
    }

    @Test
    @Order(7)
    void restauracaoSemMarcadorEPercebidaPelaVersaoDaNuvemEARecuperacaoDisparaSozinha() throws Exception {
        amb.esperarConvergencia(60_000);
        String backup = amb.backupLoja("rec-5");
        VendaRegistroResultado depois = vender("CT002", "1", "3", "3,00");
        amb.esperarConvergencia(60_000);

        amb.restaurarLoja(backup, argumentos(true)); // sem marcador (restauracao manual, fora do script)
        reatribuirServico();
        esperar("recuperacao disparada pela versao", 120_000, () -> "concluida".equals(estadoRecuperacao())
                && String.valueOf(amb.jdbcLoja.queryForObject("SELECT Motivo FROM SyncRecuperacao WHERE Id = 1", String.class))
                        .startsWith("versao-regrediu"));

        assertThat(contar(amb.jdbcLoja, "SELECT COUNT(*) FROM Vendas WHERE Id = ?", depois.vendaId())).isEqualTo(1);
        amb.esperarConvergencia(60_000);
        assertThat(amb.loja.getBean(ConferenciaSincronizacao.class).executar("manual").resultado()).isEqualTo("ok");
    }
}
