package br.com.erp.financeiro;

import static br.com.erp.financeiro.ApiEAutenticacaoTest.novaConta;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Baixa automática pelo webhook Pix e conciliação do extrato OFX. */
class PixEConciliacaoTest extends TesteBase {

  private Resp webhook(String corpo, String assinatura) {
    return executar(post(BASE + "/webhooks/pix").contentType(MediaType.APPLICATION_JSON).header("x-pix-assinatura", assinatura).content(corpo));
  }

  private Resp importarArquivo(UUID tenant, String ofx) {
    return executar(MockMvcRequestBuilders.multipart(BASE + "/conciliacoes/importar")
        .file(new MockMultipartFile("arquivo", "extrato-outubro.ofx", "application/x-ofx", ofx.getBytes(StandardCharsets.ISO_8859_1)))
        .contextPath(BASE)
        .header("Authorization", "Bearer " + token(tenant)));
  }

  private static Map<String, Object> cobrancaPix(double valor) {
    return Map.of("cliente_id", uuid(), "competencia", "2026-10", "descricao", "Fatura", "data_vencimento", "2026-10-10",
        "forma_pagamento", "pix", "itens", List.of(Map.of("tipo", "servico", "descricao", "Plano", "quantidade", 1, "valor_unitario", valor)));
  }

  @Test
  void webhookPixAssinaturaInvalida401ValidaPagaContaECobrancaERepeticaoEIdempotente() {
    UUID t = uuid();
    var c = cliente(t);
    String cob = c.post("/cobrancas", cobrancaPix(89.9)).esperar(201).data().get("id").asText();
    JsonNode ap = c.post("/cobrancas/" + cob + "/aprovar").esperar(200).data();
    String txid = ap.get("pix_txid").asText();
    String conta = ap.get("conta_receber_id").asText();

    String corpo = "{\"pix\":[{\"endToEndId\":\"E00000000202610091200abcdef12345\",\"txid\":\"" + txid
        + "\",\"valor\":\"89.90\",\"horario\":\"2026-10-09T15:00:00Z\"}]}";
    webhook(corpo, "sha256=00").esperar(401);
    assertThat(webhook(corpo, assinarPix(corpo)).esperar(200).data().at("/resultados/0/resultado").asText()).isEqualTo("baixado");

    JsonNode cr = c.get("/contas-receber/" + conta).data();
    assertThat(cr.get("status").asText()).isEqualTo("paga");
    assertThat(cr.get("valor_pago").decimalValue()).isEqualByComparingTo("89.9");
    assertThat(cr.get("forma_pagamento").asText()).isEqualTo("pix");
    assertThat(cr.get("data_pagamento").asText()).isEqualTo("2026-10-09");
    assertThat(c.get("/cobrancas/" + cob).data().get("status").asText()).isEqualTo("paga");

    // O mesmo Pix chega depois no extrato OFX: é vinculado ao título sem baixa em dobro.
    String ofx = "<OFX><STMTTRN><TRNTYPE>CREDIT<DTPOSTED>20261009<TRNAMT>89.90<FITID>PX1<MEMO>PIX " + txid + "</STMTTRN></OFX>";
    JsonNode imp = c.post("/conciliacoes/importar", Map.of("conteudo", ofx)).esperar(201).data();
    assertThat(imp.get("conciliadas_automaticamente").asInt()).isEqualTo(1);
    assertThat(imp.get("pendentes").asInt()).isZero();
    JsonNode tr = c.get("/conciliacoes/" + imp.get("id").asText()).data().at("/transacoes/0");
    assertThat(tr.get("status").asText()).isEqualTo("conciliada");
    assertThat(tr.get("criterio").asText()).isEqualTo("txid_pix_ja_baixado");
    assertThat(tr.get("conta_receber_id").asText()).isEqualTo(conta);
    assertThat(c.get("/contas-receber/" + conta).data().get("valor_pago").decimalValue()).isEqualByComparingTo("89.9");

    assertThat(webhook(corpo, assinarPix(corpo)).esperar(200).data().at("/resultados/0/resultado").asText()).isEqualTo("duplicado");

    JsonNode eventos = c.get("/eventos?limit=100").data().get("items");
    assertThat(StreamSupport.stream(eventos.spliterator(), false).map(e -> e.get("tipo").asText()))
        .contains("cobranca.aprovada", "conta_receber.paga", "cobranca.paga");
  }

