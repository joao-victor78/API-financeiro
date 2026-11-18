package br.com.erp.financeiro;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.erp.financeiro.pix.BrCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** Rascunho → aprovação (conta a receber + Pix) → pagamento ou cancelamento; QR Code legível. */
class CobrancasTest extends TesteBase {

  private final UUID tenant = uuid();

  static Map<String, Object> cobranca(Object... extra) {
    Map<String, Object> m = new HashMap<>(Map.of("cliente_id", uuid(), "competencia", "2026-10", "descricao", "Fatura outubro",
        "data_vencimento", "2026-10-10", "forma_pagamento", "pix", "itens", List.of(
            Map.of("tipo", "servico", "descricao", "Plano Pro", "quantidade", 1, "valor_unitario", 150),
            Map.of("tipo", "consumo", "descricao", "Usuários extras", "quantidade", 3, "valor_unitario", 10),
            Map.of("tipo", "extra", "descricao", "Suporte", "quantidade", 1, "valor_unitario", 19.9))));
    for (int i = 0; i < extra.length; i += 2) m.put((String) extra[i], extra[i + 1]);
    return m;
  }

  @Test
  void aprovacaoGeraContaAReceberTxidEPixCopiaEColaValido() {
    var a = cliente(tenant);
    JsonNode c = a.post("/cobrancas", cobranca()).esperar(201).data();
    assertThat(c.get("status").asText()).isEqualTo("rascunho");
    assertThat(c.get("valor_total").decimalValue()).isEqualByComparingTo("199.9");
    assertThat(c.get("pix_txid").isNull()).isTrue();
    String id = c.get("id").asText();

    JsonNode ap = a.post("/cobrancas/" + id + "/aprovar").esperar(200).data();
    assertThat(ap.get("status").asText()).isEqualTo("aprovada");
    assertThat(ap.get("pix_txid").asText()).matches("^[0-9a-f]{32}$");
    String br = ap.get("pix_copia_cola").asText();
    assertThat(BrCode.crc16(br.substring(0, br.length() - 4))).isEqualTo(br.substring(br.length() - 4));

    JsonNode conta = a.get("/contas-receber/" + ap.get("conta_receber_id").asText()).esperar(200).data();
    assertThat(conta.get("cobranca_id").asText()).isEqualTo(id);
    assertThat(conta.get("valor").decimalValue()).isEqualByComparingTo("199.9");
    assertThat(conta.get("pix_txid").asText()).isEqualTo(ap.get("pix_txid").asText());
    assertThat(conta.get("status").asText()).isEqualTo("em_aberto");

    a.post("/cobrancas/" + id + "/aprovar").esperar(409);
    a.patch("/cobrancas/" + id, Map.of("descricao", "x")).esperar(409);
  }

  @Test
  void qrCodePngLegivelContendoOPixCopiaEColaSvgEErros() throws Exception {
    var a = cliente(tenant);
    String id = a.post("/cobrancas", cobranca()).esperar(201).data().get("id").asText();
    a.get("/cobrancas/" + id + "/qrcode").esperar(409);
    JsonNode ap = a.post("/cobrancas/" + id + "/aprovar").esperar(200).data();

    Resp png = a.get("/cobrancas/" + id + "/qrcode?tamanho=400").esperar(200);
    assertThat(png.bruta().getContentType()).isEqualTo("image/png");
    BufferedImage img = ImageIO.read(new ByteArrayInputStream(png.bruta().getContentAsByteArray()));
    assertThat(img.getWidth()).isEqualTo(400);
    String lido = new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(img)))).getText();
    assertThat(lido).isEqualTo(ap.get("pix_copia_cola").asText());

    Resp svg = a.get("/cobrancas/" + id + "/qrcode?formato=svg").esperar(200);
    assertThat(svg.bruta().getContentType()).contains("image/svg+xml");
    assertThat(svg.bruta().getContentAsString(StandardCharsets.UTF_8)).contains("<svg");

    a.get("/cobrancas/" + id + "/qrcode?formato=gif").esperar(422);
    cliente(uuid()).get("/cobrancas/" + id + "/qrcode").esperar(404);
    String boleto = a.post("/cobrancas", cobranca("forma_pagamento", "boleto")).esperar(201).data().get("id").asText();
    a.post("/cobrancas/" + boleto + "/aprovar").esperar(200);
    a.get("/cobrancas/" + boleto + "/qrcode").esperar(422);
  }

  @Test
  void cancelamentoCancelaAContaVinculadaECobrancaPagaNaoCancela() {
    var a = cliente(tenant);
    String id = a.post("/cobrancas", cobranca()).esperar(201).data().get("id").asText();
    JsonNode ap = a.post("/cobrancas/" + id + "/aprovar").esperar(200).data();
    JsonNode can = a.post("/cobrancas/" + id + "/cancelar", Map.of("motivo", "Cliente desistiu")).esperar(200).data();
    assertThat(can.get("status").asText()).isEqualTo("cancelada");
    assertThat(a.get("/contas-receber/" + ap.get("conta_receber_id").asText()).data().get("status").asText()).isEqualTo("cancelada");

    String id2 = a.post("/cobrancas", cobranca()).esperar(201).data().get("id").asText();
    JsonNode ap2 = a.post("/cobrancas/" + id2 + "/aprovar").esperar(200).data();
    a.post("/contas-receber/" + ap2.get("conta_receber_id").asText() + "/baixa",
        Map.of("valor", 199.9, "data_pagamento", "2026-10-09", "forma_pagamento", "pix")).esperar(200);
    assertThat(a.get("/cobrancas/" + id2).data().get("status").asText()).isEqualTo("paga");
    a.post("/cobrancas/" + id2 + "/cancelar", Map.of("motivo", "tarde demais")).esperar(409);
  }

  @Test
  void itensSaoValidadosUmAUm() {
    Resp r = cliente(tenant).post("/cobrancas", cobranca("itens", List.of(Map.of("tipo", "brinde", "descricao", "x", "quantidade", 0, "valor_unitario", 1))))
        .esperar(422);
    assertThat(r.body().get("errors").toString()).contains("tipo").contains("quantidade");
  }
}
