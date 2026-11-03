package br.com.quack.pdv.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.quack.pdv.config.QuackProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Le o vetores.json gerado pelo console C# (codigo real da API .NET) e confere cada item. */
class CompatibilidadeDotnetTest {

    static JsonNode vetores;
    static QuackProperties props;

    @BeforeAll
    static void carregar() throws Exception {
        try (InputStream in = CompatibilidadeDotnetTest.class.getResourceAsStream("/contrato/vetores.json")) {
            vetores = new ObjectMapper().readTree(in);
        }
        props = new QuackProperties();
        props.getAuth().setJwtSecret(vetores.get("jwtSecret").asText());
        props.getSecurity().setEncryptionKey(vetores.get("encryptionKey").asText());
    }

    @Test
    void certificadoDoDotnetTemMesmaImpressaoDigitalEValidade() {
        var vetor = vetores.get("certificado");
        var leitor = new br.com.quack.pdv.empresa.app.LeitorCertificado();
        var dados = leitor.ler(vetor.get("base64").asText(), vetor.get("senha").asText());
        assertThat(dados.thumbprint()).isEqualTo(vetor.get("thumbprint").asText());
        assertThat(br.com.quack.pdv.shared.FormatoO.comOffset(dados.validoAte())).isEqualTo(vetor.get("notAfter").asText());
        assertThatThrownBy(() -> leitor.ler(vetor.get("base64").asText(), "errada"))
            .hasMessage("Certificado digital ou senha inválidos.");
    }

    @Test
    void senhasDoDotNetSaoAceitasERecusaErrada() {
        for (JsonNode item : vetores.get("senhas")) {
            String senha = item.get("senha").asText();
            String hash = item.get("hash").asText();
            assertThat(PasswordHasher.verify(senha, hash)).as(senha).isTrue();
            assertThat(PasswordHasher.verify(senha + "x", hash)).as(senha + "x").isFalse();
        }
    }

    @Test
    void hashNovoTemFormatoDoDotNet() {
        String hash = PasswordHasher.hash("Çenha-ção#é9ü");
        String[] p = hash.split("\\.");
        assertThat(p).hasSize(3);
        assertThat(p[0]).isEqualTo("100000");
        assertThat(java.util.Base64.getDecoder().decode(p[1])).hasSize(16);
        assertThat(java.util.Base64.getDecoder().decode(p[2])).hasSize(32);
        assertThat(PasswordHasher.verify("Çenha-ção#é9ü", hash)).isTrue();
    }

    @Test
    void hashMalFormadoERecusado() {
        assertThat(PasswordHasher.verify("x", "lixo")).isFalse();
        assertThat(PasswordHasher.verify("x", "abc.def.ghi")).isFalse();
        assertThat(PasswordHasher.verify("x", "100000.%%%.%%%")).isFalse();
    }

    @Test
    void segredosEncV1DoDotNetSaoDecifrados() {
        SecretProtector comChave = new SecretProtector(props.getSecurity().getEncryptionKey(), props.getAuth().getJwtSecret());
        SecretProtector semChave = new SecretProtector("", props.getAuth().getJwtSecret());
        for (JsonNode item : vetores.get("segredos")) {
            SecretProtector protetor = item.get("usaChaveDeCifra").asBoolean() ? comChave : semChave;
            assertThat(protetor.unprotect(item.get("cifrado").asText())).isEqualTo(item.get("texto").asText());
        }
    }

