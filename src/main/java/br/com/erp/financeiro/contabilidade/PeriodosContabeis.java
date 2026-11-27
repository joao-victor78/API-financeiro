package br.com.erp.financeiro.contabilidade;

import br.com.erp.financeiro.api.ApiException;
import br.com.erp.financeiro.tenant.TenantContexto;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Trava de período (§35): depois que o mês foi enviado à contabilidade, lançamentos daquela competência
 * não mudam, para o que o escritório recebeu continuar valendo. Se a contabilidade devolver o mês
 * (COM_PENDENCIA), ele destrava. Sem fechamento registrado, nada muda (comportamento anterior).
 */
@Component
public class PeriodosContabeis {

  static final Set<String> TRAVADOS = Set.of("ENVIADO_PARA_CONTABILIDADE", "EM_ANALISE", "FINALIZADO");

  private final JdbcTemplate jdbc;

  public PeriodosContabeis(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public boolean travado(String competencia) {
    if (competencia == null) return false;
    List<String> s = jdbc.queryForList("""
        SELECT status FROM financeiro.fechamentos_contabeis
         WHERE tenant_id = ? AND competencia = ? AND deleted_at IS NULL""", String.class, TenantContexto.exigir(), competencia);
    return !s.isEmpty() && TRAVADOS.contains(s.get(0));
  }

  /** 422 PERIODO_FECHADO quando a competência já está com a contabilidade. */
  public void exigirAberto(String competencia) {
    if (travado(competencia)) {
      throw ApiException.regra("PERIODO_FECHADO", "A competência " + competencia.substring(5) + "/" + competencia.substring(0, 4)
          + " já foi enviada à contabilidade; para alterar, a contabilidade precisa devolver o fechamento.");
    }
  }
}
