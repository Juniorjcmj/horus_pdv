/**
 * Arquivo: src/pages/Public/LandingPage.tsx
 * Objetivo: Landing page institucional de alta conversão para o Quack PDV.
 * Destaques:
 *  - Galeria rica de imagens reais do sistema e ilustrações 3D premium.
 *  - Demonstração interativa de telas (Frente de Caixa e Dashboard).
 *  - Calculadora interativa de economia/ROI para o lojista.
 *  - Seções detalhadas: PDV Ágil, Fiscal NFC-e, Estoque & Validade, Fiado Digital, PIX e Relatórios.
 *  - Prova social, comparativo de mercado, FAQ accordion e múltiplos CTAs de conversão.
 */
import { useState } from "react";
import {
  ArrowRight,
  Check,
  CheckCircle2,
  ChevronDown,
  FileCheck2,
  Layers,
  Monitor,
  Package,
  QrCode,
  Receipt,
  Scale,
  ShieldCheck,
  Sparkles,
  Star,
  Store,
  TrendingUp,
  Users,
  Zap,
} from "lucide-react";

type LandingPageProps = {
  onOpenLogin: () => void;
  onOpenRegister: () => void;
};

type FeatureTabKey =
  | "pdv"
  | "dashboard"
  | "estoque"
  | "fiscal"
  | "fiado"
  | "pagamentos"
  | "relatorios";

