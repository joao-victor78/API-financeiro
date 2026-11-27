package br.com.erp.financeiro.recorrencias;

import br.com.erp.financeiro.comum.EntidadeBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Consumo medido de um serviço numa competência (§41). Entra como excedente na próxima cobrança gerada. */
@Entity
@Table(name = "recorrencia_consumos")
public class RecorrenciaConsumo extends EntidadeBase {

  public enum Origem { MANUAL, INTEGRACAO }

  @Column(name = "recorrencia_id", nullable = false)
  private UUID recorrenciaId;

  @Column(name = "servico_id", nullable = false)
  private UUID servicoId;

  @Column(nullable = false, length = 7)
  private String competencia;

  @Column(nullable = false)
  private BigDecimal quantidade;

  @Column(nullable = false)
  @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
  private Origem origem = Origem.MANUAL;

  private String observacao;

  /** Cobrança em que o excedente foi faturado; enquanto nula, o consumo pode ser corrigido. */
  @Column(name = "cobranca_id")
  private UUID cobrancaId;

  @Column(name = "alertado_em")
  private OffsetDateTime alertadoEm;

  protected RecorrenciaConsumo() {}

  public RecorrenciaConsumo(UUID recorrenciaId, UUID servicoId, String competencia) {
    this.recorrenciaId = recorrenciaId;
    this.servicoId = servicoId;
    this.competencia = competencia;
  }

  public void registrar(BigDecimal quantidade, Origem origem, String observacao) {
    this.quantidade = quantidade;
    this.origem = origem;
    this.observacao = observacao;
  }

  public void faturar(UUID cobrancaId) {
    this.cobrancaId = cobrancaId;
  }

  public void marcarAlertado(OffsetDateTime quando) {
    this.alertadoEm = quando;
  }

  public Map<String, Object> instantaneo() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("recorrenciaId", recorrenciaId);
    m.put("servicoId", servicoId);
    m.put("competencia", competencia);
    m.put("quantidade", quantidade);
    m.put("origem", origem);
    m.put("cobrancaId", cobrancaId);
    return m;
  }

  public UUID getRecorrenciaId() {
    return recorrenciaId;
  }

  public UUID getServicoId() {
    return servicoId;
  }

  public String getCompetencia() {
    return competencia;
  }

  public BigDecimal getQuantidade() {
    return quantidade;
  }

  public Origem getOrigem() {
    return origem;
  }

  public String getObservacao() {
    return observacao;
  }

  public UUID getCobrancaId() {
    return cobrancaId;
  }

  public OffsetDateTime getAlertadoEm() {
    return alertadoEm;
  }
}
