package br.com.erp.financeiro.recorrencias;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ConsumoRepository extends JpaRepository<RecorrenciaConsumo, UUID> {

  @Query("""
      select c from RecorrenciaConsumo c where c.deletedAt is null and c.recorrenciaId = :regra
         and c.servicoId = :servico and c.competencia = :competencia""")
  Optional<RecorrenciaConsumo> doServico(UUID regra, UUID servico, String competencia);

  @Query("select c from RecorrenciaConsumo c where c.deletedAt is null and c.recorrenciaId = :regra order by c.competencia desc, c.servicoId")
  List<RecorrenciaConsumo> daRegra(UUID regra);

  /** Ainda não faturados, até a competência que está sendo gerada (o excedente vai na próxima cobrança). */
  @Query("""
      select c from RecorrenciaConsumo c where c.deletedAt is null and c.recorrenciaId = :regra
         and c.cobrancaId is null and c.competencia <= :competencia order by c.competencia""")
  List<RecorrenciaConsumo> aFaturar(UUID regra, String competencia);
}
