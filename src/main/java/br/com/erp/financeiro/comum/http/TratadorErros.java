package br.com.erp.financeiro.comum.http;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Converte qualquer erro no formato padrão { success:false, data:null, message, errors }. */
@RestControllerAdvice
public class TratadorErros {

  private static final Logger log = LoggerFactory.getLogger("Financeiro");

  private static ResponseEntity<Resposta> resposta(HttpStatus status, String mensagem, List<Resposta.Erro> erros) {
    return ResponseEntity.status(status).body(Resposta.erro(mensagem, erros));
  }

  /** clienteId → cliente_id; itens[0].valorUnitario → itens[0].valor_unitario (nomes como no JSON). */
  static String campoJson(String caminho) {
    return caminho.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
  }

  @ExceptionHandler(ApiException.class)
  public ResponseEntity<Resposta> negocio(ApiException e) {
    return resposta(e.getStatus(), e.getMessage(), e.getErros());
  }

  /** Validação de corpo JSON (@Valid) e de filtros da query string: 422 com a lista de campos. */
  @ExceptionHandler(BindException.class)
  public ResponseEntity<Resposta> validacao(BindException e) {
    List<Resposta.Erro> erros = e.getFieldErrors().stream()
        .map(f -> new Resposta.Erro(campoJson(f.getField()),
            f.isBindingFailure() ? "valor \"" + f.getRejectedValue() + "\" em formato inválido" : f.getDefaultMessage()))
        .collect(Collectors.toCollection(ArrayList::new));
    e.getGlobalErrors().forEach(g -> erros.add(new Resposta.Erro(null, g.getDefaultMessage())));
    return resposta(HttpStatus.UNPROCESSABLE_ENTITY, "Dados inválidos", erros);
  }

  @ExceptionHandler(HandlerMethodValidationException.class)
  public ResponseEntity<Resposta> validacaoParametros(HandlerMethodValidationException e) {
    List<Resposta.Erro> erros = e.getParameterValidationResults().stream()
        .flatMap(r -> r.getResolvableErrors().stream()
            .map(m -> new Resposta.Erro(campoJson(r.getMethodParameter().getParameterName()), m.getDefaultMessage())))
        .toList();
    return resposta(HttpStatus.UNPROCESSABLE_ENTITY, "Dados inválidos", erros);
  }

  /** JSON malformado, campo desconhecido (ex.: tenant_id) ou valor no tipo errado. */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<Resposta> jsonInvalido(HttpMessageNotReadableException e) {
    if (e.getCause() instanceof UnrecognizedPropertyException u) {
      return resposta(HttpStatus.UNPROCESSABLE_ENTITY, "Dados inválidos",
          List.of(new Resposta.Erro(u.getPropertyName(), "o campo " + u.getPropertyName() + " não é permitido")));
    }
    if (e.getCause() instanceof JsonMappingException j && !j.getPath().isEmpty()) {
      String campo = j.getPath().stream()
          .map(r -> r.getFieldName() != null ? r.getFieldName() : String.valueOf(r.getIndex()))
          .collect(Collectors.joining("."));
      String detalhe = j.getCause() instanceof IllegalArgumentException ia ? ia.getMessage() : "valor em formato inválido";
      return resposta(HttpStatus.UNPROCESSABLE_ENTITY, "Dados inválidos", List.of(new Resposta.Erro(campo, detalhe)));
    }
    return resposta(HttpStatus.BAD_REQUEST, "Corpo da requisição ausente ou JSON inválido", null);
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<Resposta> parametroInvalido(MethodArgumentTypeMismatchException e) {
    return resposta(HttpStatus.BAD_REQUEST, "Identificador em formato inválido: " + e.getName(), null);
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<Resposta> integridade(DataIntegrityViolationException e) {
    Throwable c = e.getMostSpecificCause();
    if (c instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
      return resposta(HttpStatus.CONFLICT, "Registro duplicado", null);
    }
    log.error("Violação de integridade", e);
    return resposta(HttpStatus.UNPROCESSABLE_ENTITY, "Operação viola uma regra do banco de dados", null);
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ResponseEntity<Resposta> arquivoGrande(MaxUploadSizeExceededException e) {
    return resposta(HttpStatus.PAYLOAD_TOO_LARGE, "Arquivo maior que 5 MB", null);
  }

  @ExceptionHandler(MultipartException.class)
  public ResponseEntity<Resposta> multipart(MultipartException e) {
    return resposta(HttpStatus.BAD_REQUEST, "Upload inválido: " + e.getMessage(), null);
  }

  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<Resposta> rotaInexistente(NoResourceFoundException e) {
    return resposta(HttpStatus.NOT_FOUND, "Rota não encontrada: /" + e.getResourcePath(), null);
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<Resposta> metodo(HttpRequestMethodNotSupportedException e) {
    return resposta(HttpStatus.METHOD_NOT_ALLOWED, "Método " + e.getMethod() + " não permitido nesta rota", null);
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  public ResponseEntity<Resposta> tipoConteudo(HttpMediaTypeNotSupportedException e) {
    return resposta(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Content-Type não suportado: " + e.getContentType(), null);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Resposta> inesperado(Exception e) {
    log.error("Erro inesperado", e);
    return resposta(HttpStatus.INTERNAL_SERVER_ERROR, "Erro interno", null);
  }
}
