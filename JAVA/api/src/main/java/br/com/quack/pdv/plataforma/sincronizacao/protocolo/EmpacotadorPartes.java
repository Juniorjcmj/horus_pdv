package br.com.quack.pdv.plataforma.sincronizacao.protocolo;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Divide linhas em partes de ate maxLinhas linhas ou ~maxBytes (D4: 2.000 linhas / 4 MB). Entrega cada parte ao
 * consumidor assim que a seguinte comeca, para poder marcar a ultima. Uma parte tem pelo menos uma linha.
 */
public final class EmpacotadorPartes {

    public static final int MAX_LINHAS = 2000;
    public static final long MAX_BYTES = 4L * 1024 * 1024;

    private final String loteId;
    private final long de;
    private final long ate;
    private final boolean completo;
    private final int maxLinhas;
    private final long maxBytes;
    private final Consumer<ParteLote> saida;

    private List<BlocoTabela> blocos = new ArrayList<>();
    private BlocoTabela blocoAtual;
    private int linhasAtual;
    private long bytesAtual;
    private ParteLote pendente;
    private int emitidas;

    public EmpacotadorPartes(String loteId, long de, long ate, boolean completo, int maxLinhas, long maxBytes,
                             Consumer<ParteLote> saida) {
        this.loteId = loteId;
        this.de = de;
        this.ate = ate;
        this.completo = completo;
        this.maxLinhas = maxLinhas;
        this.maxBytes = maxBytes;
        this.saida = saida;
    }

    public void adicionarLinha(String tabela, List<ColunaSync> colunas, List<String> valores) {
        reservar(tamanho(valores));
        bloco(tabela, colunas, null).linhas().add(valores);
    }

    public void adicionarExclusao(String tabela, List<ColunaSync> colunasChave, List<String> chave) {
        reservar(tamanho(chave));
        bloco(tabela, null, colunasChave).exclusoes().add(chave);
    }

    /** Nada foi adicionado ainda. */
    public boolean vazio() { return emitidas == 0 && pendente == null && linhasAtual == 0; }

    /** Fecha o lote: entrega a ultima parte (mesmo vazia, se nada foi adicionado). */
    public void concluir() {
        if (linhasAtual > 0) fecharAtual();
        if (pendente == null) pendente = new ParteLote(loteId, null, de, ate, completo, 1, true, List.of());
        saida.accept(pendente.comNumero(++emitidas, true));
        pendente = null;
    }

    private void reservar(long bytes) {
        if (linhasAtual > 0 && (linhasAtual >= maxLinhas || bytesAtual + bytes > maxBytes)) fecharAtual();
        linhasAtual++;
        bytesAtual += bytes;
    }

    private void fecharAtual() {
        if (pendente != null) saida.accept(pendente.comNumero(++emitidas, false));
        pendente = new ParteLote(loteId, null, de, ate, completo, 0, false, blocos);
        blocos = new ArrayList<>();
        blocoAtual = null;
        linhasAtual = 0;
        bytesAtual = 0;
    }

    private BlocoTabela bloco(String tabela, List<ColunaSync> colunas, List<ColunaSync> colunasChave) {
        if (blocoAtual != null && blocoAtual.tabela().equals(tabela)) {
            boolean faltaColunas = colunas != null && blocoAtual.colunas().isEmpty();
            boolean faltaChave = colunasChave != null && blocoAtual.colunasChave().isEmpty();
            if (faltaColunas || faltaChave) {
                blocoAtual = new BlocoTabela(tabela, faltaColunas ? colunas : blocoAtual.colunas(),
                        blocoAtual.linhas(), faltaChave ? colunasChave : blocoAtual.colunasChave(),
                        blocoAtual.exclusoes());
                blocos.set(blocos.size() - 1, blocoAtual);
            }
            return blocoAtual;
        }
        blocoAtual = new BlocoTabela(tabela, colunas == null ? List.of() : colunas, new ArrayList<>(),
                colunasChave == null ? List.of() : colunasChave, new ArrayList<>());
        blocos.add(blocoAtual);
        return blocoAtual;
    }

    private static long tamanho(List<String> valores) {
        long t = 0;
        for (String v : valores) t += 8 + (v == null ? 0 : v.length());
        return t;
    }
}
