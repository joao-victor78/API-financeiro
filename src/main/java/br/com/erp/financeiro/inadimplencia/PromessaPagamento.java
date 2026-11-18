package br.com.erp.financeiro.inadimplencia;

import br.com.erp.financeiro.comum.EntidadeBase;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.SQLRestriction;

/** Compromisso do cliente inadimplente de pagar em uma data. Cumprida ou quebrada automaticamente. */
@Getter
@Setter
@Entity
@Table(name = "promessas_pagamento")
@SQLRestriction("deleted_at IS NULL")
public class PromessaPagamento extends EntidadeBase {

  private UUID contaReceberId;
  private LocalDate dataPrometida;
  private BigDecimal valor;
  private String observacao;
  /** pendente | cumprida | quebrada */
  private String status = "pendente";
}
