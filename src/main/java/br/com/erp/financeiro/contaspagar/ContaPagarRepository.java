package br.com.erp.financeiro.contaspagar;

import br.com.erp.financeiro.comum.enums.StatusContaPagar;
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

public interface ContaPagarRepository extends JpaRepository<ContaPagar, UUID>, JpaSpecificationExecutor<ContaPagar> {

  Optional<ContaPagar> findByIdAndTenantId(UUID id, UUID tenantId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select c from ContaPagar c where c.id = :id and c.tenantId = :tenant")
  Optional<ContaPagar> travar(UUID id, UUID tenant);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select c from ContaPagar c where c.status in :status and c.dataVencimento < :hoje")
  List<ContaPagar> travarVencidas(Collection<StatusContaPagar> status, LocalDate hoje);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select c from ContaPagar c where c.status in :status and c.dataVencimento < :hoje and c.tenantId = :tenant")
  List<ContaPagar> travarVencidas(Collection<StatusContaPagar> status, LocalDate hoje, UUID tenant);

  record Candidata(UUID id, BigDecimal valor, BigDecimal desconto, BigDecimal juros, BigDecimal multa, BigDecimal valorPago,
      LocalDate dataVencimento) {}

  /** Conciliação: débitos do extrato procuram contas a pagar em aberto perto da data (projeção leve, sem flush). */
  @QueryHints(@QueryHint(name = HibernateHints.HINT_FLUSH_MODE, value = "COMMIT"))
  @Query("""
      select new br.com.erp.financeiro.contaspagar.ContaPagarRepository$Candidata(
        c.id, c.valor, c.desconto, c.juros, c.multa, c.valorPago, c.dataVencimento)
      from ContaPagar c
      where c.tenantId = :tenant and c.status in :status and c.dataVencimento between :de and :ate""")
  List<Candidata> candidatasConciliacao(UUID tenant, Collection<StatusContaPagar> status, LocalDate de, LocalDate ate);
}
