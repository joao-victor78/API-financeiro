package br.com.erp.financeiro.recorrencias;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

/**
 * Histórico de geração. A UNIQUE (recorrencia_id, competencia) é a garantia final de que a mesma
 * competência nunca é cobrada duas vezes, mesmo com várias instâncias do serviço.
 */
@Getter
@Setter
@Entity
@Table(name = "recorrencia_execucoes")
public class RecorrenciaExecucao {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  private UUID tenantId;
  private UUID recorrenciaId;
  private String competencia;
  private UUID cobrancaId;
  /** agendador | manual */
  private String origem;

  @CreationTimestamp
  @Column(updatable = false)
  private Instant executadaEm;
}
