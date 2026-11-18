package br.com.erp.financeiro.comum.auth;

import br.com.erp.financeiro.comum.http.ApiException;
import br.com.erp.financeiro.config.Ambiente;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Valida o JWT RS256 emitido pelo módulo de Autenticação usando apenas a chave pública.
 * Nenhuma consulta a banco central é feita: cada serviço valida a identidade de forma
 * descentralizada (RFC 7519), como um nó autônomo da rede.
 */
@Component
public class ValidadorJwt {

  private static final Pattern UUID_RE =
      Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", Pattern.CASE_INSENSITIVE);

  private final JwtParser parser;

  public ValidadorJwt(Ambiente ambiente) {
    String pem = ambiente.chavePublicaJwt();
    if (pem.isBlank()) {
      throw new IllegalStateException(
          "Chave pública JWT não encontrada. Rode \"mvn -q compile exec:java@chaves\" (dev) ou defina JWT_PUBLIC_KEY_PATH.");
    }
    var b = Jwts.parser().verifyWith(lerChavePublica(pem)); // só aceita assinaturas RSA com esta chave
    if (ambiente.emissorJwt() != null) b.requireIssuer(ambiente.emissorJwt());
    this.parser = b.build();
  }

  public static PublicKey lerChavePublica(String pem) {
    try {
      String base64 = pem.replaceAll("-----(BEGIN|END) PUBLIC KEY-----", "").replaceAll("\\s", "");
      return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64)));
    } catch (Exception e) {
      throw new IllegalStateException("Chave pública JWT inválida (esperado PEM \"BEGIN PUBLIC KEY\")", e);
    }
  }

  public AuthUser validar(String token) {
    if (token == null || token.isBlank()) throw ApiException.naoAutorizado("Token de acesso ausente");
    Claims c;
    try {
      c = parser.parseSignedClaims(token).getPayload();
    } catch (ExpiredJwtException e) {
      throw ApiException.naoAutorizado("Token expirado");
    } catch (JwtException | IllegalArgumentException e) {
      // assinatura de outra chave, algoritmo trocado (HS256/none), token malformado, emissor errado…
      throw ApiException.naoAutorizado("Token inválido");
    }

    Object tenant = c.get("tenant_id");
    if (!(tenant instanceof String t) || !UUID_RE.matcher(t).matches() || c.getSubject() == null) {
      throw ApiException.naoAutorizado("Token sem tenant_id/sub válidos");
    }
    UUID sub;
    try {
      sub = UUID.fromString(c.getSubject());
    } catch (IllegalArgumentException e) {
      sub = UUID.nameUUIDFromBytes(c.getSubject().getBytes()); // sub não-UUID (ex.: id de serviço): vira UUID estável
    }
    List<String> permissoes = c.get("permissoes") instanceof List<?> l
        ? l.stream().filter(String.class::isInstance).map(String.class::cast).toList()
        : List.of();
    String tipo = "servico".equals(c.get("tipo")) ? "servico" : "usuario";
    return new AuthUser(sub, UUID.fromString(t), permissoes, tipo, c.get("nome", String.class));
  }
}
