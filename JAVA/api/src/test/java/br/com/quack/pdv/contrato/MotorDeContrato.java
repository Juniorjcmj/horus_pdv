package br.com.quack.pdv.contrato;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Motor do teste de contrato: executa cenarios (arquivos JSON) contra uma API (a .NET, para gravar; a Java, para
 * comparar) e compara as respostas por caminho JSON.
 *
 * Cenario: { nome, usuario?, outraSessao?, antes?[{metodo,caminho,corpo?,vezes?,cabecalhos?}],
 *            requisicao{metodo,caminho,corpo?,cabecalhos?,auth?}, volateis?{caminhoJson: regra} }
 *   auth: bearer (padrao quando ha usuario), cookie, nenhum, invalido.
 *   caminho aceita {sessaoAtual} e {outraSessao}.
 *   regras de campo volatil: tipo, id (mesmo prefixo e tamanho), iso, data, texto.
 */
public final class MotorDeContrato {

    public static final ObjectMapper JSON = new ObjectMapper();
    public static final String SENHA = "Senha#Quack2026";
    public static final String[] GRUPOS = {"auth", "sessao", "usuario", "empresa", "tema", "categoria", "fornecedor", "cliente", "modulo-mercado", "treinamento", "admin-empresas", "produto", "movimentacao", "lote", "nfe-import", "entrada-manual", "ordem-compra", "promocao", "inventario", "caixa", "vendas", "cancelamento"};

