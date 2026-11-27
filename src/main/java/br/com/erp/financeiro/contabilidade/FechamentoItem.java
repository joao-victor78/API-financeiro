package br.com.erp.financeiro.contabilidade;

import br.com.erp.financeiro.comum.EntidadeBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Item do checklist do fechamento (§35). */
@Entity
@Table(name = "fechamento_itens")
public class FechamentoItem extends EntidadeBase {

  @Column(name = "fechamento_id", nullable = false)
  private UUID fechamentoId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private FechamentoContabil.Item item;

  @Column(nullable = false)
  private int ordem;

  @Column(nullable = false)
  private boolean concluido;

  @Column(name = "concluido_por")
  private UUID concluidoPor;

  @Column(name = "concluido_em")
  private OffsetDateTime concluidoEm;

  private String observacao;

  protected FechamentoItem() {}

  public FechamentoItem(UUID fechamentoId, FechamentoContabil.Item item) {
    this.fechamentoId = fechamentoId;
    this.item = item;
    this.ordem = item.ordinal() + 1;
  }

  public void marcar(boolean concluido, String observacao, UUID usuario, OffsetDateTime quando) {
    this.concluido = concluido;
    this.observacao = observacao;
    this.concluidoPor = concluido ? usuario : null;
    this.concluidoEm = concluido ? quando : null;
  }

  public UUID getFechamentoId() {
    return fechamentoId;
  }

  public FechamentoContabil.Item getItem() {
    return item;
  }

  public int getOrdem() {
    return ordem;
  }

  public boolean isConcluido() {
    return concluido;
  }

  public UUID getConcluidoPor() {
    return concluidoPor;
  }

  public OffsetDateTime getConcluidoEm() {
    return concluidoEm;
  }

  public String getObservacao() {
    return observacao;
  }
}
