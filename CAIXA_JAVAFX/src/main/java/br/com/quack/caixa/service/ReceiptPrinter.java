package br.com.quack.caixa.service;

import br.com.quack.caixa.api.FiscalApi;
import br.com.quack.caixa.api.dto.CaixaStatusDto;
import br.com.quack.caixa.api.dto.VendaPayload;
import br.com.quack.caixa.domain.Company;
import br.com.quack.caixa.domain.DomainException;
import br.com.quack.caixa.domain.Money;
import br.com.quack.caixa.domain.PaymentType;
import br.com.quack.caixa.domain.SaleOutcome;
import br.com.quack.caixa.domain.fiscal.DanfeSnapshot;
import br.com.quack.caixa.domain.fiscal.DanfeXml;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.time.Instant;
import java.util.Locale;
import java.time.ZonedDateTime;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.print.PrinterJob;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;
import javafx.scene.text.Font;
import javafx.scene.text.FontSmoothingType;
import javafx.scene.text.Text;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

/**
 * Comprovantes do caixa, portados da web:
 * <ul>
 * <li>{@link #buildCupom} = buildReceiptPrintHtml (ReceiptPreviewModal.tsx): cupom não fiscal com QR Code;</li>
 * <li>{@link #buildDanfe} = buildDanfeSnapshotHtml (danfePrint.ts): DANFE NFC-e montado só do XML fiscal;</li>
 * <li>{@link #buildDanfeFor} = buildDanfePrintHtml: as mesmas checagens antes de imprimir a nota.</li>
 * </ul>
 */
public final class ReceiptPrinter {
    /** Largura útil do papel de 80 mm (72 mm a 96 dpi). */
    public static final double RECEIPT_WIDTH_PX = 272;
    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
    private static final String MONO = "Roboto Mono";

    private ReceiptPrinter() {
    }

    // =====================================================================
    // Cupom não fiscal (buildReceiptPrintHtml)
    // =====================================================================

    public static Node buildCupom(SaleOutcome o, Company company, FiscalApi.FiscalDoc fiscal) {
        VendaPayload p = o.payload();
        Paper r = new Paper(12);
        String companyName = company == null ? "Quack Sistemas" : nz(company.displayName()).isBlank()
                ? "Quack Sistemas" : company.displayName();

        r.center(companyName.toUpperCase(), true, 14);
        r.center(company == null || nz(company.corporateName()).isBlank() ? companyName : company.corporateName(),
                false, 12);
        r.center("CNPJ: " + (company == null || nz(company.cnpj()).isBlank() ? "-" : formatCnpj(company.cnpj())),
                false, 12);
        if (company != null && !company.streetLine().isBlank()) {
            r.center(company.streetLine(), false, 12);
        }
        if (company != null && !company.cityLine().isBlank()) {
            r.center(company.cityLine(), false, 12);
        }
        r.center("Telefone: " + (company == null || company.phoneOrSac().isBlank() ? "-" : company.phoneOrSac()),
                false, 12);
        r.divider();

        r.text("CUPOM NÃO FISCAL", false, 12);
        r.text("Venda: " + o.saleNumber(), false, 12);
        r.text("Emissão: " + formatDate(p.occurredAt()), false, 12);
        r.text("Operador: " + dash(p.operatorName()), false, 12);
        r.text("CPF/CNPJ consumidor: " + dash(p.customerCpf()), false, 12);
        r.divider();

        r.itemGrid("#", "ITEM", "QTD", "TOTAL", true);
        int idx = 1;
        for (VendaPayload.Item i : p.items()) {
            r.itemGrid(String.format("%02d", idx++), i.productName(), Money.formatQty(i.quantity()),
                    Money.formatBr(i.itemTotal()), false);
            r.itemMeta(i.productCode() + " - " + o.unitOf(i.productCode()) + " " + Money.formatBr(i.unitPrice()));
        }
        r.divider();

        r.row("TOTAL", "R$ " + p.totalAmount(), true, 12);
        for (VendaPayload.Payment pay : p.payments()) {
            r.row(PaymentType.fromWire(pay.paymentType()).label(), "R$ " + Money.formatBr(pay.amount()), false, 12);
            if ("dinheiro".equalsIgnoreCase(pay.paymentType().trim())) {
                if (pay.cashGiven() != null) {
                    r.row("Valor recebido", "R$ " + Money.formatBr(pay.cashGiven()), false, 12);
                }
                if (pay.changeAmount() != null) {
                    r.row("Troco", "R$ " + Money.formatBr(pay.changeAmount()), false, 12);
                }
            }
        }

        if (o.hasFiado()) {
            r.divider();
            r.center("*** COMPRA FIADO / A PRAZO ***", true, 12);
            if (o.fiadoSaldo() != null) {
                r.row("Novo Saldo Devedor:", "R$ " + Money.formatBr(o.fiadoSaldo()), true, 11);
            }
            r.signature("Assinatura do Cliente");
        }

        String qrUrl = fiscal == null || fiscal.qrCodeUrl() == null ? "" : fiscal.qrCodeUrl().trim();
        if (fiscal != null && fiscal.chaveAcesso() != null && !fiscal.chaveAcesso().isBlank()) {
            r.divider();
            r.center("CHAVE DE ACESSO:", false, 9.5);
            r.center(formatChave(fiscal.chaveAcesso()), true, 9.5);
        }
        String qrData = !qrUrl.isEmpty() ? qrUrl : String.join("\n", nonEmpty(
                "Venda: " + o.saleNumber(),
                "Data: " + formatDate(p.occurredAt()),
                company != null && !nz(company.cnpj()).isBlank() ? "CNPJ: " + company.cnpj() : "",
                "Total: R$ " + p.totalAmount(),
                "Pgto: " + paymentLabel(p),
                "Op: " + nz(p.operatorName())));
        r.divider();
        r.qr(qrData, 130, !qrUrl.isEmpty() ? "Consulta via leitor de QR Code" : "Dados da venda offline");
        r.divider();
        r.center("Obrigado pela preferência.", false, 12);
        return r.box;
    }

