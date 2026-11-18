package br.com.erp.financeiro.cobrancas;

import br.com.erp.financeiro.comum.Dinheiro;
import br.com.erp.financeiro.comum.EntidadeBase;
import br.com.erp.financeiro.comum.enums.FormaPagamento;
import br.com.erp.financeiro.comum.enums.StatusCobranca;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.type.SqlTypes;

@Getter
@Setter
@Entity
@Table(name = "cobrancas")
@SQLRestriction("deleted_at IS NULL")
public class Cobranca extends EntidadeBase {

  private UUID clienteId;
  private UUID contratoId;
  private UUID recorrenciaId;
  private String competencia;
  private String descricao;

  @JdbcTypeCode(SqlTypes.JSON)
  private List<ItemCobranca> itens = new ArrayList<>();

  private BigDecimal valorTotal;
  private LocalDate dataVencimento;
  private FormaPagamento formaPagamento;
  private StatusCobranca status = StatusCobranca.RASCUNHO;
  private String pixTxid;
  private String pixCopiaCola;
  private Instant aprovadaEm;
  private UUID aprovadaPor;
  private Instant canceladaEm;
  private String motivoCancelamento;

  /** Soma dos itens em centavos (quantidade × valor unitário, arredondado por item). */
  public static BigDecimal totalItens(List<ItemCobranca> itens) {
    long total = 0;
    for (ItemCobranca i : itens) total += Dinheiro.arredondar(i.quantidade().multiply(BigDecimal.valueOf(Dinheiro.centavos(i.valorUnitario()))));
    return Dinheiro.reais(total);
  }
}
