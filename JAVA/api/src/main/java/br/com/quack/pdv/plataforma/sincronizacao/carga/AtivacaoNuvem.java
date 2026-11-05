package br.com.quack.pdv.plataforma.sincronizacao.carga;

import br.com.quack.pdv.plataforma.sincronizacao.nuvem.ServidorLoja;
import br.com.quack.pdv.shared.NegocioException;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Ativacao do servidor da loja na nuvem (D5, passos 3 e 4). A loja diz ate onde aplicou o delta ({@code desde}) e a
 * versao do tracking dela ({@code versaoLoja}). A nuvem confere, num retrato SNAPSHOT, que nada mudou desde
 * {@code desde}; se mudou, responde 409 e a loja repete o delta. Estando limpo, marca o servidor {@code ativo}, a carga
 * {@code ativada} e grava {@code SyncEstadoNuvem(aplicadoAte = versaoLoja, detectorDesde = versao da nuvem agora)}: a
 * partir da primeira linha dessa tabela a nuvem passa a aceitar lotes (D4) e o detector (grupo 4) olha so o futuro.
 */
@Service
@Profile("nuvem")
@org.springframework.context.annotation.Lazy
public class AtivacaoNuvem {

    public record Ativacao(long aplicadoAte, long detectorDesde) {}

    private final LeitorCargaNuvem leitor;
    private final JdbcTemplate jdbc;
    private final TransactionOperations tx;

    private final br.com.quack.pdv.plataforma.sincronizacao.nuvem.MotorDeteccao motor;

    public AtivacaoNuvem(LeitorCargaNuvem leitor, JdbcTemplate jdbc, TransactionOperations tx,
                         br.com.quack.pdv.plataforma.sincronizacao.nuvem.MotorDeteccao motor) {
        this.motor = motor;
        this.leitor = leitor;
        this.jdbc = jdbc;
        this.tx = tx;
    }

    public Ativacao ativar(ServidorLoja servidor, long desde, long versaoLoja) {
        return ativar(servidor, desde, versaoLoja, false);
    }

    /**
     * Com {@code recuperacao} (loja restaurada de um backup) o retrato que a loja acabou de puxar cobre tudo o que os
     * comandos {@code alterar}, {@code inserir} e {@code forcar} pendentes diziam; eles sao dados como aplicados para nao
     * virarem conflito. {@code excluir} e {@code reenviar} continuam: a linha excluida na nuvem ainda pode existir na loja.
     */
    public Ativacao ativar(ServidorLoja servidor, long desde, long versaoLoja, boolean recuperacao) {
        if (versaoLoja < 0) {
            throw new NegocioException(HttpStatus.BAD_REQUEST, "Versao da loja invalida.");
        }
        LeitorCargaNuvem.RetratoAtivacao retrato = leitor.retratoDeAtivacao(servidor.companyId(), desde);
        if (!retrato.vazio()) {
            throw new NegocioException(HttpStatus.CONFLICT,
                    "A nuvem mudou depois do ultimo delta aplicado; repita o delta antes de ativar.");
        }
        long detectorDesde = retrato.ate();
        tx.executeWithoutResult(s -> {
            int n = jdbc.update("UPDATE dbo.SyncEstadoNuvem SET ServidorId = ?, AplicadoAte = ?, DetectorDesde = ?, "
                    + "UltimoLoteId = NULL, AtualizadoEm = SYSDATETIMEOFFSET() WHERE CompanyId = ?",
                    servidor.id(), versaoLoja, detectorDesde, servidor.companyId());
            if (n == 0) {
                jdbc.update("INSERT INTO dbo.SyncEstadoNuvem (CompanyId, ServidorId, AplicadoAte, DetectorDesde) "
                        + "VALUES (?, ?, ?, ?)", servidor.companyId(), servidor.id(), versaoLoja, detectorDesde);
            }
            gravarEspelho(servidor.companyId(), retrato.espelho());
            if (recuperacao) {
                jdbc.update("UPDATE dbo.SyncComandos SET Status = N'aplicado', ResultadoJson = N'{\"recuperacao\":true}', "
                        + "AtualizadoEm = SYSDATETIMEOFFSET() WHERE CompanyId = ? AND Status = N'pendente' "
                        + "AND Tipo IN (N'alterar', N'inserir', N'forcar')", servidor.companyId());
            }
            jdbc.update("UPDATE dbo.SyncServidores SET Status = N'ativo', AtivadoEm = SYSDATETIMEOFFSET() "
                    + "WHERE Id = ? AND Status = N'pendente'", servidor.id());
            jdbc.update("UPDATE dbo.SyncCargas SET Status = N'ativada', AtualizadoEm = SYSDATETIMEOFFSET() "
                    + "WHERE ServidorId = ? AND Status = N'aberta'", servidor.id());
        });
        return new Ativacao(versaoLoja, detectorDesde);
    }

