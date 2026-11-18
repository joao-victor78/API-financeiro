package br.com.erp.financeiro;

import static br.com.erp.financeiro.ApiEAutenticacaoTest.novaConta;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.erp.financeiro.automacao.AgendadorService;
import br.com.erp.financeiro.automacao.AgendadorService.Job;
import br.com.erp.financeiro.inadimplencia.InadimplenciaDtos.ResultadoPromessas;
import br.com.erp.financeiro.inadimplencia.InadimplenciaDtos.ResultadoVencimentos;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class InadimplenciaTest extends TesteBase {

  @Autowired AgendadorService agendador;

  private Object rodar(Job job, UUID tenant) {
    return ((AgendadorService.ResultadoJob) agendador.executar(job, tenant)).resultado();
  }

  private static JsonNode achar(JsonNode lista, String campo, String valor) {
    return StreamSupport.stream(lista.spliterator(), false).filter(x -> x.get(campo).asText().equals(valor)).findFirst().orElseThrow();
  }

  @Test
  void jobMarcaVencidasCalculaEncargosAgingEPromessas() {
    UUID t = uuid();
    var c = cliente(t);
    UUID cli = uuid();
    relogio.fixar("2026-08-01");
    String a = c.post("/contas-receber", novaConta("cliente_id", cli, "valor", 1000, "data_vencimento", "2026-09-15", "competencia", "2026-09"))
        .esperar(201).data().get("id").asText();
    String b = c.post("/contas-receber", novaConta("valor", 500, "data_vencimento", "2026-08-10", "competencia", "2026-08"))
        .esperar(201).data().get("id").asText();
    c.post("/contas-receber", novaConta("valor", 300, "data_vencimento", "2026-11-10", "competencia", "2026-11")).esperar(201);

    relogio.fixar("2026-09-30");
    assertThat(((ResultadoVencimentos) rodar(Job.vencimentos, t)).contasReceberVencidas()).isEqualTo(2);
    assertThat(c.get("/contas-receber/" + a).data().get("status").asText()).isEqualTo("vencida");

    JsonNode lista = c.get("/inadimplencia").esperar(200).data().get("items");
    JsonNode la = achar(lista, "conta_receber_id", a);
    assertThat(la.get("dias_atraso").asInt()).isEqualTo(15);
    assertThat(la.get("multa").decimalValue()).isEqualByComparingTo("20");
    assertThat(la.get("juros").decimalValue()).isEqualByComparingTo("5");
    assertThat(la.get("valor_atualizado").decimalValue()).isEqualByComparingTo("1025");
    assertThat(la.get("faixa_aging").asText()).isEqualTo("1_30");
    JsonNode lb = achar(lista, "conta_receber_id", b);
    assertThat(lb.get("dias_atraso").asInt()).isEqualTo(51);
    assertThat(lb.get("faixa_aging").asText()).isEqualTo("31_60");

    JsonNode resumo = c.get("/inadimplencia/resumo").esperar(200).data();
    assertThat(resumo.get("total_em_aberto").decimalValue()).isEqualByComparingTo("1800");
    assertThat(resumo.get("total_vencido").decimalValue()).isEqualByComparingTo("1500");
    assertThat(resumo.get("clientes_inadimplentes").asInt()).isEqualTo(2);
    assertThat(resumo.get("taxa_inadimplencia_percentual").asDouble()).isEqualTo(83.33);
    assertThat(resumo.at("/aging/a_vencer/quantidade").asInt()).isEqualTo(1);
    assertThat(resumo.at("/aging/a_vencer/valor").decimalValue()).isEqualByComparingTo("300");

    assertThat(c.get("/eventos?tipo=conta_receber.vencida").data().at("/meta/total").asInt()).isEqualTo(2);

    // Promessa: cumprida quando paga, quebrada quando a data passa sem pagamento.
    String pa = c.post("/inadimplencia/promessas", Map.of("conta_receber_id", a, "data_prometida", "2026-10-05", "valor", 1000))
        .esperar(201).data().get("id").asText();
    String pb = c.post("/inadimplencia/promessas", Map.of("conta_receber_id", b, "data_prometida", "2026-10-05", "valor", 500))
        .esperar(201).data().get("id").asText();
    c.post("/inadimplencia/promessas", Map.of("conta_receber_id", b, "data_prometida", "2026-09-01", "valor", 1)).esperar(422);
    c.post("/contas-receber/" + a + "/baixa", Map.of("valor", 1000, "data_pagamento", "2026-10-03", "forma_pagamento", "pix")).esperar(200);
    relogio.fixar("2026-10-06");
    assertThat(((ResultadoPromessas) rodar(Job.promessas, t)).promessasQuebradas()).isEqualTo(1);
    JsonNode proms = c.get("/inadimplencia/promessas").data().get("items");
    assertThat(achar(proms, "id", pa).get("status").asText()).isEqualTo("cumprida");
    assertThat(achar(proms, "id", pb).get("status").asText()).isEqualTo("quebrada");
  }
}
