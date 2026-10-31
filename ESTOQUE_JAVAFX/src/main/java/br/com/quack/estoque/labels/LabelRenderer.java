package br.com.quack.estoque.labels;

import br.com.quack.estoque.domain.BalancaBarcode;
import br.com.quack.estoque.domain.Fmt;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;

/**
 * Desenha uma etiqueta de gôndola como nós JavaFX em pontos (1 mm = 72/25,4 pt): nome em até 2 linhas (com
 * reticências), preço em destaque, unidade, código interno, promoção (preço riscado e período) e código de barras
 * EAN-13 (GTIN válido) ou Code128 (código interno) com zona de silêncio de 10 módulos e módulo inteiro em pontos de
 * impressora (>= 2 dots a 203 dpi). Fontes embutidas (Nunito e Roboto Mono), sem fonte do sistema (design D7).
 */
public final class LabelRenderer {
    public static final int DPI_TERMICA = 203;
    public static final int ZONA_SILENCIO_MODULOS = 10;
    private static final double MODULO_MINIMO_DOTS = 2;

    private LabelRenderer() {
    }

    /** Código de barras escolhido para o produto. {@code aviso} fala de GTIN inválido ou de falta de espaço. */
    public record Barras(BarcodeFormat formato, String conteudo, BitMatrix matriz, String aviso) {
    }

    /** Etiqueta pronta: o nó e as medidas que os testes conferem. */
    public record Resultado(Pane no, Barras barras, double nomeBottom, double precoTop, double precoBottom,
            double barrasTop, double moduloPt, String aviso) {
    }

    /** EAN-13 se o GTIN for um EAN-13 válido; senão Code128 do código interno. */
    public static Barras codigoDeBarras(LabelData d) {
        String gtin = d.gtin() == null ? "" : d.gtin().trim();
        String aviso = null;
        if (!gtin.isEmpty() && !gtin.equalsIgnoreCase("SEM GTIN")) {
            if (BalancaBarcode.isValidEan13(gtin)) {
                return new Barras(BarcodeFormat.EAN_13, gtin, codificar(gtin, BarcodeFormat.EAN_13), null);
            }
            aviso = "GTIN \"" + gtin + "\" inválido (dígito verificador): a etiqueta usa Code128 do código interno.";
        }
        String codigo = ascii(d.codigo());
        if (codigo.isEmpty()) {
            codigo = "0";
        }
        return new Barras(BarcodeFormat.CODE_128, codigo, codificar(codigo, BarcodeFormat.CODE_128), aviso);
    }

