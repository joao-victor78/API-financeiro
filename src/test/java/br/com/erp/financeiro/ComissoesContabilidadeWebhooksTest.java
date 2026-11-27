package br.com.erp.financeiro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.erp.financeiro.ferramentas.EmitirToken;
import br.com.erp.financeiro.webhooks.saida.EntregadorWebhooks;
import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** §71 comissões, §33–§37 notas e fechamento contábil, §91 webhooks de saída. */
class ComissoesContabilidadeWebhooksTest extends TesteBase {

  @Autowired
  EntregadorWebhooks entregador;

  RequestPostProcessor perfil(String nome, UUID sub) {
    return usuarioComId(tenant, sub, EmitirToken.PERFIS.get(nome).toArray(String[]::new));
  }

  /**
   * DADO QUE a regra paga 10% recorrente ao vendedor
   * QUANDO a mensalidade de R$ 1.400 é paga
   * ENTÃO nasce uma comissão de R$ 140 que só o próprio vendedor (e o financeiro) vê; o estorno a cancela.
   */
  @Test
  void comissaoRecorrenteApuradaNoPagamentoECanceladaNoEstorno() throws Exception {
    relogio.fixar(LocalDate.of(2026, 11, 3));
    UUID vendedor = UUID.randomUUID();
    mvc.perform(post("/api/financeiro/comissoes/regras").with(tudo()).contentType(MediaType.APPLICATION_JSON).content("""
            {"nome":"Vendedor 10% recorrente","beneficiarioTipo":"VENDEDOR","base":"RECORRENTE","valor":10,"mesesRecorrencia":12}"""))
        .andExpect(status().isOk());
    String regra = JsonPath.read(mvc.perform(post("/api/financeiro/recorrencias").with(tudo()).contentType(MediaType.APPLICATION_JSON).content("""
            {"clienteId":"%s","diaGeracao":5,"vendedorId":"%s","itens":[
              {"servicoId":"%s","descricao":"Gerenciado","quantidade":1,"unidade":"UN","valorUnitario":1400.00}]}"""
            .formatted(UUID.randomUUID(), vendedor, RecorrenciaTest.SERVICO_LICENCA)))
        .andReturn().getResponse().getContentAsString(), "$.data.id");
    String cobranca = JsonPath.read(mvc.perform(post("/api/financeiro/recorrencias/" + regra + "/gerar-cobranca").with(tudo()))
        .andReturn().getResponse().getContentAsString(), "$.data.id");
    String pagamento = JsonPath.read(mvc.perform(post("/api/financeiro/cobrancas/" + cobranca + "/baixas").with(tudo())
            .contentType(MediaType.APPLICATION_JSON).content("{\"valor\":1400.00}"))
        .andReturn().getResponse().getContentAsString(), "$.data.pagamentoId");

    mvc.perform(get("/api/financeiro/comissoes").with(perfil("VENDEDOR", vendedor)))
        .andExpect(jsonPath("$.data.total").value(1))
        .andExpect(jsonPath("$.data.itens[0].valor").value(140.00))
        .andExpect(jsonPath("$.data.itens[0].status").value("A_PAGAR"));
    mvc.perform(get("/api/financeiro/comissoes").with(perfil("VENDEDOR", UUID.randomUUID())))
        .andExpect(jsonPath("$.data.total").value(0));

    mvc.perform(post("/api/financeiro/pagamentos/" + pagamento + "/estorno").with(tudo()).contentType(MediaType.APPLICATION_JSON)
        .content("{\"motivo\":\"Cancelado pelo cliente\"}")).andExpect(status().isOk());
    mvc.perform(get("/api/financeiro/comissoes").with(tudo()))
        .andExpect(jsonPath("$.data.itens[0].status").value("CANCELADA"));
  }

