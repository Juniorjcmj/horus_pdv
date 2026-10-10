export type BarcodeScanOrigin = {
  input: HTMLInputElement | HTMLTextAreaElement | null;
  value: string;
};

const MAX_KEY_GAP_MS = 80;
const MAX_AVERAGE_KEY_GAP_MS = 35;
const MIN_CODE_LENGTH = 4;
const MAX_CODE_LENGTH = 128;

/** USB readers send a quick keyboard burst. Listen before the focused field and POS shortcuts. */
export function listenForBarcodeScans(
  onScan: (code: string, origin: BarcodeScanOrigin) => void,
): () => void {
  let buffer = "";
  let firstKeyAt = 0;
  let lastKeyAt = 0;
  let timeout: number | undefined;
  let origin: BarcodeScanOrigin = { input: null, value: "" };
  let selectionStart: number | null = null;
  let selectionEnd: number | null = null;

  const reset = () => {
    window.clearTimeout(timeout);
    buffer = "";
    origin = { input: null, value: "" };
  };

  const restoreInput = () => {
    const { input, value } = origin;
    if (!input?.isConnected) return;
    if (input.value !== value) {
      // Bypass React's value tracker so the input event also restores controlled/masked fields.
      const prototype = input instanceof HTMLTextAreaElement
        ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
      Object.getOwnPropertyDescriptor(prototype, "value")?.set?.call(input, value);
      input.dispatchEvent(new Event("input", { bubbles: true }));
    }
    if (selectionStart !== null && selectionEnd !== null) {
      try { input.setSelectionRange(selectionStart, selectionEnd); } catch { /* Number/date inputs have no selection. */ }
    }
  };

  const onKeyDown = (event: KeyboardEvent) => {
    if (event.isComposing || event.repeat || event.ctrlKey || event.altKey || event.metaKey) {
      reset();
      return;
    }
    if (event.key === "Shift" || event.key === "CapsLock") return;
    const now = event.timeStamp;
    if (buffer && now - lastKeyAt > MAX_KEY_GAP_MS) reset();

    if (event.key === "Enter" || event.key === "Tab") {
      const isScan = buffer.length >= MIN_CODE_LENGTH
        && (lastKeyAt - firstKeyAt) / (buffer.length - 1) <= MAX_AVERAGE_KEY_GAP_MS;
      if (isScan) {
        event.preventDefault();
        event.stopImmediatePropagation();
        const code = buffer;
        const scanOrigin = origin;
        restoreInput();
        reset();
        onScan(code, scanOrigin);
      } else {
        reset();
      }
      return;
    }

    if (event.key.length !== 1 || !/^[\x20-\x7E]$/.test(event.key)) {
      reset();
      return;
    }
    if (!buffer) {
      firstKeyAt = now;
      const input = event.target instanceof HTMLInputElement || event.target instanceof HTMLTextAreaElement
        ? event.target : null;
      origin = { input, value: input?.value ?? "" };
      selectionStart = input?.selectionStart ?? null;
      selectionEnd = input?.selectionEnd ?? null;
    }
    buffer += event.key;
    lastKeyAt = now;
    if (buffer.length > MAX_CODE_LENGTH) { reset(); return; }
    window.clearTimeout(timeout);
    timeout = window.setTimeout(reset, MAX_KEY_GAP_MS * 2);
  };

  window.addEventListener("keydown", onKeyDown, true);
  window.addEventListener("pointerdown", reset, true);
  window.addEventListener("blur", reset);
  return () => {
    reset();
    window.removeEventListener("keydown", onKeyDown, true);
    window.removeEventListener("pointerdown", reset, true);
    window.removeEventListener("blur", reset);
  };
}
