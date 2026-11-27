package br.com.erp.financeiro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.erp.financeiro.conciliacao.LeitorOfx;
import com.fasterxml.jackson.databind.JsonNode;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

/** §32 conciliação (OFX, automática e manual) e §23/§74 fluxo de caixa e DRE gerencial. */
class ConciliacaoEGerencialTest extends TesteBase {

  static final String OFX = """
      OFXHEADER:100
      DATA:OFXSGML
      VERSION:102
      CHARSET:1252

      <OFX><BANKMSGSRSV1><STMTTRNRS><STMTRS><CURDEF>BRL
      <BANKACCTFROM><BANKID>341<ACCTID>12345-6</BANKACCTFROM>
      <BANKTRANLIST><DTSTART>20261101<DTEND>20261113
      <STMTTRN><TRNTYPE>CREDIT<DTPOSTED>20261111120000[-3:BRT]<TRNAMT>500,00<FITID>A1<MEMO>PIX RECEBIDO CLIENTE A
      <STMTTRN><TRNTYPE>DEBIT<DTPOSTED>20261112<TRNAMT>-200.00<FITID>B1<MEMO>PAGTO FORNECEDOR
      <STMTTRN><TRNTYPE>DEBIT<DTPOSTED>20261112<TRNAMT>-12.90<FITID>C1<MEMO>TARIFA PACOTE SERVICOS
      <STMTTRN><TRNTYPE>CREDIT<DTPOSTED>20261113<TRNAMT>300.00<FITID>D1<MEMO>PIX RECEBIDO CLIENTE B
      </BANKTRANLIST><LEDGERBAL><BALAMT>1587.10<DTASOF>20261113</LEDGERBAL></STMTRS></STMTTRNRS></BANKMSGSRSV1></OFX>
      """;

  @Test
  void leitorOfxAceitaSgmlComVirgulaEFuso() {
    LeitorOfx.Extrato e = LeitorOfx.ler(OFX.getBytes(StandardCharsets.ISO_8859_1));
    assertThat(e.lancamentos()).hasSize(4);
    assertThat(e.lancamentos().get(0).valor()).isEqualByComparingTo("500.00");
    assertThat(e.lancamentos().get(0).data()).isEqualTo(LocalDate.of(2026, 11, 11));
    assertThat(e.saldoFinal()).isEqualByComparingTo("1587.10");
    assertThat(e.inicio()).isEqualTo(LocalDate.of(2026, 11, 1));
  }

