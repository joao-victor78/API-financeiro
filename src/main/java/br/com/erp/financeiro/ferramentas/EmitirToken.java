package br.com.erp.financeiro.ferramentas;

import io.jsonwebtoken.Jwts;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * DESENVOLVIMENTO: emite um JWT RS256 como o módulo de Autenticação faria.
 *
 * <p>Uso: {@code mvn -q compile exec:java@token -Dexec.args="[tenant_uuid] [usuario|servico] [permissões,separadas,por,vírgula]"}
 */
public final class EmitirToken {

  private EmitirToken() {}

  public static PrivateKey lerChavePrivada(String pem) throws Exception {
    String b64 = pem.replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "").replaceAll("\\s", "");
    return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(b64)));
  }

  public static String emitir(PrivateKey chave, String tenant, String tipo, List<String> permissoes, Duration validade) {
    Instant agora = Instant.now();
    return Jwts.builder()
        .subject(UUID.randomUUID().toString())
        .issuer("auth")
        .claim("tenant_id", tenant)
        .claim("tipo", tipo)
        .claim("permissoes", permissoes)
        .issuedAt(Date.from(agora))
        .expiration(Date.from(agora.plus(validade)))
        .signWith(chave, Jwts.SIG.RS256)
        .compact();
  }

  public static void main(String[] args) throws Exception {
    String tenant = args.length > 0 ? args[0] : UUID.randomUUID().toString();
    String tipo = args.length > 1 ? args[1] : "usuario";
    List<String> perms = List.of((args.length > 2 ? args[2] : "financeiro:admin").split(","));
    PrivateKey chave = lerChavePrivada(Files.readString(Path.of("keys/auth-private.pem")));
    System.out.println(emitir(chave, tenant, tipo, perms, Duration.ofHours(8)));
  }
}
