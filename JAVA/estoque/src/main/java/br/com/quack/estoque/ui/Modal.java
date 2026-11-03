package br.com.quack.estoque.ui;

import java.util.function.Supplier;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * Modal em cartão (Fx.card) com título, corpo, mensagem de erro e botões Cancelar / confirmar. O botão de confirmar
 * chama {@code aoConfirmar}, que devolve true quando pode fechar.
 */
public final class Modal {
    private final Stage stage;
    private final Label erro = new Label();
    private final Button ok;
    private final Button cancelar;

    private Modal(Window dono, String titulo, String subtitulo, Node corpo, String rotuloOk, Supplier<Boolean> aoConfirmar,
            double largura, double altura) {
        VBox raiz = new VBox(12);
        raiz.setPrefWidth(largura);
        raiz.setMaxWidth(largura);
        Node sub = subtitulo == null ? null : Ui.muted(subtitulo);
        raiz.getChildren().add(Fx.modalHeader(titulo, sub, () -> fechar()));
        Node conteudo = corpo;
        if (altura > 0) {
            ScrollPane sp = new ScrollPane(corpo);
            sp.setFitToWidth(true);
            sp.setPrefViewportHeight(altura);
            // -fx-background é a base que o JavaFX usa para escolher a cor do texto dos controles (opções, caixas de marcar):
            // transparente fazia o texto sair branco sobre o fundo branco do cartão.
            sp.setStyle("-fx-background-color: transparent; -fx-background: -surface;");
            VBox.setVgrow(sp, Priority.ALWAYS);
            conteudo = sp;
        }
        raiz.getChildren().add(conteudo);
        erro.getStyleClass().add("rg-error");
        erro.setWrapText(true);
        erro.setVisible(false);
        erro.setManaged(false);
        raiz.getChildren().add(erro);
        cancelar = Fx.button(rotuloOk == null ? "Fechar" : "Cancelar", null, () -> fechar());
        HBox botoes = new HBox(8);
        botoes.setAlignment(Pos.CENTER_RIGHT);
        if (rotuloOk == null) {
            ok = null;
            botoes.getChildren().add(cancelar);
        } else {
            ok = Fx.button(rotuloOk, "rg-btn-primary", () -> {
                if (aoConfirmar.get()) {
                    fechar();
                }
            });
            ok.setDefaultButton(true);
            botoes.getChildren().addAll(cancelar, ok);
        }
        raiz.getChildren().add(botoes);
        stage = Fx.card(dono, raiz);
    }

    /** Modal de formulário com botão de confirmar. */
    public static Modal formulario(Window dono, String titulo, Node corpo, String rotuloOk, double largura,
            Supplier<Boolean> aoConfirmar) {
        return new Modal(dono, titulo, null, corpo, rotuloOk, aoConfirmar, largura, 0);
    }

    /** Modal só de leitura (botão Fechar), com rolagem se o corpo passar de {@code altura}. */
    public static Modal informativo(Window dono, String titulo, String subtitulo, Node corpo, double largura, double altura) {
        return new Modal(dono, titulo, subtitulo, corpo, null, () -> true, largura, altura);
    }

    /** Formulário com rolagem (corpo alto). */
    public static Modal formularioRolavel(Window dono, String titulo, String subtitulo, Node corpo, String rotuloOk,
            double largura, double altura, Supplier<Boolean> aoConfirmar) {
        return new Modal(dono, titulo, subtitulo, corpo, rotuloOk, aoConfirmar, largura, altura);
    }

    public Modal mostrar() {
        stage.show();
        return this;
    }

    public void fechar() {
        if (!Fx.isBusy(stage)) {
            stage.close();
        }
    }

    public void erro(String mensagem) {
        boolean tem = mensagem != null && !mensagem.isBlank();
        erro.setText(tem ? mensagem : "");
        erro.setVisible(tem);
        erro.setManaged(tem);
    }

    /** Desativa os botões enquanto uma chamada à API está em andamento. */
    public void ocupado(boolean ocupado) {
        Fx.setBusy(stage, ocupado);
        if (ok != null) {
            ok.setDisable(ocupado);
        }
        cancelar.setDisable(ocupado);
    }

    public Stage stage() {
        return stage;
    }

    /** Pergunta de texto (uma linha) com confirmação. */
    public static void pedirTexto(Window dono, String titulo, String rotulo, String valorInicial, String rotuloOk,
            java.util.function.Consumer<String> aoOk) {
        javafx.scene.control.TextField campo = Ui.campo("");
        campo.setText(valorInicial == null ? "" : valorInicial);
        VBox corpo = new VBox(6, Ui.rotulado(rotulo, campo));
        Modal[] m = new Modal[1];
        m[0] = formulario(dono, titulo, corpo, rotuloOk, 440, () -> {
            String t = campo.getText() == null ? "" : campo.getText().trim();
            if (t.isEmpty()) {
                m[0].erro("Preencha o campo.");
                return false;
            }
            aoOk.accept(t);
            return true;
        });
        m[0].mostrar();
        campo.requestFocus();
    }

    /** Confirmação sim/não em cartão. */
    public static void confirmar(Window dono, String titulo, String texto, String rotuloOk, Runnable aoOk) {
        Label t = Ui.muted(texto);
        formulario(dono, titulo, new VBox(t), rotuloOk, 440, () -> {
            aoOk.run();
            return true;
        }).mostrar();
    }

    public static void mensagem(Window dono, String titulo, String texto) {
        informativo(dono, titulo, null, Ui.muted(texto), 440, 0).mostrar();
    }
}
