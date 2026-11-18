package br.com.erp.financeiro.eventos;

import br.com.erp.financeiro.comum.Paginado;
import br.com.erp.financeiro.comum.Relogio;
import br.com.erp.financeiro.comum.auth.AuthUser;
import br.com.erp.financeiro.comum.http.ApiException;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EventosService {

  private static final Logger log = LoggerFactory.getLogger("Eventos");
  private static final int MAX_TENTATIVAS = 8;
  private static final SecureRandom ALEATORIO = new SecureRandom();

  private final EventoRepository eventos;
  private final WebhookAssinaturaRepository assinaturas;
  private final Relogio relogio;
  private final ObjectMapper json;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  public EventosService(EventoRepository eventos, WebhookAssinaturaRepository assinaturas, Relogio relogio, ObjectMapper json) {
    this.eventos = eventos;
    this.assinaturas = assinaturas;
    this.relogio = relogio;
    this.json = json;
  }

  /** Monta o payload de um evento aceitando valores nulos, na ordem informada: chave, valor, chave, valor… */
  public static Map<String, Object> dados(Object... chaveValor) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < chaveValor.length; i += 2) m.put((String) chaveValor[i], chaveValor[i + 1]);
    return m;
  }

  /** Registra o evento dentro da transação corrente (outbox). Nunca faz I/O de rede aqui. */
  public void publicar(UUID tenantId, String tipo, UUID agregadoId, Map<String, Object> payload) {
    Map<String, Object> p = new LinkedHashMap<>(payload);
    p.put("tipo", tipo);
    p.put("tenant_id", tenantId);
    p.put("ocorrido_em", relogio.agora().toString());
    Evento e = new Evento();
    e.setTenantId(tenantId);
    e.setTipo(tipo);
    e.setAgregadoId(agregadoId);
    e.setPayload(p);
    e.setProximaTentativa(relogio.agora());
    eventos.save(e);
  }

  @Transactional(readOnly = true)
  public Paginado<Evento> listar(AuthUser u, String tipo, Paginado.Paginacao p) {
    var pagina = p.pageable(Sort.by(Sort.Direction.DESC, "createdAt"));
    return Paginado.de(tipo == null ? eventos.findByTenantId(u.tenantId(), pagina)
        : eventos.findByTenantIdAndTipo(u.tenantId(), tipo, pagina), p);
  }

  /** Assinatura recém-criada: é a única vez em que o segredo aparece. */
  public record AssinaturaCriada(@JsonUnwrapped WebhookAssinatura assinatura, String segredo) {}

  @Transactional
  public AssinaturaCriada criarAssinatura(AuthUser u, String modulo, String url, List<String> tipos) {
    byte[] b = new byte[24];
    ALEATORIO.nextBytes(b);
    WebhookAssinatura a = new WebhookAssinatura();
    a.setModulo(modulo);
    a.setUrl(url);
    a.setEventos(new ArrayList<>(tipos));
    a.setSegredo(HexFormat.of().formatHex(b));
    a.auditarCriacao(u.tenantId(), u.sub());
    a = assinaturas.save(a);
    // O segredo é exibido UMA única vez, para o assinante validar a assinatura HMAC das entregas.
    return new AssinaturaCriada(a, a.getSegredo());
  }

  @Transactional(readOnly = true)
  public List<WebhookAssinatura> listarAssinaturas(AuthUser u) {
    return assinaturas.findByTenantIdOrderByCreatedAtDesc(u.tenantId());
  }

  @Transactional
  public void removerAssinatura(AuthUser u, UUID id) {
    WebhookAssinatura a = assinaturas.findByIdAndTenantId(id, u.tenantId())
        .orElseThrow(() -> ApiException.naoEncontrado("Assinatura não encontrada"));
    a.setAtiva(false);
    a.excluir(u.sub(), relogio.agora());
  }

  public record ResultadoDespacho(int processados, int entregues, int falhas) {}

  public ResultadoDespacho despachar() {
    return despachar(50);
  }

  /**
   * Despachante: entrega os eventos pendentes aos assinantes via HTTP POST assinado (HMAC-SHA256),
   * com nova tentativa em backoff exponencial. Comunicação máquina-a-máquina, sem operador.
   */
  public ResultadoDespacho despachar(int lote) {
    List<Evento> pendentes = eventos.findByStatusAndProximaTentativaLessThanEqualOrderByCreatedAtAsc(
        "pendente", relogio.agora(), PageRequest.of(0, lote));
    int entregues = 0;
    int falhas = 0;

    for (Evento ev : pendentes) {
      List<WebhookAssinatura> destinos = assinaturas.destinos(ev.getTenantId(), ev.getTipo());
      if (destinos.isEmpty()) {
        ev.setStatus("sem_assinantes");
        eventos.save(ev);
        continue;
      }

      Map<String, Object> corpoMapa = new LinkedHashMap<>();
      corpoMapa.put("id", ev.getId());
      corpoMapa.putAll(ev.getPayload());
      String corpo;
      try {
        corpo = json.writeValueAsString(corpoMapa);
      } catch (JsonProcessingException e) {
        throw new IllegalStateException(e);
      }

      List<String> erros = Collections.synchronizedList(new ArrayList<>());
      CompletableFuture.allOf(destinos.stream().map(d -> entregar(d, ev.getTipo(), corpo, erros)).toArray(CompletableFuture[]::new)).join();

      int tentativas = ev.getTentativas() + 1;
      ev.setTentativas(tentativas);
      if (erros.isEmpty()) {
        entregues++;
        ev.setStatus("entregue");
        ev.setEntregueEm(relogio.agora());
      } else {
        falhas++;
        long atrasoSeg = Math.min((long) Math.pow(2, tentativas) * 30, 3600);
        String erro = String.join("; ", erros);
        ev.setStatus(tentativas >= MAX_TENTATIVAS ? "falha" : "pendente");
        ev.setUltimoErro(erro.length() > 500 ? erro.substring(0, 500) : erro);
        ev.setProximaTentativa(relogio.agora().plusSeconds(atrasoSeg));
        log.warn("Evento {} ({}) falhou: {}", ev.getTipo(), ev.getId(), erro);
      }
      eventos.save(ev);
    }
    return new ResultadoDespacho(pendentes.size(), entregues, falhas);
  }

  private CompletableFuture<Void> entregar(WebhookAssinatura d, String tipo, String corpo, List<String> erros) {
    HttpRequest req;
    try {
      req = HttpRequest.newBuilder(URI.create(d.getUrl()))
          .timeout(Duration.ofSeconds(5))
          .header("content-type", "application/json")
          .header("x-financeiro-evento", tipo)
          .header("x-financeiro-assinatura", "sha256=" + hmacSha256(d.getSegredo(), corpo))
          .POST(HttpRequest.BodyPublishers.ofString(corpo, StandardCharsets.UTF_8))
          .build();
    } catch (IllegalArgumentException e) {
      erros.add(d.getModulo() + ": URL inválida");
      return CompletableFuture.completedFuture(null);
    }
    return http.sendAsync(req, HttpResponse.BodyHandlers.discarding())
        .handle((r, e) -> {
          if (e != null) erros.add(d.getModulo() + ": " + (e.getCause() != null ? e.getCause() : e));
          else if (r.statusCode() >= 300) erros.add(d.getModulo() + ": HTTP " + r.statusCode());
          return null;
        });
  }

  public static String hmacSha256(String segredo, String corpo) {
    return hmacSha256(segredo, corpo.getBytes(StandardCharsets.UTF_8));
  }

  public static String hmacSha256(String segredo, byte[] corpo) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(segredo.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(corpo));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public record ContagemStatus(String status, long total) {}

  @Transactional(readOnly = true)
  public List<ContagemStatus> contarPorStatus(UUID tenant) {
    return eventos.contarPorStatus(tenant).stream().map(l -> new ContagemStatus((String) l[0], (Long) l[1])).toList();
  }
}
