package br.com.erp.financeiro.inadimplencia;

import br.com.erp.financeiro.comum.Dinheiro;
import java.math.BigDecimal;
import java.util.List;

/**
 * Encargos por atraso calculados dinamicamente (não persistidos): multa única + juros simples pro rata die.
 * Padrões: multa 2% (teto do CDC, art. 52 §1º) e juros de mora de 1% ao mês — configuráveis por ambiente.
 */
public final class Encargos {

  private Encargos() {}

  public record Valores(BigDecimal multa, BigDecimal juros, BigDecimal valorAtualizado) {}

  public static Valores calcular(long saldoCent, long diasAtraso, BigDecimal multaPercentual, BigDecimal jurosMensalPercentual) {
    if (diasAtraso <= 0 || saldoCent <= 0) return new Valores(BigDecimal.ZERO, BigDecimal.ZERO, Dinheiro.reais(saldoCent));
    long multa = Math.round(saldoCent * multaPercentual.doubleValue() / 100);
    long juros = Math.round(saldoCent * (jurosMensalPercentual.doubleValue() / 100) * diasAtraso / 30);
    return new Valores(Dinheiro.reais(multa), Dinheiro.reais(juros), Dinheiro.reais(saldoCent + multa + juros));
  }

  /** Faixas de aging (dias de atraso). */
  public static final List<String> FAIXAS = List.of("a_vencer", "1_30", "31_60", "61_90", "acima_90");

  public static String faixaDe(long dias) {
    if (dias <= 0) return "a_vencer";
    if (dias <= 30) return "1_30";
    if (dias <= 60) return "31_60";
    if (dias <= 90) return "61_90";
    return "acima_90";
  }
}
