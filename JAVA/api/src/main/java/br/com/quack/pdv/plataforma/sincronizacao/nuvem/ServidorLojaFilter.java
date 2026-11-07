package br.com.quack.pdv.plataforma.sincronizacao.nuvem;

import br.com.quack.pdv.security.RespostaJson;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Token do servidor da loja (D9): {@code Authorization: LojaServidor <token>}. Resolve o servidor pelo SHA-256 em
 * {@code SyncServidores.TokenHash}; inexistente ou revogado e 401. As rotas de lotes (servidor ativo), carga e
 * ativacao (ativo ou pendente) exigem o token; nas demais rotas
 * de /api/loja-sync o cabecalho e opcional (rotas humanas usam o JWT). Registrado so no perfil nuvem.
 */
public class ServidorLojaFilter extends OncePerRequestFilter {

    public static final String ESQUEMA = "LojaServidor";
    static final String MSG = "Token do servidor invalido ou revogado.";

    private final JdbcTemplate jdbc;
    private final RespostaJson resposta;

    public ServidorLojaFilter(JdbcTemplate jdbc, RespostaJson resposta) {
        this.jdbc = jdbc;
        this.resposta = resposta;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String caminho = request.getRequestURI().substring(request.getContextPath().length()).toLowerCase(Locale.ROOT);
        boolean sincronizacao = caminho.startsWith("/api/loja-sync/");
        String cabecalho = request.getHeader("Authorization");
        boolean temToken = cabecalho != null && cabecalho.startsWith(ESQUEMA + " ");
        boolean lotes = caminho.equals("/api/loja-sync/lotes") || caminho.equals("/api/loja-sync/comandos")
                || caminho.equals("/api/loja-sync/estado") // relato de estado da loja (monitoramento)
                || caminho.equals("/api/loja-sync/comandos/resultados")
                || caminho.startsWith("/api/loja-sync/conferencia/"); // so servidor ativo
        // carga e ativacao rodam com o servidor ainda "pendente"; lotes so com o servidor "ativo"
        boolean exige = lotes || caminho.equals("/api/loja-sync/carga") || caminho.equals("/api/loja-sync/carga/delta")
                || caminho.equals("/api/loja-sync/ativar");
        if (!sincronizacao || (!temToken && !exige)) {
            chain.doFilter(request, response);
            return;
        }
        if (!temToken) {
            resposta.erro(response, 401, MSG);
            return;
        }
        String token = cabecalho.substring(ESQUEMA.length() + 1).trim();
        List<ServidorLoja> achados = token.isEmpty() ? List.of() : jdbc.query(
                "SELECT Id, CompanyId FROM dbo.SyncServidores WHERE TokenHash = ? AND Status IN ("
                        + (lotes ? "N'ativo'" : "N'ativo', N'pendente'") + ")",
                (rs, i) -> new ServidorLoja(rs.getString(1), rs.getString(2)), hash(token));
        if (achados.isEmpty()) {
            resposta.erro(response, 401, MSG);
            return;
        }
        ServidorLoja servidor = achados.getFirst();
        jdbc.update("UPDATE dbo.SyncServidores SET UltimoContato = SYSDATETIMEOFFSET() WHERE Id = ?", servidor.id());
        if (caminho.equals("/api/loja-sync/comandos")) registrarRecursos(servidor, request.getHeader(RecursosLoja.CABECALHO));
        request.setAttribute(ServidorLoja.ATRIBUTO, servidor);
        chain.doFilter(request, response);
    }

    /** O long-poll de comandos e quem anuncia os recursos; sem o cabecalho (jar antigo) a coluna volta a nula. */
    private void registrarRecursos(ServidorLoja servidor, String cabecalho) {
        String recursos = cabecalho == null || cabecalho.isBlank() ? null
                : cabecalho.trim().substring(0, Math.min(cabecalho.trim().length(), 200));
        jdbc.update("UPDATE dbo.SyncServidores SET Recursos = ? WHERE Id = ? AND ISNULL(Recursos, N'') <> ISNULL(?, N'')",
                recursos, servidor.id(), recursos);
    }

    public static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
