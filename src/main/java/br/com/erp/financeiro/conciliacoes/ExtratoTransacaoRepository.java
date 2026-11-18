package br.com.erp.financeiro.conciliacoes;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ExtratoTransacaoRepository extends JpaRepository<ExtratoTransacao, UUID> {

  /** FITIDs já importados pelo tenant, inclusive de transações excluídas (deduplicação). */
  @Query(nativeQuery = true, value = "SELECT fitid FROM financeiro.extrato_transacoes WHERE tenant_id = :tenant AND fitid IN (:fitids)")
  List<String> fitidsExistentes(UUID tenant, Collection<String> fitids);

  List<ExtratoTransacao> findByConciliacaoIdAndTenantIdOrderByDataAscFitidAsc(UUID conciliacaoId, UUID tenantId);

  List<ExtratoTransacao> findByConciliacaoIdAndTenantIdAndStatusOrderByDataAscFitidAsc(UUID conciliacaoId, UUID tenantId, String status);

  Optional<ExtratoTransacao> findByIdAndConciliacaoIdAndTenantId(UUID id, UUID conciliacaoId, UUID tenantId);

  List<ExtratoTransacao> findByConciliacaoId(UUID conciliacaoId);
}
