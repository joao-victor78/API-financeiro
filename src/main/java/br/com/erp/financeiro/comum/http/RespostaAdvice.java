package br.com.erp.financeiro.comum.http;

import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/** Envolve o retorno de toda rota do módulo no formato padrão { success, data, message, errors }. */
@RestControllerAdvice(basePackages = "br.com.erp.financeiro")
public class RespostaAdvice implements ResponseBodyAdvice<Object> {

  @Override
  public boolean supports(MethodParameter tipo, Class<? extends HttpMessageConverter<?>> conversor) {
    // Imagens (QR Code) saem como bytes, sem envelope.
    return !ByteArrayHttpMessageConverter.class.isAssignableFrom(conversor);
  }

  @Override
  public Object beforeBodyWrite(Object corpo, MethodParameter tipo, MediaType mediaType,
      Class<? extends HttpMessageConverter<?>> conversor, ServerHttpRequest req, ServerHttpResponse res) {
    if (corpo instanceof Resposta) return corpo;
    Mensagem m = tipo.getMethodAnnotation(Mensagem.class);
    return Resposta.ok(corpo, m == null ? null : m.value());
  }
}
