package br.com.erp.financeiro.cadastros;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ContaBancariaRepository extends JpaRepository<ContaBancaria, UUID> {

  @Query("select c from ContaBancaria c where c.deletedAt is null order by c.nome")
  List<ContaBancaria> ordenadas();

  @Query("select c from ContaBancaria c where c.deletedAt is null and c.ativa = true and c.padraoRecebimento = true")
  List<ContaBancaria> padraoDeRecebimento();
}
