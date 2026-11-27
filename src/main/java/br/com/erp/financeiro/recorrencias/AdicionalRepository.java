package br.com.erp.financeiro.recorrencias;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AdicionalRepository extends JpaRepository<RecorrenciaAdicional, UUID> {

  @Query("select a from RecorrenciaAdicional a where a.deletedAt is null and a.recorrenciaId = :regra order by a.competencia desc, a.createdAt desc")
  List<RecorrenciaAdicional> daRegra(UUID regra);

  @Query("""
      select a from RecorrenciaAdicional a where a.deletedAt is null and a.recorrenciaId = :regra
         and a.cobrancaId is null and a.competencia <= :competencia order by a.competencia, a.createdAt""")
  List<RecorrenciaAdicional> aFaturar(UUID regra, String competencia);
}
