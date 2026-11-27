package br.com.erp.financeiro.contabilidade;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface FechamentoItemRepository extends JpaRepository<FechamentoItem, UUID> {

  @Query("select i from FechamentoItem i where i.deletedAt is null and i.fechamentoId = :fechamento order by i.ordem")
  List<FechamentoItem> doFechamento(UUID fechamento);
}
