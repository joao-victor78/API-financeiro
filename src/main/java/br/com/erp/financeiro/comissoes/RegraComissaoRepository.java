package br.com.erp.financeiro.comissoes;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RegraComissaoRepository extends JpaRepository<RegraComissao, UUID> {

  @Query("select r from RegraComissao r where r.deletedAt is null order by r.beneficiarioTipo, r.prioridade, r.nome")
  List<RegraComissao> ordenadas();

  @Query("select r from RegraComissao r where r.deletedAt is null and r.ativa = true and r.beneficiarioTipo = :tipo")
  List<RegraComissao> ativasDoTipo(RegraComissao.Beneficiario tipo);
}
