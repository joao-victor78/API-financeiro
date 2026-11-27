package br.com.erp.financeiro.webhooks.saida;

import br.com.erp.financeiro.eventos.TiposDeEvento;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Enfileira a entrega do evento para cada assinatura ativa do tenant que o assina (§91). Roda na mesma
 * transação do outbox: se o fato desfaz, a entrega também. Um evento por assinatura (UNIQUE), então
 * reenviar ou reprocessar não duplica.
 */
@Component
public class FilaWebhooks {

  private final JdbcTemplate jdbc;

  public FilaWebhooks(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public int enfileirar(UUID tenant, UUID eventoId, String tipo, String payload, Instant agora) {
    if (!TiposDeEvento.ASSINAVEIS.containsKey(tipo)) return 0;
    Timestamp t = Timestamp.from(agora);
    return jdbc.update("""
        INSERT INTO financeiro.webhook_entregas (id, tenant_id, assinatura_id, evento_id, tipo, payload, status, tentativas,
               proxima_tentativa, criado_em)
        SELECT gen_random_uuid(), w.tenant_id, w.id, ?, ?, CAST(? AS jsonb), 'PENDENTE', 0, ?, ?
          FROM financeiro.webhook_assinaturas w
         WHERE w.tenant_id = ? AND w.deleted_at IS NULL AND w.ativa
           AND (w.eventos = '*' OR ? = ANY (string_to_array(replace(w.eventos, ' ', ''), ',')))
        ON CONFLICT (assinatura_id, evento_id) DO NOTHING""",
        eventoId, tipo, payload, t, t, tenant, tipo);
  }
}
