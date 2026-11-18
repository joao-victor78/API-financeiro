package br.com.erp.financeiro.contasreceber;

import br.com.erp.financeiro.comum.EntidadeBase;
import br.com.erp.financeiro.comum.enums.FormaPagamento;
import br.com.erp.financeiro.comum.enums.StatusContaReceber;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.SQLRestriction;

@Getter
@Setter
@Entity
@Table(name = "contas_receber")
@SQLRestriction("deleted_at IS NULL")
public class ContaReceber extends EntidadeBase {

  private UUID clienteId;
  private UUID contratoId;
  private UUID cobrancaId;
  private String descricao;
  private String competencia;
  private LocalDate dataVencimento;
  private BigDecimal valor;
  private BigDecimal desconto = BigDecimal.ZERO;
  private BigDecimal juros = BigDecimal.ZERO;
  private BigDecimal multa = BigDecimal.ZERO;
  private BigDecimal valorPago = BigDecimal.ZERO;
  private LocalDate dataPagamento;
  private FormaPagamento formaPagamento;
  private StatusContaReceber status = StatusContaReceber.EM_ABERTO;
  private String pixTxid;
}
