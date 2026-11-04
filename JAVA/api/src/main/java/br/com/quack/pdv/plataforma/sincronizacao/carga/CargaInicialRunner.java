package br.com.quack.pdv.plataforma.sincronizacao.carga;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Acionamento por linha de comando da carga inicial (D5), usado pelo instalador do grupo 7:
 * <pre>
 * java -jar quack-pdv-api.jar --spring.profiles.active=loja --quack.sync.carga.executar=true \
 *      --quack.sync.carga.nuvem-url=https://sync-pdv.quacksistemas.com.br --quack.sync.carga.email=admin@loja.com.br \
 *      --quack.sync.carga.nome-servidor="Servidor da loja"   (senha em QUACK_SYNC_CARGA_SENHA)
 * </pre>
 * Faz a carga, ativa e encerra o processo (codigo 0 = ativada; 1 = falhou, pode repetir: retoma de onde parou). A senha
 * vai por variavel de ambiente para nao aparecer na lista de processos.
 */
@Component
@Profile("loja")
@ConditionalOnProperty(name = "quack.sync.carga.executar", havingValue = "true")
public class CargaInicialRunner implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(CargaInicialRunner.class);

    private final CargaInicialLoja carga;
    private final ApplicationContext contexto;
    private final String nuvemUrl;
    private final String email;
    private final String senha;
    private final String nomeServidor;

    public CargaInicialRunner(CargaInicialLoja carga, ApplicationContext contexto,
                              @Value("${quack.sync.carga.nuvem-url:}") String nuvemUrl,
                              @Value("${quack.sync.carga.email:}") String email,
                              @Value("${quack.sync.carga.senha:}") String senha,
                              @Value("${quack.sync.carga.nome-servidor:Servidor da loja}") String nomeServidor) {
        this.carga = carga;
        this.contexto = contexto;
        this.nuvemUrl = nuvemUrl;
        this.email = email;
        this.senha = senha;
        this.nomeServidor = nomeServidor;
    }

    @Override
    public void run(ApplicationArguments args) {
        int codigo = 0;
        try {
            if (nuvemUrl.isBlank() || email.isBlank() || senha.isBlank()) {
                throw new IllegalArgumentException("Informe quack.sync.carga.nuvem-url, .email e a senha "
                        + "(variavel QUACK_SYNC_CARGA_SENHA).");
            }
            CargaInicialLoja.Resultado r = carga.executar(new CargaInicialLoja.Parametros(nuvemUrl, email, senha, nomeServidor));
            LOG.info("Carga inicial OK: empresa {}, servidor {}, {} linhas.", r.companyId(), r.serverId(), r.linhas());
        } catch (RuntimeException e) {
            LOG.error("Carga inicial falhou: {}. Rode de novo para retomar.", e.getMessage(), e);
            codigo = 1;
        }
        final int saida = codigo;
        System.exit(SpringApplication.exit(contexto, () -> saida));
    }
}