    @Test
    void protegerEDecifrarVoltaAoOriginalEPassaDiretoSemPrefixo() {
        SecretProtector p = new SecretProtector(props.getSecurity().getEncryptionKey(), props.getAuth().getJwtSecret());
        String cifrado = p.protect("Açaí & café");
        assertThat(cifrado).startsWith("enc:v1:");
        assertThat(cifrado.substring(7).split("\\.")).hasSize(3);
        assertThat(p.unprotect(cifrado)).isEqualTo("Açaí & café");
        assertThat(p.protect(cifrado)).isEqualTo(cifrado);
        assertThat(p.unprotect("texto puro")).isEqualTo("texto puro");
        assertThat(p.protect("  ")).isEmpty();
        assertThat(p.unprotect("")).isEmpty();
        assertThatThrownBy(() -> p.unprotect("enc:v1:so-uma-parte")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void tokenDeRedefinicaoMesmoHashDoDotNet() {
        JsonNode r = vetores.get("tokenRedefinicao");
        assertThat(TokenRedefinicao.hash(r.get("token").asText(), props.getAuth().getJwtSecret()))
                .isEqualTo(r.get("hash").asText());
        String gerado = TokenRedefinicao.gerar();
        assertThat(gerado).matches("[A-Za-z0-9_-]{43}");
    }

    /** Gera o arquivo que o console C# verifica ("dotnet run -- verificar"): o que o Java produz o .NET aceita. */
    @Test
    void geraVetoresDoJavaParaOConsoleCsharp() throws Exception {
        ObjectMapper m = new ObjectMapper();
        ObjectNode raiz = m.createObjectNode();
        raiz.put("jwtSecret", props.getAuth().getJwtSecret());
        raiz.put("encryptionKey", props.getSecurity().getEncryptionKey());

        ArrayNode senhas = raiz.putArray("senhas");
        for (String s : new String[] {"Senha#Quack2026", "Çenha-ção#é9ü", "12345678", "senha com espaços  "}) {
            senhas.addObject().put("senha", s).put("hash", PasswordHasher.hash(s));
        }

        SecretProtector comChave = new SecretProtector(props.getSecurity().getEncryptionKey(), props.getAuth().getJwtSecret());
        SecretProtector semChave = new SecretProtector("", props.getAuth().getJwtSecret());
        ArrayNode segredos = raiz.putArray("segredos");
        for (String t : new String[] {"smtp-senha-123", "Açaí & café #2026 ✓", "x"}) {
            segredos.addObject().put("texto", t).put("cifrado", comChave.protect(t)).put("usaChaveDeCifra", true);
            segredos.addObject().put("texto", t).put("cifrado", semChave.protect(t)).put("usaChaveDeCifra", false);
        }

        JwtService jwt = new JwtService(props, Clock.systemUTC());
        String token = jwt.criarToken("usr-1700000000000", "empresa-principal", "João da Conceição",
                "joao@exemplo.com.br", "gerente", "sess-0123456789abcdef0123456789abcdef", "200.1.1.1");
        raiz.putObject("jwt").put("token", token).put("userId", "usr-1700000000000")
                .put("name", "João da Conceição").put("role", "gerente")
                .put("sessionId", "sess-0123456789abcdef0123456789abcdef");

        String tok = TokenRedefinicao.gerar();
        raiz.putObject("tokenRedefinicao").put("token", tok).put("hash", TokenRedefinicao.hash(tok, props.getAuth().getJwtSecret()));

        Path destino = Path.of("target", "vetores-java.json");
        Files.createDirectories(destino.getParent());
        Files.writeString(destino, m.writerWithDefaultPrettyPrinter().writeValueAsString(raiz));
        assertThat(destino).exists();
    }

    @Test
    void jwtDoDotNetEAceitoNoInstanteDaEmissao() {
        JsonNode j = vetores.get("jwt");
        long iat = decodificarIat(j.get("token").asText());
        Clock relogio = Clock.fixed(Instant.ofEpochSecond(iat + 60), ZoneOffset.UTC);
        UsuarioAutenticado u = new JwtService(props, relogio).validarToken(j.get("token").asText());
        assertThat(u).isNotNull();
        assertThat(u.id()).isEqualTo(j.get("userId").asText());
        assertThat(u.sessionId()).isEqualTo(j.get("sessionId").asText());
        assertThat(u.name()).isEqualTo(j.get("name").asText());
        assertThat(u.role()).isEqualTo(j.get("role").asText());
        assertThat(u.companyId()).isEqualTo(j.get("companyId").asText());
    }

    private static long decodificarIat(String token) {
        try {
            byte[] payload = java.util.Base64.getUrlDecoder().decode(token.split("\\.")[1]);
            return new ObjectMapper().readTree(payload).get("iat").asLong();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
