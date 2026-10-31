package br.com.quack.estoque.ui;

import br.com.quack.estoque.AppContext;
import br.com.quack.estoque.api.PromocaoApi;
import br.com.quack.estoque.domain.DomainException;
import br.com.quack.estoque.domain.Fmt;
import br.com.quack.estoque.domain.Produto;
import br.com.quack.estoque.labels.FilaEtiquetas;
import br.com.quack.estoque.labels.LabelData;
import br.com.quack.estoque.labels.LabelModel;
import br.com.quack.estoque.labels.LabelRenderer;
import br.com.quack.estoque.service.PrintService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javafx.collections.FXCollections;
import javafx.scene.Group;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/** Etiquetas de gôndola: fila, modelo, pré-visualização em tamanho real, calibração e impressão pela térmica do Windows. */
public class EtiquetasView extends Tela {
    private record Linha(FilaEtiquetas.Item item, Produto produto) {
    }

    private final TableView<Linha> tabela = Ui.tabela();
    private final ComboBox<LabelModel> modelo = Ui.combo(new ArrayList<>());
    private final ComboBox<String> impressora = Ui.combo(new ArrayList<>());
    private final TextField dx = Ui.campo("0");
    private final TextField dy = Ui.campo("0");
    private final Label total = Ui.muted("");
    private final Label avisos = Ui.muted("");
    private final VBox previa = new VBox(8);
    private final Button imprimir;
    private List<PromocaoApi.Promocao> promocoes = List.of();
    private boolean carregando;

    public EtiquetasView(AppContext ctx, Navegador nav) {
        super(ctx, nav);
        imprimir = Ui.primario("Imprimir etiquetas", this::imprimir);
        getChildren().add(Ui.cabecalho("Etiquetas de gôndola", "Monte a fila e imprima na térmica pelo driver do Windows (F8).", imprimir));

        // --- fila
        Button add = Ui.botao("Adicionar produto…", () -> ProdutoPicker.escolher(janela(), ctx, false, p -> {
            ctx.etiquetas.adicionar(p.id(), 1);
            recarregarFila();
        }));
        Button alterados = Ui.botao("Preços alterados", () -> {
            int n = ctx.etiquetas.adicionarPrecosAlterados(ctx.catalogo);
            if (n == 0) {
                Toasts.info(janela(), "Nenhum produto mudou de preço desde a última etiqueta impressa.");
            } else {
                sucesso(n + " produto(s) com preço alterado na fila.");
            }
            recarregarFila();
        });
        Button copias = Ui.botao("Cópias…", () -> {
            Linha l = tabela.getSelectionModel().getSelectedItem();
            if (l != null) {
                Modal.pedirTexto(janela(), "Cópias de " + l.produto().nome(), "Quantidade de etiquetas", String.valueOf(l.item().copias), "Definir", t -> {
                    try {
                        ctx.etiquetas.definirCopias(l.item().produtoId, Integer.parseInt(t.trim()));
                        recarregarFila();
                    } catch (NumberFormatException | DomainException e) {
                        Toasts.error(janela(), "Informe um número inteiro maior que zero.");
                    }
                });
            }
        });
        Button remover = Ui.botao("Remover", () -> {
            Linha l = tabela.getSelectionModel().getSelectedItem();
            if (l != null) {
                ctx.etiquetas.remover(l.item().produtoId);
                recarregarFila();
            }
        });
        Button limpar = Ui.botao("Limpar fila", () -> Modal.confirmar(janela(), "Limpar fila", "Remover todos os produtos da fila de etiquetas?",
                "Limpar", () -> {
                    ctx.etiquetas.limpar();
                    recarregarFila();
                }));
        getChildren().add(Ui.barra(add, alterados, copias, remover, limpar));

        Ui.col(tabela, "Código", l -> l.produto().codigo(), 90);
        Ui.col(tabela, "Produto", l -> l.produto().nome(), 260);
        Ui.colNum(tabela, "Preço", l -> Fmt.dec2(l.produto().preco()), 80);
        Ui.colNum(tabela, "Cópias", l -> String.valueOf(l.item().copias), 66);
        Ui.colNum(tabela, "Último impresso", l -> {
            var u = ctx.etiquetas.ultimoPreco(l.produto().id());
            return u == null ? "—" : Fmt.dec2(u);
        }, 110);
        Ui.col(tabela, "Código de barras", l -> {
            String g = l.produto().gtin();
            if (g.isBlank()) {
                return "Code128 (cód. interno)";
            }
            return br.com.quack.estoque.domain.BalancaBarcode.isValidEan13(g) ? "EAN-13" : "GTIN inválido → Code128";
        }, 150);
        tabela.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> atualizarPrevia());
        tabela.setPrefHeight(300);
        VBox.setVgrow(tabela, Priority.ALWAYS);

