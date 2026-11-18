package br.com.erp.financeiro.recorrencias;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

public interface RecorrenciaRepository extends JpaRepository<Recorrencia, UUID>, JpaSpecificationExecutor<Recorrencia> {

  Optional<Recorrencia> findByIdAndTenantId(UUID id, UUID tenantId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select r from Recorrencia r where r.id = :id and r.tenantId = :tenant")
  Optional<Recorrencia> travar(UUID id, UUID tenant);

  /**
   * Bloqueia a recorrência para gerar o ciclo; se outra instância já a bloqueou, pula (SKIP LOCKED)
   * em vez de esperar — assim várias instâncias do agendador dividem o trabalho sem duplicar.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
  @Query("select r from Recorrencia r where r.id = :id and r.ativa = true")
  List<Recorrencia> travarSeLivre(UUID id);

  /**
   * Pré-filtro no banco: o 1º dia da competência menos a antecedência é um limite inferior da data de
   * geração (o vencimento nunca é antes do dia 1). Assim só os contratos possivelmente devidos são avaliados.
   */
  @Query(nativeQuery = true, value = """
      SELECT r.id FROM financeiro.recorrencias r
      WHERE r.ativa AND r.deleted_at IS NULL
        AND (to_date(r.proxima_competencia, 'YYYY-MM') - r.dias_antecedencia) <= :hoje""")
  List<UUID> possivelmenteDevidas(LocalDate hoje);

  @Query(nativeQuery = true, value = """
      SELECT r.id FROM financeiro.recorrencias r
      WHERE r.ativa AND r.deleted_at IS NULL AND r.tenant_id = :tenant
        AND (to_date(r.proxima_competencia, 'YYYY-MM') - r.dias_antecedencia) <= :hoje""")
  List<UUID> possivelmenteDevidas(LocalDate hoje, UUID tenant);
}
