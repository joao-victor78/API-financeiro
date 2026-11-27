package br.com.erp.financeiro.recorrencias;

import br.com.erp.financeiro.comum.Dinheiro;
import br.com.erp.financeiro.comum.EntidadeBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

/** Histórico de reajuste (§69): valor anterior, índice, novo valor e data. */
@Entity
@Table(name = "reajustes")
public class Reajuste extends EntidadeBase {

  @Column(name = "recorrencia_id", nullable = false)
  private UUID recorrenciaId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private IndiceReajuste indice;

  @Column(nullable = false)
  private BigDecimal percentual;

  @Column(name = "competencia_aplicacao", nullable = false, length = 7)
  private String competenciaAplicacao;

  @Column(name = "valor_anterior", nullable = false)
  private BigDecimal valorAnterior;

  @Column(name = "valor_novo", nullable = false)
  private BigDecimal valorNovo;

  private String observacao;

  protected Reajuste() {}

  public Reajuste(UUID recorrenciaId, IndiceReajuste indice, BigDecimal percentual, String competenciaAplicacao,
      BigDecimal valorAnterior, BigDecimal valorNovo, String observacao) {
    this.recorrenciaId = recorrenciaId;
    this.indice = indice;
    this.percentual = percentual;
    this.competenciaAplicacao = competenciaAplicacao;
    this.valorAnterior = Dinheiro.de(valorAnterior);
    this.valorNovo = Dinheiro.de(valorNovo);
    this.observacao = observacao;
  }

  public UUID getRecorrenciaId() {
    return recorrenciaId;
  }

  public IndiceReajuste getIndice() {
    return indice;
  }

  public BigDecimal getPercentual() {
    return percentual;
  }

  public String getCompetenciaAplicacao() {
    return competenciaAplicacao;
  }

  public BigDecimal getValorAnterior() {
    return valorAnterior;
  }

  public BigDecimal getValorNovo() {
    return valorNovo;
  }

  public String getObservacao() {
    return observacao;
  }
}