        // --- configuração e prévia
        modelo.setItems(FXCollections.observableArrayList(LabelModel.padroes()));
        modelo.valueProperty().addListener((o, a, b) -> {
            if (!carregando && b != null) {
                ctx.etiquetas.definirModelo(b);
                atualizarPrevia();
            }
        });
        Button personalizado = Ui.pequeno("Tamanho personalizado…", this::personalizar);
        impressora.valueProperty().addListener((o, a, b) -> {
            if (!carregando && b != null) {
                ctx.etiquetas.definirImpressora(b);
            }
        });
        dx.setPrefWidth(70);
        dy.setPrefWidth(70);
        Button salvarCal = Ui.pequeno("Salvar ajuste", this::salvarCalibracao);
        VBox config = new VBox(8, Ui.rotulado("Modelo (tamanho da etiqueta)", modelo), personalizado,
                Ui.rotulado("Impressora do Windows", impressora),
                Ui.texto("Ajuste fino (mm): negativo = esquerda / acima", "rg-form-label"),
                Ui.barra(Ui.rotulado("Horizontal", dx), Ui.rotulado("Vertical", dy), salvarCal), total, avisos);
        config.getStyleClass().add("rg-card");
        config.setPrefWidth(330);
        config.setMinWidth(330);

        ScrollPane rolagem = new ScrollPane(previa);
        rolagem.setPrefHeight(230);
        rolagem.setFitToWidth(true);
        VBox direita = new VBox(10, config, Ui.texto("Pré-visualização em tamanho real", "rg-section-title"), rolagem);
        HBox corpo = new HBox(14, tabela, direita);
        HBox.setHgrow(tabela, Priority.ALWAYS);
        VBox.setVgrow(corpo, Priority.ALWAYS);
        getChildren().add(corpo);
    }

    @Override
    public void ativar() {
        carregando = true;
        modelo.setValue(ctx.etiquetas.modelo());
        if (!LabelModel.padroes().contains(ctx.etiquetas.modelo())) {
            modelo.getItems().setAll(LabelModel.padroes());
            modelo.getItems().add(ctx.etiquetas.modelo());
            modelo.setValue(ctx.etiquetas.modelo());
        }
        List<String> nomes = PrintService.impressoras();
        impressora.setItems(FXCollections.observableArrayList(nomes));
        String salva = ctx.etiquetas.impressora();
        impressora.setValue(!salva.isBlank() && nomes.contains(salva) ? salva : PrintService.impressoraPadrao());
        dx.setText(Fmt.dec2(java.math.BigDecimal.valueOf(ctx.etiquetas.dxMm())));
        dy.setText(Fmt.dec2(java.math.BigDecimal.valueOf(ctx.etiquetas.dyMm())));
        carregando = false;
        rodar(() -> {
            ctx.garantirCatalogo(false);
            try {
                return ctx.promocoes.ativas();
            } catch (RuntimeException e) {
                return List.<PromocaoApi.Promocao>of(); // sem promoções: a etiqueta sai com o preço normal
            }
        }, promos -> {
            promocoes = promos;
            recarregarFila();
        });
    }

    private void recarregarFila() {
        List<Linha> linhas = new ArrayList<>();
        List<String> orfaos = new ArrayList<>();
        for (FilaEtiquetas.Item i : ctx.etiquetas.itens()) {
            Produto p = ctx.catalogo.porId(i.produtoId);
            if (p == null) {
                orfaos.add(i.produtoId);
            } else {
                linhas.add(new Linha(i, p));
            }
        }
        orfaos.forEach(ctx.etiquetas::remover); // produto excluído desde a última vez
        tabela.setItems(FXCollections.observableArrayList(linhas));
        total.setText(ctx.etiquetas.totalEtiquetas() + " etiquetas de " + linhas.size() + " produtos");
        imprimir.setDisable(linhas.isEmpty());
        if (!linhas.isEmpty() && tabela.getSelectionModel().getSelectedItem() == null) {
            tabela.getSelectionModel().selectFirst();
        }
        atualizarPrevia();
    }

    /** Dado da etiqueta, com o preço promocional quando há promoção de preço fixo vigente para o produto. */
    LabelData dadosDe(Produto p) {
        LocalDate hoje = LocalDate.now();
        for (PromocaoApi.Promocao promo : promocoes) {
            if (!promo.ehPrecoFixo()) {
                continue;
            }
            boolean doProduto = promo.produtoIds().contains(p.id())
                    || (!promo.categoriaId().isBlank() && promo.categoriaId().equals(p.categoriaId()));
            if (!doProduto || promo.precoFixo().compareTo(p.preco()) >= 0) {
                continue;
            }
            try {
                if (promo.fim().length() >= 10 && LocalDate.parse(promo.fim().substring(0, 10)).isBefore(hoje)) {
                    continue;
                }
                if (promo.inicio().length() >= 10 && LocalDate.parse(promo.inicio().substring(0, 10)).isAfter(hoje)) {
                    continue;
                }
            } catch (RuntimeException ignored) {
                // data ilegível: considera vigente
            }
            return LabelData.promocao(p, promo.precoFixo(), LabelRenderer.periodo(promo.inicio(), promo.fim()));
        }
        return LabelData.de(p);
    }

    private void atualizarPrevia() {
        previa.getChildren().clear();
        avisos.setText("");
        Linha l = tabela.getSelectionModel().getSelectedItem();
        LabelModel m = modelo.getValue();
        if (l == null || m == null) {
            previa.getChildren().add(Ui.muted("Selecione um produto da fila."));
            return;
        }
        try {
            LabelRenderer.Resultado r = LabelRenderer.render(m, dadosDe(l.produto()));
            double tela = 96.0 / 72.0; // pontos -> pixels de tela: tamanho real
            Group g = new Group(r.no());
            g.setScaleX(tela);
            g.setScaleY(tela);
            VBox caixa = new VBox(g);
            caixa.getStyleClass().add("rg-label-preview");
            caixa.setPrefSize(m.larguraPt() * tela + 16, m.alturaPt() * tela + 16);
            caixa.setMaxSize(m.larguraPt() * tela + 16, m.alturaPt() * tela + 16);
            caixa.setPadding(new javafx.geometry.Insets((m.alturaPt() * (tela - 1)) / 2 + 6, 0, (m.alturaPt() * (tela - 1)) / 2 + 6,
                    (m.larguraPt() * (tela - 1)) / 2 + 6));
            previa.getChildren().add(caixa);
            avisos.setText(r.aviso());
            if (!r.aviso().isBlank()) {
                avisos.setStyle("-fx-text-fill: -danger;");
            }
            previa.getChildren().add(Ui.muted(m.nome() + (m.folha() ? " · " + m.porFolha() + " por folha" : "")));
        } catch (RuntimeException e) {
            previa.getChildren().add(Ui.caixa("Não foi possível desenhar a etiqueta: " + e.getMessage(), "rg-danger-box"));
        }
    }

    private void salvarCalibracao() {
        try {
            ctx.etiquetas.definirCalibracao(Ui.numeroOuZero(dx).doubleValue(), Ui.numeroOuZero(dy).doubleValue());
            sucesso("Ajuste fino salvo neste terminal.");
        } catch (DomainException e) {
            Toasts.error(janela(), e.getMessage());
        }
    }

    private void personalizar() {
        TextField w = Ui.campo("60");
        TextField h = Ui.campo("40");
        TextField cols = Ui.campo("1");
        TextField rows = Ui.campo("1");
        TextField ex = Ui.campo("0");
        TextField ey = Ui.campo("0");
        TextField mx = Ui.campo("0");
        TextField my = Ui.campo("0");
        LabelModel atual = ctx.etiquetas.modelo();
        w.setText(Fmt.qty(java.math.BigDecimal.valueOf(atual.larguraMm())));
        h.setText(Fmt.qty(java.math.BigDecimal.valueOf(atual.alturaMm())));
        cols.setText(String.valueOf(atual.colunas()));
        rows.setText(String.valueOf(atual.linhas()));
        ex.setText(Fmt.qty(java.math.BigDecimal.valueOf(atual.espacoXMm())));
        ey.setText(Fmt.qty(java.math.BigDecimal.valueOf(atual.espacoYMm())));
        mx.setText(Fmt.qty(java.math.BigDecimal.valueOf(atual.margemEsqMm())));
        my.setText(Fmt.qty(java.math.BigDecimal.valueOf(atual.margemTopMm())));
        javafx.scene.layout.GridPane g = new javafx.scene.layout.GridPane();
        g.setHgap(10);
        g.setVgap(8);
        g.add(Ui.rotulado("Largura (mm)", w), 0, 0);
        g.add(Ui.rotulado("Altura (mm)", h), 1, 0);
        g.add(Ui.rotulado("Colunas", cols), 0, 1);
        g.add(Ui.rotulado("Linhas", rows), 1, 1);
        g.add(Ui.rotulado("Espaço entre colunas (mm)", ex), 0, 2);
        g.add(Ui.rotulado("Espaço entre linhas (mm)", ey), 1, 2);
        g.add(Ui.rotulado("Margem esquerda (mm)", mx), 0, 3);
        g.add(Ui.rotulado("Margem superior (mm)", my), 1, 3);
        Modal[] m = new Modal[1];
        m[0] = Modal.formulario(janela(), "Tamanho personalizado", new VBox(8, g, Ui.muted("1 coluna x 1 linha = rolo da térmica; "
                + "mais que isso = folha A4 em grade.")), "Usar este tamanho", 460, () -> {
            try {
                LabelModel novo = LabelModel.personalizado(Ui.numero(w, "a largura").doubleValue(), Ui.numero(h, "a altura").doubleValue(),
                        Ui.numero(cols, "as colunas").intValue(), Ui.numero(rows, "as linhas").intValue(), Ui.numeroOuZero(ex).doubleValue(),
                        Ui.numeroOuZero(ey).doubleValue(), Ui.numeroOuZero(mx).doubleValue(), Ui.numeroOuZero(my).doubleValue());
                ctx.etiquetas.definirModelo(novo);
                ativar();
                return true;
            } catch (DomainException | IllegalArgumentException e) {
                m[0].erro(e.getMessage());
                return false;
            }
        });
        m[0].mostrar();
    }

    /** Etiquetas da fila repetidas pelas cópias, na ordem da fila. */
    List<LabelData> expandir() {
        List<LabelData> out = new ArrayList<>();
        for (Linha l : tabela.getItems()) {
            LabelData d = dadosDe(l.produto());
            for (int i = 0; i < l.item().copias; i++) {
                out.add(d);
            }
        }
        return out;
    }

    private void imprimir() {
        List<LabelData> etiquetas = expandir();
        if (etiquetas.isEmpty()) {
            Toasts.error(janela(), "A fila de etiquetas está vazia.");
            return;
        }
        LabelModel m = modelo.getValue();
        Set<String> invalidos = new LinkedHashSet<>();
        for (LabelData d : etiquetas) {
            String av = LabelRenderer.codigoDeBarras(d).aviso();
            if (av != null) {
                invalidos.add(d.codigo());
            }
        }
        try {
            salvarCalibracao();
        } catch (RuntimeException ignored) {
            // valores inválidos já avisados
        }
        String nomeImpressora = impressora.getValue() == null ? "" : impressora.getValue();
        imprimir.setDisable(true);
        Toasts.info(janela(), "Enviando " + etiquetas.size() + " etiquetas para a impressora… a tela continua livre.");
        PrintService.imprimirEtiquetas(m, etiquetas, nomeImpressora, ctx.etiquetas.dxMm(), ctx.etiquetas.dyMm(), erro -> {
            imprimir.setDisable(false);
            if (erro != null) {
                Toasts.error(janela(), erro);
                return;
            }
            // Registro do preço impresso por produto (filtro "preços alterados").
            for (LabelData d : etiquetas) {
                ctx.etiquetas.registrarImpressao(d.produtoId(), d.precoFinal());
            }
            sucesso("Impressão concluída: " + etiquetas.size() + " etiquetas." + (invalidos.isEmpty() ? ""
                    : " Atenção: GTIN inválido em " + String.join(", ", invalidos) + " (saiu Code128 do código interno)."));
            ctx.etiquetas.limpar();
            recarregarFila();
        });
    }
}
