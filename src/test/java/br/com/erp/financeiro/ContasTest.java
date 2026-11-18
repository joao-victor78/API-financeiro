package br.com.erp.financeiro;

import static br.com.erp.financeiro.ApiEAutenticacaoTest.novaConta;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

/** Contas a receber e a pagar: ciclo de vida, baixa parcial/total, estorno, exclusão lógica e filtros. */
class ContasTest extends TesteBase {

  private final UUID tenant = uuid();

  @Test
  void cicloCompletoCriaAtualizaBaixaParcialBaixaTotalEBloqueiaExclusao() {
    var a = cliente(tenant);
    JsonNode c = a.post("/contas-receber", novaConta("valor", 300, "desconto", 0)).esperar(201).data();
    assertThat(c.get("status").asText()).isEqualTo("em_aberto");
    assertThat(c.get("saldo").decimalValue()).isEqualByComparingTo("300");
    String id = c.get("id").asText();

    a.patch("/contas-receber/" + id, Map.of("status", "agendada")).esperar(200);

    JsonNode p1 = a.post("/contas-receber/" + id + "/baixa", Map.of("valor", 100, "data_pagamento", "2026-10-05", "forma_pagamento", "pix"))
        .esperar(200).data();
    assertThat(p1.get("status").asText()).isEqualTo("parcialmente_paga");
    assertThat(p1.get("valor_pago").decimalValue()).isEqualByComparingTo("100");
    assertThat(p1.get("saldo").decimalValue()).isEqualByComparingTo("200");

    a.post("/contas-receber/" + id + "/baixa", Map.of("valor", 250, "data_pagamento", "2026-10-05", "forma_pagamento", "pix")).esperar(422);

    JsonNode p2 = a.post("/contas-receber/" + id + "/baixa", Map.of("valor", 200, "data_pagamento", "2026-10-06", "forma_pagamento", "pix"))
        .esperar(200).data();
    assertThat(p2.get("status").asText()).isEqualTo("paga");
    assertThat(p2.get("valor_pago").decimalValue()).isEqualByComparingTo("300");
    assertThat(p2.get("saldo").decimalValue()).isEqualByComparingTo("0");

    a.patch("/contas-receber/" + id, Map.of("valor", 1)).esperar(409);
    a.del("/contas-receber/" + id).esperar(409);
    assertThat(a.post("/contas-receber/" + id + "/estorno").esperar(200).data().get("status").asText()).isEqualTo("estornada");
  }

  @Test
  void exclusaoELogicaComAuditoria() {
    var a = cliente(tenant);
    String id = a.post("/contas-receber", novaConta()).esperar(201).data().get("id").asText();
    a.del("/contas-receber/" + id).esperar(204);
    a.get("/contas-receber/" + id).esperar(404);
    Map<String, Object> linha = jdbc.queryForMap(
        "SELECT deleted_at, deleted_by, created_by FROM financeiro.contas_receber WHERE id = ?::uuid", id);
    assertThat(linha.get("deleted_at")).isNotNull();
    assertThat(linha.get("deleted_by")).isNotNull();
    assertThat(linha.get("created_by")).isNotNull();
  }

  @Test
  void filtrosEPaginacao() {
    var a = cliente(tenant);
    UUID cli = uuid();
    for (int i = 1; i <= 3; i++) {
      a.post("/contas-receber", novaConta("cliente_id", cli, "data_vencimento", "2026-11-0" + i, "competencia", "2026-11")).esperar(201);
    }
    JsonNode r = a.get("/contas-receber?cliente_id=" + cli + "&limit=2&page=2").esperar(200).data();
    assertThat(r.get("meta").toString()).isEqualTo("{\"page\":2,\"limit\":2,\"total\":3,\"total_pages\":2}");
    assertThat(r.get("items")).hasSize(1);
    assertThat(r.at("/items/0/data_vencimento").asText()).isEqualTo("2026-11-03");
    a.get("/contas-receber?status=inexistente").esperar(422);
  }

  @Test
  void contaAPagarCriaPagaEPublicaEvento() {
    var a = cliente(tenant);
    JsonNode c = a.post("/contas-pagar", Map.of("fornecedor_id", uuid(), "centro_custo_id", uuid(), "categoria_id", uuid(),
        "descricao", "Hospedagem", "competencia", "2026-10", "data_vencimento", "2026-10-15", "valor", 850)).esperar(201).data();
    String id = c.get("id").asText();
    JsonNode p = a.post("/contas-pagar/" + id + "/pagamento", Map.of("valor", 850, "data_pagamento", "2026-10-14", "forma_pagamento", "transferencia"))
        .esperar(200).data();
    assertThat(p.get("status").asText()).isEqualTo("paga");
    JsonNode ev = a.get("/eventos?tipo=conta_pagar.paga").esperar(200).data().get("items");
    assertThat(StreamSupport.stream(ev.spliterator(), false).anyMatch(e -> e.get("agregado_id").asText().equals(id))).isTrue();
  }
}
