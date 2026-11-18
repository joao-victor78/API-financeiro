package br.com.erp.financeiro;

import br.com.erp.financeiro.comum.Relogio;
import br.com.erp.financeiro.config.Ambiente;
import br.com.erp.financeiro.config.ConfiguracaoBanco;
import br.com.erp.financeiro.eventos.EventosService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Base dos testes de integração: sobe a aplicação inteira (Spring + PostgreSQL real) uma única vez,
 * com um banco de testes recriado do zero e um par de chaves RSA próprio dos testes.
 *
 * <p>Banco usado, em ordem de prioridade: TEST_DATABASE_URL; a DATABASE_URL do .env com o sufixo
 * "_test" no nome (o banco de trabalho nunca é tocado); postgres://financeiro:financeiro@localhost:5432/erp_test.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class TesteBase {

  public static final String BASE = "/api/financeiro";
  public static final String SEGREDO_PIX = "segredo-de-teste";
  public static final KeyPair CHAVES;
  public static final String URL_BANCO_TESTE;

  static {
    try {
      KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
      g.initialize(2048);
      CHAVES = g.generateKeyPair();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    URL_BANCO_TESTE = urlDeTeste();
    recriarSchema(URL_BANCO_TESTE);
  }

  static String urlDeTeste() {
    String env = System.getenv("TEST_DATABASE_URL");
    if (env != null && !env.isBlank()) return env;
    try {
      Path arq = Path.of(".env");
      if (Files.exists(arq)) {
        for (String linha : Files.readAllLines(arq)) {
          if (linha.matches("^\\s*DATABASE_URL\\s*=.*")) {
            URI u = URI.create(linha.substring(linha.indexOf('=') + 1).trim().replaceAll("^[\"']|[\"']$", ""));
            String nome = u.getPath() == null || u.getPath().length() <= 1 ? "financeiro" : u.getPath().substring(1);
            if (!nome.endsWith("_test")) nome += "_test";
            return new URI(u.getScheme(), u.getRawUserInfo(), u.getHost(), u.getPort(), "/" + nome, null, null).toString();
          }
        }
      }
    } catch (Exception ignorado) {
      // cai no padrão
    }
    return "postgres://financeiro:financeiro@localhost:5432/erp_test";
  }

  /** Apaga e recria (via Flyway, na subida) o schema do módulo no banco de testes. */
  static void recriarSchema(String url) {
    var c = ConfiguracaoBanco.Conexao.de(url);
    if (!c.jdbcUrl().contains("_test") && !c.jdbcUrl().contains("_bench")) {
      throw new IllegalStateException("Recuso apagar um banco que não termina em _test: " + c.jdbcUrl());
    }
    try (Connection con = DriverManager.getConnection(c.jdbcUrl(), c.usuario(), c.senha())) {
      con.createStatement().execute("DROP SCHEMA IF EXISTS financeiro CASCADE");
    } catch (Exception e) {
      throw new IllegalStateException("Testes não conseguiram usar o banco " + c.jdbcUrl() + ": " + e.getMessage()
          + ". Crie-o com: sudo -u postgres psql -c \"CREATE DATABASE erp_test OWNER financeiro\"", e);
    }
  }

  static String pemPublica() {
    return "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder().encodeToString(CHAVES.getPublic().getEncoded()) + "\n-----END PUBLIC KEY-----\n";
  }

  @DynamicPropertySource
  static void propriedades(DynamicPropertyRegistry r) {
    r.add("DATABASE_URL", () -> URL_BANCO_TESTE);
    r.add("JWT_PUBLIC_KEY", TesteBase::pemPublica);
    r.add("JWT_ISSUER", () -> "auth");
    r.add("JOBS_ENABLED", () -> "false");
    r.add("PIX_WEBHOOK_SECRET", () -> SEGREDO_PIX);
    r.add("PIX_CHAVE_RECEBEDOR", () -> "financeiro@empresa.com.br");
  }

  @Autowired protected MockMvc mvc;
  @Autowired protected ObjectMapper json;
  @Autowired protected Relogio relogio;
  @Autowired protected JdbcTemplate jdbc;
  @Autowired protected Ambiente ambiente;

  @BeforeEach
  void relogioPadrao() {
    relogio.fixar("2026-10-01");
  }

  // ------------------------------------------------------------------ utilitários

  public static UUID uuid() {
    return UUID.randomUUID();
  }

  public static String token(UUID tenant, List<String> permissoes) {
    return token(b -> b.claim("tenant_id", tenant == null ? null : tenant.toString()).claim("permissoes", permissoes));
  }

  public static String token(UUID tenant) {
    return token(tenant, List.of("financeiro:admin"));
  }

  /** Token RS256 válido por 1 hora, com ajustes opcionais (expiração, claims…). */
  public static String token(UnaryOperator<JwtBuilder> ajuste) {
    Instant agora = Instant.now();
    JwtBuilder b = Jwts.builder().subject(uuid().toString()).issuer("auth").claim("tipo", "usuario")
        .issuedAt(Date.from(agora)).expiration(Date.from(agora.plusSeconds(3600)));
    return ajuste.apply(b).signWith(CHAVES.getPrivate(), Jwts.SIG.RS256).compact();
  }

  public static String assinarPix(String corpo) {
    return "sha256=" + EventosService.hmacSha256(SEGREDO_PIX, corpo);
  }

  /** Resposta HTTP já com o corpo JSON lido. */
  public record Resp(int status, JsonNode body, MockHttpServletResponse bruta) {
    public JsonNode data() {
      return body.get("data");
    }

    public Resp esperar(int esperado) {
      if (status != esperado) throw new AssertionError("Esperado HTTP " + esperado + ", veio " + status + ": " + body);
      return this;
    }
  }

  protected Resp executar(MockHttpServletRequestBuilder req) {
    try {
      MockHttpServletResponse r = mvc.perform(req).andReturn().getResponse();
      String texto = r.getContentAsString(StandardCharsets.UTF_8);
      JsonNode body = r.getContentType() != null && r.getContentType().contains("json") && !texto.isEmpty() ? json.readTree(texto) : null;
      return new Resp(r.getStatus(), body, r);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  protected String paraJson(Object o) {
    try {
      return json.writeValueAsString(o);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** Cliente HTTP autenticado para um tenant (como os outros módulos chamariam a API). */
  public class Cliente {
    private final String token;

    public Cliente(UUID tenant, List<String> permissoes) {
      this.token = TesteBase.token(tenant, permissoes);
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder b) {
      return b.header("Authorization", "Bearer " + token);
    }

    public Resp get(String url) {
      return executar(auth(TesteBase.get(BASE + url)));
    }

    public Resp post(String url, Object corpo) {
      return executar(auth(TesteBase.post(BASE + url)).contentType(MediaType.APPLICATION_JSON).content(paraJson(corpo == null ? Map.of() : corpo)));
    }

    public Resp post(String url) {
      return post(url, null);
    }

    public Resp patch(String url, Object corpo) {
      return executar(auth(TesteBase.patch(BASE + url)).contentType(MediaType.APPLICATION_JSON).content(paraJson(corpo)));
    }

    public Resp del(String url) {
      return executar(auth(TesteBase.delete(BASE + url)));
    }
  }

  protected Cliente cliente(UUID tenant) {
    return new Cliente(tenant, List.of("financeiro:admin"));
  }

  protected Cliente cliente(UUID tenant, List<String> permissoes) {
    return new Cliente(tenant, permissoes);
  }

  // Construtores de requisição com o context path da aplicação (/api/financeiro).
  static MockHttpServletRequestBuilder get(String u) {
    return MockMvcRequestBuilders.get(u).contextPath(BASE);
  }

  static MockHttpServletRequestBuilder post(String u) {
    return MockMvcRequestBuilders.post(u).contextPath(BASE);
  }

  static MockHttpServletRequestBuilder patch(String u) {
    return MockMvcRequestBuilders.patch(u).contextPath(BASE);
  }

  static MockHttpServletRequestBuilder delete(String u) {
    return MockMvcRequestBuilders.delete(u).contextPath(BASE);
  }
}
