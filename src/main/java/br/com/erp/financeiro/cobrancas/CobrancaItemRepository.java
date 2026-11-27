package br.com.erp.financeiro.cobrancas;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CobrancaItemRepository extends JpaRepository<CobrancaItem, UUID> {

  @Query("select i from CobrancaItem i where i.cobrancaId = :cobranca and i.deletedAt is null order by i.tipo, i.createdAt, i.id")
  List<CobrancaItem> daCobranca(UUID cobranca);

  @Query("select i from CobrancaItem i where i.cobrancaId in :cobrancas and i.deletedAt is null")
  List<CobrancaItem> dasCobrancas(Collection<UUID> cobrancas);
}
