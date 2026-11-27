package br.com.erp.financeiro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** §26, §41, §69, §27: consumo e excedentes, adicionais, reajuste e cartão recorrente. */
class RecorrenciaV2Test extends TesteBase {

  static final String LICENCA = RecorrenciaTest.SERVICO_LICENCA;
  static final String BACKUP = RecorrenciaTest.SERVICO_BACKUP;

  /**
   * DADO QUE a regra tem 1.000 GB de backup contratados (franquia) a R$ 0,50/GB excedente
   * QUANDO o consumo do mês é 1.400 GB e a cobrança é gerada
   * ENTÃO a cobrança traz o fixo + 400 GB × 0,50, a composição mostra o excedente e o MRR ignora a parte variável.
   */
  @Test
  void consumoAcimaDaFranquiaEntraNaCobrancaENaoNoMrr() throws Exception {
    relogio.fixar(LocalDate.of(2026, 12, 1));
    UUID empresa = UUID.randomUUID();
    String regra = criar(empresa);

    mvc.perform(put("/api/financeiro/recorrencias/" + regra + "/consumos").with(tudo()).contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"servicoId":"%s","competencia":"2026-12-01","quantidade":1400}""".formatted(BACKUP)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.excedente").value(400))
        .andExpect(jsonPath("$.data.valorExcedente").value(200.00))
        .andExpect(jsonPath("$.data.percentualUso").value(140.00));
    // alerta ao vendedor e evento para o CRM abrir a oportunidade de upgrade (§41, §105)
    assertThat(eventos("financeiro.consumo.excedido")).isEqualTo(1);

    String c = mvc.perform(post("/api/financeiro/recorrencias/" + regra + "/gerar-cobranca").with(tudo()))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.valor").value(1600.00)) // 20 × 45 + 1000 × 0,50 + 400 × 0,50
        .andExpect(jsonPath("$.data.valorVariavel").value(200.00))
        .andReturn().getResponse().getContentAsString();
    String cobranca = JsonPath.read(c, "$.data.id");
    JsonNode itens = ler(mvc.perform(get("/api/financeiro/cobrancas/" + cobranca + "/itens").with(tudo()))
        .andReturn().getResponse().getContentAsString()).path("data");
    assertThat(itens.size()).isEqualTo(3);
    assertThat(itens.toString()).contains("EXCEDENTE").contains("400");

    mvc.perform(get("/api/financeiro/mrr?periodo=2026-12").with(tudo()))
        .andExpect(jsonPath("$.data.mrrTotal").value(1400.00));

    // consumo já faturado não muda
    mvc.perform(put("/api/financeiro/recorrencias/" + regra + "/consumos").with(tudo()).contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"servicoId":"%s","competencia":"2026-12-01","quantidade":900}""".formatted(BACKUP)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errors[0].codigo").value("CONSUMO_FATURADO"));
  }

