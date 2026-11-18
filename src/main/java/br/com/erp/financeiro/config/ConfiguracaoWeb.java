package br.com.erp.financeiro.config;

import br.com.erp.financeiro.comum.auth.AuthUser;
import br.com.erp.financeiro.comum.auth.JwtInterceptor;
import br.com.erp.financeiro.comum.enums.ValorEnum;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.converter.ConverterFactory;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class ConfiguracaoWeb implements WebMvcConfigurer {

  private final JwtInterceptor jwt;

  public ConfiguracaoWeb(JwtInterceptor jwt) {
    this.jwt = jwt;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(jwt);
  }

  /** Controllers recebem o usuário autenticado declarando um parâmetro AuthUser. */
  @Override
  public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
    resolvers.add(new HandlerMethodArgumentResolver() {
      @Override
      public boolean supportsParameter(MethodParameter p) {
        return p.getParameterType() == AuthUser.class;
      }

      @Override
      public Object resolveArgument(MethodParameter p, ModelAndViewContainer m, NativeWebRequest req, WebDataBinderFactory f) {
        return req.getAttribute(JwtInterceptor.ATRIBUTO_USUARIO, RequestAttributes.SCOPE_REQUEST);
      }
    });
  }

  /** Filtros da query string aceitam o valor textual dos enums (?status=em_aberto). */
  @Override
  @SuppressWarnings({"unchecked", "rawtypes"})
  public void addFormatters(FormatterRegistry registry) {
    registry.addConverterFactory(new ConverterFactory<String, Enum>() {
      @Override
      public <T extends Enum> Converter<String, T> getConverter(Class<T> tipo) {
        if (!ValorEnum.class.isAssignableFrom(tipo)) return s -> (T) Enum.valueOf(tipo, s);
        return s -> s.isBlank() ? null : (T) ValorEnum.de((Class) tipo, s);
      }
    });
  }
}
