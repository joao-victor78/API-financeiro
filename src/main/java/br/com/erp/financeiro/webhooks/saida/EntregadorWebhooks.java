package br.com.erp.financeiro.webhooks.saida;

import br.com.erp.financeiro.comum.Relogio;
import br.com.erp.financeiro.eventos.TiposDeEvento;
import br.com.erp.financeiro.seguranca.Cifra;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Entrega dos webhooks de saída (§91, §92): a cada 15 s, as entregas pendentes. Cabeçalhos:
 * <ul>
 *   <li>X-Financeiro-Evento: financeiro.pagamento.confirmado (e X-Financeiro-Evento-Apelido: payment.received);</li>
 *   <li>X-Financeiro-Entrega: id da entrega; X-Financeiro-Evento-Id: id do evento (idempotência do lado de lá);</li>
 *   <li>X-Financeiro-Assinatura: t=&lt;epoch&gt;,v1=&lt;HMAC-SHA256 hex de "t.corpo" com o segredo da assinatura&gt;.</li>
 * </ul>
 * 2xx = ENTREGUE. Senão, nova tentativa em 1, 5, 25, 125 e 625 minutos; depois FALHOU (reenviável pela tela).
 */
@Component
public class EntregadorWebhooks {

  private static final Logger log = LoggerFactory.getLogger(EntregadorWebhooks.class);
  static final int MAX_TENTATIVAS = 6;

  private final JdbcTemplate jdbc;
  private final Cifra cifra;
  private final DestinoSeguro destinos;
  private final Relogio relogio;
  private final HttpClient http;
  private final boolean ativo;

  public EntregadorWebhooks(JdbcTemplate jdbc, Cifra cifra, DestinoSeguro destinos, Relogio relogio,
      @Value("${financeiro.jobs.ativos:true}") boolean ativo) {
    this.jdbc = jdbc;
    this.cifra = cifra;
    this.destinos = destinos;
    this.relogio = relogio;
    this.ativo = ativo;
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
  }

  @Scheduled(fixedDelayString = "PT15S", initialDelayString = "PT30S")
  public void agendado() {
    if (ativo) entregarPendentes();
  }

  public int entregarPendentes() {
    List<UUID> ids = jdbc.queryForList("""
        SELECT id FROM financeiro.webhook_entregas WHERE status = 'PENDENTE' AND proxima_tentativa <= ?
         ORDER BY proxima_tentativa LIMIT 100""", UUID.class, Timestamp.from(relogio.agora()));
    int ok = 0;
    for (UUID id : ids) if (entregar(id)) ok++;
    return ok;
  }

  /** Tenta uma entrega; seguro chamar duas vezes (só age sobre PENDENTE). */
  public synchronized boolean entregar(UUID id) {
    List<Map<String, Object>> l = jdbc.queryForList("""
        SELECT e.tipo, e.payload::text AS payload, e.evento_id, e.tentativas, w.url, w.segredo_cifrado, w.ativa, w.deleted_at
          FROM financeiro.webhook_entregas e JOIN financeiro.webhook_assinaturas w ON w.id = e.assinatura_id
         WHERE e.id = ? AND e.status = 'PENDENTE'""", id);
    if (l.isEmpty()) return false;
    Map<String, Object> e = l.get(0);
    if (!(Boolean) e.get("ativa") || e.get("deleted_at") != null) {
      registrarFalha(id, MAX_TENTATIVAS, null, "Assinatura inativa ou excluída.");
      return false;
    }
    String tipo = (String) e.get("tipo");
    String corpo = (String) e.get("payload");
    int feitas = ((Number) e.get("tentativas")).intValue();
    try {
      URI destino = destinos.validar((String) e.get("url"));
      long t = relogio.agora().getEpochSecond();
      String assinatura = "t=" + t + ",v1=" + hmac(cifra.decifrar((String) e.get("segredo_cifrado")), t + "." + corpo);
      HttpRequest req = HttpRequest.newBuilder(destino).timeout(Duration.ofSeconds(10))
          .header("Content-Type", "application/json")
          .header("User-Agent", "financeiro-webhooks/1.0")
          .header("X-Financeiro-Evento", tipo)
          .header("X-Financeiro-Evento-Apelido", TiposDeEvento.ASSINAVEIS.getOrDefault(tipo, tipo))
          .header("X-Financeiro-Evento-Id", e.get("evento_id").toString())
          .header("X-Financeiro-Entrega", id.toString())
          .header("X-Financeiro-Assinatura", assinatura)
          .POST(HttpRequest.BodyPublishers.ofString(corpo, StandardCharsets.UTF_8)).build();
      HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
      if (r.statusCode() >= 200 && r.statusCode() < 300) {
        jdbc.update("""
            UPDATE financeiro.webhook_entregas SET status = 'ENTREGUE', tentativas = tentativas + 1, ultimo_status_http = ?,
                   ultimo_erro = NULL, entregue_em = ? WHERE id = ?""", r.statusCode(), Timestamp.from(relogio.agora()), id);
        return true;
      }
      registrarFalha(id, feitas + 1, r.statusCode(), "HTTP " + r.statusCode());
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      registrarFalha(id, feitas + 1, null, "interrompido");
    } catch (Exception ex) {
      registrarFalha(id, feitas + 1, null, ex.getClass().getSimpleName() + ": " + ex.getMessage());
    }
    return false;
  }

  private void registrarFalha(UUID id, int tentativas, Integer http, String erro) {
    long espera = (long) Math.pow(5, Math.max(0, tentativas - 1)); // 1, 5, 25, 125, 625 min
    String e = erro == null ? null : erro.length() > 500 ? erro.substring(0, 500) : erro;
    jdbc.update("""
        UPDATE financeiro.webhook_entregas SET tentativas = ?, ultimo_status_http = ?, ultimo_erro = ?,
               status = CASE WHEN ? >= ? THEN 'FALHOU' ELSE 'PENDENTE' END, proxima_tentativa = ?
         WHERE id = ?""", tentativas, http, e, tentativas, MAX_TENTATIVAS,
        Timestamp.from(relogio.agora().plus(Duration.ofMinutes(espera))), id);
    log.warn("Webhook de saída {} não entregue (tentativa {}): {}", id, tentativas, e);
  }

  public static String hmac(String segredo, String base) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(segredo.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(base.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
