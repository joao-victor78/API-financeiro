package br.com.erp.financeiro.eventos;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Outbox transacional: o evento é gravado na MESMA transação da mudança de estado. */
@Getter
@Setter
@Entity
@Table(name = "eventos")
public class Evento {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  private UUID tenantId;
  private String tipo;
  private UUID agregadoId;

  @JdbcTypeCode(SqlTypes.JSON)
  private Map<String, Object> payload;

  /** pendente | entregue | sem_assinantes | falha */
  private String status = "pendente";
  private int tentativas;
  private Instant proximaTentativa;
  private String ultimoErro;
  private Instant entregueEm;

  @CreationTimestamp
  @Column(updatable = false)
  private Instant createdAt;
}
