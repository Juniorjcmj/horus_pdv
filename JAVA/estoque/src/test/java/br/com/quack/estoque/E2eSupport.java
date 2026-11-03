package br.com.quack.estoque;

import br.com.quack.estoque.api.ApiClient;
import br.com.quack.estoque.api.ApiException;
import br.com.quack.estoque.domain.Produto;
import br.com.quack.estoque.persistence.JsonFiles;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Apoio dos testes ponta a ponta (só rodam com -Dquack.e2e.url=http://localhost:5260 e a base de teste do Docker:
 * usuários caixa@teste.com / Teste@12345 (administrador) e operadora@teste.com / Caixa@12345 (caixa)).
 * Dados de apoio que a API não cria (vendas antigas) entram por SQL via docker exec no contêiner pdv-teste-sql.
 */
final class E2eSupport {
    static final String URL_PROP = "quack.e2e.url";
    static final String ADMIN_EMAIL = System.getProperty("quack.e2e.email", "caixa@teste.com");
    static final String ADMIN_SENHA = System.getProperty("quack.e2e.senha", "Teste@12345");

    final AppContext ctx;
    final ApiClient api;
    final String sufixo = Long.toString(System.currentTimeMillis(), 36);

    E2eSupport(Path home) {
        System.setProperty("quack.home", home.toString());
        this.ctx = new AppContext();
        this.api = ctx.api;
        api.setBaseUrl(System.getProperty(URL_PROP));
    }

    static void log(String msg) {
        System.out.println("[E2E] " + msg);
    }

    void loginAdmin() {
        ctx.entrar(ADMIN_EMAIL, ADMIN_SENHA);
    }

    String adminId() {
        return ctx.session.user().id();
    }

    /** Cria um produto com estoque inicial e devolve o cadastro lido de volta. */
    Produto criarProduto(String nomeBase, String codigoBase, String qtd, String custo, String venda, String categoriaId,
            String minimo, String maximo) {
        ObjectNode b = JsonFiles.MAPPER.createObjectNode();
        b.put("productName", nomeBase + " " + sufixo);
        b.put("productCode", codigoBase + "-" + sufixo);
        b.put("productQnt", qtd);
        b.put("estoqueMinimo", minimo);
        b.put("estoqueMaximo", maximo);
        b.put("productUnitPrice", custo);
        b.put("productSalePrice", venda);
        b.put("totalPriceOnProduct", "0,00");
        b.put("unidadeComercial", "UN");
        b.put("unidadeTributavel", "UN");
        if (categoriaId != null) {
            b.put("categoriaId", categoriaId);
        }
        return ctx.produtos.criar(b);
    }

    Produto produto(String id) {
        return ctx.produtos.listar().stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
    }

    /** Venda pelo contrato do caixa (HistoricoVendas): baixa estoque como o caixa faria. */
    void vender(Produto p, int quantidade) {
        BigDecimal total = p.preco().multiply(BigDecimal.valueOf(quantidade));
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("productCode", p.codigo());
        item.put("productName", p.nome());
        item.put("quantity", quantidade);
        item.put("unitPrice", p.preco());
        item.put("desconto", 0);
        item.put("itemTotal", total);
        Map<String, Object> pag = new LinkedHashMap<>();
        pag.put("paymentType", "Dinheiro");
        pag.put("amount", total);
        pag.put("cashGiven", total);
        pag.put("changeAmount", 0);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("customerName", "Consumidor");
        body.put("customerCpf", "-");
        body.put("paymentType", "Dinheiro");
        body.put("totalAmount", total.toPlainString().replace('.', ','));
        body.put("operatorName", ctx.session.user().name());
        body.put("items", List.of(item));
        body.put("payments", List.of(pag));
        try {
            api.send("POST", "/api/HistoricoVendas", body, ApiClient.SALE_TIMEOUT);
        } catch (ApiException e) {
            if (e.status() == 503 && e.data() != null && e.data().hasNonNull("saleNumber")) {
                return; // venda gravada; só a fila fiscal falhou
            }
            throw e;
        }
    }

    /** Executa SQL no banco de teste (docker exec). Devolve a saída do sqlcmd. */
    static String sql(String comando) {
        String container = System.getProperty("quack.e2e.sqlContainer", "pdv-teste-sql");
        try {
            Process p = new ProcessBuilder("docker", "exec", container, "/opt/mssql-tools18/bin/sqlcmd", "-C", "-I",
                    "-S", "localhost", "-U", "sa", "-P", System.getProperty("quack.e2e.sqlSenha", "Teste#Caixa2026"),
                    "-d", "HorusPdv", "-W", "-h", "-1", "-Q", "SET NOCOUNT ON; " + comando)
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0 || out.contains("Msg ")) {
                throw new IllegalStateException("SQL falhou: " + comando + "\n" + out);
            }
            return out.trim();
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("docker exec indisponível: " + e.getMessage(), e);
        }
    }

    static JsonNode json(String s) {
        try {
            return JsonFiles.MAPPER.readTree(s);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Executa e devolve o ApiException (ou falha se não houve erro). */
    static ApiException falha(Runnable r) {
        try {
            r.run();
        } catch (ApiException e) {
            return e;
        }
        throw new AssertionError("era esperado um erro da API");
    }

    static Path tempFile(Path dir, String nome, String conteudo) throws IOException {
        Path f = dir.resolve(nome);
        Files.writeString(f, conteudo);
        return f;
    }

    static final String ESTOQUISTA_EMAIL = "estoquista@teste.com";
    static final String ESTOQUISTA_SENHA = "Estoq@12345";

    /** Garante o usuario estoquista de teste (cria pela API como administrador) e devolve um contexto logado nele. */
    AppContext comoEstoquista(Path homeOutro) {
        try {
            AppContext t = novoContexto(homeOutro);
            t.entrar(ESTOQUISTA_EMAIL, ESTOQUISTA_SENHA);
            return t;
        } catch (ApiException semUsuario) {
            Map<String, Object> u = new LinkedHashMap<>();
            u.put("companyId", ctx.session.user().companyId());
            u.put("cpf", cpfValido());
            u.put("name", "Estoquista Teste");
            u.put("email", ESTOQUISTA_EMAIL);
            u.put("phone", "11999990001");
            u.put("role", "estoquista");
            u.put("status", "ativo");
            u.put("password", ESTOQUISTA_SENHA);
            api.send("POST", "/api/Usuario", u, ApiClient.CASH_TIMEOUT);
            AppContext t = novoContexto(homeOutro);
            t.entrar(ESTOQUISTA_EMAIL, ESTOQUISTA_SENHA);
            return t;
        }
    }

    AppContext novoContexto(Path homeOutro) {
        System.setProperty("quack.home", homeOutro.toString());
        AppContext t = new AppContext();
        t.api.setBaseUrl(System.getProperty(URL_PROP));
        System.setProperty("quack.home", homeOutro.getParent() == null ? homeOutro.toString() : homeOutro.toString());
        return t;
    }

    private static String cpfValido() {
        java.util.Random r = new java.util.Random();
        int[] d = new int[11];
        for (int i = 0; i < 9; i++) {
            d[i] = r.nextInt(10);
        }
        for (int j = 9; j < 11; j++) {
            int soma = 0;
            for (int i = 0; i < j; i++) {
                soma += d[i] * (j + 1 - i);
            }
            int resto = (soma * 10) % 11;
            d[j] = resto == 10 ? 0 : resto;
        }
        StringBuilder sb = new StringBuilder();
        for (int x : d) {
            sb.append(x);
        }
        return sb.toString();
    }
}
