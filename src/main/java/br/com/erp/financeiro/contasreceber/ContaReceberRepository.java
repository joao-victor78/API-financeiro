package br.com.erp.financeiro.contasreceber;

import br.com.erp.financeiro.comum.enums.StatusContaReceber;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.hibernate.jpa.HibernateHints;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

public interface ContaReceberRepository extends JpaRepository<ContaReceber, UUID>, JpaSpecificationExecutor<ContaReceber> {

  Optional<ContaReceber> findByIdAndTenantId(UUID id, UUID tenantId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select c from ContaReceber c where c.id = :id and c.tenantId = :tenant")
  Optional<ContaReceber> travar(UUID id, UUID tenant);

  /** Webhook Pix: o banco só informa o txid (sem tenant). */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select c from ContaReceber c where c.pixTxid = :txid")
  Optional<ContaReceber> travarPorTxid(String txid);

  Optional<ContaReceber> findFirstByCobrancaIdAndTenantId(UUID cobrancaId, UUID tenantId);

  List<ContaReceber> findByTenantIdAndStatusIn(UUID tenantId, Collection<StatusContaReceber> status);

  /** Job de vencimentos: contas em aberto cujo vencimento passou (com bloqueio). */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select c from ContaReceber c where c.status in :status and c.dataVencimento < :hoje")
  List<ContaReceber> travarVencidas(Collection<StatusContaReceber> status, LocalDate hoje);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select c from ContaReceber c where c.status in :status and c.dataVencimento < :hoje and c.tenantId = :tenant")
  List<ContaReceber> travarVencidas(Collection<StatusContaReceber> status, LocalDate hoje, UUID tenant);

  /** Dados mínimos de um título para o motor de conciliação (projeção leve, sem carregar a entidade). */
  record Candidata(UUID id, BigDecimal valor, BigDecimal desconto, BigDecimal juros, BigDecimal multa, BigDecimal valorPago,
      LocalDate dataVencimento, String pixTxid) {}

  record PixBaixado(UUID id, BigDecimal valorPago) {}

  /**
   * Conciliação: títulos em aberto perto da data do crédito ou cujo txid aparece na descrição do extrato
   * ({@code descricaoMaiuscula} já em maiúsculas). Lida sem flush: os títulos baixados nesta mesma importação
   * são descartados pelo conjunto "usados" do serviço.
   */
  @QueryHints(@QueryHint(name = HibernateHints.HINT_FLUSH_MODE, value = "COMMIT"))
  @Query("""
      select new br.com.erp.financeiro.contasreceber.ContaReceberRepository$Candidata(
        c.id, c.valor, c.desconto, c.juros, c.multa, c.valorPago, c.dataVencimento, c.pixTxid)
      from ContaReceber c
      where c.tenantId = :tenant and c.status in :status
        and (c.dataVencimento between :de and :ate or (c.pixTxid is not null and locate(upper(c.pixTxid), :descricaoMaiuscula) > 0))""")
  List<Candidata> candidatasConciliacao(UUID tenant, Collection<StatusContaReceber> status, LocalDate de, LocalDate ate,
      String descricaoMaiuscula);

  /** Pix já baixado pelo webhook que chega depois no extrato e ainda não foi vinculado a nenhuma transação. */
  @QueryHints(@QueryHint(name = HibernateHints.HINT_FLUSH_MODE, value = "COMMIT"))
  @Query("""
      select new br.com.erp.financeiro.contasreceber.ContaReceberRepository$PixBaixado(c.id, c.valorPago)
      from ContaReceber c
      where c.tenantId = :tenant and c.status = br.com.erp.financeiro.comum.enums.StatusContaReceber.PAGA
        and c.pixTxid is not null and locate(upper(c.pixTxid), :descricaoMaiuscula) > 0
        and not exists (select 1 from ExtratoTransacao e where e.contaReceberId = c.id and e.status = 'conciliada')""")
  List<PixBaixado> pixJaBaixadoSemVinculo(UUID tenant, String descricaoMaiuscula);
}
