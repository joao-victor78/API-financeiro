package br.com.erp.financeiro.inadimplencia;

import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface PromessaPagamentoRepository extends JpaRepository<PromessaPagamento, UUID>, JpaSpecificationExecutor<PromessaPagamento> {

  /**
   * Pagamento total da conta cumpre as promessas pendentes dela. Só toca promessas_pagamento: o Hibernate
   * sincroniza sozinho o que for dessa tabela, sem forçar o flush da transação inteira a cada baixa.
   */
  @Modifying
  @Query("update PromessaPagamento p set p.status = 'cumprida', p.updatedBy = :autor where p.contaReceberId = :conta and p.status = 'pendente'")
  int cumprir(UUID conta, UUID autor);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select p from PromessaPagamento p where p.status = 'pendente' and p.dataPrometida < :hoje")
  List<PromessaPagamento> travarVencidas(LocalDate hoje);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select p from PromessaPagamento p where p.status = 'pendente' and p.dataPrometida < :hoje and p.tenantId = :tenant")
  List<PromessaPagamento> travarVencidas(LocalDate hoje, UUID tenant);
}
