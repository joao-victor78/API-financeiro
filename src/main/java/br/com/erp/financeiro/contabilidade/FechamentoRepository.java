package br.com.erp.financeiro.contabilidade;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface FechamentoRepository extends JpaRepository<FechamentoContabil, UUID> {

  @Query("select f from FechamentoContabil f where f.deletedAt is null and f.competencia = :competencia")
  Optional<FechamentoContabil> daCompetencia(String competencia);

  @Query("select f from FechamentoContabil f where f.deletedAt is null order by f.competencia desc")
  List<FechamentoContabil> todos();
}
