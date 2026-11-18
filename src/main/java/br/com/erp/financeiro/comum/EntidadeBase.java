package br.com.erp.financeiro.comum;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Regra técnica (i) do projeto: toda tabela de negócio possui id único, tenant e campos de auditoria,
 * com exclusão lógica (soft delete). As entidades filhas usam @SQLRestriction("deleted_at IS NULL").
 */
@Getter
@Setter
@MappedSuperclass
public abstract class EntidadeBase {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false, updatable = false)
  private UUID tenantId;

  @CreationTimestamp
  @Column(updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  private Instant updatedAt;

  private Instant deletedAt;
  private UUID createdBy;
  private UUID updatedBy;
  private UUID deletedBy;

  /** Exclusão lógica com registro de quem excluiu. */
  public void excluir(UUID autor, Instant quando) {
    this.deletedAt = quando;
    this.deletedBy = autor;
    this.updatedBy = autor;
  }

  /** Preenche tenant e autoria de um registro novo. */
  public void auditarCriacao(UUID tenant, UUID autor) {
    this.tenantId = tenant;
    this.createdBy = autor;
    this.updatedBy = autor;
  }
}
