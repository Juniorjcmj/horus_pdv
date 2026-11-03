package br.com.quack.estoque.ui;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * Indicador de carregamento sobre uma área da tela (tabela, gráfico): camada translúcida com o círculo girando e uma
 * mensagem, que bloqueia cliques enquanto a consulta roda. Conta chamadas sobrepostas: só some quando todas terminam.
 */
public final class Carregando extends StackPane {
    private final VBox camada;
    private final Label mensagem = new Label();
    private final Node[] travados;
    private final Node conteudo;
    private Node avisoOriginal;
    private int pendentes;

    /**
     * @param conteudo área coberta pelo indicador
     * @param travar   controles desabilitados enquanto carrega (ex.: botões de calcular/gerar)
     */
    public Carregando(Node conteudo, Node... travar) {
        this.travados = travar;
        this.conteudo = conteudo;
        ProgressIndicator roda = new ProgressIndicator();
        roda.setPrefSize(42, 42);
        roda.setMaxSize(42, 42);
        mensagem.getStyleClass().add("rg-carregando-texto");
        camada = new VBox(10, roda, mensagem);
        camada.setAlignment(Pos.CENTER);
        camada.getStyleClass().add("rg-carregando");
        camada.setVisible(false);
        camada.setManaged(true);
        getChildren().addAll(conteudo, camada);
        VBox.setVgrow(this, Priority.ALWAYS);
        setMinHeight(0);
    }

    /** Loader da área de telas da janela principal (definido pela MainView). */
    private static Carregando pagina;
    /** Só aparece se a consulta passar disso: consultas rápidas não piscam a tela. */
    private static final javafx.util.Duration ATRASO = javafx.util.Duration.millis(300);
    private final javafx.animation.PauseTransition espera = new javafx.animation.PauseTransition(ATRASO);
    private boolean comAtraso;

    public static void definirPagina(Carregando c) {
        pagina = c;
    }

    /**
     * Roda a consulta fora da tela mostrando o loader da área de telas (se houver) com a mensagem padrão.
     * Use em toda carga de dados das telas; não use em ações de leitura contínua (bipar no inventário).
     */
    public static <T> void async(java.util.function.Supplier<T> trabalho, java.util.function.Consumer<T> ok,
            java.util.function.Consumer<Throwable> erro) {
        async("Carregando…", trabalho, ok, erro);
    }

    public static <T> void async(String mensagem, java.util.function.Supplier<T> trabalho, java.util.function.Consumer<T> ok,
            java.util.function.Consumer<Throwable> erro) {
        Carregando c = pagina;
        if (c == null) {
            Fx.async(trabalho, ok, erro);
            return;
        }
        c.mostrar(mensagem);
        Fx.async(trabalho, r -> {
            c.ocultar();
            ok.accept(r);
        }, t -> {
            c.ocultar();
            erro.accept(t);
        });
    }

    /** Loader de página: aparece só depois de {@link #ATRASO} e não trava botões (a camada já bloqueia cliques). */
    public Carregando comAtraso() {
        this.comAtraso = true;
        return this;
    }

    /** Mostra o indicador com a mensagem (pode ser chamado de novo para trocar o texto). */
    public void mostrar(String texto) {
        pendentes++;
        mensagem.setText(texto);
        for (Node n : travados) {
            n.setDisable(true);
        }
        if (comAtraso && !camada.isVisible()) {
            espera.setOnFinished(e -> {
                if (pendentes > 0) {
                    exibir();
                }
            });
            espera.playFromStart();
            return;
        }
        exibir();
    }

    private void exibir() {
        camada.setVisible(true);
        camada.toFront();
        // o aviso "Nada para mostrar." da tabela vazia não aparece por trás do círculo
        if (conteudo instanceof javafx.scene.control.TableView<?> t && avisoOriginal == null) {
            avisoOriginal = t.getPlaceholder();
            t.setPlaceholder(new Label(""));
        }
    }

    /** Esconde quando a última consulta pendente terminar. */
    public void ocultar() {
        pendentes = Math.max(0, pendentes - 1);
        if (pendentes == 0) {
            espera.stop();
            camada.setVisible(false);
            if (conteudo instanceof javafx.scene.control.TableView<?> t && avisoOriginal != null) {
                t.setPlaceholder(avisoOriginal);
                avisoOriginal = null;
            }
            for (Node n : travados) {
                n.setDisable(false);
            }
        }
    }

    public boolean ativo() {
        return pendentes > 0;
    }
}
