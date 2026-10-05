/**
 * Arquivo: src/pages/Admin/AprendizadoPage.tsx
 * Objetivo: página de aprendizado — vídeos de treinamento (YouTube) e instruções em texto, organizados em seções
 *           (Caixa, Estoque…). Todos os usuários assistem; o administrador geral cria/edita seções e vídeos.
 * Entradas esperadas: não recebe props; consome a API api/treinamento.
 */
import { BookOpen, Pencil, Play, Plus, Save, Trash2, X } from "lucide-react";
import { useCallback, useEffect, useMemo, useState } from "react";
import PageHeader from "@/components/Admin/PageHeader";
import { Toast, useStatusDialog } from "@/hooks/Dialog";
import PageLayout from "@/layout/PageLayout";
import {
  treinamentoService,
  type TreinamentoSecaoDto,
  type TreinamentoVideoDto,
} from "@/services/api/treinamentoService";

type SecaoForm = { id?: string; nome: string; descricao: string; ordem: string };
type VideoForm = { id?: string; secaoId: string; titulo: string; descricao: string; instrucoes: string; url: string; ordem: string };

export default function AprendizadoPage() {
  const statusDialog = useStatusDialog();
  const [loading, setLoading] = useState(true);
  const [podeGerenciar, setPodeGerenciar] = useState(false);
  const [secoes, setSecoes] = useState<TreinamentoSecaoDto[]>([]);
  const [secaoId, setSecaoId] = useState("");
  const [videoId, setVideoId] = useState("");
  const [secaoForm, setSecaoForm] = useState<SecaoForm | null>(null);
  const [videoForm, setVideoForm] = useState<VideoForm | null>(null);
  const [saving, setSaving] = useState(false);

  const carregar = useCallback(async () => {
    try {
      const data = await treinamentoService.listar();
      setPodeGerenciar(data.podeGerenciar);
      setSecoes(data.secoes);
    } catch (error) {
      Toast.error(error instanceof Error ? error.message : "Erro ao carregar os treinamentos.");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void carregar();
  }, [carregar]);

  const secaoAtual = useMemo(() => secoes.find((item) => item.id === secaoId) ?? secoes[0] ?? null, [secoes, secaoId]);
  const videoAtual = useMemo(
    () => secaoAtual?.videos.find((item) => item.id === videoId) ?? secaoAtual?.videos[0] ?? null,
    [secaoAtual, videoId],
  );

  const passos = useMemo(
    () => (videoAtual?.instrucoes ?? "").split(/\r?\n/).map((linha) => linha.trim()).filter(Boolean),
    [videoAtual],
  );

  const salvarSecao = async () => {
    if (!secaoForm) return;
    if (!secaoForm.nome.trim()) return Toast.error("Informe o nome da seção.");
    setSaving(true);
    try {
      await treinamentoService.salvarSecao(
        { nome: secaoForm.nome.trim(), descricao: secaoForm.descricao.trim(), ordem: Number(secaoForm.ordem) || 0 },
        secaoForm.id,
      );
      Toast.success("Seção salva.");
      setSecaoForm(null);
      await carregar();
    } catch (error) {
      Toast.error(error instanceof Error ? error.message : "Erro ao salvar a seção.");
    } finally {
      setSaving(false);
    }
  };

  const excluirSecao = async (secao: TreinamentoSecaoDto) => {
    const confirmed = await statusDialog.confirm(
      `Excluir a seção "${secao.nome}"${secao.videos.length ? ` e seus ${secao.videos.length} vídeo(s)` : ""}?`,
    );
    if (!confirmed) return;
    try {
      await treinamentoService.excluirSecao(secao.id);
      Toast.success("Seção excluída.");
      setSecaoId("");
      await carregar();
    } catch (error) {
      Toast.error(error instanceof Error ? error.message : "Erro ao excluir a seção.");
    }
  };

  const salvarVideo = async () => {
    if (!videoForm) return;
    if (!videoForm.titulo.trim()) return Toast.error("Informe o título do vídeo.");
    if (!videoForm.url.trim()) return Toast.error("Informe o link do YouTube.");
    setSaving(true);
    try {
      const response = await treinamentoService.salvarVideo(
        {
          secaoId: videoForm.secaoId,
          titulo: videoForm.titulo.trim(),
          descricao: videoForm.descricao.trim(),
          instrucoes: videoForm.instrucoes,
          url: videoForm.url.trim(),
          ordem: Number(videoForm.ordem) || 0,
        },
        videoForm.id,
      );
      Toast.success("Vídeo salvo.");
      setSecaoId(videoForm.secaoId);
      if (response.data?.id) setVideoId(response.data.id);
      setVideoForm(null);
      await carregar();
    } catch (error) {
      Toast.error(error instanceof Error ? error.message : "Erro ao salvar o vídeo.");
    } finally {
      setSaving(false);
    }
  };

  const excluirVideo = async (video: TreinamentoVideoDto) => {
    const confirmed = await statusDialog.confirm(`Excluir o vídeo "${video.titulo}"?`);
    if (!confirmed) return;
    try {
      await treinamentoService.excluirVideo(video.id);
      Toast.success("Vídeo excluído.");
      setVideoId("");
      await carregar();
    } catch (error) {
      Toast.error(error instanceof Error ? error.message : "Erro ao excluir o vídeo.");
    }
  };

  const novoVideo = () => {
    if (!secaoAtual) return Toast.error("Crie uma seção primeiro.");
    setVideoForm({
      secaoId: secaoAtual.id,
      titulo: "",
      descricao: "",
      instrucoes: "",
      url: "",
      ordem: String((secaoAtual.videos.at(-1)?.ordem ?? 0) + 1),
    });
  };

  const editarVideo = (video: TreinamentoVideoDto) =>
    setVideoForm({
      id: video.id,
      secaoId: video.secaoId,
      titulo: video.titulo,
      descricao: video.descricao,
      instrucoes: video.instrucoes,
      url: `https://youtu.be/${video.youtubeId}`,
      ordem: String(video.ordem),
    });

  return (
    <PageLayout className="space-y-4 py-4 md:space-y-6 md:py-6 lg:py-8">
      <PageHeader
        title="Aprendizado"
        description="Vídeos e instruções para aprender a usar o sistema."
        action={
          podeGerenciar ? (
            <div className="flex gap-2">
              <button
                type="button"
                onClick={() => setSecaoForm({ nome: "", descricao: "", ordem: String((secoes.at(-1)?.ordem ?? 0) + 1) })}
                className="btn-secondary inline-flex items-center gap-2"
              >
                <Plus size={16} /> Nova seção
              </button>
              <button type="button" onClick={novoVideo} className="btn-primary inline-flex items-center gap-2">
                <Plus size={16} /> Novo vídeo
              </button>
            </div>
          ) : undefined
        }
      />

      {loading ? (
        <div className="card p-8 text-center text-sm text-text-secondary">Carregando…</div>
      ) : secoes.length === 0 ? (
        <div className="card flex flex-col items-center gap-2 p-10 text-center text-sm text-text-secondary">
          <BookOpen size={28} />
          Nenhum treinamento disponível ainda.
        </div>
      ) : (
        <>
          <div className="flex flex-wrap items-center gap-1.5 rounded-lg border border-border-primary bg-bg-primary p-1 w-fit max-w-full">
            {secoes.map((secao) => (
              <button
                key={secao.id}
                type="button"
                onClick={() => {
                  setSecaoId(secao.id);
                  setVideoId("");
                }}
                className={`rounded-md px-3 py-1.5 text-xs font-semibold transition-all ${
                  secaoAtual?.id === secao.id
                    ? "bg-bg-card text-brand-primary shadow-xs"
                    : "text-text-secondary hover:text-text-primary"
                }`}
              >
                {secao.nome} <span className="opacity-60">({secao.videos.length})</span>
              </button>
            ))}
          </div>

          {secaoAtual && (
            <div className="flex flex-wrap items-center justify-between gap-2">
              <p className="text-sm text-text-secondary">{secaoAtual.descricao}</p>
              {podeGerenciar && (
                <div className="flex gap-2">
                  <button
                    type="button"
                    onClick={() =>
                      setSecaoForm({
                        id: secaoAtual.id,
                        nome: secaoAtual.nome,
                        descricao: secaoAtual.descricao,
                        ordem: String(secaoAtual.ordem),
                      })
                    }
                    className="btn-secondary inline-flex items-center gap-1.5 text-xs"
                  >
                    <Pencil size={14} /> Editar seção
                  </button>
                  <button
                    type="button"
                    onClick={() => void excluirSecao(secaoAtual)}
                    className="btn-secondary inline-flex items-center gap-1.5 text-xs text-red-500"
                  >
                    <Trash2 size={14} /> Excluir seção
                  </button>
                </div>
              )}
            </div>
          )}

          {secaoAtual && secaoAtual.videos.length === 0 ? (
            <div className="card p-8 text-center text-sm text-text-secondary">Esta seção ainda não tem vídeos.</div>
          ) : (
            videoAtual && (
              <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_320px]">
                <div className="card space-y-3 p-4">
                  <div className="aspect-video w-full overflow-hidden rounded-xl bg-black">
                    <iframe
                      key={videoAtual.youtubeId}
                      src={`https://www.youtube-nocookie.com/embed/${videoAtual.youtubeId}?rel=0`}
                      title={videoAtual.titulo}
                      className="h-full w-full"
                      allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; fullscreen"
                      allowFullScreen
                    />
                  </div>
                  <div className="flex items-start justify-between gap-2">
                    <div>
                      <h2 className="text-lg font-bold text-text-primary">{videoAtual.titulo}</h2>
                      {videoAtual.descricao && <p className="mt-1 text-sm text-text-secondary">{videoAtual.descricao}</p>}
                    </div>
                    {podeGerenciar && (
                      <div className="flex shrink-0 gap-1.5">
                        <button type="button" onClick={() => editarVideo(videoAtual)} className="btn-secondary p-2" title="Editar vídeo">
                          <Pencil size={14} />
                        </button>
                        <button
                          type="button"
                          onClick={() => void excluirVideo(videoAtual)}
                          className="btn-secondary p-2 text-red-500"
                          title="Excluir vídeo"
                        >
                          <Trash2 size={14} />
                        </button>
                      </div>
                    )}
                  </div>
                  {passos.length > 0 && (
                    <div className="rounded-xl border border-border-primary bg-bg-primary p-3">
                      <h3 className="mb-2 text-sm font-semibold text-text-primary">Passo a passo</h3>
                      <ol className="list-decimal space-y-1 pl-5 text-sm text-text-secondary">
                        {passos.map((passo, index) => (
                          <li key={index}>{passo}</li>
                        ))}
                      </ol>
                    </div>
                  )}
                </div>

                <div className="card space-y-1.5 p-3 lg:max-h-[640px] lg:overflow-y-auto">
                  {secaoAtual?.videos.map((video, index) => (
                    <button
                      key={video.id}
                      type="button"
                      onClick={() => setVideoId(video.id)}
                      className={`flex w-full items-center gap-3 rounded-lg border p-2 text-left transition ${
                        video.id === videoAtual.id
                          ? "border-accent bg-accent/10"
                          : "border-border-primary hover:border-accent/50"
                      }`}
                    >
                      <span className="relative h-12 w-20 shrink-0 overflow-hidden rounded-md bg-black">
                        <img
                          src={`https://i.ytimg.com/vi/${video.youtubeId}/mqdefault.jpg`}
                          alt=""
                          loading="lazy"
                          className="h-full w-full object-cover opacity-90"
                        />
                        <Play size={16} className="absolute inset-0 m-auto text-white drop-shadow" />
                      </span>
                      <span className="min-w-0 text-sm font-medium text-text-primary">
                        <span className="mr-1 text-text-secondary">{index + 1}.</span>
                        {video.titulo}
                      </span>
                    </button>
                  ))}
                </div>
              </div>
            )
          )}
        </>
      )}

      {secaoForm && (
        <Modal title={secaoForm.id ? "Editar seção" : "Nova seção"} onClose={() => setSecaoForm(null)}>
          <Field label="Nome da seção">
            <input
              className="input-field w-full"
              value={secaoForm.nome}
              maxLength={120}
              placeholder="Ex.: Estoque"
              onChange={(e) => setSecaoForm({ ...secaoForm, nome: e.target.value })}
            />
          </Field>
          <Field label="Descrição (opcional)">
            <input
              className="input-field w-full"
              value={secaoForm.descricao}
              maxLength={400}
              onChange={(e) => setSecaoForm({ ...secaoForm, descricao: e.target.value })}
            />
          </Field>
          <Field label="Ordem">
            <input
              className="input-field w-full"
              inputMode="numeric"
              value={secaoForm.ordem}
              onChange={(e) => setSecaoForm({ ...secaoForm, ordem: e.target.value.replace(/\D/g, "") })}
            />
          </Field>
          <ModalActions saving={saving} onCancel={() => setSecaoForm(null)} onSave={() => void salvarSecao()} />
        </Modal>
      )}

      {videoForm && (
        <Modal title={videoForm.id ? "Editar vídeo" : "Novo vídeo"} onClose={() => setVideoForm(null)}>
          <Field label="Seção">
            <select
              className="select-field w-full"
              value={videoForm.secaoId}
              onChange={(e) => setVideoForm({ ...videoForm, secaoId: e.target.value })}
            >
              {secoes.map((secao) => (
                <option key={secao.id} value={secao.id} className="bg-bg-primary text-text-primary">
                  {secao.nome}
                </option>
              ))}
            </select>
          </Field>
          <Field label="Título">
            <input
              className="input-field w-full"
              value={videoForm.titulo}
              maxLength={160}
              onChange={(e) => setVideoForm({ ...videoForm, titulo: e.target.value })}
            />
          </Field>
          <Field label="Link do YouTube">
            <input
              className="input-field w-full"
              value={videoForm.url}
              placeholder="https://youtu.be/..."
              onChange={(e) => setVideoForm({ ...videoForm, url: e.target.value })}
            />
          </Field>
          <Field label="Descrição (opcional)">
            <input
              className="input-field w-full"
              value={videoForm.descricao}
              maxLength={600}
              onChange={(e) => setVideoForm({ ...videoForm, descricao: e.target.value })}
            />
          </Field>
          <Field label="Passo a passo (um passo por linha)">
            <textarea
              className="input-field min-h-28 w-full"
              value={videoForm.instrucoes}
              onChange={(e) => setVideoForm({ ...videoForm, instrucoes: e.target.value })}
            />
          </Field>
          <Field label="Ordem">
            <input
              className="input-field w-full"
              inputMode="numeric"
              value={videoForm.ordem}
              onChange={(e) => setVideoForm({ ...videoForm, ordem: e.target.value.replace(/\D/g, "") })}
            />
          </Field>
          <ModalActions saving={saving} onCancel={() => setVideoForm(null)} onSave={() => void salvarVideo()} />
        </Modal>
      )}
    </PageLayout>
  );
}

function Modal({ title, onClose, children }: { title: string; onClose: () => void; children: React.ReactNode }) {
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4" onMouseDown={onClose}>
      <div
        className="card max-h-[90vh] w-full max-w-lg space-y-3 overflow-y-auto p-5"
        onMouseDown={(event) => event.stopPropagation()}
      >
        <div className="flex items-center justify-between">
          <h2 className="text-base font-bold text-text-primary">{title}</h2>
          <button type="button" onClick={onClose} className="text-text-secondary hover:text-text-primary" aria-label="Fechar">
            <X size={18} />
          </button>
        </div>
        {children}
      </div>
    </div>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="block">
      <span className="mb-1.5 block text-sm text-text-secondary">{label}</span>
      {children}
    </label>
  );
}

function ModalActions({ saving, onCancel, onSave }: { saving: boolean; onCancel: () => void; onSave: () => void }) {
  return (
    <div className="flex justify-end gap-2 pt-1">
      <button type="button" onClick={onCancel} className="btn-secondary">
        Cancelar
      </button>
      <button type="button" onClick={onSave} disabled={saving} className="btn-primary inline-flex items-center gap-2">
        <Save size={16} /> {saving ? "Salvando…" : "Salvar"}
      </button>
    </div>
  );
}
