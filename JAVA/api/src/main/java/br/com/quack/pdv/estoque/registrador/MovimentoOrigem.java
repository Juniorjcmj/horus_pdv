package br.com.quack.pdv.estoque.registrador;

/** Origem e autor de uma movimentacao de estoque (quem, por que e de qual documento). */
public record MovimentoOrigem(
        String origemTipo,
        String origemId,
        String motivo,
        String usuarioId,
        String usuarioNome) {

    public MovimentoOrigem {
        motivo = motivo == null ? "" : motivo;
        usuarioId = usuarioId == null || usuarioId.isBlank() ? "sistema" : usuarioId;
        usuarioNome = usuarioNome == null || usuarioNome.isBlank() ? "Sistema" : usuarioNome;
    }

    public static MovimentoOrigem sistema(String origemTipo, String origemId, String motivo) {
        return new MovimentoOrigem(origemTipo, origemId, motivo, "sistema", "Sistema");
    }
}