    private static String ascii(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : (s == null ? "" : s.trim()).toCharArray()) {
            sb.append(c >= 32 && c <= 126 ? c : '-');
        }
        return sb.toString();
    }

    private static BitMatrix codificar(String conteudo, BarcodeFormat formato) {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.MARGIN, 0);
        try {
            return new MultiFormatWriter().encode(conteudo, formato, 0, 0, hints);
        } catch (WriterException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Não foi possível gerar o código de barras de \"" + conteudo + "\": "
                    + e.getMessage(), e);
        }
    }

    private static Font nunito(FontWeight w, double size) {
        return Font.font("Nunito", w, size);
    }

    private static Font mono(double size) {
        return Font.font("Roboto Mono", FontWeight.NORMAL, size);
    }

    /** Altura real de uma linha da fonte (ascendente + descendente), medida pelo JavaFX. */
    private static double altura(Font f) {
        Text t = new Text("Ágjp");
        t.setFont(f);
        return t.getLayoutBounds().getHeight();
    }

    private static double largura(String s, Font f) {
        Text t = new Text(s);
        t.setFont(f);
        return t.getLayoutBounds().getWidth();
    }

    /** Quebra o nome em até 2 linhas que caibam em {@code largura}; o que sobrar vira reticências. */
    public static String quebraNome(String nome, Font font, double largura) {
        String texto = nome == null ? "" : nome.trim().replaceAll("\\s+", " ");
        if (texto.isEmpty() || largura(texto, font) <= largura) {
            return texto;
        }
        String[] palavras = texto.split(" ");
        StringBuilder l1 = new StringBuilder();
        int i = 0;
        while (i < palavras.length) {
            String candidata = l1.isEmpty() ? palavras[i] : l1 + " " + palavras[i];
            if (largura(candidata, font) > largura && !l1.isEmpty()) {
                break;
            }
            if (largura(candidata, font) > largura) { // palavra maior que a linha: corta
                l1.append(cortar(palavras[i], font, largura));
                i++;
                break;
            }
            l1.setLength(0);
            l1.append(candidata);
            i++;
        }
        StringBuilder resto = new StringBuilder();
        for (; i < palavras.length; i++) {
            resto.append(resto.isEmpty() ? "" : " ").append(palavras[i]);
        }
        if (resto.isEmpty()) {
            return l1.toString();
        }
        String l2 = resto.toString();
        if (largura(l2, font) > largura) {
            while (l2.length() > 1 && largura(l2 + "…", font) > largura) {
                l2 = l2.substring(0, l2.length() - 1);
            }
            l2 = l2.stripTrailing() + "…";
        }
        return l1 + "\n" + l2;
    }

    private static String cortar(String palavra, Font font, double largura) {
        String s = palavra;
        while (s.length() > 1 && largura(s, font) > largura) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    public static Resultado render(LabelModel m, LabelData d) {
        double w = m.larguraPt();
        double h = m.alturaPt();
        double pad = Math.max(2.5, 0.05 * h);
        double inner = w - 2 * pad;

        Pane pane = new Pane();
        pane.setPrefSize(w, h);
        pane.setMinSize(w, h);
        pane.setMaxSize(w, h);
        pane.setStyle("-fx-background-color: white;");
        Rectangle clip = new Rectangle(w, h);
        pane.setClip(clip);

        Barras barras = codigoDeBarras(d);
        StringBuilder aviso = new StringBuilder(barras.aviso() == null ? "" : barras.aviso());

        // Nome (até 2 linhas).
        Font fNome = nunito(FontWeight.BOLD, 0.095 * h);
        String nome = quebraNome(d.nome(), fNome, inner);
        Text tNome = texto(nome, fNome, pad, pad, TextAlignment.LEFT);
        tNome.setLineSpacing(0);
        double linhaNome = altura(fNome);
        double nomeBottom = pad + 2 * linhaNome;
        pane.getChildren().add(tNome);

        // Preço em destaque (+ promoção) à esquerda; unidade e código interno à direita.
        double yPreco = nomeBottom + 0.01 * h;
        Font fUnid = nunito(FontWeight.BOLD, 0.085 * h);
        String unidade = "/" + (d.unidade() == null || d.unidade().isBlank() ? "UN" : d.unidade().trim().toUpperCase());
        String codigoTxt = "cód. " + d.codigo();
        double larguraDireita = Math.min(inner * 0.38, Math.max(largura(unidade, fUnid), largura(codigoTxt, mono(0.07 * h))));
        double areaPreco = inner - larguraDireita - 3;
        String precoTxt = Fmt.dec2(d.precoFinal());
        double fsPreco = 0.22 * h;
        Font fPreco = nunito(FontWeight.BOLD, fsPreco);
        Font fRs = nunito(FontWeight.BOLD, fsPreco * 0.42);
        double rsLargura = largura("R$ ", fRs);
        while (fsPreco > 6 && rsLargura + largura(precoTxt, fPreco) > areaPreco) {
            fsPreco *= 0.92;
            fPreco = nunito(FontWeight.BOLD, fsPreco);
            fRs = nunito(FontWeight.BOLD, fsPreco * 0.42);
            rsLargura = largura("R$ ", fRs);
        }
        double alturaPreco = altura(fPreco) * 0.9;
        Text tRs = texto("R$ ", fRs, pad, yPreco + fsPreco * 0.18, TextAlignment.LEFT);
        Text tPreco = texto(precoTxt, fPreco, pad + rsLargura, yPreco, TextAlignment.LEFT);
        pane.getChildren().addAll(tRs, tPreco);
        if (d.emPromocao()) {
            Font fDe = nunito(FontWeight.NORMAL, 0.07 * h);
            String de = "de " + Fmt.money(d.preco()) + (d.periodoPromocao() == null || d.periodoPromocao().isBlank() ? ""
                    : " · " + d.periodoPromocao());
            Text tDe = texto(de, fDe, pad, yPreco + alturaPreco - 0.01 * h, TextAlignment.LEFT);
            // só o preço normal é riscado: desenha o risco sobre o trecho "R$ ..." (sem riscar o período)
            String soPreco = "de " + Fmt.money(d.preco());
            double xIni = pad;
            double xFim = pad + largura(soPreco, fDe);
            double yRisco = tDe.getLayoutY() + fDe.getSize() * 0.55;
            javafx.scene.shape.Line linha = new javafx.scene.shape.Line(xIni + largura("de ", fDe), yRisco, xFim, yRisco);
            linha.setStroke(Color.BLACK);
            linha.setStrokeWidth(Math.max(0.6, fDe.getSize() * 0.07));
            pane.getChildren().addAll(tDe, linha);
            alturaPreco += altura(fDe);
        }
        double precoBottom = yPreco + alturaPreco;
        Text tUn = texto(unidade, fUnid, w - pad, yPreco + fsPreco * 0.1, TextAlignment.RIGHT);
        Text tCod = texto(codigoTxt, mono(0.07 * h), w - pad, yPreco + fsPreco * 0.1 + altura(fUnid),
                TextAlignment.RIGHT);
        pane.getChildren().addAll(tUn, tCod);

        // Código de barras + número legível.
        Font fDig = mono(0.085 * h);
        double digitosH = altura(fDig);
        double yDigitos = h - pad - digitosH;
        double barrasTop = Math.max(precoBottom + 0.01 * h, 0);
        double alturaBarras = Math.max(6, yDigitos - barrasTop - 1);
        int modulos = barras.matriz().getWidth();
        int total = modulos + 2 * ZONA_SILENCIO_MODULOS;
        double dotsPorPt = DPI_TERMICA / 72.0;
        double moduloDots = Math.max(MODULO_MINIMO_DOTS, Math.floor(inner * dotsPorPt / total));
        double moduloPt = moduloDots / dotsPorPt;
        double larguraBarras = total * moduloPt;
        if (larguraBarras > inner + 0.5) {
            if (aviso.length() > 0) {
                aviso.append(' ');
            }
            aviso.append("Etiqueta estreita para o código de barras: o leitor pode ter dificuldade.");
        }
        Canvas canvas = new Canvas(larguraBarras, alturaBarras);
        desenharBarras(canvas.getGraphicsContext2D(), barras.matriz(), moduloPt, alturaBarras);
        canvas.setLayoutX((w - larguraBarras) / 2);
        canvas.setLayoutY(barrasTop);
        pane.getChildren().add(canvas);
        String numero = barras.formato() == BarcodeFormat.EAN_13
                ? barras.conteudo().charAt(0) + " " + barras.conteudo().substring(1, 7) + " " + barras.conteudo().substring(7)
                : barras.conteudo();
        Text tDig = texto(numero, fDig, w / 2, yDigitos, TextAlignment.CENTER);
        pane.getChildren().add(tDig);

        return new Resultado(pane, barras, nomeBottom, yPreco, precoBottom, barrasTop, moduloPt, aviso.toString());
    }

    private static Text texto(String s, Font f, double x, double y, TextAlignment alinhamento) {
        Text t = new Text(s);
        t.setFont(f);
        t.setFill(Color.BLACK);
        t.setTextOrigin(VPos.TOP);
        t.setFontSmoothingType(javafx.scene.text.FontSmoothingType.GRAY);
        t.setTextAlignment(alinhamento);
        double largura = t.getLayoutBounds().getWidth();
        switch (alinhamento) {
            case RIGHT -> t.setLayoutX(x - largura);
            case CENTER -> t.setLayoutX(x - largura / 2);
            default -> t.setLayoutX(x);
        }
        t.setLayoutY(y);
        return t;
    }

    /** Barras com a zona de silêncio de cada lado; cada módulo ocupa moduloPt (um número inteiro de dots). */
    static void desenharBarras(GraphicsContext g, BitMatrix m, double moduloPt, double altura) {
        g.setFill(Color.WHITE);
        g.fillRect(0, 0, (m.getWidth() + 2 * ZONA_SILENCIO_MODULOS) * moduloPt, altura);
        g.setFill(Color.BLACK);
        int i = 0;
        while (i < m.getWidth()) {
            if (!m.get(i, 0)) {
                i++;
                continue;
            }
            int j = i;
            while (j < m.getWidth() && m.get(j, 0)) {
                j++;
            }
            g.fillRect((ZONA_SILENCIO_MODULOS + i) * moduloPt, 0, (j - i) * moduloPt, altura);
            i = j;
        }
    }

    /** Período para exibir na etiqueta: "até 30/11" (fim da promoção) ou vazio. */
    public static String periodo(String inicioIso, String fimIso) {
        if (fimIso == null || fimIso.length() < 10) {
            return "";
        }
        try {
            LocalDate fim = LocalDate.parse(fimIso.substring(0, 10));
            return "até " + String.format("%02d/%02d", fim.getDayOfMonth(), fim.getMonthValue());
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** Nó da etiqueta (atalho para a pré-visualização/impressão). */
    public static Node no(LabelModel m, LabelData d) {
        return render(m, d).no();
    }

    /** Preço da etiqueta como o app compara com o último impresso. */
    public static BigDecimal precoImpresso(LabelData d) {
        return d.precoFinal();
    }
}
