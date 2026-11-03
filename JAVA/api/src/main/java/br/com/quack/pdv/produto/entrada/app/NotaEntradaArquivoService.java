package br.com.quack.pdv.produto.entrada.app;

import static br.com.quack.pdv.produto.entrada.app.EntradaSuporte.digitos;
import static br.com.quack.pdv.produto.entrada.app.EntradaSuporte.erro;
import static br.com.quack.pdv.produto.entrada.app.EntradaSuporte.hash;
import static br.com.quack.pdv.produto.entrada.app.EntradaSuporte.vazio;

import br.com.quack.pdv.produto.entrada.web.NfeImportModels.NotaDetalhe;
import br.com.quack.pdv.produto.entrada.web.NfeImportModels.NotaPagina;
import br.com.quack.pdv.produto.entrada.web.NfeImportModels.NotaResumo;
import br.com.quack.pdv.produto.entrada.web.NfeImportRequests.ConfirmRequest;
import br.com.quack.pdv.produto.entrada.web.NfeImportRequests.ItemInput;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class NotaEntradaArquivoService {
    public record Documento(
            String identidade,
            String chave,
            int modelo,
            String numero,
            String serie,
            String emissao,
            BigDecimal total,
            String origem,
            byte[] xml) {}

    public record Salva(String id, JsonNode entrada) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public NotaEntradaArquivoService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public Documento preparar(ConfirmRequest r) {
        var d = r.documento;
        byte[] xml =
                d != null && !vazio(d.xmlBase64)
                        ? NfeXmlParser.decodificarBase64(
                                d.xmlBase64,
                                "Conteúdo do XML inválido. Selecione o arquivo novamente.")
                        : null;
        var p = xml == null ? null : NfeXmlParser.parse(xml);
        if (p != null && !p.emitente().cnpj().equals(digitos(r.fornecedor.cnpj)))
            throw erro("O fornecedor da entrada deve ser o emitente do XML.");
        String chave = p == null ? (d == null ? null : d.chaveAcesso) : p.chaveAcesso();
        if (!vazio(chave)) {
            chave = NfeAccessKey.validateInvoiceKey(chave);
            if (!chave.substring(6, 20).equals(digitos(r.fornecedor.cnpj)))
                throw erro("O CNPJ da chave não corresponde ao fornecedor da entrada.");
            if (p != null
                    && (Integer.parseInt(chave.substring(20, 22)) != p.modelo()
                            || !mesmoNumero(p.serie(), chave.substring(22, 25))
                            || !mesmoNumero(p.numeroNota(), chave.substring(25, 34))))
                throw erro("Modelo, número ou série do XML não correspondem à chave de acesso.");
            if (d != null && !vazio(d.chaveAcesso) && !digitos(d.chaveAcesso).equals(chave))
                throw erro("A chave informada não corresponde ao XML.");
        }
        if (d == null)
            throw erro(
                    "Seu aplicativo está desatualizado e não enviou os dados da nota. Recarregue a"
                        + " página (Ctrl+F5) ou reinicie o Quack PDV e confirme a entrada de"
                        + " novo.");
        if (xml == null && vazio(chave))
            throw erro("Informe o XML ou a chave do cupom antes de confirmar a entrada.");
        return new Documento(
                hash(chave),
                chave,
                p == null ? Integer.parseInt(chave.substring(20, 22)) : p.modelo(),
                p == null ? Long.toString(Long.parseLong(chave.substring(25, 34))) : p.numeroNota(),
                p == null ? Integer.toString(Integer.parseInt(chave.substring(22, 25))) : p.serie(),
                p == null ? null : p.dataEmissao(),
                p == null ? null : p.valorNota(),
                xml == null ? "digitada" : "xml",
                xml);
    }

    private static boolean mesmoNumero(String a, String b) {
        try {
            return Long.parseLong(a) == Long.parseLong(b);
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    public void travar(String recurso, String mensagem) {
        Integer codigo =
                jdbc.execute(
                        (org.springframework.jdbc.core.ConnectionCallback<Integer>)
                                con -> {
                                    if (con.getAutoCommit())
                                        throw new IllegalStateException(
                                                "A reserva de entrada exige uma transação ativa.");
                                    // O driver abre a transação implicitamente apenas no primeiro
                                    // acesso a tabela. O applock pode ser
                                    // a primeira instrução; inicia o escopo SQL que o
                                    // TransactionTemplate confirma ou desfaz.
                                    try (var inicio = con.createStatement()) {
                                        inicio.execute("IF @@TRANCOUNT=0 BEGIN TRANSACTION;");
                                    }
                                    try (var stmt =
                                            con.prepareCall(
                                                    "{? = call sys.sp_getapplock(?, ?, ?, ?)}")) {
                                        stmt.registerOutParameter(1, java.sql.Types.INTEGER);
                                        stmt.setNString(2, recurso);
                                        stmt.setString(3, "Exclusive");
                                        stmt.setString(4, "Transaction");
                                        stmt.setInt(5, 15000);
                                        stmt.execute();
                                        return stmt.getInt(1);
                                    }
                                });
        if (codigo == null || codigo < 0) throw erro(mensagem);
    }

    public void reservar(String empresa, Documento d) {
        travar(
                "nota-entrada:" + empresa + ":" + d.identidade(),
                "Outra entrada desta nota está em processamento. Tente novamente.");
        jdbc.update(
                "DELETE FROM NotasEntradaArquivo WHERE CompanyId=? AND Identidade=? AND"
                    + " Estornada=1",
                empresa,
                d.identidade());
        if (anterior(empresa, null, d.identidade()).isPresent())
            throw erro(
                    "Esta nota já deu entrada no sistema. Consulte Notas de entrada para ver os"
                        + " itens; o estoque não foi alterado.");
    }

    public Optional<Salva> anterior(String empresa, String operacao, String identidade) {
        if (operacao != null) {
            var porOperacao = buscar(empresa, "OperacaoManualId", operacao);
            if (porOperacao.isPresent()) return porOperacao;
        }
        return buscar(empresa, "Identidade", identidade);
    }

    private Optional<Salva> buscar(String empresa, String coluna, String valor) {
        return jdbc
                .query(
                        "SELECT Id,EntradaJson FROM NotasEntradaArquivo WHERE CompanyId=? AND "
                                + coluna
                                + "=?",
                        (rs, n) -> new Salva(rs.getString(1), lerJson(rs.getString(2))),
                        empresa,
                        valor)
                .stream()
                .findFirst();
    }

    public String salvar(
            String empresa,
            ConfirmRequest r,
            Documento d,
            String usuarioId,
            String usuarioNome,
            Object entrada,
            byte[] comprovante,
            String nome,
            String tipo,
            BigDecimal valorEntrada,
            String operacao) {
        String id = "ne-" + UUID.randomUUID().toString().replace("-", "");
        Map<String, Object> padrao = new LinkedHashMap<>();
        padrao.put("fornecedor", r.fornecedor.paraMapa());
        padrao.put("itens", r.itens.stream().map(ItemInput::paraMapa).toList());
        BigDecimal total =
                valorEntrada == null
                        ? r.itens.stream()
                                .map(
                                        i ->
                                                br.com.quack.pdv.shared.Dinheiro.ler(i.quantidade)
                                                        .multiply(
                                                                br.com.quack.pdv.shared.Dinheiro
                                                                        .ler(i.precoCusto)))
                                .reduce(BigDecimal.ZERO, BigDecimal::add)
                        : valorEntrada;
        jdbc.update(
                """
INSERT INTO NotasEntradaArquivo
(Id,CompanyId,Identidade,ChaveAcesso,Modelo,NumeroNota,Serie,FornecedorNome,FornecedorCnpj,DataEmissao,ValorNota,
 ValorEntrada,QuantidadeItens,Origem,XmlOriginal,EntradaJson,UsuarioId,UsuarioNome,ComprovanteOriginal,ComprovanteNome,ComprovanteTipo,OperacaoManualId)
VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
""",
                id,
                empresa,
                d.identidade(),
                d.chave(),
                d.modelo(),
                d.numero(),
                d.serie(),
                r.fornecedor.companyName,
                digitos(r.fornecedor.cnpj),
                d.emissao(),
                d.total(),
                total,
                r.itens.size(),
                d.origem(),
                new org.springframework.jdbc.core.SqlParameterValue(
                        java.sql.Types.VARBINARY, d.xml()),
                JsonWeb.escrever(entrada == null ? padrao : entrada),
                usuarioId,
                usuarioNome,
                new org.springframework.jdbc.core.SqlParameterValue(
                        java.sql.Types.VARBINARY, comprovante),
                nome,
                tipo,
                operacao);
        return id;
    }

    JsonNode lerJson(String json) {
        try {
            return mapper.readTree(json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("JSON de entrada inválido", ex);
        }
    }

    private static final String COLUNAS =
            "Id,ChaveAcesso,Modelo,NumeroNota,Serie,FornecedorNome,FornecedorCnpj,DataEmissao,ValorNota,ValorEntrada,QuantidadeItens,Origem,CASE"
                + " WHEN XmlOriginal IS NULL THEN CAST(0 AS BIT) ELSE CAST(1 AS BIT) END AS"
                + " TemXml,CriadaEm,UsuarioNome,CASE WHEN ComprovanteOriginal IS NULL THEN CAST(0"
                + " AS BIT) ELSE CAST(1 AS BIT) END AS"
                + " TemComprovante,ComprovanteNome,ISNULL(Estornada,0) AS"
                + " Estornada,EstornadaEm,EstornadaPorUsuarioNome,EstornoJustificativa";

    public NotaPagina listar(String empresa, String busca, int pagina, int tamanho) {
        pagina = Math.clamp(pagina, 1, 1000000);
        tamanho = Math.clamp(tamanho, 1, 100);
        String termo = busca == null ? "" : busca.trim();
        if (termo.length() > 100) termo = termo.substring(0, 100);
        termo =
                "%"
                        + termo.replace("~", "~~")
                                .replace("%", "~%")
                                .replace("_", "~_")
                                .replace("[", "~[")
                        + "%";
        String where =
                "CompanyId=? AND (?='%%' OR FornecedorNome LIKE ? ESCAPE '~' OR FornecedorCnpj LIKE"
                    + " ? ESCAPE '~' OR ChaveAcesso LIKE ? ESCAPE '~' OR NumeroNota LIKE ? ESCAPE"
                    + " '~')";
        Integer total =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM NotasEntradaArquivo WHERE " + where,
                        Integer.class,
                        empresa,
                        termo,
                        termo,
                        termo,
                        termo,
                        termo);
        var notas =
                jdbc.query(
                        "SELECT "
                                + COLUNAS
                                + " FROM NotasEntradaArquivo WHERE "
                                + where
                                + " ORDER BY CriadaEm DESC,Id DESC OFFSET ? ROWS FETCH NEXT ? ROWS"
                                + " ONLY",
                        (rs, n) -> resumo(rs),
                        empresa,
                        termo,
                        termo,
                        termo,
                        termo,
                        termo,
                        (pagina - 1) * tamanho,
                        tamanho);
        return new NotaPagina(notas, total == null ? 0 : total, pagina, tamanho);
    }

    public Optional<NotaDetalhe> obter(String empresa, String id) {
        return jdbc
                .query(
                        "SELECT "
                                + COLUNAS
                                + ",EntradaJson FROM NotasEntradaArquivo WHERE CompanyId=? AND"
                                + " Id=?",
                        (rs, n) ->
                                new NotaDetalhe(resumo(rs), lerJson(rs.getString("EntradaJson"))),
                        empresa,
                        id)
                .stream()
                .findFirst();
    }

    public byte[] xml(String empresa, String id) {
        return jdbc
                .query(
                        "SELECT XmlOriginal FROM NotasEntradaArquivo WHERE CompanyId=? AND Id=?",
                        (rs, n) -> rs.getBytes(1),
                        empresa,
                        id)
                .stream()
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    public record Arquivo(byte[] bytes, String nome, String tipo) {}

    public Optional<Arquivo> comprovante(String empresa, String id) {
        return jdbc
                .query(
                        "SELECT ComprovanteOriginal,ComprovanteNome,ComprovanteTipo FROM"
                            + " NotasEntradaArquivo WHERE CompanyId=? AND Id=? AND"
                            + " ComprovanteOriginal IS NOT NULL",
                        (rs, n) -> new Arquivo(rs.getBytes(1), rs.getString(2), rs.getString(3)),
                        empresa,
                        id)
                .stream()
                .findFirst();
    }

    private static NotaResumo resumo(ResultSet r) throws SQLException {
        return new NotaResumo(
                r.getString("Id"),
                r.getString("ChaveAcesso"),
                r.getInt("Modelo"),
                r.getString("NumeroNota"),
                r.getString("Serie"),
                r.getString("FornecedorNome"),
                r.getString("FornecedorCnpj"),
                r.getString("DataEmissao"),
                r.getBigDecimal("ValorNota"),
                r.getBigDecimal("ValorEntrada"),
                r.getInt("QuantidadeItens"),
                r.getString("Origem"),
                r.getBoolean("TemXml"),
                dataJson(r, "CriadaEm"),
                r.getString("UsuarioNome"),
                r.getBoolean("TemComprovante"),
                r.getString("ComprovanteNome"),
                r.getBoolean("Estornada"),
                dataJson(r, "EstornadaEm"),
                r.getString("EstornadaPorUsuarioNome"),
                r.getString("EstornoJustificativa"));
    }

    private static String dataJson(ResultSet r, String coluna) throws SQLException {
        var t = r.getTimestamp(coluna);
        if (t == null) return null;
        var instante = t.toLocalDateTime().toInstant(ZoneOffset.UTC);
        return t.getNanos() == 0
                ? instante.toString()
                : br.com.quack.pdv.shared.FormatoO.utcZ(instante);
    }

    @Transactional
    public void anexar(String empresa, String id, String base64) {
        if (vazio(base64)) throw erro("Selecione o arquivo XML da nota.");
        byte[] xml =
                NfeXmlParser.decodificarBase64(
                        base64, "Conteúdo do XML inválido. Selecione o arquivo novamente.");
        var p = NfeXmlParser.parse(xml);
        String chave = NfeAccessKey.validateInvoiceKey(p.chaveAcesso()), identidade = hash(chave);
        travar(
                "nota-entrada:" + empresa + ":" + identidade,
                "Outra operação com esta nota está em andamento. Tente novamente.");
        var nota = obter(empresa, id).orElseThrow(() -> erro("Entrada não encontrada."));
        if (nota.nota().estornada())
            throw erro("Esta entrada foi estornada; não é possível anexar o XML.");
        if (!"sem-documento".equals(nota.nota().origem()))
            throw erro("Esta entrada já possui documento fiscal vinculado.");
        if (!nota.nota().fornecedorCnpj().equals(p.emitente().cnpj()))
            throw erro("O emitente do XML não é o fornecedor desta entrada.");
        Integer n =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM NotasEntradaArquivo WHERE CompanyId=? AND"
                            + " Identidade=? AND Id<>? AND ISNULL(Estornada,0)=0",
                        Integer.class,
                        empresa,
                        identidade,
                        id);
        if (n != null && n > 0) throw erro("Esta nota já está vinculada a outra entrada.");
        jdbc.update(
                "UPDATE NotasEntradaArquivo SET"
                    + " Identidade=?,ChaveAcesso=?,Modelo=?,NumeroNota=?,Serie=?,DataEmissao=?,ValorNota=?,Origem='xml',XmlOriginal=?"
                    + " WHERE CompanyId=? AND Id=?",
                identidade,
                chave,
                p.modelo(),
                p.numeroNota(),
                p.serie(),
                p.dataEmissao(),
                p.valorNota(),
                xml,
                empresa,
                id);
    }

    public boolean marcarEstornada(
            String empresa, String id, String usuarioId, String usuarioNome, String justificativa) {
        return jdbc.update(
                        "UPDATE NotasEntradaArquivo SET"
                            + " Estornada=1,EstornadaEm=SYSUTCDATETIME(),EstornadaPorUsuarioId=?,EstornadaPorUsuarioNome=?,EstornoJustificativa=?"
                            + " WHERE CompanyId=? AND Id=? AND ISNULL(Estornada,0)=0",
                        usuarioId,
                        usuarioNome,
                        justificativa,
                        empresa,
                        id)
                > 0;
    }

    public byte[] exportar(String empresa, int ano, int mes) {
        if (ano < 2000 || ano > 2100 || mes < 1 || mes > 12) throw erro("Mês inválido.");
        var inicio = java.time.OffsetDateTime.of(ano, mes, 1, 0, 0, 0, 0, ZoneOffset.ofHours(-3));
        var linhas =
                jdbc.query(
                        """
SELECT Id,SWITCHOFFSET(CAST(CriadaEm AS datetimeoffset),'-03:00') AS DataEntrada,Origem,Modelo,NumeroNota,Serie,ChaveAcesso,
FornecedorNome,FornecedorCnpj,DataEmissao,ValorNota,ValorEntrada,ISNULL(Estornada,0) AS Estornada,XmlOriginal
FROM NotasEntradaArquivo WHERE CompanyId=? AND CriadaEm>=? AND CriadaEm<? ORDER BY CriadaEm
""",
                        (rs, n) ->
                                new Exportacao(
                                        rs.getString("Id"),
                                        rs.getObject("DataEntrada", java.time.OffsetDateTime.class),
                                        rs.getString("Origem"),
                                        rs.getInt("Modelo"),
                                        rs.getString("NumeroNota"),
                                        rs.getString("Serie"),
                                        rs.getString("ChaveAcesso"),
                                        rs.getString("FornecedorNome"),
                                        rs.getString("FornecedorCnpj"),
                                        rs.getString("DataEmissao"),
                                        rs.getBigDecimal("ValorNota"),
                                        rs.getBigDecimal("ValorEntrada"),
                                        rs.getBoolean("Estornada"),
                                        rs.getBytes("XmlOriginal")),
                        empresa,
                        inicio,
                        inicio.plusMonths(1));
        try (var buffer = new java.io.ByteArrayOutputStream()) {
            try (var zip = new java.util.zip.ZipOutputStream(buffer)) {
                StringBuilder csv =
                        new StringBuilder(
                                "Data entrada;Tipo;Numero;Serie;Chave de"
                                    + " acesso;Fornecedor;CNPJ;Emissao;Valor da nota;Custo da"
                                    + " entrada;Situacao;XML no pacote\r\n");
                Set<String> nomes = new HashSet<>();
                for (var l : linhas) {
                    boolean incluir = l.xml != null && !l.estornada;
                    if (incluir) {
                        String nome = "xml/" + (vazio(l.chave) ? l.id : l.chave) + ".xml";
                        if (nomes.add(nome.toLowerCase(Locale.ROOT))) {
                            zip.putNextEntry(new java.util.zip.ZipEntry(nome));
                            zip.write(l.xml);
                            zip.closeEntry();
                        }
                    }
                    String tipo =
                            "manual".equals(l.origem)
                                    ? "Entrada manual"
                                    : l.modelo == 65
                                            ? "NFC-e"
                                            : l.modelo == 55 ? "NF-e" : "Sem documento";
                    csv.append(
                                    String.join(
                                            ";",
                                            celula(
                                                    l.data.format(
                                                            java.time.format.DateTimeFormatter
                                                                    .ofPattern(
                                                                            "dd/MM/yyyy HH:mm"))),
                                            celula(tipo),
                                            celula(l.numero),
                                            celula(l.serie),
                                            celula(l.chave == null ? "" : "'" + l.chave),
                                            celula(l.fornecedor),
                                            celula(l.cnpj),
                                            celula(l.emissao),
                                            moeda(l.total),
                                            moeda(l.entrada),
                                            celula(l.estornada ? "Estornada" : "Ativa"),
                                            celula(incluir ? "Sim" : "Nao")))
                            .append("\r\n");
                }
                zip.putNextEntry(new java.util.zip.ZipEntry("resumo.csv"));
                zip.write(new byte[] {(byte) 239, (byte) 187, (byte) 191});
                zip.write(csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            return buffer.toByteArray();
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }

    private record Exportacao(
            String id,
            java.time.OffsetDateTime data,
            String origem,
            int modelo,
            String numero,
            String serie,
            String chave,
            String fornecedor,
            String cnpj,
            String emissao,
            BigDecimal total,
            BigDecimal entrada,
            boolean estornada,
            byte[] xml) {}

    private static String celula(String s) {
        return "\"" + (s == null ? "" : s).replace("\"", "\"\"") + "\"";
    }

    private static String moeda(BigDecimal n) {
        return n == null
                ? ""
                : n.setScale(2, java.math.RoundingMode.HALF_EVEN).toPlainString().replace('.', ',');
    }
}
