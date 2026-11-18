package br.com.erp.financeiro.cobrancas;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface CobrancaRepository extends JpaRepository<Cobranca, UUID>, JpaSpecificationExecutor<Cobranca> {

  Optional<Cobranca> findByIdAndTenantId(UUID id, UUID tenantId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select c from Cobranca c where c.id = :id and c.tenantId = :tenant")
  Optional<Cobranca> travar(UUID id, UUID tenant);

  long countByTenantId(UUID tenantId);
}