  /**
   * DADO QUE há um recebimento de R$ 500 e uma conta paga de R$ 200 no sistema
   * QUANDO importo o OFX do banco
   * ENTÃO os dois conciliam sozinhos, a tarifa fica pendente sugerida como TARIFA e o mesmo arquivo não entra de novo;
   * a cobrança de R$ 300 é baixada a partir do extrato e o saldo do sistema bate com o do banco.
   */
  @Test
  void importaConciliaEFechaOSaldo() throws Exception {
    relogio.fixar(LocalDate.of(2026, 11, 10));
    String conta = JsonPath.read(mvc.perform(post("/api/financeiro/cadastros/contas-bancarias").with(tudo())
            .contentType(MediaType.APPLICATION_JSON).content("""
                {"nome":"Itaú PJ","bancoCodigo":"341","tipo":"CORRENTE","saldoInicial":1000.00,"dataSaldoInicial":"2026-11-01"}"""))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.data.id");
    String c500 = criarCobranca(UUID.randomUUID(), "500.00", LocalDate.of(2026, 11, 15));
    mvc.perform(post("/api/financeiro/cobrancas/" + c500 + "/baixas").with(tudo()).contentType(MediaType.APPLICATION_JSON)
        .content("{\"valor\":500.00}")).andExpect(status().isCreated());
    String c300 = criarCobranca(UUID.randomUUID(), "300.00", LocalDate.of(2026, 11, 20));
    relogio.fixar(LocalDate.of(2026, 11, 12));
    String cp = JsonPath.read(mvc.perform(post("/api/financeiro/contas-pagar").with(tudo()).contentType(MediaType.APPLICATION_JSON).content("""
            {"fornecedorNome":"Distribuidor","competencia":"2026-11-01","vencimento":"2026-11-12","valor":200.00}"""))
        .andReturn().getResponse().getContentAsString(), "$.data.id");
    mvc.perform(patch("/api/financeiro/contas-pagar/" + cp + "/aprovar").with(tudo())).andExpect(status().isOk());
    mvc.perform(post("/api/financeiro/contas-pagar/" + cp + "/pagamento").with(tudo())).andExpect(status().isOk());

    relogio.fixar(LocalDate.of(2026, 11, 13));
    MockMultipartFile arquivo = new MockMultipartFile("arquivo", "extrato.ofx", "application/x-ofx", OFX.getBytes(StandardCharsets.ISO_8859_1));
    mvc.perform(multipart("/api/financeiro/conciliacao/extratos").file(arquivo).param("contaBancariaId", conta).with(tudo()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.novos").value(4))
        .andExpect(jsonPath("$.data.conciliadosAutomaticamente").value(2))
        .andExpect(jsonPath("$.data.pendentes").value(2));
    mvc.perform(multipart("/api/financeiro/conciliacao/extratos").file(arquivo).param("contaBancariaId", conta).with(tudo()))
        .andExpect(status().isConflict());

    JsonNode pendentes = ler(mvc.perform(get("/api/financeiro/conciliacao/transacoes?status=PENDENTE&contaBancariaId=" + conta).with(tudo()))
        .andReturn().getResponse().getContentAsString()).at("/data/itens");
    assertThat(pendentes.size()).isEqualTo(2);
    String tarifa = null;
    String pix300 = null;
    for (JsonNode t : pendentes) {
      if (t.path("valor").decimalValue().compareTo(new BigDecimal("-12.90")) == 0) {
        tarifa = t.path("id").asText();
        assertThat(t.path("classificacao").asText()).isEqualTo("TARIFA");
      } else {
        pix300 = t.path("id").asText();
      }
    }
    mvc.perform(get("/api/financeiro/conciliacao/transacoes/" + pix300 + "/sugestoes").with(tudo()))
        .andExpect(jsonPath("$.data.candidatos[0].tipo").value("COBRANCA_EM_ABERTO"));
    mvc.perform(post("/api/financeiro/conciliacao/transacoes/" + tarifa + "/conciliar").with(tudo()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"classificacao\":\"TARIFA\"}"))
        .andExpect(jsonPath("$.data.status").value("CONCILIADA"));
    mvc.perform(post("/api/financeiro/conciliacao/transacoes/" + pix300 + "/conciliar").with(tudo()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"cobrancaId\":\"%s\"}".formatted(c300)))
        .andExpect(jsonPath("$.data.status").value("CONCILIADA"));
    mvc.perform(get("/api/financeiro/cobrancas/" + c300).with(tudo())).andExpect(jsonPath("$.data.status").value("PAGO"));

    mvc.perform(get("/api/financeiro/conciliacao/resumo").with(tudo()))
        .andExpect(jsonPath("$.data[0].pendentes").value(0))
        .andExpect(jsonPath("$.data[0].saldoPeloExtrato").value(1587.10))
        .andExpect(jsonPath("$.data[0].saldoPeloSistema").value(1587.10))
        .andExpect(jsonPath("$.data[0].diferenca").value(0.00));

    // §23 fluxo de caixa: entradas 800, saídas 200 + tarifa 12,90
    mvc.perform(get("/api/financeiro/fluxo-caixa?de=2026-11-01&ate=2026-11-30&agrupamento=MES").with(tudo()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.saldoInicial").value(1000.00))
        .andExpect(jsonPath("$.data.periodos[0].entradasRealizadas").value(800.00))
        .andExpect(jsonPath("$.data.periodos[0].saidasRealizadas").value(212.90));

    // §23 DRE gerencial (competência): receita 800, despesas 200 e tarifa 12,90
    String dre = mvc.perform(get("/api/financeiro/dre?de=2026-11&ate=2026-11").with(tudo()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.receitaLiquida").value(800.00))
        .andExpect(jsonPath("$.data.resultadoLiquido").value(587.10))
        .andReturn().getResponse().getContentAsString();
    assertThat(dre).contains("Tarifas bancárias");

    // home do financeiro com os indicadores novos (§74, §111)
    mvc.perform(get("/api/financeiro/dashboard").with(tudo()))
        .andExpect(jsonPath("$.data.receitaMes").value(800.00))
        .andExpect(jsonPath("$.data.despesasMes").value(200.00))
        .andExpect(jsonPath("$.data.conciliacoesPendentes").value(0))
        .andExpect(jsonPath("$.data.saldoBancario").value(1587.10));
  }

  @Test
  void relatoriosNovosExportamComPermissao() throws Exception {
    for (String r : new String[] {"contas-pagar", "inadimplencia", "mrr", "fluxo-caixa", "dre", "comissoes", "conciliacao", "notas-fiscais"}) {
      mvc.perform(get("/api/financeiro/relatorios/" + r + "/exportar?formato=csv").with(tudo())).andExpect(status().isOk());
    }
    mvc.perform(get("/api/financeiro/relatorios/dre/exportar?formato=pdf").with(usuario(tenant, "financeiro.relatorio.exportar")))
        .andExpect(status().isForbidden());
  }
}