    // =====================================================================
    // DANFE NFC-e (buildDanfePrintHtml + buildDanfeSnapshotHtml)
    // =====================================================================

    /** Valida o documento da venda e monta o DANFE. Lança DomainException com a mensagem da web. */
    public static Node buildDanfeFor(SaleOutcome o, Company company, FiscalApi.FiscalDoc fiscal) {
        if (fiscal.status() != FiscalApi.AUTORIZADO && fiscal.status() != FiscalApi.CONTINGENCIA_PENDENTE) {
            throw new DomainException("A impressão ainda está sendo preparada. Aguarde e tente novamente.");
        }
        DanfeSnapshot snapshot = DanfeXml.parse(fiscal.xmlDocumento());
        if (fiscal.status() == FiscalApi.AUTORIZADO && !snapshot.autorizado()) {
            throw new DomainException("O XML de autorização ainda não está disponível. Aguarde e tente novamente.");
        }
        if (fiscal.chaveAcesso() != null && !fiscal.chaveAcesso().isBlank()
                && !snapshot.chave().equals(fiscal.chaveAcesso())) {
            throw new DomainException("O documento de impressão não corresponde à venda. Consulte a administração.");
        }
        String registered = company == null ? "" : nz(company.cnpj()).replaceAll("\\D", "");
        if (!registered.isEmpty() && !registered.equals(snapshot.emitente().cnpj())) {
            throw new DomainException("O documento de impressão pertence a outra empresa. Consulte a administração.");
        }
        return buildDanfe(snapshot);
    }

    public static Node buildDanfe(DanfeSnapshot d) {
        Paper r = new Paper(11);
        boolean pending = d.contingencia() && !d.autorizado();
        boolean adjustments = d.desconto().signum() > 0 || d.frete().signum() > 0 || d.seguro().signum() > 0
                || d.outros().signum() > 0;

        r.center(d.emitente().nome(), true, 13);
        if (!d.emitente().fantasia().isBlank() && !d.emitente().fantasia().equals(d.emitente().nome())) {
            r.center(d.emitente().fantasia(), false, 11);
        }
        r.center("CNPJ: " + DanfeXml.formatDocument(d.emitente().cnpj(), "CNPJ"), false, 11);
        r.center(d.emitente().endereco(), false, 11);
        r.divider();
        r.center("DANFE NFC-e", true, 11);
        r.center("Documento Auxiliar da Nota Fiscal de Consumidor Eletrônica", false, 11);
        if (pending) {
            r.warning("EMITIDA EM CONTINGÊNCIA", "Pendente de autorização");
        }
        if ("2".equals(d.ambiente())) {
            r.warning("EMITIDA EM AMBIENTE DE HOMOLOGAÇÃO", "SEM VALOR FISCAL");
        }
        r.divider();
        int idx = 1;
        for (DanfeSnapshot.Item i : d.itens()) {
            r.text(String.format("%02d", idx++) + " " + i.codigo() + " — " + i.descricao(), true, 11);
            r.row(Money.formatQty(i.quantidade()) + " " + i.unidade() + " × R$ " + Money.formatBr(i.unitario()),
                    "R$ " + Money.formatBr(i.total()), false, 11);
        }
        r.divider();
        r.row("Qtd. total de itens", String.valueOf(d.itens().size()), false, 11);
        r.row("Valor Total", "R$ " + Money.formatBr(d.total()), !adjustments, 11);
        if (d.frete().signum() > 0) {
            r.row("Frete", "R$ " + Money.formatBr(d.frete()), false, 11);
        }
        if (d.seguro().signum() > 0) {
            r.row("Seguro", "R$ " + Money.formatBr(d.seguro()), false, 11);
        }
        if (d.outros().signum() > 0) {
            r.row("Outras despesas", "R$ " + Money.formatBr(d.outros()), false, 11);
        }
        if (d.desconto().signum() > 0) {
            r.row("Desconto", "R$ " + Money.formatBr(d.desconto()), false, 11);
        }
        if (adjustments) {
            r.row("Valor a Pagar", "R$ " + Money.formatBr(d.pagar()), true, 11);
        }
        r.divider();
        r.text("FORMA DE PAGAMENTO / VALOR PAGO", true, 11);
        for (DanfeSnapshot.Pagamento pg : d.pagamentos()) {
            r.row(pg.forma(), "R$ " + Money.formatBr(pg.valor()), false, 11);
        }
        if (d.troco().signum() > 0) {
            r.row("Troco", "R$ " + Money.formatBr(d.troco()), true, 11);
        }
        r.divider();
        r.center("Consulte pela Chave de Acesso em:", false, 10);
        r.center(d.consulta(), false, 10);
        r.center(formatChave(d.chave()), false, 10);
        r.divider();
        if (d.consumidor() != null) {
            r.center("CONSUMIDOR " + d.consumidor().tipo() + ": "
                    + DanfeXml.formatDocument(d.consumidor().documento(), d.consumidor().tipo()), false, 10);
            if (!d.consumidor().nome().isBlank()) {
                r.center(d.consumidor().nome(), false, 10);
            }
            if (!d.consumidor().endereco().isBlank()) {
                r.center(d.consumidor().endereco(), false, 10);
            }
        } else {
            r.center("CONSUMIDOR NÃO IDENTIFICADO", false, 10);
        }
        r.divider();
        r.center("NFC-e nº " + formatNumeroNf(d.numero()) + " Série " + d.serie(), true, 10);
        r.center("Data de Emissão: " + formatDate(d.emitidoEm()), false, 10);
        if (d.protocolo() != null) {
            r.center("Protocolo de autorização: " + d.protocolo(), false, 10);
            r.center("Data de Autorização: " + formatDate(d.autorizadoEm()), false, 10);
        }
        if (pending) {
            r.warning("EMITIDA EM CONTINGÊNCIA", "Pendente de autorização");
        }
        r.qr(d.qrCode(), 113, "Consulta via leitor de QR Code");
        if (!d.mensagens().isEmpty() || d.tributos() != null) {
            r.divider();
            for (String m : d.mensagens()) {
                r.text(m, false, 10);
            }
            if (d.tributos() != null) {
                r.row("Tributos totais (Lei 12.741/2012)", "R$ " + Money.formatBr(d.tributos()), false, 10);
            }
        }
        return r.box;
    }

