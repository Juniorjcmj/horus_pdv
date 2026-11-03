package br.com.quack.estoque.ui;

import br.com.quack.estoque.AppContext;
import br.com.quack.estoque.domain.ConversaoUnidade;
import br.com.quack.estoque.domain.DomainException;
import br.com.quack.estoque.domain.Fmt;
import br.com.quack.estoque.domain.Perfil;
import br.com.quack.estoque.domain.Produto;
import br.com.quack.estoque.persistence.JsonFiles;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

/**
 * Entrada por NF-e: abrir o XML ou digitar a chave de acesso (SEFAZ), conferir item a item (vínculo ao produto,
 * conversão de unidade, lote/validade, preço de venda) e confirmar. A conferência é salva em rascunho local a cada
 * mudança para sobreviver a sessão expirada ou queda da API.
 */
public final class EntradaNfeDialog {
    private static final String RASCUNHO = "entrada-nfe";

    private final AppContext ctx;
    private final Window dono;
    private final Perfil perfil;
    private final Runnable aoConcluir;
    private final Runnable irParaEtiquetas;

    private Modal modal;
    private VBox corpo;
    private ObjectNode preview;
    private final List<ItemConf> itens = new ArrayList<>();
    private final Label total = Ui.texto("", "rg-section-title");
    private final Label pendencias = Ui.muted("");
    private CheckBox fornecedorOk;
    private boolean carregandoRascunho;

    private EntradaNfeDialog(AppContext ctx, Window dono, Runnable aoConcluir, Runnable irParaEtiquetas) {
        this.ctx = ctx;
        this.dono = dono;
        this.perfil = ctx.session.perfil();
        this.aoConcluir = aoConcluir;
        this.irParaEtiquetas = irParaEtiquetas;
    }

    public static void abrir(AppContext ctx, Window dono, Runnable aoConcluir, Runnable irParaEtiquetas) {
        new EntradaNfeDialog(ctx, dono, aoConcluir, irParaEtiquetas).iniciar();
    }

    // ------------------------------------------------------------------ etapa 1: origem da nota

    private void iniciar() {
        corpo = new VBox(12);
        corpo.setMinWidth(900);
        modal = Modal.formularioRolavel(dono, "Nova entrada por NF-e", "Abra o XML da nota ou digite a chave de acesso.", corpo,
                null, 940, 560, () -> true);
        modal.mostrar();
        etapaOrigem();
        if (ctx.rascunhos.existe(RASCUNHO)) {
            JsonNode r = ctx.rascunhos.ler(RASCUNHO);
            if (r != null && r.has("preview")) {
                Modal.confirmar(modal.stage(), "Rascunho encontrado", "Há uma conferência de NF-e não concluída ("
                        + r.path("preview").path("numeroNota").asText("?") + "). Retomar de onde parou?", "Retomar", () -> {
                            try {
                                montarConferencia((ObjectNode) r.get("preview"), r.get("itens"));
                            } catch (RuntimeException e) {
                                ctx.rascunhos.apagar(RASCUNHO);
                                Toasts.error(dono, "Rascunho ilegível; comece de novo. " + e.getMessage());
                            }
                        });
            }
        }
    }

    private void etapaOrigem() {
        corpo.getChildren().clear();
        TextField chave = Ui.campo("Chave de acesso (44 dígitos)");
        Button xml = Ui.primario("Abrir arquivo XML…", this::abrirXml);
        Button sefaz = Ui.botao("Buscar na SEFAZ pela chave", () -> {
            String k = chave.getText() == null ? "" : chave.getText().replaceAll("\\D", "");
            if (k.length() != 44) {
                modal.erro("A chave de acesso tem 44 números.");
                return;
            }
            modal.erro(null);
            carregar(() -> ctx.entradas.buscarSefaz(k));
        });
        corpo.getChildren().addAll(
                Ui.texto("1. De onde vem a nota?", "rg-section-title"),
                Ui.barra(xml, Ui.muted("ou")),
                Ui.rotulado("Chave de acesso", chave), Ui.barra(sefaz),
                Ui.caixa("A consulta na SEFAZ usa o certificado digital da empresa. Sem certificado, abra o arquivo XML.", "rg-warn-box"));
    }

