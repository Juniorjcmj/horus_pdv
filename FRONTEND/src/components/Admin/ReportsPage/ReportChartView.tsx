/**
 * Arquivo: src/components/Admin/ReportsPage/ReportChartView.tsx
 * Objetivo: renderiza um gráfico (barras) para relatórios que declaram configuração de chart.
 * Entradas esperadas: recebe a config de gráfico do relatório e as linhas de resultado já geradas.
 */

import { useMemo } from "react";
import {
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import type { ReportChart } from "./reportsConfig";
import type { ReportResultRow } from "./reportResultTypes";

type ReportChartViewProps = {
  chart: ReportChart;
  rows: ReportResultRow[];
};

const currencyFormatter = new Intl.NumberFormat("pt-BR", {
  style: "currency",
  currency: "BRL",
});

const numberFormatter = new Intl.NumberFormat("pt-BR");

const compactCurrencyFormatter = new Intl.NumberFormat("pt-BR", {
  style: "currency",
  currency: "BRL",
  notation: "compact",
  maximumFractionDigits: 1,
});

function toNumber(value: string | number | undefined): number {
  return typeof value === "number" && Number.isFinite(value) ? value : 0;
}

export default function ReportChartView({ chart, rows }: ReportChartViewProps) {
  const serie = chart.series[0];

  const data = useMemo(
    () =>
      rows.map((row) => ({
        label: String(row[chart.xKey] ?? "-"),
        value: toNumber(row[serie.key]),
      })),
    [rows, chart.xKey, serie.key],
  );

  const isCurrency = chart.valueFormat === "currency";
  const formatFull = (value: number) =>
    isCurrency ? currencyFormatter.format(value) : numberFormatter.format(value);
  const formatAxis = (value: number) =>
    isCurrency ? compactCurrencyFormatter.format(value) : numberFormatter.format(value);

  // Destaca a barra de maior valor para responder "qual horário vende mais" num relance.
  const maxValue = data.reduce((max, item) => Math.max(max, item.value), 0);

  if (data.length === 0) return null;

  return (
    <div className="rounded-xl border border-border-primary bg-bg-primary p-4">
      <p className="mb-3 text-sm font-semibold text-text-primary">
        {serie.label} por {chart.xLabel ?? chart.xKey}
      </p>
      <div className="h-72 w-full">
        <ResponsiveContainer width="100%" height="100%">
          <BarChart data={data} margin={{ top: 8, right: 12, left: 4, bottom: 24 }}>
            <CartesianGrid strokeDasharray="3 3" stroke="var(--color-border-primary)" vertical={false} />
            <XAxis
              dataKey="label"
              tick={{ fill: "var(--color-text-secondary)", fontSize: 11 }}
              angle={-35}
              textAnchor="end"
              interval={0}
              height={50}
              stroke="var(--color-border-primary)"
            />
            <YAxis
              tick={{ fill: "var(--color-text-secondary)", fontSize: 11 }}
              tickFormatter={formatAxis}
              width={72}
              stroke="var(--color-border-primary)"
            />
            <Tooltip
              cursor={{ fill: "var(--color-accent)", fillOpacity: 0.08 }}
              formatter={(value) => [formatFull(toNumber(value as number)), serie.label]}
              contentStyle={{
                background: "var(--color-bg-light)",
                border: "1px solid var(--color-border-primary)",
                borderRadius: 12,
                color: "var(--color-text-primary)",
                fontSize: 12,
              }}
              labelStyle={{ color: "var(--color-text-primary)", fontWeight: 600 }}
            />
            <Bar dataKey="value" name={serie.label} radius={[6, 6, 0, 0]} maxBarSize={56}>
              {data.map((item, index) => (
                <Cell
                  key={`bar-${index}`}
                  fill="var(--color-accent)"
                  fillOpacity={item.value === maxValue && maxValue > 0 ? 1 : 0.55}
                />
              ))}
            </Bar>
          </BarChart>
        </ResponsiveContainer>
      </div>
    </div>
  );
}
