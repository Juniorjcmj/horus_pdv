/**
 * Arquivo: src/components/Admin/BarcodeSvg.tsx
 * Objetivo: Renderiza código de barras em vetor SVG nítido usando JsBarcode.
 *           Possui detecção automática entre EAN-13, EAN-8 e CODE128 com fallback resiliente.
 */

import JsBarcode from "jsbarcode";
import { useEffect, useRef, useState } from "react";

interface BarcodeSvgProps {
  value: string;
  format?: "auto" | "EAN13" | "EAN8" | "CODE128";
  width?: number;
  height?: number;
  displayValue?: boolean;
  fontSize?: number;
  margin?: number;
  className?: string;
}

export default function BarcodeSvg({
  value,
  format = "auto",
  width = 1.4,
  height = 32,
  displayValue = true,
  fontSize = 10,
  margin = 2,
  className = "",
}: BarcodeSvgProps) {
  const svgRef = useRef<SVGSVGElement>(null);
  const [renderError, setRenderError] = useState(false);

  useEffect(() => {
    if (!svgRef.current) return;
    const cleanValue = (value || "").trim();

    if (!cleanValue) {
      setRenderError(true);
      return;
    }

    // Helper para tentar renderizar com formato específico
    const tryRender = (targetFormat: string, targetValue: string): boolean => {
      try {
        JsBarcode(svgRef.current, targetValue, {
          format: targetFormat,
          width,
          height,
          displayValue,
          fontSize,
          font: "monospace",
          textMargin: 1,
          margin,
          background: "transparent",
          lineColor: "#000000",
        });
        return true;
      } catch {
        return false;
      }
    };

    setRenderError(false);

    if (format === "auto") {
      const isDigitsOnly = /^\d+$/.test(cleanValue);

      // Tenta EAN-13 se tiver 12 ou 13 dígitos
      if (isDigitsOnly && (cleanValue.length === 12 || cleanValue.length === 13)) {
        if (tryRender("EAN13", cleanValue)) return;
      }

      // Tenta EAN-8 se tiver 7 ou 8 dígitos
      if (isDigitsOnly && (cleanValue.length === 7 || cleanValue.length === 8)) {
        if (tryRender("EAN8", cleanValue)) return;
      }

      // Fallback universal para CODE128
      if (tryRender("CODE128", cleanValue)) return;

      setRenderError(true);
    } else {
      if (!tryRender(format, cleanValue)) {
        // Fallback para CODE128
        if (!tryRender("CODE128", cleanValue)) {
          setRenderError(true);
        }
      }
    }
  }, [value, format, width, height, displayValue, fontSize, margin]);

  if (renderError) {
    return (
      <div className={`flex flex-col items-center justify-center p-1 text-center font-mono text-[10px] text-text-tertiary ${className}`}>
        <span>[Cód: {value}]</span>
      </div>
    );
  }

  return (
    <svg
      ref={svgRef}
      className={`block max-w-full overflow-visible ${className}`}
      aria-label={`Código de barras ${value}`}
    />
  );
}