    // =====================================================================
    // Fechamento de caixa (buildSummaryPrintHtml de CashClosingSummaryModal.tsx)
    // =====================================================================

    public static Node buildCashClosing(CaixaStatusDto.Session session, Company company) {
        Paper r = new Paper(12);
        String fantasy = company == null || company.displayName().isBlank() ? "HORUS PDV" : company.displayName();
        String corporate = company == null || nz(company.corporateName()).isBlank() ? fantasy : company.corporateName();
        r.center(fantasy.toUpperCase(), true, 14);
        if (!corporate.equals(fantasy)) {
            r.center(corporate, false, 12);
        }
        if (company != null && !nz(company.cnpj()).isBlank()) {
            r.center("CNPJ: " + formatCnpj(company.cnpj()), false, 12);
        }
        if (company != null && !company.streetLine().isBlank()) {
            r.center(company.streetLine(), false, 12);
        }
        if (company != null && !company.cityLine().isBlank()) {
            r.center(company.cityLine(), false, 12);
        }
        String phones = company == null ? "" : String.join(" / ", nonEmpty(company.phone(), company.sacPhone()));
        if (!phones.isBlank()) {
            r.center("Tel: " + phones, false, 12);
        }
        r.divider();

        r.center("FECHAMENTO DE CAIXA", true, 12.5);
        r.center("RESUMO DE TURNO / CONFERÊNCIA GERENCIAL", false, 10);
        r.divider();

        String turno = nz(session.id());
        r.row("Turno ID:", "#" + turno.substring(0, Math.min(8, turno.length())).toUpperCase(Locale.ROOT), false, 12);
        r.row("Abertura:", formatDate(session.openedAt()), false, 12);
        r.row("Fechamento:", formatDate(session.closedAt() == null ? Instant.now().toString() : session.closedAt()),
                false, 12);
        r.row("Duração:", elapsed(session.elapsedMinutes()), false, 12);
        r.row("Operador Abertura:", dash(session.operatorName()), false, 12);
        String closedBy = nz(session.closedByName()).isBlank() ? dash(session.operatorName()) : session.closedByName();
        r.row("Fechado Por:", closedBy, false, 12);
        r.divider();

        // Vendas por forma de pagamento
        r.text("VENDAS POR FORMA DE PAGAMENTO", true, 11);
        List<CaixaStatusDto.Breakdown> breakdown = session.paymentBreakdown() == null ? List.of()
                : session.paymentBreakdown();
        BigDecimal totalVendas = BigDecimal.ZERO;
        BigDecimal vendasDinheiro = BigDecimal.ZERO;
        for (CaixaStatusDto.Breakdown b : breakdown) {
            BigDecimal v = money(b.total());
            totalVendas = totalVendas.add(v);
            if ("dinheiro".equalsIgnoreCase(nz(b.paymentType()))) {
                vendasDinheiro = vendasDinheiro.add(v);
            }
            r.row(closingPaymentLabel(b.paymentType()), "R$ " + Money.formatBr(v), false, 12);
        }
        if (breakdown.isEmpty()) {
            r.row("Nenhuma venda registrada", "R$ 0,00", false, 12);
        }
        r.thinDivider();
        r.row("TOTAL VENDIDO NO TURNO", "R$ " + Money.formatBr(totalVendas), true, 12);
        r.divider();

        // Sangrias e reforços detalhados
        r.text("SANGRIA E REFORÇOS DETALHADOS", true, 11);
        List<CaixaStatusDto.Movement> movs = session.movimentos() == null ? List.of() : session.movimentos();
        BigDecimal reforcos = BigDecimal.ZERO;
        BigDecimal sangrias = BigDecimal.ZERO;
        int i = 1;
        for (CaixaStatusDto.Movement m : movs) {
            boolean sangria = "sangria".equalsIgnoreCase(nz(m.tipo()));
            BigDecimal v = money(m.valor());
            if (sangria) {
                sangrias = sangrias.add(v);
            } else {
                reforcos = reforcos.add(v);
            }
            r.row(String.format("%02d. ", i++) + (sangria ? "(-) SANGRIA" : "(+) REFORÇO"), "R$ " + Money.formatBr(v),
                    true, 12);
            r.row("Data/Hora: " + formatDate(m.createdAt()), "Op: " + dash(m.operatorName()), false, 10.5);
            r.motivo("Motivo: " + (nz(m.motivo()).isBlank() ? "Não informado" : m.motivo()));
        }
        if (movs.isEmpty()) {
            r.center("Nenhuma sangria ou reforço registrado.", false, 11);
        }
        r.thinDivider();
        r.row("(+) TOTAL REFORÇOS:", "R$ " + Money.formatBr(reforcos), true, 12);
        r.row("(-) TOTAL SANGRIAS:", "R$ " + Money.formatBr(sangrias), true, 12);
        r.divider();

        // Conferência da gaveta
        r.text("CONFERÊNCIA DA GAVETA (DINHEIRO)", true, 11);
        r.row("(+) Fundo de Troco (Abertura):", "R$ " + Money.formatBr(money(session.openingAmount())), false, 12);
        r.row("(+) Vendas em Dinheiro:", "R$ " + Money.formatBr(vendasDinheiro), false, 12);
        r.row("(+) Reforços no Caixa:", "R$ " + Money.formatBr(reforcos), false, 12);
        r.row("(-) Sangrias do Caixa:", "R$ " + Money.formatBr(sangrias), false, 12);
        r.thinDivider();
        r.row("(=) Dinheiro Esperado em Gaveta:", "R$ " + Money.formatBr(money(session.expectedCashAmount())), true,
                12);
        r.row("(=) Dinheiro Informado / Contado:", "R$ " + Money.formatBr(money(session.closingAmount())), true, 12);
        BigDecimal diff = money(session.differenceAmount());
        String status = diff.signum() == 0 ? " (EXATO)" : diff.signum() < 0 ? " (FALTA)" : " (SOBRA)";
        r.diffBox("DIFERENÇA: R$ " + Money.formatBr(diff) + status,
                nz(session.differenceReason()).isBlank() ? null
                        : "Justificativa da diferença: " + session.differenceReason(),
                diff.signum() != 0);

        if (!nz(session.note()).isBlank()) {
            r.divider();
            r.text("OBSERVAÇÕES DO FECHAMENTO", true, 11);
            r.text(session.note(), false, 11);
        }
        r.divider();

        // Assinaturas para auditoria
        r.signatureBlock("-".equals(closedBy) ? "Operador de Caixa" : closedBy, "Operador Responsável pelo Turno");
        r.signatureBlock("Gerente / Conferente", "Visto e Conferência Administrativa");
        r.center("Emissão: " + FMT.format(ZonedDateTime.now(SAO_PAULO)), false, 9.5);
        r.center("HORUS PDV - Sistema de Automação Comercial", false, 9.5);
        return r.box;
    }

