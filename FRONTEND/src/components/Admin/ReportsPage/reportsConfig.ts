/**
 * Arquivo: src/components/Admin/ReportsPage/reportsConfig.ts
 * Objetivo: define tipos e catálogo estático de relatórios disponíveis no módulo de relatórios.
 * Entradas esperadas: não recebe props; exporta estruturas de configuração consumidas pela UI.
 */

import {
  Ban,
  BarChart3,
  Boxes,
  CalendarClock,
  Clock,
  Coins,
  CreditCard,
  FileChartColumnIncreasing,
  FileSpreadsheet,
  HandCoins,
  Hash,
  History,
  Landmark,
  PackageSearch,
  Percent,
  PiggyBank,
  ShoppingCart,
  TrendingUp,
  Users,
  type LucideIcon,
} from "lucide-react";

export type ReportFilterType = "date" | "time" | "text" | "select" | "multiselect" | "checkbox";

export type ReportFilterOption = {
  label: string;
  value: string;
};

export type ReportFilter = {
  id: string;
  label: string;
  type: ReportFilterType;
  options?: ReportFilterOption[];
};

export type ReportChartSeries = {
  key: string;
  label: string;
};

export type ReportChart = {
  type: "bar";
  xKey: string;
  xLabel?: string;
  series: ReportChartSeries[];
  valueFormat?: "currency" | "number";
};

export type ReportDefinition = {
  id: string;
  title: string;
  description: string;
  icon: LucideIcon;
  filters: ReportFilter[];
  chart?: ReportChart;
};

const periodFilters: ReportFilter[] = [
  { id: "startDate", label: "Data inicial", type: "date" },
  { id: "endDate", label: "Data final", type: "date" },
];

const groupByOptions: ReportFilterOption[] = [
  { label: "Diário", value: "daily" },
  { label: "Semanal", value: "weekly" },
  { label: "Mensal", value: "monthly" },
];

const paymentMethodOptions: ReportFilterOption[] = [
  { label: "Todos", value: "all" },
  { label: "Dinheiro", value: "cash" },
  { label: "PIX", value: "pix" },
  { label: "Cartão de Débito", value: "debit" },
  { label: "Cartão de Crédito", value: "credit" },
  { label: "Fiado", value: "fiado" },
];

const auditEventTypeOptions: ReportFilterOption[] = [
  { label: "Todos os eventos", value: "" },
  { label: "Abertura de caixa (CaixaAbertura)", value: "CaixaAbertura" },
  { label: "Fechamento de caixa (CaixaFechamento)", value: "CaixaFechamento" },
  { label: "Reforço de caixa (CaixaReforco)", value: "CaixaReforco" },
  { label: "Sangria de caixa (CaixaSangria)", value: "CaixaSangria" },
  { label: "Venda bloqueada (VendaBloqueada)", value: "VendaBloqueada" },
  { label: "Débito fiado (FiadoDebito)", value: "FiadoDebito" },
  { label: "Recebimento fiado (FiadoRecebimento)", value: "FiadoRecebimento" },
  { label: "Alteração de limite fiado (FiadoLimiteAlterado)", value: "FiadoLimiteAlterado" },
  { label: "Cadastro de cliente (ClienteCriado)", value: "ClienteCriado" },
  { label: "Atualização de cliente (ClienteAtualizado)", value: "ClienteAtualizado" },
  { label: "Exclusão de cliente (ClienteExcluido)", value: "ClienteExcluido" },
  { label: "Alteração de preço autorizada (AlteracaoPreco)", value: "AlteracaoPreco" },
  { label: "Venda offline abaixo do preço sem autorização (PrecoOfflineAbaixo)", value: "PrecoOfflineAbaixo" },
  { label: "Cancelamento de venda (VendaCancelada)", value: "VendaCancelada" },
  { label: "Cancelamento de venda negado (CancelamentoVendaNegado)", value: "CancelamentoVendaNegado" },
  { label: "NFC-e não emitida - venda cancelada (NfceNaoEmitida)", value: "NfceNaoEmitida" },
  { label: "NFC-e de venda cancelada a cancelar manualmente (NfceCancelamentoPendente)", value: "NfceCancelamentoPendente" },
  { label: "Cancelamento de NFC-e (NfceCancelamento)", value: "NfceCancelamento" },
  { label: "Devolução de NF-e entrada (NfeDevolucaoEntrada)", value: "NfeDevolucaoEntrada" },
  { label: "Correção de venda (VendaCorrigida)", value: "VendaCorrigida" },
  { label: "Ruptura de estoque (EstoqueRuptura)", value: "EstoqueRuptura" },
  { label: "Inventário aberto (InventarioAberto)", value: "InventarioAberto" },
  { label: "Inventário aplicado (InventarioAplicado)", value: "InventarioAplicado" },
  { label: "Aplicação de inventário negada (InventarioAplicacaoNegada)", value: "InventarioAplicacaoNegada" },
  { label: "Inventário cancelado (InventarioCancelado)", value: "InventarioCancelado" },
  { label: "Classificação fiscal IA (ProdutoFiscalIa)", value: "ProdutoFiscalIa" },
];

