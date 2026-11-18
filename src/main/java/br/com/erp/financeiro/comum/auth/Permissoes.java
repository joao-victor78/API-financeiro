package br.com.erp.financeiro.comum.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Permissões exigidas pela rota (todas). financeiro:admin libera qualquer uma. */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Permissoes {
  String[] value();
}