  @Test
  void adicionalEntraNaProximaCobrancaUmaVezSo() throws Exception {
    relogio.fixar(LocalDate.of(2026, 12, 1));
    String regra = criar(UUID.randomUUID());
    mvc.perform(post("/api/financeiro/recorrencias/" + regra + "/adicionais").with(tudo()).contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"competencia":"2026-12-01","descricao":"Visita técnica","valor":350.00}"""))
        .andExpect(status().isOk());
    mvc.perform(post("/api/financeiro/recorrencias/" + regra + "/gerar-cobranca").with(tudo()))
        .andExpect(jsonPath("$.data.valor").value(1750.00));
    relogio.fixar(LocalDate.of(2027, 1, 5));
    mvc.perform(post("/api/financeiro/recorrencias/" + regra + "/gerar-cobranca").with(tudo()))
        .andExpect(jsonPath("$.data.valor").value(1400.00));
  }

  /**
   * DADO QUE a regra vale R$ 1.400,00
   * QUANDO aplico IPCA de 10% a partir da próxima geração
   * ENTÃO o histórico guarda anterior, índice e novo valor, e só as próximas cobranças mudam.
   */
  @Test
  void reajusteRegistraAnteriorIndiceENovoValor() throws Exception {
    relogio.fixar(LocalDate.of(2026, 12, 1));
    String regra = criar(UUID.randomUUID());
    mvc.perform(post("/api/financeiro/recorrencias/" + regra + "/gerar-cobranca").with(tudo()))
        .andExpect(jsonPath("$.data.valor").value(1400.00));
    mvc.perform(post("/api/financeiro/recorrencias/" + regra + "/reajustes").with(tudo()).contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"indice":"IPCA","percentual":10,"observacao":"IPCA 12 meses"}"""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.valorAnterior").value(1400.00))
        .andExpect(jsonPath("$.data.valorNovo").value(1540.00))
        .andExpect(jsonPath("$.data.competenciaAplicacao").value("2027-01-01"));
    mvc.perform(get("/api/financeiro/recorrencias/" + regra).with(tudo()))
        .andExpect(jsonPath("$.data.valorProximoCiclo").value(1540.00));
    // competência já gerada não aceita reajuste
    mvc.perform(post("/api/financeiro/recorrencias/" + regra + "/reajustes").with(tudo()).contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"indice":"IGPM","percentual":3,"competenciaAplicacao":"2026-12-01"}"""))
        .andExpect(status().isUnprocessableEntity());
    assertThat(eventos("financeiro.reajuste.aplicado")).isEqualTo(1);
  }

  /** §27: com cartão recorrente, a cobrança gerada fica AGENDADO e o pedido ao provedor sai sozinho. */
  @Test
  void cartaoRecorrenteAgendaECobraNoProvedor() throws Exception {
    relogio.fixar(LocalDate.of(2026, 12, 1));
    String regra = criar(UUID.randomUUID());
    mvc.perform(post("/api/financeiro/recorrencias/" + regra + "/cartao/teste").with(tudo()))
        .andExpect(jsonPath("$.data.cobrancaAutomatica").value(true))
        .andExpect(jsonPath("$.data.cartaoFinal").value("4242"));
    String c = mvc.perform(post("/api/financeiro/recorrencias/" + regra + "/gerar-cobranca").with(tudo()))
        .andExpect(jsonPath("$.data.status").value("AGENDADO"))
        .andReturn().getResponse().getContentAsString();
    String cobranca = JsonPath.read(c, "$.data.id");
    mvc.perform(get("/api/financeiro/cobrancas/" + cobranca + "/pagamentos").with(tudo()))
        .andExpect(jsonPath("$.data[0].meio").value("CARTAO"))
        .andExpect(jsonPath("$.data[0].status").value("PENDENTE"));
  }

  /** "Técnico não visualiza margem financeira" (§3): sem financeiro.margem.ver o custo não sai. */
  @Test
  void custoDoItemSoComPermissaoDeMargem() throws Exception {
    String regra = criar(UUID.randomUUID());
    JsonNode licenca = itemLicenca(mvc.perform(get("/api/financeiro/recorrencias/" + regra).with(tudo()))
        .andReturn().getResponse().getContentAsString());
    assertThat(licenca.path("custoUnitario").decimalValue()).isEqualByComparingTo("30.00");
    assertThat(licenca.path("margemPercentual").decimalValue()).isEqualByComparingTo("33.33");
    JsonNode semMargem = itemLicenca(mvc.perform(get("/api/financeiro/recorrencias/" + regra)
        .with(usuario(tenant, "financeiro.recorrencia.ver"))).andReturn().getResponse().getContentAsString());
    assertThat(semMargem.path("custoUnitario").isNull()).isTrue();
    assertThat(semMargem.path("margemPercentual").isNull()).isTrue();
  }

  JsonNode itemLicenca(String corpo) throws Exception {
    for (JsonNode i : ler(corpo).at("/data/itens")) if (i.path("servicoId").asText().equals(LICENCA)) return i;
    throw new AssertionError("item de licença ausente");
  }

  String criar(UUID empresa) throws Exception {
    String r = mvc.perform(post("/api/financeiro/recorrencias").with(tudo()).contentType(MediaType.APPLICATION_JSON).content("""
            {"clienteId":"%s","descricao":"Plano gerenciado","diaGeracao":5,"diasParaVencimento":10,"formaPagamento":"PIX",
             "vendedorId":"%s","itens":[
               {"servicoId":"%s","descricao":"Licenças","quantidade":20,"unidade":"UN","valorUnitario":45.00,"custoUnitario":30.00,"fabricante":"Microsoft"},
               {"servicoId":"%s","descricao":"Backup","quantidade":1000,"unidade":"GB","valorUnitario":0.50,"franquia":1000,"valorExcedente":0.50}]}"""
            .formatted(empresa, UUID.randomUUID(), LICENCA, BACKUP)))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    return JsonPath.read(r, "$.data.id");
  }
}
