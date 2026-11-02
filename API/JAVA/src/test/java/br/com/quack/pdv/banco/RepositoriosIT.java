package br.com.quack.pdv.banco;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.quack.pdv.BancoIT;
import br.com.quack.pdv.auditoria.app.AuditoriaService;
import br.com.quack.pdv.auditoria.domain.AuditLog;
import br.com.quack.pdv.auth.domain.PasswordResetToken;
import br.com.quack.pdv.auth.repo.PasswordResetTokenRepository;
import br.com.quack.pdv.empresa.domain.Empresa;
import br.com.quack.pdv.empresa.repo.EmpresaRepository;
import br.com.quack.pdv.sessao.domain.Sessao;
import br.com.quack.pdv.sessao.repo.SessaoRepository;
import br.com.quack.pdv.usuario.domain.Usuario;
import br.com.quack.pdv.usuario.repo.UsuarioRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** Mapeamento JPA contra o SQL Server real: datas com offset, acentos, IDENTITY e dados de referencia. */
@SpringBootTest
@org.springframework.transaction.annotation.Transactional
class RepositoriosIT extends BancoIT {

    @Autowired UsuarioRepository usuarios;
    @Autowired SessaoRepository sessoes;
    @Autowired EmpresaRepository empresas;
    @Autowired PasswordResetTokenRepository tokens;
    @Autowired AuditoriaService auditoria;
    @Autowired JdbcTemplate jdbc;

    @Test
    void bancoNovoTemEmpresaPrincipalAprovadaESemUsuarioDeDev() {
        Empresa principal = empresas.findById("empresa-principal").orElseThrow();
        assertThat(principal.getStatus()).isEqualTo("aprovada");
        assertThat(principal.getFantasyName()).isEqualTo("Quack PDV");
        assertThat(usuarios.findById("usr-001")).isEmpty();
        assertThat(empresas.exigeAprovacaoParaNovasEmpresas()).contains(true);
    }

    @Test
    void usuarioComAcentosEOffsetDateTimeViajamIntactos() {
        String id = "usr-it-" + UUID.randomUUID().toString().substring(0, 8);
        Usuario u = new Usuario();
        u.setId(id);
        u.setCpf("123" + id.hashCode());
        u.setName("José da Conceição Ünïcode");
        u.setEmail(id + "@teste.com");
        u.setCreatedAt("2026-11-01");
        u.setPasswordHash("100000.a.b");
        u.setFirstFailedLoginAt(OffsetDateTime.of(2026, 11, 1, 22, 30, 0, 123456700, ZoneOffset.UTC));
        usuarios.saveAndFlush(u);

        Usuario lido = usuarios.findById(id).orElseThrow();
        assertThat(lido.getName()).isEqualTo("José da Conceição Ünïcode");
        assertThat(lido.getFirstFailedLoginAt().toInstant()).isEqualTo(u.getFirstFailedLoginAt().toInstant());
        assertThat(lido.getRole()).isEqualTo("atendente");
        assertThat(usuarios.findByEmail(id.toUpperCase() + "@TESTE.COM")).isPresent(); // colacao case-insensitive
    }

    @Test
    void tentativasFalhasAtomicasBloqueiamNaQuinta() {
        String id = "usr-it-lock-" + UUID.randomUUID().toString().substring(0, 6);
        Usuario u = new Usuario();
        u.setId(id);
        u.setCpf("9" + Math.abs(id.hashCode()));
        u.setName("Lock");
        u.setEmail(id + "@teste.com");
        u.setCreatedAt("2026-11-01");
        u.setPasswordHash("100000.a.b");
        usuarios.saveAndFlush(u);

        OffsetDateTime agora = OffsetDateTime.now(ZoneOffset.UTC);
        for (int i = 1; i <= 4; i++) {
            usuarios.registrarTentativaFalha(id, agora, 5, agora.plusMinutes(10));
            assertThat(usuarios.buscarFimBloqueio(id)).isEmpty();
        }
        usuarios.registrarTentativaFalha(id, agora, 5, agora.plusMinutes(10));
        assertThat(usuarios.buscarFimBloqueio(id)).isPresent();
        assertThat(usuarios.findById(id).orElseThrow().getFailedLoginAttempts()).isEqualTo(5);

        usuarios.zerarBloqueio(id);
        assertThat(usuarios.findById(id).orElseThrow().getFailedLoginAttempts()).isZero();
        assertThat(usuarios.buscarFimBloqueio(id)).isEmpty();
    }

