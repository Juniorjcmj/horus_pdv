/**
 * Arquivo: src/pages/Admin/FluxosDocPage.tsx
 * Objetivo: documentação visual dos principais fluxos operacionais do sistema.
 * Entradas esperadas: não recebe props; exibe conteúdo estático/documental.
 */
import {
  ArrowRight,
  Ban,
  BookOpen,
  CalendarClock,
  ChevronDown,
  ChevronRight,
  Package,
  Receipt,
  ShoppingCart,
  Tag,
  Landmark,
} from "lucide-react";
import { useState, type ReactNode } from "react";
import PageHeader from "@/components/Admin/PageHeader";
import PageLayout from "@/layout/PageLayout";

/* ── tipos ────────────────────────────────────────────────── */

type Passo = {
  titulo: string;
  descricao: string;
  destaque?: boolean;
};

type FluxoConfig = {
  id: string;
  titulo: string;
  resumo: string;
  icone: ReactNode;
  cor: string;            // classe bg do badge
  corBorda: string;       // classe border-l do card
  passos: Passo[];
  dicasImportantes?: string[];
};

/* ── dados dos fluxos ─────────────────────────────────────── */

const FLUXOS: FluxoConfig[] = [
  /* 1 ── Entrada de Produto com Nota Fiscal ──────────────── */
  {
    id: "entrada-nf",
    titulo: "Entrada de Produto com Nota Fiscal",
    resumo:
      "Recebimento de mercadoria via NF-e (modelo 55) ou NFC-e (modelo 65). O sistema importa o XML, valida os dados e atualiza estoque e cadastros automaticamente.",
    icone: <Package size={20} />,
    cor: "bg-blue-500/15 text-blue-600",
    corBorda: "border-l-blue-500",
    passos: [
      {
        titulo: "Iniciar Entrada",
        descricao:
          "Acesse Notas de Entrada no menu lateral. Clique em Nova Entrada.",
      },
      {
        titulo: "Escolher origem do documento",
        descricao:
          "Consulta SEFAZ — digite a chave de acesso (44 dígitos) para baixar a NF-e direto da Receita. Upload XML — selecione o arquivo XML do computador (aceita NF-e e NFC-e).",
      },
      {
        titulo: "Preview e Validação",
        descricao:
          "O sistema lê o XML e exibe: fornecedor (CNPJ, razão social), lista de itens (código, nome, NCM, quantidade, custo). Se o fornecedor já existir, ele é identificado automaticamente.",
      },
      {
        titulo: "Revisão e Edição",
        descricao:
          "Ajuste quantidades recebidas, fator de conversão de unidade (ex.: caixa → unidade), custo, margem de lucro e preço de venda. Informe lote e validade quando aplicável. Vincule itens a produtos já cadastrados.",
        destaque: true,
      },
      {
        titulo: "Confirmação",
        descricao:
          "Ao confirmar, o sistema grava tudo de uma só vez: cria fornecedor (se novo), cria ou atualiza produtos e registra a movimentação de entrada no estoque.",
      },
      {
        titulo: "Histórico",
        descricao:
          "Todas as entradas ficam registradas com busca por fornecedor, número ou chave. É possível estornar (revertendo o estoque) e exportar XMLs do mês para a contabilidade.",
      },
    ],
    dicasImportantes: [
      "A margem de lucro é calculada em tempo real: ao alterar custo ou preço, os demais campos se ajustam.",
      "O estorno de uma entrada reverte completamente o estoque — exige justificativa.",
      "A consulta SEFAZ só funciona para NF-e modelo 55 (compras entre empresas).",
    ],
  },

  /* 2 ── Venda no PDV ────────────────────────────────────── */
  {
    id: "venda-pdv",
    titulo: "Venda no PDV",
    resumo:
      "Fluxo completo da frente de caixa, do início da venda ao cupom fiscal. Funciona mesmo sem internet — as vendas offline são sincronizadas automaticamente.",
    icone: <ShoppingCart size={20} />,
    cor: "bg-green-500/15 text-green-600",
    corBorda: "border-l-green-500",
    passos: [
      {
        titulo: "Abrir Caixa",
        descricao:
          "Inicie uma sessão de caixa informando o valor inicial em dinheiro. A sessão fica vinculada ao operador e ao terminal.",
      },
      {
        titulo: "Buscar Produto",
        descricao:
          "Use o leitor de código de barras, pesquise por nome ou navegue por categoria. Os dados vêm do banco local (IndexedDB), garantindo velocidade mesmo offline.",
      },
      {
        titulo: "Montar Carrinho",
        descricao:
          "Adicione itens, ajuste quantidades e aplique descontos. Se o preço ficar abaixo do cadastrado, o sistema solicita autorização de um supervisor.",
      },
      {
        titulo: "Pagamento",
        descricao:
          "Aceita Dinheiro (com cálculo de troco), PIX, Débito, Crédito e Fiado. Pagamento misto é suportado — combine dois ou mais métodos na mesma venda.",
        destaque: true,
      },
      {
        titulo: "Finalizar Venda",
        descricao:
          "O sistema valida todos os dados, grava a venda, os pagamentos e as movimentações de estoque em uma operação atômica. O estoque é deduzido imediatamente.",
      },
      {
        titulo: "Cupom e Recibo",
        descricao:
          "A NFC-e é enfileirada automaticamente (não bloqueia a venda). O recibo fica disponível para impressão ou reimpressão a qualquer momento.",
      },
      {
        titulo: "Modo Offline",
        descricao:
          "Sem internet, a venda é salva localmente com referência OFF-XXXXXX. Ao reconectar, o sistema sincroniza automaticamente com o servidor, que atribui o número definitivo. O processo é idempotente — não há risco de duplicidade.",
        destaque: true,
      },
    ],
    dicasImportantes: [
      "A venda nunca é bloqueada pela emissão fiscal — o cupom é processado em segundo plano.",
      "O estoque é deduzido no momento da finalização, tanto online quanto offline.",
      "Vendas offline ficam no histórico com status pendente até a sincronização.",
    ],
  },

  /* 3 ── Emissão de NFC-e ────────────────────────────────── */
  {
    id: "emissao-nfce",
    titulo: "Emissão de NFC-e",
    resumo:
      "Documento fiscal eletrônico ao consumidor. A emissão é automática após cada venda e nunca trava o caixa — se a SEFAZ estiver fora do ar, entra em contingência.",
    icone: <Receipt size={20} />,
    cor: "bg-purple-500/15 text-purple-600",
    corBorda: "border-l-purple-500",
    passos: [
      {
        titulo: "Enfileiramento automático",
        descricao:
          "Ao finalizar uma venda, a NFC-e é enfileirada automaticamente. O operador não precisa fazer nada — o processo é totalmente em segundo plano.",
      },
      {
        titulo: "Geração do XML",
        descricao:
          "O servidor monta o XML com os dados da venda: itens, quantidades, valores, forma de pagamento (código tPag) e dados do emitente.",
      },
      {
        titulo: "Assinatura digital",
        descricao:
          "O XML é assinado com o certificado digital da empresa (A1). A assinatura garante autenticidade e integridade do documento.",
      },
      {
        titulo: "Transmissão à SEFAZ",
        descricao:
          "O documento é enviado à Secretaria da Fazenda do estado. Se autorizado, recebe protocolo e número oficial.",
        destaque: true,
      },
      {
        titulo: "Contingência offline",
        descricao:
          "Se a SEFAZ estiver indisponível, o sistema emite em contingência (tpEmis=9): a venda segue normalmente e o documento é transmitido assim que a SEFAZ voltar.",
        destaque: true,
      },
      {
        titulo: "Cancelamento",
        descricao:
          "NFC-e autorizada pode ser cancelada dentro do prazo legal. Exige justificativa (mínimo 15 caracteres). Supervisor pode autorizar em casos especiais.",
      },
    ],
    dicasImportantes: [
      "Status possíveis: Pendente → Autorizado → (Cancelado). Rejeitado volta para a fila de reemissão.",
      "Documentos em contingência são retransmitidos automaticamente quando a SEFAZ volta.",
      "O menu Fiscal permite consultar, cancelar e exportar todos os XMLs do mês.",
    ],
  },

  /* 4 ── Cancelamento de NFC-e ────────────────────────────── */
  {
    id: "cancelamento-nfce",
    titulo: "Cancelamento de NFC-e",
    resumo:
      "Cancelamento de nota fiscal ao consumidor, com regra de tempo: até 30 minutos é cancelamento direto na SEFAZ; após 30 minutos, o sistema orienta a devolução via NF-e de entrada.",
    icone: <Ban size={20} />,
    cor: "bg-red-500/15 text-red-600",
    corBorda: "border-l-red-500",
    passos: [
      {
        titulo: "Localizar o documento",
        descricao:
          "Na tela de cancelamento (acessível pelo PDV ou pelo menu Fiscal), busque a NFC-e por número da venda, número da nota ou chave de acesso de 44 dígitos.",
      },
      {
        titulo: "Verificação automática de prazo",
        descricao:
          "O sistema calcula o tempo decorrido desde a autorização. NFC-e (modelo 65) tem janela de 30 minutos; NF-e (modelo 55) tem janela de 24 horas. Se o prazo expirou, o botão muda automaticamente de \"Cancelar\" para \"Devolver\".",
        destaque: true,
      },
      {
        titulo: "Autenticação de supervisor",
        descricao:
          "O cancelamento exige autorização de um gerente ou administrador. Selecione o supervisor e informe a senha. A operação fica registrada na trilha de auditoria.",
      },
      {
        titulo: "Justificativa obrigatória",
        descricao:
          "Informe o motivo do cancelamento com no mínimo 15 caracteres — exigência da SEFAZ. O sistema oferece sugestões de texto para facilitar o preenchimento.",
      },
      {
        titulo: "Transmissão do evento à SEFAZ",
        descricao:
          "O sistema envia o evento de cancelamento à SEFAZ com o certificado digital A1, o protocolo de autorização original e a justificativa. Se a SEFAZ aceitar (cStat 135 ou 155), a nota é marcada como Cancelada.",
        destaque: true,
      },
      {
        titulo: "Retentativas automáticas",
        descricao:
          "Se a transmissão falhar (timeout, SEFAZ fora do ar), o documento entra na fila de retentativas: até 12 tentativas com espera crescente (2s, 4s, 8s... até 5 minutos). Se a SEFAZ estiver indisponível por 3 tentativas seguidas, o sistema entra em modo contingência.",
      },
      {
        titulo: "Recuperação de resposta perdida",
        descricao:
          "Se houve timeout na resposta (a requisição saiu mas não voltou), o sistema consulta a SEFAZ antes de reenviar — um timeout não significa rejeição. Isso evita cancelamentos duplicados.",
      },
      {
        titulo: "Fora do prazo: Devolução",
        descricao:
          "Quando passa dos 30 minutos (NFC-e) ou 24 horas (NF-e), o cancelamento direto não é mais possível pela legislação. O sistema orienta a emissão de uma NF-e modelo 55 de entrada (devolução), que é um documento fiscal formal de estorno.",
        destaque: true,
      },
    ],
    dicasImportantes: [
      "O prazo de 30 minutos é contado a partir da autorização na SEFAZ, não da finalização da venda.",
      "A SEFAZ pode aceitar cancelamentos fora do prazo (cStat 155), mas não é garantido — prefira cancelar rapidamente.",
      "O cancelamento nunca trava o caixa: é processado em segundo plano pela fila do outbox.",
      "Toda operação de cancelamento fica registrada com supervisor, operador, data/hora e justificativa.",
    ],
  },

  /* 5 ── Fechamento de Caixa ────────────────────────────── */
  {
    id: "fechamento-caixa",
    titulo: "Fechamento de Caixa",
    resumo:
      "Conferência e encerramento da sessão de caixa. Compara o valor esperado com o valor contado, exige justificativa para diferenças e gera relatório completo.",
    icone: <Landmark size={20} />,
    cor: "bg-orange-500/15 text-orange-600",
    corBorda: "border-l-orange-500",
    passos: [
      {
        titulo: "Movimentações durante o turno",
        descricao:
          "Durante a sessão, registre Sangrias (retiradas de dinheiro) e Reforços (adições ao caixa). Ambos exigem motivo por escrito.",
      },
      {
        titulo: "Iniciar fechamento",
        descricao:
          "Somente quem abriu o caixa (ou um gerente/administrador) pode fechá-lo. Clique em Fechar Caixa.",
      },
      {
        titulo: "Contagem de dinheiro",
        descricao:
          "Conte o dinheiro físico na gaveta e informe o valor. O sistema calcula automaticamente: Esperado = Abertura + Vendas em dinheiro + Reforços − Sangrias.",
        destaque: true,
      },
      {
        titulo: "Conferência de diferença",
        descricao:
          "Se o valor contado for diferente do esperado, a diferença aparece em destaque. É obrigatório informar o motivo da diferença para continuar.",
        destaque: true,
      },
      {
        titulo: "Confirmação do fechamento",
        descricao:
          "Ao confirmar, a sessão é encerrada e o relatório de fechamento é gerado com: resumo por forma de pagamento, total de sangrias e reforços, diferença e justificativa.",
      },
      {
        titulo: "Histórico de sessões",
        descricao:
          "Consulte todas as sessões encerradas por data ou operador. O relatório detalha cada movimento realizado durante o turno.",
      },
    ],
    dicasImportantes: [
      "Não é possível fechar o caixa sem justificar diferenças — o campo é obrigatório.",
      "Sangrias e reforços ficam registrados com data, valor e motivo para auditoria.",
      "Sessões abertas há mais de 24h são bloqueadas para novas vendas como proteção.",
    ],
  },

  /* 6 ── Promoções ───────────────────────────────────────── */
  {
    id: "promocoes",
    titulo: "Promoções",
    resumo:
      "Crie campanhas promocionais com 6 tipos de desconto, aplique por categoria ou produto específico, e agende início e fim com antecedência.",
    icone: <Tag size={20} />,
    cor: "bg-pink-500/15 text-pink-600",
    corBorda: "border-l-pink-500",
    passos: [
      {
        titulo: "Criar campanha",
        descricao:
          "Acesse Promoções no menu. Clique em Nova Promoção, dê um nome à campanha e escolha o tipo.",
      },
      {
        titulo: "Escolher o tipo de promoção",
        descricao:
          "Desconto percentual (ex.: 10% off) · Desconto em valor (ex.: R$2,50 off por unidade) · Preço fixo (ex.: por R$4,99) · Leve X Pague Y (ex.: leve 3 pague 2) · Combo quantidade (ex.: 3 por R$10) · Preço atacado (ex.: acima de 10un, R$3,50 cada).",
        destaque: true,
      },
      {
        titulo: "Definir abrangência",
        descricao:
          "Por categoria — aplica a todos os produtos da categoria e subcategorias. Ou por produtos específicos — selecione individualmente quais participam.",
      },
      {
        titulo: "Agendar vigência",
        descricao:
          "Defina data/hora de início e fim. Promoções podem ser criadas inativas e ativadas depois, ou já ativas com validade futura.",
      },
      {
        titulo: "Aplicação automática na venda",
        descricao:
          "Na frente de caixa, o desconto é aplicado automaticamente ao adicionar um produto elegível. O operador vê o preço original e o promocional.",
      },
      {
        titulo: "Acompanhamento de resultados",
        descricao:
          "O painel mostra: quantidade vendida na promoção, desconto total concedido, receita bruta e receita líquida da campanha.",
      },
    ],
    dicasImportantes: [
      "Promoções por categoria incluem automaticamente todas as subcategorias.",
      "O tipo Leve X Pague Y calcula proporcionalmente para quantidades maiores (ex.: leve 6, pague 4).",
      "Promoções expiradas são desativadas automaticamente — não aparecem mais na venda.",
    ],
  },

  /* 7 ── Controle de Validade ────────────────────────────── */
  {
    id: "controle-validade",
    titulo: "Controle de Validade (FEFO)",
    resumo:
      "Rastreamento de lotes e datas de validade com lógica FEFO (primeiro a vencer, primeiro a sair). Alertas automáticos para produtos próximos do vencimento.",
    icone: <CalendarClock size={20} />,
    cor: "bg-red-500/15 text-red-600",
    corBorda: "border-l-red-500",
    passos: [
      {
        titulo: "Registro de lote",
        descricao:
          "Ao dar entrada em mercadoria (via NF ou manual), informe o número do lote e a data de validade. Se a categoria tiver prazo padrão configurado, a data é sugerida automaticamente.",
      },
      {
        titulo: "Painel de alertas",
        descricao:
          "O sistema exibe 4 indicadores: Vencidos (vermelho), Críticos (laranja — até 1/3 da janela de alerta), Atenção (amarelo — dentro da janela) e Valor em Risco (soma do custo dos lotes alertados).",
        destaque: true,
      },
      {
        titulo: "Consulta avançada de lotes",
        descricao:
          "Filtre por produto, período de validade, categoria, faixa de alerta (vencido, crítico, atenção, ok) e origem do lote. Atalhos rápidos: vencidos, vence hoje, próximos 7/15/30/60/90 dias.",
      },
      {
        titulo: "Baixa por perda",
        descricao:
          "Lotes vencidos podem ser baixados como perda. O sistema remove a quantidade do estoque e registra a movimentação para auditoria.",
      },
      {
        titulo: "FEFO na venda",
        descricao:
          "Quando ativo, o sistema consome automaticamente o lote mais próximo do vencimento a cada venda. Três modos: Desligado (sem consumo), Sombra (consome em segundo plano para teste) e Ativo (consumo real com saldo atualizado).",
        destaque: true,
      },
      {
        titulo: "Prazos por categoria",
        descricao:
          "Configure prazo padrão de validade e janela de alerta por categoria. Subcategorias herdam do pai quando não configuradas.",
      },
    ],
    dicasImportantes: [
      "A janela de alerta padrão é 15 dias, mas pode ser ajustada por categoria.",
      "O modo Sombra permite testar o FEFO sem afetar o saldo real — ideal para a ativação inicial.",
      "A data de validade do produto é sempre a do lote mais antigo com saldo.",
    ],
  },
];

