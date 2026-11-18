package br.com.erp.financeiro.ferramentas;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import br.com.erp.financeiro.TesteBase;
import br.com.erp.financeiro.automacao.AgendadorService;
import br.com.erp.financeiro.automacao.AgendadorService.Job;
import br.com.erp.financeiro.eventos.EventosService;
import br.com.erp.financeiro.inadimplencia.InadimplenciaDtos.ResultadoVencimentos;
import br.com.erp.financeiro.recorrencias.RecorrenciasDtos.ResultadoProcessamento;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * Demonstração narrada do fluxo M2M (para a apresentação do trabalho).
 * Mostra o módulo Financeiro operando como um "nó" autônomo: o tempo passa, ele cobra, o banco avisa,
 * ele baixa, avisa o módulo Core — e a única ação humana é configurar o contrato.
 *
 * <p>Uso: {@code mvn -q test -Dtest=Demonstracao} (usa o banco erp_test e o recria do zero)
 */
class Demonstracao extends TesteBase {

  private static final String TITULO = "\u001b[1;36m";
  private static final String OK = "\u001b[32m";
  private static final String AUTO = "\u001b[35m";
  private static final String HUMANO = "\u001b[33m";
  private static final String CINZA = "\u001b[90m";
  private static final String FIM = "\u001b[0m";
  private static final NumberFormat BRL = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("pt-BR"));

  @Autowired AgendadorService agendador;
  @Autowired EventosService eventos;

  private int passo;
  private int acoesHumanas;
  private int acoesAutomaticas;

  private void titulo(String t) {
    System.out.println("\n" + TITULO + (++passo) + ". " + t + FIM);
  }

  private void humano(String t) {
    acoesHumanas++;
    System.out.println("   " + HUMANO + "[HUMANO]" + FIM + " " + t);
  }

  private void auto(String t) {
    acoesAutomaticas++;
    System.out.println("   " + AUTO + "[AUTOMÁTICO]" + FIM + " " + t);
  }

  private static void info(String t) {
    System.out.println("   " + CINZA + t + FIM);
  }

  private static String brl(JsonNode v) {
    return BRL.format(v.decimalValue());
  }

  @Test
  void fluxoCompleto() throws Exception {
    UUID tenant = uuid();
    var api = cliente(tenant);

    // "Módulo Core" simulado, que só escuta eventos do Financeiro
    List<JsonNode> recebidosCore = new CopyOnWriteArrayList<>();
    HttpServer core = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    core.createContext("/api/core/webhooks/financeiro", ex -> {
      recebidosCore.add(json.readTree(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
      ex.sendResponseHeaders(204, -1);
      ex.close();
    });
    core.start();
    String urlCore = "http://127.0.0.1:" + core.getAddress().getPort() + "/api/core/webhooks/financeiro";

    titulo("Descoberta: outro serviço verifica se o Financeiro está no ar");
    JsonNode h = executar(get(BASE + "/health").contextPath(BASE)).data();
    auto("GET /health → " + h.get("status").asText() + " (" + h.at("/dependencias/banco/tipo").asText() + " "
        + h.at("/dependencias/banco/status").asText() + ", v" + h.get("versao").asText() + ")");

    titulo("Módulo Core se inscreve para ser avisado de pagamentos e atrasos");
    api.post("/eventos/assinaturas", Map.of("modulo", "core", "url", urlCore,
        "eventos", List.of("cobranca.paga", "conta_receber.vencida", "recorrencia.cobranca_gerada"))).esperar(201);
    auto("Assinatura criada via API (comunicação serviço-a-serviço)");

    titulo("Configuração dos contratos (única etapa humana)");
    relogio.fixar("2026-09-15");
    var pro = Map.of("tipo", "servico", "descricao", "Licença plano Pro", "quantidade", 1, "valor_unitario", 249.9);
    var extras = Map.of("tipo", "consumo", "descricao", "Usuários adicionais", "quantidade", 4, "valor_unitario", 15);
    JsonNode r1 = api.post("/recorrencias", Map.of("cliente_id", uuid(), "contrato_id", uuid(), "descricao", "Assinatura Cliente Alfa",
        "periodicidade", "mensal", "dia_vencimento", 10, "dias_antecedencia", 10, "data_inicio", "2026-10-01", "forma_pagamento", "pix",
        "itens", List.of(pro, extras))).esperar(201).data();
    humano("Recorrência \"Cliente Alfa\" — " + brl(r1.get("valor_ciclo")) + "/mês, vence dia 10, Pix");
    JsonNode r2 = api.post("/recorrencias", Map.of("cliente_id", uuid(), "contrato_id", uuid(), "descricao", "Assinatura Cliente Beta",
        "periodicidade", "mensal", "dia_vencimento", 10, "dias_antecedencia", 10, "data_inicio", "2026-10-01", "forma_pagamento", "boleto",
        "itens", List.of(pro))).esperar(201).data();
    humano("Recorrência \"Cliente Beta\" — " + brl(r2.get("valor_ciclo")) + "/mês, vence dia 10, boleto");

    titulo("30/09 00:05 — o agendador acorda sozinho");
    relogio.fixar("2026-09-30");
    var g = (ResultadoProcessamento) ((AgendadorService.ResultadoJob) agendador.executar(Job.recorrencias, null)).resultado();
    auto(g.cobrancasGeradas() + " cobranças emitidas e aprovadas para a competência 10/2026");
    JsonNode contas = api.get("/contas-receber").data().get("items");
    JsonNode alfa = StreamSupport.stream(contas.spliterator(), false).filter(c -> !c.get("pix_txid").isNull()).findFirst().orElseThrow();
    JsonNode beta = StreamSupport.stream(contas.spliterator(), false).filter(c -> c.get("pix_txid").isNull()).findFirst().orElseThrow();
    auto("Pix copia-e-cola gerado para Alfa (txid " + alfa.get("pix_txid").asText().substring(0, 12) + "…)");
    auto("Contas a receber criadas: " + contas.size());

    titulo("08/10 — Cliente Alfa paga o Pix; o banco (PSP) notifica o Financeiro");
    String corpo = json.writeValueAsString(Map.of("pix", List.of(Map.of("endToEndId", "E18236120202610081432s0000000001",
        "txid", alfa.get("pix_txid").asText(), "valor", alfa.get("valor").decimalValue().toPlainString(), "horario", "2026-10-08T17:32:00Z"))));
    JsonNode w = executar(post(BASE + "/webhooks/pix").contextPath(BASE).contentType(MediaType.APPLICATION_JSON)
        .header("x-pix-assinatura", assinarPix(corpo)).content(corpo)).data();
    auto("Webhook Pix assinado (HMAC) recebido → " + w.at("/resultados/0/resultado").asText());
    JsonNode alfaDepois = api.get("/contas-receber/" + alfa.get("id").asText()).data();
    auto("Conta Alfa: " + alfaDepois.get("status").asText() + ", " + brl(alfaDepois.get("valor_pago")) + " em "
        + alfaDepois.get("data_pagamento").asText() + " — sem digitação");

    titulo("Despachante entrega os eventos ao módulo Core");
    auto(eventos.despachar().entregues() + " eventos entregues via HTTP POST assinado");
    for (JsonNode e : recebidosCore) {
      info("Core recebeu: " + e.get("tipo").asText() + (e.has("competencia") ? " (" + e.get("competencia").asText() + ")" : ""));
    }

    titulo("11/10 00:15 — job de vencimentos");
    relogio.fixar("2026-10-11");
    var v = (ResultadoVencimentos) ((AgendadorService.ResultadoJob) agendador.executar(Job.vencimentos, null)).resultado();
    auto(v.contasReceberVencidas() + " conta marcada como vencida (Cliente Beta)");
    recebidosCore.clear();
    eventos.despachar();
    for (JsonNode e : recebidosCore) info("Core recebeu: " + e.get("tipo").asText() + " → pode suspender o acesso do cliente automaticamente");

    titulo("25/10 — painel de inadimplência");
    relogio.fixar("2026-10-25");
    JsonNode res = api.get("/inadimplencia/resumo").data();
    JsonNode linha = api.get("/inadimplencia").data().at("/items/0");
    info("Total vencido " + brl(res.get("total_vencido")) + " | atualizado " + brl(res.get("total_vencido_atualizado")) + " | taxa "
        + res.get("taxa_inadimplencia_percentual").asDouble() + "%");
    info("Beta: " + linha.get("dias_atraso").asInt() + " dias de atraso, multa " + brl(linha.get("multa")) + ", juros " + brl(linha.get("juros")));

    titulo("26/10 — extrato OFX do banco chega com o boleto do Beta pago");
    String ofx = "OFXHEADER:100\nDATA:OFXSGML\nVERSION:102\nCHARSET:1252\n\n<OFX><BANKMSGSRSV1><STMTTRNRS><STMTRS><BANKACCTFROM><BANKID>0001<ACCTID>12345-6</BANKACCTFROM><BANKTRANLIST>\n"
        + "<STMTTRN><TRNTYPE>CREDIT<DTPOSTED>20261008<TRNAMT>" + alfa.get("valor").decimalValue().toPlainString() + "<FITID>X1<MEMO>PIX " + alfa.get("pix_txid").asText() + "</STMTTRN>\n"
        + "<STMTTRN><TRNTYPE>CREDIT<DTPOSTED>20261013<TRNAMT>" + beta.get("valor").decimalValue().toPlainString() + "<FITID>X2<MEMO>LIQUIDACAO BOLETO</STMTTRN>\n"
        + "<STMTTRN><TRNTYPE>DEBIT<DTPOSTED>20261015<TRNAMT>-19.90<FITID>X3<MEMO>TARIFA PACOTE SERVICOS</STMTTRN>\n"
        + "</BANKTRANLIST></STMTRS></STMTTRNRS></BANKMSGSRSV1></OFX>";
    humano("Upload do arquivo OFX (no futuro: busca automática via Open Finance)");
    JsonNode imp = api.post("/conciliacoes/importar", Map.of("nome_arquivo", "extrato-out.ofx", "conteudo", ofx)).esperar(201).data();
    auto(imp.get("total_transacoes").asInt() + " transações lidas, " + imp.get("conciliadas_automaticamente").asInt()
        + " conciliadas automaticamente, " + imp.get("pendentes").asInt() + " pendentes");
    info("Pix do Alfa, já baixado pelo webhook, foi só vinculado ao título (sem baixa em dobro)");
    info("Tarifa bancária não tem título → fica para o financeiro classificar");
    JsonNode betaDepois = api.get("/contas-receber/" + beta.get("id").asText()).data();
    auto("Conta Beta: " + betaDepois.get("status").asText() + " em " + betaDepois.get("data_pagamento").asText());

    System.out.println("\n" + OK + "Resumo: " + acoesHumanas + " ações humanas × " + acoesAutomaticas
        + " ações executadas automaticamente pelo sistema." + FIM + "\n");
    core.stop(0);
  }
}