    /**
     * Recria o espelho da empresa a partir da nuvem agora (a loja acabou de receber exatamente isso) e devolve a
     * versao em que o detector deve comecar. Usado na ativacao e pelos testes que ativam a loja sem carga.
     */
    public long reconstruirEspelho(String companyId) {
        LeitorCargaNuvem.RetratoAtivacao r = leitor.retratoDeAtivacao(companyId, leitor.versaoAtual());
        tx.executeWithoutResult(s -> gravarEspelho(companyId, r.espelho()));
        return r.ate();
    }

    private void gravarEspelho(String companyId, java.util.List<LeitorCargaNuvem.EspelhoLinha> linhas) {
        jdbc.update("DELETE FROM dbo.SyncEspelho WHERE CompanyId = ?", companyId);
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        jdbc.batchUpdate("INSERT INTO dbo.SyncEspelho (Tabela, Chave, CompanyId, ColunasJson) VALUES (?, ?, ?, ?)", linhas, 500,
                (ps, l) -> {
                    ps.setString(1, l.tabela().nome());
                    ps.setString(2, l.chave());
                    ps.setString(3, companyId);
                    try {
                        ps.setString(4, json.writeValueAsString(l.colunas()));
                    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                        throw new IllegalStateException(e);
                    }
                });
    }

    /** Pagina da carga servida: guarda so as chaves das linhas (espelho minimo) para o delta saber de que empresa e. */
    public void registrarPagina(ServidorLoja servidor, CargaPagina p) {
        var t = br.com.quack.pdv.plataforma.sincronizacao.catalogo.CatalogoSincronizacao.tabela(p.tabela())
                .filter(br.com.quack.pdv.plataforma.sincronizacao.catalogo.TabelaSincronizada::usaEspelho);
        if (t.isEmpty() || p.linhas().isEmpty()) return;
        java.util.List<String> pk = motor.chavePrimaria(t.get());
        int[] pos = pk.stream().mapToInt(c -> {
            for (int i = 0; i < p.colunas().size(); i++) if (p.colunas().get(i).nome().equals(c)) return i;
            return -1;
        }).toArray();
        jdbc.batchUpdate("""
                IF NOT EXISTS (SELECT 1 FROM dbo.SyncEspelho WHERE Tabela = ? AND Chave = ?)
                    INSERT INTO dbo.SyncEspelho (Tabela, Chave, CompanyId, ColunasJson) VALUES (?, ?, ?, N'{}')
                """, p.linhas(), 500, (ps, linha) -> {
            StringBuilder chave = new StringBuilder();
            for (int i = 0; i < pos.length; i++) chave.append(i == 0 ? "" : "|").append(linha.get(pos[i]));
            ps.setString(1, t.get().nome());
            ps.setString(2, chave.toString());
            ps.setString(3, t.get().nome());
            ps.setString(4, chave.toString());
            ps.setString(5, servidor.companyId());
        });
    }

    /** Abre a carga do servidor (uma aberta por vez) guardando a versao do retrato; devolve a versao. */
    public long abrirCarga(ServidorLoja servidor) {
        return abrirCarga(servidor, false);
    }

    /** {@code renovar}: fecha a carga aberta (de uma tentativa antiga) e abre outra com o retrato de agora. */
    public long abrirCarga(ServidorLoja servidor, boolean renovar) {
        if (renovar) {
            jdbc.update("UPDATE dbo.SyncCargas SET Status = N'substituida', AtualizadoEm = SYSDATETIMEOFFSET() "
                    + "WHERE ServidorId = ? AND Status = N'aberta'", servidor.id());
        }
        java.util.List<Long> abertas = jdbc.queryForList(
                "SELECT VersaoRetrato FROM dbo.SyncCargas WHERE ServidorId = ? AND Status = N'aberta'", Long.class,
                servidor.id());
        if (!abertas.isEmpty()) return abertas.getFirst();
        long versao = leitor.versaoAtual();
        jdbc.update("INSERT INTO dbo.SyncCargas (Id, CompanyId, ServidorId, VersaoRetrato) VALUES (?, ?, ?, ?)",
                "carga-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 24), servidor.companyId(),
                servidor.id(), versao);
        return versao;
    }
}
