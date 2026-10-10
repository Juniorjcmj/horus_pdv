import { getDesktopBridge } from "@/infrastructure/desktop/bridge";

/** Envia o cupom ao programa instalado; mantém a impressão pelo navegador nas versões anteriores. */
export async function printSaleReceipt(html: string): Promise<void> {
  const printer = getDesktopBridge()?.printer;
  if (printer?.printReceipt) {
    const result = await printer.printReceipt(html);
    if (!result.printed) throw new Error(result.error || "A impressora não confirmou o envio do cupom.");
    return;
  }

  const url = URL.createObjectURL(new Blob([html], { type: "text/html;charset=utf-8" }));
  const popup = window.open(url, "_blank", "width=420,height=720");
  if (!popup) {
    URL.revokeObjectURL(url);
    throw new Error("A janela de impressão foi bloqueada.");
  }
  popup.addEventListener("afterprint", () => {
    popup.close();
    URL.revokeObjectURL(url);
  }, { once: true });
}
