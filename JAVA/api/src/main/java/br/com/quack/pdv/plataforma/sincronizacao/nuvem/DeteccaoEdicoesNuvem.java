package br.com.quack.pdv.plataforma.sincronizacao.nuvem;

import br.com.quack.pdv.plataforma.sincronizacao.catalogo.TabelaSincronizada;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Liga o {@link MotorDeteccao} ao aplicador de lotes (D6): deteccao inline antes de escrever e manutencao do espelho
 * depois. Usa o {@link JdbcTemplate} do proprio aplicador, entao tudo roda na transacao do lote.
 */
@Component
@Profile("nuvem")
public class DeteccaoEdicoesNuvem implements DeteccaoInline {

    private final JdbcTemplate jdbc;
    private final MotorDeteccao motor;

    public DeteccaoEdicoesNuvem(@org.springframework.context.annotation.Lazy JdbcTemplate jdbc,
                                @org.springframework.context.annotation.Lazy MotorDeteccao motor) {
        this.jdbc = jdbc;
        this.motor = motor;
    }

    @Override
    public Set<String> antes(String companyId, TabelaSincronizada t, String chave, boolean existe) {
        return motor.inline(jdbc, companyId, t, chave, existe);
    }

    @Override
    public Set<String> antesDaRessincronizacao(String companyId, TabelaSincronizada t, String chave, boolean existe) {
        return motor.inline(jdbc, companyId, t, chave, existe, true);
    }

    @Override
    public void depois(String companyId, TabelaSincronizada t, String chave, Map<String, String> textosDoLote,
                       Set<String> bloqueadas) {
        if (!t.usaEspelho()) return;
        motor.lerLinha(jdbc, t, MotorDeteccao.partesDaChave(chave)).ifPresent(live -> {
            Map<String, String> esp = new LinkedHashMap<>(motor.espelhoDe(t, live));
            // coluna bloqueada: a nuvem nao a escreveu; o espelho guarda o valor da loja (base do comando)
            for (String c : bloqueadas) {
                if (esp.containsKey(c) && textosDoLote.containsKey(c)
                        && t.classeDaColuna(c, null) == br.com.quack.pdv.plataforma.sincronizacao.catalogo.ClasseColuna.EDITAVEL) {
                    esp.put(c, textosDoLote.get(c));
                }
            }
            motor.gravarEspelho(jdbc, t, chave, companyId, esp);
        });
    }

    @Override
    public void depoisDeExcluir(String companyId, TabelaSincronizada t, String chave) {
        if (t.usaEspelho()) motor.apagarEspelho(jdbc, t, chave);
    }

    @Override
    public boolean conhecida(String companyId, TabelaSincronizada t, String chave) {
        return t.usaEspelho() && motor.lerEspelho(jdbc, t, chave, companyId).isPresent();
    }
}
