package br.com.erp.financeiro.recorrencias;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ReajusteRepository extends JpaRepository<Reajuste, UUID> {

  @Query("select r from Reajuste r where r.deletedAt is null and r.recorrenciaId = :regra order by r.createdAt desc")
  List<Reajuste> daRegra(UUID regra);

  @Query("select count(r) from Reajuste r where r.deletedAt is null and r.recorrenciaId = :regra and r.createdAt >= :desde")
  long desde(UUID regra, OffsetDateTime desde);
}