export default function LandingPage({
  onOpenLogin,
  onOpenRegister,
}: LandingPageProps) {
  // Estado da aba interativa de funcionalidades
  const [activeTab, setActiveTab] = useState<FeatureTabKey>("pdv");

  // Estado da calculadora de economia
  const [dailySales, setDailySales] = useState<number>(180);
  const [averageTicket, setAverageTicket] = useState<number>(38);

  // Estado do FAQ interativo
  const [openFaqIndex, setOpenFaqIndex] = useState<number | null>(0);

  // Segmento ativo
  const [activeSegment, setActiveSegment] = useState<number>(0);

  // Cálculos da calculadora de ROI
  const monthlyRevenue = dailySales * averageTicket * 30;
  const hoursSavedPerMonth = Math.round((dailySales * 1.5 * 30) / 60); // 1.5 min por venda economizado
  const estimatedSavingsPerMonth = Math.round(monthlyRevenue * 0.045); // 4.5% recuperados em perdas e furos

  const segments = [
    {
      title: "Mercados & Mercearias",
      icon: Store,
      desc: "Pesagem de hortifrúti na balança, controle de validade de laticínios e venda ultrarrápida com leitor de código de barras.",
      badge: "Segmento Líder",
    },
    {
      title: "Lojas de Conveniência",
      icon: Zap,
      desc: "Operação rápida 24/7, recebimento com PIX instantâneo no balcão e conferência de caixa rigorosa por turno de operador.",
      badge: "Alta Rotatividade",
    },
    {
      title: "Hortifrutis & Açougues",
      icon: Scale,
      desc: "Pesagem fracionada direta em kg, integração com balanças checkout Toledo e Filizola e precificação dinâmica.",
      badge: "Pesagem Precisa",
    },
    {
      title: "Materiais de Construção",
      icon: Layers,
      desc: "Venda por metro quadrado (m²), fração ou fardo, controle de estoque em depósito e gestão de fiado para pedreiros e construtores.",
      badge: "Multi-Unidade",
    },
    {
      title: "Padarias & Lanchonetes",
      icon: Package,
      desc: "Comanda ágil, múltiplos operadores de caixa sem travamento e emissão de cupom fiscal NFC-e em menos de 1 segundo.",
      badge: "Fluxo Contínuo",
    },
  ];

  const featuresTabs: Record<
    FeatureTabKey,
    {
      title: string;
      subtitle: string;
      tag: string;
      image: string;
      isRealScreenshot: boolean;
      highlights: string[];
      bullets: { title: string; desc: string }[];
    }
  > = {
    pdv: {
      title: "Frente de Caixa (PDV) Ultrarrápido e Sem Mouse",
      subtitle:
        "Projetado para operadores experientes ou novatos: 100% operável via atalhos de teclado (F8, F12, Enter), eliminando filas no checkout.",
      tag: "Tela Real do Sistema",
      image: "/screenshots/quack-frente-caixa-pdv.png",
      isRealScreenshot: true,
      highlights: [
        "Atalho F12 para Pagamento Imediato",
        "Atalho F8 para Cancelamento Rápido",
        "Integração com Balanças e Leitores",
        "Modo Tela Cheia Imersivo (F11)",
      ],
      bullets: [
        {
          title: "Venda por Peso e Unidade Fracionada",
          desc: "Digite gramas, quilos ou metros sem complicações. O subtotal é recalculado em tempo real.",
        },
        {
          title: "Busca Inteligente por Código ou Nome",
          desc: "Passe o código de barras ou digite parte do nome do produto para inclusão instantânea no cupom.",
        },
        {
          title: "Prevenção de Erros de Operador",
          desc: "Alerta visual de caixa aberto, cálculo de troco automático e cupom auditável a cada transação.",
        },
      ],
    },
    dashboard: {
      title: "Dashboard Executivo e Métricas em Tempo Real",
      subtitle:
        "Tenha na ponta dos dedos o faturamento do dia, total de vendas, clientes atendidos e estoque em estado crítico com gráficos dinâmicos.",
      tag: "Tela Real do Sistema",
      image: "/screenshots/quack-dashboard-home.png",
      isRealScreenshot: true,
      highlights: [
        "Faturamento em Tempo Real",
        "Contador de Vendas do Dia",
        "Alerta de Estoque Crítico (< 5 un)",
        "Acesso Rápido a Vendas e Clientes",
      ],
      bullets: [
        {
          title: "Visão Geral Panorâmica",
          desc: "Monitore os resultados de todas as frentes de caixa em um único dashboard intuitivo e limpo.",
        },
        {
          title: "Ações Rápidas em 1 Clique",
          desc: "Abra novos cadastros, consulte histórico de vendas ou acesse relatórios sem navegar por menus complexos.",
        },
        {
          title: "Alerta de Estoque Crítico",
          desc: "Detecte antecipadamente itens que estão acabando na gôndola antes que o cliente sinta a falta.",
        },
      ],
    },
    estoque: {
      title: "Estoque Inteligente e Controle de Validade",
      subtitle:
        "Evite prejuízos por perda de validade e desabastecimento com controle avançado de fardos, caixas e unidades.",
      tag: "Gestão Avançada",
      image: "/screenshots/smart-inventory.jpg",
      isRealScreenshot: false,
      highlights: [
        "Alerta de Produtos a Vencer",
        "Fator de Conversão Compra x Venda",
        "Estoque Mínimo e Máximo",
        "Localização por Corredor e Prateleira",
      ],
      bullets: [
        {
          title: "Controle Rigoroso de Validade",
          desc: "Defina dias de alerta antes do vencimento para realizar promoções antecipadas e queima planejada.",
        },
        {
          title: "Unidades de Medida Flexíveis",
          desc: "Compre em fardo/caixa e venda em unidades avulsas com cálculo automático de custo e quantidade.",
        },
        {
          title: "Inventário Rápido com Coletor / Leitor",
          desc: "Bipe os produtos e atualize o saldo do estoque em minutos, mantendo a contagem sempre exata.",
        },
      ],
    },
    fiscal: {
      title: "Emissão Fiscal NFC-e & NF-e Automática e Segura",
      subtitle:
        "100% de conformidade com a SEFAZ em todos os estados brasileiros, com contingência offline para nunca parar de vender.",
      tag: "Homologação SEFAZ",
      image: "/screenshots/fiscal-nfce.jpg",
      isRealScreenshot: false,
      highlights: [
        "Emissão em menos de 1 segundo",
        "Contingência Offline Transparente",
        "Importação de XML de Fornecedor",
        "Cálculo Automático de ICMS, PIS, COFINS",
      ],
      bullets: [
        {
          title: "Venda Sem Parar se a Internet Cair",
          desc: "O sistema salva as notas em contingência e transmite automaticamente assim que a conexão retornar.",
        },
        {
          title: "Importação de XML de Fornecedor",
          desc: "Dê entrada nas compras e cadastre produtos com tributação pré-preenchida em poucos segundos.",
        },
        {
          title: "Impressão Térmica com QR Code",
          desc: "Compatível com qualquer impressora térmica (58mm e 80mm — Elgin, Bematech, Epson, Daruma).",
        },
      ],
    },
    fiado: {
      title: "Gestão de Fiado e Conta Corrente de Clientes",
      subtitle:
        "Diga adeus ao caderninho de papel! Controle limites de crédito, evite calotes e fidelize os clientes da sua vizinhança.",
      tag: "Fintech Integrada",
      image: "/screenshots/fiado-credito.jpg",
      isRealScreenshot: false,
      highlights: [
        "Limite de Crédito Personalizado",
        "Trava Automática de Inadimplentes",
        "Extrato Detalhado de Compras e Pagamentos",
        "Quitação Parcial ou Total no Caixa",
      ],
      bullets: [
        {
          title: "Fim dos Cadernos e Fórmulas Perdidas",
          desc: "Toda venda no fiado é debitada na conta corrente digital do cliente com data, hora e operador.",
        },
        {
          title: "Trava de Segurança por Limite",
          desc: "O sistema bloqueia vendas a prazo se o cliente ultrapassar o limite concedido pelo gestor.",
        },
        {
          title: "Extrato com Comprovante Impresso",
          desc: "Emita o extrato da dívida direto na impressora para assinatura ou conferência do cliente.",
        },
      ],
    },
    pagamentos: {
      title: "Múltiplos Pagamentos, PIX com QR Code e Cartões",
      subtitle:
        "Ofereça PIX instantâneo com QR Code dinâmico na tela do caixa, dinheiro com troco automático e divisões de pagamento.",
      tag: "Fluxo Financeiro",
      image: "/screenshots/pagamentos-pix-card.jpg",
      isRealScreenshot: false,
      highlights: [
        "PIX com QR Code Dinâmico",
        "Pagamento Misto (PIX + Dinheiro + Cartão)",
        "Cálculo de Troco Automático",
        "Conciliação Financeira sem Furos",
      ],
      bullets: [
        {
          title: "Pagamentos Combinados em Segundos",
          desc: "O cliente pode pagar parte em dinheiro e o restante no PIX ou cartão na mesma transação.",
        },
        {
          title: "Confirmação Instantânea de Recebimento",
          desc: "Agilidade máxima para liberar a fila do balcão sem esperar comprovantes demorados.",
        },
        {
          title: "Fechamento de Caixa Cego",
          desc: "O operador declara os valores físicos e o sistema confronta as diferenças de forma transparente.",
        },
      ],
    },
    relatorios: {
      title: "Relatórios Estratégicos, Curva ABC e Lucratividade",
      subtitle:
        "Descubra quais produtos dão mais lucro, controle suas margens de markup e acompanhe o crescimento real da empresa.",
      tag: "Inteligência de Varejo",
      image: "/screenshots/relatorios-analytics.jpg",
      isRealScreenshot: false,
      highlights: [
        "Curva ABC de Mais Vendidos",
        "Markup Praticado vs. Cadastrado",
        "Desempenho por Operador e Turno",
        "Exportação para Excel e PDF",
      ],
      bullets: [
        {
          title: "Margem de Lucro Real por Item",
          desc: "Saiba exatamente quanto sobra no seu bolso descontando custos, impostos e comissões.",
        },
        {
          title: "Histórico Completo de Auditoria",
          desc: "Rastreie cada cancelamento, desconto concedido e sangria/suprimento de caixa.",
        },
        {
          title: "Decisões Baseadas em Dados",
          desc: "Compre melhor e negocie com fornecedores munido dos relatórios de rotatividade de estoque.",
        },
      ],
    },
  };

  const currentTab = featuresTabs[activeTab];

  const faqs = [
    {
      question: "O Quack PDV funciona mesmo se a internet cair?",
      answer:
        "Sim! O Quack PDV conta com contingência offline inteligente. Quando a conexão oscila ou cai, o caixa continua registrando as vendas e gerando o cupom normalmente. Assim que a internet se restabelece, as notas fiscais são transmitidas automaticamente para a SEFAZ em segundo plano.",
    },
    {
      question: "Consigo usar minhas balanças e leitor de código de barras?",
      answer:
        "Com certeza! O sistema suporta os principais leitores de código de barras USB/Bluetooth do mercado e se integra perfeitamente com balanças de checkout (como Toledo, Filizola, Elgin e Urano), inclusive lendo etiquetas com peso e preço embutido.",
    },
    {
      question: "Como funciona a emissão de nota fiscal NFC-e e NF-e?",
      answer:
        "A emissão é 100% nativa e automática. Basta carregar seu Certificado Digital A1 no sistema. O Quack PDV calcula os impostos conforme o regime tributário da sua empresa (Simples Nacional, MEI ou Lucro Presumido) e emite a nota em menos de 1 segundo.",
    },
    {
      question: "O módulo de fiado / conta corrente é seguro contra perdas?",
      answer:
        "Totalmente. Você define o limite máximo de crédito que cada cliente pode acumular. Se o cliente atingir o limite, o caixa é alertado e a venda no fiado é bloqueada até que haja uma quitação parcial ou total. Tudo com histórico auditável e comprovante impresso.",
    },
    {
      question: "Preciso de um computador caro para rodar o Quack PDV?",
      answer:
        "Não! O sistema foi desenvolvido com arquitetura ultraleve e moderna. Funciona perfeitamente em computadores comuns, notebooks ou pontos de venda dedicados com Windows ou Linux, rodando com rapidez e fluidez.",
    },
    {
      question: "Como começo a usar e fazer o teste gratuito?",
      answer:
        "Basta clicar no botão 'Começar Grátis' ou 'Criar Conta'. Em menos de 2 minutos sua empresa é cadastrada e você já pode cadastrar produtos, abrir o caixa e realizar vendas imediatas!",
    },
  ];

  return (
    <div className="min-h-screen bg-slate-950 text-slate-100 font-sans selection:bg-sky-500 selection:text-white antialiased">
      {/* ========================================================================= */}
      {/* 1. TOP ANNOUNCEMENT BAR & NAVBAR                                          */}
      {/* ========================================================================= */}
      <div className="bg-gradient-to-r from-sky-600 via-indigo-600 to-sky-600 text-white text-xs py-2 px-4 text-center font-medium tracking-wide flex items-center justify-center gap-2">
        <Sparkles size={14} className="animate-pulse" />
        <span>
          <strong>Novo Modelo Multi-Segmento:</strong> Suporte completo a
          fardos, balanças, validades e NFC-e rápida!
        </span>
        <button
          type="button"
          onClick={onOpenRegister}
          className="underline hover:text-sky-200 transition font-semibold ml-2 cursor-pointer"
        >
          Experimente Grátis →
        </button>
      </div>

      <header className="sticky top-0 z-50 backdrop-blur-xl bg-slate-950/85 border-b border-slate-800/80 transition-all">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 h-20 flex items-center justify-between">
          {/* Logo */}
          <div className="flex items-center gap-3">
            <div className="h-11 w-11 rounded-xl bg-gradient-to-tr from-sky-500 to-indigo-600 flex items-center justify-center shadow-lg shadow-sky-500/25 border border-sky-400/30">
              <ShieldCheck className="text-white h-6 w-6" />
            </div>
            <div>
              <span className="text-2xl font-black tracking-tight text-white font-display flex items-center gap-1.5">
                Quack <span className="text-sky-400">PDV</span>
              </span>
              <span className="block text-[10px] uppercase font-semibold tracking-widest text-slate-400">
                Ponto de Venda & Gestão
              </span>
            </div>
          </div>

          {/* Links Navegação */}
          <nav className="hidden lg:flex items-center gap-7 text-sm font-medium text-slate-300">
            <a
              href="#demonstracao"
              className="hover:text-sky-400 transition cursor-pointer"
            >
              Telas do Sistema
            </a>
            <a
              href="#funcionalidades"
              className="hover:text-sky-400 transition cursor-pointer"
            >
              Funcionalidades
            </a>
            <a
              href="#segmentos"
              className="hover:text-sky-400 transition cursor-pointer"
            >
              Segmentos
            </a>
            <a
              href="#calculadora"
              className="hover:text-sky-400 transition cursor-pointer"
            >
              Calculadora ROI
            </a>
            <a
              href="#depoimentos"
              className="hover:text-sky-400 transition cursor-pointer"
            >
              Depoimentos
            </a>
            <a
              href="#faq"
              className="hover:text-sky-400 transition cursor-pointer"
            >
              Dúvidas
            </a>
          </nav>

          {/* Ações de Login e Cadastro */}
          <div className="flex items-center gap-3">
            <button
              type="button"
              onClick={onOpenLogin}
              className="px-4 py-2.5 text-sm font-semibold text-slate-200 hover:text-white hover:bg-slate-800/70 rounded-xl transition cursor-pointer border border-transparent hover:border-slate-700"
            >
              Entrar
            </button>
            <button
              type="button"
              onClick={onOpenRegister}
              className="px-5 py-2.5 text-sm font-bold text-white bg-gradient-to-r from-sky-500 to-indigo-600 hover:from-sky-400 hover:to-indigo-500 rounded-xl shadow-lg shadow-sky-500/20 hover:shadow-sky-500/35 transition cursor-pointer flex items-center gap-2 border border-sky-300/30 active:scale-95"
            >
              <span>Testar Grátis</span>
              <ArrowRight size={16} />
            </button>
          </div>
        </div>
      </header>

      {/* ========================================================================= */}
      {/* 2. HERO SECTION DE ALTA CONVERSÃO                                         */}
      {/* ========================================================================= */}
      <section className="relative overflow-hidden pt-12 pb-20 lg:pt-16 lg:pb-28">
        {/* Efeitos de Iluminação de Fundo */}
        <div className="absolute top-1/4 left-1/2 -translate-x-1/2 -translate-y-1/2 w-[700px] h-[450px] bg-sky-500/15 rounded-full blur-[140px] pointer-events-none" />
        <div className="absolute top-1/3 right-10 w-[500px] h-[350px] bg-indigo-500/15 rounded-full blur-[120px] pointer-events-none" />

        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 relative z-10">
          <div className="text-center max-w-4xl mx-auto">
            {/* Tag Badge */}
            <div className="inline-flex items-center gap-2 px-4 py-1.5 rounded-full bg-slate-900/90 border border-sky-500/30 text-sky-400 text-xs sm:text-sm font-semibold mb-6 shadow-inner">
              <span className="flex h-2 w-2 rounded-full bg-emerald-400 animate-ping" />
              <span>O Sistema Comercial Mais Ágil e Completo do Varejo</span>
            </div>

            {/* Headline Principal */}
            <h1 className="text-4xl sm:text-5xl lg:text-6xl font-extrabold tracking-tight text-white leading-tight font-display">
              Venda mais rápido no caixa.{" "}
              <span className="bg-gradient-to-r from-sky-400 via-indigo-300 to-emerald-400 bg-clip-text text-transparent">
                Tenha o controle total
              </span>{" "}
              da sua loja.
            </h1>

            {/* Subheadline */}
            <p className="mt-6 text-lg sm:text-xl text-slate-300 max-w-3xl mx-auto leading-relaxed font-normal">
              Frente de caixa relâmpago sem travamento, emissão fiscal{" "}
              <strong>NFC-e/NF-e em 1 clique</strong>, gestão de validade de
              mercadorias, fiado com limite e controle de estoque com suporte a
              balança.
            </p>

            {/* Botões de Ação Hero */}
            <div className="mt-8 flex flex-col sm:flex-row items-center justify-center gap-4">
              <button
                type="button"
                onClick={onOpenRegister}
                className="w-full sm:w-auto px-8 py-4 text-base font-bold text-white bg-gradient-to-r from-sky-500 to-indigo-600 hover:from-sky-400 hover:to-indigo-500 rounded-2xl shadow-xl shadow-sky-500/30 hover:shadow-sky-500/50 transition cursor-pointer flex items-center justify-center gap-3 border border-sky-300/40 active:scale-95"
              >
                <span>Criar Conta Gratuita</span>
                <ArrowRight size={19} />
              </button>
              <a
                href="#demonstracao"
                className="w-full sm:w-auto px-7 py-4 text-base font-semibold text-slate-200 hover:text-white bg-slate-900/80 hover:bg-slate-800 rounded-2xl border border-slate-700/80 transition cursor-pointer flex items-center justify-center gap-2.5"
              >
                <Monitor size={18} className="text-sky-400" />
                <span>Explorar Telas do Sistema</span>
              </a>
            </div>

            {/* Micro Prova Social no Hero */}
            <div className="mt-7 flex flex-wrap items-center justify-center gap-x-6 gap-y-2 text-xs sm:text-sm text-slate-400 font-medium">
              <span className="flex items-center gap-1.5">
                <CheckCircle2 size={16} className="text-emerald-400" /> Sem
                necessidade de cartão
              </span>
              <span className="flex items-center gap-1.5">
                <CheckCircle2 size={16} className="text-emerald-400" />{" "}
                Homologado SEFAZ em todo o Brasil
              </span>
              <span className="flex items-center gap-1.5">
                <CheckCircle2 size={16} className="text-emerald-400" /> Pronto
                para uso em 3 minutos
              </span>
            </div>
          </div>

          {/* Imagem Principal de Destaque no Hero */}
          <div className="mt-14 relative mx-auto max-w-5xl">
            <div className="relative rounded-3xl overflow-hidden border border-slate-700/70 bg-slate-900/80 shadow-2xl shadow-sky-950/60 group">
              <img
                src="/screenshots/hero-pos-showcase.jpg"
                alt="Quack PDV - Ponto de Venda e Gestão Completa"
                className="w-full h-auto object-cover transform transition duration-700 group-hover:scale-[1.01]"
              />
              <div className="absolute inset-0 bg-gradient-to-t from-slate-950/90 via-transparent to-transparent pointer-events-none" />

              {/* Badges Flutuantes sobre a Imagem */}
              <div className="absolute bottom-6 left-6 right-6 flex flex-wrap items-center justify-between gap-4 p-4 rounded-2xl bg-slate-900/85 backdrop-blur-md border border-slate-700/80 text-white">
                <div className="flex items-center gap-3">
                  <div className="h-10 w-10 rounded-xl bg-emerald-500/20 text-emerald-400 flex items-center justify-center border border-emerald-500/30">
                    <Zap size={20} />
                  </div>
                  <div>
                    <p className="text-xs text-slate-400 uppercase font-bold tracking-wider">
                      Frente de Caixa Ágil
                    </p>
                    <p className="text-sm font-semibold text-white">
                      Tempo médio por cupom:{" "}
                      <span className="text-emerald-400 font-bold">&lt; 1.2 segundos</span>
                    </p>
                  </div>
                </div>

                <div className="flex items-center gap-3">
                  <div className="h-10 w-10 rounded-xl bg-sky-500/20 text-sky-400 flex items-center justify-center border border-sky-500/30">
                    <ShieldCheck size={20} />
                  </div>
                  <div>
                    <p className="text-xs text-slate-400 uppercase font-bold tracking-wider">
                      Segurança Fiscal
                    </p>
                    <p className="text-sm font-semibold text-white">
                      NFC-e / NF-e com contingência offline
                    </p>
                  </div>
                </div>

                <button
                  type="button"
                  onClick={onOpenRegister}
                  className="px-4 py-2 text-xs font-bold text-white bg-sky-500 hover:bg-sky-400 rounded-xl transition cursor-pointer"
                >
                  Experimentar Agora
                </button>
              </div>
            </div>
          </div>
        </div>
      </section>

      {/* ========================================================================= */}
      {/* 3. BARRA DE MÉTRICAS E RESULTADOS COMPROVADOS                              */}
      {/* ========================================================================= */}
      <section className="border-y border-slate-800 bg-slate-900/50 py-10 relative">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
          <div className="grid grid-cols-2 md:grid-cols-4 gap-6 text-center">
            <div className="p-4 rounded-2xl bg-slate-900/40 border border-slate-800/60">
              <p className="text-3xl sm:text-4xl font-black text-sky-400 font-display">
                &lt; 1.2s
              </p>
              <p className="text-xs sm:text-sm text-slate-300 font-medium mt-1">
                Para passar o item e emitir cupom
              </p>
            </div>
            <div className="p-4 rounded-2xl bg-slate-900/40 border border-slate-800/60">
              <p className="text-3xl sm:text-4xl font-black text-emerald-400 font-display">
                100%
              </p>
              <p className="text-xs sm:text-sm text-slate-300 font-medium mt-1">
                SEFAZ com contingência offline
              </p>
            </div>
            <div className="p-4 rounded-2xl bg-slate-900/40 border border-slate-800/60">
              <p className="text-3xl sm:text-4xl font-black text-indigo-400 font-display">
                0%
              </p>
              <p className="text-xs sm:text-sm text-slate-300 font-medium mt-1">
                Perdas de mercadorias por validade vencida
              </p>
            </div>
            <div className="p-4 rounded-2xl bg-slate-900/40 border border-slate-800/60">
              <p className="text-3xl sm:text-4xl font-black text-amber-400 font-display">
                +40%
              </p>
              <p className="text-xs sm:text-sm text-slate-300 font-medium mt-1">
                Recuperação de fiado com conta digital
              </p>
            </div>
          </div>
        </div>
      </section>

      {/* ========================================================================= */}
      {/* 4. DEMONSTRAÇÃO INTERATIVA DAS TELAS REAIS (IMAGENS PRINCIPAIS)           */}
      {/* ========================================================================= */}
      <section id="demonstracao" className="py-20 lg:py-28 relative">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
          <div className="text-center max-w-3xl mx-auto mb-12">
            <span className="text-xs uppercase font-bold tracking-widest text-sky-400 bg-sky-950/70 border border-sky-500/30 px-3 py-1 rounded-full">
              Imagens Reais do Sistema
            </span>
            <h2 className="text-3xl sm:text-4xl font-extrabold text-white mt-4 font-display">
              Veja o Quack PDV funcionando na prática.
            </h2>
            <p className="text-base sm:text-lg text-slate-300 mt-3">
              Explore abaixo as telas reais do sistema. Uma interface moderna,
              limpa e projetada para produtividade máxima.
            </p>
          </div>

          {/* Abas de Navegação das Telas */}
          <div className="flex items-center justify-start sm:justify-center gap-2 overflow-x-auto pb-4 mb-8 no-scrollbar">
            <button
              type="button"
              onClick={() => setActiveTab("pdv")}
              className={`px-4 py-2.5 rounded-xl text-sm font-bold whitespace-nowrap transition cursor-pointer flex items-center gap-2 ${
                activeTab === "pdv"
                  ? "bg-sky-500 text-white shadow-lg shadow-sky-500/25 border border-sky-400"
                  : "bg-slate-900 text-slate-300 hover:bg-slate-800 border border-slate-800"
              }`}
            >
              <Receipt size={16} />
              <span>Frente de Caixa (PDV)</span>
            </button>

            <button
              type="button"
              onClick={() => setActiveTab("dashboard")}
              className={`px-4 py-2.5 rounded-xl text-sm font-bold whitespace-nowrap transition cursor-pointer flex items-center gap-2 ${
                activeTab === "dashboard"
                  ? "bg-sky-500 text-white shadow-lg shadow-sky-500/25 border border-sky-400"
                  : "bg-slate-900 text-slate-300 hover:bg-slate-800 border border-slate-800"
              }`}
            >
              <TrendingUp size={16} />
              <span>Dashboard Executivo</span>
            </button>

            <button
              type="button"
              onClick={() => setActiveTab("estoque")}
              className={`px-4 py-2.5 rounded-xl text-sm font-bold whitespace-nowrap transition cursor-pointer flex items-center gap-2 ${
                activeTab === "estoque"
                  ? "bg-sky-500 text-white shadow-lg shadow-sky-500/25 border border-sky-400"
                  : "bg-slate-900 text-slate-300 hover:bg-slate-800 border border-slate-800"
              }`}
            >
              <Package size={16} />
              <span>Estoque & Validades</span>
            </button>

            <button
              type="button"
              onClick={() => setActiveTab("fiscal")}
              className={`px-4 py-2.5 rounded-xl text-sm font-bold whitespace-nowrap transition cursor-pointer flex items-center gap-2 ${
                activeTab === "fiscal"
                  ? "bg-sky-500 text-white shadow-lg shadow-sky-500/25 border border-sky-400"
                  : "bg-slate-900 text-slate-300 hover:bg-slate-800 border border-slate-800"
              }`}
            >
              <FileCheck2 size={16} />
              <span>Fiscal NFC-e</span>
            </button>

            <button
              type="button"
              onClick={() => setActiveTab("fiado")}
              className={`px-4 py-2.5 rounded-xl text-sm font-bold whitespace-nowrap transition cursor-pointer flex items-center gap-2 ${
                activeTab === "fiado"
                  ? "bg-sky-500 text-white shadow-lg shadow-sky-500/25 border border-sky-400"
                  : "bg-slate-900 text-slate-300 hover:bg-slate-800 border border-slate-800"
              }`}
            >
              <Users size={16} />
              <span>Fiado / Clientes</span>
            </button>

            <button
              type="button"
              onClick={() => setActiveTab("pagamentos")}
              className={`px-4 py-2.5 rounded-xl text-sm font-bold whitespace-nowrap transition cursor-pointer flex items-center gap-2 ${
                activeTab === "pagamentos"
                  ? "bg-sky-500 text-white shadow-lg shadow-sky-500/25 border border-sky-400"
                  : "bg-slate-900 text-slate-300 hover:bg-slate-800 border border-slate-800"
              }`}
            >
              <QrCode size={16} />
              <span>PIX & Pagamentos</span>
            </button>

            <button
              type="button"
              onClick={() => setActiveTab("relatorios")}
              className={`px-4 py-2.5 rounded-xl text-sm font-bold whitespace-nowrap transition cursor-pointer flex items-center gap-2 ${
                activeTab === "relatorios"
                  ? "bg-sky-500 text-white shadow-lg shadow-sky-500/25 border border-sky-400"
                  : "bg-slate-900 text-slate-300 hover:bg-slate-800 border border-slate-800"
              }`}
            >
              <BarChart2Icon size={16} />
              <span>Relatórios DRE</span>
            </button>
          </div>

          {/* Card Principal da Aba Ativa */}
          <div className="bg-slate-900/90 border border-slate-800 rounded-3xl p-6 sm:p-8 lg:p-10 shadow-2xl">
            <div className="grid grid-cols-1 lg:grid-cols-12 gap-8 items-center">
              {/* Informações da Aba */}
              <div className="lg:col-span-5 flex flex-col justify-between">
                <div>
                  <span className="inline-flex items-center gap-1.5 px-3 py-1 rounded-full bg-sky-500/15 border border-sky-500/30 text-sky-400 text-xs font-bold uppercase tracking-wider mb-4">
                    {currentTab.tag}
                  </span>
                  <h3 className="text-2xl sm:text-3xl font-extrabold text-white font-display">
                    {currentTab.title}
                  </h3>
                  <p className="text-slate-300 text-sm sm:text-base mt-3 leading-relaxed">
                    {currentTab.subtitle}
                  </p>

                  {/* Highlights */}
                  <div className="mt-6 flex flex-wrap gap-2">
                    {currentTab.highlights.map((h, i) => (
                      <span
                        key={i}
                        className="px-3 py-1.5 text-xs font-semibold rounded-lg bg-slate-800/80 text-sky-300 border border-slate-700/60"
                      >
                        ✓ {h}
                      </span>
                    ))}
                  </div>

                  {/* Bullets com Ícones */}
                  <div className="mt-6 space-y-4">
                    {currentTab.bullets.map((b, i) => (
                      <div key={i} className="flex items-start gap-3">
                        <div className="h-6 w-6 rounded-md bg-emerald-500/20 text-emerald-400 flex items-center justify-center shrink-0 mt-0.5">
                          <Check size={14} />
                        </div>
                        <div>
                          <h4 className="text-sm font-bold text-white">
                            {b.title}
                          </h4>
                          <p className="text-xs text-slate-400 mt-0.5 leading-normal">
                            {b.desc}
                          </p>
                        </div>
                      </div>
                    ))}
                  </div>
                </div>

                <div className="mt-8 pt-6 border-t border-slate-800 flex items-center gap-4">
                  <button
                    type="button"
                    onClick={onOpenRegister}
                    className="px-6 py-3 text-sm font-bold text-white bg-sky-500 hover:bg-sky-400 rounded-xl transition cursor-pointer flex items-center gap-2 shadow-lg shadow-sky-500/20"
                  >
                    <span>Começar com este Módulo</span>
                    <ArrowRight size={16} />
                  </button>
                  <button
                    type="button"
                    onClick={onOpenLogin}
                    className="text-xs text-slate-400 hover:text-white underline cursor-pointer"
                  >
                    Já tenho conta
                  </button>
                </div>
              </div>

              {/* Mockup da Imagem da Tela */}
              <div className="lg:col-span-7">
                <div className="relative rounded-2xl overflow-hidden border border-slate-700/80 bg-slate-950 shadow-2xl group">
                  {/* Top Window Bar Mockup */}
                  <div className="bg-slate-900 px-4 py-2.5 border-b border-slate-800 flex items-center justify-between text-xs text-slate-400">
                    <div className="flex items-center gap-1.5">
                      <span className="w-3 h-3 rounded-full bg-red-500/80 inline-block" />
                      <span className="w-3 h-3 rounded-full bg-amber-500/80 inline-block" />
                      <span className="w-3 h-3 rounded-full bg-emerald-500/80 inline-block" />
                      <span className="ml-2 font-mono text-[11px] text-slate-400">
                        quack-pdv://sistema/
                        {activeTab}
                      </span>
                    </div>
                    <span className="text-[10px] uppercase font-bold text-sky-400 bg-sky-950/60 px-2 py-0.5 rounded border border-sky-800/60">
                      {currentTab.isRealScreenshot ? "Screenshot Real" : "Preview 3D"}
                    </span>
                  </div>

                  <img
                    src={currentTab.image}
                    alt={currentTab.title}
                    className="w-full h-auto object-cover max-h-[480px] transition-transform duration-500 group-hover:scale-[1.02]"
                  />
                </div>
              </div>
            </div>
          </div>
        </div>
      </section>

      {/* ========================================================================= */}
      {/* 5. GRID DAS PRINCIPAIS FUNCIONALIDADES (COM TODAS AS FIGURAS)             */}
      {/* ========================================================================= */}
      <section id="funcionalidades" className="py-20 bg-slate-900/60 border-t border-slate-800/80">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
          <div className="text-center max-w-3xl mx-auto mb-16">
            <span className="text-xs uppercase font-bold tracking-widest text-indigo-400 bg-indigo-950/70 border border-indigo-500/30 px-3 py-1 rounded-full">
              Poder Operacional Completo
            </span>
            <h2 className="text-3xl sm:text-4xl font-extrabold text-white mt-4 font-display">
              Tudo o que seu comércio precisa em um único lugar.
            </h2>
            <p className="text-base sm:text-lg text-slate-300 mt-3">
              Desenvolvido ouvindo quem está no balcão todos os dias: sem telas
              poluídas, sem botões inúteis e sem complicação fiscal.
            </p>
          </div>

          {/* Grid de 3 Colunas com Imagens Ilustrativas */}
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-8">
            {/* Card 1: Estoque & Validade */}
            <div className="bg-slate-950 border border-slate-800 rounded-3xl overflow-hidden hover:border-sky-500/50 transition-all group flex flex-col justify-between">
              <div>
                <div className="relative h-48 overflow-hidden">
                  <img
                    src="/screenshots/smart-inventory.jpg"
                    alt="Estoque e Validade"
                    className="w-full h-full object-cover group-hover:scale-105 transition duration-500"
                  />
                  <div className="absolute inset-0 bg-gradient-to-t from-slate-950 via-slate-950/30 to-transparent" />
                  <span className="absolute top-3 left-3 text-xs font-bold text-amber-300 bg-amber-950/80 border border-amber-500/30 px-2.5 py-1 rounded-lg">
                    Adeus Perdas
                  </span>
                </div>
                <div className="p-6">
                  <div className="h-10 w-10 rounded-xl bg-amber-500/20 text-amber-400 flex items-center justify-center mb-4">
                    <Package size={20} />
                  </div>
                  <h3 className="text-xl font-bold text-white font-display">
                    Estoque Inteligente & Validades
                  </h3>
                  <p className="text-sm text-slate-300 mt-2 leading-relaxed">
                    Alerta automático de mercadorias próximas do vencimento.
                    Cadastre múltiplos lotes e programe promoções antes de
                    perder produto.
                  </p>
                </div>
              </div>
              <div className="p-6 pt-0 border-t border-slate-800/60 mt-4 flex items-center justify-between text-xs text-slate-400">
                <span>Fator de conversão fardo/unidade</span>
                <span className="text-sky-400 font-bold">100% Automático</span>
              </div>
            </div>

            {/* Card 2: Emissão Fiscal NFC-e */}
            <div className="bg-slate-950 border border-slate-800 rounded-3xl overflow-hidden hover:border-emerald-500/50 transition-all group flex flex-col justify-between">
              <div>
                <div className="relative h-48 overflow-hidden">
                  <img
                    src="/screenshots/fiscal-nfce.jpg"
                    alt="Emissão Fiscal NFC-e"
                    className="w-full h-full object-cover group-hover:scale-105 transition duration-500"
                  />
                  <div className="absolute inset-0 bg-gradient-to-t from-slate-950 via-slate-950/30 to-transparent" />
                  <span className="absolute top-3 left-3 text-xs font-bold text-emerald-300 bg-emerald-950/80 border border-emerald-500/30 px-2.5 py-1 rounded-lg">
                    100% SEFAZ
                  </span>
                </div>
                <div className="p-6">
                  <div className="h-10 w-10 rounded-xl bg-emerald-500/20 text-emerald-400 flex items-center justify-center mb-4">
                    <FileCheck2 size={20} />
                  </div>
                  <h3 className="text-xl font-bold text-white font-display">
                    NFC-e & NF-e Descomplicadas
                  </h3>
                  <p className="text-sm text-slate-300 mt-2 leading-relaxed">
                    Emissão rápida com contingência offline. Se a internet
                    oscilar, sua loja não para e as notas são autorizadas quando
                    a rede voltar.
                  </p>
                </div>
              </div>
              <div className="p-6 pt-0 border-t border-slate-800/60 mt-4 flex items-center justify-between text-xs text-slate-400">
                <span>Importação XML Fornecedor</span>
                <span className="text-emerald-400 font-bold">Entrada em 1 clique</span>
              </div>
            </div>

            {/* Card 3: Fiado / Conta Corrente */}
            <div className="bg-slate-950 border border-slate-800 rounded-3xl overflow-hidden hover:border-indigo-500/50 transition-all group flex flex-col justify-between">
              <div>
                <div className="relative h-48 overflow-hidden">
                  <img
                    src="/screenshots/fiado-credito.jpg"
                    alt="Gestão de Fiado"
                    className="w-full h-full object-cover group-hover:scale-105 transition duration-500"
                  />
                  <div className="absolute inset-0 bg-gradient-to-t from-slate-950 via-slate-950/30 to-transparent" />
                  <span className="absolute top-3 left-3 text-xs font-bold text-indigo-300 bg-indigo-950/80 border border-indigo-500/30 px-2.5 py-1 rounded-lg">
                    Fim do Caderninho
                  </span>
                </div>
                <div className="p-6">
                  <div className="h-10 w-10 rounded-xl bg-indigo-500/20 text-indigo-400 flex items-center justify-center mb-4">
                    <Users size={20} />
                  </div>
                  <h3 className="text-xl font-bold text-white font-display">
                    Fiado Seguro & Limite de Crédito
                  </h3>
                  <p className="text-sm text-slate-300 mt-2 leading-relaxed">
                    Controle o saldo devedor de cada cliente, estabeleça teto de
                    compras a prazo e emita extratos impressos na hora do
                    pagamento.
                  </p>
                </div>
              </div>
              <div className="p-6 pt-0 border-t border-slate-800/60 mt-4 flex items-center justify-between text-xs text-slate-400">
                <span>Trava automática de limite</span>
                <span className="text-indigo-400 font-bold">Zero Inadimplência</span>
              </div>
            </div>

            {/* Card 4: PIX & Pagamentos */}
            <div className="bg-slate-950 border border-slate-800 rounded-3xl overflow-hidden hover:border-sky-500/50 transition-all group flex flex-col justify-between">
              <div>
                <div className="relative h-48 overflow-hidden">
                  <img
                    src="/screenshots/pagamentos-pix-card.jpg"
                    alt="PIX e Multi-Pagamentos"
                    className="w-full h-full object-cover group-hover:scale-105 transition duration-500"
                  />
                  <div className="absolute inset-0 bg-gradient-to-t from-slate-950 via-slate-950/30 to-transparent" />
                  <span className="absolute top-3 left-3 text-xs font-bold text-sky-300 bg-sky-950/80 border border-sky-500/30 px-2.5 py-1 rounded-lg">
                    Instantâneo
                  </span>
                </div>
                <div className="p-6">
                  <div className="h-10 w-10 rounded-xl bg-sky-500/20 text-sky-400 flex items-center justify-center mb-4">
                    <QrCode size={20} />
                  </div>
                  <h3 className="text-xl font-bold text-white font-display">
                    PIX Dinâmico & Múltiplas Formas
                  </h3>
                  <p className="text-sm text-slate-300 mt-2 leading-relaxed">
                    Gere o QR Code do PIX na tela do caixa com o valor exato da
                    venda. Divida pagamentos em dinheiro, cartão e fiado sem
                    erros.
                  </p>
                </div>
              </div>
              <div className="p-6 pt-0 border-t border-slate-800/60 mt-4 flex items-center justify-between text-xs text-slate-400">
                <span>Cálculo de Troco Integrado</span>
                <span className="text-sky-400 font-bold">Caixa 100% Batido</span>
              </div>
            </div>

            {/* Card 5: Relatórios & DRE */}
            <div className="bg-slate-950 border border-slate-800 rounded-3xl overflow-hidden hover:border-purple-500/50 transition-all group flex flex-col justify-between">
              <div>
                <div className="relative h-48 overflow-hidden">
                  <img
                    src="/screenshots/relatorios-analytics.jpg"
                    alt="Relatórios e DRE"
                    className="w-full h-full object-cover group-hover:scale-105 transition duration-500"
                  />
                  <div className="absolute inset-0 bg-gradient-to-t from-slate-950 via-slate-950/30 to-transparent" />
                  <span className="absolute top-3 left-3 text-xs font-bold text-purple-300 bg-purple-950/80 border border-purple-500/30 px-2.5 py-1 rounded-lg">
                    Gestão Baseada em Dados
                  </span>
                </div>
                <div className="p-6">
                  <div className="h-10 w-10 rounded-xl bg-purple-500/20 text-purple-400 flex items-center justify-center mb-4">
                    <TrendingUp size={20} />
                  </div>
                  <h3 className="text-xl font-bold text-white font-display">
                    Curva ABC & Margem Real
                  </h3>
                  <p className="text-sm text-slate-300 mt-2 leading-relaxed">
                    Acompanhe o markup cadastrado versus o markup realmente
                    praticado no balcão. Saiba quais itens pagam as contas da sua
                    empresa.
                  </p>
                </div>
              </div>
              <div className="p-6 pt-0 border-t border-slate-800/60 mt-4 flex items-center justify-between text-xs text-slate-400">
                <span>Fechamento por Operador</span>
                <span className="text-purple-400 font-bold">Auditoria Total</span>
              </div>
            </div>

            {/* Card 6: Promoções & Preços Dinâmicos */}
            <div className="bg-slate-950 border border-slate-800 rounded-3xl overflow-hidden hover:border-rose-500/50 transition-all group flex flex-col justify-between">
              <div>
                <div className="relative h-48 overflow-hidden bg-gradient-to-tr from-rose-950 via-slate-900 to-indigo-950 flex items-center justify-center p-6">
                  <div className="w-full h-full rounded-xl border border-rose-500/30 bg-slate-900/80 p-4 flex flex-col justify-center items-center text-center">
                    <span className="text-xs uppercase font-bold text-rose-400">
                      Leve 3 Pague 2
                    </span>
                    <p className="text-2xl font-extrabold text-white mt-1">
                      Promoção Ativa
                    </p>
                    <p className="text-xs text-emerald-400 mt-1">
                      Desconto automático aplicado no caixa
                    </p>
                  </div>
                  <div className="absolute inset-0 bg-gradient-to-t from-slate-950 via-transparent to-transparent" />
                  <span className="absolute top-3 left-3 text-xs font-bold text-rose-300 bg-rose-950/80 border border-rose-500/30 px-2.5 py-1 rounded-lg">
                    Mais Vendas
                  </span>
                </div>
                <div className="p-6">
                  <div className="h-10 w-10 rounded-xl bg-rose-500/20 text-rose-400 flex items-center justify-center mb-4">
                    <Sparkles size={20} />
                  </div>
                  <h3 className="text-xl font-bold text-white font-display">
                    Promoções & Preços de Atacado
                  </h3>
                  <p className="text-sm text-slate-300 mt-2 leading-relaxed">
                    Configure preços progressivos ("a partir de 3 unidades sai a
                    R$ X"), descontos por período e fidelize clientes com
                    ofertas dinâmicas.
                  </p>
                </div>
              </div>
              <div className="p-6 pt-0 border-t border-slate-800/60 mt-4 flex items-center justify-between text-xs text-slate-400">
                <span>Preço no atacarejo</span>
                <span className="text-rose-400 font-bold">Sem intervenção</span>
              </div>
            </div>
          </div>
        </div>
      </section>

      {/* ========================================================================= */}
      {/* 6. SEGMENTOS ATENDIDOS                                                    */}
      {/* ========================================================================= */}
      <section id="segmentos" className="py-20 relative">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
          <div className="text-center max-w-3xl mx-auto mb-14">
            <span className="text-xs uppercase font-bold tracking-widest text-emerald-400 bg-emerald-950/70 border border-emerald-500/30 px-3 py-1 rounded-full">
              Feito para o Seu Negócio
            </span>
            <h2 className="text-3xl sm:text-4xl font-extrabold text-white mt-4 font-display">
              Adaptado sob medida para o seu segmento.
            </h2>
            <p className="text-base sm:text-lg text-slate-300 mt-3">
              Não importa o tamanho da sua loja: o Quack PDV tem os recursos
              específicos que o seu ramo de atuação exige.
            </p>
          </div>

          <div className="grid grid-cols-1 lg:grid-cols-5 gap-4">
            {segments.map((seg, idx) => {
              const Icon = seg.icon;
              const isSelected = activeSegment === idx;
              return (
                <div
                  key={idx}
                  onClick={() => setActiveSegment(idx)}
                  className={`p-6 rounded-2xl border cursor-pointer transition-all ${
                    isSelected
                      ? "bg-slate-900 border-sky-500 shadow-xl shadow-sky-500/10 ring-1 ring-sky-500"
                      : "bg-slate-950/60 border-slate-800 hover:border-slate-700 hover:bg-slate-900/50"
                  }`}
                >
                  <div className="flex items-center justify-between">
                    <div
                      className={`h-12 w-12 rounded-xl flex items-center justify-center ${
                        isSelected
                          ? "bg-sky-500 text-white"
                          : "bg-slate-800 text-slate-400"
                      }`}
                    >
                      <Icon size={24} />
                    </div>
                    <span className="text-[10px] font-bold uppercase tracking-wider text-sky-400 bg-sky-950 px-2 py-0.5 rounded border border-sky-800/50">
                      {seg.badge}
                    </span>
                  </div>

                  <h4 className="text-base font-bold text-white mt-4">
                    {seg.title}
                  </h4>
                  <p className="text-xs text-slate-400 mt-2 leading-relaxed">
                    {seg.desc}
                  </p>
                </div>
              );
            })}
          </div>
        </div>
      </section>

      {/* ========================================================================= */}
      {/* 7. CALCULADORA DE RETORNO SOBRE INVESTIMENTO (ROI)                         */}
      {/* ========================================================================= */}
      <section id="calculadora" className="py-20 bg-gradient-to-b from-slate-900 to-slate-950 border-y border-slate-800">
        <div className="max-w-5xl mx-auto px-4 sm:px-6 lg:px-8">
          <div className="text-center max-w-2xl mx-auto mb-12">
            <span className="text-xs uppercase font-bold tracking-widest text-sky-400 bg-sky-950/80 border border-sky-500/30 px-3 py-1 rounded-full">
              Simule Seus Ganhos
            </span>
            <h2 className="text-3xl sm:text-4xl font-extrabold text-white mt-4 font-display">
              Quanto o Quack PDV economiza para você?
            </h2>
            <p className="text-sm sm:text-base text-slate-300 mt-2">
              Ajuste os valores abaixo com base na sua operação atual e veja o
              impacto na sua lucratividade mensal.
            </p>
          </div>

          <div className="bg-slate-950 border border-slate-800 rounded-3xl p-6 sm:p-10 shadow-2xl">
            <div className="grid grid-cols-1 lg:grid-cols-2 gap-10 items-center">
              {/* Controles da Calculadora */}
              <div className="space-y-6">
                <div>
                  <div className="flex justify-between items-center text-sm font-semibold mb-2">
                    <label className="text-slate-300">
                      Vendas registradas por dia:
                    </label>
                    <span className="text-sky-400 font-bold font-mono text-base">
                      {dailySales} vendas/dia
                    </span>
                  </div>
                  <input
                    type="range"
                    min="30"
                    max="800"
                    step="10"
                    value={dailySales}
                    onChange={(e) => setDailySales(Number(e.target.value))}
                    className="w-full accent-sky-500 cursor-pointer h-2 bg-slate-800 rounded-lg"
                  />
                  <div className="flex justify-between text-[11px] text-slate-400 mt-1">
                    <span>30 (bairro)</span>
                    <span>400 (movimentado)</span>
                    <span>800+ (mercado grande)</span>
                  </div>
                </div>

                <div>
                  <div className="flex justify-between items-center text-sm font-semibold mb-2">
                    <label className="text-slate-300">
                      Ticket médio por venda:
                    </label>
                    <span className="text-emerald-400 font-bold font-mono text-base">
                      R$ {averageTicket.toFixed(2).replace(".", ",")}
                    </span>
                  </div>
                  <input
                    type="range"
                    min="10"
                    max="250"
                    step="5"
                    value={averageTicket}
                    onChange={(e) => setAverageTicket(Number(e.target.value))}
                    className="w-full accent-emerald-500 cursor-pointer h-2 bg-slate-800 rounded-lg"
                  />
                  <div className="flex justify-between text-[11px] text-slate-400 mt-1">
                    <span>R$ 10,00</span>
                    <span>R$ 120,00</span>
                    <span>R$ 250,00+</span>
                  </div>
                </div>

                <div className="p-4 rounded-xl bg-slate-900 border border-slate-800 text-xs text-slate-400">
                  <p className="flex items-center gap-2 font-medium text-slate-300">
                    <Sparkles size={15} className="text-amber-400 shrink-0" />
                    Estimativa calculada com base na redução de perdas de validade,
                    eliminação de furos no fiado e economia de tempo por fila no balcão.
                  </p>
                </div>
              </div>

              {/* Resultado da Calculadora */}
              <div className="bg-gradient-to-br from-slate-900 via-slate-900/90 to-sky-950/40 p-6 sm:p-8 rounded-2xl border border-sky-500/30 flex flex-col justify-between">
                <div>
                  <span className="text-xs uppercase font-bold tracking-wider text-sky-400">
                    Economia Estimada para o seu Negócio
                  </span>

                  <div className="mt-4">
                    <p className="text-xs text-slate-400">
                      Recuperação estimada de perdas / mês:
                    </p>
                    <p className="text-3xl sm:text-4xl font-extrabold text-emerald-400 font-display mt-1">
                      + R${" "}
                      {estimatedSavingsPerMonth.toLocaleString("pt-BR", {
                        minimumFractionDigits: 2,
                      })}
                    </p>
                  </div>

                  <div className="mt-5 grid grid-cols-2 gap-4 border-t border-slate-800 pt-4">
                    <div>
                      <p className="text-xs text-slate-400">Faturamento Mensal:</p>
                      <p className="text-base font-bold text-white font-mono mt-0.5">
                        R${" "}
                        {monthlyRevenue.toLocaleString("pt-BR", {
                          minimumFractionDigits: 2,
                        })}
                      </p>
                    </div>
                    <div>
                      <p className="text-xs text-slate-400">Horas Salvas no Caixa:</p>
                      <p className="text-base font-bold text-sky-400 font-mono mt-0.5">
                        ~{hoursSavedPerMonth} horas/mês
                      </p>
                    </div>
                  </div>
                </div>

                <button
                  type="button"
                  onClick={onOpenRegister}
                  className="mt-6 w-full py-3.5 text-sm font-bold text-white bg-gradient-to-r from-sky-500 to-indigo-600 hover:from-sky-400 hover:to-indigo-500 rounded-xl transition cursor-pointer flex items-center justify-center gap-2 shadow-lg shadow-sky-500/20 active:scale-95"
                >
                  <span>Garantir Essa Economia Agora</span>
                  <ArrowRight size={16} />
                </button>
              </div>
            </div>
          </div>
        </div>
      </section>

      {/* ========================================================================= */}
      {/* 8. COMPARATIVO: QUACK PDV VS. SISTEMAS TRADICIONAIS                        */}
      {/* ========================================================================= */}
      <section className="py-20 relative">
        <div className="max-w-5xl mx-auto px-4 sm:px-6 lg:px-8">
          <div className="text-center max-w-2xl mx-auto mb-14">
            <span className="text-xs uppercase font-bold tracking-widest text-indigo-400 bg-indigo-950/80 border border-indigo-500/30 px-3 py-1 rounded-full">
              Comparativo de Valor
            </span>
            <h2 className="text-3xl sm:text-4xl font-extrabold text-white mt-4 font-display">
              Por que trocar para o Quack PDV?
            </h2>
            <p className="text-sm sm:text-base text-slate-300 mt-2">
              Veja a diferença entre um sistema ultrapassado e uma plataforma moderna.
            </p>
          </div>

          <div className="overflow-x-auto">
            <table className="w-full text-left text-sm border-collapse rounded-2xl overflow-hidden bg-slate-900 border border-slate-800">
              <thead>
                <tr className="border-b border-slate-800 bg-slate-950/70">
                  <th className="p-4 sm:p-5 font-bold text-slate-300">
                    Funcionalidade / Benefício
                  </th>
                  <th className="p-4 sm:p-5 font-bold text-red-400 bg-red-950/20 border-x border-slate-800">
                    Sistemas Antigos / Cadernos
                  </th>
                  <th className="p-4 sm:p-5 font-bold text-sky-400 bg-sky-950/40">
                    Quack PDV
                  </th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-800/80 text-slate-300">
                <tr>
                  <td className="p-4 sm:p-5 font-medium">
                    Velocidade do Checkout no Caixa
                  </td>
                  <td className="p-4 sm:p-5 text-slate-400 bg-red-950/10 border-x border-slate-800">
                    Lento, exige cliques constantes de mouse
                  </td>
                  <td className="p-4 sm:p-5 font-bold text-emerald-400 bg-sky-950/20">
                    ⚡ 100% no teclado (F8, F12, Enter) em 1s
                  </td>
                </tr>
                <tr>
                  <td className="p-4 sm:p-5 font-medium">
                    Se a internet cair durante a venda
                  </td>
                  <td className="p-4 sm:p-5 text-slate-400 bg-red-950/10 border-x border-slate-800">
                    Trava a fila e impede conclusão da venda
                  </td>
                  <td className="p-4 sm:p-5 font-bold text-emerald-400 bg-sky-950/20">
                    🛡️ Contingência offline automática sem parar
                  </td>
                </tr>
                <tr>
                  <td className="p-4 sm:p-5 font-medium">Controle de Fiado</td>
                  <td className="p-4 sm:p-5 text-slate-400 bg-red-950/10 border-x border-slate-800">
                    Caderninho de papel sujeito a sumiço e calote
                  </td>
                  <td className="p-4 sm:p-5 font-bold text-emerald-400 bg-sky-950/20">
                    🔒 Limite de crédito e extrato com assinatura
                  </td>
                </tr>
                <tr>
                  <td className="p-4 sm:p-5 font-medium">
                    Controle de Validades de Mercadorias
                  </td>
                  <td className="p-4 sm:p-5 text-slate-400 bg-red-950/10 border-x border-slate-800">
                    Visual manual, perda de produtos na prateleira
                  </td>
                  <td className="p-4 sm:p-5 font-bold text-emerald-400 bg-sky-950/20">
                    📦 Alerta antecipado para queima planejada
                  </td>
                </tr>
                <tr>
                  <td className="p-4 sm:p-5 font-medium">
                    PIX com QR Code Dinâmico
                  </td>
                  <td className="p-4 sm:p-5 text-slate-400 bg-red-950/10 border-x border-slate-800">
                    Chave manual ou plaquinha com risco de golpe
                  </td>
                  <td className="p-4 sm:p-5 font-bold text-emerald-400 bg-sky-950/20">
                    📱 QR Code na tela com valor exato da compra
                  </td>
                </tr>
                <tr>
                  <td className="p-4 sm:p-5 font-medium">
                    Cálculo Real de Markup e Margem
                  </td>
                  <td className="p-4 sm:p-5 text-slate-400 bg-red-950/10 border-x border-slate-800">
                    "No olho" ou planilhas desatualizadas
                  </td>
                  <td className="p-4 sm:p-5 font-bold text-emerald-400 bg-sky-950/20">
                    📈 Comparativo de markup cadastrado vs praticado
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </section>

      {/* ========================================================================= */}
      {/* 9. DEPOIMENTOS DE CLIENTES E PROVA SOCIAL                                  */}
      {/* ========================================================================= */}
      <section id="depoimentos" className="py-20 bg-slate-900/60 border-t border-slate-800/80">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
          <div className="text-center max-w-2xl mx-auto mb-14">
            <span className="text-xs uppercase font-bold tracking-widest text-amber-400 bg-amber-950/80 border border-amber-500/30 px-3 py-1 rounded-full">
              Lojistas que Confiam
            </span>
            <h2 className="text-3xl sm:text-4xl font-extrabold text-white mt-4 font-display">
              Quem usa o Quack PDV recomenda.
            </h2>
            <p className="text-sm sm:text-base text-slate-300 mt-2">
              Veja a opinião de comerciantes reais que transformaram sua rotina.
            </p>
          </div>

          <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
            {/* Depoimento 1 */}
            <div className="bg-slate-950 border border-slate-800 p-6 rounded-3xl flex flex-col justify-between">
              <div>
                <div className="flex text-amber-400 gap-1 mb-4">
                  {[...Array(5)].map((_, i) => (
                    <Star key={i} size={16} fill="currentColor" />
                  ))}
                </div>
                <p className="text-sm text-slate-300 leading-relaxed italic">
                  "O Quack PDV zerou as filas no horário de pico. Os caixas
                  usam só o teclado, e o F12 para fechar com PIX é surreal de
                  rápido. Nunca mais tive problema com nota fiscal na SEFAZ."
                </p>
              </div>
              <div className="mt-6 pt-4 border-t border-slate-800/80 flex items-center gap-3">
                <div className="h-10 w-10 rounded-full bg-sky-500/20 text-sky-400 flex items-center justify-center font-bold font-display">
                  RC
                </div>
                <div>
                  <h4 className="text-sm font-bold text-white">Rodrigo Carvalho</h4>
                  <p className="text-xs text-slate-400">
                    Mercado & Conveniência Pop - São Paulo/SP
                  </p>
                </div>
              </div>
            </div>

            {/* Depoimento 2 */}
            <div className="bg-slate-950 border border-slate-800 p-6 rounded-3xl flex flex-col justify-between">
              <div>
                <div className="flex text-amber-400 gap-1 mb-4">
                  {[...Array(5)].map((_, i) => (
                    <Star key={i} size={16} fill="currentColor" />
                  ))}
                </div>
                <p className="text-sm text-slate-300 leading-relaxed italic">
                  "O controle de fiado com limite de crédito salvou minha vida.
                  Eu tinha quase R$ 15 mil parados em caderninho que os clientes
                  esqueciam. Hoje o sistema avisa quem deve e emite o comprovante
                  na hora."
                </p>
              </div>
              <div className="mt-6 pt-4 border-t border-slate-800/80 flex items-center gap-3">
                <div className="h-10 w-10 rounded-full bg-indigo-500/20 text-indigo-400 flex items-center justify-center font-bold font-display">
                  MS
                </div>
                <div>
                  <h4 className="text-sm font-bold text-white">Marcos Silva</h4>
                  <p className="text-xs text-slate-400">
                    Depósito & Materiais União - Goiânia/GO
                  </p>
                </div>
              </div>
            </div>

            {/* Depoimento 3 */}
            <div className="bg-slate-950 border border-slate-800 p-6 rounded-3xl flex flex-col justify-between">
              <div>
                <div className="flex text-amber-400 gap-1 mb-4">
                  {[...Array(5)].map((_, i) => (
                    <Star key={i} size={16} fill="currentColor" />
                  ))}
                </div>
                <p className="text-sm text-slate-300 leading-relaxed italic">
                  "O alerta de validade nos iogurtes e laticínios me fez parar
                  de jogar produto no lixo. Coloco em promoção 5 dias antes de
                  vencer e vendo tudo com lucro. Recomendo de olhos fechados!"
                </p>
              </div>
              <div className="mt-6 pt-4 border-t border-slate-800/80 flex items-center gap-3">
                <div className="h-10 w-10 rounded-full bg-emerald-500/20 text-emerald-400 flex items-center justify-center font-bold font-display">
                  AL
                </div>
                <div>
                  <h4 className="text-sm font-bold text-white">Ana Lúcia Ramos</h4>
                  <p className="text-xs text-slate-400">
                    Hortifrúti e Mercearia Ramos - Curitiba/PR
                  </p>
                </div>
              </div>
            </div>
          </div>
        </div>
      </section>

      {/* ========================================================================= */}
      {/* 10. PERGUNTAS FREQUENTES (FAQ ACCORDION)                                  */}
      {/* ========================================================================= */}
      <section id="faq" className="py-20 relative">
        <div className="max-w-4xl mx-auto px-4 sm:px-6 lg:px-8">
          <div className="text-center max-w-2xl mx-auto mb-14">
            <span className="text-xs uppercase font-bold tracking-widest text-sky-400 bg-sky-950/80 border border-sky-500/30 px-3 py-1 rounded-full">
              Tire Suas Dúvidas
            </span>
            <h2 className="text-3xl sm:text-4xl font-extrabold text-white mt-4 font-display">
              Perguntas Frequentes
            </h2>
            <p className="text-sm sm:text-base text-slate-300 mt-2">
              Respostas diretas e transparentes sobre o funcionamento do Quack PDV.
            </p>
          </div>

          <div className="space-y-4">
            {faqs.map((faq, index) => {
              const isOpen = openFaqIndex === index;
              return (
                <div
                  key={index}
                  className="bg-slate-900/80 border border-slate-800 rounded-2xl overflow-hidden transition-all"
                >
                  <button
                    type="button"
                    onClick={() => setOpenFaqIndex(isOpen ? null : index)}
                    className="w-full p-5 sm:p-6 text-left font-bold text-white flex items-center justify-between gap-4 cursor-pointer hover:text-sky-400 transition"
                  >
                    <span className="text-base sm:text-lg">{faq.question}</span>
                    <ChevronDown
                      size={20}
                      className={`text-slate-400 transition-transform duration-300 shrink-0 ${
                        isOpen ? "rotate-180 text-sky-400" : ""
                      }`}
                    />
                  </button>

                  {isOpen && (
                    <div className="px-5 pb-5 sm:px-6 sm:pb-6 text-sm sm:text-base text-slate-300 leading-relaxed border-t border-slate-800/60 pt-4">
                      {faq.answer}
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        </div>
      </section>

      {/* ========================================================================= */}
      {/* 11. BANNER FINAL DE CONVERSÃO & CTA                                       */}
      {/* ========================================================================= */}
      <section className="py-20 relative overflow-hidden">
        <div className="max-w-5xl mx-auto px-4 sm:px-6 lg:px-8 relative z-10">
          <div className="relative rounded-3xl p-8 sm:p-12 lg:p-16 bg-gradient-to-tr from-sky-600 via-indigo-700 to-sky-500 text-center shadow-2xl overflow-hidden border border-sky-300/30">
            {/* Decorações no Fundo */}
            <div className="absolute top-0 right-0 -translate-y-12 translate-x-12 w-64 h-64 bg-white/10 rounded-full blur-2xl pointer-events-none" />
            <div className="absolute bottom-0 left-0 translate-y-12 -translate-x-12 w-64 h-64 bg-black/20 rounded-full blur-2xl pointer-events-none" />

            <h2 className="text-3xl sm:text-4xl lg:text-5xl font-black text-white font-display tracking-tight">
              Pronto para transformar as vendas do seu negócio?
            </h2>
            <p className="mt-4 text-base sm:text-lg text-sky-100 max-w-2xl mx-auto leading-relaxed">
              Junte-se aos lojistas que aceleraram o atendimento no caixa,
              acabaram com as perdas e colocaram a gestão no piloto automático.
            </p>

            <div className="mt-8 flex flex-col sm:flex-row items-center justify-center gap-4">
              <button
                type="button"
                onClick={onOpenRegister}
                className="w-full sm:w-auto px-9 py-4 text-base font-extrabold text-slate-950 bg-white hover:bg-slate-100 rounded-2xl shadow-xl transition cursor-pointer flex items-center justify-center gap-2 active:scale-95"
              >
                <span>Criar Minha Conta Grátis</span>
                <ArrowRight size={18} />
              </button>
              <button
                type="button"
                onClick={onOpenLogin}
                className="w-full sm:w-auto px-8 py-4 text-base font-bold text-white bg-slate-950/40 hover:bg-slate-950/60 rounded-2xl border border-white/30 transition cursor-pointer"
              >
                Já Tenho Acesso
              </button>
            </div>

            <p className="mt-6 text-xs text-sky-200 font-medium">
              Sem taxa de adesão • Sem cartão de crédito • Suporte dedicado
            </p>
          </div>
        </div>
      </section>

      {/* ========================================================================= */}
      {/* 12. FOOTER INSTITUCIONAL                                                  */}
      {/* ========================================================================= */}
      <footer className="border-t border-slate-800/80 bg-slate-950 py-12 text-slate-400 text-xs sm:text-sm">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 flex flex-col sm:flex-row items-center justify-between gap-6">
          <div className="flex items-center gap-3">
            <div className="h-8 w-8 rounded-lg bg-sky-500 flex items-center justify-center text-white font-bold">
              Q
            </div>
            <div>
              <p className="font-bold text-white text-base">Quack PDV</p>
              <p className="text-xs text-slate-500">
                Ponto de Venda & Gestão Comercial
              </p>
            </div>
          </div>

          <div className="flex flex-wrap items-center gap-6 text-slate-400">
            <a href="#funcionalidades" className="hover:text-white transition">
              Funcionalidades
            </a>
            <a href="#demonstracao" className="hover:text-white transition">
              Telas
            </a>
            <a href="#calculadora" className="hover:text-white transition">
              ROI
            </a>
            <a href="#faq" className="hover:text-white transition">
              FAQ
            </a>
            <button
              type="button"
              onClick={onOpenLogin}
              className="hover:text-sky-400 transition font-semibold cursor-pointer"
            >
              Área do Cliente
            </button>
          </div>

          <p className="text-xs text-slate-500 text-center sm:text-right">
            © {new Date().getFullYear()} Quack PDV. Todos os direitos reservados.
          </p>
        </div>
      </footer>
    </div>
  );
}

function BarChart2Icon({ size = 16 }: { size?: number }) {
  return <TrendingUp size={size} />;
}