    @Test
    void sessaoEValidacaoDeRequisicao() {
        String uid = "usr-it-sess-" + UUID.randomUUID().toString().substring(0, 6);
        Usuario u = new Usuario();
        u.setId(uid);
        u.setCpf("8" + Math.abs(uid.hashCode()));
        u.setName("Sessao");
        u.setEmail(uid + "@teste.com");
        u.setCreatedAt("2026-11-01");
        u.setPasswordHash("x");
        u.setPerfisAdicionais("financeiro");
        usuarios.saveAndFlush(u);

        Sessao s = new Sessao();
        s.setId("sess-" + UUID.randomUUID().toString().replace("-", ""));
        s.setUserId(uid);
        s.setDevice("Navegador - Chrome");
        s.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        sessoes.saveAndFlush(s);

        var v = sessoes.validarRequisicao(s.getId(), uid, "empresa-principal").orElseThrow();
        assertThat(v.getSessionActive()).isEqualTo(1);
        assertThat(v.getUserStatus()).isEqualTo("ativo");
        assertThat(v.getPerfisAdicionais()).isEqualTo("financeiro");
        assertThat(v.getCompanyStatus()).isEqualTo("aprovada");

        sessoes.apagarPorId(s.getId());
        assertThat(sessoes.validarRequisicao(s.getId(), uid, "empresa-principal").orElseThrow().getSessionActive()).isZero();
    }

    @Test
    void auditLogGeraIdentityECortaDescricao() {
        AuditLog a = auditoria.registrar("empresa-principal", "usr-x", "Fulano", "TesteIT", "d".repeat(700), "Teste", "1", "1.2.3.4");
        assertThat(a.getId()).isNotNull().isPositive();
        String gravada = jdbc.queryForObject("SELECT Description FROM AuditLog WHERE Id = ?", String.class, a.getId());
        assertThat(gravada).hasSize(500);
    }

    @Test
    void tokenDeRedefinicaoGravaDatasComOffset() {
        String uid = "usr-it-prt-" + UUID.randomUUID().toString().substring(0, 6);
        Usuario u = new Usuario();
        u.setId(uid);
        u.setCpf("7" + Math.abs(uid.hashCode()));
        u.setName("Token");
        u.setEmail(uid + "@teste.com");
        u.setCreatedAt("2026-11-01");
        u.setPasswordHash("x");
        usuarios.saveAndFlush(u);

        OffsetDateTime agora = OffsetDateTime.now(ZoneOffset.UTC);
        PasswordResetToken t = new PasswordResetToken();
        t.setId("prt-" + UUID.randomUUID().toString().replace("-", ""));
        t.setUserId(uid);
        t.setEmail(u.getEmail());
        t.setTokenHash(UUID.randomUUID().toString().replace("-", ""));
        t.setCreatedAt(agora);
        t.setRequestedAt(agora);
        t.setExpiresAt(agora.plusMinutes(30));
        t.setUpdatedAt(agora);
        tokens.saveAndFlush(t);

        assertThat(tokens.findByTokenHashAndConsumedAtIsNull(t.getTokenHash())).isPresent();
        tokens.invalidarNaoUsados(uid, agora);
        assertThat(tokens.findByTokenHashAndConsumedAtIsNull(t.getTokenHash())).isEmpty();
    }
}
