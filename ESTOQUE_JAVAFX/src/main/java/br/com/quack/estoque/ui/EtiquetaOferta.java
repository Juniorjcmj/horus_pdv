package br.com.quack.estoque.ui;

import br.com.quack.estoque.AppContext;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/** Depois de confirmar uma entrada: oferece colocar os produtos recebidos na fila de etiquetas. */
public final class EtiquetaOferta {
    /** Produto recebido e a quantidade que entrou no estoque. */
    public record Recebido(String produtoId, String nome, BigDecimal quantidade) {
    }

    private EtiquetaOferta() {
    }

    /** Soma por produto (o mesmo produto em dois itens da nota vira uma linha). */
    static Map<String, Recebido> agrupar(List<Recebido> itens) {
        Map<String, Recebido> out = new LinkedHashMap<>();
        for (Recebido r : itens) {
            out.merge(r.produtoId(), r, (a, b) -> new Recebido(a.produtoId(), a.nome(), a.quantidade().add(b.quantidade())));
        }
        return out;
    }

    /** Número de cópias de uma linha: 1 por produto ou a quantidade recebida (arredondada para cima, no mínimo 1). */
    static int copias(Recebido r, boolean pelaQuantidade) {
        if (!pelaQuantidade) {
            return 1;
        }
        return Math.max(1, Math.min(9999, r.quantidade().setScale(0, java.math.RoundingMode.CEILING).intValue()));
    }

    public static void oferecer(Window dono, AppContext ctx, List<Recebido> itens, Runnable irParaEtiquetas) {
        Map<String, Recebido> linhas = agrupar(itens);
        if (linhas.isEmpty()) {
            return;
        }
        ToggleGroup g = new ToggleGroup();
        RadioButton um = new RadioButton("Uma etiqueta por produto (" + linhas.size() + ")");
        RadioButton qtd = new RadioButton("Uma etiqueta por unidade recebida (" + total(linhas, true) + ")");
        RadioButton nao = new RadioButton("Não imprimir etiquetas agora");
        um.setToggleGroup(g);
        qtd.setToggleGroup(g);
        nao.setToggleGroup(g);
        um.setSelected(true);
        VBox corpo = new VBox(10, Ui.muted("A entrada foi confirmada. Deseja colocar os " + linhas.size()
                + " produtos recebidos na fila de etiquetas de gôndola?"), um, qtd, nao);
        Modal.formulario(dono, "Etiquetas da entrada", corpo, "Continuar", 480, () -> {
            if (!nao.isSelected()) {
                boolean pelaQtd = qtd.isSelected();
                for (Recebido r : linhas.values()) {
                    ctx.etiquetas.adicionar(r.produtoId(), copias(r, pelaQtd));
                }
                Toasts.success(dono, linhas.size() + " produtos na fila de etiquetas (" + ctx.etiquetas.totalEtiquetas() + " etiquetas).");
                if (irParaEtiquetas != null) {
                    irParaEtiquetas.run();
                }
            }
            return true;
        }).mostrar();
    }

    private static int total(Map<String, Recebido> linhas, boolean pelaQuantidade) {
        return linhas.values().stream().mapToInt(r -> copias(r, pelaQuantidade)).sum();
    }
}
