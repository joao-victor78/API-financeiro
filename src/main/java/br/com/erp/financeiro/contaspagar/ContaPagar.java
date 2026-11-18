package br.com.erp.financeiro.contaspagar;

import br.com.erp.financeiro.comum.EntidadeBase;
import br.com.erp.financeiro.comum.enums.FormaPagamento;
import br.com.erp.financeiro.comum.enums.StatusContaPagar;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.SQLRestriction;

/** Fornecedor, centro de custo e categoria são UUIDs de outros módulos (sem FK entre schemas). */
@Getter
@Setter
@Entity
@Table(name = "contas_pagar")
@SQLRestriction("deleted_at IS NULL")
public class ContaPagar extends EntidadeBase {

  private UUID fornecedorId;
  private UUID centroCustoId;
  private UUID categoriaId;
  private String descricao;
  private String documento;
  private String competencia;
  private LocalDate dataVencimento;
  private BigDecimal valor;
  private BigDecimal desconto = BigDecimal.ZERO;
  private BigDecimal juros = BigDecimal.ZERO;
  private BigDecimal multa = BigDecimal.ZERO;
  private BigDecimal valorPago = BigDecimal.ZERO;
  private LocalDate dataPagamento;
  private FormaPagamento formaPagamento;
  private StatusContaPagar status = StatusContaPagar.EM_ABERTO;
}