    private static final Pattern ISO = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})$");
    private static final Pattern DATA = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final Pattern EXPIRES = Pattern.compile("(?i)expires=[^;]+");

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .version(HttpClient.Version.HTTP_1_1).build();
    private final String baseUrl;
    private final Runnable restaurarEstado;
    private java.util.function.Consumer<String> executorSql = sql -> {
        throw new IllegalStateException("passo \"sql\" sem executor configurado");
    };

    public MotorDeContrato(String baseUrl, Runnable restaurarEstado) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.restaurarEstado = restaurarEstado;
    }

    /** Executor dos passos {"sql": "..."} do "antes" (ajuste de estado que nao tem rota, rodado nos dois lados). */
    public MotorDeContrato comExecutorSql(java.util.function.Consumer<String> executor) {
        this.executorSql = executor;
        return this;
    }

    // ---- cenarios ----

    public static List<JsonNode> carregarCenarios(String grupo) throws Exception {
        try (InputStream in = MotorDeContrato.class.getResourceAsStream("/contrato/cenarios/" + grupo + ".json")) {
            if (in == null) throw new IllegalStateException("cenarios/" + grupo + ".json nao encontrado");
            List<JsonNode> lista = new ArrayList<>();
            JSON.readTree(in).forEach(lista::add);
            return lista;
        }
    }

    public static JsonNode carregarGravacao(String grupo, String nome) throws Exception {
        try (InputStream in = MotorDeContrato.class.getResourceAsStream("/contrato/gravacoes/" + grupo + "/" + nome + ".json")) {
            if (in == null) throw new IllegalStateException("gravacao ausente: " + grupo + "/" + nome + " (rode o gravador)");
            return JSON.readTree(in);
        }
    }

    // ---- execucao ----

    public ObjectNode executar(JsonNode cenario) throws Exception {
        restaurarEstado.run();

        String usuario = cenario.hasNonNull("usuario") ? cenario.get("usuario").asText() : null;
        String tokenAtual = null;
        String sessaoAtual = "";
        String outraSessao = "";
        if (usuario != null) {
            Resposta login = enviar("POST", "/api/Auth/login", JSON.createObjectNode().put("email", usuario).put("password", SENHA).toString(), Map.of(), null);
            if (login.status != 200) throw new IllegalStateException("login de " + usuario + " falhou: " + login.corpo);
            tokenAtual = login.cookieDeAutenticacao();
            sessaoAtual = JSON.readTree(login.corpo).path("data").path("sessionId").asText();
            if (cenario.path("outraSessao").asBoolean(false)) {
                Resposta outro = enviar("POST", "/api/Auth/login", JSON.createObjectNode().put("email", usuario).put("password", SENHA).toString(), Map.of(), null);
                outraSessao = JSON.readTree(outro.corpo).path("data").path("sessionId").asText();
            }
        }

        Map<String,String> variaveis = new LinkedHashMap<>();
        for (JsonNode passo : cenario.path("antes")) {
            if (passo.hasNonNull("sql")) {
                executorSql.accept(passo.get("sql").asText());
                continue;
            }
            String tokenPasso = tokenAtual;
            if (passo.hasNonNull("usuario")) {
                Resposta login = enviar("POST", "/api/Auth/login", JSON.createObjectNode().put("email", passo.get("usuario").asText()).put("password", SENHA).toString(), Map.of(), null);
                if (login.status != 200) throw new IllegalStateException("login do passo anterior falhou: " + login.corpo);
                tokenPasso = login.cookieDeAutenticacao();
            }
            int vezes = passo.path("vezes").asInt(1);
            for (int i = 0; i < vezes; i++) {
                String authPasso = passo.path("auth").asText("nenhum").equals("bearer") ? "Bearer " + tokenPasso : null;
                String caminhoPasso = substituir(passo.get("caminho").asText(), variaveis);
                Resposta resultado = enviar(passo.get("metodo").asText(), caminhoPasso, substituirCorpo(corpoComoTexto(passo.get("corpo")), variaveis), cabecalhos(passo.get("cabecalhos")), authPasso);
                if (passo.has("status") && resultado.status != passo.get("status").asInt()) throw new IllegalStateException("passo anterior falhou: " + resultado.status + " " + resultado.corpo);
                if (passo.has("capturar")) {
                    JsonNode corpo = JSON.readTree(resultado.corpo);
                    for (var campos = passo.get("capturar").fields(); campos.hasNext();) {
                        var campo = campos.next();
                        JsonNode valor = corpo;
                        for (String chave : campo.getValue().asText().replace("[", ".").replace("]", "").split("\\.")) {
                            valor = valor.isArray() ? valor.path(Integer.parseInt(chave)) : valor.path(chave);
                        }
                        if (!valor.isTextual()) throw new IllegalStateException("captura ausente: " + campo.getValue());
                        variaveis.put(campo.getKey(), valor.asText());
                    }
                }
            }
        }

        JsonNode req = cenario.get("requisicao");
        String auth = req.path("auth").asText(usuario != null ? "bearer" : "nenhum");
        String caminho = req.get("caminho").asText().replace("{sessaoAtual}", sessaoAtual).replace("{outraSessao}", outraSessao);
        caminho = substituir(caminho, variaveis);
        String authorization = switch (auth) {
            case "bearer" -> "Bearer " + tokenAtual;
            case "invalido" -> "Bearer token.invalido.aqui";
            default -> null;
        };
        Map<String, String> cab = new LinkedHashMap<>(cabecalhos(req.get("cabecalhos")));
        if ("cookie".equals(auth)) cab.put("Cookie", "horuspdv.auth=" + tokenAtual);
        Resposta r = enviar(req.get("metodo").asText(), caminho, substituirCorpo(corpoComoTexto(req.get("corpo")), variaveis), cab, authorization);
        return r.comoGravacao();
    }

    private static String substituir(String caminho, Map<String,String> variaveis) {
        for (var entrada : variaveis.entrySet()) caminho = caminho.replace("{" + entrada.getKey() + "}", entrada.getValue());
        return caminho;
    }

    private static String substituirCorpo(String corpo, Map<String,String> variaveis) {
        return corpo == null ? null : substituir(corpo, variaveis);
    }

    private static Map<String, String> cabecalhos(JsonNode no) {
        Map<String, String> m = new LinkedHashMap<>();
        if (no != null) no.fields().forEachRemaining(e -> m.put(e.getKey(), e.getValue().asText()));
        return m;
    }

    private static String corpoComoTexto(JsonNode corpo) {
        if (corpo == null || corpo.isNull()) return null;
        return corpo.isTextual() ? corpo.asText() : expandir(corpo).toString();
    }

    /**
     * Troca textos "@fixture:arquivo" pelo base64 do arquivo em contrato/fixtures. Listas grandes saem do objeto
     * {"repetir":N,"item":{...}}, que vira uma lista com N copias do item.
     */
    private static JsonNode expandir(JsonNode no) {
        if (no.isTextual() && no.asText().startsWith("@fixture:")) {
            try (InputStream in = MotorDeContrato.class.getResourceAsStream("/contrato/fixtures/" + no.asText().substring(9))) {
                if (in == null) throw new IllegalStateException("fixture ausente: " + no.asText());
                return JSON.getNodeFactory().textNode(java.util.Base64.getEncoder().encodeToString(in.readAllBytes()));
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }
        if (no.isObject()) {
            if (no.has("repetir") && no.has("item") && no.size() == 2) {
                ArrayNode lista = JSON.createArrayNode();
                for (int i = 0; i < no.get("repetir").asInt(); i++) lista.add(expandir(no.get("item")));
                return lista;
            }
            ObjectNode novo = JSON.createObjectNode();
            no.fields().forEachRemaining(e -> novo.set(e.getKey(), expandir(e.getValue())));
            return novo;
        }
        if (no.isArray()) {
            ArrayNode novo = JSON.createArrayNode();
            no.forEach(e -> novo.add(expandir(e)));
            return novo;
        }
        return no;
    }

    record Resposta(int status, java.net.http.HttpHeaders cabecalhos, String corpo) {

        String cookieDeAutenticacao() {
            for (String c : cabecalhos.allValues("set-cookie")) {
                if (c.startsWith("horuspdv.auth=")) {
                    String v = c.substring("horuspdv.auth=".length());
                    int i = v.indexOf(';');
                    return i < 0 ? v : v.substring(0, i);
                }
            }
            return null;
        }

        ObjectNode comoGravacao() throws Exception {
            ObjectNode g = JSON.createObjectNode();
            g.put("status", status);
            ObjectNode h = g.putObject("cabecalhos");
            cabecalhos.firstValue("content-type").ifPresent(v -> h.put("content-type", v.toLowerCase(Locale.ROOT).replace(" ", "")));
            cabecalhos.firstValue("content-disposition").ifPresent(v -> h.put("content-disposition", v));
            cabecalhos.firstValue("retry-after").ifPresent(v -> h.put("retry-after", v));
            ArrayNode cookies = h.putArray("set-cookie");
            for (String c : cabecalhos.allValues("set-cookie")) cookies.add(normalizarCookie(c));
            if (cookies.isEmpty()) h.remove("set-cookie");
            if (corpo == null || corpo.isEmpty()) {
                g.putNull("corpo");
            } else {
                try {
                    g.set("corpo", JSON.readTree(corpo));
                } catch (Exception e) {
                    g.put("corpo", corpo);
                }
            }
            return g;
        }
    }

    /** Set-Cookie sem o valor e sem a data (so nome, atributos e a forma deles). */
    static String normalizarCookie(String c) {
        String semData = EXPIRES.matcher(c).replaceAll("expires=<data>");
        int i = semData.indexOf('=');
        int fim = semData.indexOf(';');
        String nome = semData.substring(0, i);
        return nome + "=<valor>" + (fim < 0 ? "" : semData.substring(fim));
    }

    private Resposta enviar(String metodo, String caminho, String corpo, Map<String, String> cabecalhos, String authorization)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + caminho))
                .method(metodo, corpo == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(corpo, StandardCharsets.UTF_8));
        if (corpo != null) b.header("Content-Type", "application/json");
        b.header("User-Agent", "Mozilla/5.0 Chrome/120 contrato");
        if (authorization != null) b.header("Authorization", authorization);
        cabecalhos.forEach(b::header);
        HttpResponse<byte[]> r = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        String tipo = r.headers().firstValue("content-type").orElse("").toLowerCase(Locale.ROOT);
        String texto = tipo.contains("zip") ? descreverZip(r.body()) : new String(r.body(), StandardCharsets.UTF_8);
        return new Resposta(r.statusCode(), r.headers(), texto);
    }

    /** Resposta ZIP vira JSON {"arquivos":[{nome,tamanho,texto?}]} (texto so de .csv e .xml), em ordem de nome. */
    static String descreverZip(byte[] zip) {
        try (java.util.zip.ZipInputStream in = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
            java.util.TreeMap<String, ObjectNode> arquivos = new java.util.TreeMap<>();
            for (java.util.zip.ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                byte[] dados = in.readAllBytes();
                ObjectNode item = JSON.createObjectNode().put("nome", e.getName()).put("tamanho", dados.length);
                if (e.getName().endsWith(".csv") || e.getName().endsWith(".xml")) {
                    item.put("texto", new String(dados, StandardCharsets.UTF_8));
                }
                arquivos.put(e.getName(), item);
            }
            ObjectNode raiz = JSON.createObjectNode();
            ArrayNode lista = raiz.putArray("arquivos");
            arquivos.values().forEach(lista::add);
            return raiz.toString();
        } catch (java.io.IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    // ---- comparacao ----

    public static List<String> comparar(JsonNode gravada, JsonNode atual, JsonNode cenario) {
        List<String> difs = new ArrayList<>();
        if (gravada.get("status").asInt() != atual.get("status").asInt()) {
            difs.add("status: esperado " + gravada.get("status") + " mas veio " + atual.get("status"));
        }
        Map<String, String> volateis = new LinkedHashMap<>();
        cenario.path("volateis").fields().forEachRemaining(e -> volateis.put(e.getKey(), e.getValue().asText()));

        JsonNode hg = gravada.path("cabecalhos");
        JsonNode ha = atual.path("cabecalhos");
        for (String chave : new TreeSet<>(nomes(hg, ha))) {
            if (!hg.path(chave).equals(ha.path(chave))) {
                difs.add("cabecalho " + chave + ": esperado " + hg.path(chave) + " mas veio " + ha.path(chave));
            }
        }
        if (cenario.path("compararDocumentoOpenApi").asBoolean(false) && atual.path("status").asInt() == 200) {
            if (!atual.path("corpo").path("openapi").asText().startsWith("3.") || !atual.path("corpo").path("paths").isObject()) difs.add("documento OpenAPI 3 invalido");
        } else compararNo(gravada.path("corpo"), atual.path("corpo"), "corpo", volateis, difs);
        return difs;
    }

    private static List<String> nomes(JsonNode a, JsonNode b) {
        List<String> n = new ArrayList<>();
        a.fieldNames().forEachRemaining(n::add);
        b.fieldNames().forEachRemaining(k -> { if (!n.contains(k)) n.add(k); });
        return n;
    }

    private static void compararNo(JsonNode esperado, JsonNode atual, String caminho, Map<String, String> volateis, List<String> difs) {
        String regra = volateis.get(caminho.startsWith("corpo.") ? caminho.substring(6) : caminho.equals("corpo") ? "" : caminho);
        if (regra != null) {
            String erro = conferirRegra(regra, esperado, atual);
            if (erro != null) difs.add(caminho + " (volatil/" + regra + "): " + erro);
            return;
        }
        if (esperado.getNodeType() != atual.getNodeType()) {
            boolean ambosNumeros = esperado.isNumber() && atual.isNumber();
            if (!ambosNumeros) {
                difs.add(caminho + ": tipo esperado " + esperado.getNodeType() + " (" + abreviar(esperado) + ") mas veio "
                        + atual.getNodeType() + " (" + abreviar(atual) + ")");
                return;
            }
        }
        switch (esperado.getNodeType()) {
            case OBJECT -> {
                Iterator<String> it = esperado.fieldNames();
                while (it.hasNext()) {
                    String k = it.next();
                    if (!atual.has(k)) difs.add(caminho + "." + k + ": campo ausente na resposta Java");
                    else compararNo(esperado.get(k), atual.get(k), caminho + "." + k, volateis, difs);
                }
                atual.fieldNames().forEachRemaining(k -> {
                    if (!esperado.has(k)) difs.add(caminho + "." + k + ": campo extra na resposta Java (" + abreviar(atual.get(k)) + ")");
                });
            }
            case ARRAY -> {
                if (esperado.size() != atual.size()) {
                    difs.add(caminho + ": tamanho esperado " + esperado.size() + " mas veio " + atual.size());
                    return;
                }
                for (int i = 0; i < esperado.size(); i++) compararNo(esperado.get(i), atual.get(i), caminho + "[" + i + "]", volateis, difs);
            }
            case NUMBER -> {
                if (esperado.decimalValue().compareTo(atual.decimalValue()) != 0) {
                    difs.add(caminho + ": esperado " + esperado + " mas veio " + atual);
                }
            }
            default -> {
                if (!esperado.equals(atual)) difs.add(caminho + ": esperado " + abreviar(esperado) + " mas veio " + abreviar(atual));
            }
        }
    }

    private static String conferirRegra(String regra, JsonNode esperado, JsonNode atual) {
        if (esperado.getNodeType() != atual.getNodeType()) return "tipo " + esperado.getNodeType() + " x " + atual.getNodeType();
        if (esperado.isNull()) return null;
        String e = esperado.asText();
        String a = atual.asText();
        return switch (regra) {
            case "tipo" -> null;
            case "texto" -> a.isBlank() ? "texto vazio" : null;
            case "banco-sql" -> {
                String banco = "database \"[^\"]+\"";
                if (!Pattern.compile(banco).matcher(e).find() || !Pattern.compile(banco).matcher(a).find()) {
                    yield "mensagem SQL sem nome do banco";
                }
                yield e.replaceAll(banco, "database <banco>").equals(a.replaceAll(banco, "database <banco>"))
                        ? null : "mensagem SQL diferente fora do nome do banco";
            }
            case "iso" -> !ISO.matcher(a).matches() ? "nao e data ISO com offset: " + a
                    : !ISO.matcher(e).matches() ? "gravacao fora do formato ISO: " + e : formaDaFracao(e, a);
            case "iso-json" -> {
                String formato = "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{0,6}[1-9])?(Z|[+-]\\d{2}:\\d{2})$";
                if (!e.matches(formato) || !a.matches(formato)) yield "data fora do formato JSON .NET: " + a;
                yield e.endsWith("Z") != a.endsWith("Z")
                        || (e.endsWith("+00:00") && !a.endsWith("+00:00"))
                        ? "sufixo de fuso diferente: " + a + " x " + e : null;
            }
            case "data" -> DATA.matcher(a).matches() ? null : "nao e yyyy-MM-dd: " + a;
            case "id" -> {
                String guid = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
                if (e.matches(guid) || a.matches(guid)) yield e.matches(guid) && a.matches(guid) ? null : "GUID fora do formato canonico";
                String pe = e.contains("-") ? e.substring(0, e.indexOf('-')) : e;
                String pa = a.contains("-") ? a.substring(0, a.indexOf('-')) : a;
                yield !pe.equals(pa) ? "prefixo " + pa + " em vez de " + pe
                        : e.length() != a.length() ? "tamanho " + a.length() + " em vez de " + e.length() : null;
            }
            default -> "regra desconhecida " + regra;
        };
    }

    /** Fracao com 7 casas fixas ("o") ou aparada ("System.Text.Json"): o estilo tem de ser o mesmo. */
    private static String formaDaFracao(String e, String a) {
        boolean sete = e.matches(".*\\.\\d{7}(Z|[+-]\\d{2}:\\d{2})$");
        boolean seteA = a.matches(".*\\.\\d{7}(Z|[+-]\\d{2}:\\d{2})$");
        if (sete && !seteA) return "esperava 7 casas de fracao: " + a;
        boolean zFinal = e.endsWith("Z");
        if (zFinal != a.endsWith("Z")) return "sufixo de fuso diferente: " + a + " x " + e;
        if (e.endsWith("+00:00") && !a.endsWith("+00:00")) return "esperava +00:00: " + a;
        return null;
    }

    private static String abreviar(JsonNode n) {
        String s = n.toString();
        return s.length() > 160 ? s.substring(0, 160) + "..." : s;
    }

    // ---- gravacao em disco ----

    public static void gravar(Path raiz, String grupo, String nome, JsonNode gravacao) throws Exception {
        Path destino = raiz.resolve(grupo).resolve(nome + ".json");
        Files.createDirectories(destino.getParent());
        Files.writeString(destino, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(gravacao) + System.lineSeparator(),
                StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unused")
    private static JsonNodeType tipo(JsonNode n) {
        return n.getNodeType();
    }
}
