package br.com.erp.financeiro.recorrencias;

import br.com.erp.financeiro.cobrancas.ItemCobranca;
import br.com.erp.financeiro.comum.EntidadeBase;
import br.com.erp.financeiro.comum.enums.FormaPagamento;
import br.com.erp.financeiro.comum.enums.Periodicidade;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.type.SqlTypes;

/** Contrato recorrente: a cada ciclo o agendador emite a cobrança sozinho. */
@Getter
@Setter
@Entity
@Table(name = "recorrencias")
@SQLRestriction("deleted_at IS NULL")
public class Recorrencia extends EntidadeBase {

  private UUID clienteId;
  private UUID contratoId;
  private String descricao;
  private Periodicidade periodicidade = Periodicidade.MENSAL;
  private int diaVencimento;
  private int diasAntecedencia = 10;
  private LocalDate dataInicio;
  private LocalDate dataFim;
  /** Próxima competência (AAAA-MM) a ser cobrada. */
  private String proximaCompetencia;
  private FormaPagamento formaPagamento = FormaPagamento.PIX;
  private boolean aprovacaoAutomatica = true;
  private boolean ativa = true;

  @JdbcTypeCode(SqlTypes.JSON)
  private List<ItemCobranca> itens = new ArrayList<>();
}
