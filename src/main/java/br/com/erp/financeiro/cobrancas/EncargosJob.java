package br.com.erp.financeiro.cobrancas;

import br.com.erp.financeiro.tenant.TenantContexto;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Job diário de juros e multa (§24): roda antes da régua, para os avisos saírem com o saldo atualizado. */
@Component
public class EncargosJob {

  private static final Logger log = LoggerFactory.getLogger(EncargosJob.class);

  private final EncargosService encargos;
  private final JdbcTemplate jdbc;
  private final boolean ativo;

  public EncargosJob(EncargosService encargos, JdbcTemplate jdbc, @Value("${financeiro.jobs.ativos:true}") boolean ativo) {
    this.encargos = encargos;
    this.jdbc = jdbc;
    this.ativo = ativo;
  }

  @Scheduled(cron = "${financeiro.jobs.encargos-cron:0 45 6 * * *}", zone = "${financeiro.jobs.fuso}")
  public void agendado() {
    if (ativo) executar();
  }

  public int executar() {
    int total = 0;
    List<UUID> tenants = jdbc.queryForList("SELECT tenant_id FROM financeiro.configuracoes WHERE encargos_automaticos", UUID.class);
    for (UUID tenant : tenants) {
      try {
        total += TenantContexto.calcular(tenant, encargos::recalcular);
      } catch (RuntimeException e) {
        log.warn("Encargos do tenant {} não recalculados: {}", tenant, e.getMessage());
      }
    }
    if (total > 0) log.info("Encargos: {} cobrança(s) atualizada(s).", total);
    return total;
  }
}
