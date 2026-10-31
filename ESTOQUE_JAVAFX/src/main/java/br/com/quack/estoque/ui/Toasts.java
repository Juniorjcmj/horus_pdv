package br.com.quack.estoque.ui;

import javafx.animation.PauseTransition;
import javafx.scene.control.Label;
import javafx.stage.Popup;
import javafx.stage.Window;
import javafx.util.Duration;

/** Avisos temporários no canto inferior direito. */
public final class Toasts {
    public enum Kind { SUCCESS, ERROR, INFO }

    private static Popup current;

    private Toasts() {
    }

    public static void show(Window owner, String message, Kind kind) {
        if (owner == null || !owner.isShowing()) {
            return;
        }
        if (current != null) {
            current.hide();
        }
        Label l = new Label(message);
        l.setWrapText(true);
        l.setMaxWidth(420);
        l.getStyleClass().addAll("rg-toast", switch (kind) {
            case SUCCESS -> "rg-toast-success";
            case ERROR -> "rg-toast-error";
            case INFO -> "rg-toast-info";
        });
        javafx.scene.layout.StackPane host = new javafx.scene.layout.StackPane(l);
        host.getStyleClass().add("rg-toast-host");
        host.getStylesheets().setAll(Fx.stylesheets());
        Popup p = new Popup();
        p.getContent().add(host);
        p.setAutoFix(true);
        p.show(owner, owner.getX() + owner.getWidth() - 460, owner.getY() + owner.getHeight() - 120);
        current = p;
        PauseTransition t = new PauseTransition(Duration.millis(3500));
        t.setOnFinished(e -> p.hide());
        t.play();
    }

    public static void success(Window o, String m) {
        show(o, m, Kind.SUCCESS);
    }

    public static void error(Window o, String m) {
        show(o, m, Kind.ERROR);
    }

    public static void info(Window o, String m) {
        show(o, m, Kind.INFO);
    }
}
