package br.com.erp.financeiro.cadastros;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CentroCustoRepository extends JpaRepository<CentroCusto, UUID> {

  @Query("select c from CentroCusto c where c.deletedAt is null order by c.codigo")
  List<CentroCusto> ordenados();

  @Query("select c from CentroCusto c where c.deletedAt is null and lower(c.codigo) = lower(:codigo)")
  Optional<CentroCusto> porCodigo(String codigo);
}
