package br.com.erp.financeiro.cobrancas;

import br.com.erp.financeiro.comum.Dinheiro;
import br.com.erp.financeiro.comum.EntidadeBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Linha da composição de uma cobrança gerada pela recorrência (§26): CONTRATO + SERVIÇOS + QUANTIDADE +
 * CONSUMO + ADICIONAIS. Cobrança lançada à mão não tem composição (o valor é o digitado).
 */
@Entity
@Table(name = "cobranca_itens")
public class CobrancaItem extends EntidadeBase {

  public enum Tipo { FIXO, EXCEDENTE, ADICIONAL }

  @Column(name = "cobranca_id", nullable = false)
  private UUID cobrancaId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Tipo tipo;

  @Column(name = "servico_id")
  private UUID servicoId;

  private String fabricante;

  @Column(nullable = false)
  private String descricao;

  @Column(nullable = false)
  private BigDecimal quantidade;

  private String unidade;

  @Column(name = "valor_unitario", nullable = false)
  private BigDecimal valorUnitario;

  @Column(nullable = false)
  private BigDecimal total;

  @Column(name = "custo_total")
  private BigDecimal custoTotal;

  protected CobrancaItem() {}

  public CobrancaItem(UUID cobrancaId, Tipo tipo, UUID servicoId, String fabricante, String descricao, BigDecimal quantidade,
      String unidade, BigDecimal valorUnitario, BigDecimal total, BigDecimal custoTotal) {
    this.cobrancaId = cobrancaId;
    this.tipo = tipo;
    this.servicoId = servicoId;
    this.fabricante = fabricante;
    this.descricao = descricao.length() > 250 ? descricao.substring(0, 250) : descricao;
    this.quantidade = quantidade;
    this.unidade = unidade;
    this.valorUnitario = Dinheiro.de(valorUnitario);
    this.total = Dinheiro.de(total);
    this.custoTotal = custoTotal == null ? null : Dinheiro.de(custoTotal);
  }

  public UUID getCobrancaId() {
    return cobrancaId;
  }

  public Tipo getTipo() {
    return tipo;
  }

  public UUID getServicoId() {
    return servicoId;
  }

  public String getFabricante() {
    return fabricante;
  }

  public String getDescricao() {
    return descricao;
  }

  public BigDecimal getQuantidade() {
    return quantidade;
  }

  public String getUnidade() {
    return unidade;
  }

  public BigDecimal getValorUnitario() {
    return valorUnitario;
  }

  public BigDecimal getTotal() {
    return total;
  }

  public BigDecimal getCustoTotal() {
    return custoTotal;
  }
}
