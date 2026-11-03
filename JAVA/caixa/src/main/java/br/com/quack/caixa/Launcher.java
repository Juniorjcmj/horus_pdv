package br.com.quack.caixa;

/** Ponto de entrada sem herdar de Application (necessário para fat-jar no classpath). */
public final class Launcher {
    private Launcher() {
    }

    public static void main(String[] args) {
        App.main(args);
    }
}
