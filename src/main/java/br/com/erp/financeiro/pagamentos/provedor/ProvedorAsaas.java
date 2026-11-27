package br.com.erp.financeiro.pagamentos.provedor;

import br.com.erp.financeiro.comum.Relogio;
import br.com.erp.financeiro.plataforma.ContatosDaEmpresa;
import br.com.erp.financeiro.tenant.TenantContexto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Adaptador Asaas (§28), ligado com PROVEDOR_PAGAMENTO=asaas. API v3: cliente (/customers) por empresa,
 * cobrança (/payments) com billingType PIX, BOLETO ou CREDIT_CARD, QR Code do PIX (/pixQrCode), linha
 * digitável (/identificationField), estorno (/refund) e exclusão. A chave da API vai no cabeçalho
 * access_token; o webhook chega em /public/financeiro/webhooks/asaas com o token configurado no
 * painel do Asaas no cabeçalho asaas-access-token.
 *
 * <p>externalReference = "cobrancaId|pagamentoId": é assim que o webhook volta para a cobrança certa.
 * Nenhum dado de cartão passa pelo módulo (RN-22): o link usa a página do Asaas e o cartão recorrente
 * usa o creditCardToken já gerado pelo Asaas.
 */
@Component
@ConditionalOnProperty(name = "financeiro.provedor.nome", havingValue = "asaas")
public class ProvedorAsaas implements ProvedorPagamento {

  private static final Logger log = LoggerFactory.getLogger(ProvedorAsaas.class);
  public static final String NOME = "asaas";

  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private final ObjectMapper mapper;
  private final JdbcTemplate jdbc;
  private final ContatosDaEmpresa crm;
  private final Relogio relogio;
  private final String url;
  private final String chave;
  private final String tokenWebhook;

  public ProvedorAsaas(ObjectMapper mapper, JdbcTemplate jdbc, ContatosDaEmpresa crm, Relogio relogio,
      @Value("${financeiro.provedor.asaas.url}") String url,
      @Value("${financeiro.provedor.asaas.chave-api}") String chave,
      @Value("${financeiro.provedor.asaas.token-webhook}") String tokenWebhook) {
    if (chave == null || chave.isBlank()) throw new IllegalStateException("PROVEDOR_PAGAMENTO=asaas exige ASAAS_CHAVE_API");
    if (tokenWebhook == null || tokenWebhook.isBlank()) throw new IllegalStateException("PROVEDOR_PAGAMENTO=asaas exige ASAAS_TOKEN_WEBHOOK");
    this.mapper = mapper;
    this.jdbc = jdbc;
    this.crm = crm;
    this.relogio = relogio;
    this.url = url.replaceAll("/+$", "");
    this.chave = chave;
    this.tokenWebhook = tokenWebhook;
  }

  @Override
  public String nome() {
    return NOME;
  }

  @Override
  public Cobrado createPix(Solicitacao s) {
    JsonNode p = criarCobranca(s, "PIX", null);
    JsonNode qr = chamar("GET", "/payments/" + p.path("id").asText() + "/pixQrCode", null);
    return new Cobrado(p.path("id").asText(), qr.path("payload").asText(null), null, p.path("bankSlipUrl").asText(null),
        p.path("invoiceUrl").asText(null), data(qr.path("expirationDate").asText(null)));
  }

  @Override
  public Cobrado createBoleto(Solicitacao s) {
    JsonNode p = criarCobranca(s, "BOLETO", null);
    JsonNode linha = chamar("GET", "/payments/" + p.path("id").asText() + "/identificationField", null);
    return new Cobrado(p.path("id").asText(), null, linha.path("identificationField").asText(null), p.path("bankSlipUrl").asText(null),
        p.path("invoiceUrl").asText(null), s.vencimento() == null ? null : s.vencimento().plusDays(1).atStartOfDay().atOffset(java.time.ZoneOffset.ofHours(-3)));
  }

  /** Link: a fatura do Asaas, onde o cliente digita o cartão. */
  @Override
  public Cobrado createCardPayment(Solicitacao s) {
    JsonNode p = criarCobranca(s, "CREDIT_CARD", null);
    return new Cobrado(p.path("id").asText(), null, null, null, p.path("invoiceUrl").asText(null), null);
  }

  @Override
  public Cobrado chargeSavedCard(String tokenCartao, Solicitacao s) {
    JsonNode p = criarCobranca(s, "CREDIT_CARD", tokenCartao);
    return new Cobrado(p.path("id").asText(), null, null, null, p.path("invoiceUrl").asText(null), null);
  }

  @Override
  public boolean refund(String referencia, BigDecimal valor) {
    Map<String, Object> corpo = new LinkedHashMap<>();
    if (valor != null) corpo.put("value", valor);
    JsonNode r = chamar("POST", "/payments/" + referencia + "/refund", corpo);
    return !r.path("id").isMissingNode();
  }

  @Override
  public SituacaoNoProvedor getPayment(String referencia) {
    String status = chamar("GET", "/payments/" + referencia, null).path("status").asText("");
    return switch (status) {
      case "RECEIVED", "CONFIRMED", "RECEIVED_IN_CASH" -> SituacaoNoProvedor.PAGO;
      case "PENDING", "OVERDUE", "AWAITING_RISK_ANALYSIS" -> SituacaoNoProvedor.PENDENTE;
      case "REFUNDED", "REFUND_REQUESTED", "CHARGEBACK_REQUESTED" -> SituacaoNoProvedor.ESTORNADO;
      default -> SituacaoNoProvedor.DESCONHECIDO;
    };
  }