    private void abrirXml() {
        FileChooser fc = new FileChooser();
        fc.setTitle("XML da NF-e de compra");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("XML da NF-e", "*.xml"));
        File f = fc.showOpenDialog(modal.stage());
        if (f == null) {
            return;
        }
        try {
            if (f.length() > 10L * 1024 * 1024) {
                throw new DomainException("O XML deve ter no máximo 10 MB.");
            }
            String b64 = Base64.getEncoder().encodeToString(Files.readAllBytes(f.toPath()));
            modal.erro(null);
            carregar(() -> ctx.entradas.previewXml(b64));
        } catch (IOException | DomainException e) {
            modal.erro(e instanceof DomainException ? e.getMessage() : "Não foi possível ler o arquivo: " + e.getMessage());
        }
    }

    private void carregar(java.util.function.Supplier<JsonNode> chamada) {
        modal.ocupado(true);
        Fx.async(() -> {
            JsonNode p = chamada.get();
            ctx.garantirCatalogo(true);
            return p;
        }, p -> {
            modal.ocupado(false);
            if (p == null || !p.has("itens")) {
                modal.erro("A API não devolveu os itens da nota.");
                return;
            }
            montarConferencia((ObjectNode) p, null);
        }, t -> {
            modal.ocupado(false);
            modal.erro(Fx.message(t));
        });
    }

    // ------------------------------------------------------------------ etapa 2: conferência

    /** Um item da nota em conferência. */
    private final class ItemConf {
        final JsonNode origem;
        Produto existente;
        boolean novo;
        final TextField qtdCompra = Ui.campo("Qtd.");
        final TextField unidadeCompra = Ui.campo("Un. compra");
        final TextField fator = Ui.campo("Fator");
        final TextField custoCompra = Ui.campo("Custo");
        final TextField lote = Ui.campo("Lote");
        final DatePicker validade = new DatePicker();
        final CheckBox prazoPadrao = Ui.check("Usar prazo padrão da categoria", false);
        final CheckBox atualizarPreco = Ui.check("Atualizar preço de venda", false);
        final TextField precoNovo = Ui.campo("Preço de venda");
        final TextField codigoNovo = Ui.campo("Código do produto");
        final TextField nomeNovo = Ui.campo("Nome do produto");
        final Label vinculo = new Label();
        final Label calculo = Ui.muted("");
        final Label precoInfo = Ui.muted("");
        final VBox card = new VBox(6);
        final GridPane campos = new GridPane();
        final VBox blocoNovo = new VBox(6);
        final VBox blocoPreco = new VBox(4);
        final HBoxLike acoes = new HBoxLike();

        ItemConf(JsonNode origem) {
            this.origem = origem;
            card.getStyleClass().add("rg-card");
            fator.setText("1");
            unidadeCompra.setText(origem.path("unidadeComercial").asText("UN"));
            qtdCompra.setText(origem.path("quantidade").asText("1"));
            custoCompra.setText(origem.path("precoCusto").asText("0"));
            nomeNovo.setText(origem.path("productName").asText(""));
            codigoNovo.setText(origem.path("productCode").asText(""));
            String idExistente = origem.path("produtoExistenteId").asText("");
            if (!idExistente.isBlank()) {
                existente = ctx.catalogo.porId(idExistente);
            }
            validade.setMaxWidth(Double.MAX_VALUE);
            validade.setPromptText("Validade");

            campos.setHgap(8);
            campos.setVgap(6);
            campos.add(Ui.rotulado("Qtd. comprada", qtdCompra), 0, 0);
            campos.add(Ui.rotulado("Unidade da compra", unidadeCompra), 1, 0);
            campos.add(Ui.rotulado("Fator (itens por embalagem)", fator), 2, 0);
            campos.add(Ui.rotulado("Custo da embalagem (R$)", custoCompra), 3, 0);
            campos.add(Ui.rotulado("Lote", lote), 0, 1);
            campos.add(Ui.rotulado("Validade", validade), 1, 1);
            campos.add(prazoPadrao, 2, 1, 2, 1);
            for (int c = 0; c < 4; c++) {
                javafx.scene.layout.ColumnConstraints cc = new javafx.scene.layout.ColumnConstraints();
                cc.setPercentWidth(25);
                campos.getColumnConstraints().add(cc);
            }
            blocoNovo.getChildren().addAll(Ui.texto("Produto novo (será cadastrado)", "rg-form-label"),
                    Ui.barra(Ui.rotulado("Código", codigoNovo), Ui.rotulado("Nome", nomeNovo),
                            Ui.rotulado("Preço de venda (R$)", precoNovo)));
            blocoPreco.getChildren().addAll(atualizarPreco, precoInfo);
            acoes.add(Ui.pequeno("Vincular a produto…", this::vincular));
            acoes.add(Ui.pequeno("Cadastrar como novo", () -> {
                existente = null;
                novo = true;
                if (precoNovo.getText().isBlank()) {
                    precoNovo.setText(Fmt.dec2(sugerirPreco()));
                }
                atualizarVisual();
                salvarRascunho();
            }));
            card.getChildren().addAll(cabecalho(), vinculo, campos, blocoNovo, blocoPreco, calculo, acoes.caixa);

            for (javafx.scene.control.TextInputControl t : List.of(qtdCompra, unidadeCompra, fator, custoCompra, lote,
                    precoNovo, codigoNovo, nomeNovo)) {
                t.textProperty().addListener((o, a, b) -> mudou());
            }
            validade.valueProperty().addListener((o, a, b) -> mudou());
            prazoPadrao.selectedProperty().addListener((o, a, b) -> mudou());
            atualizarPreco.selectedProperty().addListener((o, a, b) -> mudou());
            novo = existente == null && origem.path("produtoExistenteId").asText("").isBlank() && false;
            atualizarVisual();
        }

        Label cabecalho() {
            String gtin = origem.path("gtin").asText("");
            Label l = Ui.texto("Item " + origem.path("numeroItem").asInt() + " · " + origem.path("productName").asText("")
                    + (gtin.isBlank() || gtin.equalsIgnoreCase("SEM GTIN") ? "" : "  (GTIN " + gtin + ")")
                    + (origem.path("codigoFornecedor").asText("").isBlank() ? "" : "  · cód. fornecedor "
                            + origem.path("codigoFornecedor").asText()), "rg-section-title");
            l.setWrapText(true);
            return l;
        }

        BigDecimal custoUnit() {
            return ConversaoUnidade.custoUnitario(Fmt.parse(custoCompra.getText()), Fmt.parse(fator.getText()));
        }

        BigDecimal qtdEstoque() {
            return ConversaoUnidade.quantidadeEstoque(Fmt.parse(qtdCompra.getText()), Fmt.parse(fator.getText()));
        }

        BigDecimal sugerirPreco() {
            try {
                return custoUnit().multiply(new BigDecimal("1.30")).setScale(2, RoundingMode.UP);
            } catch (RuntimeException e) {
                return BigDecimal.ZERO;
            }
        }

        boolean pendente() {
            return existente == null && !novo;
        }

        void vincular() {
            ProdutoPicker.escolher(modal.stage(), ctx, perfil.verCustos(), p -> {
                existente = p;
                novo = false;
                if (!precoNovo.isFocused() && precoNovo.getText().isBlank()) {
                    precoNovo.setText(Fmt.dec2(p.preco()));
                }
                atualizarVisual();
                salvarRascunho();
            });
        }

        void mudou() {
            atualizarVisual();
            salvarRascunho();
        }

        void atualizarVisual() {
            boolean ex = existente != null;
            vinculo.getStyleClass().removeAll("rg-text-danger", "rg-text-success");
            if (ex) {
                vinculo.setText("Vinculado a: " + existente.codigo() + " · " + existente.nome() + "  (estoque " + Fmt.qty(existente.estoque())
                        + " " + existente.unidade() + ")");
                vinculo.getStyleClass().add("rg-text-success");
            } else if (novo) {
                vinculo.setText("Será cadastrado como produto novo.");
                vinculo.getStyleClass().add("rg-text-success");
            } else {
                vinculo.setText("PENDENTE: vincule a um produto existente ou cadastre como novo.");
                vinculo.getStyleClass().add("rg-text-danger");
            }
            blocoNovo.setVisible(novo);
            blocoNovo.setManaged(novo);
            blocoPreco.setVisible(ex);
            blocoPreco.setManaged(ex);
            boolean controla = ex && existente.controlaValidade();
            validade.setDisable(prazoPadrao.isSelected());
            prazoPadrao.setVisible(controla);
            prazoPadrao.setManaged(controla);
            if (ex) {
                precoInfo.setText("Preço atual R$ " + Fmt.dec2(existente.preco()) + (atualizarPreco.isSelected() ? "" : "  (o preço atual é mantido)"));
                precoNovo.setDisable(!atualizarPreco.isSelected());
                if (atualizarPreco.isSelected() && !blocoPreco.getChildren().contains(precoNovo)) {
                    blocoPreco.getChildren().add(precoNovo);
                } else if (!atualizarPreco.isSelected()) {
                    blocoPreco.getChildren().remove(precoNovo);
                }
            }
            try {
                BigDecimal q = qtdEstoque();
                BigDecimal c = custoUnit();
                calculo.setText("Entra no estoque: " + Fmt.qty(q) + " " + (ex ? existente.unidade() : origem.path("unidadeComercial").asText("UN"))
                        + (perfil.verCustos() ? " · custo unitário R$ " + Fmt.cost4(c) : "")
                        + (perfil.verCustos() ? " · total R$ " + Fmt.dec2(ConversaoUnidade.totalLinha(Fmt.parse(qtdCompra.getText()),
                                Fmt.parse(custoCompra.getText()))) : ""));
                calculo.setStyle("");
            } catch (RuntimeException e) {
                calculo.setText("Confira quantidade, fator e custo: " + e.getMessage());
                calculo.setStyle("-fx-text-fill: -danger;");
            }
            atualizarTotais();
        }
    }

    /** Linha de botões. */
    private static final class HBoxLike {
        final javafx.scene.layout.HBox caixa = new javafx.scene.layout.HBox(8);

        void add(javafx.scene.Node n) {
            caixa.getChildren().add(n);
        }
    }

    private void montarConferencia(ObjectNode p, JsonNode rascunhoItens) {
        this.preview = p;
        itens.clear();
        corpo.getChildren().clear();
        carregandoRascunho = true;

        JsonNode f = p.path("fornecedor");
        String numero = p.path("numeroNota").asText("");
        corpo.getChildren().add(Ui.texto("2. Conferência da nota " + numero + (p.path("serie").asText("").isBlank() ? "" : " série " + p.path("serie").asText()),
                "rg-section-title"));
        corpo.getChildren().add(Ui.muted("Fornecedor: " + f.path("fantasyName").asText(f.path("companyName").asText("")) + " · CNPJ "
                + f.path("cnpj").asText("") + " · " + p.path("itens").size() + " itens"));
        fornecedorOk = null;
        if (!f.path("jaExiste").asBoolean(true)) {
            fornecedorOk = Ui.check("Cadastrar o fornecedor \"" + f.path("companyName").asText("") + "\" com os dados do emitente da nota",
                    true);
            corpo.getChildren().addAll(Ui.caixa("Fornecedor ainda não cadastrado.", "rg-warn-box"), fornecedorOk);
        }

        for (JsonNode it : p.path("itens")) {
            ItemConf c = new ItemConf(it);
            itens.add(c);
            corpo.getChildren().add(c.card);
        }
        if (rascunhoItens != null) {
            aplicarRascunho(rascunhoItens);
        }
        carregandoRascunho = false;
        itens.forEach(ItemConf::atualizarVisual);

        Button confirmar = Ui.primario("Confirmar entrada", this::confirmar);
        Button recomecar = Ui.botao("Descartar e recomeçar", () -> {
            ctx.rascunhos.apagar(RASCUNHO);
            etapaOrigem();
        });
        corpo.getChildren().addAll(total, pendencias, Ui.barra(recomecar, confirmar));
        atualizarTotais();
        salvarRascunho();
    }

    private void atualizarTotais() {
        BigDecimal soma = BigDecimal.ZERO;
        int pend = 0;
        for (ItemConf c : itens) {
            if (c.pendente()) {
                pend++;
            }
            try {
                soma = soma.add(ConversaoUnidade.totalLinha(Fmt.parse(c.qtdCompra.getText()), Fmt.parse(c.custoCompra.getText())));
            } catch (RuntimeException ignored) {
                // item incompleto: fora da soma
            }
        }
        total.setText(perfil.verCustos() ? "Total conferido: " + Fmt.money(soma) : itens.size() + " itens");
        pendencias.setText(pend == 0 ? "Todos os itens estão vinculados." : pend + " item(ns) pendente(s) de vínculo.");
        pendencias.setStyle(pend == 0 ? "" : "-fx-text-fill: -danger;");
    }

    // ------------------------------------------------------------------ rascunho

    private void salvarRascunho() {
        if (carregandoRascunho || preview == null) {
            return;
        }
        ObjectNode r = JsonFiles.MAPPER.createObjectNode();
        r.set("preview", preview);
        ArrayNode arr = r.putArray("itens");
        for (ItemConf c : itens) {
            ObjectNode o = arr.addObject();
            o.put("existenteId", c.existente == null ? "" : c.existente.id());
            o.put("novo", c.novo);
            o.put("qtdCompra", c.qtdCompra.getText());
            o.put("unidadeCompra", c.unidadeCompra.getText());
            o.put("fator", c.fator.getText());
            o.put("custoCompra", c.custoCompra.getText());
            o.put("lote", c.lote.getText());
            o.put("validade", c.validade.getValue() == null ? "" : c.validade.getValue().toString());
            o.put("prazoPadrao", c.prazoPadrao.isSelected());
            o.put("atualizarPreco", c.atualizarPreco.isSelected());
            o.put("precoNovo", c.precoNovo.getText());
            o.put("codigoNovo", c.codigoNovo.getText());
            o.put("nomeNovo", c.nomeNovo.getText());
        }
        ctx.rascunhos.salvar(RASCUNHO, r);
    }

    private void aplicarRascunho(JsonNode arr) {
        for (int i = 0; i < itens.size() && i < arr.size(); i++) {
            ItemConf c = itens.get(i);
            JsonNode o = arr.get(i);
            String id = o.path("existenteId").asText("");
            c.existente = id.isBlank() ? null : ctx.catalogo.porId(id);
            c.novo = o.path("novo").asBoolean(false);
            c.qtdCompra.setText(o.path("qtdCompra").asText(""));
            c.unidadeCompra.setText(o.path("unidadeCompra").asText(""));
            c.fator.setText(o.path("fator").asText("1"));
            c.custoCompra.setText(o.path("custoCompra").asText(""));
            c.lote.setText(o.path("lote").asText(""));
            String v = o.path("validade").asText("");
            c.validade.setValue(v.isBlank() ? null : LocalDate.parse(v));
            c.prazoPadrao.setSelected(o.path("prazoPadrao").asBoolean(false));
            c.atualizarPreco.setSelected(o.path("atualizarPreco").asBoolean(false));
            c.precoNovo.setText(o.path("precoNovo").asText(""));
            c.codigoNovo.setText(o.path("codigoNovo").asText(""));
            c.nomeNovo.setText(o.path("nomeNovo").asText(""));
        }
    }

    // ------------------------------------------------------------------ confirmação

    /** Monta o corpo de POST /api/NfeImport/confirmar validando cada item (DomainException com a mensagem). */
    private ObjectNode montarRequisicao() {
        if (fornecedorOk != null && !fornecedorOk.isSelected()) {
            throw new DomainException("A entrada exige o cadastro do fornecedor da nota. Marque a opção para cadastrá-lo.");
        }
        ObjectNode req = JsonFiles.MAPPER.createObjectNode();
        if (preview.has("documento") && !preview.get("documento").isNull()) {
            req.set("documento", preview.get("documento"));
        }
        ObjectNode forn = req.putObject("fornecedor");
        JsonNode f = preview.path("fornecedor");
        for (String campo : new String[] {"cnpj", "companyName", "fantasyName", "cep", "city", "state", "address", "neighborhood",
            "number", "telephone"}) {
            forn.put(campo, f.path(campo).asText(""));
        }
        ArrayNode arr = req.putArray("itens");
        for (ItemConf c : itens) {
            int n = c.origem.path("numeroItem").asInt();
            if (c.pendente()) {
                throw new DomainException("Item " + n + ": vincule a um produto existente ou cadastre como novo.");
            }
            BigDecimal qtdCompra;
            BigDecimal fator;
            BigDecimal custoCompra;
            try {
                qtdCompra = Fmt.parse(c.qtdCompra.getText());
                fator = Fmt.parse(c.fator.getText());
                custoCompra = Fmt.parse(c.custoCompra.getText());
            } catch (DomainException e) {
                throw new DomainException("Item " + n + ": " + e.getMessage());
            }
            if (qtdCompra.signum() <= 0) {
                throw new DomainException("Item " + n + ": a quantidade deve ser maior que zero.");
            }
            if (fator.signum() <= 0) {
                throw new DomainException("Item " + n + ": o fator de conversão deve ser maior que zero.");
            }
            if (custoCompra.signum() < 0) {
                throw new DomainException("Item " + n + ": o custo não pode ser negativo.");
            }
            BigDecimal qtdEstoque = ConversaoUnidade.quantidadeEstoque(qtdCompra, fator);
            BigDecimal custoUnit = ConversaoUnidade.custoUnitario(custoCompra, fator);

            ObjectNode o = arr.addObject();
            o.put("numeroItem", n);
            o.put("codigoFornecedor", c.origem.path("codigoFornecedor").asText(""));
            o.put("gtin", c.origem.path("gtin").asText("SEM GTIN"));
            o.put("ncm", c.origem.path("ncm").asText("00000000"));
            if (c.origem.hasNonNull("cest")) {
                o.put("cest", c.origem.get("cest").asText());
            }
            o.put("quantidade", Fmt.qtyApi(qtdEstoque));
            o.put("precoCusto", Fmt.cost4Api(custoUnit));
            if (c.existente != null) {
                o.put("produtoExistenteId", c.existente.id());
                o.put("produtoExistenteNome", c.existente.nome());
                o.put("productCode", c.existente.codigo());
                o.put("productName", c.existente.nome());
                o.put("unidadeComercial", c.existente.unidade());
                o.put("precoVenda", Fmt.moneyApi(c.existente.preco()));
                if (c.atualizarPreco.isSelected()) {
                    try {
                        if (Fmt.parse(c.precoNovo.getText()).signum() <= 0) {
                            throw new DomainException("o preço de venda deve ser maior que zero.");
                        }
                    } catch (DomainException e) {
                        throw new DomainException("Item " + n + ": " + e.getMessage());
                    }
                }
                if (c.existente.controlaValidade() && c.validade.getValue() == null && !c.prazoPadrao.isSelected()) {
                    throw new DomainException("Item " + n + ": \"" + c.existente.nome()
                            + "\" controla validade. Informe a data de validade (ou marque o prazo padrão da categoria).");
                }
            } else {
                String codigo = c.codigoNovo.getText() == null ? "" : c.codigoNovo.getText().trim();
                String nome = c.nomeNovo.getText() == null ? "" : c.nomeNovo.getText().trim();
                if (codigo.isEmpty()) {
                    throw new DomainException("Item " + n + ": informe o código do produto novo.");
                }
                if (nome.length() < 3) {
                    throw new DomainException("Item " + n + ": informe o nome do produto novo (mínimo 3 letras).");
                }
                BigDecimal preco;
                try {
                    preco = Fmt.parse(c.precoNovo.getText());
                } catch (DomainException e) {
                    throw new DomainException("Item " + n + ": informe o preço de venda do produto novo.");
                }
                if (preco.signum() <= 0) {
                    throw new DomainException("Item " + n + ": o preço de venda deve ser maior que zero.");
                }
                o.put("productCode", codigo);
                o.put("productName", nome);
                o.put("unidadeComercial", c.origem.path("unidadeComercial").asText("UN"));
                o.put("precoVenda", Fmt.moneyApi(preco));
            }
            if (c.validade.getValue() != null && !c.prazoPadrao.isSelected()) {
                o.put("dataValidade", c.validade.getValue().toString());
            }
            if (c.lote.getText() != null && !c.lote.getText().isBlank()) {
                o.put("numeroLote", c.lote.getText().trim());
            }
        }
        return req;
    }

    private void confirmar() {
        ObjectNode req;
        try {
            req = montarRequisicao();
        } catch (DomainException e) {
            modal.erro(e.getMessage());
            return;
        }
        modal.erro(null);
        modal.ocupado(true);
        List<ItemConf> alvo = List.copyOf(itens);
        Fx.async(() -> {
            JsonNode resultado = ctx.entradas.confirmar(req);
            // Preço de venda dos produtos existentes marcados para atualizar (PATCH com a versão atual).
            List<String> falhas = new ArrayList<>();
            List<Produto> atuais = ctx.produtos.listar();
            for (ItemConf c : alvo) {
                if (c.existente != null && c.atualizarPreco.isSelected()) {
                    Produto atual = atuais.stream().filter(p -> p.id().equals(c.existente.id())).findFirst().orElse(null);
                    try {
                        if (atual == null) {
                            throw new DomainException("produto não encontrado");
                        }
                        ctx.produtos.atualizarPreco(atual.id(), Fmt.moneyApi(Fmt.parse(c.precoNovo.getText())), atual.versaoPreco());
                    } catch (RuntimeException e) {
                        falhas.add(c.existente.nome() + ": " + Fx.message(e));
                    }
                }
            }
            ctx.garantirCatalogo(true);
            return new Object[] {resultado, falhas};
        }, r -> {
            modal.ocupado(false);
            ctx.rascunhos.apagar(RASCUNHO);
            modal.fechar();
            @SuppressWarnings("unchecked")
            List<String> falhas = (List<String>) r[1];
            Toasts.success(dono, "Entrada confirmada: " + ((JsonNode) r[0]).path("produtosCriados").asInt() + " produtos criados, "
                    + ((JsonNode) r[0]).path("produtosAtualizados").asInt() + " atualizados.");
            if (!falhas.isEmpty()) {
                Modal.mensagem(dono, "Preço de venda não atualizado", String.join("\n", falhas));
            }
            if (aoConcluir != null) {
                aoConcluir.run();
            }
            List<EtiquetaOferta.Recebido> recebidos = new ArrayList<>();
            for (int i = 0; i < alvo.size(); i++) {
                ItemConf c = alvo.get(i);
                Produto p = c.existente != null ? ctx.catalogo.porId(c.existente.id())
                        : ctx.catalogo.todos().stream().filter(x -> x.codigo().equals(c.codigoNovo.getText().trim())).findFirst().orElse(null);
                if (p != null) {
                    recebidos.add(new EtiquetaOferta.Recebido(p.id(), p.nome(), c.qtdEstoque()));
                }
            }
            EtiquetaOferta.oferecer(dono, ctx, recebidos, irParaEtiquetas);
        }, t -> {
            modal.ocupado(false);
            modal.erro(Fx.message(t)); // nota repetida: mostra a mensagem da API e não duplica o estoque
        });
    }
}
