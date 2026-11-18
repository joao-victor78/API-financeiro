package br.com.erp.financeiro;

import static br.com.erp.financeiro.ApiEAutenticacaoTest.novaConta;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.erp.financeiro.automacao.AgendadorService;
import br.com.erp.financeiro.automacao.AgendadorService.Job;
import br.com.erp.financeiro.eventos.EventosService;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Eventos M2M: o módulo avisa os outros módulos por webhook assinado, com novas tentativas. */
class EventosTest extends TesteBase {

  @Autowired EventosService eventos;
  @Autowired AgendadorService agendador;

  record Recebido(String evento, String assinatura, String corpo) {}

  private HttpServer servidor;
  private String url;
  private final List<Recebido> recebidos = new CopyOnWriteArrayList<>();
  private volatile boolean falhar;

  @BeforeEach
  void subirModuloCoreFalso() throws Exception {
    servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    servidor.createContext("/webhooks/financeiro", ex -> {
      String corpo = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      recebidos.add(new Recebido(ex.getRequestHeaders().getFirst("x-financeiro-evento"),
          ex.getRequestHeaders().getFirst("x-financeiro-assinatura"), corpo));
      ex.sendResponseHeaders(falhar ? 500 : 204, -1);
      ex.close();
    });
    servidor.start();
    url = "http://127.0.0.1:" + servidor.getAddress().getPort() + "/webhooks/financeiro";
  }

  @AfterEach
  void derrubar() {
    servidor.stop(0);
  }

  @Test
  void assinanteRecebeOEventoAssinadoHmacEFalhasEntramEmNovaTentativa() throws Exception {
    UUID t = uuid();
    var c = cliente(t);
    c.post("/eventos/assinaturas", Map.of("modulo", "core", "url", "ftp://x", "eventos", List.of("cobranca.paga"))).esperar(422);
    JsonNode ass = c.post("/eventos/assinaturas", Map.of("modulo", "core", "url", url, "eventos", List.of("conta_receber.paga"))).esperar(201).data();
    String segredo = ass.get("segredo").asText();
    assertThat(segredo).hasSize(48);
    assertThat(c.get("/eventos/assinaturas").data().get(0).has("segredo")).isFalse();

    // drena eventos de outros testes
    eventos.despachar(1000);
    recebidos.clear();

    String conta = c.post("/contas-receber", novaConta("valor", 10)).esperar(201).data().get("id").asText();
    c.post("/contas-receber/" + conta + "/baixa", Map.of("valor", 10, "data_pagamento", "2026-10-01", "forma_pagamento", "dinheiro")).esperar(200);
    assertThat(eventos.despachar().entregues()).isEqualTo(1);
    assertThat(recebidos).hasSize(1);
    Recebido r = recebidos.get(0);
    assertThat(r.evento()).isEqualTo("conta_receber.paga");
    assertThat(r.assinatura()).isEqualTo("sha256=" + EventosService.hmacSha256(segredo, r.corpo()));
    JsonNode corpo = json.readTree(r.corpo());
    assertThat(corpo.get("tipo").asText()).isEqualTo("conta_receber.paga");
    assertThat(corpo.get("tenant_id").asText()).isEqualTo(t.toString());
    assertThat(corpo.get("conta_receber_id").asText()).isEqualTo(conta);
    assertThat(corpo.get("valor_pago").decimalValue()).isEqualByComparingTo("10");
    assertThat(corpo.get("origem").asText()).isEqualTo("manual");

    // assinante fora do ar
    falhar = true;
    String conta2 = c.post("/contas-receber", novaConta("valor", 20)).esperar(201).data().get("id").asText();
    c.post("/contas-receber/" + conta2 + "/baixa", Map.of("valor", 20, "data_pagamento", "2026-10-01", "forma_pagamento", "dinheiro")).esperar(200);
    assertThat(eventos.despachar().falhas()).isEqualTo(1);
    Map<String, Object> ev = jdbc.queryForMap(
        "SELECT status, tentativas, ultimo_erro FROM financeiro.eventos WHERE agregado_id = ?::uuid AND tipo = 'conta_receber.paga'", conta2);
    assertThat(ev.get("status")).isEqualTo("pendente");
    assertThat(((Number) ev.get("tentativas")).intValue()).isEqualTo(1);
    assertThat((String) ev.get("ultimo_erro")).contains("HTTP 500");
  }

  @Test
  void statusDaAutomacaoExpoeUltimasExecucoes() {
    UUID t = uuid();
    agendador.executar(Job.recorrencias, t);
    JsonNode s = cliente(t).get("/automacao/status").esperar(200).data();
    assertThat(s.at("/jobs/recorrencias/ultima").isNull()).isFalse();
    cliente(t).post("/automacao/executar/inexistente").esperar(400);
    cliente(t, List.of("financeiro:escrita")).post("/automacao/executar/vencimentos").esperar(403);
    cliente(t).post("/automacao/executar/vencimentos").esperar(200);
  }
}
