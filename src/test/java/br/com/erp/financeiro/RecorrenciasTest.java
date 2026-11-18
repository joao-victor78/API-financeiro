package br.com.erp.financeiro;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.erp.financeiro.automacao.AgendadorService;
import br.com.erp.financeiro.automacao.AgendadorService.Job;
import br.com.erp.financeiro.recorrencias.RecorrenciasDtos.ResultadoProcessamento;
import br.com.erp.financeiro.recorrencias.RecorrenciasService;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** O agendador gera as cobranças sozinho, sem duplicar, recuperando ciclos perdidos e respeitando o contrato. */
class RecorrenciasTest extends TesteBase {

  @Autowired AgendadorService agendador;
  @Autowired RecorrenciasService recorrencias;

  static Map<String, Object> recorrencia(Object... extra) {
    Map<String, Object> m = new HashMap<>(Map.of("cliente_id", uuid(), "contrato_id", uuid(), "descricao", "Assinatura Pro",
        "periodicidade", "mensal", "dia_vencimento", 10, "dias_antecedencia", 10, "data_inicio", "2026-10-01",
        "forma_pagamento", "pix", "itens", List.of(Map.of("tipo", "servico", "descricao", "Plano Pro", "quantidade", 1, "valor_unitario", 199.9))));
    for (int i = 0; i < extra.length; i += 2) m.put((String) extra[i], extra[i + 1]);
    return m;
  }

  private ResultadoProcessamento rodar(UUID tenant) {
    return (ResultadoProcessamento) ((AgendadorService.ResultadoJob) agendador.executar(Job.recorrencias, tenant)).resultado();
  }

  @Test
  void geraACobrancaSozinhaQuandoChegaADataSemDuplicarERecuperandoCiclosPerdidos() {
    UUID t = uuid();
    var c = cliente(t);
    JsonNode r = c.post("/recorrencias", recorrencia()).esperar(201).data();
    assertThat(r.get("proxima_competencia").asText()).isEqualTo("2026-10");
    assertThat(r.get("valor_ciclo").decimalValue()).isEqualByComparingTo("199.9");
    assertThat(r.get("proximo_ciclo").toString())
        .isEqualTo("{\"competencia\":\"2026-10\",\"data_vencimento\":\"2026-10-10\",\"data_geracao\":\"2026-09-30\"}");

    relogio.fixar("2026-09-29");
    assertThat(rodar(t).cobrancasGeradas()).isZero();

    relogio.fixar("2026-09-30");
    var res = rodar(t);
    assertThat(res.cobrancasGeradas()).isEqualTo(1);
    assertThat(res.cobrancasAprovadas()).isEqualTo(1);

    // Rodar de novo no mesmo dia não duplica (idempotência).
    assertThat(rodar(t).cobrancasGeradas()).isZero();

    // Serviço ficou fora do ar de outubro a janeiro: ao voltar, emite nov, dez e jan de uma vez.
    relogio.fixar("2027-01-05");
    assertThat(rodar(t).cobrancasGeradas()).isEqualTo(3);

    String id = r.get("id").asText();
    JsonNode cobs = c.get("/cobrancas?recorrencia_id=" + id).data().get("items");
    assertThat(StreamSupport.stream(cobs.spliterator(), false).map(x -> x.get("competencia").asText()).sorted())
        .containsExactly("2026-10", "2026-11", "2026-12", "2027-01");
    JsonNode contas = c.get("/contas-receber?limit=100").data().get("items");
    assertThat(contas).hasSize(4);
    assertThat(StreamSupport.stream(contas.spliterator(), false)
        .allMatch(x -> !x.get("pix_txid").isNull() && x.get("valor").decimalValue().compareTo(new java.math.BigDecimal("199.9")) == 0)).isTrue();

    JsonNode det = c.get("/recorrencias/" + id).data();
    assertThat(det.get("execucoes")).hasSize(4);
    assertThat(StreamSupport.stream(det.get("execucoes").spliterator(), false).allMatch(e -> e.get("origem").asText().equals("agendador"))).isTrue();
    assertThat(det.get("proxima_competencia").asText()).isEqualTo("2027-02");
  }

  @Test
  void execucoesConcorrentesDuasInstanciasNaoGeramCobrancaEmDobro() throws Exception {
    UUID t = uuid();
    var c = cliente(t);
    for (int i = 0; i < 20; i++) c.post("/recorrencias", recorrencia()).esperar(201);
    relogio.fixar("2026-09-30");
    Callable<ResultadoProcessamento> instancia = () -> recorrencias.processarPendentes(t);
    var pool = Executors.newFixedThreadPool(3);
    try {
      int total = 0;
      for (var f : pool.invokeAll(List.of(instancia, instancia, instancia))) total += f.get().cobrancasGeradas();
      assertThat(total).isEqualTo(20);
    } finally {
      pool.shutdown();
    }
    assertThat(jdbc.queryForObject("SELECT count(*) FROM financeiro.cobrancas WHERE tenant_id = ?::uuid", Long.class, t.toString()))
        .isEqualTo(20);
  }

  @Test
  void respeitaDataFimEncerrandoEAprovacaoManualFicandoEmRascunho() {
    UUID t = uuid();
    var c = cliente(t);
    String id = c.post("/recorrencias", recorrencia("data_fim", "2026-11-30", "aprovacao_automatica", false)).esperar(201).data().get("id").asText();
    relogio.fixar("2027-03-01");
    var res = rodar(t);
    assertThat(res.cobrancasGeradas()).isEqualTo(2);
    assertThat(res.cobrancasAprovadas()).isZero();
    assertThat(res.encerradas()).isEqualTo(1);
    assertThat(c.get("/recorrencias/" + id).data().get("ativa").asBoolean()).isFalse();
    JsonNode cobs = c.get("/cobrancas").data().get("items");
    assertThat(StreamSupport.stream(cobs.spliterator(), false).allMatch(x -> x.get("status").asText().equals("rascunho"))).isTrue();
  }

  @Test
  void pausarImpedeGeracaoPrevisaoListaProximosCiclosEGeracaoManual() {
    UUID t = uuid();
    var c = cliente(t);
    String id = c.post("/recorrencias", recorrencia("periodicidade", "trimestral", "dia_vencimento", 31)).esperar(201).data().get("id").asText();
    JsonNode prev = c.get("/recorrencias/" + id + "/previsao?ciclos=3").esperar(200).data();
    assertThat(StreamSupport.stream(prev.spliterator(), false).map(p -> p.get("data_vencimento").asText()))
        .containsExactly("2026-10-31", "2027-01-31", "2027-04-30");

    c.post("/recorrencias/" + id + "/pausar").esperar(200);
    relogio.fixar("2026-12-01");
    assertThat(rodar(t).cobrancasGeradas()).isZero();
    c.post("/recorrencias/" + id + "/gerar").esperar(409);
    c.post("/recorrencias/" + id + "/reativar").esperar(200);
    JsonNode g = c.post("/recorrencias/" + id + "/gerar").esperar(201).data();
    assertThat(g.get("competencia").asText()).isEqualTo("2026-10");
  }
}