  @Test
  void txidDesconhecidoNaoQuebraOLote() {
    String corpo = "{\"pix\":[{\"endToEndId\":\"E1\",\"txid\":\"naoexiste\",\"valor\":\"1.00\",\"horario\":\"2026-10-09T15:00:00Z\"}]}";
    assertThat(webhook(corpo, assinarPix(corpo)).esperar(200).data().at("/resultados/0/resultado").asText()).isEqualTo("txid_desconhecido");
  }

  @Test
  void importaConciliaAutomaticamenteOInequivocoEDeixaAmbiguosParaRevisao() {
    UUID t = uuid();
    var c = cliente(t);

    // 1) cobrança Pix (txid no extrato)
    String cob = c.post("/cobrancas", cobrancaPix(250)).esperar(201).data().get("id").asText();
    JsonNode ap = c.post("/cobrancas/" + cob + "/aprovar").esperar(200).data();
    // 2) valor único próximo do vencimento
    String unica = c.post("/contas-receber", novaConta("valor", 437.15, "data_vencimento", "2026-10-12")).esperar(201).data().get("id").asText();
    // 3) duas contas idênticas (ambíguo)
    String amb1 = c.post("/contas-receber", novaConta("valor", 99.9, "data_vencimento", "2026-10-15")).esperar(201).data().get("id").asText();
    String amb2 = c.post("/contas-receber", novaConta("valor", 99.9, "data_vencimento", "2026-10-15")).esperar(201).data().get("id").asText();
    // 4) conta a pagar
    String pagar = c.post("/contas-pagar", Map.of("fornecedor_id", uuid(), "centro_custo_id", uuid(), "categoria_id", uuid(), "descricao", "Cloud",
        "competencia", "2026-10", "data_vencimento", "2026-10-20", "valor", 1200)).esperar(201).data().get("id").asText();

    String ofx = """
        OFXHEADER:100
        DATA:OFXSGML
        VERSION:102
        CHARSET:1252

        <OFX><BANKMSGSRSV1><STMTTRNRS><STMTRS>
        <BANKACCTFROM><BANKID>0260<ACCTID>98765-4</BANKACCTFROM>
        <BANKTRANLIST><DTSTART>20261001<DTEND>20261031
        <STMTTRN><TRNTYPE>CREDIT<DTPOSTED>20261009<TRNAMT>250.00<FITID>F1<MEMO>PIX REC %s</STMTTRN>
        <STMTTRN><TRNTYPE>CREDIT<DTPOSTED>20261011<TRNAMT>437,15<FITID>F2<MEMO>TED CLIENTE</STMTTRN>
        <STMTTRN><TRNTYPE>CREDIT<DTPOSTED>20261015<TRNAMT>99.90<FITID>F3<MEMO>PIX RECEBIDO</STMTTRN>
        <STMTTRN><TRNTYPE>CREDIT<DTPOSTED>20261016<TRNAMT>12.34<FITID>F4<MEMO>RENDIMENTO</STMTTRN>
        <STMTTRN><TRNTYPE>DEBIT<DTPOSTED>20261020<TRNAMT>-1200.00<FITID>F5<MEMO>PAG FORNECEDOR</STMTTRN>
        </BANKTRANLIST></STMTRS></STMTTRNRS></BANKMSGSRSV1></OFX>""".formatted(ap.get("pix_txid").asText());

    JsonNode imp = importarArquivo(t, ofx).esperar(201).data();
    assertThat(imp.get("total_transacoes").asInt()).isEqualTo(5);
    assertThat(imp.get("conciliadas_automaticamente").asInt()).isEqualTo(3);
    assertThat(imp.get("pendentes").asInt()).isEqualTo(2);
    assertThat(imp.get("ignoradas_duplicadas").asInt()).isZero();
    assertThat(imp.get("banco_id").asText()).isEqualTo("0260");
    assertThat(imp.get("taxa_automatica_percentual").asDouble()).isEqualTo(60);
    assertThat(imp.get("arquivo_nome").asText()).isEqualTo("extrato-outubro.ofx");

    assertThat(c.get("/contas-receber/" + ap.get("conta_receber_id").asText()).data().get("status").asText()).isEqualTo("paga");
    assertThat(c.get("/cobrancas/" + cob).data().get("status").asText()).isEqualTo("paga");
    JsonNode u = c.get("/contas-receber/" + unica).data();
    assertThat(u.get("status").asText()).isEqualTo("paga");
    assertThat(u.get("data_pagamento").asText()).isEqualTo("2026-10-11");
    assertThat(c.get("/contas-pagar/" + pagar).data().get("status").asText()).isEqualTo("paga");
    assertThat(c.get("/contas-receber/" + amb1).data().get("status").asText()).isEqualTo("em_aberto");

    String concId = imp.get("id").asText();
    JsonNode det = c.get("/conciliacoes/" + concId).data().get("transacoes");
    Map<String, String> criterios = new TreeMap<>();
    for (JsonNode x : det) criterios.put(x.get("fitid").asText(), x.get("status").asText() + "/" + x.get("criterio").asText());
    assertThat(criterios).containsExactlyEntriesOf(new TreeMap<>(Map.of(
        "F1", "conciliada/txid_pix",
        "F2", "conciliada/valor_exato+data(1d)",
        "F3", "pendente/null",
        "F4", "pendente/null",
        "F5", "conciliada/valor_exato+data(0d)")));

    // Revisão humana do ambíguo com sugestões
    String f3 = idDe(det, "F3");
    JsonNode sug = c.get("/conciliacoes/" + concId + "/transacoes/" + f3 + "/sugestoes").esperar(200).data();
    assertThat(StreamSupport.stream(sug.spliterator(), false).map(s -> s.get("id").asText())).containsExactlyInAnyOrder(amb1, amb2);
    c.post("/conciliacoes/" + concId + "/transacoes/" + f3 + "/conciliar", Map.of("conta_pagar_id", pagar)).esperar(422);
    c.post("/conciliacoes/" + concId + "/transacoes/" + f3 + "/conciliar", Map.of("conta_receber_id", amb2)).esperar(200);
    assertThat(c.get("/contas-receber/" + amb2).data().get("status").asText()).isEqualTo("paga");
    c.post("/conciliacoes/" + concId + "/transacoes/" + idDe(det, "F4") + "/ignorar").esperar(200);
    assertThat(c.get("/conciliacoes/" + concId).data().get("pendentes").asInt()).isZero();

    // Reimportar o mesmo extrato não duplica nada
    JsonNode re = importarArquivo(t, ofx).esperar(201).data();
    assertThat(re.get("total_transacoes").asInt()).isEqualTo(5);
    assertThat(re.get("ignoradas_duplicadas").asInt()).isEqualTo(5);
    assertThat(re.get("conciliadas_automaticamente").asInt()).isZero();
  }

  private static String idDe(JsonNode transacoes, String fitid) {
    return StreamSupport.stream(transacoes.spliterator(), false).filter(x -> x.get("fitid").asText().equals(fitid))
        .findFirst().orElseThrow().get("id").asText();
  }

  @Test
  void arquivoInvalido422ENadaEGravado() {
    UUID t = uuid();
    var c = cliente(t);
    Resp r = c.post("/conciliacoes/importar", Map.of("nome_arquivo", "x.csv", "conteudo", "data;valor\n2026-10-01;10")).esperar(422);
    assertThat(r.body().get("message").asText()).contains("OFX inválido");
    assertThat(c.get("/conciliacoes").data().at("/meta/total").asInt()).isZero();
  }
}
