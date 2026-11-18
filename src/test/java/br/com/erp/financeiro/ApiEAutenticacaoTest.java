package br.com.erp.financeiro;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.jsonwebtoken.Jwts;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/** Health, formato padrão de resposta, paginação, documentação, JWT RS256 e isolamento multi-tenant. */
class ApiEAutenticacaoTest extends TesteBase {

  private final UUID tenantA = uuid();
  private final UUID tenantB = uuid();

  static Map<String, Object> novaConta(Object... extra) {
    Map<String, Object> m = new HashMap<>(Map.of("cliente_id", uuid(), "descricao", "Mensalidade", "competencia", "2026-10",
        "data_vencimento", "2026-10-10", "valor", 199.9));
    for (int i = 0; i < extra.length; i += 2) m.put((String) extra[i], extra[i + 1]);
    return m;
  }

  private Resp semToken(String url) {
    return executar(get(BASE + url));
  }

  private Resp comToken(String url, String token) {
    return executar(get(BASE + url).header("Authorization", "Bearer " + token));
  }

  @Test
  void healthEPublicoEInformaBancoEAgendador() {
    Resp r = semToken("/health").esperar(200);
    assertThat(r.body().get("success").asBoolean()).isTrue();
    assertThat(r.body().get("message").isNull()).isTrue();
    assertThat(r.body().get("errors").isNull()).isTrue();
    assertThat(r.data().get("status").asText()).isEqualTo("ok");
    assertThat(r.data().get("modulo").asText()).isEqualTo("financeiro");
    assertThat(r.data().at("/dependencias/banco/status").asText()).isEqualTo("ok");
  }

  @Test
  void errosSeguemFormatoPadrao() {
    Resp r = cliente(tenantA).post("/contas-receber", Map.of("valor", -1)).esperar(422);
    assertThat(r.body().get("success").asBoolean()).isFalse();
    assertThat(r.body().get("data").isNull()).isTrue();
    assertThat(r.body().get("message").asText()).isEqualTo("Dados inválidos");
    List<String> campos = StreamSupport.stream(r.body().get("errors").spliterator(), false).map(e -> e.get("campo").asText()).toList();
    assertThat(campos).contains("cliente_id", "valor");
  }

  @Test
  void paginacaoLimitadaA100Registros() {
    cliente(tenantA).get("/contas-receber?limit=100").esperar(200);
    Resp r = cliente(tenantA).get("/contas-receber?limit=101").esperar(422);
    assertThat(r.body().at("/errors/0/mensagem").asText()).contains("100");
  }

  @Test
  void documentacaoOpenApiDisponivel() {
    Resp r = semToken("/docs-json").esperar(200);
    assertThat(r.bruta().getContentType()).contains("json");
  }

  @Test
  void semToken401() {
    Resp r = semToken("/contas-receber").esperar(401);
    assertThat(r.body().get("message").asText()).isEqualTo("Token de acesso ausente");
  }

  @Test
  void tokenAssinadoPorOutraChave401() throws Exception {
    var g = KeyPairGenerator.getInstance("RSA");
    g.initialize(2048);
    String t = Jwts.builder().subject("x").issuer("auth").claim("tenant_id", tenantA.toString())
        .claim("permissoes", List.of("financeiro:admin")).signWith(g.generateKeyPair().getPrivate(), Jwts.SIG.RS256).compact();
    comToken("/contas-receber", t).esperar(401);
  }

  @Test
  void ataqueDeTrocaDeAlgoritmoHs256401() {
    // HS256 usando a chave pública como "segredo": clássico ataque de confusão de algoritmo.
    var segredo = new SecretKeySpec(pemPublica().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    String t = Jwts.builder().subject("x").issuer("auth").claim("tenant_id", tenantA.toString())
        .claim("permissoes", List.of("financeiro:admin")).signWith(segredo, Jwts.SIG.HS256).compact();
    comToken("/contas-receber", t).esperar(401);
  }

  @Test
  void tokenExpirado401ComMensagemEspecifica() {
    String t = token(b -> b.claim("tenant_id", tenantA.toString()).claim("permissoes", List.of("financeiro:admin"))
        .expiration(Date.from(Instant.now().minusSeconds(10))));
    assertThat(comToken("/contas-receber", t).esperar(401).body().get("message").asText()).isEqualTo("Token expirado");
  }

  @Test
  void tokenSemTenant401() {
    comToken("/contas-receber", token(null, List.of("financeiro:admin"))).esperar(401);
  }

  @Test
  void permissaoInsuficiente403() {
    var leitor = cliente(tenantA, List.of("financeiro:leitura"));
    leitor.get("/contas-receber").esperar(200);
    leitor.post("/contas-receber", novaConta()).esperar(403);
  }

  @Test
  void tenantIdNuncaEAceitoDoClienteDaApi() {
    Resp r = cliente(tenantA).post("/contas-receber", novaConta("tenant_id", tenantB)).esperar(422);
    assertThat(r.body().get("errors").toString()).contains("tenant_id");
  }

  @Test
  void isolamentoTenantBNaoEnxergaDadosDoTenantA() {
    var a = cliente(tenantA);
    var b = cliente(tenantB);
    JsonNode c = a.post("/contas-receber", novaConta()).esperar(201).data();
    String id = c.get("id").asText();
    assertThat(c.get("tenant_id").asText()).isEqualTo(tenantA.toString());
    b.get("/contas-receber/" + id).esperar(404);
    b.patch("/contas-receber/" + id, Map.of("descricao", "x")).esperar(404);
    b.del("/contas-receber/" + id).esperar(404);
    JsonNode itens = b.get("/contas-receber").esperar(200).data().get("items");
    assertThat(StreamSupport.stream(itens.spliterator(), false).noneMatch(x -> x.get("id").asText().equals(id))).isTrue();
  }

  @Test
  void identificadorMalformadoNaRota400() {
    cliente(tenantA).get("/contas-receber/nao-e-uuid").esperar(400);
  }
}
