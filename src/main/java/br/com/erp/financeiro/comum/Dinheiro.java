package br.com.erp.financeiro.comum;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Toda aritmética monetária é feita em centavos inteiros (long) para evitar erro de arredondamento. */
public final class Dinheiro {

  private Dinheiro() {}

  public static long centavos(BigDecimal valor) {
    return valor == null ? 0 : valor.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
  }

  public static BigDecimal reais(long centavos) {
    return BigDecimal.valueOf(centavos, 2);
  }

  /** Arredondamento "meio para cima", igual ao Math.round do cálculo original. */
  public static long arredondar(BigDecimal valor) {
    return valor.setScale(0, RoundingMode.HALF_UP).longValueExact();
  }

  /** Percentual com duas casas: parte / todo × 100. */
  public static double percentual(long parte, long todo) {
    return todo == 0 ? 0 : Math.round((double) parte / todo * 10000) / 100.0;
  }
}
