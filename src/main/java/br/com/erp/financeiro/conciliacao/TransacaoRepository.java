package br.com.erp.financeiro.conciliacao;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface TransacaoRepository extends JpaRepository<TransacaoBancaria, UUID>, JpaSpecificationExecutor<TransacaoBancaria> {

  @Query("select t from TransacaoBancaria t where t.deletedAt is null and t.contaBancariaId = :conta and t.identificador = :id")
  Optional<TransacaoBancaria> doIdentificador(UUID conta, String id);

  @Query("select t from TransacaoBancaria t where t.deletedAt is null and t.status = br.com.erp.financeiro.conciliacao.TransacaoBancaria.Status.PENDENTE order by t.data")
  List<TransacaoBancaria> pendentes();

  @Query("select t from TransacaoBancaria t where t.deletedAt is null and t.status = br.com.erp.financeiro.conciliacao.TransacaoBancaria.Status.PENDENTE and t.contaBancariaId = :conta order by t.data")
  List<TransacaoBancaria> pendentesDaConta(UUID conta);

  @Query("select count(t) from TransacaoBancaria t where t.deletedAt is null and t.status = br.com.erp.financeiro.conciliacao.TransacaoBancaria.Status.PENDENTE")
  long contarPendentes();

  @Query("select t from TransacaoBancaria t where t.deletedAt is null and t.pagamentoId = :pagamento")
  Optional<TransacaoBancaria> doPagamento(UUID pagamento);

  @Query("select t from TransacaoBancaria t where t.deletedAt is null and t.contaPagarId = :conta")
  Optional<TransacaoBancaria> daContaPagar(UUID conta);
}
