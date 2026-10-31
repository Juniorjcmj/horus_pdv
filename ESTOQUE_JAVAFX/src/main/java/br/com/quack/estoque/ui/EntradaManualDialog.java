package br.com.quack.estoque.ui;

import br.com.quack.estoque.AppContext;
import br.com.quack.estoque.api.CadastroApi;
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
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import javafx.collections.FXCollections;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

/**
 * Entrada manual (cupom, recibo ou sem documento): data, fornecedor opcional, número do documento, itens com
 * quantidade, unidade de compra, fator e custo, e anexo do comprovante. O OperacaoId é gerado ao abrir a janela:
 * reenviar a mesma entrada (clique duplo, queda de rede) não duplica o estoque.
 */
public final class EntradaManualDialog {
    private static final List<String> DOCUMENTOS = List.of("cupom", "recibo", "outro");

    private final AppContext ctx;
    private final Window dono;
    private final Perfil perfil;
    private final Runnable aoConcluir;
    private final Runnable irParaEtiquetas;
    private final String operacaoId = UUID.randomUUID().toString();
    private final List<Linha> linhas = new ArrayList<>();
    private final VBox listaLinhas = new VBox(8);
    private final DatePicker data = new DatePicker(LocalDate.now());
    private final ComboBox<String> tipoDoc = Ui.combo(DOCUMENTOS);
    private final TextField numero = Ui.campo("Número do documento (opcional)");
    private final TextField chave = Ui.campo("Chave de acesso (opcional, 44 dígitos)");
    private final TextField observacao = Ui.campo("Observação");
    private final ComboBox<CadastroApi.Fornecedor> fornecedor = Ui.combo(new ArrayList<>());
    private final Label anexoNome = Ui.muted("Nenhum comprovante anexado.");
    private File anexo;
    private Modal modal;

    private EntradaManualDialog(AppContext ctx, Window dono, Runnable aoConcluir, Runnable irParaEtiquetas) {
        this.ctx = ctx;
        this.dono = dono;
        this.perfil = ctx.session.perfil();
        this.aoConcluir = aoConcluir;
        this.irParaEtiquetas = irParaEtiquetas;
    }

    public static void abrir(AppContext ctx, Window dono, Runnable aoConcluir, Runnable irParaEtiquetas) {
        new EntradaManualDialog(ctx, dono, aoConcluir, irParaEtiquetas).montar();
    }

    /** Uma linha da entrada: produto existente + compra convertida. */
    private final class Linha {
        final Produto produto;
        final TextField qtdCompra = Ui.campo("Qtd.");
        final TextField unidadeCompra = Ui.campo("Un.");
        final TextField fator = Ui.campo("Fator");
        final TextField custo = Ui.campo("Custo da embalagem");
        final TextField lote = Ui.campo("Lote");
        final DatePicker validade = new DatePicker();
        final CheckBox alterarPreco = Ui.check("Alterar preço de venda", false);
        final TextField preco = Ui.campo("Novo preço");
        final Label calculo = Ui.muted("");
        final VBox card = new VBox(6);

        Linha(Produto p) {
            this.produto = p;
            card.getStyleClass().add("rg-card");
            qtdCompra.setText("1");
            unidadeCompra.setText(p.unidade());
            fator.setText("1");
            preco.setText(Fmt.dec2(p.preco()));
            preco.setDisable(true);
            validade.setMaxWidth(Double.MAX_VALUE);
            validade.setPromptText("Validade");
            GridPane g = new GridPane();
            g.setHgap(8);
            g.setVgap(6);
            g.add(Ui.rotulado("Qtd. comprada", qtdCompra), 0, 0);
            g.add(Ui.rotulado("Unidade da compra", unidadeCompra), 1, 0);
            g.add(Ui.rotulado("Fator", fator), 2, 0);
            g.add(Ui.rotulado("Custo da embalagem (R$)", custo), 3, 0);
            g.add(Ui.rotulado("Lote", lote), 0, 1);
            g.add(Ui.rotulado(p.controlaValidade() ? "Validade (obrigatória)" : "Validade", validade), 1, 1);
            g.add(alterarPreco, 2, 1);
            g.add(preco, 3, 1);
            for (int c = 0; c < 4; c++) {
                javafx.scene.layout.ColumnConstraints cc = new javafx.scene.layout.ColumnConstraints();
                cc.setPercentWidth(25);
                g.getColumnConstraints().add(cc);
            }
            Button remover = Ui.pequeno("Remover item", () -> {
                linhas.remove(this);
                listaLinhas.getChildren().remove(card);
            });
            card.getChildren().addAll(Ui.texto(p.codigo() + " · " + p.nome() + "  (estoque " + Fmt.qty(p.estoque()) + " " + p.unidade() + ")",
                    "rg-section-title"), g, calculo, remover);
            alterarPreco.selectedProperty().addListener((o, a, b) -> preco.setDisable(!b));
            for (TextField t : List.of(qtdCompra, fator, custo)) {
                t.textProperty().addListener((o, a, b) -> calcular());
            }
            calcular();
        }

