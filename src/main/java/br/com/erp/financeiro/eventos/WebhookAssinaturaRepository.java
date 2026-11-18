package br.com.erp.financeiro.eventos;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface WebhookAssinaturaRepository extends JpaRepository<WebhookAssinatura, UUID> {

  List<WebhookAssinatura> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

  Optional<WebhookAssinatura> findByIdAndTenantId(UUID id, UUID tenantId);

  /** Assinantes ativos do tenant inscritos no tipo de evento (coluna text[]). */
  @Query(nativeQuery = true, value = """
      SELECT * FROM financeiro.webhook_assinaturas a
      WHERE a.tenant_id = :tenant AND a.ativa AND a.deleted_at IS NULL AND CAST(:tipo AS text) = ANY(a.eventos)""")
  List<WebhookAssinatura> destinos(UUID tenant, String tipo);
}