    private static String closingPaymentLabel(String wire) {
        String w = nz(wire).trim().toLowerCase(Locale.ROOT);
        return switch (w) {
            case "dinheiro" -> "Dinheiro";
            case "pix" -> "PIX";
            case "debito" -> "Cartão Débito";
            case "credito" -> "Cartão Crédito";
            case "fiado" -> "Fiado / A Prazo";
            case "outros" -> "Outros";
            default -> nz(wire);
        };
    }

    private static String elapsed(long minutes) {
        if (minutes < 1) {
            return "menos de 1 min";
        }
        long h = minutes / 60;
        long m = minutes % 60;
        return h == 0 ? m + " min" : h + "h " + String.format("%02d", m) + "min";
    }

    private static BigDecimal money(String v) {
        try {
            return v == null || v.isBlank() ? BigDecimal.ZERO : Money.parseBr(v);
        } catch (DomainException e) {
            return BigDecimal.ZERO;
        }
    }

    // =====================================================================
    // Cancelamento e devolução de nota (PdvNfceCancelModal.tsx)
    // =====================================================================

    /** Comprovante de cancelamento (buildCancellationPrintHtml). */
    public static Node buildCancelamento(FiscalApi.FiscalDoc doc, FiscalApi.CancelResult result, String justificativa) {
        Paper r = new Paper(12);
        boolean m55 = doc.modelo55();
        r.center("COMPROVANTE DE CANCELAMENTO", true, 14);
        r.center(m55 ? "NF-e (MODELO 55)" : "NFC-e (MODELO 65)", false, 10);
        r.divider();
        r.row(m55 ? "NF-e Cancelada:" : "NFC-e Cancelada:", "Nº " + formatNumeroNf(str(doc.numeroNf())), true, 12);
        r.row("Série:", str(doc.serie()), false, 12);
        r.row("Venda:", "#" + nz(doc.saleNumber()), true, 12);
        r.row("Valor:", "R$ " + dash(doc.totalAmount()), true, 12);
        r.divider();
        r.text("CHAVE DE ACESSO:", true, 12);
        r.text(formatChave(doc.chaveAcesso()), false, 10);
        r.divider();
        r.row("Prot. Cancelamento:", dash(result == null ? null : result.protocoloCancelamento()), true, 12);
        r.row("Data Cancelamento:", FMT.format(ZonedDateTime.now(SAO_PAULO)), false, 12);
        r.row("Autorizado por:", result == null || nz(result.supervisorNome()).isBlank() ? "Gerente"
                : result.supervisorNome(), true, 12);
        r.divider();
        r.text("JUSTIFICATIVA:", true, 12);
        r.text(nz(justificativa), false, 10);
        r.divider();
        r.center("CANCELAMENTO HOMOLOGADO NA SEFAZ", true, 10);
        r.center("MERCADORIAS ESTORNADAS AO ESTOQUE", true, 10);
        return r.box;
    }

