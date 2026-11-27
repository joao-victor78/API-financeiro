package br.com.erp.financeiro.webhooks.saida;

import br.com.erp.financeiro.api.ApiException;
import br.com.erp.financeiro.auditoria.AuditoriaService;
import br.com.erp.financeiro.comum.Relogio;
import br.com.erp.financeiro.comum.Tenant;
import br.com.erp.financeiro.eventos.TiposDeEvento;
import br.com.erp.financeiro.seguranca.Cifra;
import br.com.erp.financeiro.tenant.TenantContexto;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Central de webhooks de saída (§91): cadastro, segredo, histórico de entregas, reenvio e teste. */
@Service
public class WebhookSaidaService {

  private final WebhookAssinaturaRepository assinaturas;
  private final Cifra cifra;
  private final DestinoSeguro destinos;
  private final EntregadorWebhooks entregador;
  private final AuditoriaService auditoria;
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final Relogio relogio;
  private final SecureRandom aleatorio = new SecureRandom();

  public WebhookSaidaService(WebhookAssinaturaRepository assinaturas, Cifra cifra, DestinoSeguro destinos,
      EntregadorWebhooks entregador, AuditoriaService auditoria, JdbcTemplate jdbc, ObjectMapper mapper, Relogio relogio) {
    this.assinaturas = assinaturas;
    this.cifra = cifra;
    this.destinos = destinos;
    this.entregador = entregador;
    this.auditoria = auditoria;
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.relogio = relogio;
  }

  public record AssinaturaEntrada(
      @NotBlank(message = "obrigatória") @Size(max = 120, message = "até 120 caracteres") String descricao,
      @NotBlank(message = "obrigatória") @Size(max = 500, message = "até 500 caracteres") String url,
      @NotEmpty(message = "escolha ao menos um evento") List<String> eventos,
      Boolean ativa) {}

  /** O segredo só aparece na criação e na troca; depois fica cifrado (§85). */
  public record AssinaturaSaida(UUID id, String descricao, String url, List<String> eventos, boolean ativa, String segredo,
      OffsetDateTime createdAt, long entregues, long pendentes, long falhas) {}

  public record Entrega(UUID id, UUID assinaturaId, UUID eventoId, String tipo, String status, int tentativas, Integer ultimoStatusHttp,
      String ultimoErro, OffsetDateTime proximaTentativa, OffsetDateTime entregueEm, OffsetDateTime criadoEm) {}

  @io.swagger.v3.oas.annotations.media.Schema(name = "EventoAssinavel")
  public record Evento(String tipo, String apelido) {}

  public List<Evento> eventosDisponiveis() {
    return TiposDeEvento.ASSINAVEIS.entrySet().stream().sorted(Map.Entry.comparingByKey())
        .map(e -> new Evento(e.getKey(), e.getValue())).toList();
  }

  @Transactional(readOnly = true)
  public List<AssinaturaSaida> listar() {
    return assinaturas.todas().stream().map(w -> saida(w, null)).toList();
  }

  @Transactional
  public AssinaturaSaida criar(AssinaturaEntrada e) {
    destinos.validar(e.url());
    String segredo = novoSegredo();
    WebhookAssinatura w = assinaturas.save(new WebhookAssinatura(e.descricao().trim(), e.url().trim(), eventos(e.eventos()), cifra.cifrar(segredo)));
    if (Boolean.FALSE.equals(e.ativa())) w.configurar(w.getDescricao(), w.getUrl(), w.getEventos(), false);
    auditoria.registrar("CRIAR", "webhook_assinatura", w.getId(), null, w.instantaneo());
    return saida(w, segredo);
  }

  @Transactional
  public AssinaturaSaida editar(UUID id, AssinaturaEntrada e) {
    WebhookAssinatura w = obter(id);
    destinos.validar(e.url());
    Map<String, Object> antes = w.instantaneo();
    w.configurar(e.descricao().trim(), e.url().trim(), eventos(e.eventos()), e.ativa() == null || e.ativa());
    auditoria.registrar("EDITAR", "webhook_assinatura", w.getId(), antes, w.instantaneo());
    return saida(w, null);
  }

  @Transactional
  public AssinaturaSaida trocarSegredo(UUID id) {
    WebhookAssinatura w = obter(id);
    String segredo = novoSegredo();
    w.trocarSegredo(cifra.cifrar(segredo));
    auditoria.registrar("TROCAR_SEGREDO", "webhook_assinatura", w.getId(), null, Map.of("descricao", w.getDescricao()));
    return saida(w, segredo);
  }

  @Transactional
  public void excluir(UUID id) {
    WebhookAssinatura w = obter(id);
    Map<String, Object> antes = w.instantaneo();
    w.excluir();
    auditoria.registrar("EXCLUIR", "webhook_assinatura", w.getId(), antes, null);
  }