  @Override
  public boolean cancelPayment(String referencia) {
    try {
      return chamar("DELETE", "/payments/" + referencia, null).path("deleted").asBoolean(false);
    } catch (RuntimeException e) {
      log.warn("Cobrança {} não excluída no Asaas: {}", referencia, e.getMessage());
      return false;
    }
  }

  /** O token do cabeçalho asaas-access-token chega em "assinatura" (ver AsaasWebhookController). */
  @Override
  public EventoProvedor processWebhook(WebhookRecebido w) {
    if (w.assinatura() == null || !MessageDigest.isEqual(w.assinatura().getBytes(StandardCharsets.UTF_8),
        tokenWebhook.getBytes(StandardCharsets.UTF_8))) {
      throw new AssinaturaInvalidaException();
    }
    TipoEventoProvedor tipo = switch (w.tipo()) {
      case "PAYMENT_RECEIVED", "PAYMENT_CONFIRMED", "PAYMENT_RECEIVED_IN_CASH" -> TipoEventoProvedor.CONFIRMADO;
      case "PAYMENT_REFUNDED", "PAYMENT_PARTIALLY_REFUNDED", "PAYMENT_CHARGEBACK_REQUESTED" -> TipoEventoProvedor.ESTORNADO;
      case "PAYMENT_CREDIT_CARD_CAPTURE_REFUSED", "PAYMENT_REPROVED_BY_RISK_ANALYSIS" -> TipoEventoProvedor.FALHOU;
      default -> throw new IllegalArgumentException("tipo");
    };
    return new EventoProvedor(w.eventoId(), tipo, w.cobrancaId(), w.pagamentoId(), w.valorPago(),
        w.dataPagamento() != null ? w.dataPagamento() : relogio.agoraUtc());
  }

  // ------------------------------------------------------------------ apoio

  private JsonNode criarCobranca(Solicitacao s, String tipo, String tokenCartao) {
    Map<String, Object> corpo = new LinkedHashMap<>();
    corpo.put("customer", cliente(s.empresaId()));
    corpo.put("billingType", tipo);
    corpo.put("value", s.valor());
    LocalDate venc = s.vencimento() == null || s.vencimento().isBefore(relogio.hoje()) ? relogio.hoje() : s.vencimento();
    corpo.put("dueDate", venc.toString());
    corpo.put("description", s.descricao() == null ? "Cobrança " + s.cobrancaId().toString().substring(0, 8) : s.descricao());
    corpo.put("externalReference", s.cobrancaId() + "|" + s.pagamentoId());
    if (tokenCartao != null) corpo.put("creditCardToken", tokenCartao);
    return chamar("POST", "/payments", corpo);
  }

  /** Cliente do Asaas da empresa: reaproveitado (provedor_clientes) ou criado com os dados do CRM. */
  private String cliente(UUID empresaId) {
    if (empresaId == null) throw new IllegalStateException("A cobrança não tem empresa para o cliente do Asaas.");
    UUID tenant = TenantContexto.exigir();
    List<String> ja = jdbc.queryForList("SELECT referencia FROM financeiro.provedor_clientes WHERE tenant_id = ? AND provedor = ? AND empresa_id = ?",
        String.class, tenant, NOME, empresaId);
    if (!ja.isEmpty()) return ja.get(0);
    ContatosDaEmpresa.DadosEmpresa d = crm.dados(tenant, empresaId)
        .orElseThrow(() -> new IllegalStateException("Dados da empresa (razão social e CNPJ/CPF) indisponíveis no CRM."));
    if (d.documento() == null || d.documento().isBlank()) throw new IllegalStateException("A empresa não tem CNPJ/CPF no CRM.");
    Map<String, Object> corpo = new LinkedHashMap<>();
    corpo.put("name", d.razaoSocial());
    corpo.put("cpfCnpj", d.documento());
    if (d.email() != null) corpo.put("email", d.email());
    corpo.put("externalReference", empresaId.toString());
    corpo.put("notificationDisabled", true); // a régua de cobrança é do módulo (§30), não do Asaas
    String id = chamar("POST", "/customers", corpo).path("id").asText();
    jdbc.update("INSERT INTO financeiro.provedor_clientes (tenant_id, provedor, empresa_id, referencia, criado_em) VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
        tenant, NOME, empresaId, id, Timestamp.from(relogio.agora()));
    return id;
  }

  private JsonNode chamar(String metodo, String caminho, Object corpo) {
    try {
      HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url + caminho)).timeout(Duration.ofSeconds(15))
          .header("access_token", chave).header("Content-Type", "application/json").header("User-Agent", "financeiro/1.0");
      HttpRequest.BodyPublisher p = corpo == null ? HttpRequest.BodyPublishers.noBody()
          : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(corpo), StandardCharsets.UTF_8);
      HttpResponse<String> r = http.send(b.method(metodo, p).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      JsonNode j = r.body() == null || r.body().isBlank() ? mapper.createObjectNode() : mapper.readTree(r.body());
      if (r.statusCode() >= 300) {
        String erro = j.path("errors").path(0).path("description").asText("HTTP " + r.statusCode());
        throw new IllegalStateException("Asaas recusou " + metodo + " " + caminho.replaceAll("/[a-z_]+_[A-Za-z0-9]+", "/…") + ": " + erro);
      }
      return j;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Chamada ao Asaas interrompida", e);
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Asaas indisponível: " + e.getMessage(), e);
    }
  }

  private static OffsetDateTime data(String s) {
    if (s == null || s.isBlank()) return null;
    try {
      return LocalDateTime.parse(s.replace(' ', 'T')).atOffset(java.time.ZoneOffset.ofHours(-3));
    } catch (RuntimeException e) {
      return null;
    }
  }
}
