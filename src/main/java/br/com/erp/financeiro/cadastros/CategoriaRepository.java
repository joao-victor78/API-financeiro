package br.com.erp.financeiro.cadastros;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CategoriaRepository extends JpaRepository<CategoriaFinanceira, UUID> {

  @Query("select c from CategoriaFinanceira c where c.deletedAt is null order by c.tipo, c.nome")
  List<CategoriaFinanceira> ordenadas();

  @Query("select c from CategoriaFinanceira c where c.deletedAt is null and c.tipo = :tipo and lower(c.nome) = lower(:nome)")
  Optional<CategoriaFinanceira> porNome(CategoriaFinanceira.Tipo tipo, String nome);

  @Query("select c from CategoriaFinanceira c where c.id in :ids")
  List<CategoriaFinanceira> porIds(Collection<UUID> ids);
}
