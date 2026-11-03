package br.com.quack.estoque.ui;

import br.com.quack.estoque.AppContext;
import com.fasterxml.jackson.databind.JsonNode;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;

/** Início: alertas do dia (crítico, vencendo, preços alterados, inventário aberto) e atalhos das tarefas mais comuns. */
public class InicioView extends Tela {
    private final FlowPane cartoes = new FlowPane(12, 12);

    public InicioView(AppContext ctx, Navegador nav) {
        super(ctx, nav);
        getChildren().add(Ui.cabecalho("Início", "Olá, " + ctx.session.user().name() + ". Veja o que precisa de atenção hoje."));
        getChildren().add(cartoes);
        getChildren().add(Ui.texto("Tarefas rápidas", "rg-section-title"));
        getChildren().add(Ui.barra(Ui.primario("Nova entrada por NF-e (F3)", nav::novaEntradaNfe), Ui.botao("Ajuste de estoque (F4)", () -> nav.ir("ajustes")),
                Ui.botao("Contar inventário (F6)", () -> nav.ir("inventario-contar")), Ui.botao("Etiquetas (F8)", () -> nav.ir("etiquetas")),
                Ui.botao("Produtos (F2)", () -> nav.ir("produtos"))));
    }

    private VBox cartao(String valor, String rotulo, String estilo, String destino) {
        Label v = Ui.texto(valor, "kpi-value", "rg-stat-value");
        Label r = Ui.texto(rotulo.toUpperCase(java.util.Locale.ROOT), "kpi-label", "rg-stat-label");
        r.setWrapText(true);
        VBox c = new VBox(2, v, r);
        c.getStyleClass().addAll("rg-stat", estilo);
        c.setOnMouseClicked(e -> nav.ir(destino));
        return c;
    }

    @Override
    public void ativar() {
        rodar(() -> {
            ctx.garantirCatalogo(false);
            int critico = 0;
            int vencendo = 0;
            String inventario = "nenhum";
            try {
                critico = ctx.relatorios.critico().size();
            } catch (RuntimeException ignored) {
                critico = -1;
            }
            try {
                vencendo = ctx.relatorios.validade(7).size();
            } catch (RuntimeException ignored) {
                vencendo = -1;
            }
            try {
                JsonNode abertos = ctx.inventarios.listar("em_contagem");
                if (abertos != null && abertos.size() > 0) {
                    inventario = "#" + abertos.get(0).path("numero").asInt() + (abertos.size() > 1 ? " +" + (abertos.size() - 1) : "");
                }
            } catch (RuntimeException ignored) {
                inventario = "?";
            }
            return new Object[] {critico, vencendo, inventario, ctx.etiquetas.precosAlterados(ctx.catalogo).size()};
        }, r -> {
            cartoes.getChildren().setAll(
                    cartao(txt((int) r[0]), "produtos com estoque crítico (zerado ou abaixo do mínimo)", (int) r[0] > 0 ? "rg-stat-danger" : "rg-stat-ok", "relatorios"),
                    cartao(txt((int) r[1]), "lotes vencidos ou vencendo em 7 dias", (int) r[1] > 0 ? "rg-stat-danger" : "rg-stat-ok", "relatorios"),
                    cartao(String.valueOf(r[3]), "produtos com preço alterado sem etiqueta nova", (int) r[3] > 0 ? "rg-stat-danger" : "rg-stat-ok", "etiquetas"),
                    cartao(String.valueOf(r[2]), "inventário em contagem", "rg-stat-ok", "inventario"),
                    cartao(String.valueOf(ctx.etiquetas.totalEtiquetas()), "etiquetas na fila de impressão", "rg-stat-ok", "etiquetas"),
                    cartao(String.valueOf(ctx.catalogo.todos().size()), "produtos no catálogo", "rg-stat-ok", "produtos"));
        });
    }

    private static String txt(int n) {
        return n < 0 ? "—" : String.valueOf(n);
    }
}
