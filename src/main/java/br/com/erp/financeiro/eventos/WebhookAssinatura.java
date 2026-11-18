package br.com.erp.financeiro.eventos;

import br.com.erp.financeiro.comum.EntidadeBase;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.type.SqlTypes;

/** Outro módulo (ou sistema externo) se inscreve para ser avisado automaticamente de eventos financeiros. */
@Getter
@Setter
@Entity
@Table(name = "webhook_assinaturas")
@SQLRestriction("deleted_at IS NULL")
public class WebhookAssinatura extends EntidadeBase {

  private String modulo;
  private String url;

  @JdbcTypeCode(SqlTypes.ARRAY)
  private List<String> eventos;

  /** Usado para assinar (HMAC) cada entrega. Nunca sai nas listagens. */
  @JsonIgnore
  private String segredo;

  private boolean ativa = true;
}