  @Test
  void fechamentoDeComissoesLancaContaAPagar() throws Exception {
    relogio.fixar(LocalDate.of(2026, 11, 3));
    UUID vendedor = UUID.randomUUID();
    mvc.perform(post("/api/financeiro/comissoes/regras").with(tudo()).contentType(MediaType.APPLICATION_JSON).content("""
        {"nome":"Implantação 5%","beneficiarioTipo":"VENDEDOR","base":"PERCENTUAL","valor":5}"""));
    String c = JsonPath.read(mvc.perform(post("/api/financeiro/cobrancas").with(tudo()).contentType(MediaType.APPLICATION_JSON).content("""
            {"clienteId":"%s","competencia":"2026-11-01","vencimento":"2026-11-20","valor":2000.00,"vendedorId":"%s"}"""
            .formatted(UUID.randomUUID(), vendedor))).andReturn().getResponse().getContentAsString(), "$.data.id");
    mvc.perform(post("/api/financeiro/cobrancas/" + c + "/baixas").with(tudo()).contentType(MediaType.APPLICATION_JSON).content("{\"valor\":2000.00}"));
    mvc.perform(post("/api/financeiro/comissoes/fechamento?competencia=2026-11-01").with(tudo()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(100.00))
        .andExpect(jsonPath("$.data.beneficiarios").value(1));
    mvc.perform(get("/api/financeiro/contas-pagar?status=PENDENTE").with(tudo()))
        .andExpect(jsonPath("$.data.itens[0].valor").value(100.00));
  }

  /**
   * DADO QUE o mês de novembro foi conferido
   * QUANDO o financeiro envia à contabilidade
   * ENTÃO o pacote é gerado, os lançamentos de novembro travam, só a contabilidade analisa/finaliza,
   * e a devolução com pendência destrava o mês.
   */
  @Test
  void fluxoDoFechamentoContabil() throws Exception {
    relogio.fixar(LocalDate.of(2026, 12, 2));
    UUID fin = UUID.randomUUID();
    String c = criarCobranca(UUID.randomUUID(), "900.00", LocalDate.of(2026, 11, 25));
    mvc.perform(post("/api/financeiro/notas-fiscais").with(tudo()).contentType(MediaType.APPLICATION_JSON).content("""
            {"tipo":"EMITIDA","numero":"1001","dataEmissao":"2026-11-25","valor":900.00,"valorImpostos":45.00,"cobrancaId":"%s"}"""
            .formatted(c)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.competencia").value("2026-11-01"));
    mvc.perform(post("/api/financeiro/notas-fiscais").with(tudo()).contentType(MediaType.APPLICATION_JSON).content("""
            {"tipo":"EMITIDA","numero":"1001","dataEmissao":"2026-11-25","valor":900.00,"cobrancaId":"%s"}""".formatted(c)))
        .andExpect(status().isConflict());

    mvc.perform(post("/api/financeiro/contabilidade/fechamentos/2026-11/iniciar").with(perfil("FINANCEIRO", fin)))
        .andExpect(jsonPath("$.data.status").value("EM_CONFERENCIA"))
        .andExpect(jsonPath("$.data.diagnostico.notasEmitidas").value(1));
    mvc.perform(patch("/api/financeiro/contabilidade/fechamentos/2026-11/status").with(perfil("FINANCEIRO", fin))
            .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"PRONTO\"}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.errors[0].codigo").value("CHECKLIST_INCOMPLETO"));
    for (String item : List.of("RECEITAS_CONFERIDAS", "DESPESAS_CONFERIDAS", "EXTRATOS_CONFERIDOS", "NOTAS_EMITIDAS", "NOTAS_RECEBIDAS",
        "COMPROVANTES", "COMISSOES", "IMPOSTOS", "LANCAMENTOS_CLASSIFICADOS", "DOCUMENTACAO_COMPLETA")) {
      mvc.perform(put("/api/financeiro/contabilidade/fechamentos/2026-11/itens/" + item).with(perfil("FINANCEIRO", fin))
          .contentType(MediaType.APPLICATION_JSON).content("{\"concluido\":true}")).andExpect(status().isOk());
    }
    mvc.perform(patch("/api/financeiro/contabilidade/fechamentos/2026-11/status").with(perfil("FINANCEIRO", fin))
        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"PRONTO\"}")).andExpect(jsonPath("$.data.status").value("PRONTO"));
    mvc.perform(patch("/api/financeiro/contabilidade/fechamentos/2026-11/status").with(perfil("FINANCEIRO", fin))
            .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ENVIADO_PARA_CONTABILIDADE\"}"))
        .andExpect(jsonPath("$.data.status").value("ENVIADO_PARA_CONTABILIDADE"))
        .andExpect(jsonPath("$.data.pacoteDocumentoId").isNotEmpty());
    assertThat(eventos("financeiro.fechamento.enviado")).isEqualTo(1);

    // trava: novembro não aceita lançamento novo
    mvc.perform(post("/api/financeiro/cobrancas").with(tudo()).contentType(MediaType.APPLICATION_JSON).content("""
            {"clienteId":"%s","competencia":"2026-11-01","vencimento":"2026-12-20","valor":10.00}""".formatted(UUID.randomUUID())))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.errors[0].codigo").value("PERIODO_FECHADO"));
    // o financeiro não analisa; a contabilidade sim
    mvc.perform(patch("/api/financeiro/contabilidade/fechamentos/2026-11/status").with(perfil("FINANCEIRO", fin))
        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"EM_ANALISE\"}")).andExpect(status().isForbidden());
    RequestPostProcessor contador = perfil("CONTABILIDADE", UUID.randomUUID());
    mvc.perform(get("/api/financeiro/contabilidade/portal?competencia=2026-11").with(contador))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totais.RECEITA").value(900.00))
        .andExpect(jsonPath("$.data.documentosFiscais.length()").value(1));
    byte[] zip = mvc.perform(get("/api/financeiro/contabilidade/exportar?competencia=2026-11&formato=zip").with(contador))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
    assertThat(zip[0]).isEqualTo((byte) 'P');
    String ofx = mvc.perform(get("/api/financeiro/contabilidade/exportar?competencia=2026-11&formato=ofx").with(contador))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    assertThat(ofx).contains("<OFX>");
    // a contabilidade não vê o que não é dela
    mvc.perform(get("/api/financeiro/recorrencias").with(contador)).andExpect(status().isForbidden());

