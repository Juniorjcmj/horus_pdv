package br.com.quack.estoque.analise;

import br.com.quack.estoque.ui.Ui;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/** Cartão de um bloco da análise: título, rodapé com o horário do cálculo e conteúdo que carrega sozinho (esqueleto, vazio, erro). */
public final class BlocoCard extends VBox {
    private final StackPane corpo = new StackPane();
    private final Label rodape = Ui.muted("");

    public BlocoCard(String titulo, String dica, double largura, double altura) {
        super(6);
        getStyleClass().add("rg-card");
        Label t = Ui.texto(titulo, "rg-section-title");
        getChildren().add(t);
        if (dica != null && !dica.isBlank()) {
            Label d = Ui.muted(dica);
            d.getStyleClass().add("rg-chart-dica");
            getChildren().add(d);
        }
        corpo.setMinHeight(altura - 70);
        corpo.setPrefHeight(altura - 70);
        corpo.setAlignment(Pos.CENTER);
        VBox.setVgrow(corpo, Priority.ALWAYS);
        rodape.getStyleClass().add("rg-chart-rodape");
        getChildren().addAll(corpo, rodape);
        setPrefSize(largura, altura);
        setMinWidth(Math.min(largura, 340));
        carregando();
    }

    public void carregando() {
        corpo.getChildren().setAll(Ui.texto("Carregando…", "rg-muted", "rg-bloco-estado"));
        rodape.setText("");
    }

    public void vazio(String mensagem) {
        Label l = Ui.muted(mensagem);
        l.getStyleClass().add("rg-bloco-estado");
        l.setMaxWidth(320);
        l.setAlignment(Pos.CENTER);
        l.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        corpo.getChildren().setAll(l);
    }

    public void erro(String mensagem) {
        Label l = Ui.texto("Não foi possível carregar: " + mensagem, "rg-bloco-erro");
        l.setWrapText(true);
        l.setMaxWidth(320);
        corpo.getChildren().setAll(l);
        rodape.setText("");
    }

    public void mostrar(Node conteudo, String calculadoEm) {
        corpo.getChildren().setAll(conteudo);
        rodape.setText(calculadoEm == null ? "" : calculadoEm);
    }
}
