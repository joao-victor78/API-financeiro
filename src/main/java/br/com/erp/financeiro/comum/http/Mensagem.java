package br.com.erp.financeiro.comum.http;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Mensagem colocada no campo "message" da resposta de sucesso da rota. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Mensagem {
  String value();
}
