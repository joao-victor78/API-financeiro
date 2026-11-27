package br.com.erp.financeiro.comissoes;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface ComissaoRepository extends JpaRepository<Comissao, UUID>, JpaSpecificationExecutor<Comissao> {

  @Query("select c from Comissao c where c.deletedAt is null and c.pagamentoId = :pagamento")
  List<Comissao> doPagamento(UUID pagamento);

  @Query("select count(c) from Comissao c where c.deletedAt is null and c.status <> br.com.erp.financeiro.comissoes.Comissao.Status.CANCELADA and c.regraId = :regra and c.beneficiarioId = :beneficiario and c.cobrancaId in :cobrancas")
  long jaPagasNasCobrancas(UUID regra, UUID beneficiario, List<UUID> cobrancas);

  @Query("select c from Comissao c where c.deletedAt is null and c.status = br.com.erp.financeiro.comissoes.Comissao.Status.A_PAGAR and c.competencia = :competencia order by c.beneficiarioTipo, c.beneficiarioId")
  List<Comissao> aPagarNaCompetencia(String competencia);
}
