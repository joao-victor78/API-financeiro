package br.com.erp.financeiro.recorrencias;

import br.com.erp.financeiro.comum.Dinheiro;
import br.com.erp.financeiro.comum.EntidadeBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Valor avulso que entra na próxima cobrança da regra (§26 "ADICIONAIS"): hora técnica, visita, ativação. */
@Entity
@Table(name = "recorrencia_adicionais")
public class RecorrenciaAdicional extends EntidadeBase {

  @Column(name = "recorrencia_id", nullable = false)
  private UUID recorrenciaId;

  @Column(nullable = false, length = 7)
  private String competencia;

  @Column(nullable = false)
  private String descricao;

  @Column(nullable = false)
  private BigDecimal valor;

  @Column(name = "cobranca_id")
  private UUID cobrancaId;

  protected RecorrenciaAdicional() {}

  public RecorrenciaAdicional(UUID recorrenciaId, String competencia, String descricao, BigDecimal valor) {
    this.recorrenciaId = recorrenciaId;
    this.competencia = competencia;
    this.descricao = descricao;
    this.valor = Dinheiro.de(valor);
  }

  public void faturar(UUID cobrancaId) {
    this.cobrancaId = cobrancaId;
  }

  public Map<String, Object> instantaneo() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("recorrenciaId", recorrenciaId);
    m.put("competencia", competencia);
    m.put("descricao", descricao);
    m.put("valor", valor);
    m.put("cobrancaId", cobrancaId);
    return m;
  }

  public UUID getRecorrenciaId() {
    return recorrenciaId;
  }

  public String getCompetencia() {
    return competencia;
  }

  public String getDescricao() {
    return descricao;
  }

  public BigDecimal getValor() {
    return valor;
  }

  public UUID getCobrancaId() {
    return cobrancaId;
  }
}
