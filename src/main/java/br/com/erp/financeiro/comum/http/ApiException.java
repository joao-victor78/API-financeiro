package br.com.erp.financeiro.comum.http;

import java.util.List;
import lombok.Getter;
import org.springframework.http.HttpStatus;

/** Erro de negócio com o status HTTP correspondente. Vira { success:false, message, errors }. */
@Getter
public class ApiException extends RuntimeException {

  private final HttpStatus status;
  private final transient List<Resposta.Erro> erros;

  public ApiException(HttpStatus status, String mensagem) {
    this(status, mensagem, null);
  }

  public ApiException(HttpStatus status, String mensagem, List<Resposta.Erro> erros) {
    super(mensagem);
    this.status = status;
    this.erros = erros;
  }

  public static ApiException naoEncontrado(String m) {
    return new ApiException(HttpStatus.NOT_FOUND, m);
  }

  public static ApiException conflito(String m) {
    return new ApiException(HttpStatus.CONFLICT, m);
  }

  /** 422: dados válidos no formato, mas recusados pela regra de negócio. */
  public static ApiException invalido(String m) {
    return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, m);
  }

  public static ApiException requisicaoInvalida(String m) {
    return new ApiException(HttpStatus.BAD_REQUEST, m);
  }

  public static ApiException naoAutorizado(String m) {
    return new ApiException(HttpStatus.UNAUTHORIZED, m);
  }

  public static ApiException proibido(String m) {
    return new ApiException(HttpStatus.FORBIDDEN, m);
  }
}
