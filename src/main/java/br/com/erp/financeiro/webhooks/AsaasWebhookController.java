package br.com.erp.financeiro.webhooks;

import br.com.erp.financeiro.api.Resposta;
import br.com.erp.financeiro.pagamentos.provedor.ProvedorPagamento;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Webhook do Asaas (§29), traduzido para o formato neutro e processado pelo mesmo WebhookService:
 * validação do token, idempotência pelo id do evento e baixa automática. Evento que não é de pagamento
 * responde 200 "ignorado" para o Asaas não reenviar.
 */
@Tag(name = "webhooks")
@RestController
@ConditionalOnProperty(name = "financeiro.provedor.nome", havingValue = "asaas")
public class AsaasWebhookController {

  private static final Logger log = LoggerFactory.getLogger(AsaasWebhookController.class);

  private final WebhookService webhooks;

  public AsaasWebhookController(WebhookService webhooks) {
    this.webhooks = webhooks;
  }

  @PostMapping("/public/financeiro/webhooks/asaas")
  public Resposta<Map<String, Object>> receber(@RequestHeader(name = "asaas-access-token", required = false) String token,
      @RequestBody JsonNode corpo) {
    String evento = corpo.path("event").asText("");
    JsonNode p = corpo.path("payment");
    String[] ref = p.path("externalReference").asText("").split("\\|");
    UUID cobranca = uuid(ref.length > 0 ? ref[0] : null);
    UUID pagamento = uuid(ref.length > 1 ? ref[1] : null);
    String id = corpo.path("id").asText("");
    if (id.isBlank()) id = evento + ":" + p.path("id").asText("") + ":" + p.path("status").asText("");
    if (cobranca == null || !evento.startsWith("PAYMENT_")) return Resposta.ok(Map.of("recebido", true, "ignorado", true));
    BigDecimal valor = p.hasNonNull("value") ? p.path("value").decimalValue() : null;
    String quando = p.path("clientPaymentDate").asText(p.path("paymentDate").asText(p.path("confirmedDate").asText("")));
    OffsetDateTime data = quando.isBlank() ? null : LocalDate.parse(quando.substring(0, 10)).atTime(12, 0).atOffset(java.time.ZoneOffset.ofHours(-3));
    try {
      webhooks.receber(new ProvedorPagamento.WebhookRecebido(id, token, evento, cobranca, pagamento, valor, data));
    } catch (IllegalArgumentException e) {
      log.info("Evento Asaas {} não tratado; ignorado.", evento);
      return Resposta.ok(Map.of("recebido", true, "ignorado", true));
    }
    return Resposta.ok(Map.of("recebido", true));
  }

  @ExceptionHandler(ProvedorPagamento.AssinaturaInvalidaException.class)
  ResponseEntity<Resposta<Void>> tokenInvalido(ProvedorPagamento.AssinaturaInvalidaException e) {
    return ResponseEntity.status(401).body(Resposta.falha("Token do webhook inválido."));
  }

  private static UUID uuid(String s) {
    try {
      return s == null || s.isBlank() ? null : UUID.fromString(s);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
