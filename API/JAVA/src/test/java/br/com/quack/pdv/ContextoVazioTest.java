package br.com.quack.pdv;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.quack.pdv.config.QuackProperties;
import br.com.quack.pdv.web.WebTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** O contexto sobe com o perfil de teste (sem banco; os servicos que precisam dele ficam como mocks). */
class ContextoVazioTest extends WebTestBase {

    @Autowired
    QuackProperties props;

    @Test
    void contextoSobeComPerfilDeTeste() {
        assertThat(props.getAuth().getSessionHours()).isEqualTo(8);
        assertThat(props.isDev()).isFalse();
    }
}
