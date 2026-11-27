package br.com.erp.financeiro.comissoes;

import br.com.erp.financeiro.comum.Dinheiro;
import br.com.erp.financeiro.comum.EntidadeBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Comissão apurada sobre um pagamento recebido (§71). Fechar o mês lança em contas a pagar. */
@Entity
@Table(name = "comissoes")
public class Comissao extends EntidadeBase {

  public enum Status { A_PAGAR, LANCADA, CANCELADA }

  @Column(name = "regra_id", nullable = false)
  private UUID regraId;

  @Enumerated(EnumType.STRING)
  @Column(name = "beneficiario_tipo", nullable = false)
  private RegraComissao.Beneficiario beneficiarioTipo;

  @Column(name = "beneficiario_id", nullable = false)
  private UUID beneficiarioId;

  @Column(name = "cobranca_id", nullable = false)
  private UUID cobrancaId;

  @Column(name = "pagamento_id", nullable = false)
  private UUID pagamentoId;

  @Column(name = "empresa_id", nullable = false)
  private UUID empresaId;

  @Column(nullable = false, length = 7)
  private String competencia;

  @Column(name = "base_calculo", nullable = false)
  private BigDecimal baseCalculo;

  private BigDecimal percentual;

  @Column(nullable = false)
  private BigDecimal valor;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Status status = Status.A_PAGAR;

  @Column(name = "conta_pagar_id")
  private UUID contaPagarId;

  protected Comissao() {}

  public Comissao(UUID regraId, RegraComissao.Beneficiario tipo, UUID beneficiarioId, UUID cobrancaId, UUID pagamentoId,
      UUID empresaId, String competencia, BigDecimal base, BigDecimal percentual, BigDecimal valor) {
    this.regraId = regraId;
    this.beneficiarioTipo = tipo;
    this.beneficiarioId = beneficiarioId;
    this.cobrancaId = cobrancaId;
    this.pagamentoId = pagamentoId;
    this.empresaId = empresaId;
    this.competencia = competencia;
    this.baseCalculo = Dinheiro.de(base);
    this.percentual = percentual;
    this.valor = Dinheiro.de(valor);
  }

  public void lancar(UUID contaPagarId) {
    this.contaPagarId = contaPagarId;
    this.status = Status.LANCADA;
  }

  public void cancelar() {
    this.status = Status.CANCELADA;
  }

  public Map<String, Object> instantaneo() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("regraId", regraId);
    m.put("beneficiarioTipo", beneficiarioTipo);
    m.put("beneficiarioId", beneficiarioId);
    m.put("cobrancaId", cobrancaId);
    m.put("pagamentoId", pagamentoId);
    m.put("competencia", competencia);
    m.put("baseCalculo", baseCalculo);
    m.put("percentual", percentual);
    m.put("valor", valor);
    m.put("status", status);
    m.put("contaPagarId", contaPagarId);
    return m;
  }

  public UUID getRegraId() {
    return regraId;
  }

  public RegraComissao.Beneficiario getBeneficiarioTipo() {
    return beneficiarioTipo;
  }

  public UUID getBeneficiarioId() {
    return beneficiarioId;
  }

  public UUID getCobrancaId() {
    return cobrancaId;
  }

  public UUID getPagamentoId() {
    return pagamentoId;
  }

  public UUID getEmpresaId() {
    return empresaId;
  }

  public String getCompetencia() {
    return competencia;
  }

  public BigDecimal getBaseCalculo() {
    return baseCalculo;
  }

  public BigDecimal getPercentual() {
    return percentual;
  }

  public BigDecimal getValor() {
    return valor;
  }

  public Status getStatus() {
    return status;
  }

  public UUID getContaPagarId() {
    return contaPagarId;
  }
}
