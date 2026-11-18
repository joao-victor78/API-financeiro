package br.com.erp.financeiro.conciliacoes;

import br.com.erp.financeiro.comum.EntidadeBase;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.SQLRestriction;

/** Uma importação de extrato OFX e o resumo da conciliação automática. */
@Getter
@Setter
@Entity
@Table(name = "conciliacoes")
@SQLRestriction("deleted_at IS NULL")
public class Conciliacao extends EntidadeBase {

  private String arquivoNome;
  private String bancoId;
  private String contaId;
  private LocalDate periodoInicio;
  private LocalDate periodoFim;
  private int totalTransacoes;
  private int conciliadasAutomaticamente;
  private int pendentes;
  private int ignoradasDuplicadas;
}
