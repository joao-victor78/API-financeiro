package br.com.erp.financeiro.conciliacoes;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConciliacaoRepository extends JpaRepository<Conciliacao, UUID> {

  Optional<Conciliacao> findByIdAndTenantId(UUID id, UUID tenantId);

  Page<Conciliacao> findByTenantId(UUID tenantId, Pageable p);
}