const departmentOptions: ReportFilterOption[] = [
  { label: "Todos os departamentos", value: "all" },
  { label: "Mercearia", value: "cat-mercearia" },
  { label: "Bebidas", value: "cat-bebidas" },
  { label: "Laticínios", value: "cat-laticinios" },
  { label: "Padaria", value: "cat-padaria" },
  { label: "Açougue", value: "cat-acougue" },
  { label: "Hortifruti", value: "cat-hortifruti" },
  { label: "Frios", value: "cat-frios" },
  { label: "Limpeza", value: "cat-limpeza" },
  { label: "Higiene", value: "cat-higiene" },
  { label: "Congelados", value: "cat-congelados" },
  { label: "Bomboniere", value: "cat-bomboniere" },
  { label: "Bazar", value: "cat-bazar" },
  { label: "Pet", value: "cat-pet" },
  { label: "Tabacaria", value: "cat-tabacaria" },
];

const categoriaFilter: ReportFilter = {
  id: "categoriaId",
  label: "Departamento",
  type: "select",
  options: departmentOptions,
};

const faixaValidadeOptions: ReportFilterOption[] = [
  { label: "Todos os controlados", value: "todos" },
  { label: "Somente vencidos", value: "vencidos" },
  { label: "Vencem em até 7 dias", value: "7d" },
  { label: "Vencem em até 15 dias", value: "15d" },
  { label: "Vencem em até 30 dias", value: "30d" },
];

const faixaInadimplenciaOptions: ReportFilterOption[] = [
  { label: "Todos os devedores", value: "todos" },
  { label: "Em dia (menos de 30 dias)", value: "em_dia" },
  { label: "Atraso moderado (30 a 60 dias)", value: "atraso_30" },
  { label: "Inadimplência crítica (mais de 60 dias)", value: "atraso_60" },
];

