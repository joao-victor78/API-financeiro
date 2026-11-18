package br.com.erp.financeiro.comum.http;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** Formato padrão de resposta de todos os módulos: { success, data, message, errors }. */
public record Resposta(boolean success, Object data, String message, List<Erro> errors) {

  /** Erro de um campo (validação) ou erro geral, quando {@code campo} é nulo. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Erro(String campo, String mensagem) {}

  public static Resposta ok(Object data, String message) {
    return new Resposta(true, data, message, null);
  }

  public static Resposta erro(String message, List<Erro> errors) {
    return new Resposta(false, null, message, errors);
  }
}