    /** Comprovante térmico da devolução, com assinatura do cliente (buildDevolucaoPrintHtml). */
    public static Node buildDevolucao(FiscalApi.FiscalDoc doc, FiscalApi.DevolucaoResult result, String justificativa) {
        Paper r = new Paper(12);
        r.center("COMPROVANTE DE DEVOLUÇÃO", true, 13);
        r.center("NF-e DE ENTRADA (MODELO 55)", false, 10);
        r.divider();
        r.row("NF-e Entrada:", "Nº " + formatNumeroNf(str(result.numeroNfe())) + " / Série " + str(result.serieNfe()),
                true, 12);
        r.row((doc.modelo55() ? "NF-e" : "NFC-e") + " Referenciada:", "Nº " + formatNumeroNf(str(doc.numeroNf())),
                true, 12);
        r.row("Venda Origem:", "#" + nz(doc.saleNumber()), true, 12);
        r.row("Valor Total:", "R$ " + dash(doc.totalAmount()), true, 12);
        r.divider();
        r.text("CHAVE NF-e DEVOLUÇÃO:", true, 12);
        r.text(formatChave(result.chaveAcesso()), false, 10);
        r.divider();
        r.row("Prot. Autorização:", dash(result.protocolo()), true, 12);
        r.row("Data/Hora:", FMT.format(ZonedDateTime.now(SAO_PAULO)), false, 12);
        r.row("Autorizado por:", nz(result.supervisorNome()).isBlank() ? "Gerente" : result.supervisorNome(), true, 12);
        r.row("Destinatário/Remetente:", nz(result.destinatarioNome()).isBlank() ? "Entrada Própria"
                : result.destinatarioNome(), true, 12);
        r.divider();
        r.text("JUSTIFICATIVA:", true, 12);
        r.text(nz(justificativa), false, 10);
        r.divider();
        r.signatureBlock("Assinatura do Cliente / Recebedor", "Atesto a devolução das mercadorias e estorno financeiro");
        r.center("DEVOLUÇÃO FISCAL HOMOLOGADA NA SEFAZ", true, 10);
        r.center("MERCADORIAS ESTORNADAS AO ESTOQUE", true, 10);
        return r.box;
    }

    /** DANFE A4 simplificado da NF-e de devolução (buildDevolucaoDanfeA4Html). */
    public static Node buildDevolucaoA4(FiscalApi.FiscalDoc doc, FiscalApi.DevolucaoResult result,
            String justificativa) {
        double w = 720;
        String quando = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").format(ZonedDateTime.now(SAO_PAULO));
        String ref = doc.modelo55() ? "NF-e" : "NFC-e";
        VBox page = new VBox(6);
        page.setPadding(new Insets(20));
        page.setPrefWidth(w + 40);
        page.setStyle("-fx-background-color: white;");

        VBox left = new VBox(2, a4("DOCUMENTO AUXILIAR DA NOTA FISCAL ELETRÔNICA", true, 14),
                a4("0 - ENTRADA    [ 0 ]", true, 11),
                a4("Nº " + formatNumeroNf(str(result.numeroNfe())) + "    SÉRIE: " + str(result.serieNfe()), false, 11),
                a4("NATUREZA DA OPERAÇÃO: DEVOLUÇÃO DE VENDA", false, 11));
        VBox right = new VBox(2, a4("CHAVE DE ACESSO", true, 11), a4(formatChave(result.chaveAcesso()), true, 12),
                a4("PROTOCOLO SEFAZ: " + dash(result.protocolo()) + " - " + quando, false, 11));
        right.setAlignment(Pos.TOP_RIGHT);
        HBox.setHgrow(left, Priority.ALWAYS);
        HBox head = new HBox(12, left, right);
        page.getChildren().add(a4Box(head, w));

        page.getChildren().add(a4Box(new VBox(2, a4("DESTINATÁRIO / REMETENTE", true, 11),
                a4("NOME / RAZÃO SOCIAL: " + (nz(result.destinatarioNome()).isBlank() ? "ENTRADA PRÓPRIA"
                        : result.destinatarioNome()) + "    DATA DA EMISSÃO: " + quando, false, 11)), w));
        page.getChildren().add(a4Box(new VBox(2, a4("DOCUMENTOS FISCAIS REFERENCIADOS", true, 11),
                a4(ref + " Referenciada (Modelo " + (doc.modelo55() ? "55" : "65") + "): "
                        + formatChave(doc.chaveAcesso()), false, 11),
                a4("Venda de Origem: #" + nz(doc.saleNumber()) + "  |  " + ref + " Original Nº: "
                        + formatNumeroNf(str(doc.numeroNf())), false, 11)), w));
        page.getChildren().add(a4Box(new VBox(2, a4("CÁLCULO DO IMPOSTO", true, 11),
                a4("VALOR TOTAL DOS PRODUTOS: R$ " + dash(doc.totalAmount()) + "    VALOR DO FRETE: R$ 0,00"
                        + "    VALOR TOTAL DA NOTA: R$ " + dash(doc.totalAmount()), false, 11)), w));
        page.getChildren().add(a4Box(new VBox(2, a4("DADOS ADICIONAIS / INFORMAÇÕES COMPLEMENTARES", true, 11),
                a4("Devolução de venda de mercadorias referente ao documento fiscal (" + ref + " Modelo "
                        + (doc.modelo55() ? "55" : "65") + ") chave " + nz(doc.chaveAcesso()) + ".", false, 10),
                a4("Justificativa: " + nz(justificativa) + ".", false, 10),
                a4("Supervisor Responsável: " + nz(result.supervisorNome()) + ".", false, 10)), w));

        Line l1 = new Line(0, 0, w * 0.45, 0);
        Line l2 = new Line(0, 0, w * 0.45, 0);
        VBox s1 = new VBox(4, l1, a4("Assinatura do Recebedor / Cliente", false, 11));
        VBox s2 = new VBox(4, l2, a4("Visto do Supervisor / Gerente", false, 11));
        s1.setAlignment(Pos.CENTER);
        s2.setAlignment(Pos.CENTER);
        HBox sig = new HBox(w * 0.1, s1, s2);
        sig.setAlignment(Pos.CENTER);
        VBox.setMargin(sig, new Insets(40, 0, 0, 0));
        page.getChildren().add(sig);
        return page;
    }

