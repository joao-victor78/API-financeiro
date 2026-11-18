package br.com.erp.financeiro.pix;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

/** Registro idempotente das notificações Pix recebidas (endToEndId é único no SPI). */
@Getter
@Setter
@Entity
@Table(name = "pix_recebimentos")
public class PixRecebimento {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  private UUID tenantId;
  private String endToEndId;
  private String txid;
  private BigDecimal valor;
  private Instant horario;
  private UUID contaReceberId;

  @CreationTimestamp
  @Column(updatable = false)
  private Instant createdAt;
}