/* ── componentes internos ─────────────────────────────────── */

function PassoItem({
  numero,
  passo,
}: {
  numero: number;
  passo: Passo;
}) {
  return (
    <div className="flex gap-3">
      <div
        className={`flex h-7 w-7 shrink-0 items-center justify-center rounded-full text-xs font-bold ${
          passo.destaque
            ? "bg-accent/15 text-accent"
            : "bg-surface-secondary text-text-secondary"
        }`}
      >
        {numero}
      </div>
      <div className="min-w-0 flex-1 pt-0.5">
        <p className="text-sm font-semibold text-text-primary">
          {passo.titulo}
        </p>
        <p className="mt-0.5 text-sm leading-relaxed text-text-secondary">
          {passo.descricao}
        </p>
      </div>
    </div>
  );
}

function FluxoCard({ fluxo }: { fluxo: FluxoConfig }) {
  const [aberto, setAberto] = useState(false);

  return (
    <article
      id={fluxo.id}
      className={`card overflow-hidden border-l-4 ${fluxo.corBorda}`}
    >
      {/* cabeçalho clicável */}
      <button
        type="button"
        onClick={() => setAberto((v) => !v)}
        className="flex w-full items-start gap-3 px-4 py-4 text-left transition-colors hover:bg-surface-secondary/40 md:px-5"
      >
        <span className={`mt-0.5 flex h-9 w-9 shrink-0 items-center justify-center rounded-lg ${fluxo.cor}`}>
          {fluxo.icone}
        </span>
        <div className="min-w-0 flex-1">
          <h2 className="text-base font-semibold text-text-primary">
            {fluxo.titulo}
          </h2>
          <p className="mt-0.5 text-sm text-text-secondary">{fluxo.resumo}</p>
        </div>
        <span className="mt-1 shrink-0 text-text-secondary">
          {aberto ? <ChevronDown size={18} /> : <ChevronRight size={18} />}
        </span>
      </button>

      {/* conteúdo expandido */}
      {aberto && (
        <div className="border-t border-border-primary px-4 pb-5 pt-4 md:px-5">
          {/* passos */}
          <h3 className="mb-3 flex items-center gap-2 text-xs font-semibold uppercase tracking-wide text-text-secondary">
            <ArrowRight size={14} />
            Passo a passo
          </h3>
          <div className="space-y-4">
            {fluxo.passos.map((p, i) => (
              <PassoItem key={i} numero={i + 1} passo={p} />
            ))}
          </div>

          {/* dicas */}
          {fluxo.dicasImportantes && fluxo.dicasImportantes.length > 0 && (
            <div className="mt-5 rounded-lg border border-accent/20 bg-accent/5 px-4 py-3">
              <p className="mb-2 text-xs font-semibold uppercase tracking-wide text-accent">
                Dicas importantes
              </p>
              <ul className="space-y-1.5">
                {fluxo.dicasImportantes.map((dica, i) => (
                  <li
                    key={i}
                    className="flex gap-2 text-sm text-text-secondary"
                  >
                    <span className="mt-1.5 h-1.5 w-1.5 shrink-0 rounded-full bg-accent/60" />
                    {dica}
                  </li>
                ))}
              </ul>
            </div>
          )}
        </div>
      )}
    </article>
  );
}

