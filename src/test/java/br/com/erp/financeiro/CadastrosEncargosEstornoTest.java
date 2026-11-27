package br.com.erp.financeiro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.erp.financeiro.cobrancas.EncargosService;
import br.com.erp.financeiro.regua.ReguaService;
import br.com.erp.financeiro.tenant.TenantContexto;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

/** §25 cadastros e documento, §24 juros/multa e AGENDADO, §28 estorno, §96 documentos. */
class CadastrosEncargosEstornoTest extends TesteBase {

  @Autowired
  EncargosService encargos;

  @Autowired
  ReguaService regua;

  @Test
  void contaPagarComCadastroPreencheOTextoEExigeCategoriaDeDespesa() throws Exception {
    String receita = categoria("Mensalidades", "RECEITA", "RECEITA_BRUTA");
    String despesa = categoria("Cloud", "DESPESA", "CUSTOS");
    String centro = JsonPath.read(mvc.perform(post("/api/financeiro/cadastros/centros-custo").with(tudo())
            .contentType(MediaType.APPLICATION_JSON).content("{\"codigo\":\"OPS\",\"nome\":\"Operações\"}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.data.id");

    mvc.perform(post("/api/financeiro/contas-pagar").with(tudo()).contentType(MediaType.APPLICATION_JSON).content(conta(despesa, centro)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.categoria").value("Cloud"))
        .andExpect(jsonPath("$.data.centroCusto").value("OPS — Operações"))
        .andExpect(jsonPath("$.data.categoriaId").value(despesa));
    mvc.perform(post("/api/financeiro/contas-pagar").with(tudo()).contentType(MediaType.APPLICATION_JSON).content(conta(receita, centro)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].codigo").value("CATEGORIA_TIPO"));
    // duplicada e grupo incompatível
    mvc.perform(post("/api/financeiro/cadastros/categorias").with(tudo()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"nome\":\"cloud\",\"tipo\":\"DESPESA\",\"grupoDre\":\"CUSTOS\"}"))
        .andExpect(status().isConflict());
    mvc.perform(post("/api/financeiro/cadastros/categorias").with(tudo()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"nome\":\"X\",\"tipo\":\"DESPESA\",\"grupoDre\":\"RECEITA_BRUTA\"}"))
        .andExpect(status().isBadRequest());
    // em uso: não exclui
    mvc.perform(delete("/api/financeiro/cadastros/categorias/" + despesa).with(tudo())).andExpect(status().isConflict());
    // sem permissão de administrar cadastro
    mvc.perform(post("/api/financeiro/cadastros/categorias").with(usuario(tenant, "financeiro.cadastro.ver"))
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isForbidden());
  }

  /** §96: PDF aceito e baixado igual; executável disfarçado recusado. */
  @Test
  void documentoValidaConteudoEBaixaOMesmoArquivo() throws Exception {
    byte[] pdf = "%PDF-1.4\nconteudo de teste\n%%EOF".getBytes(StandardCharsets.US_ASCII);
    String r = mvc.perform(multipart("/api/financeiro/documentos").file(new MockMultipartFile("arquivo", "nota 123.pdf", "application/pdf", pdf))
            .param("categoria", "NOTA_FISCAL").with(tudo()))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.nomeArquivo").value("nota_123.pdf"))
        .andReturn().getResponse().getContentAsString();
    String id = JsonPath.read(r, "$.data.id");
    byte[] baixado = mvc.perform(get("/api/financeiro/documentos/" + id + "/arquivo").with(tudo()))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
    assertThat(baixado).isEqualTo(pdf);
    mvc.perform(multipart("/api/financeiro/documentos").file(new MockMultipartFile("arquivo", "virus.pdf", "application/pdf",
            new byte[] {'M', 'Z', 0, 1})).with(tudo()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].codigo").value("CONTEUDO_INVALIDO"));
    mvc.perform(multipart("/api/financeiro/documentos").file(new MockMultipartFile("arquivo", "a.exe", "application/octet-stream",
            new byte[] {1, 2})).with(tudo()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].codigo").value("TIPO_NAO_ACEITO"));
  }

  /**
   * DADO QUE o tenant ligou juros de 1% ao mês e multa de 2%
   * QUANDO a cobrança de R$ 1.000,00 está 15 dias vencida
   * ENTÃO o job grava multa R$ 20,00 e juros R$ 5,00 — e cobrança com juros digitados à mão não muda.
   */
  @Test
  void encargosAutomaticosRespeitamOsDigitadosAMao() throws Exception {
    mvc.perform(put("/api/financeiro/configuracoes").with(tudo()).contentType(MediaType.APPLICATION_JSON).content("""
            {"encargosAutomaticos":true,"multaPercentual":2,"jurosMensalPercentual":1,"diasCarencia":0,
             "conciliacaoToleranciaDias":3,"alertaConsumoPercentual":100}"""))
        .andExpect(status().isOk());
    LocalDate venc = LocalDate.of(2026, 11, 1);
    relogio.fixar(venc.minusDays(1));
    String automatica = criarCobranca(UUID.randomUUID(), "1000.00", venc);
    String manual = JsonPath.read(mvc.perform(post("/api/financeiro/cobrancas").with(tudo()).contentType(MediaType.APPLICATION_JSON).content("""
            {"clienteId":"%s","competencia":"2026-11-01","vencimento":"2026-11-01","valor":1000.00,"juros":7.00}"""
            .formatted(UUID.randomUUID()))).andReturn().getResponse().getContentAsString(), "$.data.id");
    relogio.fixar(venc.plusDays(15));
    assertThat(TenantContexto.calcular(tenant, encargos::recalcular)).isEqualTo(1);
    mvc.perform(get("/api/financeiro/cobrancas/" + automatica).with(tudo()))
        .andExpect(jsonPath("$.data.multa").value(20.00))
        .andExpect(jsonPath("$.data.juros").value(5.00))
        .andExpect(jsonPath("$.data.saldo").value(1025.00));
    mvc.perform(get("/api/financeiro/cobrancas/" + manual).with(tudo()))
        .andExpect(jsonPath("$.data.juros").value(7.00))
        .andExpect(jsonPath("$.data.multa").value(0.00))
        .andExpect(jsonPath("$.data.encargosManuais").value(true));
  }

  /** §24 AGENDADO: a cobrança agendada só entra em atraso depois da data marcada. */
  @Test
  void agendamentoSeguraOAtrasoAteADataMarcada() throws Exception {
    LocalDate venc = LocalDate.of(2026, 11, 10);
    relogio.fixar(venc.minusDays(2));
    String c = criarCobranca(UUID.randomUUID(), "300.00", venc);
    mvc.perform(post("/api/financeiro/cobrancas/" + c + "/agendamento").with(tudo()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"data\":\"2026-11-20\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("AGENDADO"))
        .andExpect(jsonPath("$.data.agendadaPara").value("2026-11-20"));
    relogio.fixar(LocalDate.of(2026, 11, 15));
    TenantContexto.calcular(tenant, regua::executar);
    mvc.perform(get("/api/financeiro/cobrancas/" + c).with(tudo())).andExpect(jsonPath("$.data.status").value("AGENDADO"));
    relogio.fixar(LocalDate.of(2026, 11, 21));
    TenantContexto.calcular(tenant, regua::executar);
    mvc.perform(get("/api/financeiro/cobrancas/" + c).with(tudo())).andExpect(jsonPath("$.data.status").value("ATRASADO"));
  }

  /** §28 refund: estorno parcial devolve o saldo, exige motivo e não passa do valor pago. */
  @Test
  void estornoPedidoPeloUsuario() throws Exception {
    String c = criarCobranca(UUID.randomUUID(), "400.00", hoje().plusDays(5));
    String p = JsonPath.read(mvc.perform(post("/api/financeiro/cobrancas/" + c + "/baixas").with(tudo()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"valor\":400.00}")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.data.pagamentoId");
    mvc.perform(post("/api/financeiro/pagamentos/" + p + "/estorno").with(tudo()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"valor\":500.00,\"motivo\":\"teste\"}"))
        .andExpect(status().isUnprocessableEntity());
    mvc.perform(post("/api/financeiro/pagamentos/" + p + "/estorno").with(tudo()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"valor\":150.00,\"motivo\":\"Serviço não prestado\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("ESTORNADO"))
        .andExpect(jsonPath("$.data.valorEstornado").value(150.00));
    mvc.perform(get("/api/financeiro/cobrancas/" + c).with(tudo()))
        .andExpect(jsonPath("$.data.saldo").value(150.00))
        .andExpect(jsonPath("$.data.status").value("PARCIAL"));
    assertThat(eventos("financeiro.pagamento.falhou")).isEqualTo(1);
    mvc.perform(post("/api/financeiro/pagamentos/" + p + "/estorno").with(admin()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"motivo\":\"x\"}"))
        .andExpect(status().isForbidden());
  }

  String categoria(String nome, String tipo, String grupo) throws Exception {
    return JsonPath.read(mvc.perform(post("/api/financeiro/cadastros/categorias").with(tudo()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"nome\":\"%s\",\"tipo\":\"%s\",\"grupoDre\":\"%s\"}".formatted(nome, tipo, grupo)))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.data.id");
  }

  static String conta(String categoria, String centro) {
    return """
        {"fornecedorNome":"AWS","competencia":"2026-11-01","vencimento":"2026-11-20","valor":800.00,
         "categoriaId":"%s","centroCustoId":"%s"}""".formatted(categoria, centro);
  }
}