    mvc.perform(patch("/api/financeiro/contabilidade/fechamentos/2026-11/status").with(contador)
        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"EM_ANALISE\"}")).andExpect(jsonPath("$.data.status").value("EM_ANALISE"));
    mvc.perform(patch("/api/financeiro/contabilidade/fechamentos/2026-11/status").with(contador)
        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"COM_PENDENCIA\"}")).andExpect(status().isBadRequest());
    mvc.perform(patch("/api/financeiro/contabilidade/fechamentos/2026-11/status").with(contador)
            .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"COM_PENDENCIA\",\"pendencias\":\"Falta a NF do fornecedor X\"}"))
        .andExpect(jsonPath("$.data.status").value("COM_PENDENCIA"));
    // devolvido: destrava
    mvc.perform(post("/api/financeiro/cobrancas").with(tudo()).contentType(MediaType.APPLICATION_JSON).content("""
            {"clienteId":"%s","competencia":"2026-11-01","vencimento":"2026-12-20","valor":10.00}""".formatted(UUID.randomUUID())))
        .andExpect(status().isCreated());
  }

  /**
   * DADO QUE há um webhook de saída para financeiro.cobranca.criada
   * QUANDO uma cobrança é lançada
   * ENTÃO o destino recebe o evento assinado com HMAC (t.corpo) e a entrega fica ENTREGUE.
   */
  @Test
  void webhookDeSaidaEntregaAssinado() throws Exception {
    List<String[]> recebidos = new CopyOnWriteArrayList<>();
    HttpServer servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    servidor.createContext("/hook", troca -> {
      String corpo = new String(troca.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      recebidos.add(new String[] {troca.getRequestHeaders().getFirst("X-Financeiro-Assinatura"), corpo,
          troca.getRequestHeaders().getFirst("X-Financeiro-Evento")});
      troca.sendResponseHeaders(204, -1);
      troca.close();
    });
    servidor.start();
    try {
      String r = mvc.perform(post("/api/financeiro/webhooks").with(tudo()).contentType(MediaType.APPLICATION_JSON).content("""
              {"descricao":"ERP do cliente","url":"http://127.0.0.1:%d/hook","eventos":["financeiro.cobranca.criada"]}"""
              .formatted(servidor.getAddress().getPort())))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.segredo").isNotEmpty())
          .andReturn().getResponse().getContentAsString();
      String segredo = JsonPath.read(r, "$.data.segredo");
      criarCobranca(UUID.randomUUID(), "99.90", hoje().plusDays(3));
      assertThat(entregador.entregarPendentes()).isGreaterThanOrEqualTo(1);
      String[] ultimo = recebidos.stream().filter(x -> x[1].contains(tenant.toString())).findFirst().orElseThrow();
      assertThat(ultimo[2]).isEqualTo("financeiro.cobranca.criada");
      String t = ultimo[0].split(",")[0].substring(2);
      assertThat(ultimo[0]).endsWith("v1=" + EntregadorWebhooks.hmac(segredo, t + "." + ultimo[1]));
      mvc.perform(get("/api/financeiro/webhooks").with(tudo()))
          .andExpect(jsonPath("$.data[0].entregues").value(1))
          .andExpect(jsonPath("$.data[0].segredo").doesNotExist());
    } finally {
      servidor.stop(0);
    }
  }

  @Test
  void webhookDeSaidaRecusaEventoDesconhecido() throws Exception {
    mvc.perform(post("/api/financeiro/webhooks").with(tudo()).contentType(MediaType.APPLICATION_JSON).content("""
            {"descricao":"x","url":"https://exemplo.com/hook","eventos":["qualquer.coisa"]}"""))
        .andExpect(status().isBadRequest());
  }
}
