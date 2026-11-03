package br.com.quack.estoque.labels;

import br.com.quack.estoque.domain.Catalogo;
import br.com.quack.estoque.domain.DomainException;
import br.com.quack.estoque.domain.Produto;
import br.com.quack.estoque.persistence.JsonFiles;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fila de etiquetas do terminal, persistida em etiquetas.json (%APPDATA%\QuackEstoque): itens e cópias, modelo,
 * impressora, calibração e o último preço impresso por produto (para o filtro "preços alterados").
 */
public final class FilaEtiquetas {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Item {
        public String produtoId;
        public int copias = 1;

        public Item() {
        }

        public Item(String produtoId, int copias) {
            this.produtoId = produtoId;
            this.copias = copias;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Estado {
        public List<Item> fila = new ArrayList<>();
        public String modeloId = LabelModel.R60X40.id();
        public String impressora = "";
        public double dxMm;
        public double dyMm;
        public Map<String, BigDecimal> ultimoPreco = new LinkedHashMap<>();
        /** Tamanho personalizado: largura, altura, colunas, linhas, espaçoX, espaçoY, margemEsq, margemTop. */
        public double[] personalizado;
    }

    private final Path arquivo;
    private Estado estado = new Estado();

    public FilaEtiquetas(Path arquivo) {
        this.arquivo = arquivo;
        if (arquivo != null && Files.exists(arquivo)) {
            try {
                this.estado = JsonFiles.read(arquivo, Estado.class);
            } catch (IOException e) {
                this.estado = new Estado(); // arquivo corrompido: começa vazia
            }
        }
    }

    public synchronized List<Item> itens() {
        return List.copyOf(estado.fila);
    }

    public synchronized int totalEtiquetas() {
        return estado.fila.stream().mapToInt(i -> i.copias).sum();
    }

    /** Soma cópias se o produto já está na fila. */
    public synchronized void adicionar(String produtoId, int copias) {
        if (produtoId == null || produtoId.isBlank() || copias < 1) {
            throw new DomainException("Informe ao menos 1 cópia.");
        }
        for (Item i : estado.fila) {
            if (i.produtoId.equals(produtoId)) {
                i.copias = Math.min(9999, i.copias + copias);
                salvar();
                return;
            }
        }
        estado.fila.add(new Item(produtoId, Math.min(9999, copias)));
        salvar();
    }

    public synchronized void definirCopias(String produtoId, int copias) {
        if (copias < 1) {
            throw new DomainException("Informe ao menos 1 cópia.");
        }
        estado.fila.stream().filter(i -> i.produtoId.equals(produtoId)).forEach(i -> i.copias = Math.min(9999, copias));
        salvar();
    }

    public synchronized void remover(String produtoId) {
        estado.fila.removeIf(i -> i.produtoId.equals(produtoId));
        salvar();
    }

    public synchronized void limpar() {
        estado.fila.clear();
        salvar();
    }

    /**
     * Produtos cujo preço de venda mudou desde a última etiqueta impressa. Produto que nunca teve etiqueta
     * impressa neste terminal não entra (não há "último preço" para comparar).
     */
    public synchronized List<Produto> precosAlterados(Catalogo catalogo) {
        List<Produto> out = new ArrayList<>();
        for (Produto p : catalogo.todos()) {
            BigDecimal ultimo = estado.ultimoPreco.get(p.id());
            if (ultimo != null && ultimo.compareTo(p.preco()) != 0) {
                out.add(p);
            }
        }
        return out;
    }

    public synchronized int adicionarPrecosAlterados(Catalogo catalogo) {
        List<Produto> alterados = precosAlterados(catalogo);
        for (Produto p : alterados) {
            adicionar(p.id(), 1);
        }
        return alterados.size();
    }

    /** Registra o preço impresso (chamado quando a impressão do produto termina bem). */
    public synchronized void registrarImpressao(String produtoId, BigDecimal preco) {
        estado.ultimoPreco.put(produtoId, preco);
        salvar();
    }

    public synchronized BigDecimal ultimoPreco(String produtoId) {
        return estado.ultimoPreco.get(produtoId);
    }

    // ---- preferências do terminal

    public synchronized LabelModel modelo() {
        if ("personalizado".equals(estado.modeloId) && estado.personalizado != null && estado.personalizado.length == 8) {
            double[] c = estado.personalizado;
            try {
                return LabelModel.personalizado(c[0], c[1], (int) c[2], (int) c[3], c[4], c[5], c[6], c[7]);
            } catch (IllegalArgumentException ignored) {
                // cai no padrão
            }
        }
        return LabelModel.padroes().stream().filter(m -> m.id().equals(estado.modeloId)).findFirst()
                .orElse(LabelModel.R60X40);
    }

    public synchronized void definirModelo(LabelModel m) {
        estado.modeloId = m.id();
        if ("personalizado".equals(m.id())) {
            estado.personalizado = new double[] {m.larguraMm(), m.alturaMm(), m.colunas(), m.linhas(), m.espacoXMm(),
                m.espacoYMm(), m.margemEsqMm(), m.margemTopMm()};
        }
        salvar();
    }

    public synchronized String impressora() {
        return estado.impressora == null ? "" : estado.impressora;
    }

    public synchronized void definirImpressora(String nome) {
        estado.impressora = nome == null ? "" : nome;
        salvar();
    }

    public synchronized double dxMm() {
        return estado.dxMm;
    }

    public synchronized double dyMm() {
        return estado.dyMm;
    }

    public synchronized void definirCalibracao(double dxMm, double dyMm) {
        estado.dxMm = Math.max(-20, Math.min(20, dxMm));
        estado.dyMm = Math.max(-20, Math.min(20, dyMm));
        salvar();
    }

    private void salvar() {
        if (arquivo == null) {
            return;
        }
        try {
            JsonFiles.writeAtomic(arquivo, estado);
        } catch (IOException e) {
            // sem permissão de gravar: a fila segue em memória
            System.err.println("Fila de etiquetas não gravada: " + e.getMessage());
        }
    }
}
