package br.com.erp.financeiro.eventos;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface EventoRepository extends JpaRepository<Evento, UUID> {

  Page<Evento> findByTenantId(UUID tenantId, Pageable p);

  Page<Evento> findByTenantIdAndTipo(UUID tenantId, String tipo, Pageable p);

  List<Evento> findByStatusAndProximaTentativaLessThanEqualOrderByCreatedAtAsc(String status, Instant ate, Pageable p);

  @Query("select e.status, count(e) from Evento e where e.tenantId = :tenant group by e.status")
  List<Object[]> contarPorStatus(UUID tenant);
}