export const reportCatalog: ReportDefinition[] = [
  {
    id: "lucro-markup-cadastro",
    title: "Lucro e markup sem período",
    description: "Consulte o lucro e o markup de cada produto pelos preços atuais de compra e venda do cadastro.",
    icon: Percent,
    filters: [],
  },
  {
    id: "lucro-markup-produtos",
    title: "Lucro e markup por produto",
    description: "Compare compra, venda e lucro dos produtos vendidos, com os totais e o markup do período.",
    icon: PiggyBank,
    filters: [
      ...periodFilters,
      { id: "productSearch", label: "Código ou descrição do produto", type: "text" },
      categoriaFilter,
    ],
  },
  {
    id: "vendas-periodo",
    title: "Vendas por Período",
    description: "Consolida faturamento, ticket médio e quantidade de vendas no período.",
    icon: TrendingUp,
    filters: [
      ...periodFilters,
      categoriaFilter,
      { id: "groupBy", label: "Agrupar por", type: "select", options: groupByOptions },
      { id: "paymentMethod", label: "Forma de pagamento", type: "select", options: paymentMethodOptions },
    ],
  },
  {
    id: "historico-vendas",
    title: "Histórico de Vendas",
    description: "Relatório detalhado de operações por operador, cliente e forma de pagamento.",
    icon: ShoppingCart,
    filters: [
      ...periodFilters,
      categoriaFilter,
      { id: "startTime", label: "Hora inicial", type: "time" },
      { id: "endTime", label: "Hora final", type: "time" },
      { id: "paymentMethod", label: "Forma de pagamento", type: "select", options: paymentMethodOptions },
    ],
  },
  {
    id: "produtos-mais-vendidos",
    title: "Produtos Mais Vendidos",
    description: "Classifica os itens com maior saída por quantidade e faturamento.",
    icon: FileChartColumnIncreasing,
    filters: [
      ...periodFilters,
      categoriaFilter,
      { id: "startTime", label: "Hora inicial", type: "time" },
      { id: "endTime", label: "Hora final", type: "time" },
      { id: "paymentMethod", label: "Forma de pagamento", type: "select", options: paymentMethodOptions },
    ],
  },
  {
    id: "produtos-por-valor",
    title: "Produtos por Valor",
    description: "Ranking dos produtos com maior faturamento (valor vendido) no período.",
    icon: Coins,
    filters: [
      ...periodFilters,
      categoriaFilter,
      { id: "startTime", label: "Hora inicial", type: "time" },
      { id: "endTime", label: "Hora final", type: "time" },
      { id: "paymentMethod", label: "Forma de pagamento", type: "select", options: paymentMethodOptions },
    ],
  },
  {
    id: "produtos-por-quantidade",
    title: "Produtos por Quantidade",
    description: "Ranking dos produtos com maior quantidade vendida no período.",
    icon: Hash,
    filters: [
      ...periodFilters,
      categoriaFilter,
      { id: "startTime", label: "Hora inicial", type: "time" },
      { id: "endTime", label: "Hora final", type: "time" },
      { id: "paymentMethod", label: "Forma de pagamento", type: "select", options: paymentMethodOptions },
    ],
  },
  {
    id: "produtos-por-lucro",
    title: "Produtos por Lucro",
    description: "Ranking dos produtos que mais geram lucro em reais (faturamento menos custo).",
    icon: PiggyBank,
    filters: [
      ...periodFilters,
      categoriaFilter,
      { id: "startTime", label: "Hora inicial", type: "time" },
      { id: "endTime", label: "Hora final", type: "time" },
      { id: "paymentMethod", label: "Forma de pagamento", type: "select", options: paymentMethodOptions },
    ],
  },
  {
    id: "produtos-por-margem",
    title: "Produtos por % de Lucro",
    description: "Ranking dos produtos com maior margem percentual de lucro no período.",
    icon: Percent,
    filters: [
      ...periodFilters,
      categoriaFilter,
      { id: "startTime", label: "Hora inicial", type: "time" },
      { id: "endTime", label: "Hora final", type: "time" },
      { id: "paymentMethod", label: "Forma de pagamento", type: "select", options: paymentMethodOptions },
    ],
  },
  {
    id: "vendas-por-horario",
    title: "Vendas por Horário",
    description: "Mostra em quais horas do dia o PDV mais vende (faturamento e ticket médio por hora).",
    icon: Clock,
    filters: [
      ...periodFilters,
      categoriaFilter,
      { id: "paymentMethod", label: "Forma de pagamento", type: "select", options: paymentMethodOptions },
    ],
    chart: {
      type: "bar",
      xKey: "horario",
      xLabel: "Horário",
      series: [{ key: "faturamentoValor", label: "Faturamento" }],
      valueFormat: "currency",
    },
  },
  {
    id: "vendas-por-pagamento",
    title: "Vendas por Tipo de Pagamento",
    description: "Consolida faturamento e participação de cada forma de pagamento no período.",
    icon: CreditCard,
    filters: [
      ...periodFilters,
      categoriaFilter,
    ],
  },
  {
    id: "margem-por-categoria",
    title: "Margem por Categoria",
    description: "Analisa rentabilidade, CMV e margem bruta (R$ e %) consolidada por departamento.",
    icon: Percent,
    filters: [
      ...periodFilters,
      categoriaFilter,
      { id: "paymentMethod", label: "Forma de pagamento", type: "select", options: paymentMethodOptions },
    ],
  },
  {
    id: "vencimentos",
    title: "Controle de Vencimentos",
    description: "Monitora produtos perecíveis vencidos e próximos do vencimento para evitar perdas.",
    icon: CalendarClock,
    filters: [
      { id: "faixa", label: "Faixa de vencimento", type: "select", options: faixaValidadeOptions },
      categoriaFilter,
    ],
  },
  {
    id: "reposicao-giro",
    title: "Sugestão de Reposição (Giro)",
    description: "Quanto repor de cada produto pela venda média diária, cobertura desejada e estoque mínimo/máximo, por fornecedor.",
    icon: ShoppingCart,
    filters: [
      { id: "dias", label: "Período de vendas (dias)", type: "text" },
      { id: "cobertura", label: "Cobertura desejada (dias)", type: "text" },
      categoriaFilter,
    ],
  },
  {
    id: "curva-abc",
    title: "Curva ABC de Produtos",
    description: "Classifica os produtos vendidos em A, B e C pelo faturamento acumulado (período máximo de 365 dias).",
    icon: TrendingUp,
    filters: [...periodFilters, categoriaFilter],
  },
  {
    id: "estoque-parado",
    title: "Estoque Parado",
    description: "Produtos com estoque e sem venda há X dias, com o valor parado pelo custo.",
    icon: Boxes,
    filters: [{ id: "dias", label: "Sem venda há (dias)", type: "text" }],
  },
  {
    id: "perdas",
    title: "Perdas de Estoque",
    description: "Baixas por perda no período, por produto e motivo, com quantidade e valor pelo custo.",
    icon: PackageSearch,
    filters: [...periodFilters],
  },
  {
    id: "inadimplencia",
    title: "Inadimplência e Aging List (Fiado)",
    description: "Acompanhamento da carteira de clientes com saldo devedor fiado, faixas de atraso e dias sem pagamento.",
    icon: Landmark,
    filters: [
      { id: "faixa", label: "Faixa de atraso", type: "select", options: faixaInadimplenciaOptions },
    ],
  },
  {
    id: "clientes-frequentes",
    title: "Clientes Mais Frequentes",
    description: "Mostra clientes com maior recorrência de compra e gasto médio.",
    icon: Users,
    filters: [
      ...periodFilters,
      { id: "startTime", label: "Hora inicial", type: "time" },
      { id: "endTime", label: "Hora final", type: "time" },
      { id: "paymentMethod", label: "Forma de pagamento", type: "select", options: paymentMethodOptions },
    ],
  },
  {
    id: "estoque-critico",
    title: "Estoque Crítico",
    description: "Identifica produtos abaixo do estoque mínimo para reposição.",
    icon: PackageSearch,
    filters: [
      categoriaFilter,
      { id: "onlyOutOfStock", label: "Somente sem estoque", type: "checkbox" },
    ],
  },
  {
    id: "compras-fornecedor",
    title: "Compras por Fornecedor",
    description: "Resumo de compras e custo por fornecedor para análise de margem.",
    icon: HandCoins,
    filters: [
      ...periodFilters,
      categoriaFilter,
    ],
  },
  {
    id: "movimento-estoque",
    title: "Movimento de Estoque",
    description: "Entradas e saídas por produto para acompanhar giro e rupturas.",
    icon: Boxes,
    filters: [
      ...periodFilters,
      categoriaFilter,
      { id: "groupBy", label: "Agrupar por", type: "select", options: groupByOptions },
    ],
  },
  {
    id: "desempenho-caixa",
    title: "Desempenho de Caixa",
    description: "Analisa abertura, fechamento e variação por operador de caixa.",
    icon: BarChart3,
    filters: [
      ...periodFilters,
      { id: "paymentMethod", label: "Forma de pagamento", type: "select", options: paymentMethodOptions },
    ],
  },
  {
    id: "log-atividades",
    title: "Log de Atividades",
    description: "Trilha de auditoria: quem fez o quê e quando (caixa, sangria/reforço, vendas bloqueadas, fiado).",
    icon: History,
    filters: [
      ...periodFilters,
      { id: "eventType", label: "Tipo de evento", type: "select", options: auditEventTypeOptions },
    ],
  },
  {
    id: "cancelamentos",
    title: "Relatório de Cancelamentos",
    description: "Histórico detalhado de vendas canceladas com operador, supervisor autorizador, motivo e estorno de estoque.",
    icon: Ban,
    filters: [
      ...periodFilters,
    ],
  },
  {
    id: "auditoria-cancelamentos",
    title: "Trilha de Cancelamentos (Auditoria)",
    description: "Cada tentativa de cancelar uma venda, autorizada ou negada (senha errada, venda com nota...): operador, gerente, motivo, turno, itens, pagamentos, terminal e IP. Disponível apenas para administradores e gerentes.",
    icon: Ban,
    filters: [
      ...periodFilters,
      {
        id: "resultado",
        label: "Resultado",
        type: "select",
        options: [
          { label: "Todos", value: "" },
          { label: "Canceladas", value: "cancelada" },
          { label: "Negadas", value: "negada" },
        ],
      },
    ],
  },
  {
    id: "ficha-fiscal-produtos",
    title: "Ficha Fiscal e Contábil",
    description: "Consolida dados fiscais (NCM, CEST, CFOP, tributação) e contábeis (custos, markups, margens) com a movimentação real de cada produto no período. Disponível apenas para administradores e gerentes.",
    icon: FileSpreadsheet,
    filters: [
      ...periodFilters,
      categoriaFilter,
      { id: "productSearch", label: "Código ou descrição do produto", type: "text" },
    ],
  },
];