    private static Text a4(String value, boolean bold, double px) {
        Text t = new Text(value == null ? "" : value);
        t.setFont(Font.font("Nunito", bold ? FontWeight.BOLD : FontWeight.NORMAL, px));
        t.setFill(Color.BLACK);
        t.setFontSmoothingType(FontSmoothingType.GRAY);
        return t;
    }

    private static VBox a4Box(Node content, double w) {
        VBox b = new VBox(content);
        b.setStyle("-fx-border-color: black; -fx-border-width: 1; -fx-padding: 6;");
        b.setPrefWidth(w);
        b.setMaxWidth(w);
        return b;
    }

    private static String str(Integer v) {
        return v == null ? "" : String.valueOf(v);
    }

    // =====================================================================
    // Impressão
    // =====================================================================

    /** Fila de impressão: uma impressão por vez, fora da linha da tela. */
    private static final java.util.concurrent.ExecutorService PRINT_QUEUE =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "quack-impressao");
                t.setDaemon(true);
                return t;
            });

    /**
     * Imprime SEM travar a tela: monta o comprovante aqui (FX thread) e envia à impressora em segundo plano.
     * Um driver lento ou parado (ex.: esperando aceite de licença) não congela mais o caixa.
     * done recebe null quando foi para a impressora, ou a mensagem de erro (sempre na FX thread).
     */
    public static void printAsync(Node receipt, java.util.function.Consumer<String> done) {
        prepare(receipt);
        PRINT_QUEUE.submit(() -> {
            String error = null;
            try {
                printPrepared(receipt);
            } catch (RuntimeException e) {
                error = e instanceof DomainException ? e.getMessage()
                        : e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            String result = error;
            javafx.application.Platform.runLater(() -> done.accept(result));
        });
    }

    /** Impressão bloqueante (testes/ferramentas). Prefira {@link #printAsync}. */
    public static void print(Node receipt) {
        prepare(receipt);
        printPrepared(receipt);
    }

    /** Um nó fora de qualquer cena não tem CSS nem layout calculados e sairia em branco. */
    private static void prepare(Node receipt) {
        if (receipt.getScene() == null) {
            new javafx.scene.Scene(new javafx.scene.Group(receipt));
        }
        receipt.applyCss();
        if (receipt instanceof javafx.scene.Parent parent) {
            parent.layout();
        }
    }

    /** O nó já preparado não está numa janela visível, então pode ser impresso fora da FX thread. */
    private static void printPrepared(Node receipt) {
        PrinterJob job = PrinterJob.createPrinterJob();
        if (job == null) {
            throw new DomainException("Nenhuma impressora disponível");
        }
        try {
            if (job.printPage(receipt)) {
                job.endJob();
            } else {
                job.cancelJob();
                throw new DomainException("A impressão não foi concluída.");
            }
        } catch (DomainException e) {
            throw e;
        } catch (RuntimeException e) {
            job.cancelJob();
            throw new DomainException("Falha ao imprimir: " + e.getMessage());
        }
    }

    // =====================================================================
    // Formatação (mesmas regras de danfePrint.ts)
    // =====================================================================

    public static String formatChave(String chave) {
        return chave == null || chave.isBlank() ? "—" : chave.replaceAll("(\\d{4})(?=\\d)", "$1 ").trim();
    }

    public static String formatNumeroNf(String numero) {
        if (numero == null || numero.isBlank()) {
            return "—";
        }
        String n = numero.length() >= 9 ? numero : "0".repeat(9 - numero.length()) + numero;
        return n.replaceFirst("^(\\d{3})(\\d{3})(\\d{3})$", "$1.$2.$3");
    }

    /** Data/hora de Brasília "dd/MM/yyyy HH:mm:ss" (formatDateTime / formatReceiptDate). */
    public static String formatDate(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        String v = value.trim();
        if (v.matches("^\\d{2}/\\d{2}/\\d{4}.*")) {
            return v;
        }
        try {
            return FMT.format(OffsetDateTime.parse(v).atZoneSameInstant(SAO_PAULO));
        } catch (RuntimeException e) {
            try {
                return FMT.format(Instant.parse(v).atZone(SAO_PAULO));
            } catch (RuntimeException e2) {
                return v;
            }
        }
    }

    private static String formatCnpj(String raw) {
        String d = raw == null ? "" : raw.replaceAll("\\D", "");
        return d.length() == 14 ? DanfeXml.formatDocument(d, "CNPJ") : nz(raw);
    }

    private static String paymentLabel(VendaPayload p) {
        if (p.payments().size() == 1) {
            return PaymentType.fromWire(p.payments().get(0).paymentType()).label();
        }
        List<String> labels = new ArrayList<>();
        for (VendaPayload.Payment pay : p.payments()) {
            labels.add(PaymentType.fromWire(pay.paymentType()).label());
        }
        return "Múltiplos (" + String.join(" + ", labels) + ")";
    }

    private static List<String> nonEmpty(String... values) {
        List<String> out = new ArrayList<>();
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                out.add(v);
            }
        }
        return out;
    }

    private static String dash(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    // =====================================================================
    // "Papel": monta o comprovante como nós JavaFX (equivalente ao HTML da web)
    // =====================================================================

    private static final class Paper {
        final VBox box = new VBox(1);
        private final double baseSize;

        Paper(double baseSizePx) {
            this.baseSize = baseSizePx;
            box.setPrefWidth(RECEIPT_WIDTH_PX + 16);
            box.setMaxWidth(RECEIPT_WIDTH_PX + 16);
            box.setPadding(new Insets(10, 8, 12, 8));
            box.setStyle("-fx-background-color: white;");
        }

        /**
         * Texto do comprovante: Text (e não Label) para fixar preto e suavização em cinza no próprio nó.
         * A suavização LCD pinta as bordas das letras de azul/vermelho; e o papel não depende do CSS da tela.
         */
        private Text text(String value, boolean bold, double px, double wrap) {
            Text t = new Text(value == null ? "" : value);
            t.setFont(Font.font(MONO, bold ? FontWeight.BOLD : FontWeight.NORMAL, px));
            t.setFill(Color.BLACK);
            t.setFontSmoothingType(FontSmoothingType.GRAY);
            if (wrap > 0) {
                t.setWrappingWidth(wrap);
            }
            return t;
        }

        void text(String value, boolean bold, double px) {
            box.getChildren().add(text(value, bold, px, RECEIPT_WIDTH_PX));
        }

        void center(String value, boolean bold, double px) {
            Text t = text(value, bold, px, RECEIPT_WIDTH_PX);
            t.setTextAlignment(TextAlignment.CENTER);
            box.getChildren().add(t);
        }

        void row(String left, String right, boolean bold, double px) {
            Text b = text(right, bold, px, 0);
            double rightWidth = b.getLayoutBounds().getWidth();
            Text a = text(left, bold, px, Math.max(60, RECEIPT_WIDTH_PX - rightWidth - 8));
            Region sp = new Region();
            HBox.setHgrow(sp, Priority.ALWAYS);
            HBox h = new HBox(8, a, sp, b);
            h.setMaxWidth(RECEIPT_WIDTH_PX);
            h.setPrefWidth(RECEIPT_WIDTH_PX);
            box.getChildren().add(h);
        }

        /** Grade "# ITEM QTD TOTAL" com colunas 24 / livre / 34 / 58 px, como no CSS da web. */
        void itemGrid(String n, String item, String qtd, String total, boolean bold) {
            GridPane g = new GridPane();
            g.setHgap(4);
            g.setPrefWidth(RECEIPT_WIDTH_PX);
            g.setMaxWidth(RECEIPT_WIDTH_PX);
            ColumnConstraints c0 = new ColumnConstraints(24);
            ColumnConstraints c1 = new ColumnConstraints();
            c1.setHgrow(Priority.ALWAYS);
            ColumnConstraints c2 = new ColumnConstraints(34);
            c2.setHalignment(HPos.RIGHT);
            ColumnConstraints c3 = new ColumnConstraints(58);
            c3.setHalignment(HPos.RIGHT);
            g.getColumnConstraints().addAll(c0, c1, c2, c3);
            double itemWidth = RECEIPT_WIDTH_PX - 24 - 34 - 58 - 3 * 4;
            g.add(text(n, bold, baseSize, 0), 0, 0);
            g.add(text(item, bold, baseSize, itemWidth), 1, 0);
            g.add(text(qtd, bold, baseSize, 0), 2, 0);
            g.add(text(total, bold, baseSize, 0), 3, 0);
            if (!bold) {
                VBox.setMargin(g, new Insets(5, 0, 0, 0));
            }
            box.getChildren().add(g);
        }

        void itemMeta(String value) {
            HBox h = new HBox(text(value, false, 11, RECEIPT_WIDTH_PX - 28));
            h.setPadding(new Insets(0, 0, 0, 28));
            box.getChildren().add(h);
        }

        void divider() {
            Line line = new Line(0, 0, RECEIPT_WIDTH_PX, 0);
            line.getStrokeDashArray().addAll(4.0, 3.0);
            line.setStroke(Color.web("#475569"));
            VBox.setMargin(line, new Insets(6, 0, 6, 0));
            box.getChildren().add(line);
        }

        void warning(String title, String sub) {
            Text t1 = text(title, true, 11, RECEIPT_WIDTH_PX - 10);
            Text t2 = text(sub, false, 11, RECEIPT_WIDTH_PX - 10);
            t1.setTextAlignment(TextAlignment.CENTER);
            t2.setTextAlignment(TextAlignment.CENTER);
            VBox w = new VBox(0, t1, t2);
            w.setAlignment(Pos.CENTER);
            w.setStyle("-fx-border-color: black; -fx-border-width: 1; -fx-padding: 4;");
            w.setMaxWidth(RECEIPT_WIDTH_PX);
            VBox.setMargin(w, new Insets(6, 0, 6, 0));
            box.getChildren().add(w);
        }

        void signature(String caption) {
            Line line = new Line(0, 0, RECEIPT_WIDTH_PX - 40, 0);
            line.setStroke(Color.BLACK);
            VBox s = new VBox(4, line, text(caption, false, 10, 0));
            s.setAlignment(Pos.CENTER);
            s.setMaxWidth(RECEIPT_WIDTH_PX);
            s.setPrefWidth(RECEIPT_WIDTH_PX);
            VBox.setMargin(s, new Insets(24, 0, 0, 0));
            box.getChildren().add(s);
        }

        /** Linha pontilhada fina (separador dentro de uma seção). */
        void thinDivider() {
            Line line = new Line(0, 0, RECEIPT_WIDTH_PX, 0);
            line.getStrokeDashArray().addAll(1.5, 2.5);
            line.setStroke(Color.web("#94a3b8"));
            VBox.setMargin(line, new Insets(3, 0, 3, 0));
            box.getChildren().add(line);
        }

        /** Motivo da sangria/reforço: faixa com barra preta à esquerda (como .mov-motivo da web). */
        void motivo(String value) {
            HBox h = new HBox(text(value, false, 11, RECEIPT_WIDTH_PX - 10));
            h.setStyle("-fx-background-color: #f8fafc; -fx-border-color: black; -fx-border-width: 0 0 0 2;"
                    + " -fx-padding: 2 4 2 4;");
            h.setMaxWidth(RECEIPT_WIDTH_PX);
            VBox.setMargin(h, new Insets(2, 0, 5, 0));
            box.getChildren().add(h);
        }

        /** Caixa da diferença: borda grossa quando há falta/sobra (como .diff-box da web). */
        void diffBox(String title, String reason, boolean strong) {
            Text t = text(title, true, 12, RECEIPT_WIDTH_PX - 14);
            t.setTextAlignment(TextAlignment.CENTER);
            VBox v = new VBox(3, t);
            if (reason != null) {
                v.getChildren().add(text(reason, false, 10.5, RECEIPT_WIDTH_PX - 14));
            }
            v.setAlignment(Pos.CENTER);
            v.setStyle("-fx-border-color: " + (strong ? "black" : "#64748b") + "; -fx-border-width: "
                    + (strong ? 2 : 1) + "; -fx-padding: 6; -fx-background-color: "
                    + (strong ? "#fef2f2" : "#f0fdf4") + ";");
            v.setMaxWidth(RECEIPT_WIDTH_PX);
            VBox.setMargin(v, new Insets(6, 0, 0, 0));
            box.getChildren().add(v);
        }

        /** Linha de assinatura com nome em negrito e descrição. */
        void signatureBlock(String name, String sub) {
            Line line = new Line(0, 0, RECEIPT_WIDTH_PX * 0.85, 0);
            line.setStroke(Color.BLACK);
            Text n = text(name, true, 10.5, 0);
            Text d = text(sub, false, 9.5, 0);
            d.setFill(Color.web("#475569"));
            VBox v = new VBox(3, line, n, d);
            v.setAlignment(Pos.CENTER);
            v.setMaxWidth(RECEIPT_WIDTH_PX);
            v.setPrefWidth(RECEIPT_WIDTH_PX);
            VBox.setMargin(v, new Insets(26, 0, 4, 0));
            box.getChildren().add(v);
        }

        void qr(String data, double size, String caption) {
            if (data == null || data.isBlank()) {
                return;
            }
            StackPane qrBox = new StackPane(qrCanvas(data, size));
            qrBox.setPadding(new Insets(6));
            qrBox.setStyle("-fx-background-color: white;");
            VBox v = new VBox(4, qrBox, text(caption, false, 9, 0));
            v.setAlignment(Pos.CENTER);
            v.setMaxWidth(RECEIPT_WIDTH_PX);
            v.setPrefWidth(RECEIPT_WIDTH_PX);
            VBox.setMargin(v, new Insets(4, 0, 4, 0));
            box.getChildren().add(v);
        }
    }

    /** QR Code nível M sem margem (mesmos parâmetros do QRCodeSVG da web), desenhado pixel a pixel. */
    static Canvas qrCanvas(String data, double size) {
        BitMatrix m;
        try {
            m = new QRCodeWriter().encode(data, BarcodeFormat.QR_CODE, 0, 0, Map.of(
                    EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
                    EncodeHintType.MARGIN, 0,
                    EncodeHintType.CHARACTER_SET, "UTF-8"));
        } catch (WriterException e) {
            throw new DomainException("Não foi possível gerar o QR Code do comprovante.");
        }
        int n = m.getWidth();
        double cell = Math.max(1, Math.floor(size / n));
        double side = cell * n;
        Canvas c = new Canvas(side, side);
        GraphicsContext g = c.getGraphicsContext2D();
        g.setFill(Color.WHITE);
        g.fillRect(0, 0, side, side);
        g.setFill(Color.BLACK);
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                if (m.get(x, y)) {
                    g.fillRect(x * cell, y * cell, cell, cell);
                }
            }
        }
        return c;
    }
}
