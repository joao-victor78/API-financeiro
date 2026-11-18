package br.com.erp.financeiro.comum.auth;

import br.com.erp.financeiro.comum.http.ApiException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Exige JWT válido em toda rota do módulo (exceto @Publico) e confere as permissões de @Permissoes.
 * O token vem no cabeçalho "Authorization: Bearer …" ou no cookie httpOnly "financeiro_token",
 * usado pelo frontend em iframe, que NÃO guarda o token em localStorage.
 */
@Component
public class JwtInterceptor implements HandlerInterceptor {

  public static final String COOKIE_TOKEN = "financeiro_token";
  public static final String ATRIBUTO_USUARIO = AuthUser.class.getName();
  private static final String PACOTE = "br.com.erp.financeiro";

  private final ValidadorJwt validador;

  public JwtInterceptor(ValidadorJwt validador) {
    this.validador = validador;
  }

  /** Token do cabeçalho Bearer ou, na falta dele, do cookie financeiro_token. */
  public static String extrairToken(HttpServletRequest req) {
    String h = req.getHeader("Authorization");
    if (h != null && h.startsWith("Bearer ")) return h.substring(7).trim();
    Cookie[] cookies = req.getCookies();
    if (cookies == null) return null;
    return Arrays.stream(cookies)
        .filter(c -> COOKIE_TOKEN.equals(c.getName()) && !c.getValue().isBlank())
        .map(Cookie::getValue)
        .findFirst()
        .orElse(null);
  }

  @Override
  public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
    // Só as rotas do módulo (a documentação Swagger, por exemplo, é aberta).
    if (!(handler instanceof HandlerMethod hm) || !hm.getBeanType().getPackageName().startsWith(PACOTE)) return true;
    if (anotacao(hm, Publico.class) != null) return true;

    AuthUser usuario = validador.validar(extrairToken(req));
    req.setAttribute(ATRIBUTO_USUARIO, usuario);

    Permissoes exigidas = anotacao(hm, Permissoes.class);
    if (exigidas != null && !Arrays.stream(exigidas.value()).allMatch(usuario::tem)) {
      throw ApiException.proibido("Permissão necessária: " + String.join(", ", exigidas.value()));
    }
    return true;
  }

  private static <A extends java.lang.annotation.Annotation> A anotacao(HandlerMethod hm, Class<A> tipo) {
    A noMetodo = AnnotatedElementUtils.findMergedAnnotation(hm.getMethod(), tipo);
    return noMetodo != null ? noMetodo : AnnotatedElementUtils.findMergedAnnotation(hm.getBeanType(), tipo);
  }
}
