package br.com.erp.financeiro.conciliacao;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ExtratoRepository extends JpaRepository<ExtratoImportado, UUID> {

  @Query("select e from ExtratoImportado e where e.deletedAt is null and e.contaBancariaId = :conta and e.sha256 = :sha")
  Optional<ExtratoImportado> mesmoArquivo(UUID conta, String sha);

  @Query("select e from ExtratoImportado e where e.deletedAt is null order by e.createdAt desc")
  List<ExtratoImportado> recentes(Pageable limite);
}
