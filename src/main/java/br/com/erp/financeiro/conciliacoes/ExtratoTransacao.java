package br.com.erp.financeiro.conciliacoes;

import br.com.erp.financeiro.comum.EntidadeBase;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.SQLRestriction;

/** Linha do extrato. FITID único por tenant: reimportar o mesmo arquivo não duplica lançamentos. */
@Getter
@Setter
@Entity
@Table(name = "extrato_transacoes")
@SQLRestriction("deleted_at IS NULL")
public class ExtratoTransacao extends EntidadeBase {

  private UUID conciliacaoId;
  private String fitid;
  private LocalDate data;
  private BigDecimal valor;
  /** credito | debito */
  private String tipo;
  private String descricao;
  /** pendente | conciliada | ignorada */
  private String status = "pendente";
  private UUID contaReceberId;
  private UUID contaPagarId;
  private Integer score;
  private String criterio;
  private Instant conciliadaEm;
}