/* ── página principal ─────────────────────────────────────── */

export default function FluxosDocPage() {
  return (
    <PageLayout className="space-y-4 py-4 md:space-y-6 md:py-6 lg:py-8">
      <PageHeader
        title="Documentação de Fluxos"
        description="Guia visual dos processos operacionais do sistema. Clique em cada fluxo para ver o passo a passo."
      />

      {/* índice rápido */}
      <nav className="card flex flex-wrap gap-2 px-4 py-3 md:px-5">
        <span className="mr-1 flex items-center gap-1.5 text-xs font-semibold uppercase tracking-wide text-text-secondary">
          <BookOpen size={14} />
          Ir para:
        </span>
        {FLUXOS.map((f) => (
          <a
            key={f.id}
            href={`#${f.id}`}
            onClick={(e) => {
              e.preventDefault();
              document.getElementById(f.id)?.scrollIntoView({ behavior: "smooth", block: "start" });
            }}
            className="inline-flex items-center gap-1.5 rounded-md border border-border-primary bg-bg-primary px-2.5 py-1 text-xs font-medium text-text-primary transition-colors hover:bg-surface-secondary"
          >
            {f.icone}
            {f.titulo}
          </a>
        ))}
      </nav>

      {/* cards dos fluxos */}
      <div className="space-y-3">
        {FLUXOS.map((f) => (
          <FluxoCard key={f.id} fluxo={f} />
        ))}
      </div>
    </PageLayout>
  );
}