        void calcular() {
            try {
                BigDecimal q = ConversaoUnidade.quantidadeEstoque(Fmt.parse(qtdCompra.getText()), Fmt.parse(fator.getText()));
                String extra = "";
                if (!custo.getText().isBlank()) {
                    extra = " · custo unitário R$ " + Fmt.cost4(ConversaoUnidade.custoUnitario(Fmt.parse(custo.getText()),
                            Fmt.parse(fator.getText())));
                }
                calculo.setText("Entra no estoque: " + Fmt.qty(q) + " " + produto.unidade() + " → saldo "
                        + Fmt.qty(produto.estoque().add(q)) + extra);
                calculo.setStyle("");
            } catch (RuntimeException e) {
                calculo.setText("Confira quantidade e fator.");
                calculo.setStyle("-fx-text-fill: -danger;");
            }
        }
    }

    private void montar() {
        tipoDoc.setValue("cupom");
        tipoDoc.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(String s) {
                return s == null ? "" : switch (s) {
                    case "cupom" -> "Cupom / NFC-e";
                    case "recibo" -> "Recibo";
                    default -> "Outro comprovante";
                };
            }

            @Override
            public String fromString(String s) {
                return s;
            }
        });
        data.setMaxWidth(Double.MAX_VALUE);
        fornecedor.setPromptText("Sem fornecedor");
        fornecedor.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(CadastroApi.Fornecedor f) {
                return f == null ? "" : f.nome();
            }

            @Override
            public CadastroApi.Fornecedor fromString(String s) {
                return null;
            }
        });

        GridPane cab = new GridPane();
        cab.setHgap(10);
        cab.setVgap(8);
        cab.add(Ui.rotulado("Data da compra", data), 0, 0);
        cab.add(Ui.rotulado("Tipo de comprovante", tipoDoc), 1, 0);
        cab.add(Ui.rotulado("Número", numero), 2, 0);
        cab.add(Ui.rotulado("Fornecedor (opcional)", fornecedor), 0, 1);
        cab.add(Ui.rotulado("Chave de acesso (cupom NFC-e)", chave), 1, 1, 2, 1);
        cab.add(Ui.rotulado("Observação", observacao), 0, 2, 3, 1);
        for (int c = 0; c < 3; c++) {
            javafx.scene.layout.ColumnConstraints cc = new javafx.scene.layout.ColumnConstraints();
            cc.setPercentWidth(33.3);
            cab.getColumnConstraints().add(cc);
        }
        fornecedor.setVisible(perfil.cadastrarCategoriasEFornecedores());
        fornecedor.setManaged(perfil.cadastrarCategoriasEFornecedores());

        Button add = Ui.primario("Adicionar produto…", () -> ProdutoPicker.escolher(modal.stage(), ctx, perfil.verCustos(), p -> {
            Linha l = new Linha(p);
            linhas.add(l);
            listaLinhas.getChildren().add(l.card);
            l.qtdCompra.requestFocus();
            l.qtdCompra.selectAll();
        }));
        Button anexar = Ui.botao("Anexar comprovante…", this::escolherAnexo);
        VBox corpo = new VBox(12, cab, Ui.barra(anexar, anexoNome), Ui.texto("Itens", "rg-section-title"), listaLinhas, Ui.barra(add));
        corpo.setMinWidth(760);
        modal = Modal.formularioRolavel(dono, "Entrada manual", "Cupom, recibo ou entrada sem nota fiscal.", corpo, "Confirmar entrada",
                820, 560, () -> {
                    confirmar();
                    return false;
                });
        modal.mostrar();
        if (perfil.cadastrarCategoriasEFornecedores()) {
            Fx.async(ctx.cadastros::fornecedores, lista -> fornecedor.setItems(FXCollections.observableArrayList(lista)), t -> {
            });
        }
        Fx.async(() -> {
            ctx.garantirCatalogo(false);
            return true;
        }, ok -> {
        }, t -> modal.erro(Fx.message(t)));
    }

    private void escolherAnexo() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Comprovante da compra");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Imagem, PDF ou XML", "*.jpg", "*.jpeg", "*.png", "*.pdf", "*.xml"));
        File f = fc.showOpenDialog(modal.stage());
        if (f == null) {
            return;
        }
        if (f.length() > 10L * 1024 * 1024) {
            modal.erro("O comprovante deve ter no máximo 10 MB.");
            return;
        }
        anexo = f;
        anexoNome.setText("Comprovante: " + f.getName());
        modal.erro(null);
    }

    private ObjectNode montarRequisicao() {
        if (linhas.isEmpty()) {
            throw new DomainException("Adicione ao menos um produto à entrada.");
        }
        if (data.getValue() == null) {
            throw new DomainException("Informe a data da compra.");
        }
        String chaveTxt = chave.getText() == null ? "" : chave.getText().replaceAll("\\D", "");
        if (!chaveTxt.isEmpty() && chaveTxt.length() != 44) {
            throw new DomainException("A chave de acesso tem 44 números.");
        }
        ObjectNode req = JsonFiles.MAPPER.createObjectNode();
        req.put("operacaoId", operacaoId);
        req.put("dataCompra", data.getValue().toString());
        if (fornecedor.getValue() != null) {
            req.put("fornecedorId", fornecedor.getValue().id());
        }
        req.put("tipoDocumento", tipoDoc.getValue());
        req.put("numeroDocumento", numero.getText() == null ? "" : numero.getText().trim());
        if (!chaveTxt.isEmpty()) {
            req.put("chaveAcesso", chaveTxt);
        }
        req.put("observacao", observacao.getText() == null ? "" : observacao.getText().trim());
        if (anexo != null) {
            try {
                ObjectNode c = req.putObject("comprovante");
                c.put("nome", anexo.getName());
                c.put("base64", Base64.getEncoder().encodeToString(Files.readAllBytes(anexo.toPath())));
            } catch (IOException e) {
                throw new DomainException("Não foi possível ler o comprovante: " + e.getMessage());
            }
        }
        ArrayNode arr = req.putArray("itens");
        int n = 0;
        for (Linha l : linhas) {
            n++;
            BigDecimal qtd;
            BigDecimal fator;
            BigDecimal custo;
            try {
                qtd = Fmt.parse(l.qtdCompra.getText());
                fator = Fmt.parse(l.fator.getText());
                custo = Fmt.parse(l.custo.getText());
            } catch (DomainException e) {
                throw new DomainException("Item " + n + " (" + l.produto.nome() + "): confira quantidade, fator e custo.");
            }
            if (qtd.signum() <= 0 || fator.signum() <= 0 || custo.signum() <= 0) {
                throw new DomainException("Item " + n + " (" + l.produto.nome() + "): quantidade, fator e custo devem ser maiores que zero.");
            }
            if (l.produto.controlaValidade() && l.validade.getValue() == null) {
                throw new DomainException("Item " + n + ": \"" + l.produto.nome() + "\" controla validade. Informe a validade.");
            }
            ObjectNode o = arr.addObject();
            o.put("numeroItem", n);
            o.put("produtoExistenteId", l.produto.id());
            o.put("produtoExistenteNome", l.produto.nome());
            o.put("productCode", l.produto.codigo());
            o.put("productName", l.produto.nome());
            o.put("unidadeComercial", l.produto.unidade());
            o.put("quantidadeCompra", Fmt.qtyApi(qtd));
            o.put("unidadeCompra", l.unidadeCompra.getText() == null || l.unidadeCompra.getText().isBlank() ? l.produto.unidade()
                    : l.unidadeCompra.getText().trim().toUpperCase());
            o.put("fatorConversao", Fmt.qtyApi(fator));
            o.put("custoCompra", Fmt.cost4Api(custo));
            o.put("quantidade", Fmt.qtyApi(ConversaoUnidade.quantidadeEstoque(qtd, fator)));
            o.put("precoCusto", Fmt.cost4Api(ConversaoUnidade.custoUnitario(custo, fator)));
            o.put("alterarPrecoVenda", l.alterarPreco.isSelected());
            if (l.alterarPreco.isSelected()) {
                BigDecimal preco = Fmt.parse(l.preco.getText());
                if (preco.signum() <= 0) {
                    throw new DomainException("Item " + n + ": o novo preço de venda deve ser maior que zero.");
                }
                o.put("precoVenda", Fmt.moneyApi(preco));
            } else {
                o.put("precoVenda", Fmt.moneyApi(l.produto.preco()));
            }
            o.put("estoqueAntes", Fmt.qtyApi(l.produto.estoque()));
            o.put("estoqueDepois", Fmt.qtyApi(l.produto.estoque().add(ConversaoUnidade.quantidadeEstoque(qtd, fator))));
            if (l.validade.getValue() != null) {
                o.put("dataValidade", l.validade.getValue().toString());
            }
            if (l.lote.getText() != null && !l.lote.getText().isBlank()) {
                o.put("numeroLote", l.lote.getText().trim());
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
        List<Linha> alvo = List.copyOf(linhas);
        Fx.async(() -> {
            JsonNode r = ctx.produtos.entradaManual(req);
            ctx.garantirCatalogo(true);
            return r;
        }, r -> {
            modal.ocupado(false);
            modal.fechar();
            Toasts.success(dono, "Entrada manual confirmada: " + alvo.size() + " item(ns).");
            if (aoConcluir != null) {
                aoConcluir.run();
            }
            List<EtiquetaOferta.Recebido> recebidos = new ArrayList<>();
            for (Linha l : alvo) {
                try {
                    recebidos.add(new EtiquetaOferta.Recebido(l.produto.id(), l.produto.nome(),
                            ConversaoUnidade.quantidadeEstoque(Fmt.parse(l.qtdCompra.getText()), Fmt.parse(l.fator.getText()))));
                } catch (RuntimeException ignored) {
                    // já validado ao montar
                }
            }
            EtiquetaOferta.oferecer(dono, ctx, recebidos, irParaEtiquetas);
        }, t -> {
            modal.ocupado(false);
            modal.erro(Fx.message(t));
        });
    }
}
