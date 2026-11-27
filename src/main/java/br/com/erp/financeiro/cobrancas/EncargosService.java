package br.com.erp.financeiro.cobrancas;

import br.com.erp.financeiro.auditoria.AuditoriaService;
import br.com.erp.financeiro.comum.Dinheiro;
import br.com.erp.financeiro.comum.Relogio;
import br.com.erp.financeiro.configuracoes.ConfiguracaoService;
import br.com.erp.financeiro.configuracoes.ConfiguracaoService.Configuracao;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Juros e multa automáticos (§24, decisão do PD-8 agora configurável). Só roda com
 * encargos_automaticos ligado no tenant. Regra:
 *
 * <ul>
 *   <li>base = valor − desconto − já pago (nunca negativa);</li>
 *   <li>multa = base × multa% — uma vez, a partir do primeiro dia de atraso após a carência;</li>
 *   <li>juros = base × (juros mensal% ÷ 30) × dias de atraso após a carência (juros simples, pró-rata dia).</li>
 * </ul>
 *
 * Cobrança com juros ou multa digitados à mão (encargos_manuais) nunca é recalculada.
 */
@Service
public class EncargosService {

  private static final BigDecimal CEM = BigDecimal.valueOf(100);
  private static final BigDecimal TRINTA = BigDecimal.valueOf(30);

  private final CobrancaRepository cobrancas;
  private final ConfiguracaoService configuracoes;
  private final AuditoriaService auditoria;
  private final Relogio relogio;

  public EncargosService(CobrancaRepository cobrancas, ConfiguracaoService configuracoes, AuditoriaService auditoria,
      Relogio relogio) {
    this.cobrancas = cobrancas;
    this.configuracoes = configuracoes;
    this.auditoria = auditoria;
    this.relogio = relogio;
  }

  public record Encargos(BigDecimal juros, BigDecimal multa) {}

  /** Cálculo puro, também usado pela tela para simular. */
  public static Encargos calcular(Cobranca c, Configuracao cfg, LocalDate hoje) {
    long dias = c.diasAtraso(hoje) - cfg.diasCarencia();
    if (dias <= 0) return new Encargos(Dinheiro.ZERO, Dinheiro.ZERO);
    BigDecimal base = c.principal().subtract(c.getValorPago());
    if (base.signum() <= 0) return new Encargos(c.getJuros(), c.getMulta());
    BigDecimal multa = base.multiply(cfg.multaPercentual()).divide(CEM, 2, RoundingMode.HALF_EVEN);
    BigDecimal juros = base.multiply(cfg.jurosMensalPercentual()).multiply(BigDecimal.valueOf(dias))
        .divide(CEM.multiply(TRINTA), 2, RoundingMode.HALF_EVEN);
    return new Encargos(juros, multa);
  }

  /** Recalcula as cobranças vencidas do tenant do contexto. @return quantas mudaram */
  @Transactional
  public int recalcular() {
    Configuracao cfg = configuracoes.atual();
    if (!cfg.encargosAutomaticos()) return 0;
    LocalDate hoje = relogio.hoje();
    int alteradas = 0;
    List<Cobranca> vencidas = cobrancas.vencidasEm(
        List.of(StatusCobranca.ABERTO, StatusCobranca.PARCIAL, StatusCobranca.ATRASADO, StatusCobranca.AGENDADO), hoje);
    for (Cobranca c : vencidas) {
      if (c.isEncargosManuais()) continue;
      if (c.getStatus() == StatusCobranca.AGENDADO && c.getAgendadaPara() != null && !c.getAgendadaPara().isBefore(hoje)) continue;
      Encargos e = calcular(c, cfg, hoje);
      if (e.juros().compareTo(c.getJuros()) == 0 && e.multa().compareTo(c.getMulta()) == 0) continue;
      Map<String, Object> antes = c.instantaneo();
      c.aplicarEncargos(e.juros(), e.multa(), hoje);
      auditoria.registrar("CALCULAR_ENCARGOS", CobrancaService.ENTIDADE, c.getId(), antes, c.instantaneo());
      alteradas++;
    }
    return alteradas;
  }
}
