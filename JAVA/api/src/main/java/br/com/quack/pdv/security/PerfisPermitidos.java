package br.com.quack.pdv.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Perfis que podem chamar a rota (equivale ao HorusAuthorizeRoles). Vale o perfil principal ou algum perfil
 * adicional. Na classe vale para todas as rotas; no metodo, sobrescreve a classe.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface PerfisPermitidos {

    String[] value();
}