  @Transactional(readOnly = true)
  public List<Entrega> entregas(UUID assinaturaId, String status) {
    StringBuilder sql = new StringBuilder("SELECT * FROM financeiro.webhook_entregas WHERE tenant_id = ?");
    List<Object> args = new java.util.ArrayList<>(List.of(TenantContexto.exigir()));
    if (assinaturaId != null) {
      sql.append(" AND assinatura_id = ?");
      args.add(assinaturaId);
    }
    if (status != null && !status.isBlank()) {
      sql.append(" AND status = ?");
      args.add(status.toUpperCase());
    }
    sql.append(" ORDER BY criado_em DESC LIMIT 200");
    return jdbc.query(sql.toString(), (rs, i) -> new Entrega(rs.getObject("id", UUID.class), rs.getObject("assinatura_id", UUID.class),
        rs.getObject("evento_id", UUID.class), rs.getString("tipo"), rs.getString("status"), rs.getInt("tentativas"),
        rs.getObject("ultimo_status_http") == null ? null : rs.getInt("ultimo_status_http"), rs.getString("ultimo_erro"),
        odt(rs.getTimestamp("proxima_tentativa")), odt(rs.getTimestamp("entregue_em")), odt(rs.getTimestamp("criado_em"))), args.toArray());
  }

  /** Volta a entrega para a fila agora (FALHOU ou PENDENTE). */
  @Transactional
  public void reenviar(UUID entregaId) {
    int n = jdbc.update("""
        UPDATE financeiro.webhook_entregas SET status = 'PENDENTE', tentativas = 0, proxima_tentativa = ?
         WHERE id = ? AND tenant_id = ? AND status <> 'ENTREGUE'""", Timestamp.from(relogio.agora()), entregaId, TenantContexto.exigir());
    if (n == 0) throw ApiException.conflito("ENTREGA_NAO_REENVIAVEL", "Entrega não encontrada ou já entregue.");
    auditoria.registrar("REENVIAR_WEBHOOK", "webhook_entrega", entregaId, null, Map.of("reenviado", true));
  }

  /** Envia um evento "financeiro.webhook.teste" agora, fora da fila, e devolve a situação da entrega. */
  @Transactional
  public Entrega testar(UUID id) {
    WebhookAssinatura w = obter(id);
    UUID evento = UUID.randomUUID();
    Map<String, Object> corpo = new LinkedHashMap<>();
    corpo.put("id", evento);
    corpo.put("tipo", "financeiro.webhook.teste");
    corpo.put("tenantId", TenantContexto.exigir());
    corpo.put("ocorridoEm", relogio.agoraUtc());
    corpo.put("dados", Map.of("mensagem", "Teste de webhook do módulo Financeiro."));
    String json;
    try {
      json = mapper.writeValueAsString(corpo);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    UUID entrega = UUID.randomUUID();
    Timestamp agora = Timestamp.from(relogio.agora());
    jdbc.update("""
        INSERT INTO financeiro.webhook_entregas (id, tenant_id, assinatura_id, evento_id, tipo, payload, status, tentativas, proxima_tentativa, criado_em)
        VALUES (?, ?, ?, ?, 'financeiro.webhook.teste', CAST(? AS jsonb), 'PENDENTE', 0, ?, ?)""",
        entrega, TenantContexto.exigir(), w.getId(), evento, json, agora, agora);
    entregador.entregar(entrega);
    return entregas(w.getId(), null).stream().filter(x -> x.id().equals(entrega)).findFirst().orElseThrow();
  }

  private WebhookAssinatura obter(UUID id) {
    return Tenant.ativoDoTenant(assinaturas.findById(id), "Webhook");
  }

  private String eventos(List<String> lista) {
    if (lista.contains("*")) return "*";
    for (String e : lista) {
      if (!TiposDeEvento.ASSINAVEIS.containsKey(e)) throw ApiException.campo("eventos", "EVENTO_DESCONHECIDO", "Evento desconhecido: " + e + ".");
    }
    return String.join(",", lista.stream().distinct().toList());
  }

  private String novoSegredo() {
    byte[] b = new byte[32];
    aleatorio.nextBytes(b);
    return "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  private AssinaturaSaida saida(WebhookAssinatura w, String segredo) {
    Map<String, Object> c = jdbc.queryForMap("""
        SELECT count(*) FILTER (WHERE status = 'ENTREGUE') AS entregues, count(*) FILTER (WHERE status = 'PENDENTE') AS pendentes,
               count(*) FILTER (WHERE status = 'FALHOU') AS falhas FROM financeiro.webhook_entregas WHERE assinatura_id = ?""", w.getId());
    return new AssinaturaSaida(w.getId(), w.getDescricao(), w.getUrl(), w.listaDeEventos(), w.isAtiva(), segredo, w.getCreatedAt(),
        ((Number) c.get("entregues")).longValue(), ((Number) c.get("pendentes")).longValue(), ((Number) c.get("falhas")).longValue());
  }

  private static OffsetDateTime odt(Timestamp t) {
    return t == null ? null : t.toInstant().atOffset(ZoneOffset.UTC);
  }
}
