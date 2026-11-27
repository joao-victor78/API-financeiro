package br.com.erp.financeiro.configuracoes;

import br.com.erp.financeiro.auditoria.AuditoriaService;
import br.com.erp.financeiro.comum.Relogio;
import br.com.erp.financeiro.seguranca.Usuarios;
import br.com.erp.financeiro.tenant.TenantContexto;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Parâmetros financeiros do tenant (§113: configurar sem código). Sem linha gravada valem os padrões —
 * com encargos automáticos desligados, exatamente o comportamento anterior (PD-8).
 */
@Service
public class ConfiguracaoService {

  public record Configuracao(
      boolean encargosAutomaticos,
      @NotNull @DecimalMin("0") @DecimalMax("20") @Digits(integer = 2, fraction = 2) BigDecimal multaPercentual,
      @NotNull @DecimalMin("0") @DecimalMax("20") @Digits(integer = 2, fraction = 2) BigDecimal jurosMensalPercentual,
      @Min(0) @Max(30) int diasCarencia,
      @Min(0) @Max(15) int conciliacaoToleranciaDias,
      @NotNull @DecimalMin("50") @DecimalMax("500") @Digits(integer = 3, fraction = 2) BigDecimal alertaConsumoPercentual) {

    public static final Configuracao PADRAO = new Configuracao(false, new BigDecimal("2.00"), new BigDecimal("1.00"), 0, 3,
        new BigDecimal("100.00"));
  }

  private final JdbcTemplate jdbc;
  private final AuditoriaService auditoria;
  private final Relogio relogio;

  public ConfiguracaoService(JdbcTemplate jdbc, AuditoriaService auditoria, Relogio relogio) {
    this.jdbc = jdbc;
    this.auditoria = auditoria;
    this.relogio = relogio;
  }

  @Transactional(readOnly = true)
  public Configuracao atual() {
    List<Configuracao> l = jdbc.query("""
        SELECT encargos_automaticos, multa_percentual, juros_mensal_percentual, dias_carencia,
               conciliacao_tolerancia_dias, alerta_consumo_percentual
          FROM financeiro.configuracoes WHERE tenant_id = ?""",
        (rs, i) -> new Configuracao(rs.getBoolean(1), rs.getBigDecimal(2), rs.getBigDecimal(3), rs.getInt(4), rs.getInt(5),
            rs.getBigDecimal(6)),
        TenantContexto.exigir());
    return l.isEmpty() ? Configuracao.PADRAO : l.get(0);
  }

  @Transactional
  public Configuracao salvar(Configuracao c) {
    Configuracao antes = atual();
    Timestamp agora = Timestamp.from(relogio.agora());
    jdbc.update("""
        INSERT INTO financeiro.configuracoes (tenant_id, encargos_automaticos, multa_percentual, juros_mensal_percentual,
               dias_carencia, conciliacao_tolerancia_dias, alerta_consumo_percentual, created_at, updated_at, updated_by)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (tenant_id) DO UPDATE SET encargos_automaticos = EXCLUDED.encargos_automaticos,
               multa_percentual = EXCLUDED.multa_percentual, juros_mensal_percentual = EXCLUDED.juros_mensal_percentual,
               dias_carencia = EXCLUDED.dias_carencia, conciliacao_tolerancia_dias = EXCLUDED.conciliacao_tolerancia_dias,
               alerta_consumo_percentual = EXCLUDED.alerta_consumo_percentual,
               updated_at = EXCLUDED.updated_at, updated_by = EXCLUDED.updated_by""",
        TenantContexto.exigir(), c.encargosAutomaticos(), c.multaPercentual(), c.jurosMensalPercentual(), c.diasCarencia(),
        c.conciliacaoToleranciaDias(), c.alertaConsumoPercentual(), agora, agora, Usuarios.atual());
    auditoria.registrar("ALTERAR_CONFIGURACAO", "configuracao", TenantContexto.exigir(), antes, c);
    return atual();
  }
}
