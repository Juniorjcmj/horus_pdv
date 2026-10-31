package br.com.quack.estoque.service;

import br.com.quack.estoque.domain.DomainException;
import br.com.quack.estoque.labels.LabelData;
import br.com.quack.estoque.labels.LabelModel;
import br.com.quack.estoque.labels.LabelPrintLayout;
import br.com.quack.estoque.labels.LabelRenderer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.print.PageLayout;
import javafx.print.PageOrientation;
import javafx.print.Paper;
import javafx.print.Printer;
import javafx.print.PrinterJob;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.shape.Rectangle;
import javafx.scene.transform.Scale;
import javafx.scene.transform.Translate;

/**
 * Impressão pelo driver do Windows, sempre em segundo plano (um trabalho por vez) para um driver lento ou parado não
 * congelar a tela. A técnica de ajuste à largura imprimível ({@link #fitScale}), divisão em páginas
 * ({@link #pageRanges}) e posicionamento ({@link #showPage}) vem do ReceiptPrinter do Quack Caixa.
 */
public final class PrintService {
    /** Diferença máxima (mm somados em largura e altura) para aceitar um papel do driver como o da etiqueta. */
    static final double TOLERANCIA_PAPEL_MM = 3.0;

    private static final ExecutorService PRINT_QUEUE = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "quack-impressao");
        t.setDaemon(true);
        return t;
    });

    private PrintService() {
    }

    // =====================================================================
    // Impressoras
    // =====================================================================

    public static List<String> impressoras() {
        List<String> out = new ArrayList<>();
        try {
            for (Printer p : Printer.getAllPrinters()) {
                out.add(p.getName());
            }
        } catch (RuntimeException e) {
            // sem subsistema de impressão: lista vazia
        }
        return out;
    }

    public static String impressoraPadrao() {
        try {
            Printer p = Printer.getDefaultPrinter();
            return p == null ? "" : p.getName();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static Printer achar(String nome) {
        if (nome != null && !nome.isBlank()) {
            for (Printer p : Printer.getAllPrinters()) {
                if (p.getName().equals(nome)) {
                    return p;
                }
            }
            throw new DomainException("A impressora \"" + nome + "\" não está disponível. Escolha outra na lista.");
        }
        Printer p = Printer.getDefaultPrinter();
        if (p == null) {
            throw new DomainException("Nenhuma impressora disponível.");
        }
        return p;
    }

    /** Papel da lista do driver mais próximo de largura x altura (mm), ou null se nenhum chegar perto. */
    public static Paper papelMaisProximo(Collection<Paper> papeis, double larguraMm, double alturaMm) {
        Paper melhor = null;
        double menor = Double.MAX_VALUE;
        for (Paper p : papeis) {
            double d = distanciaMm(p.getWidth() / LabelModel.PT_POR_MM, p.getHeight() / LabelModel.PT_POR_MM, larguraMm,
                    alturaMm);
            if (d < menor) {
                menor = d;
                melhor = p;
            }
        }
        return menor <= TOLERANCIA_PAPEL_MM ? melhor : null;
    }

    /** Distância entre dois papéis em mm, aceitando a rotação (retrato x paisagem). */
    static double distanciaMm(double w1, double h1, double w2, double h2) {
        return Math.min(Math.abs(w1 - w2) + Math.abs(h1 - h2), Math.abs(w1 - h2) + Math.abs(h1 - w2));
    }

    // =====================================================================
    // Etiquetas
    // =====================================================================

    /**
     * Imprime as etiquetas (já com as cópias repetidas) no modelo e na impressora escolhidos. Uma etiqueta por página
     * no rolo; grade na folha. {@code done} recebe null se foi tudo para a impressora, ou a mensagem de erro (na FX
     * thread).
     */
    public static void imprimirEtiquetas(LabelModel modelo, List<LabelData> etiquetas, String impressora, double dxMm,
            double dyMm, Consumer<String> done) {
        PRINT_QUEUE.submit(() -> {
            String erro = null;
            try {
                imprimirEtiquetasBloqueante(modelo, etiquetas, impressora, dxMm, dyMm);
            } catch (RuntimeException e) {
                erro = e instanceof DomainException ? e.getMessage()
                        : "Falha ao imprimir: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
            String resultado = erro;
            Platform.runLater(() -> done.accept(resultado));
        });
    }

    private static void imprimirEtiquetasBloqueante(LabelModel modelo, List<LabelData> etiquetas, String impressora,
            double dxMm, double dyMm) {
        if (etiquetas.isEmpty()) {
            throw new DomainException("A fila de etiquetas está vazia.");
        }
        Printer printer = achar(impressora);
        double[] papelMm = LabelPrintLayout.papelMm(modelo);
        Paper papel = papelMaisProximo(printer.getPrinterAttributes().getSupportedPapers(), papelMm[0], papelMm[1]);
        if (papel == null) {
            papel = printer.getDefaultPageLayout().getPaper();
        }
        PageLayout layout = printer.createPageLayout(papel, PageOrientation.PORTRAIT, Printer.MarginType.HARDWARE_MINIMUM);
        LabelPrintLayout.Resultado geo = LabelPrintLayout.calcular(modelo, etiquetas.size(), layout.getPrintableWidth(),
                layout.getPrintableHeight(), dxMm * LabelModel.PT_POR_MM, dyMm * LabelModel.PT_POR_MM, layout.getLeftMargin(),
                layout.getTopMargin());

        // Os nós são montados na FX thread; a impressão em si roda aqui, fora dela.
        CompletableFuture<List<Group>> paginas = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                List<Group> out = new ArrayList<>();
                for (LabelPrintLayout.Pagina pagina : geo.paginas()) {
                    Group g = new Group();
                    for (LabelPrintLayout.Slot slot : pagina.slots()) {
                        Node etiqueta = LabelRenderer.render(modelo, etiquetas.get(slot.indice())).no();
                        etiqueta.getTransforms().setAll(new Translate(slot.x(), slot.y()), new Scale(geo.escala(), geo.escala()));
                        g.getChildren().add(etiqueta);
                    }
                    new Scene(g);
                    g.applyCss();
                    out.add(g);
                }
                paginas.complete(out);
            } catch (RuntimeException e) {
                paginas.completeExceptionally(e);
            }
        });
        List<Group> grupos;
        try {
            grupos = paginas.get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new DomainException("Não foi possível montar as etiquetas: " + e.getMessage());
        }

        PrinterJob job = PrinterJob.createPrinterJob(printer);
        if (job == null) {
            throw new DomainException("Não foi possível iniciar a impressão em \"" + printer.getName() + "\".");
        }
        try {
            job.getJobSettings().setPageLayout(layout);
            for (Group g : grupos) {
                if (!job.printPage(layout, g)) {
                    job.cancelJob();
                    throw new DomainException("A impressão não foi concluída. Confira a impressora \"" + printer.getName()
                            + "\" (papel, tampa, fila do Windows).");
                }
            }
            job.endJob();
        } catch (DomainException e) {
            throw e;
        } catch (RuntimeException e) {
            job.cancelJob();
            throw new DomainException("Falha ao imprimir: " + e.getMessage());
        }
    }

    // =====================================================================
    // Documentos (relatórios, pedido de compra) na impressora padrão ou escolhida
    // =====================================================================

    /**
     * Imprime um documento já montado (nó fora de qualquer janela), reduzido à largura imprimível e dividido em
     * páginas entre as linhas. done recebe null se foi para a impressora, ou a mensagem de erro (FX thread).
     */
    public static void imprimirDocumento(Node documento, String impressora, Consumer<String> done) {
        preparar(documento);
        PRINT_QUEUE.submit(() -> {
            String erro = null;
            try {
                imprimirDocumentoBloqueante(documento, impressora);
            } catch (RuntimeException e) {
                erro = e instanceof DomainException ? e.getMessage()
                        : "Falha ao imprimir: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
            String resultado = erro;
            Platform.runLater(() -> done.accept(resultado));
        });
    }

    /** Um nó fora de qualquer cena não tem CSS nem layout calculados e sairia em branco. */
    public static void preparar(Node no) {
        if (no.getScene() == null) {
            new Scene(new Group(no));
        }
        no.applyCss();
        no.autosize();
        if (no instanceof Parent parent) {
            parent.layout();
        }
    }

    private static void imprimirDocumentoBloqueante(Node doc, String impressora) {
        Printer printer = achar(impressora);
        PrinterJob job = PrinterJob.createPrinterJob(printer);
        if (job == null) {
            throw new DomainException("Nenhuma impressora disponível.");
        }
        List<javafx.scene.transform.Transform> original = List.copyOf(doc.getTransforms());
        javafx.scene.shape.Shape clipOriginal = doc.getClip() instanceof javafx.scene.shape.Shape s ? s : null;
        try {
            PageLayout layout = printer.createPageLayout(printer.getDefaultPageLayout().getPaper(),
                    PageOrientation.PORTRAIT, Printer.MarginType.HARDWARE_MINIMUM);
            job.getJobSettings().setPageLayout(layout);
            Bounds b = doc.getBoundsInLocal();
            if (b.getWidth() <= 0 || b.getHeight() <= 0) {
                throw new DomainException("Documento vazio: nada para imprimir.");
            }
            double escala = fitScale(b.getWidth(), layout.getPrintableWidth());
            double paginaLocal = layout.getPrintableHeight() / escala;
            List<double[]> paginas = pageRanges(rowBottoms(doc), b.getMinY(), b.getMaxY(), paginaLocal);
            for (double[] pagina : paginas) {
                showPage(doc, b, escala, pagina);
                if (!job.printPage(layout, doc)) {
                    job.cancelJob();
                    throw new DomainException("A impressão não foi concluída.");
                }
            }
            job.endJob();
        } catch (DomainException e) {
            throw e;
        } catch (RuntimeException e) {
            job.cancelJob();
            throw new DomainException("Falha ao imprimir: " + e.getMessage());
        } finally {
            doc.getTransforms().setAll(original);
            doc.setClip(clipOriginal);
        }
    }

    /** Posiciona o documento para imprimir só a faixa [page[0], page[1]], reduzido pela escala, a partir de (0,0). */
    public static void showPage(Node doc, Bounds b, double escala, double[] pagina) {
        doc.getTransforms().setAll(new Scale(escala, escala), new Translate(-b.getMinX(), -pagina[0]));
        doc.setClip(new Rectangle(b.getMinX(), pagina[0], b.getWidth(), pagina[1] - pagina[0]));
    }

    /** Escala para o conteúdo caber na largura imprimível (nunca aumenta). */
    public static double fitScale(double larguraConteudo, double larguraImprimivel) {
        if (larguraConteudo <= 0 || larguraImprimivel <= 0 || larguraConteudo <= larguraImprimivel) {
            return 1;
        }
        return larguraImprimivel / larguraConteudo;
    }

    private static List<Double> rowBottoms(Node doc) {
        List<Double> out = new ArrayList<>();
        if (doc instanceof Parent p) {
            for (Node filho : p.getChildrenUnmodifiable()) {
                out.add(filho.getBoundsInParent().getMaxY());
            }
        }
        return out;
    }

    /**
     * Divide [top, bottom] em páginas de até alturaPagina, virando a página no fim da última linha que cabe. Linha maior
     * que uma página é cortada no limite.
     */
    public static List<double[]> pageRanges(List<Double> fimDasLinhas, double top, double bottom, double alturaPagina) {
        List<double[]> paginas = new ArrayList<>();
        if (alturaPagina <= 0 || bottom - top <= alturaPagina + 0.5) {
            paginas.add(new double[] {top, bottom});
            return paginas;
        }
        List<Double> quebras = fimDasLinhas.stream().filter(y -> y > top && y < bottom).sorted().toList();
        double inicio = top;
        while (bottom - inicio > alturaPagina + 0.5) {
            double limite = inicio + alturaPagina;
            double fim = limite;
            for (double y : quebras) {
                if (y > inicio + 1 && y <= limite) {
                    fim = y;
                }
            }
            paginas.add(new double[] {inicio, fim});
            inicio = fim;
        }
        paginas.add(new double[] {inicio, bottom});
        return paginas;
    }
}
